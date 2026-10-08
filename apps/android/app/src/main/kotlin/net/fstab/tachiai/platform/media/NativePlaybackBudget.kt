package net.fstab.tachiai.platform.media

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal enum class NativeMediaEvent {
    MANIFEST_BYTES, PLAYLIST_PARSED, MEDIA_BYTES, HTTP_REJECTED, SOURCE_NOT_ALLOWLISTED, REQUEST_FAILED,
    ENCRYPTION_UNSUPPORTED, PLAYLIST_UNSUPPORTED, LIMIT_REACHED, BUFFERING, READY, PLAYING, PAUSED,
    VIDEO_FRAME, ENDED, PLAYER_FAILED, STOPPED,
}

// A media budget, not an OAuth/token expiry promise. No credential held here.
internal class NativePlaybackBudget(
    remainingRetentionMs: Long,
    private val canContinue: () -> Boolean,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    maximumDurationMs: Long = 120_000L,
) {
    init { require(maximumDurationMs in 1..300_000L) }
    private val started = clockMs()
    private val duration = minOf(maximumDurationMs, maxOf(0L, remainingRetentionMs))
    private val stopped = AtomicBoolean(false)
    val active: Boolean get() = !stopped.get() && canContinue() &&
        clockMs() >= started && clockMs() - started < duration
    val remainingMs: Long get() {
        val now = clockMs()
        if (stopped.get() || !canContinue() || now < started) return 0
        return (duration - (now - started)).coerceAtLeast(0)
    }
    // Temporary request groups own their child, not the shared presentation.
    // Parent invalidation/expiry still gates it; creating a child never renews time.
    fun child(): NativePlaybackBudget = NativePlaybackBudget(remainingMs, { active }, clockMs,
        maximumDurationMs = 300_000)
    fun stop() { stopped.set(true) }
    fun check() { if (!active) throw IOException("Playback budget ended") }
}

internal fun unencryptedHls(text: String): Boolean = text.startsWith("#EXTM3U") &&
    text.lineSequence().map { it.trim() }.none { line ->
        line.startsWith("#EXT-X-SESSION-KEY:") ||
            (line.startsWith("#EXT-X-KEY:") && line.substringAfter(':').trim() != "METHOD=NONE")
    }
