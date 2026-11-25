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

    @Test fun unsupportedOrUnavailableFeedsRefuseBoth() {
        // A live feed without a live point can't be bounded (#660).
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

    // A live window of 180 s whose live point is 170 s in, starting at content
    // time 1,000,000 (#660).
    private fun live(position: Long, livePoint: Long = 170_000, windowStart: Long = 1_000_000, playing: Boolean = true) =
        NativeTimingSnapshot(position, 180_000, position + 5_000, 15_000, windowStart, livePoint, true, true, true, true,
            false, playing, playing, 3, 1f)

    @Test fun liveSkipsMoveWithinTheWindowAndTargetTheContentClock() {
        val plan = nativePairSkipPlan(mapOf(A to live(100_000)), -10_000)
        assertEquals(NativeSeekOutcome.REQUESTED, plan.outcome)
        assertEquals(listOf(NativeSkipTarget(A, 100_000, 90_000, liveClock = true, targetClockMs = 1_090_000)), plan.targets)
        assertFalse(plan.limitedAtLive)
        assertEquals(NativeSeekOutcome.REQUESTED, nativePairSkipPlan(mapOf(A to live(100_000)), 60_000).outcome)
    }

    @Test fun liveForwardStopsAtTheLivePoint() {
        val clamped = nativePairSkipPlan(mapOf(A to live(165_000)), 10_000)
        assertEquals(NativeSeekOutcome.CLAMPED, clamped.outcome)
        assertEquals(5_000L, clamped.movementMs)
        assertEquals(170_000L, clamped.targets.single().targetMs)
        assertEquals(A, clamped.limitedBy)
        assertTrue(clamped.limitedAtLive)
        for (position in listOf(170_000L, 175_000L)) { // At or ahead of the computed live point.
            val atLive = nativePairSkipPlan(mapOf(A to live(position)), 10_000)
            assertEquals(NativeSeekOutcome.NO_CHANGE, atLive.outcome)
            assertEquals(A, atLive.limitedBy)
            assertTrue(atLive.limitedAtLive)
            assertTrue(atLive.targets.isEmpty())
        }
        // The live point is never past the window's end.
        assertEquals(30_000L, nativePairSkipPlan(mapOf(A to live(150_000, livePoint = 400_000)), 60_000).movementMs)
    }

    @Test fun liveBackwardClampsAtTheOldestPointAndNeverBuildsDelay() {
        val clamped = nativePairSkipPlan(mapOf(A to live(8_000)), -60_000)
        assertEquals(NativeSeekOutcome.CLAMPED, clamped.outcome)
        assertEquals(LIVE_SKIP_START_MARGIN_MS, clamped.targets.single().targetMs)
        assertFalse(clamped.limitedAtLive)
        val oldest = nativePairSkipPlan(mapOf(A to live(3_000)), -10_000)
        assertEquals(NativeSeekOutcome.NO_CHANGE, oldest.outcome)
        assertEquals(A, oldest.limitedBy)
        assertFalse(oldest.limitedAtLive)
        // Start over's extreme request also stops at the oldest point.
        assertEquals(-95_000L, nativePairSkipPlan(mapOf(A to live(100_000)), -Long.MAX_VALUE).movementMs)
    }

    @Test fun aLivePairKeepsItsOffsetAndReturnsTheNearerFeedToLive() {
        // A is 20 s behind live, B 50 s: one minute forward stops with A live.
        val plan = nativePairSkipPlan(mapOf(A to live(150_000), B to live(120_000, windowStart = 7_000_000)), 60_000)
        assertEquals(NativeSeekOutcome.CLAMPED, plan.outcome)
        assertEquals(20_000L, plan.movementMs)
        assertEquals(A, plan.limitedBy)
        assertTrue(plan.limitedAtLive)
        assertEquals(listOf(170_000L, 140_000L), plan.targets.map { it.targetMs })
        assertEquals(listOf(1_170_000L, 7_140_000L), plan.targets.map { it.targetClockMs })
        // With A live, a forward skip moves neither.
        val atLive = nativePairSkipPlan(mapOf(A to live(170_000), B to live(140_000)), 10_000)
        assertEquals(NativeSeekOutcome.NO_CHANGE, atLive.outcome)
        assertEquals(A, atLive.limitedBy)
        assertTrue(atLive.limitedAtLive)
        // Backward is limited by the feed nearest its oldest point.
        val back = nativePairSkipPlan(mapOf(A to live(150_000), B to live(20_000)), -60_000)
        assertEquals(-(20_000L - LIVE_SKIP_START_MARGIN_MS), back.movementMs)
        assertEquals(B, back.limitedBy)
    }

    @Test fun aMixedLiveAndReplayPairMovesBothByOneAmount() {
        val back = nativePairSkipPlan(mapOf(A to live(100_000), B to replay(50_000)), -60_000)
        assertEquals(NativeSeekOutcome.CLAMPED, back.outcome)
        assertEquals(-50_000L, back.movementMs)
        assertEquals(B, back.limitedBy)
        assertEquals(listOf(NativeSkipTarget(A, 100_000, 50_000, true, 1_050_000), NativeSkipTarget(B, 50_000, 0)),
            back.targets)
        val forward = nativePairSkipPlan(mapOf(A to live(100_000), B to replay(50_000)), 60_000)
        assertEquals(NativeSeekOutcome.REQUESTED, forward.outcome)
        assertEquals(listOf(160_000L, 110_000L), forward.targets.map { it.targetMs })
        val toLive = nativePairSkipPlan(mapOf(A to replay(50_000), B to live(150_000)), 60_000)
        assertEquals(20_000L, toLive.movementMs)
        assertEquals(B, toLive.limitedBy)
        assertTrue(toLive.limitedAtLive)
        // A replay nearer its end than the live feed is to live limits as before.
        val end = nativePairSkipPlan(mapOf(A to replay(595_000), B to live(100_000)), 60_000)
        assertEquals(A, end.limitedBy)
        assertFalse(end.limitedAtLive)
        assertEquals(600_000L - SKIP_END_MARGIN_MS - 595_000L, end.movementMs)
    }

    // With the player's target live offset, the live point follows its
    // continuous live offset rather than the default position, which steps
    // with each refresh. A skip to it lands exactly at the target (#660).
    @Test fun theLivePointUsesTheTargetLiveOffsetWhenKnown() {
        // 25 s behind the live edge with a 10 s target: 15 s behind the live point.
        val sample = live(100_000, livePoint = 108_000).copy(liveOffsetMs = 25_000, targetLiveOffsetMs = 10_000)
        assertEquals(15_000L, nativeBehindLivePointMs(sample))
        assertEquals(115_000L, checkNotNull(nativeLiveSkipRange(sample)).liveMs)
        val toLive = nativePairSkipPlan(mapOf(A to sample), Long.MAX_VALUE / 4)
        assertEquals(15_000L, toLive.movementMs)
        assertTrue(toLive.limitedAtLive)
        // The same position after the window refreshed: the default moved, the
        // live offset didn't, so neither does the live point.
        val refreshed = sample.copy(defaultPositionMs = 112_000)
        assertEquals(15_000L, nativeBehindLivePointMs(refreshed))
        // Never past the window's end.
        assertEquals(180_000L, checkNotNull(nativeLiveSkipRange(sample.copy(liveOffsetMs = 200_000, targetLiveOffsetMs = 0))).liveMs)
        // Playing slightly ahead of the target reads as at the live point.
        assertEquals(NativeSeekOutcome.NO_CHANGE,
            nativePairSkipPlan(mapOf(A to sample.copy(liveOffsetMs = 9_500)), 10_000).outcome)
        // Without a target, the default position stands in.
        assertEquals(8_000L, nativeBehindLivePointMs(sample.copy(targetLiveOffsetMs = null)))
        assertNull(nativeBehindLivePointMs(sample.copy(targetLiveOffsetMs = null, defaultPositionMs = null)))
    }

    @Test fun liveFeedsWithoutAStableClockOrInsideTheirWindowRefuse() {
        val noClock = nativePairSkipPlan(mapOf(A to live(100_000).copy(windowStartMs = null)), -10_000)
        assertEquals(NativeSeekOutcome.UNSUPPORTED, noClock.outcome)
        assertEquals(A, noClock.blockedBy)
        assertEquals(NativeSeekOutcome.UNSUPPORTED,
            nativePairSkipPlan(mapOf(A to live(100_000).copy(defaultPositionMs = 0)), -10_000).outcome)
        assertEquals(NativeSeekOutcome.UNSUPPORTED,
            nativePairSkipPlan(mapOf(A to live(100_000).copy(seekable = false), B to replay(1)), -10_000).outcome)
        // A paused feed that slid out of its window plays from its buffer, not a seek.
        val expired = nativePairSkipPlan(mapOf(A to live(-2_000)), -10_000)
        assertEquals(NativeSeekOutcome.OUTSIDE_WINDOW, expired.outcome)
        assertEquals(A, expired.blockedBy)
        assertEquals(NativeSeekOutcome.AD_BLOCKED,
            nativePairSkipPlan(mapOf(A to live(100_000).copy(playingAd = true)), -10_000).outcome)
        assertEquals(NativeLiveSkipRange(LIVE_SKIP_START_MARGIN_MS, 170_000, 100_000), nativeLiveSkipRange(live(100_000)))
        assertNull(nativeLiveSkipRange(replay(100_000)))
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

    // A held live window can slide before the seek settles: positions drop
    // while the content clock stays put, and that is what settles (#660).
    private fun slide(member: Member, byMs: Long) {
        member.sample = member.sample.copy(positionMs = checkNotNull(member.sample.positionMs) - byMs,
            windowStartMs = checkNotNull(member.sample.windowStartMs) + byMs, state = 3)
    }

    @Test fun aLivePairSkipSettlesOnTheContentClockWhileTheWindowSlides() {
        val a = Member(live(150_000)); val b = Member(live(120_000, windowStart = 7_000_000))
        a.buffering = true; b.buffering = true
        var now = 0L
        val pair = NativeMixedPair(a, b, { true }, {}, { now })
        val status = pair.status
        assertEquals(NativeSeekOutcome.REQUESTED, pair.seekTogether(-10_000).outcome)
        assertEquals(listOf(140_000L), a.seeks)
        assertEquals(listOf(110_000L), b.seeks)
        val pending = pair.pendingSeekDiagnostics
        assertEquals(listOf(1_140_000L, 7_110_000L), pending.map { it.targetClockMs })
        assertTrue(pending.all { it.liveClock && it.clockKindMatches })
        slide(a, 4_000); slide(b, 6_000)
        now = 500
        pair.poll()
        assertFalse(pair.busy)
        assertTrue(a.sample.playWhenReady && b.sample.playWhenReady)
        assertTrue(pair.requestedAdjustmentValid)
        assertNull(pair.lastTimingSettlement) // The pair offset is untouched.
        assertEquals(status, pair.status)
    }

    @Test fun aLivePairSkipDoesNotSettleOnAMovedContentClock() {
        val a = Member(live(150_000)); val b = Member(replay(50_000))
        a.buffering = true
        var now = 0L
        val pair = NativeMixedPair(a, b, { true }, {}, { now })
        assertEquals(NativeSeekOutcome.REQUESTED, pair.seekTogether(10_000).outcome)
        assertEquals(listOf(160_000L), a.seeks)
        assertEquals(listOf(60_000L), b.seeks)
        // Same window position, but the window moved under it: not the target.
        a.sample = a.sample.copy(windowStartMs = 1_004_000, state = 3)
        now = 500
        pair.poll()
        assertTrue(pair.busy)
        now = 8_000
        pair.poll()
        assertFalse(pair.busy)
        assertFalse(a.sample.playWhenReady || b.sample.playWhenReady)
    }

    @Test fun aPausedLivePairSkipStaysPausedAtItsTargets() {
        val a = Member(live(100_000, playing = false)); val b = Member(live(60_000, playing = false))
        val pair = NativeMixedPair(a, b, { true }, {}, { 0L })
        val plan = pair.seekTogether(60_000)
        assertEquals(NativeSeekOutcome.REQUESTED, plan.outcome)
        pair.poll()
        assertFalse(pair.busy)
        assertFalse(a.sample.playWhenReady || b.sample.playWhenReady)
        assertEquals(160_000L, a.sample.positionMs)
        assertEquals(120_000L, b.sample.positionMs)
    }

    @Test fun controllerSkipsASingleLiveFeedUpToItsLivePoint() {
        val single = PrototypePlaybackController({ true }, singleFeed = true)
        val member = Member(live(165_000))
        single.setMember(NativeMixedSide.A, member)
        val plan = single.seekTogether(10_000)
        assertEquals(NativeSeekOutcome.CLAMPED, plan.outcome)
        assertTrue(plan.limitedAtLive)
        assertEquals(listOf(170_000L), member.seeks)
        assertTrue(member.sample.playWhenReady)
        assertEquals(NativeSeekOutcome.NO_CHANGE, single.seekTogether(10_000).outcome)
        assertEquals(1, member.seeks.size)
    }

    // Live with a feed that slid behind its window (#660): it is brought back,
    // keeping the offset when the shared move reaches it, otherwise each live
    // feed goes to its own live point. Ordinary skips name the feed instead.
    @Test fun liveBringsASingleFeedBackFromBehindItsWindow() {
        val plan = nativeLivePlan(mapOf(A to live(-74_000)))
        assertEquals(NativeSeekOutcome.REQUESTED, plan.outcome)
        assertTrue(plan.keepsOffset)
        assertEquals(244_000L, plan.movementMs)
        assertEquals(listOf(NativeSkipTarget(A, -74_000, 170_000, true, 1_170_000)), plan.targets)
        // At live already: nothing to do.
        val atLive = nativeLivePlan(mapOf(A to live(170_000)))
        assertEquals(NativeSeekOutcome.NO_CHANGE, atLive.outcome)
        assertTrue(atLive.limitedAtLive)
    }

    @Test fun liveKeepsThePairOffsetWhenTheSharedMoveReachesTheFeedBehindItsWindow() {
        val plan = nativeLivePlan(mapOf(A to live(60_000), B to live(-20_000)))
        assertEquals(NativeSeekOutcome.REQUESTED, plan.outcome)
        assertTrue(plan.keepsOffset)
        assertEquals(110_000L, plan.movementMs) // A reaches live; B lands inside its window.
        assertEquals(listOf(170_000L, 90_000L), plan.targets.map { it.targetMs })
        assertEquals(A, plan.limitedBy)
        assertTrue(plan.limitedAtLive)
    }

    @Test fun liveDropsThePairOffsetWhenOnlySeparateMovesReachLive() {
        // A is 20 s from live; B needs 79 s just to re-enter its window.
        val plan = nativeLivePlan(mapOf(A to live(150_000), B to live(-74_000, windowStart = 7_000_000)))
        assertEquals(NativeSeekOutcome.REQUESTED, plan.outcome)
        assertFalse(plan.keepsOffset)
        assertEquals(listOf(NativeSkipTarget(A, 150_000, 170_000, true, 1_170_000),
            NativeSkipTarget(B, -74_000, 170_000, true, 7_170_000)), plan.targets)
        assertEquals(244_000L, plan.movementMs)
        // A live feed already at live stays put; only the stale one moves.
        val oneMoves = nativeLivePlan(mapOf(A to live(170_000), B to live(-74_000)))
        assertFalse(oneMoves.keepsOffset)
        assertEquals(listOf(B), oneMoves.targets.map { it.side })
        // A replay paired with a stale live feed stays where it is.
        val mixed = nativeLivePlan(mapOf(A to replay(595_000), B to live(-74_000)))
        assertFalse(mixed.keepsOffset)
        assertEquals(listOf(B), mixed.targets.map { it.side })
        val together = nativeLivePlan(mapOf(A to replay(50_000), B to live(-74_000)))
        assertTrue(together.keepsOffset)
        assertEquals(listOf(294_000L, 170_000L), together.targets.map { it.targetMs })
    }

    @Test fun aFeedOutsideItsWindowGetsAClearReasonElsewhere() {
        // Ordinary skips can't bring it back: they name it.
        for (delta in listOf(10_000L, -10_000L)) {
            val plan = nativePairSkipPlan(mapOf(A to live(100_000), B to live(-74_000)), delta)
            assertEquals(NativeSeekOutcome.OUTSIDE_WINDOW, plan.outcome)
            assertEquals(B, plan.blockedBy)
            assertTrue(plan.targets.isEmpty())
        }
        // A forward skip large enough to re-enter the window keeps the offset.
        val large = nativePairSkipPlan(mapOf(A to live(10_000), B to live(-10_000)), 60_000)
        assertEquals(NativeSeekOutcome.REQUESTED, large.outcome)
        assertEquals(listOf(70_000L, 50_000L), large.targets.map { it.targetMs })
        // Ahead of its window's end, nothing a seek within it can repair.
        val ahead = nativeLivePlan(mapOf(A to live(100_000), B to live(200_000)))
        assertEquals(NativeSeekOutcome.OUTSIDE_WINDOW, ahead.outcome)
        assertEquals(B, ahead.blockedBy)
        // Other refusals win over the repair.
        assertEquals(NativeSeekOutcome.AD_BLOCKED,
            nativeLivePlan(mapOf(A to live(-74_000), B to live(100_000).copy(playingAd = true))).outcome)
    }

    @Test fun aLivePairGoesLiveFromBehindItsWindowAndLosesTheOffset() {
        val a = Member(live(150_000)); val b = Member(live(-74_000, windowStart = 7_000_000))
        var now = 0L
        val pair = NativeMixedPair(a, b, { true }, {}, { now })
        assertTrue(pair.requestedAdjustmentValid)
        val plan = pair.goLive()
        assertEquals(NativeSeekOutcome.REQUESTED, plan.outcome)
        assertFalse(plan.keepsOffset)
        assertEquals(listOf(170_000L), a.seeks)
        assertEquals(listOf(170_000L), b.seeks)
        assertFalse(pair.requestedAdjustmentValid) // The feeds moved by different amounts.
        assertNull(pair.lastTimingSettlement)
        now = 500
        pair.poll()
        assertFalse(pair.busy)
        assertTrue(a.sample.playWhenReady && b.sample.playWhenReady)
    }

    @Test fun aLivePairGoesLiveFromBehindItsWindowKeepingTheOffset() {
        val a = Member(live(60_000)); val b = Member(live(-20_000))
        val pair = NativeMixedPair(a, b, { true }, {}, { 0L })
        val plan = pair.goLive()
        assertTrue(plan.keepsOffset)
        assertEquals(listOf(170_000L), a.seeks)
        assertEquals(listOf(90_000L), b.seeks)
        assertTrue(pair.requestedAdjustmentValid)
        pair.poll()
        assertFalse(pair.busy)
    }

    @Test fun controllerGoesLiveWithASingleFeedBehindItsWindow() {
        val single = PrototypePlaybackController({ true }, singleFeed = true)
        val member = Member(live(-74_000))
        single.setMember(NativeMixedSide.A, member)
        val plan = single.goLive()
        assertEquals(NativeSeekOutcome.REQUESTED, plan.outcome)
        assertEquals(listOf(170_000L), member.seeks)
        // An ordinary skip names it instead of moving it.
        val behind = Member(live(-74_000))
        val other = PrototypePlaybackController({ true }, singleFeed = true)
        other.setMember(NativeMixedSide.B, behind)
        val refused = other.seekTogether(10_000)
        assertEquals(NativeSeekOutcome.OUTSIDE_WINDOW, refused.outcome)
        assertEquals(NativeMixedSide.B, refused.blockedBy)
        assertTrue(behind.seeks.isEmpty())
    }

    // A live feed driven by a clock that moves on with every read, as a real
    // player's position and live offset do while it plays (#660). sliding:
    // a constant-length window whose start advances, like a recorded Twitch
    // HLS window (#699); otherwise a window that grows from a fixed start,
    // like a recorded ABEMA DASH window. The default position sits a snapped
    // segment behind the target live offset.
    private class ClockedLive(private val clock: LongArray, private val sliding: Boolean, private val windowMs: Long,
        private val targetMs: Long, behindLiveEdgeMs: Long) : NativePairMember {
        private val firstStart = clock[0] - 2_000 - windowMs
        var content = clock[0] - behindLiveEdgeMs
        var playing = true
        var lastNow = clock[0]
        // Applied once the pair holds: a feed that changes while held.
        var onHold: (ClockedLive) -> Unit = {}
        val seeks = mutableListOf<Long>()
        private fun read(): Long {
            clock[0] += 3
            if (playing) content += clock[0] - lastNow
            lastNow = clock[0]
            return clock[0]
        }
        override fun timingSnapshot(): NativeTimingSnapshot {
            val now = read()
            val end = now - 2_000
            val start = if (sliding) end - windowMs else firstStart
            val position = content - start
            val duration = end - start
            return NativeTimingSnapshot(position, duration, position + 5_000, now - content, start,
                duration - targetMs - 1_500, true, true, true, true, false, playing, playing, 3, 1f, targetMs)
        }
        override fun seekToMs(targetMs: Long): NativeSeekPlan {
            val sample = timingSnapshot()
            val plan = nativeSeekPlan(sample, targetMs)
            plan.targetMs?.let { seeks += it; content = checkNotNull(sample.windowStartMs) + it }
            return plan
        }
        override fun setTimingPlaying(playing: Boolean): Boolean {
            read(); this.playing = playing
            if (!playing) onHold(this)
            return true
        }
        override fun setVolume(volume: Float) = true
        override fun close() {}
        fun behindLivePoint() = checkNotNull(nativeBehindLivePointMs(timingSnapshot()))
    }

    private class ClockedPair {
        val clock = longArrayOf(1_700_000_000_000L)
        // ABEMA about 3:20 behind in a growing ~300 s window; Twitch about
        // 3:16 behind in a constant 238 s window, about 40 s into it.
        val abema = ClockedLive(clock, sliding = false, windowMs = 300_000, targetMs = 10_000, behindLiveEdgeMs = 210_000)
        val twitch = ClockedLive(clock, sliding = true, windowMs = 238_000, targetMs = 6_000, behindLiveEdgeMs = 202_000)
        val pair = NativeMixedPair(abema, twitch, { true }, {}, { clock[0] })
    }

    @Test fun aPlayingLivePairSkipsForwardAcrossTheHoldWhileItsClocksMoveOn() {
        val fixture = ClockedPair()
        val before = fixture.twitch.content - fixture.abema.content
        val plan = fixture.pair.seekTogether(60_000)
        assertEquals(NativeSeekOutcome.REQUESTED, plan.outcome)
        assertEquals(60_000L, plan.movementMs)
        assertEquals(1, fixture.abema.seeks.size)
        assertEquals(1, fixture.twitch.seeks.size)
        fixture.pair.poll()
        assertFalse(fixture.pair.busy)
        assertTrue(fixture.abema.playing && fixture.twitch.playing)
        // Both moved by one amount: the offset holds to within the reads' few ms.
        assertTrue(kotlin.math.abs(fixture.twitch.content - fixture.abema.content - before) < 100)
    }

    @Test fun aPlayingLivePairGoesLiveAcrossTheHoldKeepingItsOffset() {
        val fixture = ClockedPair()
        val before = fixture.twitch.content - fixture.abema.content
        val plan = fixture.pair.goLive()
        assertEquals(NativeSeekOutcome.REQUESTED, plan.outcome)
        assertTrue(plan.keepsOffset)
        assertNull(plan.reason)
        // Twitch is the nearer to its live point (196 s against 200 s).
        assertEquals(NativeMixedSide.B, plan.limitedBy)
        fixture.pair.poll()
        assertFalse(fixture.pair.busy)
        assertTrue(fixture.twitch.behindLivePoint() <= 1_000)
        assertTrue(kotlin.math.abs(fixture.twitch.content - fixture.abema.content - before) < 100)
        // A forward skip from there is at the live point, give or take the reads.
        val next = nativePairSkipPlan(mapOf(
            NativeMixedSide.A to fixture.abema.timingSnapshot(), NativeMixedSide.B to fixture.twitch.timingSnapshot()), 10_000)
        assertTrue(next.limitedAtLive && next.movementMs <= 1_000)
    }

    @Test fun aRealChangeWhileHeldIsNamedAndPlaybackGoesOn() {
        val fixture = ClockedPair()
        // Twitch stalls 5 s back once held: its live point is now 5 s further away.
        fixture.twitch.onHold = { it.content -= 5_000 }
        val plan = fixture.pair.goLive()
        assertEquals(NativeSeekOutcome.UNAVAILABLE, plan.outcome)
        assertEquals(NativeMixedSide.B, plan.blockedBy)
        assertEquals("B changed while holding both feeds", plan.reason)
        assertTrue(fixture.abema.seeks.isEmpty() && fixture.twitch.seeks.isEmpty())
        assertFalse(fixture.pair.busy)
        assertTrue(fixture.abema.playing && fixture.twitch.playing) // Nothing moved, so both play on.
        assertEquals("B changed while holding both feeds; neither feed moved.", fixture.pair.status)
    }

    @Test fun heldPlansAgreeWithinTheReadToleranceOnly() {
        fun plan(movementA: Long, movementB: Long?, keeps: Boolean = true) = NativePairSkipPlan(NativeSeekOutcome.REQUESTED,
            movementA, listOfNotNull(NativeSkipTarget(A, 0, movementA), movementB?.let { NativeSkipTarget(B, 0, it) }),
            keepsOffset = keeps)
        assertNull(skipPlanDrift(plan(60_000, 60_000), plan(60_000, 60_000)))
        assertNull(skipPlanDrift(plan(194_000, 194_000), plan(194_009, 194_009)))
        assertEquals(B, skipPlanDrift(plan(194_000, 194_000), plan(194_000, 199_000)))
        assertEquals(B, skipPlanDrift(plan(20_000, 20_000), plan(20_000, null)))
        assertEquals(A, skipPlanDrift(plan(20_000, 20_000), plan(20_000, 20_000, keeps = false)))
    }
}
