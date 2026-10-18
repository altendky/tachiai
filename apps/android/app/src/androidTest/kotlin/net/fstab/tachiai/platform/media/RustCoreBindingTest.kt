package net.fstab.tachiai.platform.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Provider-free tests of the production Android facade through generated native
// bindings. No saved account, route, stream, media player or network is touched.
class RustCoreBindingTest {
    private val sample = NativeTimingSnapshot(20_000, 60_000, 30_000, 10_000,
        1_000_000, 50_000, true, true, true, true, false, false, false, 3, 1f)

    @Test fun packagedCorePlansBothDirectionsAndClampsWithoutOverflow() {
        assertEquals(NativeSeekPlan(NativeSeekOutcome.REQUESTED, 25_000), nativeRelativeSeekPlan(sample, 5_000))
        assertEquals(NativeSeekPlan(NativeSeekOutcome.REQUESTED, 15_000), nativeRelativeSeekPlan(sample, -5_000))
        assertEquals(NativeSeekPlan(NativeSeekOutcome.CLAMPED, 60_000), nativeRelativeSeekPlan(sample, Long.MAX_VALUE))
        assertEquals(NativeSeekPlan(NativeSeekOutcome.CLAMPED, 0), nativeRelativeSeekPlan(sample, Long.MIN_VALUE))
    }

    @Test fun packagedCoreDoesNotTurnBackIntoForwardForAMovedWindow() {
        assertEquals(NativeSeekPlan(NativeSeekOutcome.OUTSIDE_WINDOW),
            nativeRelativeSeekPlan(sample.copy(positionMs = -5_000), -5_000))
        assertEquals(NativeSeekPlan(NativeSeekOutcome.OUTSIDE_WINDOW),
            nativeRelativeSeekPlan(sample.copy(positionMs = 65_000), 5_000))
        assertEquals(NativeSeekPlan(NativeSeekOutcome.REQUESTED, 50_000),
            nativeLiveDefaultPlan(sample.copy(positionMs = null, seekable = false)))
    }

    @Test fun packagedCoreKeepsMissingClocksAndRefusalsExplicit() {
        assertEquals(NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE), nativeSeekPlan(null, 5_000))
        assertEquals(NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE),
            nativeSeekPlan(sample.copy(durationMs = null, playingAd = true), 5_000))
        assertEquals(NativeSeekPlan(NativeSeekOutcome.AD_BLOCKED),
            nativeSeekPlan(sample.copy(playingAd = true), 5_000))
        assertEquals(NativeSeekPlan(NativeSeekOutcome.UNSUPPORTED),
            nativeSeekPlan(sample.copy(seekable = false), 5_000))
    }

    @Test fun packagedCoreComputesContentClocksWithCheckedArithmetic() {
        assertEquals(1_020_000L, sample.contentTimeMs)
        assertEquals(995_000L, sample.copy(positionMs = -5_000).contentTimeMs)
        assertNull(sample.copy(windowStartMs = null).contentTimeMs)
        assertNull(sample.copy(windowStartMs = Long.MAX_VALUE).contentTimeMs)
        assertNull(sample.copy(positionMs = Long.MIN_VALUE).contentTimeMs)
    }
}
