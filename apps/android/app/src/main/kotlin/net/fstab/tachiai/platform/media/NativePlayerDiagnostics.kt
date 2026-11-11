package net.fstab.tachiai.platform.media

import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.platform.network.ProviderTransportFailureCarrier

// Main-thread SDK sampling plus worker-safe, cause-free failure projections.
// Decoder counters describe Media3's renderer output, not screen presentation.
@UnstableApi
internal class NativePlayerDiagnostics(private val clockMs: () -> Long = SystemClock::elapsedRealtime) {
    private data class Failure(val stage: NativeFailureStage, val at: Long)
    private data class Transport(val stage: NativeFailureStage, val at: Long,
        val evidence: net.fstab.tachiai.platform.network.ProviderTransportEvidence)
    private val failure = AtomicReference<Failure?>()
    private val transport = AtomicReference<Transport?>()
    private val errorCode = AtomicReference<Int?>()
    private val cached = AtomicReference<NativePlaybackDiagnosticSnapshot?>()
    private val video = NativeVideoCounterTracker()

    fun recordFailure(stage: NativeFailureStage, error: Throwable) {
        val now = clockMs()
        if (error is PlaybackException) errorCode.set(error.errorCode)
        failure.updateAndGet { previous -> if (previous == null || now >= previous.at) Failure(stage, now) else previous }
        val safeTransport = (error as? NativeMediaRequestException)?.transportEvidence
            ?: (error as? ProviderTransportFailureCarrier)?.transportFailure
        safeTransport?.let { evidence -> transport.updateAndGet { previous ->
            if (previous == null || now >= previous.at) Transport(stage, now, evidence) else previous
        } }
    }
    fun recordPlayerError(code: Int) { errorCode.set(code) }

    fun capture(player: ExoPlayer, timing: NativeTimingSnapshot?): NativePlaybackDiagnosticSnapshot? {
        // An error/release can clear the timeline. Preserve the last complete
        // timing context and its age instead of replacing it with unknown zeros.
        if (timing == null && cached.get()?.liveClock != null) return snapshot(released = false)
        val now = clockMs()
        return try {
            val counters = runCatching {
                if (player.videoFormat == null) null else player.videoDecoderCounters?.also { it.ensureUpdated() }
            }.getOrNull()
            val counts = video.observe(counters, counters?.renderedOutputBufferCount, counters?.droppedBufferCount, now)
            val code = player.playerError?.errorCode ?: errorCode.get()
            if (code != null) errorCode.set(code)
            val value = NativePlaybackDiagnosticSnapshot(now, 0, false, player.playWhenReady,
                player.isPlaying, player.playbackState.takeIf { it in 1..4 } ?: 0,
                player.playbackSuppressionReason.takeIf { it in 0..4 } ?: -1, code,
                timing?.let { if (it.live || it.dynamic) it.contentTimeMs else it.positionMs?.takeIf { position -> position >= 0 } },
                timing?.let { it.live || it.dynamic }, counts.renderedFrames, counts.droppedFrames,
                counts.decoderGeneration, counts.renderProgressAgeMs, null, null, null,
                timing?.positionMs, timing?.durationMs, timing?.bufferedMs)
            cached.set(value)
            withFailures(value, now)
        } catch (_: Exception) { snapshot(released = false) }
    }

    fun snapshot(released: Boolean): NativePlaybackDiagnosticSnapshot? {
        val now = clockMs()
        val value = cached.get() ?: return null
        val age = nativeDiagnosticAge(now, value.observedAtElapsedMs) ?: Long.MAX_VALUE
        return withFailures(value.copy(observedAtElapsedMs = now, sampleAgeMs = age, released = released,
            errorCode = errorCode.get(),
            renderProgressAgeMs = value.renderProgressAgeMs?.let { prior ->
                if (Long.MAX_VALUE - prior < age) Long.MAX_VALUE else prior + age
            }), now)
    }
    private fun withFailures(value: NativePlaybackDiagnosticSnapshot, now: Long): NativePlaybackDiagnosticSnapshot {
        val latest = failure.get()
        val latestTransport = transport.get()
        return value.copy(failureStage = latest?.stage, failureAgeMs = nativeDiagnosticAge(now, latest?.at),
            transportFailure = latestTransport?.let { evidence -> nativeDiagnosticAge(now, evidence.at)?.let { age ->
                NativePlaybackTransportFailure(evidence.stage, age, evidence.evidence)
            } })
    }
}
