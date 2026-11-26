package net.fstab.tachiai.platform.media

import java.io.IOException
import java.io.InputStream
import java.io.FilterInputStream
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

// Own resources as they are allocated, including a partially built player.
// A released flag or repeated no-op close is not confirmation of failed cleanup.
internal class NativeCleanupReceipt(private val onUnconfirmed: () -> Unit = {}) : AutoCloseable {
    private enum class State { OPEN, SEALED, CLOSING, CONFIRMED, FAILED }
    private var state = State.OPEN
    private val lock = Any()
    private var failureObserved = false
    private var allocations = 0
    private class Action(val order: Int, val ownerThread: Boolean, val run: () -> Unit)
    private val actions = mutableListOf<Action>()
    // Allocated resources whose owner may still release them normally. Once a
    // normal release is confirmed while admission is open, its reservation is
    // dropped so long live playback does not retain every past request.
    private val reservations = IdentityHashMap<Any, Action>()
    internal val retainedActionCount get() = synchronized(lock) { actions.size }
    private var asynchronous = false
    // #730: Media3 release that reported its timeout may still finish on its
    // playback thread. Asynchronous cleanup waits for that bounded evidence.
    private var lateReleases = 0
    private var deferredFinish: (() -> Unit)? = null
    val pending get() = synchronized(lock) { asynchronous }
    val confirmed get() = synchronized(lock) { state == State.CONFIRMED }
    private fun notifyFailure() { runCatching(onUnconfirmed) }
    fun failed() {
        synchronized(lock) { failureObserved = true; if (state == State.CONFIRMED) state = State.FAILED }
        notifyFailure()
    }
    // Media3 release may report timeout through its listener and still return normally.
    fun playerError(releasing: Boolean, errorCode: Int) {
        if (releasing && errorCode == NATIVE_PLAYER_RELEASE_TIMEOUT_CODE) failed()
    }
    // A player release that timed out waits for late completion evidence only
    // while asynchronous cleanup is closing; confirmation stays deferred until
    // the returned one-shot resolver reports it. Elsewhere the caller fails.
    fun awaitLateRelease(): ((Boolean) -> Unit)? {
        synchronized(lock) {
            if (!asynchronous || state != State.CLOSING) return null
            lateReleases++
        }
        val resolved = AtomicBoolean()
        return { completed -> if (resolved.compareAndSet(false, true)) lateReleaseResolved(completed) }
    }
    // The pending finish reports a failed late release on the owner executor.
    private fun lateReleaseResolved(completed: Boolean) {
        val resume = synchronized(lock) {
            if (!completed) failureObserved = true
            lateReleases--
            if (lateReleases == 0) deferredFinish.also { deferredFinish = null } else null
        }
        resume?.invoke()
    }
    // Internal Media3/timer callbacks must return so queued owner disposal can
    // run. External close still throws and rejects uncertain confirmation.
    fun stopInternally(onFailure: NativeFailureObserver, close: () -> Unit) {
        try { close() } catch (error: Throwable) {
            failed()
            reportNativeFailure(NativeFailureStage.PLAYER_RELEASE, onFailure, error)
        }
    }
    fun seal() {
        val uncertain = synchronized(lock) {
            if (state == State.OPEN) state = State.SEALED
            failureObserved
        }
        if (uncertain) notifyFailure()
    }
    fun own(order: Int, ownerThread: Boolean = false, action: () -> Unit) {
        val refused = synchronized(lock) {
            actions.add(Action(order, ownerThread, action))
            if (state != State.OPEN) {
                failureObserved = true
                if (state != State.SEALED) state = State.FAILED
                true
            } else false
        }
        if (refused) { notifyFailure(); throw IOException("Late native ownership refused") }
    }
    fun <T> allocate(order: Int, factory: () -> T, release: (T) -> Unit): T {
        val completed = CountDownLatch(1)
        var releaseResource: (() -> Unit)? = null
        val action = Action(order, ownerThread = false) {
            completed.await()
            releaseResource?.invoke()
        }
        synchronized(lock) {
            if (state != State.OPEN) throw IOException("Native allocation admission ended")
            allocations++
            // Reserve ownership before the factory starts. Request cancellation
            // runs first on the worker and can unblock an input factory; its
            // eventual resource must close before the player is released.
            actions.add(action)
        }
        var resource: T? = null
        var produced = false
        try {
            val owned: T = factory()
            resource = owned
            produced = true
            releaseResource = { release(owned) }
            // An admitted factory may finish after sealing, but its caller may
            // no longer use the resource. The reservation still owns its close.
            synchronized(lock) {
                if (state != State.OPEN) throw IOException("Native allocation admission ended")
                if (owned != null) reservations[owned] = action
            }
            return owned
        } finally {
            synchronized(lock) {
                allocations--
                // Nothing was produced (or nothing to release): an open receipt
                // has no resource to retain for this reservation.
                if (state == State.OPEN && (!produced || resource == null)) actions.remove(action)
            }
            completed.countDown()
        }
    }
    // The owner confirmed a normal release of an allocated resource. Only an
    // open receipt forgets it; sealing or closing keeps the final close owner.
    fun released(resource: Any) {
        synchronized(lock) {
            if (state != State.OPEN) return
            val action = reservations.remove(resource) ?: return
            actions.remove(action)
        }
    }
    fun input(factory: () -> InputStream): InputStream =
        allocate(25, { OwnedInput(factory(), ::failed, ::released) }) { it.close() }
    fun optionalInput(factory: () -> InputStream?): InputStream? =
        allocate(25, { factory()?.let { OwnedInput(it, ::failed, ::released) } }) { it?.close() }

    override fun close() {
        val captured = synchronized(lock) {
            if (state == State.CONFIRMED) return
            if (state !in setOf(State.OPEN, State.SEALED)) throw IOException("Native cleanup remains unconfirmed")
            if (allocations != 0) {
                state = State.SEALED
                throw IOException("Native allocations still stopping; asynchronous cleanup required")
            }
            state = State.CLOSING
            actions.sortedBy { it.order }.map { it.run }
        }
        try {
            closeNativeResources(*captured.toTypedArray())
            synchronized(lock) {
                if (state != State.CLOSING || failureObserved) throw IOException("Native cleanup remains unconfirmed")
                state = State.CONFIRMED
                actions.clear()
                reservations.clear()
            }
        } catch (error: Throwable) {
            synchronized(lock) { state = State.FAILED }
            notifyFailure()
            throw error
        }
    }

    // Preserve release order without doing transport I/O on the player looper
    // or blocking that looper waiting for a worker. The receipt owns every action
    // until all lanes finish; failure/rejection never becomes confirmation.
    fun closeAsync(worker: Executor, owner: Executor, onComplete: () -> Unit, onFailure: (Throwable) -> Unit) {
        val captured = synchronized(lock) {
            if (asynchronous || state == State.CONFIRMED) return
            if (state !in setOf(State.OPEN, State.SEALED)) throw IOException("Native cleanup remains unconfirmed")
            state = State.CLOSING
            asynchronous = true
            actions.sortedBy { it.order }
        }
        val groups = mutableListOf<MutableList<Action>>()
        captured.forEach { action ->
            if (groups.lastOrNull()?.first()?.ownerThread != action.ownerThread) groups.add(mutableListOf())
            groups.last().add(action)
        }
        var failure: Throwable? = null
        fun remember(error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
        fun finish() {
            val error = synchronized(lock) {
                if (lateReleases > 0) {
                    deferredFinish = {
                        try { owner.execute(::finish) }
                        catch (error: Throwable) { remember(error); finish() }
                    }
                    return
                }
                if (state != State.CLOSING || failureObserved || failure != null) {
                    state = State.FAILED
                    failure ?: IOException("Native cleanup remains unconfirmed")
                } else {
                    state = State.CONFIRMED
                    actions.clear()
                    reservations.clear()
                    null
                }.also { asynchronous = false }
            }
            if (error != null) { notifyFailure(); runCatching { onFailure(error) } }
            runCatching(onComplete)
        }
        fun dispatch(index: Int) {
            if (index == groups.size) {
                try { owner.execute(::finish) }
                catch (error: Throwable) { remember(error); finish() }
                return
            }
            val group = groups[index]
            val executor = if (group.first().ownerThread) owner else worker
            try { executor.execute {
                group.forEach { action -> try { action.run() } catch (error: Throwable) { remember(error) } }
                dispatch(index + 1)
            } } catch (error: Throwable) {
                // A rejected lane's resources stay retained. Continue later
                // lanes on their required executor, never on this caller.
                remember(error)
                dispatch(index + 1)
            }
        }
        dispatch(0)
    }
    override fun toString() = "NativeCleanupReceipt(status only)"
}

// A successfully closed stream is not closed twice; a failed close remains
// strongly owned and cannot become a successful repeated no-op.
private class OwnedInput(input: InputStream, private val failed: () -> Unit,
    private val released: (Any) -> Unit) : FilterInputStream(input) {
    private var attempted = false
    private var confirmed = false
    @Synchronized override fun close() {
        if (attempted) {
            if (!confirmed) throw IOException("Stream cleanup remains unconfirmed")
            return
        }
        attempted = true
        try { super.close(); confirmed = true }
        catch (error: Throwable) { failed(); throw error }
        released(this)
    }
}
