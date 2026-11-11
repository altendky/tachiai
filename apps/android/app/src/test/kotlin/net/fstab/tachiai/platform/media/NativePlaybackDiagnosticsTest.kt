package net.fstab.tachiai.platform.media

import org.junit.Assert.*
import org.junit.Test

class NativePlaybackDiagnosticsTest {
    private fun sample(time: Long = 0, clock: Long = 20_000, frames: Long? = 10) =
        NativePlaybackDiagnosticSnapshot(time, 0, false, true, true, 3, 0, null,
            clock, true, frames, frames?.let { 0L }, frames?.let { 1L }, null, null, null, null)

    @Test fun `clock stall is an observation only after the full continuous playing interval`() {
        val tracker = NativePlaybackLivenessTracker()
        assertEquals(NativePlaybackLivenessState.OBSERVING, tracker.observe(sample()).state)
        assertEquals(NativePlaybackLivenessState.OBSERVING, tracker.observe(sample(4_999)).state)
        val stalled = tracker.observe(sample(5_000))
        assertEquals(NativePlaybackLivenessState.NO_CLOCK_PROGRESS, stalled.state)
        assertEquals(5_000L, stalled.clockProgressAgeMs)
        assertEquals(5_000L, stalled.renderProgressAgeMs)
        assertEquals(NativePlaybackLivenessState.PROGRESSING, tracker.observe(sample(5_001, 20_001, 11)).state)
    }

    @Test fun `clock progression without renderer progress is distinct from an unknown renderer`() {
        val tracker = NativePlaybackLivenessTracker()
        tracker.observe(sample())
        assertEquals(NativePlaybackLivenessState.OBSERVING, tracker.observe(sample(4_999, 24_999)).state)
        assertEquals(NativePlaybackLivenessState.NO_RENDER_PROGRESS, tracker.observe(sample(5_000, 25_000)).state)
        assertEquals(NativePlaybackLivenessState.OBSERVING, tracker.observe(sample(6_000, 26_000, null)).state)
        assertEquals(NativePlaybackLivenessState.CLOCK_PROGRESS_ONLY, tracker.observe(sample(7_000, 27_000, null)).state)
    }

    @Test fun `held buffering suppressed and not playing SDK states never become a decoder stall`() {
        for ((value, expected) in listOf(
            sample().copy(playWhenReady = false, playing = false) to NativePlaybackLivenessState.HELD,
            sample().copy(state = 2) to NativePlaybackLivenessState.BUFFERING,
            sample().copy(playing = false, suppressionReason = 1) to NativePlaybackLivenessState.SUPPRESSED,
            sample().copy(playing = false) to NativePlaybackLivenessState.READY_NOT_PLAYING,
            sample().copy(state = 1) to NativePlaybackLivenessState.IDLE,
            sample().copy(state = 4) to NativePlaybackLivenessState.ENDED,
            sample().copy(errorCode = 2001) to NativePlaybackLivenessState.ERROR,
            sample().copy(clockMs = null) to NativePlaybackLivenessState.CLOCK_UNAVAILABLE,
            sample().copy(sampleAgeMs = 1) to NativePlaybackLivenessState.CACHED,
            sample().copy(released = true) to NativePlaybackLivenessState.RELEASED,
        )) {
            val tracker = NativePlaybackLivenessTracker()
            tracker.observe(sample())
            assertEquals(expected, tracker.observe(value.copy(observedAtElapsedMs = 60_000)).state)
            assertEquals(NativePlaybackLivenessState.OBSERVING, tracker.observe(sample(60_001)).state)
        }
    }

    @Test fun `decoder clock kind counter and monotonic clock discontinuities restart the grace interval`() {
        for (value in listOf(sample(5_000, 19_000), sample(5_000).copy(liveClock = false),
                sample(5_000).copy(decoderGeneration = 2), sample(5_000).copy(renderedFrames = 9), sample(0))) {
            val tracker = NativePlaybackLivenessTracker()
            tracker.observe(sample(1))
            assertEquals(NativePlaybackLivenessState.OBSERVING, tracker.observe(value).state)
            assertEquals(0L, tracker.observe(value).clockProgressAgeMs)
        }
        val tracker = NativePlaybackLivenessTracker()
        assertEquals(NativePlaybackLivenessState.UNAVAILABLE, tracker.observe(null).state)
        assertEquals(NativePlaybackLivenessState.UNAVAILABLE, tracker.observe(sample(-1)).state)
    }

    @Test fun `decoder totals are monotonic across replacement and reset with factual progress ages`() {
        val tracker = NativeVideoCounterTracker()
        val first = Any(); val second = Any()
        assertEquals(NativeVideoDiagnosticCounters(10, 2, 1, 0), tracker.observe(first, 10, 2, 100))
        assertEquals(NativeVideoDiagnosticCounters(10, 3, 1, 50), tracker.observe(first, 10, 3, 150))
        assertEquals(NativeVideoDiagnosticCounters(11, 3, 1, 0), tracker.observe(first, 11, 3, 160))
        assertEquals(NativeVideoDiagnosticCounters(11, 3, 2, null), tracker.observe(second, 0, 0, 170))
        assertEquals(NativeVideoDiagnosticCounters(13, 4, 2, 0), tracker.observe(second, 2, 1, 180))
        assertEquals(NativeVideoDiagnosticCounters(13, 4, 3, null), tracker.observe(second, 0, 0, 190))
        assertNull(tracker.observe(null, null, null, 200).renderedFrames)
        assertNull(tracker.observe(second, -1, 0, 200).renderedFrames)
        assertEquals(NativeVideoDiagnosticCounters(14, 4, 3, 0), tracker.observe(second, 1, 0, 210))
    }
}
