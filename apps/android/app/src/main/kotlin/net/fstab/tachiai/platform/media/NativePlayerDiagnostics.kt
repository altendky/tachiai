package net.fstab.tachiai.platform.media

import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.platform.network.ProviderTransportFailureCarrier
import net.fstab.tachiai.platform.network.ProviderTransportFailure
import net.fstab.tachiai.platform.network.ProviderTransportExceptionKind

// A recorded player failure owns its transport projection, including an
// explicitly cleared local/policy cause. Preparation evidence is a fallback
// only before the player has recorded a failure of its own.
internal fun latestNativePlaybackTransport(snapshot: NativePlaybackDiagnosticSnapshot?,
    early: NativePlaybackTransportFailure?): NativePlaybackTransportFailure? =
    if (snapshot?.failureStage != null) snapshot.transportFailure
    else listOfNotNull(early, snapshot?.transportFailure).minByOrNull { it.ageMs }

// Main-thread SDK sampling plus worker-safe, cause-free failure projections.
// Decoder counters describe Media3's renderer output, not screen presentation.
@UnstableApi
internal class NativePlayerDiagnostics(private val clockMs: () -> Long = SystemClock::elapsedRealtime) {
    private data class Failure(val stage: NativeFailureStage, val at: Long)
    private data class MediaFailure(val stage: NativeFailureStage, val at: Long,
        val transport: net.fstab.tachiai.platform.network.ProviderTransportEvidence?, val httpStatus: Int?)
    private data class FailureState(val failure: Failure? = null, val media: MediaFailure? = null,
        val terminalRecorded: Boolean = false, val terminalMedia: MediaFailure? = null,
        val terminalVeto: MediaFailure? = null)
    private val failures = AtomicReference(FailureState())
    private val errorCode = AtomicReference<Int?>()
    private val playerFailure = AtomicReference<NativePlayerFailure?>()
    private val periodBoundaryAt = AtomicReference<Long?>()
    private val cached = AtomicReference<NativePlaybackDiagnosticSnapshot?>()
    private val video = NativeVideoCounterTracker()

    @Synchronized fun recordFailure(stage: NativeFailureStage, error: Throwable) {
        val now = clockMs()
        if (error is PlaybackException) {
            errorCode.set(error.errorCode)
            // The first fatal error owns the record; cleanup cannot replace it.
            if (stage == NativeFailureStage.PLAYER_ERROR)
                runCatching { nativePlayerFailure(error) }.getOrNull()?.let { playerFailure.compareAndSet(null, it) }
        }
        val safeTransport = (error as? NativeMediaRequestException)?.transportEvidence
            ?: (error as? ProviderTransportFailureCarrier)?.transportFailure
        val mediaObserved = stage in setOf(NativeFailureStage.MEDIA_REQUEST_OPEN, NativeFailureStage.MEDIA_REQUEST_READ) || safeTransport != null
        val http = (error as? NativeMediaRequestException)?.httpStatus
        val protected = http in setOf(401, 403) || safeTransport?.let {
            !it.localCancellationRequested && (it.failure == ProviderTransportFailure.TLS ||
                it.exceptionKind in setOf(ProviderTransportExceptionKind.TLS_HANDSHAKE,
                    ProviderTransportExceptionKind.TLS_PEER, ProviderTransportExceptionKind.TLS_OTHER))
        } == true
        failures.updateAndGet { previous ->
            // One immutable record replaces both projections. A local/policy
            // failure clears old HTTP and transport authority; a delayed older
            // callback cannot resurrect either after a newer refusal.
            val terminal = stage == NativeFailureStage.PLAYER_ERROR && !previous.terminalRecorded
            FailureState(
                if (previous.failure == null || now >= previous.failure.at) Failure(stage, now) else previous.failure,
                if (mediaObserved && (previous.media == null || now >= previous.media.at))
                    MediaFailure(stage, now, safeTransport, http) else previous.media,
                previous.terminalRecorded || terminal, if (terminal) previous.media else previous.terminalMedia,
                if (previous.terminalRecorded && mediaObserved && protected)
                    MediaFailure(stage, now, safeTransport, http) else previous.terminalVeto)
        }
    }
    fun recordPlayerError(code: Int) { errorCode.set(code) }
    // #722: the player crossed into another period, or the period structure
    // of its live manifest changed.
    fun recordPeriodBoundary() { periodBoundaryAt.set(clockMs()) }

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
        val state = failures.get()
        val latest = state.failure
        // Native teardown can report inactive requests after a player error.
        // The fatal player owns its already-observed request cause, including
        // an explicitly empty local/policy projection, through that cleanup.
        val media = state.terminalVeto ?: if (state.terminalRecorded) state.terminalMedia else state.media
        return value.copy(failureStage = latest?.stage, failureAgeMs = nativeDiagnosticAge(now, latest?.at),
            playerFailure = playerFailure.get(), periodBoundaryAgeMs = nativeDiagnosticAge(now, periodBoundaryAt.get()),
            transportFailure = media?.let { evidence -> evidence.transport?.let { transport ->
                nativeDiagnosticAge(now, evidence.at)?.let { age -> NativePlaybackTransportFailure(evidence.stage, age, transport) }
            } }, httpFailure = media?.let { evidence -> evidence.httpStatus?.let { status ->
                nativeDiagnosticAge(now, evidence.at)?.let { age -> NativePlaybackHttpFailure(evidence.stage, age, status) }
            } })
    }
}
