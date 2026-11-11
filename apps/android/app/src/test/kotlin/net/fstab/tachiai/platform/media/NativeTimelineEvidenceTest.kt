package net.fstab.tachiai.platform.media

import org.junit.Assert.*
import org.junit.Test

class NativeTimelineEvidenceTest {
    private fun sample(sequence: Long, time: Long = 100_000 + sequence * 5_000, discontinuity: Long = 0) =
        NativeHlsTimelineSample(sequence, time, 5_000, discontinuity)
    private fun hls(vararg sequences: Long) = NativeTimelineEvidence.Hls(sequences.map { sample(it) })

    @Test fun `overlapping HLS sequence with exact time duration and absolute discontinuity seems consistent`() {
        assertEquals(NativeTimelineConsistency.CONSISTENT, compareNativeTimelineEvidence(hls(10, 11, 12), hls(11, 12, 13)))
        val before = NativeTimelineEvidence.Hls(listOf(sample(10, discontinuity = 2), sample(11, discontinuity = 3)))
        val after = NativeTimelineEvidence.Hls(listOf(sample(11, discontinuity = 3), sample(12, discontinuity = 3)))
        assertEquals(NativeTimelineConsistency.CONSISTENT, compareNativeTimelineEvidence(before, after))
    }

    @Test fun `same sequence conflicting time duration or discontinuity is changed even with another match`() {
        for (changed in listOf(sample(11).copy(startTimeMs = 1_000_000), sample(11).copy(durationMs = 4_000),
                sample(11).copy(discontinuity = 1))) {
            val current = NativeTimelineEvidence.Hls(listOf(sample(10), changed, sample(12)))
            assertEquals(NativeTimelineConsistency.CHANGED, compareNativeTimelineEvidence(hls(10, 11), current))
        }
    }

    @Test fun `an appended discontinuity changes the witness despite matching older overlap`() {
        val saved = NativeTimelineEvidence.Hls(listOf(sample(10), sample(11)))
        val current = NativeTimelineEvidence.Hls(listOf(sample(11), sample(12, discontinuity = 1)))
        assertEquals(NativeTimelineConsistency.CHANGED, compareNativeTimelineEvidence(saved, current))
        val sameEndDiscontinuity = NativeTimelineEvidence.Hls(listOf(sample(11), sample(12)))
        assertEquals(NativeTimelineConsistency.CONSISTENT, compareNativeTimelineEvidence(saved, sameEndDiscontinuity))
    }

    @Test fun `HLS rollback is changed but adjacent or far nonoverlapping program times are unknown`() {
        assertEquals(NativeTimelineConsistency.CHANGED, compareNativeTimelineEvidence(hls(10, 11, 12), hls(9, 10, 11)))
        assertEquals(NativeTimelineConsistency.CHANGED, compareNativeTimelineEvidence(hls(100, 101), hls(0, 1)))
        assertEquals(NativeTimelineConsistency.UNKNOWN, compareNativeTimelineEvidence(hls(10, 11), hls(12, 13)))
        assertEquals(NativeTimelineConsistency.UNKNOWN, compareNativeTimelineEvidence(hls(10, 11), hls(500, 501)))
    }

    @Test fun `DASH requires the same epoch and current period origin without using period IDs`() {
        val before = NativeTimelineEvidence.Dash(1_000_000, 20_000)
        assertEquals(NativeTimelineConsistency.CONSISTENT,
            compareNativeTimelineEvidence(before, NativeTimelineEvidence.Dash(1_000_000, 20_000)))
        assertEquals(NativeTimelineConsistency.CHANGED,
            compareNativeTimelineEvidence(before, NativeTimelineEvidence.Dash(1_000_001, 20_000)))
        assertEquals(NativeTimelineConsistency.CHANGED,
            compareNativeTimelineEvidence(before, NativeTimelineEvidence.Dash(1_000_000, 30_000)))
    }

    @Test fun `replay duration is only a same-resource heuristic and missing or cross-kind evidence is unknown`() {
        val replay = NativeTimelineEvidence.Replay(60_000)
        assertEquals(NativeTimelineConsistency.CONSISTENT, compareNativeTimelineEvidence(replay, replay.copy()))
        assertEquals(NativeTimelineConsistency.CHANGED,
            compareNativeTimelineEvidence(replay, NativeTimelineEvidence.Replay(59_000)))
        for ((before, after) in listOf(null to replay, replay to null, replay to hls(1),
                NativeTimelineEvidence.Dash(1_000_000, 0) to hls(1)))
            assertEquals(NativeTimelineConsistency.UNKNOWN, compareNativeTimelineEvidence(before, after))
    }

    @Test fun `HLS conversion uses checked absolute program time and discontinuity numbers`() {
        assertEquals(NativeHlsTimelineSample(11, 1_005_000, 5_000, 3),
            nativeHlsTimelineSample(11, 1_000_000_000, 5_000_000, 5_000_000, 2, 1))
        assertNull(nativeHlsTimelineSample(1, Long.MAX_VALUE, 1, 5_000_000, 0, 0))
        assertNull(nativeHlsTimelineSample(1, 1_000_000, 0, 5_000, Long.MAX_VALUE, 1))
        assertNull(nativeHlsTimelineSample(1, -1, 0, 5_000, 0, 0))
        assertNull(nativeHlsTimelineSample(1, 1_000_000, -1, 5_000, 0, 0))
        assertNull(nativeHlsTimelineSample(1, 1_000_000, 0, 999, 0, 0))
    }

    @Test fun `evidence is bounded validated and has redacted string representations`() {
        assertThrows(IllegalArgumentException::class.java) { NativeTimelineEvidence.Hls(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { NativeTimelineEvidence.Hls((0L..8L).map { sample(it) }) }
        assertThrows(IllegalArgumentException::class.java) { NativeTimelineEvidence.Hls(listOf(sample(1), sample(1))) }
        assertThrows(IllegalArgumentException::class.java) { NativeTimelineEvidence.Dash(Long.MAX_VALUE, 1) }
        assertThrows(IllegalArgumentException::class.java) { NativeTimelineEvidence.Replay(0) }
        assertThrows(IllegalArgumentException::class.java) { NativeHlsTimelineSample(1, Long.MAX_VALUE, 1, 0) }
        val mutable = mutableListOf(sample(1))
        val before = NativeTimelineEvidence.Hls(mutable)
        mutable.clear()
        assertEquals(1, before.samples.size)
        assertEquals(NativeTimelineConsistency.CONSISTENT, compareNativeTimelineEvidence(before, hls(1)))
        assertThrows(UnsupportedOperationException::class.java) { (before.samples as MutableList<NativeHlsTimelineSample>).clear() }
        for (evidence in listOf(hls(12345), NativeTimelineEvidence.Dash(12345, 0), NativeTimelineEvidence.Replay(12345)))
            assertFalse(evidence.toString().contains("12345"))
        assertFalse(sample(12345).toString().contains("12345"))
    }
}
