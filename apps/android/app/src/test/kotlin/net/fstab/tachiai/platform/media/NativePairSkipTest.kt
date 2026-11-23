package net.fstab.tachiai.platform.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePairSkipTest {
    private fun replay(position: Long, duration: Long = 600_000, playing: Boolean = true) = NativeTimingSnapshot(
        position, duration, position + 10_000, null, null, null, false, false, true, true, false, playing, playing, 3, 1f)
    private val A = NativeMixedSide.A
    private val B = NativeMixedSide.B

    @Test fun bothFeedsMoveByTheSameAmount() {
        val plan = nativePairSkipPlan(mapOf(A to replay(100_000), B to replay(40_000)), 10_000)
        assertEquals(NativeSeekOutcome.REQUESTED, plan.outcome)
        assertEquals(10_000L, plan.movementMs)
        assertEquals(listOf(NativeSkipTarget(A, 100_000, 110_000), NativeSkipTarget(B, 40_000, 50_000)), plan.targets)
        assertNull(plan.limitedBy)
    }

    @Test fun backwardIsShortenedByTheFeedNearestItsStart() {
        val plan = nativePairSkipPlan(mapOf(A to replay(100_000), B to replay(25_000)), -60_000)
        assertEquals(NativeSeekOutcome.CLAMPED, plan.outcome)
        assertEquals(-25_000L, plan.movementMs)
        assertEquals(B, plan.limitedBy)
        assertEquals(listOf(75_000L, 0L), plan.targets.map { it.targetMs })
    }

    @Test fun forwardStopsOneSecondBeforeTheNearestEnd() {
        val plan = nativePairSkipPlan(mapOf(A to replay(100_000), B to replay(570_000)), 60_000)
        assertEquals(NativeSeekOutcome.CLAMPED, plan.outcome)
        assertEquals(600_000L - SKIP_END_MARGIN_MS - 570_000L, plan.movementMs)
        assertEquals(B, plan.limitedBy)
        assertEquals(listOf(129_000L, 599_000L), plan.targets.map { it.targetMs })
    }

    @Test fun aFeedAtItsLimitMovesNeither() {
        val start = nativePairSkipPlan(mapOf(A to replay(100_000), B to replay(0)), -10_000)
        assertEquals(NativeSeekOutcome.NO_CHANGE, start.outcome)
        assertEquals(B, start.limitedBy)
        assertTrue(start.targets.isEmpty())
        val end = nativePairSkipPlan(mapOf(A to replay(599_500), B to replay(10_000)), 10_000)
        assertEquals(NativeSeekOutcome.NO_CHANGE, end.outcome)
        assertEquals(A, end.limitedBy)
        assertEquals(NativeSeekOutcome.NO_CHANGE, nativePairSkipPlan(mapOf(A to replay(1_000)), 0).outcome)
    }

    @Test fun anAdvertisementInEitherFeedRefusesBoth() {
        val plan = nativePairSkipPlan(mapOf(A to replay(100_000), B to replay(50_000).copy(playingAd = true)), 10_000)
        assertEquals(NativeSeekOutcome.AD_BLOCKED, plan.outcome)
        assertEquals(B, plan.blockedBy)
        assertTrue(plan.targets.isEmpty())
    }

    @Test fun liveUnsupportedOrUnavailableFeedsRefuseBoth() {
        val live = replay(50_000).copy(live = true, dynamic = true, windowStartMs = 1_000_000)
        assertEquals(NativeSeekOutcome.UNSUPPORTED, nativePairSkipPlan(mapOf(A to replay(1), B to live), 10_000).outcome)
        assertEquals(NativeSeekOutcome.UNSUPPORTED,
            nativePairSkipPlan(mapOf(A to replay(50_000).copy(seekable = false), B to replay(1)), 10_000).outcome)
        val buffering = nativePairSkipPlan(mapOf(A to replay(50_000).copy(state = 2), B to replay(1)), 10_000)
        assertEquals(NativeSeekOutcome.UNAVAILABLE, buffering.outcome)
        assertEquals(A, buffering.blockedBy)
        assertEquals(NativeSeekOutcome.UNAVAILABLE, nativePairSkipPlan(mapOf(A to replay(1), B to null), 10_000).outcome)
        assertEquals(NativeSeekOutcome.UNAVAILABLE,
            nativePairSkipPlan(mapOf(A to replay(1).copy(durationMs = null)), 10_000).outcome)
        assertEquals(NativeSeekOutcome.UNAVAILABLE, nativePairSkipPlan(emptyMap(), 10_000).outcome)
    }

    @Test fun extremeRequestsSaturateWithinTheFeeds() {
        val plan = nativePairSkipPlan(mapOf(A to replay(100_000)), Long.MIN_VALUE)
        assertEquals(NativeSeekOutcome.CLAMPED, plan.outcome)
        assertEquals(-100_000L, plan.movementMs)
        assertEquals(NativeSeekOutcome.CLAMPED, nativePairSkipPlan(mapOf(A to replay(100_000)), Long.MAX_VALUE).outcome)
    }

    private class Member(var sample: NativeTimingSnapshot) : NativePairMember {
        var refuseSeek = false
        val seeks = mutableListOf<Long>()
        // The SDK reports a seek's target at once, then READY after buffering.
        var buffering = false
        override fun timingSnapshot() = sample
        override fun seekToMs(targetMs: Long): NativeSeekPlan {
            seeks += targetMs
            if (refuseSeek) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
            val plan = nativeSeekPlan(sample, targetMs)
            plan.targetMs?.let { sample = sample.copy(positionMs = it, state = if (buffering) 2 else 3) }
            return plan
        }
        override fun setTimingPlaying(playing: Boolean): Boolean {
            sample = sample.copy(playing = playing && sample.state == 3, playWhenReady = playing); return true
        }
        override fun setVolume(volume: Float) = true
        override fun close() {}
    }

    private class Pair(playing: Boolean = true) {
        val a = Member(NativeTimingSnapshot(100_000, 600_000, 110_000, null, null, null,
            false, false, true, true, false, playing, playing, 3, 1f))
        val b = Member(a.sample.copy(positionMs = 40_000))
        var now = 0L
        val pair = NativeMixedPair(a, b, { true }, {}, { now })
    }

    @Test fun pairSkipHoldsBothSeeksBothAndResumesKeepingTheOffset() {
        val fixture = Pair()
        fixture.a.buffering = true; fixture.b.buffering = true
        val adjustment = fixture.pair.requestedAdjustmentMs
        val status = fixture.pair.status
        val plan = fixture.pair.seekTogether(-10_000)
        assertEquals(NativeSeekOutcome.REQUESTED, plan.outcome)
        assertTrue(fixture.pair.busy)
        assertEquals(listOf(90_000L), fixture.a.seeks)
        assertEquals(listOf(30_000L), fixture.b.seeks)
        assertFalse(fixture.a.sample.playWhenReady)
        assertEquals(status, fixture.pair.status) // A skip is not announced as a status change.
        assertEquals(2, fixture.pair.pendingSeekDiagnostics.size)
        fixture.pair.poll() // Still buffering.
        assertTrue(fixture.pair.busy)
        fixture.a.sample = fixture.a.sample.copy(state = 3); fixture.b.sample = fixture.b.sample.copy(state = 3)
        fixture.now = 500
        fixture.pair.poll()
        assertFalse(fixture.pair.busy)
        assertTrue(fixture.a.sample.playWhenReady && fixture.b.sample.playWhenReady)
        assertEquals(adjustment, fixture.pair.requestedAdjustmentMs)
        assertTrue(fixture.pair.requestedAdjustmentValid)
        assertNull(fixture.pair.lastTimingSettlement) // Pair offset history is untouched.
        assertEquals(status, fixture.pair.status)
    }

    @Test fun pausedPairSkipStaysPaused() {
        val fixture = Pair(playing = false)
        assertEquals(NativeSeekOutcome.REQUESTED, fixture.pair.seekTogether(60_000).outcome)
        fixture.pair.poll()
        assertFalse(fixture.pair.busy)
        assertFalse(fixture.a.sample.playWhenReady || fixture.b.sample.playWhenReady)
        assertEquals(160_000L, fixture.a.sample.positionMs)
        assertEquals(100_000L, fixture.b.sample.positionMs)
    }

    @Test fun refusedPlanMovesAndHoldsNeither() {
        val fixture = Pair()
        fixture.b.sample = fixture.b.sample.copy(playingAd = true)
        assertEquals(NativeSeekOutcome.AD_BLOCKED, fixture.pair.seekTogether(10_000).outcome)
        assertTrue(fixture.a.seeks.isEmpty() && fixture.b.seeks.isEmpty())
        assertTrue(fixture.a.sample.playWhenReady)
        assertFalse(fixture.pair.busy)
    }

    @Test fun aRefusedSecondSeekRollsBackAndPausesBoth() {
        val fixture = Pair()
        fixture.b.refuseSeek = true
        assertEquals(NativeSeekOutcome.UNAVAILABLE, fixture.pair.seekTogether(10_000).outcome)
        assertEquals(listOf(110_000L, 100_000L), fixture.a.seeks)
        assertEquals(100_000L, fixture.a.sample.positionMs)
        // B's uncertain refusal is also rolled back; that refusal leaves its
        // movement, and so the requested offset history, unknown.
        assertEquals(listOf(50_000L, 40_000L), fixture.b.seeks)
        assertFalse(fixture.pair.requestedAdjustmentValid)
        assertFalse(fixture.pair.busy)
        assertFalse(fixture.a.sample.playWhenReady || fixture.b.sample.playWhenReady)
        assertTrue(fixture.pair.status.startsWith("Skip refused by B"))
    }

    @Test fun pauseDuringASkipKeepsTheOffsetValidAndTimeoutPausesBoth() {
        val fixture = Pair()
        fixture.a.buffering = true
        fixture.pair.seekTogether(10_000)
        assertTrue(fixture.pair.pause())
        assertFalse(fixture.pair.busy)
        assertTrue(fixture.pair.requestedAdjustmentValid)
        val again = Pair()
        again.a.buffering = true
        again.pair.seekTogether(10_000)
        again.now = 8_000
        again.pair.poll()
        assertFalse(again.pair.busy)
        assertFalse(again.a.sample.playWhenReady || again.b.sample.playWhenReady)
    }

    @Test fun aBusyPairRefusesAnotherSkip() {
        val fixture = Pair()
        fixture.a.buffering = true
        fixture.pair.seekTogether(10_000)
        assertEquals(NativeSeekOutcome.UNAVAILABLE, fixture.pair.seekTogether(10_000).outcome)
        assertEquals(1, fixture.a.seeks.size)
    }

    @Test fun controllerSkipsASingleFeedDirectlyAndRefusesAnIncompletePair() {
        val single = PrototypePlaybackController({ true }, singleFeed = true)
        val member = Member(NativeTimingSnapshot(100_000, 600_000, 110_000, null, null, null,
            false, false, true, true, false, true, true, 3, 1f))
        single.setMember(NativeMixedSide.B, member)
        val plan = single.seekTogether(60_000)
        assertEquals(NativeSeekOutcome.REQUESTED, plan.outcome)
        assertEquals(listOf(160_000L), member.seeks)
        assertTrue(member.sample.playWhenReady) // No hold: the feed keeps playing.
        assertFalse(single.busy)

        val incomplete = PrototypePlaybackController({ true })
        val alone = Member(member.sample)
        incomplete.setMember(NativeMixedSide.A, alone)
        val refused = incomplete.seekTogether(10_000)
        assertEquals(NativeSeekOutcome.UNAVAILABLE, refused.outcome)
        assertEquals(NativeMixedSide.B, refused.blockedBy)
        assertTrue(alone.seeks.isEmpty())

        val both = PrototypePlaybackController({ true })
        val first = Member(member.sample.copy(positionMs = 100_000))
        val second = Member(member.sample.copy(positionMs = 30_000))
        both.setMember(NativeMixedSide.A, first); both.setMember(NativeMixedSide.B, second)
        assertSame(NativeSeekOutcome.REQUESTED, both.seekTogether(-10_000).outcome)
        assertEquals(listOf(90_000L), first.seeks)
        assertEquals(listOf(20_000L), second.seeks)
    }
}
