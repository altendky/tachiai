package net.fstab.tachiai.platform.media

import java.io.IOException
import java.io.InputStream
import java.io.FilterInputStream
import java.util.concurrent.Executor

// Own resources as they are allocated, including a partially built player.
// A released flag or repeated no-op close is not confirmation of failed cleanup.
internal class NativeCleanupReceipt(private val onUnconfirmed: () -> Unit = {}) : AutoCloseable {
    private enum class State { OPEN, SEALED, CLOSING, CONFIRMED, FAILED }
    private var state = State.OPEN
    private val lock = Any()
    private var failureObserved = false
    private var allocations = 0
    private data class Action(val order: Int, val ownerThread: Boolean, val run: () -> Unit)
    private val actions = mutableListOf<Action>()
    private var asynchronous = false
    val pending get() = synchronized(lock) { asynchronous }
    val confirmed get() = synchronized(lock) { state == State.CONFIRMED }
    private fun notifyFailure() { runCatching(onUnconfirmed) }
    fun failed() {
        synchronized(lock) { failureObserved = true; if (state == State.CONFIRMED) state = State.FAILED }
        notifyFailure()
    }
    // Media3 release may report timeout through its listener and still return normally.
    fun playerError(releasing: Boolean, errorCode: Int) {
        if (releasing && errorCode == 1003) failed()
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
            if (allocations != 0) failureObserved = true
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
        synchronized(lock) {
            if (state != State.OPEN) throw IOException("Native allocation admission ended")
            allocations++
        }
        try {
            val resource = factory()
            own(order) { release(resource) }
            return resource
        } finally { synchronized(lock) { allocations-- } }
    }
    fun input(factory: () -> InputStream): InputStream =
        allocate(25, { OwnedInput(factory(), ::failed) }) { it.close() }
    fun optionalInput(factory: () -> InputStream?): InputStream? =
        allocate(25, { factory()?.let { OwnedInput(it, ::failed) } }) { it?.close() }

    override fun close() {
        val captured = synchronized(lock) {
            if (state == State.CONFIRMED) return
            if (state !in setOf(State.OPEN, State.SEALED)) throw IOException("Native cleanup remains unconfirmed")
            if (allocations != 0) failureObserved = true
            state = State.CLOSING
            actions.sortedBy { it.order }.map { it.run }
        }
        try {
            closeNativeResources(*captured.toTypedArray())
            synchronized(lock) {
                if (state != State.CLOSING || failureObserved) throw IOException("Native cleanup remains unconfirmed")
                state = State.CONFIRMED
                actions.clear()
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
            if (allocations != 0) failureObserved = true
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
                if (state != State.CLOSING || failureObserved || failure != null) {
                    state = State.FAILED
                    failure ?: IOException("Native cleanup remains unconfirmed")
                } else {
                    state = State.CONFIRMED
                    actions.clear()
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
private class OwnedInput(input: InputStream, private val failed: () -> Unit) : FilterInputStream(input) {
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
    }
}
