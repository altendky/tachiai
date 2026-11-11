package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeHlsTimelineSample
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeTimelineConsistency
import net.fstab.tachiai.platform.media.NativeTimelineEvidence
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.platform.media.nativeMarkedAlignmentPlan
import org.junit.Assert.*
import org.junit.Test

class PairOffsetTimelineTest {
    private val aTimeline = NativeTimelineEvidence.Dash(1_000_000, 0)
    private val bTimeline = NativeTimelineEvidence.Dash(9_000_000, 0)
    private fun live(origin: Long, position: Long, duration: Long = 60_000) =
        NativeTimingSnapshot(position, duration, position, null, origin, null,
            true, true, true, true, false, false, false, 3, 1f)
    private fun replay(position: Long, duration: Long = 60_000) =
        live(0, position, duration).copy(live = false, dynamic = false, windowStartMs = null)
    private fun saved(residual: Long = 0) = PairOffsetCorrespondence(1_001_000, 9_009_000,
        true, true, aTimeline, bTimeline, residual)
    private fun check(correspondence: PairOffsetCorrespondence? = saved(),
        a: NativeTimingSnapshot? = live(1_000_000, 5_000), b: NativeTimingSnapshot? = live(9_000_000, 12_000),
        at: NativeTimelineEvidence? = aTimeline, bt: NativeTimelineEvidence? = bTimeline) =
        checkPairOffsetTimeline(correspondence, a, b, at, bt)

    @Test fun `matching timeline witnesses compare progress from each unrelated own origin`() {
        // A progressed 4 s from its mark, B 3 s: A is 1 s ahead, so B needs 1 s advance.
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.CONSISTENT, -1_000), check())
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.CONSISTENT, 250), check(saved(1_250)))
        // Dynamic-only remains the same sliding/content clock kind.
        assertEquals(check(), check(a = live(1_000_000, 5_000).copy(live = false, dynamic = true)))
    }

    @Test fun `new initial player positions use correspondence rather than repeating the saved scalar`() {
        val preference = PairOffsetPreference(26_400, true, saved())
        val result = check(preference.correspondence, a = live(1_000_000, 35_000), b = live(9_000_000, 40_000))
        // Progress of 34 s versus 31 s requires -3 s, regardless of the prior 26.4 s request.
        assertEquals(-3_000L, result.correctionMs)
        assertNotEquals(preference.offsetMs, result.correctionMs)
        assertEquals(NativeTimelineConsistency.CONSISTENT, result.consistency)
    }

    @Test fun `old marks may be outside DVR while the newly computed correction has legal current targets`() {
        val correspondence = PairOffsetCorrespondence(1_001_000, 9_001_000, true, true, aTimeline, bTimeline)
        val a = live(1_060_000, 10_000)
        val b = live(9_060_000, 9_000)
        assertTrue(correspondence.aMs < a.windowStartMs!!)
        assertTrue(correspondence.bMs < b.windowStartMs!!)
        val result = check(correspondence, a, b)
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.CONSISTENT, -1_000), result)
        val plan = nativeMarkedAlignmentPlan(a, b, checkNotNull(result.correctionMs))
        assertEquals(NativeSeekOutcome.REQUESTED, plan.outcome)
        assertTrue(plan.targets.all { it.targetMs in 0L..60_000L })
        assertTrue(plan.targets.none { it.targetMs == correspondence.aMs || it.targetMs == correspondence.bMs })
    }

    @Test fun `one unknown or changed witness blocks timestamp correction and changed takes precedence`() {
        val hls = NativeTimelineEvidence.Hls(listOf(NativeHlsTimelineSample(10, 1_000_000, 5_000, 0)))
        val correspondence = saved().copy(bTimeline = hls)
        val unknown = NativeTimelineEvidence.Hls(listOf(NativeHlsTimelineSample(11, 1_005_000, 5_000, 0)))
        val changed = NativeTimelineEvidence.Hls(listOf(NativeHlsTimelineSample(10, 1_000_001, 5_000, 0)))
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.UNKNOWN), check(correspondence, bt = unknown))
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.UNKNOWN), check(at = null))
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.CHANGED), check(correspondence, at = null, bt = changed))
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.CHANGED), check(bt = NativeTimelineEvidence.Dash(9_000_001, 0)))
    }

    @Test fun `clock kind changes unavailable clocks readiness and ads never yield a correction`() {
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.CHANGED), check(a = replay(5_000)))
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.CHANGED), check(b = replay(12_000)))
        for (invalid in listOf(live(1_000_000, 5_000).copy(windowStartMs = null),
                live(1_000_000, 5_000).copy(state = 2), live(1_000_000, 5_000).copy(playingAd = true),
                live(1_000_000, 5_000).copy(positionMs = null)))
            assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.UNKNOWN), check(a = invalid))
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.UNKNOWN), check(correspondence = null))
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.UNKNOWN), check(a = null))
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.UNKNOWN), check(b = null))
    }

    @Test fun `zero correction is a valid consistent result for paused replay observations`() {
        val timeline = NativeTimelineEvidence.Replay(60_000)
        val correspondence = PairOffsetCorrespondence(1_000, 10_000, false, false, timeline, timeline)
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.CONSISTENT, 0),
            check(correspondence, replay(6_000), replay(15_000), timeline, timeline))
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.CONSISTENT, 0), check(saved(1_000)))
    }

    @Test fun `correction bounds are inclusive and checked arithmetic cannot wrap into a permitted seek`() {
        val duration = MAX_PAIR_OFFSET_MS + 10
        val replayTimeline = NativeTimelineEvidence.Replay(duration)
        val correspondence = PairOffsetCorrespondence(0, 0, false, false, replayTimeline, replayTimeline)
        for (delta in listOf(-MAX_PAIR_OFFSET_MS, MAX_PAIR_OFFSET_MS)) {
            val a = replay(if (delta < 0) -delta else 0, duration)
            val b = replay(if (delta > 0) delta else 0, duration)
            assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.CONSISTENT, delta),
                check(correspondence, a, b, replayTimeline, replayTimeline))
        }
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.UNKNOWN),
            check(correspondence, replay(MAX_PAIR_OFFSET_MS + 1, duration), replay(0, duration), replayTimeline, replayTimeline))

        val epoch = NativeTimelineEvidence.Dash(0, 0)
        val differenceOverflow = PairOffsetCorrespondence(0, Long.MAX_VALUE, true, true, epoch, epoch)
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.UNKNOWN),
            check(differenceOverflow, live(0, Long.MAX_VALUE, Long.MAX_VALUE), live(0, 0), epoch, epoch))
        val inverseOverflow = PairOffsetCorrespondence(Long.MAX_VALUE, 0, true, true, epoch, epoch)
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.UNKNOWN),
            check(inverseOverflow, live(0, 0), live(0, 1), epoch, epoch))
        assertEquals(PairOffsetTimelineCheck(NativeTimelineConsistency.UNKNOWN),
            check(a = live(Long.MAX_VALUE, 1)))
    }
}
