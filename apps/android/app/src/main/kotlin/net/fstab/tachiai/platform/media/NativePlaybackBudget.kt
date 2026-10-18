package net.fstab.tachiai.platform.media

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal enum class NativeMediaEvent {
    MANIFEST_BYTES, PLAYLIST_PARSED, MEDIA_BYTES, HTTP_REJECTED, SOURCE_NOT_ALLOWLISTED, REQUEST_FAILED,
    ENCRYPTION_UNSUPPORTED, PLAYLIST_UNSUPPORTED, LIMIT_REACHED, BUFFERING, READY, PLAYING, PAUSED,
    VIDEO_FRAME, ENDED, PLAYER_FAILED, STOPPED,
}

// A media budget, not an OAuth/token expiry promise. No credential held here.
internal class NativePlaybackBudget private constructor(
    private val canContinue: () -> Boolean,
    private val clockMs: () -> Long,
    private val duration: Long?,
) {
    // Historical probes and tests explicitly retain their finite observation window.
    constructor(remainingRetentionMs: Long, canContinue: () -> Boolean,
        clockMs: () -> Long = { System.nanoTime() / 1_000_000 }, maximumDurationMs: Long = 120_000L) :
        this(canContinue, clockMs, minOf(maximumDurationMs, maxOf(0L, remainingRetentionMs))) {
        require(maximumDurationMs in 1..300_000L)
    }
    companion object {
        fun continuous(canContinue: () -> Boolean,
            clockMs: () -> Long = { System.nanoTime() / 1_000_000 }): NativePlaybackBudget =
            NativePlaybackBudget(canContinue, clockMs, null)
    }
    val isTimeBounded get() = duration != null
    private val started = clockMs()
    private val stopped = AtomicBoolean(false)
    val active: Boolean get() = !stopped.get() && canContinue() &&
        clockMs() >= started && (duration == null || clockMs() - started < duration)
    // Continuous ownership has no elapsed-time deadline. Do not add this sentinel
    // to a clock or schedule a Handler task with it; use deadlineAt/isTimeBounded.
    val remainingMs: Long get() {
        val now = clockMs()
        if (stopped.get() || !canContinue() || now < started) return 0
        return duration?.let { (it - (now - started)).coerceAtLeast(0) } ?: Long.MAX_VALUE
    }
    fun deadlineAt(nowMs: Long): Long {
        require(nowMs >= 0)
        val remaining = remainingMs
        return if (remaining > Long.MAX_VALUE - nowMs) Long.MAX_VALUE else nowMs + remaining
    }
    // Temporary request groups own their child, not the shared presentation.
    // Parent invalidation/expiry still gates it; creating a child never renews time.
    fun child(): NativePlaybackBudget = if (isTimeBounded)
        NativePlaybackBudget(remainingMs, { active }, clockMs, maximumDurationMs = 300_000)
        else continuous({ active }, clockMs)
    fun stop() { stopped.set(true) }
    fun check() { if (!active) throw IOException("Playback budget ended") }
}

internal fun unencryptedHls(text: String): Boolean = text.startsWith("#EXTM3U") &&
    text.lineSequence().map { it.trim() }.none { line ->
        line.startsWith("#EXT-X-SESSION-KEY:") ||
            (line.startsWith("#EXT-X-KEY:") && line.substringAfter(':').trim() != "METHOD=NONE")
    }
