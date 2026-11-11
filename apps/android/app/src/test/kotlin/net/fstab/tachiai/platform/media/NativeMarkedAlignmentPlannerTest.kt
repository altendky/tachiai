package net.fstab.tachiai.platform.media

import org.junit.Assert.*
import org.junit.Test

class NativeMarkedAlignmentPlannerTest {
    private fun live(position: Long, duration: Long = 60_000, origin: Long = 1_000_000) =
        NativeTimingSnapshot(position, duration, position, null, origin, null,
            true, true, true, true, false, false, false, 3, 1f)
    private fun replay(position: Long) = live(position).copy(live = false, dynamic = false, windowStartMs = null)
    private fun exact(plan: NativeAlignmentPlan, delta: Long) {
        assertEquals(NativeSeekOutcome.REQUESTED, plan.outcome)
        assertEquals(delta, plan.targets.sumOf { if (it.side == NativeMixedSide.A) it.movementMs else -it.movementMs })
    }

    @Test fun `safer exact single B wins over A near its live left edge`() {
        val plan = nativeMarkedAlignmentPlan(live(11_000), live(20_000, origin = 9_000_000), -10_000)
        exact(plan, -10_000)
        val target = plan.targets.single()
        assertEquals(NativeMixedSide.B, target.side)
        assertEquals(30_000L, target.targetMs)
        assertEquals(9_030_000L, target.targetClockMs)
    }

    @Test fun `healthy preferred single is retained when splitting has worse live margin`() {
        val plan = nativeMarkedAlignmentPlan(live(20_000), live(50_000), 5_000)
        exact(plan, 5_000)
        assertEquals(NativeMixedSide.A, plan.targets.single().side)
        assertEquals(25_000L, plan.targets.single().targetMs)
    }

    @Test fun `exact boundary single gives way to a materially safer interior split`() {
        val plan = nativeMarkedAlignmentPlan(live(20_000), live(50_000), -20_000)
        exact(plan, -20_000)
        assertEquals(2, plan.targets.size)
        assertEquals(5_001L, plan.targets[0].targetMs)
        assertEquals(55_001L, plan.targets[1].targetMs)
        assertTrue(plan.targets.all { it.targetMs > 0 && it.targetMs < 60_000 })
    }

    @Test fun `combined twenty six point four seconds remains valid without a minimum margin`() {
        for (delta in listOf(26_400L, -26_400L)) {
            val a = live(if (delta > 0) 39_500 else 20_500)
            val b = live(if (delta > 0) 10_000 else 50_000, origin = 9_000_000)
            val plan = nativeMarkedAlignmentPlan(a, b, delta)
            exact(plan, delta)
            assertEquals(2, plan.targets.size)
            assertEquals(if (delta > 0) 18_450L else -18_450L, plan.targets[0].movementMs)
            assertEquals(if (delta > 0) -7_950L else 7_950L, plan.targets[1].movementMs)
        }
    }

    @Test fun `replay-only preference and replay alternative avoid unnecessary live seeks`() {
        val replayOnly = nativeMarkedAlignmentPlan(replay(11_000), replay(20_000), -10_000)
        exact(replayOnly, -10_000)
        assertEquals(NativeMixedSide.A, replayOnly.targets.single().side)
        val mixed = nativeMarkedAlignmentPlan(live(20_000), replay(20_000), 5_000)
        exact(mixed, 5_000)
        assertEquals(NativeMixedSide.B, mixed.targets.single().side)
        assertFalse(mixed.targets.single().liveClock)
    }

    @Test fun `one second or less of improvement does not add a second SDK seek`() {
        for (delta in listOf(-2_000L, -200L, 1L, 200L, 2_000L)) {
            val plan = nativeMarkedAlignmentPlan(live(30_000), live(30_000), delta)
            exact(plan, delta)
            assertEquals(NativeMixedSide.A, plan.targets.single().side)
        }
    }

    @Test fun `missing clocks stale positions capability ads and overflow never create a candidate`() {
        val a = live(20_500)
        val b = live(50_000, origin = 9_000_000)
        assertEquals(NativeSeekOutcome.NO_CHANGE, nativeMarkedAlignmentPlan(a, b, 0).outcome)
        assertEquals(NativeSeekOutcome.AD_BLOCKED,
            nativeMarkedAlignmentPlan(a.copy(playingAd = true), b, -26_400).outcome)
        assertEquals(NativeSeekOutcome.UNAVAILABLE,
            nativeMarkedAlignmentPlan(a.copy(state = 2), b, -26_400).outcome)
        for (invalid in listOf(a.copy(positionMs = -1), a.copy(positionMs = 60_001),
                a.copy(windowStartMs = null), a.copy(seekable = false), a.copy(durationMs = null),
                a.copy(windowStartMs = Long.MAX_VALUE))) {
            assertEquals(NativeSeekOutcome.UNAVAILABLE, nativeMarkedAlignmentPlan(invalid, b, -26_400).outcome)
        }
        for (delta in listOf(Long.MIN_VALUE, Long.MAX_VALUE))
            assertEquals(NativeSeekOutcome.UNAVAILABLE, nativeMarkedAlignmentPlan(a, b, delta).outcome)
        val insufficient = nativeMarkedAlignmentPlan(a, live(59_000), -26_400)
        assertEquals(NativeSeekOutcome.UNAVAILABLE, insufficient.outcome)
        assertEquals(21_500L, insufficient.availableMagnitudeMs)
        val boundary = nativeMarkedAlignmentPlan(live(0), live(60_000), 120_000)
        exact(boundary, 120_000)
        assertEquals(listOf(60_000L, 0L), boundary.targets.map { it.targetMs })
    }
}
