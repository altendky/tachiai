package net.fstab.tachiai.platform.media

import net.fstab.tachiai.platform.network.ProviderTransportEvidence

// Fixed scalars only. clockMs is an in-memory own-clock comparison input, not
// a provider timestamp to persist. No identity, format, URI or exception object.
internal data class NativePlaybackTransportFailure(val stage: NativeFailureStage, val ageMs: Long,
    val evidence: ProviderTransportEvidence)
internal data class NativePlaybackDiagnosticSnapshot(
    val observedAtElapsedMs: Long,
    val sampleAgeMs: Long,
    val released: Boolean,
    val playWhenReady: Boolean,
    val playing: Boolean,
    val state: Int,
    val suppressionReason: Int,
    val errorCode: Int?,
    val clockMs: Long?,
    val liveClock: Boolean?,
    val renderedFrames: Long?,
    val droppedFrames: Long?,
    val decoderGeneration: Long?,
    val renderProgressAgeMs: Long?,
    val failureStage: NativeFailureStage?,
    val failureAgeMs: Long?,
    val transportFailure: NativePlaybackTransportFailure?,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val bufferedMs: Long? = null,
)

internal enum class NativePlaybackLivenessState {
    UNAVAILABLE, CACHED, RELEASED, ERROR, IDLE, BUFFERING, ENDED, HELD, SUPPRESSED,
    READY_NOT_PLAYING, CLOCK_UNAVAILABLE, OBSERVING, PROGRESSING, CLOCK_PROGRESS_ONLY,
    NO_CLOCK_PROGRESS, NO_RENDER_PROGRESS,
}
internal data class NativePlaybackLivenessObservation(val state: NativePlaybackLivenessState,
    val clockProgressAgeMs: Long? = null, val renderProgressAgeMs: Long? = null)

// Observations, never fault attribution, recovery authority or a watchdog.
// Hosts reset the tracker when the member changes. Decoder replacement, clock
// reversal/kind changes and inactive transport start a fresh grace interval.
internal class NativePlaybackLivenessTracker(private val stallThresholdMs: Long = 5_000) {
    init { require(stallThresholdMs > 0) }
    private var previous: NativePlaybackDiagnosticSnapshot? = null
    private var lastClockProgress: Long? = null
    private var lastRenderProgress: Long? = null
    private var clockAdvanced = false
    private var renderAdvanced = false

    fun reset() {
        previous = null; lastClockProgress = null; lastRenderProgress = null
        clockAdvanced = false; renderAdvanced = false
    }
    fun observe(value: NativePlaybackDiagnosticSnapshot?): NativePlaybackLivenessObservation {
        fun inactive(state: NativePlaybackLivenessState): NativePlaybackLivenessObservation {
            reset(); return NativePlaybackLivenessObservation(state)
        }
        if (value == null || value.observedAtElapsedMs < 0 || value.sampleAgeMs < 0)
            return inactive(NativePlaybackLivenessState.UNAVAILABLE)
        if (value.released) return inactive(NativePlaybackLivenessState.RELEASED)
        if (value.errorCode != null) return inactive(NativePlaybackLivenessState.ERROR)
        if (value.sampleAgeMs != 0L) return inactive(NativePlaybackLivenessState.CACHED)
        when (value.state) {
            1 -> return inactive(NativePlaybackLivenessState.IDLE)
            2 -> return inactive(NativePlaybackLivenessState.BUFFERING)
            4 -> return inactive(NativePlaybackLivenessState.ENDED)
            3 -> Unit
            else -> return inactive(NativePlaybackLivenessState.UNAVAILABLE)
        }
        if (!value.playWhenReady) return inactive(NativePlaybackLivenessState.HELD)
        if (value.suppressionReason > 0) return inactive(NativePlaybackLivenessState.SUPPRESSED)
        if (!value.playing) return inactive(NativePlaybackLivenessState.READY_NOT_PLAYING)
        val clock = value.clockMs
        if (clock == null || clock < 0 || value.liveClock == null)
            return inactive(NativePlaybackLivenessState.CLOCK_UNAVAILABLE)
        val before = previous
        val now = value.observedAtElapsedMs
        val newWindow = before == null || now < before.observedAtElapsedMs ||
            before.liveClock != value.liveClock || before.clockMs == null || clock < before.clockMs ||
            before.decoderGeneration != value.decoderGeneration ||
            before.renderedFrames != null && value.renderedFrames != null && value.renderedFrames < before.renderedFrames
        if (newWindow) {
            lastClockProgress = now
            lastRenderProgress = now.takeIf { value.renderedFrames != null }
            clockAdvanced = false; renderAdvanced = false
        } else {
            if (clock > checkNotNull(before?.clockMs)) { lastClockProgress = now; clockAdvanced = true }
            val previousRendered = before?.renderedFrames
            if (value.renderedFrames == null) { lastRenderProgress = null; renderAdvanced = false }
            else if (previousRendered == null) lastRenderProgress = now
            else if (value.renderedFrames > previousRendered) { lastRenderProgress = now; renderAdvanced = true }
        }
        previous = value
        val clockAge = nativeDiagnosticAge(now, lastClockProgress)
        val renderAge = nativeDiagnosticAge(now, lastRenderProgress)
        val state = when {
            clockAge != null && clockAge >= stallThresholdMs -> NativePlaybackLivenessState.NO_CLOCK_PROGRESS
            renderAge != null && renderAge >= stallThresholdMs -> NativePlaybackLivenessState.NO_RENDER_PROGRESS
            clockAdvanced && value.renderedFrames == null -> NativePlaybackLivenessState.CLOCK_PROGRESS_ONLY
            clockAdvanced && renderAdvanced -> NativePlaybackLivenessState.PROGRESSING
            else -> NativePlaybackLivenessState.OBSERVING
        }
        return NativePlaybackLivenessObservation(state, clockAge, renderAge)
    }
}

internal fun nativeDiagnosticAge(now: Long, began: Long?): Long? {
    if (began == null || now < 0 || began < 0 || now < began) return null
    return try { Math.subtractExact(now, began) } catch (_: ArithmeticException) { null }
}

internal data class NativeVideoDiagnosticCounters(val renderedFrames: Long?, val droppedFrames: Long?,
    val decoderGeneration: Long?, val renderProgressAgeMs: Long?)

// DecoderCounters belong to a decoder instance and can reset. Preserve app-
// monotonic totals while exposing generation changes to restart comparisons.
internal class NativeVideoCounterTracker {
    private var identity: Any? = null
    private var rendered = 0
    private var dropped = 0
    private var totalRendered = 0L
    private var totalDropped = 0L
    private var generation = 0L
    private var lastRenderProgress: Long? = null
    fun observe(token: Any?, renderedCount: Int?, droppedCount: Int?, now: Long): NativeVideoDiagnosticCounters {
        if (token == null || renderedCount == null || droppedCount == null || renderedCount < 0 || droppedCount < 0 || now < 0)
            return NativeVideoDiagnosticCounters(null, null, null, null)
        val replaced = identity !== token || renderedCount < rendered || droppedCount < dropped
        if (replaced) {
            generation = saturatedAdd(generation, 1)
            rendered = 0; dropped = 0; lastRenderProgress = null
        }
        identity = token
        val renderedDelta = renderedCount - rendered
        totalRendered = saturatedAdd(totalRendered, renderedDelta.toLong())
        totalDropped = saturatedAdd(totalDropped, (droppedCount - dropped).toLong())
        if (renderedDelta > 0) lastRenderProgress = now
        rendered = renderedCount; dropped = droppedCount
        return NativeVideoDiagnosticCounters(totalRendered, totalDropped, generation, nativeDiagnosticAge(now, lastRenderProgress))
    }
    private fun saturatedAdd(first: Long, second: Long) =
        if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second
}
