package net.fstab.tachiai.platform.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class NativePlaybackTimingTest {
    private val sample = NativeTimingSnapshot(20_000, 60_000, 30_000, 10_000,
        1_000_000, 50_000, true, true, true, true, false, false, false, 3, 1f)

    @Test fun `forward and backward mean later and earlier media`() {
        assertEquals(NativeSeekPlan(NativeSeekOutcome.REQUESTED, 25_000), nativeRelativeSeekPlan(sample, 5_000))
        assertEquals(NativeSeekPlan(NativeSeekOutcome.REQUESTED, 15_000), nativeRelativeSeekPlan(sample, -5_000))
    }
    @Test fun `bounds are clamped not a future or expired-history promise`() {
        assertEquals(NativeSeekPlan(NativeSeekOutcome.CLAMPED, 0), nativeRelativeSeekPlan(sample, -30_000))
        assertEquals(NativeSeekPlan(NativeSeekOutcome.CLAMPED, 60_000), nativeRelativeSeekPlan(sample, 100_000))
        assertEquals(NativeSeekPlan(NativeSeekOutcome.NO_CHANGE, 60_000),
            nativeRelativeSeekPlan(sample.copy(positionMs = 60_000), 5_000))
    }
    @Test fun `missing timeline clocks are unavailable not zero`() {
        assertEquals(NativeSeekOutcome.UNAVAILABLE, nativeRelativeSeekPlan(null, 5_000).outcome)
        for (unknown in listOf(sample.copy(positionMs = null), sample.copy(durationMs = null), sample.copy(durationMs = 0)))
            assertEquals(NativeSeekOutcome.UNAVAILABLE, nativeRelativeSeekPlan(unknown, 5_000).outcome)
        assertEquals(NativeSeekOutcome.REQUESTED, nativeRelativeSeekPlan(sample.copy(liveOffsetMs = null), 5_000).outcome)
    }
    @Test fun `unseekable and advertisements refuse before seeking`() {
        assertEquals(NativeSeekOutcome.UNSUPPORTED, nativeRelativeSeekPlan(sample.copy(seekable = false), 5_000).outcome)
        assertEquals(NativeSeekOutcome.AD_BLOCKED, nativeRelativeSeekPlan(sample.copy(playingAd = true), 5_000).outcome)
    }
    @Test fun `relative arithmetic overflow cannot wrap to earlier content`() {
        assertEquals(60_000L, nativeRelativeSeekPlan(sample, Long.MAX_VALUE).targetMs)
        assertEquals(0L, nativeRelativeSeekPlan(sample, Long.MIN_VALUE).targetMs)
    }
    @Test fun `live catch up is distinct from arbitrary seek and replay`() {
        assertEquals(NativeSeekPlan(NativeSeekOutcome.REQUESTED, 50_000), nativeLiveDefaultPlan(sample.copy(seekable = false)))
        assertEquals(NativeSeekOutcome.UNSUPPORTED, nativeLiveDefaultPlan(sample.copy(live = false)).outcome)
        assertEquals(NativeSeekOutcome.UNSUPPORTED, nativeLiveDefaultPlan(sample.copy(canSeekDefault = false)).outcome)
        assertEquals(NativeSeekOutcome.UNAVAILABLE, nativeLiveDefaultPlan(sample.copy(defaultPositionMs = null)).outcome)
        assertEquals(NativeSeekOutcome.AD_BLOCKED, nativeLiveDefaultPlan(sample.copy(playingAd = true)).outcome)
    }
    @Test fun `stable content clock requires a known window origin and cannot overflow`() {
        assertEquals(1_020_000L, sample.contentTimeMs)
        assertEquals(sample.contentTimeMs, sample.copy(windowStartMs = 1_005_000, positionMs = 15_000).contentTimeMs)
        assertNull(sample.copy(windowStartMs = null).contentTimeMs)
        assertNull(sample.copy(windowStartMs = Long.MAX_VALUE).contentTimeMs)
        assertEquals(995_000L, sample.copy(positionMs = -5_000).contentTimeMs)
        assertNull(sample.copy(positionMs = Long.MIN_VALUE).contentTimeMs)
    }
    @Test fun `live default recovery does not require current position to be inside the moving window`() {
        assertEquals(NativeSeekPlan(NativeSeekOutcome.REQUESTED, 50_000), nativeLiveDefaultPlan(sample.copy(positionMs = -5_000)))
        assertEquals(NativeSeekPlan(NativeSeekOutcome.REQUESTED, 50_000), nativeLiveDefaultPlan(sample.copy(positionMs = null)))
        assertEquals(NativeSeekPlan(NativeSeekOutcome.OUTSIDE_WINDOW), nativeRelativeSeekPlan(sample.copy(positionMs = -5_000), -5_000))
        assertEquals(NativeSeekPlan(NativeSeekOutcome.OUTSIDE_WINDOW), nativeRelativeSeekPlan(sample.copy(positionMs = 65_000), 5_000))
        assertEquals(NativeSeekPlan(NativeSeekOutcome.OUTSIDE_WINDOW), nativeRelativeSeekPlan(sample.copy(positionMs = -5_000), 0))
        assertEquals(NativeSeekPlan(NativeSeekOutcome.REQUESTED, 0), nativeRelativeSeekPlan(sample.copy(positionMs = -5_000), 5_000))
    }
    @Test fun `planning and observing never renew the existing media budget`() {
        var now = 0L
        val budget = NativePlaybackBudget(120_000, { true }, { now })
        now = 119_999
        nativeRelativeSeekPlan(sample, 5_000)
        nativeLiveDefaultPlan(sample)
        now++
        assertFalse(budget.active)
    }
}
