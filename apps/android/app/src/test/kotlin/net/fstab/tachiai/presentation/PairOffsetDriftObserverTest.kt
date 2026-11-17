package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeHlsTimelineSample
import net.fstab.tachiai.platform.media.NativeTimelineEvidence
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import org.junit.Assert.*
import org.junit.Test

class PairOffsetDriftObserverTest {
    private fun replay(position: Long, speed: Float = 1f) = NativeTimingSnapshot(position, Long.MAX_VALUE,
        position, null, null, null, false, false, true, false, false, true, true, 3, speed)
    private fun live(origin: Long, position: Long = 10_000) = replay(position).copy(
        live = true, dynamic = true, windowStartMs = origin, durationMs = 60_000)
    private fun hls(first: Long, origin: Long, discontinuity: Long = 0) = NativeTimelineEvidence.Hls(
        (first..first + 2).map { NativeHlsTimelineSample(it, origin + it * 1_000, 1_000, discontinuity) })
    private class Fixture {
        val observer = PairOffsetDriftObserver()
        var pair: Any? = Any()
        var revision = 0L
        var timing: Any? = null
        fun sample(now: Long, a: NativeTimingSnapshot?, b: NativeTimingSnapshot?,
            ta: NativeTimelineEvidence? = NativeTimelineEvidence.Replay(Long.MAX_VALUE),
            tb: NativeTimelineEvidence? = NativeTimelineEvidence.Replay(Long.MAX_VALUE), blocked: Boolean = false) =
            observer.observe(pair, revision, timing, a, b, ta, tb, now, blocked)
    }

    @Test fun `unrelated replay origins with equal progress have zero drift and retain baseline age`() {
        val f = Fixture()
        val first = f.sample(100, replay(500_000), replay(2_000))
        assertEquals(PairOffsetDriftStatus.MEASURING, first.status)
        assertEquals(0L, first.driftMs); assertEquals(0L, first.baselineAgeMs)
        val later = f.sample(10_100, replay(510_000), replay(12_000))
        assertEquals(0L, later.driftMs); assertEquals(10_000L, later.baselineAgeMs)
        assertEquals(first.segment, later.segment)
    }

    @Test fun `relative progress sign identifies either feed gaining two hundred milliseconds`() {
        for (aAhead in listOf(true, false)) {
            val f = Fixture()
            f.sample(0, replay(500_000), replay(2_000))
            val value = f.sample(10_000, replay(510_000L + if (aAhead) 200L else 0L),
                replay(12_000L + if (aAhead) 0L else 200L))
            assertEquals(if (aAhead) 200L else -200L, value.driftMs)
            assertEquals(PairOffsetDriftStatus.MEASURING, value.status)
        }
    }

    @Test fun `live clocks follow overlapping HLS witnesses long after the baseline playlist rolls away`() {
        val f = Fixture()
        val originA = 1_700_000_000_000L; val originB = 9_000_000_000_000L
        val initial = f.sample(0, live(originA), live(originB), hls(0, originA), hls(0, originB))
        for (sequence in 1L..3_600L) {
            // Window-relative positions stay fixed while independent windows
            // advance. Only A gains an additional 200 ms by the final sample.
            val extra = if (sequence == 3_600L) 200L else 0L
            val value = f.sample(sequence * 1_000, live(originA + sequence * 1_000, 10_000 + extra),
                live(originB + sequence * 1_000), hls(sequence, originA), hls(sequence, originB))
            assertEquals(PairOffsetDriftStatus.MEASURING, value.status)
            assertEquals(extra, value.driftMs)
            assertEquals(initial.segment, value.segment)
        }
    }

    @Test fun `discontinuity and missing overlap retire drift instead of treating program time as proof`() {
        for (changed in listOf(true, false)) {
            val f = Fixture(); val origin = 1_700_000_000_000L
            val first = f.sample(0, live(origin), live(origin), hls(0, origin), hls(0, origin))
            val next = if (changed) hls(1, origin, 1) else hls(20, origin)
            val refused = f.sample(5_000, live(origin + 5_000), live(origin + 5_200), next, next)
            assertEquals(if (changed) PairOffsetDriftStatus.CHANGED else PairOffsetDriftStatus.UNKNOWN, refused.status)
            assertNull(refused.driftMs); assertNull(refused.baselineAgeMs)
            val fresh = f.sample(6_000, live(origin + 6_000), live(origin + 6_200), next, next)
            assertEquals(0L, fresh.driftMs); assertEquals(0L, fresh.baselineAgeMs)
            assertTrue(fresh.segment > first.segment)
        }
    }

    @Test fun `pair identity explicit intent and even unsettled timing dispatch cannot inherit a baseline`() {
        for (change in 0..2) {
            val f = Fixture()
            val first = f.sample(0, replay(500_000), replay(2_000))
            when (change) {
                0 -> f.pair = Any()
                1 -> f.revision++
                else -> f.timing = Any()
            }
            val boundary = f.sample(5_000, replay(520_000), replay(7_000))
            assertEquals(PairOffsetDriftStatus.CHANGED, boundary.status)
            assertNull(boundary.driftMs); assertEquals(0L, boundary.baselineAgeMs)
            assertTrue(boundary.segment > first.segment)
            assertEquals(0L, f.sample(6_000, replay(521_000), replay(8_000)).driftMs)
        }
    }

    @Test fun `pause buffering ads missing snapshots and explicit block retire the segment`() {
        for (mode in 0..5) {
            val f = Fixture()
            val first = f.sample(0, replay(500_000), replay(2_000))
            val held = when (mode) {
                0 -> replay(501_000).copy(playing = false, playWhenReady = false)
                1 -> replay(501_000).copy(state = 2)
                2 -> replay(501_000).copy(playingAd = true)
                3 -> null
                4 -> replay(501_000).copy(playing = false)
                else -> replay(501_000)
            }
            val blocked = f.sample(1_000, held, replay(3_000), blocked = mode == 5)
            assertEquals(PairOffsetDriftStatus.BLOCKED, blocked.status); assertNull(blocked.driftMs)
            val fresh = f.sample(2_000, replay(502_000), replay(3_000))
            assertEquals(0L, fresh.driftMs); assertEquals(0L, fresh.baselineAgeMs)
            assertTrue(fresh.segment > first.segment)
        }
    }

    @Test fun `own clock rollback or live clock kind change cannot appear as measured drift`() {
        for (kindChange in listOf(false, true)) {
            val f = Fixture()
            f.sample(0, replay(500_000), replay(2_000))
            val moved = if (kindChange) live(1_700_000_000_000L) else replay(499_999)
            val result = f.sample(1_000, moved, replay(3_000))
            assertEquals(PairOffsetDriftStatus.CHANGED, result.status); assertNull(result.driftMs)
        }
    }

    @Test fun `missing stable clock or evidence never falls back to comparing unrelated relative positions`() {
        val f = Fixture()
        for (value in listOf(replay(-1), replay(10_000).copy(positionMs = null), live(0).copy(windowStartMs = null))) {
            val result = f.sample(0, value, replay(2_000))
            assertEquals(PairOffsetDriftStatus.UNKNOWN, result.status); assertNull(result.driftMs)
        }
        assertEquals(PairOffsetDriftStatus.UNKNOWN, f.sample(0, replay(20_000), replay(2_000), ta = null).status)
        val fresh = f.sample(1_000, replay(21_000), replay(3_000))
        assertEquals(0L, fresh.driftMs); assertEquals(0L, fresh.baselineAgeMs)
    }

    @Test fun `positive finite nonunit speeds remain observations while invalid speeds block measurement`() {
        val f = Fixture()
        f.sample(0, replay(10_000, 1.02f), replay(2_000, 1f))
        assertEquals(200L, f.sample(10_000, replay(20_200, 1.02f), replay(12_000, 1f)).driftMs)
        for (speed in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val invalid = f.sample(10_000, replay(20_200, speed), replay(12_000))
            assertEquals(PairOffsetDriftStatus.BLOCKED, invalid.status); assertNull(invalid.driftMs)
        }
    }

    @Test fun `time rollback negative inputs and excessive relative movement produce no drift`() {
        val f = Fixture()
        f.sample(10_000, replay(10_000), replay(2_000))
        for (now in listOf(-1L, 9_999L)) {
            val invalid = f.sample(now, replay(11_000), replay(3_000))
            assertEquals(PairOffsetDriftStatus.UNKNOWN, invalid.status)
            assertNull(invalid.driftMs); assertNull(invalid.baselineAgeMs)
        }
        f.sample(10_001, replay(10_001), replay(2_001))
        val overBound = f.sample(10_002, replay(Long.MAX_VALUE), replay(2_002))
        assertEquals(PairOffsetDriftStatus.UNKNOWN, overBound.status); assertNull(overBound.driftMs)
        f.revision = -1
        assertEquals(PairOffsetDriftStatus.UNKNOWN, f.sample(10_003, replay(10_003), replay(2_003)).status)
    }

    @Test fun `clear and absent pair drop baseline ownership and exported samples contain only relative scalars`() {
        val f = Fixture()
        val first = f.sample(0, replay(123_456_789), replay(9_876_543_210))
        f.observer.clear()
        val next = f.sample(100, replay(123_456_999), replay(9_876_543_310))
        assertEquals(0L, next.driftMs); assertTrue(next.segment > first.segment)
        assertFalse(next.toString().contains("123456")); assertFalse(next.toString().contains("987654"))
        f.pair = null
        assertEquals(PairOffsetDriftStatus.UNKNOWN, f.sample(200, replay(0), replay(0)).status)
        f.pair = Any()
        assertEquals(0L, f.sample(300, replay(1), replay(2)).driftMs)
    }
}
