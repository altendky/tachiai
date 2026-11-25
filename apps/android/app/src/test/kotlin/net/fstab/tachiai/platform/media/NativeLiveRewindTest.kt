package net.fstab.tachiai.platform.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLiveRewindTest {
    // A live window of 180 s, playing 10 s behind its end (the live point).
    private val live = NativeTimingSnapshot(170_000, 180_000, 190_000, 10_000, 1_000_000, 170_000,
        true, true, true, true, false, false, false, 3, 1f)

    @Test fun `span keeps the floor, grows with the delay and stops at the cap`() {
        // Floor: the provider window plus 2 minutes.
        assertEquals(180_000L, liveRewindSpanMs(60_000, 0))
        assertEquals(180_000L, liveRewindSpanMs(60_000, 150_000))
        assertEquals(150_000L, liveRewindSpanMs(30_000, 6_000))
        // Growth: the distance behind live plus 30 s.
        assertEquals(230_000L, liveRewindSpanMs(60_000, 200_000))
        assertEquals(330_000L, liveRewindSpanMs(30_000, 300_000))
        // Cap: a 6:00 delay plus its margin, however far behind the feed is.
        assertEquals(390_000L, liveRewindSpanMs(60_000, 360_000))
        assertEquals(390_000L, liveRewindSpanMs(60_000, 3_600_000))
        // Never shorter than the provider's own window.
        assertEquals(600_000L, liveRewindSpanMs(600_000, 0))
        // Nonsense inputs are bounded.
        assertEquals(180_000L, liveRewindSpanMs(60_000, -5))
        assertEquals(390_000L, liveRewindSpanMs(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test fun `behind live prefers the continuous live offset`() {
        assertEquals(12_000L, nativeBehindLiveMs(12_000, 180_000, 170_000))
        assertEquals(10_000L, nativeBehindLiveMs(null, 180_000, 170_000))
        assertEquals(200_000L, nativeBehindLiveMs(null, 180_000, -20_000))
        assertNull(nativeBehindLiveMs(null, null, 170_000))
        assertNull(nativeBehindLiveMs(null, 0, 0))
        assertNull(nativeBehindLiveMs(null, 180_000, 190_000))
    }

    @Test fun `delay excludes the player's normal live latency`() {
        assertEquals(0L, nativeLiveDelayMs(live))
        val held = live.copy(positionMs = 50_000, liveOffsetMs = 130_000)
        assertEquals(120_000L, nativeLiveDelayMs(held))
        assertEquals(240_000L, nativeLiveDelayRoomMs(held))
        // Without the live offset, the default position is the live point.
        assertEquals(120_000L, nativeLiveDelayMs(held.copy(liveOffsetMs = null)))
        assertNull(nativeLiveDelayMs(live.copy(live = false, dynamic = false)))
        assertNull(nativeLiveDelayRoomMs(live.copy(live = false, dynamic = false)))
    }

    @Test fun `a delay past six minutes is refused, forward movement never is`() {
        val held = live.copy(positionMs = 50_000, liveOffsetMs = 130_000) // 2:00 delay
        assertFalse(nativeLiveDelayExceeded(held, -240_000))
        assertTrue(nativeLiveDelayExceeded(held, -240_001))
        assertFalse(nativeLiveDelayExceeded(held, 60_000))
        assertTrue(nativeLiveDelayExceeded(held, Long.MIN_VALUE))
        val atLimit = live.copy(positionMs = 0, liveOffsetMs = 370_000)
        assertTrue(nativeLiveDelayExceeded(atLimit, -1))
        assertFalse(nativeLiveDelayExceeded(live.copy(live = false, dynamic = false), -3_600_000))
        assertEquals(LIVE_DELAY_LIMIT_MESSAGE, "Delay is limited to 6:00")
    }

    @Test fun `a lone feed paused past its oldest point resumes just inside the window`() {
        val stale = live.copy(positionMs = -20_000, liveOffsetMs = 400_000)
        val plan = checkNotNull(nativeOldestResumePlan(mapOf(NativeMixedSide.A to stale)))
        assertEquals(listOf(NativeOldestResumeTarget(NativeMixedSide.A, LIVE_OLDEST_RESUME_MARGIN_MS)), plan.targets)
        assertTrue(plan.keepsOffset)
        assertEquals(setOf(NativeMixedSide.A), plan.stale)
        assertEquals("The feed passed the oldest recorded point while paused, so playback resumed there.",
            nativeOldestResumeNotice(plan, pair = false))
        // A very short window resumes in its middle.
        val short = stale.copy(durationMs = 6_000)
        assertEquals(3_000L, nativeOldestResumePlan(mapOf(NativeMixedSide.A to short))!!.targets.single().targetMs)
    }

    @Test fun `a pair moves together and keeps its offset when both can`() {
        val a = live.copy(positionMs = -20_000)
        val b = live.copy(positionMs = 40_000)
        val plan = checkNotNull(nativeOldestResumePlan(mapOf(NativeMixedSide.A to a, NativeMixedSide.B to b)))
        assertTrue(plan.keepsOffset)
        assertEquals(listOf(NativeOldestResumeTarget(NativeMixedSide.A, 5_000), NativeOldestResumeTarget(NativeMixedSide.B, 65_000)),
            plan.targets)
        assertEquals("A passed the oldest recorded point while paused, so playback resumed there.",
            nativeOldestResumeNotice(plan, pair = true))
        // Both stale: the further one sets the movement.
        val both = checkNotNull(nativeOldestResumePlan(mapOf(NativeMixedSide.A to a, NativeMixedSide.B to b.copy(positionMs = -2_000))))
        assertEquals(listOf(5_000L, 23_000L), both.targets.map { it.targetMs })
        assertEquals("Both feeds passed the oldest recorded point while paused, so playback resumed there.",
            nativeOldestResumeNotice(both, pair = true))
    }

    @Test fun `a pair whose other feed cannot move as far moves only the stale feed`() {
        val a = live.copy(positionMs = -20_000)
        val b = live.copy(positionMs = 170_000)
        val plan = checkNotNull(nativeOldestResumePlan(mapOf(NativeMixedSide.A to a, NativeMixedSide.B to b)))
        assertFalse(plan.keepsOffset)
        assertEquals(listOf(NativeOldestResumeTarget(NativeMixedSide.A, 5_000)), plan.targets)
        assertEquals("A passed the oldest recorded point while paused, so playback resumed there; the offset was not kept.",
            nativeOldestResumeNotice(plan, pair = true))
    }

    @Test fun `nothing to resume or nothing repairable returns no plan`() {
        assertNull(nativeOldestResumePlan(mapOf(NativeMixedSide.A to live)))
        val stale = live.copy(positionMs = -20_000)
        for (unrepairable in listOf(stale.copy(seekable = false), stale.copy(state = 2), stale.copy(playingAd = true),
            stale.copy(durationMs = null), stale.copy(live = false, dynamic = false)))
            assertNull(nativeOldestResumePlan(mapOf(NativeMixedSide.A to unrepairable)))
        // A partner ahead of its window end, or unknown, is not repaired here.
        assertNull(nativeOldestResumePlan(mapOf(NativeMixedSide.A to stale, NativeMixedSide.B to live.copy(positionMs = 190_000))))
        assertNull(nativeOldestResumePlan(mapOf(NativeMixedSide.A to stale, NativeMixedSide.B to live.copy(positionMs = null))))
    }
}
