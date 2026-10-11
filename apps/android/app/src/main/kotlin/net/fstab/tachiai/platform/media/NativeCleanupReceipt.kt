package net.fstab.tachiai.platform.media

import java.io.IOException
import java.io.InputStream
import java.io.FilterInputStream

// Own resources as they are allocated, including a partially built player.
// A released flag or repeated no-op close is not confirmation of failed cleanup.
internal class NativeCleanupReceipt(private val onUnconfirmed: () -> Unit = {}) : AutoCloseable {
    private enum class State { OPEN, SEALED, CLOSING, CONFIRMED, FAILED }
    private var state = State.OPEN
    private val lock = Any()
    private var failureObserved = false
    private var allocations = 0
    private val actions = mutableListOf<Pair<Int, () -> Unit>>()
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
    fun own(order: Int, action: () -> Unit) {
        val refused = synchronized(lock) {
            actions.add(order to action)
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
            actions.sortedBy { it.first }.map { it.second }
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
