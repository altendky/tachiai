package net.fstab.tachiai.platform.media

import org.junit.Assert.*
import org.junit.Test

class NativeRelativeNudgeTest {
    private val replay = NativeTimingSnapshot(20_000, 60_000, 30_000, null, null, null,
        false, false, true, true, false, false, false, 3, 1f)
    private val live = replay.copy(live = true, dynamic = true, windowStartMs = 1_000_000)

    @Test fun `both signs work in all four source combinations without clock subtraction`() {
        for (a in listOf(replay, live)) for (b in listOf(replay, live.copy(windowStartMs = 9_000_000))) {
            for (step in listOf(100L, 250L, 500L, 1_000L, 5_000L)) for (delta in listOf(-step, step)) {
                assertEquals(NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.SELECTED, NativeMixedSide.A, delta),
                    nativeRelativeNudgePlan(a, b, delta))
            }
        }
    }
    @Test fun `fine steps fall back on B only when A cannot move the whole step`() {
        for (step in listOf(100L, 250L, 500L)) {
            assertEquals(NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.SELECTED, NativeMixedSide.B, -step),
                nativeRelativeNudgePlan(replay.copy(positionMs = 60_000), live, step))
            assertEquals(NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.SELECTED, NativeMixedSide.B, step),
                nativeRelativeNudgePlan(replay.copy(positionMs = 0), live, -step))
            assertEquals(NativeRelativeNudgeOutcome.NO_FULL_STEP,
                nativeRelativeNudgePlan(replay.copy(positionMs = 60_000), live.copy(positionMs = 0), step).outcome)
        }
    }
    @Test fun `fallback uses opposite movement on B when A cannot deliver the whole step`() {
        for (a in listOf(replay.copy(positionMs = 59_000), replay.copy(seekable = false), live.copy(windowStartMs = null)))
            assertEquals(NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.SELECTED, NativeMixedSide.B, -5_000),
                nativeRelativeNudgePlan(a, replay, 5_000))
        assertEquals(NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.SELECTED, NativeMixedSide.B, 5_000),
            nativeRelativeNudgePlan(replay.copy(positionMs = 1_000), live, -5_000))
    }
    @Test fun `partial clamps and exhausted windows never pretend to deliver a full step`() {
        assertEquals(NativeRelativeNudgeOutcome.NO_FULL_STEP,
            nativeRelativeNudgePlan(replay.copy(positionMs = 59_000), replay.copy(positionMs = 1_000), 5_000).outcome)
        assertEquals(NativeRelativeNudgeOutcome.NO_FULL_STEP,
            nativeRelativeNudgePlan(replay.copy(positionMs = 0), replay.copy(positionMs = 60_000), -5_000).outcome)
    }
    @Test fun `stale live position cannot clamp by more than the requested step`() {
        assertEquals(NativeRelativeNudgeOutcome.NO_FULL_STEP,
            nativeRelativeNudgePlan(live.copy(positionMs = -10_000), live.copy(positionMs = -10_000), 5_000).outcome)
        assertEquals(NativeMixedSide.B,
            nativeRelativeNudgePlan(live.copy(positionMs = 70_000), replay, -5_000).side)
    }
    @Test fun `ads missing peers and nonready peers refuse globally`() {
        for ((a, b) in listOf(null to replay, replay to null, replay.copy(state = 2) to replay, replay to live.copy(state = 4)))
            assertEquals(NativeRelativeNudgeOutcome.UNAVAILABLE, nativeRelativeNudgePlan(a, b, 5_000).outcome)
        for ((a, b) in listOf(replay.copy(playingAd = true) to replay, replay to live.copy(playingAd = true)))
            assertEquals(NativeRelativeNudgeOutcome.AD_BLOCKED, nativeRelativeNudgePlan(a, b, 5_000).outcome)
    }
    @Test fun `unknown windows or live content epochs cannot invent capabilities`() {
        for (invalid in listOf(replay.copy(durationMs = null), replay.copy(durationMs = 0),
            replay.copy(positionMs = null), live.copy(windowStartMs = null), live.copy(windowStartMs = -1)))
            assertEquals(NativeRelativeNudgeOutcome.NO_FULL_STEP, nativeRelativeNudgePlan(invalid, invalid, 5_000).outcome)
    }
    @Test fun `zero and arithmetic overflow never dispatch`() {
        assertEquals(NativeRelativeNudgeOutcome.NO_CHANGE, nativeRelativeNudgePlan(replay, live, 0).outcome)
        for (delta in listOf(Long.MIN_VALUE, Long.MAX_VALUE))
            assertEquals(NativeRelativeNudgeOutcome.NO_FULL_STEP, nativeRelativeNudgePlan(replay, live, delta).outcome)
        val overflowing = live.copy(windowStartMs = Long.MAX_VALUE - 21_000)
        assertEquals(NativeRelativeNudgeOutcome.NO_FULL_STEP,
            nativeRelativeNudgePlan(overflowing, overflowing.copy(seekable = false), 5_000).outcome)
    }
}
