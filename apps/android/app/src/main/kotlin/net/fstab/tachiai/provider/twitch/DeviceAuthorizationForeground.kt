package net.fstab.tachiai.provider.twitch

import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

// Platform-neutral, non-sensitive lifecycle state. Updated on the UI thread;
// StateFlow publishes snapshots to the I/O worker without Compose state reads.
internal class DeviceAuthorizationForeground(initiallyForeground: Boolean = true) {
    private data class State(val foreground: Boolean, val pauses: Long)
    private val state = MutableStateFlow(State(initiallyForeground, 0))
    val isForeground: Boolean get() = state.value.foreground
    val pauseRevision: Long get() = state.value.pauses

    fun setForeground(foreground: Boolean) {
        val previous = state.value
        if (previous.foreground != foreground) {
            state.value = State(foreground, previous.pauses + if (foreground) 0 else 1)
        }
    }

    suspend fun awaitForeground(deadlineMs: Long, clockMs: () -> Long): Boolean {
        val now = clockMs()
        if (now >= deadlineMs) return false
        val remaining = try { Math.subtractExact(deadlineMs, now) }
        catch (_: ArithmeticException) { Long.MAX_VALUE }
        if (!isForeground && withTimeoutOrNull(remaining) {
            state.first { it.foreground }
        } == null) return false
        return clockMs() < deadlineMs
    }
}

internal class DeviceRequestPaused : IOException()
