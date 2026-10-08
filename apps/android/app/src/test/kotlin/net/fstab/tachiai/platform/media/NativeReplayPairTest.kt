package net.fstab.tachiai.platform.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeReplayPairTest {
    private val sample = NativeTimingSnapshot(20_000, 60_000, 30_000, null, null, null,
        false, false, true, true, false, false, false, 3, 1f)
    private class Member(var snapshot: NativeTimingSnapshot) : NativePairMember {
        var closed = false
        var volume = 0.5f
        var seekAccepted = true
        override fun timingSnapshot() = if (closed) null else snapshot
        override fun seekToMs(targetMs: Long): NativeSeekPlan {
            if (!seekAccepted) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
            val plan = nativeSeekPlan(snapshot, targetMs)
            plan.targetMs?.let { snapshot = snapshot.copy(positionMs = it) }
            return plan
        }
        override fun setTimingPlaying(playing: Boolean): Boolean {
            if (closed) return false
            snapshot = snapshot.copy(playing = playing, playWhenReady = playing)
            return true
        }
        override fun setVolume(volume: Float): Boolean {
            if (closed || !volume.isFinite() || volume !in 0f..1f) return false
            this.volume = volume
            return true
        }
        override fun close() { closed = true }
    }

    @Test fun `signed offset means A later or earlier than B`() {
        for (offset in listOf(-5_000L, -1_000L, 0L, 1_000L, 5_000L)) {
            val plan = nativeReplayPairPlan(sample, sample, offset)
            assertEquals(NativePairOutcome.REQUESTED, plan.outcome)
            assertEquals(20_000L + offset, plan.aMs)
            assertEquals(20_000L, plan.bMs)
            assertEquals(offset, nativeReplayOffsetMs(sample.copy(positionMs = plan.aMs), sample))
        }
    }
    @Test fun `anchor and bounds refuse offset distortion or overflow`() {
        assertEquals(NativePairPlan(NativePairOutcome.REQUESTED, 35_000, 30_000),
            nativeReplayPairPlan(sample, sample, 5_000, 30_000))
        for (offset in listOf(-30_000L, 50_000L, Long.MAX_VALUE, Long.MIN_VALUE))
            assertEquals(NativePairOutcome.OUT_OF_RANGE, nativeReplayPairPlan(sample, sample, offset).outcome)
        assertEquals(NativePairOutcome.OUT_OF_RANGE, nativeReplayPairPlan(sample, sample, 0, -1).outcome)
        assertEquals(NativePairOutcome.OUT_OF_RANGE, nativeReplayPairPlan(sample, sample, 0, 70_000).outcome)
    }
    @Test fun `unknown live dynamic ads and missing seek refuse before transaction`() {
        assertEquals(NativePairOutcome.UNAVAILABLE, nativeReplayPairPlan(null, sample, 0).outcome)
        assertEquals(NativePairOutcome.UNAVAILABLE, nativeReplayPairPlan(sample.copy(durationMs = null), sample, 0).outcome)
        for (invalid in listOf(sample.copy(live = true), sample.copy(dynamic = true), sample.copy(seekable = false)))
            assertEquals(NativePairOutcome.UNSUPPORTED, nativeReplayPairPlan(sample, invalid, 0).outcome)
        assertEquals(NativePairOutcome.AD_BLOCKED, nativeReplayPairPlan(sample.copy(playingAd = true), sample, 0).outcome)
        assertNull(nativeReplayOffsetMs(sample, sample.copy(live = true)))
        assertNull(nativeReplayOffsetMs(sample.copy(positionMs = -1), sample))
    }
    @Test fun `settled clocks require both paused READY within tolerance`() {
        val plan = nativeReplayPairPlan(sample, sample, 0)
        assertTrue(nativePairSettled(plan, sample.copy(positionMs = 20_100), sample))
        for (invalid in listOf(sample.copy(playing = true), sample.copy(playWhenReady = true),
            sample.copy(state = 2), sample.copy(positionMs = 20_101), sample.copy(playingAd = true)))
            assertFalse(nativePairSettled(plan, sample, invalid))
    }
    @Test fun `focus denial and loss never leave one member playing`() {
        val a = Member(sample); val b = Member(sample)
        var allow = false
        val pair = NativeReplayPair(a, b, { allow }, {})
        assertFalse(pair.play())
        assertFalse(a.snapshot.playing); assertFalse(b.snapshot.playing)
        allow = true
        assertTrue(pair.play())
        pair.align(5_000)
        pair.focusLost()
        pair.poll()
        assertFalse(a.snapshot.playing); assertFalse(b.snapshot.playing)
        assertFalse(pair.busy)
        assertTrue(pair.play()) // Only this explicit action may request focus again.
    }
    @Test fun `paired seek resumes only when both held targets settle`() {
        val a = Member(sample); val b = Member(sample)
        val pair = NativeReplayPair(a, b, { true }, {})
        pair.play(); pair.align(5_000)
        b.snapshot = b.snapshot.copy(state = 2)
        pair.poll()
        assertTrue(pair.busy); assertFalse(a.snapshot.playing)
        b.snapshot = b.snapshot.copy(state = 3)
        pair.poll()
        assertFalse(pair.busy); assertTrue(a.snapshot.playing); assertTrue(b.snapshot.playing)
        assertEquals(5_000L, nativeReplayOffsetMs(a.snapshot, b.snapshot))
    }
    @Test fun `partial rejection timeout manual pause and teardown cancel restoration`() {
        var now = 0L
        val a = Member(sample); val b = Member(sample)
        var released = 0
        val pair = NativeReplayPair(a, b, { true }, { released++ }, { now })
        pair.play(); b.seekAccepted = false
        assertEquals(NativePairOutcome.UNAVAILABLE, pair.align(5_000).outcome)
        assertFalse(a.snapshot.playing); assertFalse(b.snapshot.playing)
        b.seekAccepted = true
        pair.play(); pair.align(1_000)
        now = 8_000
        pair.poll()
        assertFalse(pair.busy); assertFalse(a.snapshot.playing)
        pair.play(); pair.align(0); pair.pause(); pair.poll()
        assertFalse(a.snapshot.playing)
        pair.close(); pair.close(); pair.poll()
        assertEquals(1, released); assertTrue(a.closed); assertTrue(b.closed)
        assertFalse(pair.play())
        assertEquals(NativePairOutcome.UNAVAILABLE, pair.align(0).outcome)
    }
    @Test fun `a paused pair stays paused after alignment and invalid plan leaves playing pair alone`() {
        val a = Member(sample); val b = Member(sample)
        val pair = NativeReplayPair(a, b, { true }, {})
        pair.align(-5_000); pair.poll()
        assertFalse(a.snapshot.playing); assertEquals(-5_000L, pair.requestedOffsetMs)
        pair.play()
        pair.align(Long.MAX_VALUE)
        assertTrue(a.snapshot.playing); assertTrue(b.snapshot.playing)
        assertEquals(-5_000L, pair.requestedOffsetMs)
    }
    @Test fun `throwing member cleanup still closes peer and releases focus`() {
        val broken = object : NativePairMember {
            override fun timingSnapshot() = sample
            override fun seekToMs(targetMs: Long): NativeSeekPlan = throw IllegalStateException()
            override fun setTimingPlaying(playing: Boolean): Boolean = throw IllegalStateException()
            override fun setVolume(volume: Float) = false
            override fun close(): Unit = throw IllegalStateException()
        }
        val b = Member(sample)
        var releases = 0
        val pair = NativeReplayPair(broken, b, { true }, { releases++ })
        assertEquals(NativePairOutcome.UNAVAILABLE, pair.align(5_000).outcome)
        pair.close(); pair.close()
        assertTrue(b.closed); assertEquals(1, releases); assertTrue(pair.cleanupFailed)
    }
}
