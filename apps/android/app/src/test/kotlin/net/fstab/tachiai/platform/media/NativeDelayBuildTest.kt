package net.fstab.tachiai.platform.media

import net.fstab.tachiai.presentation.PairOffsetPreference
import net.fstab.tachiai.presentation.PairOffsetSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Building delay (#618, user decision 5 of 2026-11-25).
class NativeDelayBuildTest {
    private val A = NativeMixedSide.A
    private val B = NativeMixedSide.B

    // A live feed on a shared wall clock. Its recorded window starts
    // `historyMs` behind the live edge and keeps that start while held, as a
    // growing rewind buffer does (#697). The live edge is 2 s behind now and
    // the live point (target and default position) 8 s behind the edge.
    // providerWindowMs: the window's oldest part is the provider's own and
    // slides with the live edge, except from retainedFromMs on, which the
    // recorder cached in time. bufferAfterSeek: reads that report BUFFERING
    // after each seek.
    private class Live(private val clock: LongArray, historyMs: Long, behindEdgeMs: Long) : NativePairMember {
        var start = clock[0] - 2_000 - historyMs
        var providerWindowMs: Long? = null
        var retainedFromMs = Long.MAX_VALUE
        var bufferAfterSeek = 0
        private var buffering = 0
        var content = clock[0] - 2_000 - behindEdgeMs
        var playing = true
        var playWhenReady = true
        var ad = false
        private var lastNow = clock[0]
        val seeks = mutableListOf<Long>()
        var defaultSeeks = 0
        var plays = 0
        private fun sync() {
            if (playing) content += clock[0] - lastNow
            lastNow = clock[0]
        }
        override fun timingSnapshot(): NativeTimingSnapshot {
            sync()
            val end = clock[0] - 2_000
            val first = providerWindowMs?.let { maxOf(start, minOf(end - it, retainedFromMs)) } ?: start
            val duration = end - first
            val position = content - first
            val state = if (buffering > 0) { buffering--; 2 } else 3
            return NativeTimingSnapshot(position, duration, position + 5_000, clock[0] - content, first,
                duration - 8_000, true, true, true, true, ad, playing, playWhenReady, state, 1f, 10_000)
        }
        override fun seekToMs(targetMs: Long): NativeSeekPlan {
            val sample = timingSnapshot()
            val plan = nativeSeekPlan(sample, targetMs)
            if (plan.outcome == NativeSeekOutcome.REQUESTED) {
                seeks += targetMs; content = checkNotNull(sample.windowStartMs) + targetMs; buffering = bufferAfterSeek
            }
            return plan
        }
        override fun seekLiveDefault(): NativeSeekPlan {
            val sample = timingSnapshot()
            val plan = nativeLiveDefaultPlan(sample)
            if (plan.outcome == NativeSeekOutcome.REQUESTED || plan.outcome == NativeSeekOutcome.CLAMPED) {
                defaultSeeks++; content = checkNotNull(sample.windowStartMs) + checkNotNull(sample.defaultPositionMs)
            }
            return plan
        }
        override fun setTimingPlaying(playing: Boolean): Boolean {
            sync()
            if (playing) plays++
            this.playing = playing; playWhenReady = playing
            return true
        }
        override fun setVolume(volume: Float) = true
        override fun close() {}
    }

    // By default each feed has 4 s of history behind it, less than the 5 s
    // margin, so a build has none to use at once.
    private class Fixture(historyA: Long = 12_000, behindA: Long = 8_000, historyB: Long = 12_000, behindB: Long = 8_000) {
        val clock = longArrayOf(1_700_000_000_000L)
        val a = Live(clock, historyA, behindA)
        val b = Live(clock, historyB, behindB)
        val pair = NativeMixedPair(a, b, { true }, {}, { clock[0] }, requireCurrentLiveWindowOnPlay = true)
        fun advance(ms: Long) { clock[0] += ms }
        // A later relative to B, in content time.
        fun relation() = a.content - b.content
    }

    @Test fun theCountdownIsTheRequestLessWhatTheOtherFeedHasPlayedSince() {
        assertEquals(90_000L, nativeDelayBuildRemainingMs(-90_000, 0, 0))
        assertEquals(60_000L, nativeDelayBuildRemainingMs(-90_000, 30_000, 0))
        // A held feed that drifted forward slightly has built that much less.
        assertEquals(60_200L, nativeDelayBuildRemainingMs(-90_000, 30_000, 200))
        assertEquals(0L, nativeDelayBuildRemainingMs(-90_000, 95_000, 0))
        assertEquals(0L, nativeDelayBuildRemainingMs(Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE))
        assertEquals("Building delay… 1:30", delayBuildLabel(90_000))
        assertEquals("Building delay… 0:40", delayBuildLabel(39_001))
        assertEquals("Building delay… 0:01", delayBuildLabel(1))
        assertEquals("Building delay… 0:00", delayBuildLabel(0))
        assertEquals("Building delay… 6:00", delayBuildLabel(360_000))
        // Past the target after a reducing step, the need is negative.
        assertEquals(-25_000L, nativeDelayBuildNeedMs(-5_000, 30_000, 0))
        assertEquals(0L, nativeDelayBuildRemainingMs(-5_000, 30_000, 0))
        // A jump back into recorded history counts as built.
        assertEquals(68_000L, nativeDelayBuildRemainingMs(-90_000, 0, -22_000))
        // What the recorded history covers at once: back to 30 s inside the
        // oldest point, never the whole movement.
        assertEquals(22_000L, nativeDelayBuildRecordedMs(52_000, -90_000))
        assertEquals(0L, nativeDelayBuildRecordedMs(29_000, -90_000))
        assertEquals(0L, nativeDelayBuildRecordedMs(4_000, -90_000))
        assertEquals(89_999L, nativeDelayBuildRecordedMs(200_000, -90_000))
        assertEquals(0L, nativeDelayBuildRecordedMs(null, -90_000))
        assertEquals(0L, nativeDelayBuildRecordedMs(52_000, 10_000))
    }

    @Test fun aBuildIsPlannedOnlyForARecordedHistoryShortfallWhilePlaying() {
        val fixture = Fixture()
        val sa = fixture.a.timingSnapshot(); val sb = fixture.b.timingSnapshot()
        // Positive: A later relative to B, so B is held; negative holds A.
        assertEquals(NativeDelayBuildPlan(NativeDelayBuildOutcome.BUILD, B, -90_000, 90_000), nativeDelayBuildPlan(sa, sb, 90_000))
        assertEquals(NativeDelayBuildPlan(NativeDelayBuildOutcome.BUILD, A, -90_000, -90_000), nativeDelayBuildPlan(sa, sb, -90_000))
        // B's 4 s of history behind it already covers 3 s.
        assertEquals(NativeDelayBuildOutcome.NOT_NEEDED, nativeDelayBuildPlan(sa, sb, 3_000).outcome)
        val paused = sb.copy(playing = false, playWhenReady = false)
        assertEquals(NativeDelayBuildOutcome.NOT_PLAYING, nativeDelayBuildPlan(sa, paused, 90_000).outcome)
        assertEquals(NativeDelayBuildOutcome.UNAVAILABLE, nativeDelayBuildPlan(sa, sb.copy(playingAd = true), 90_000).outcome)
        assertEquals(NativeDelayBuildOutcome.UNAVAILABLE, nativeDelayBuildPlan(sa, sb, 0).outcome)
        // A replay is never held to build delay: it has all its history.
        val replay = NativeTimingSnapshot(10_000, 600_000, 20_000, null, null, null, false, false, true, true,
            false, true, true, 3, 1f)
        assertEquals(NativeDelayBuildOutcome.UNAVAILABLE, nativeDelayBuildPlan(sa, replay, 90_000).outcome)
        assertEquals(NativeDelayBuildOutcome.BUILD, nativeDelayBuildPlan(replay, sb, 90_000).outcome)
    }

    @Test fun theDelayLimitStillRefusesARequestPastSixMinutes() {
        val fixture = Fixture()
        val sa = fixture.a.timingSnapshot(); val sb = fixture.b.timingSnapshot()
        // B is 2 s behind its live window's end beyond its normal latency.
        assertEquals(NativeDelayBuildOutcome.BUILD, nativeDelayBuildPlan(sa, sb, LIVE_DELAY_LIMIT_MS - 2_000).outcome)
        assertEquals(NativeDelayBuildOutcome.DELAY_LIMITED, nativeDelayBuildPlan(sa, sb, LIVE_DELAY_LIMIT_MS - 1_000).outcome)
        assertEquals(NativeSeekOutcome.UNAVAILABLE, fixture.pair.shiftRelative(LIVE_DELAY_LIMIT_MS - 1_000).outcome)
        assertEquals(LIVE_DELAY_LIMIT_MESSAGE, fixture.pair.status)
        assertNull(fixture.pair.delayBuild)
        assertFalse(fixture.pair.busy)
        assertTrue(fixture.a.playing && fixture.b.playing)
        assertTrue(fixture.a.seeks.isEmpty() && fixture.b.seeks.isEmpty())
    }

    @Test fun theHeldFeedCountsDownFromTheSnapshotsAndResumesOnceEnoughIsBuilt() {
        val fixture = Fixture()
        val before = fixture.relation()
        assertEquals(NativeSeekOutcome.REQUESTED, fixture.pair.shiftRelative(90_000).outcome)
        assertTrue(fixture.pair.busy)
        assertEquals(NativeDelayBuildState(B, 90_000, 90_000), fixture.pair.delayBuild)
        assertFalse(fixture.b.playing)
        assertTrue(fixture.a.playing)
        assertTrue(fixture.a.seeks.isEmpty() && fixture.b.seeks.isEmpty())
        assertEquals("Building delay for B; A keeps playing.", fixture.pair.status)
        fixture.advance(30_000); fixture.pair.poll()
        assertEquals(60_000L, fixture.pair.delayBuild?.remainingMs)
        // The other feed buffering or in an advertisement pauses the count.
        fixture.a.ad = true
        fixture.advance(5_000); fixture.pair.poll()
        assertEquals(60_000L, fixture.pair.delayBuild?.remainingMs)
        fixture.a.ad = false
        fixture.a.content -= 5_000 // It did not play through the advertisement.
        fixture.advance(59_900); fixture.pair.poll()
        assertEquals(100L, fixture.pair.delayBuild?.remainingMs)
        assertFalse(fixture.b.playing)
        assertNull(fixture.pair.lastTimingSettlement)
        fixture.advance(250); fixture.pair.poll()
        // Resumed by a seek to exactly where it belongs now (150 ms on), and
        // asked to play at once; that seek also resets its live target.
        assertEquals(1, fixture.b.seeks.size)
        assertTrue(fixture.b.playing)
        assertTrue(fixture.pair.busy) // Applied only once it plays.
        assertNull(fixture.pair.lastTimingSettlement)
        fixture.advance(250); fixture.pair.poll()
        assertFalse(fixture.pair.busy)
        assertNull(fixture.pair.delayBuild)
        assertEquals(NativeTimingSettlement(1, 90_000), fixture.pair.lastTimingSettlement)
        assertEquals(90_000L, fixture.pair.requestedAdjustmentMs)
        assertTrue(fixture.pair.requestedAdjustmentValid)
        assertTrue(checkNotNull(fixture.pair.lastDelayBuildEnd).completed)
        assertEquals("Delay built; B resumed.", fixture.pair.status)
        assertEquals(before + 90_000, fixture.relation())
        assertTrue(fixture.a.seeks.isEmpty())
        assertEquals(0, fixture.a.plays) // A was never interrupted.
    }

    @Test fun eitherFeedCanBeTheOneHeld() {
        val fixture = Fixture()
        val before = fixture.relation()
        assertEquals(NativeSeekOutcome.REQUESTED, fixture.pair.shiftRelative(-75_000).outcome)
        assertEquals(A, fixture.pair.delayBuild?.side)
        assertFalse(fixture.a.playing); assertTrue(fixture.b.playing)
        fixture.advance(75_100); fixture.pair.poll(); fixture.advance(100); fixture.pair.poll()
        assertFalse(fixture.pair.busy)
        assertEquals(NativeTimingSettlement(1, -75_000), fixture.pair.lastTimingSettlement)
        assertEquals(before - 75_000, fixture.relation())
    }

    @Test fun aSavedOffsetRestoreBuildsTooAndCountsAsAppliedOnlyOnResume() {
        val fixture = Fixture()
        val token = fixture.pair
        val session = PairOffsetSession(PairOffsetPreference(90_000, true))
        session.bind(token)
        val command = checkNotNull(session.planStartup(token, 0))
        // The activity dispatches a saved offset through marked alignment.
        val result = fixture.pair.alignRelative(command.deltaMs)
        assertEquals(NativeSeekOutcome.REQUESTED, result.outcome)
        assertEquals(B, fixture.pair.delayBuild?.side)
        session.dispatched(command, result.outcome)
        session.observe(token, 0, 0, fixture.pair.busy)
        assertTrue(session.state.busy)
        assertEquals(0L, session.state.appliedOffsetMs)
        fixture.advance(90_100); fixture.pair.poll(); fixture.advance(100); fixture.pair.poll()
        val settled = checkNotNull(fixture.pair.lastTimingSettlement)
        session.observe(token, settled.revision, settled.deltaMs, fixture.pair.busy)
        assertFalse(session.state.busy)
        assertEquals(90_000L, session.state.appliedOffsetMs)
        assertTrue(session.state.enabledApplied)
    }

    @Test fun pauseCancelsAndRestoresThePreviousRelationship() {
        val fixture = Fixture()
        val before = fixture.relation()
        fixture.pair.shiftRelative(90_000)
        val frozen = checkNotNull(fixture.b.timingSnapshot().positionMs)
        fixture.advance(30_000); fixture.pair.poll()
        assertTrue(fixture.pair.pause())
        assertNull(fixture.pair.delayBuild)
        assertFalse(fixture.pair.busy)
        assertFalse(fixture.a.playing || fixture.b.playing)
        assertEquals(listOf(frozen + 30_000), fixture.b.seeks)
        assertEquals(before, fixture.relation())
        assertEquals("$DELAY_BUILD_CANCELED; paused.", fixture.pair.status)
        val end = checkNotNull(fixture.pair.lastDelayBuildEnd)
        assertTrue(end.canceled && end.restored && !end.completed)
        assertTrue(fixture.pair.requestedAdjustmentValid)
        assertEquals(0L, fixture.pair.requestedAdjustmentMs)
        assertNull(fixture.pair.lastTimingSettlement)
        fixture.advance(120_000); fixture.pair.poll()
        assertTrue(fixture.b.seeks.size == 1 && !fixture.b.playing)
    }

    // Decision 5 asks for only the shortfall to be built (coordinator, 2026-11-25).
    @Test fun theRecordedHistoryIsUsedAtOnceAndOnlyTheShortfallIsBuilt() {
        // B has 52 s of history behind it: it jumps back 22 s, keeping 30 s
        // inside its oldest point, and builds the other 68 s.
        val fixture = Fixture(historyB = 60_000)
        val before = fixture.relation()
        assertEquals(NativeSeekOutcome.REQUESTED, fixture.pair.shiftRelative(90_000).outcome)
        assertEquals(listOf(DELAY_BUILD_HOLD_MARGIN_MS), fixture.b.seeks)
        assertFalse(fixture.b.playing)
        assertEquals(NativeDelayBuildState(B, 68_000, 90_000), fixture.pair.delayBuild)
        fixture.pair.poll()
        assertEquals(68_000L, fixture.pair.delayBuild?.remainingMs)
        assertEquals("Building delay… 1:08", delayBuildLabel(checkNotNull(fixture.pair.delayBuild).remainingMs))
        fixture.advance(68_100); fixture.pair.poll(); fixture.advance(100); fixture.pair.poll()
        assertFalse(fixture.pair.busy)
        assertEquals(NativeTimingSettlement(1, 90_000), fixture.pair.lastTimingSettlement)
        assertEquals(before + 90_000, fixture.relation())
        // Canceling such a build still restores the previous relationship.
        val canceled = Fixture(historyB = 60_000)
        val previous = canceled.relation()
        canceled.pair.shiftRelative(90_000)
        canceled.advance(10_000); canceled.pair.poll()
        assertTrue(canceled.pair.pause())
        assertEquals(previous, canceled.relation())
        assertTrue(checkNotNull(canceled.pair.lastDelayBuildEnd).restored)
    }

    // #707 on the emulator: a saved-offset restore at startup, with only the
    // provider's ~56 s window, was stopped within a second as "recorded
    // history ran out". The held frame now stays 30 s inside the oldest point,
    // nothing is judged while it buffers after the jump, and a window start
    // that still slides past it moves it back inside.
    @Test fun aStartupRestoreLongerThanTheProviderWindowSurvives() {
        fun run(recorderLagMs: Long): Fixture {
            val fixture = Fixture(historyB = 56_000)
            fixture.b.providerWindowMs = 56_000
            // The recorder caches the oldest segments only after this long, so
            // until then the window start slides with the live edge.
            fixture.b.retainedFromMs = fixture.b.start + recorderLagMs
            fixture.b.bufferAfterSeek = 3
            val before = fixture.relation()
            assertEquals(NativeSeekOutcome.REQUESTED, fixture.pair.alignRelative(115_000).outcome)
            assertEquals(B, fixture.pair.delayBuild?.side)
            // 48 s behind B: it jumps back 18 s and builds 97 s.
            assertEquals(listOf(DELAY_BUILD_HOLD_MARGIN_MS), fixture.b.seeks)
            assertEquals(97_000L, fixture.pair.delayBuild?.remainingMs)
            for (second in 1..200) {
                if (!fixture.pair.busy) break
                fixture.advance(1_000); fixture.pair.poll()
            }
            assertFalse(fixture.pair.busy)
            assertEquals(NativeTimingSettlement(1, 115_000), fixture.pair.lastTimingSettlement)
            assertTrue(kotlin.math.abs(before + 115_000 - fixture.relation()) <= 1)
            return fixture
        }
        // The window start slides 8 s before the recorder catches up: the
        // frame 30 s inside is never reached. Seeks: the jump and the resume.
        assertEquals(2, run(8_000).b.seeks.size)
        // A recorder 40 s late: the frame is moved back inside once.
        assertEquals(3, run(40_000).b.seeks.size)
    }

    // User, 2026-11-25: same-direction steps add, opposite steps reduce.
    @Test fun aTimingStepThatHoldsTheFeedFurtherBehindAddsToTheBuild() {
        val fixture = Fixture()
        val before = fixture.relation()
        fixture.pair.shiftRelative(90_000)
        fixture.advance(10_000); fixture.pair.poll()
        // A plain nudge is still refused while busy; steps adjust the build.
        assertEquals(NativeSeekOutcome.UNAVAILABLE, fixture.pair.shiftRelative(5_000).outcome)
        assertEquals(NativeDelayBuildAdjustment.ADDED, fixture.pair.adjustDelayBuild(5_000))
        assertEquals(NativeDelayBuildState(B, 85_000, 95_000), fixture.pair.delayBuild)
        assertEquals("Building delay for B: 1:25 to go.", fixture.pair.status)
        assertEquals(NativeDelayBuildAdjustment.ADDED, fixture.pair.adjustDelayBuild(5_000))
        assertEquals(90_000L, fixture.pair.delayBuild?.remainingMs)
        fixture.advance(90_100); fixture.pair.poll(); fixture.advance(100); fixture.pair.poll()
        assertFalse(fixture.pair.busy)
        assertEquals(NativeTimingSettlement(1, 100_000), fixture.pair.lastTimingSettlement)
        assertEquals(100_000L, fixture.pair.requestedAdjustmentMs)
        assertEquals(before + 100_000, fixture.relation())
        assertEquals(NativeDelayBuildAdjustment.NOT_BUILDING, fixture.pair.adjustDelayBuild(5_000))
    }

    @Test fun aStepPastTheDelayLimitIsRefusedAndTheBuildContinues() {
        val fixture = Fixture()
        fixture.pair.shiftRelative(300_000)
        // B is 2 s behind beyond its latency: 358 s of room in all.
        assertEquals(NativeDelayBuildAdjustment.DELAY_LIMITED, fixture.pair.adjustDelayBuild(60_000))
        assertEquals(LIVE_DELAY_LIMIT_MESSAGE, fixture.pair.status)
        assertEquals(NativeDelayBuildState(B, 300_000, 300_000), fixture.pair.delayBuild)
        assertEquals(NativeDelayBuildAdjustment.ADDED, fixture.pair.adjustDelayBuild(55_000))
        assertEquals(355_000L, fixture.pair.delayBuild?.remainingMs)
        assertTrue(fixture.pair.busy)
    }

    @Test fun anOppositeStepReducesTheBuildAndResumesAtZero() {
        val fixture = Fixture()
        val before = fixture.relation()
        fixture.pair.shiftRelative(90_000)
        fixture.advance(10_000); fixture.pair.poll()
        assertEquals(NativeDelayBuildAdjustment.REDUCED, fixture.pair.adjustDelayBuild(-5_000))
        assertEquals(NativeDelayBuildState(B, 75_000, 85_000), fixture.pair.delayBuild)
        assertEquals("Building delay for B: 1:15 to go.", fixture.pair.status)
        // Reducing to exactly what has been built resumes as if complete.
        assertEquals(NativeDelayBuildAdjustment.REDUCED, fixture.pair.adjustDelayBuild(-75_000))
        assertTrue(fixture.b.playing)
        assertEquals(1, fixture.b.seeks.size) // The resume seek resets the live target.
        fixture.advance(100); fixture.pair.poll()
        assertFalse(fixture.pair.busy)
        assertEquals(NativeTimingSettlement(1, 10_000), fixture.pair.lastTimingSettlement)
        // The resume seek is never to the very frame it is on (1 ms on).
        assertTrue(kotlin.math.abs(before + 10_000 - fixture.relation()) <= 1)
        assertTrue(checkNotNull(fixture.pair.lastDelayBuildEnd).completed)
    }

    @Test fun reducingPastWhatIsBuiltAppliesTheNetChange() {
        val fixture = Fixture()
        val before = fixture.relation()
        fixture.pair.shiftRelative(90_000)
        val frozen = checkNotNull(fixture.b.timingSnapshot().positionMs)
        fixture.advance(30_000); fixture.pair.poll()
        // 30 s is built but only 5 s is now wanted: B moves forward 25 s and plays.
        assertEquals(NativeDelayBuildAdjustment.REDUCED, fixture.pair.adjustDelayBuild(-85_000))
        assertEquals(listOf(frozen + 25_000), fixture.b.seeks)
        assertTrue(fixture.b.playing)
        fixture.advance(100); fixture.pair.poll()
        assertFalse(fixture.pair.busy)
        assertEquals(NativeTimingSettlement(1, 5_000), fixture.pair.lastTimingSettlement)
        assertEquals(before + 5_000, fixture.relation())

        // Past the other way, with A held further ahead than its window allows:
        // refused, and the build goes on unchanged.
        val far = Fixture()
        far.pair.shiftRelative(90_000)
        assertEquals(NativeDelayBuildAdjustment.UNAVAILABLE, far.pair.adjustDelayBuild(-200_000))
        assertEquals("B can't move that far forward; the delay is still building.", far.pair.status)
        assertEquals(NativeDelayBuildState(B, 90_000, 90_000), far.pair.delayBuild)
        assertTrue(far.b.seeks.isEmpty())
    }

    @Test fun theSessionFollowsAdjustmentsAndSavesTheNetChange() {
        val token = Any()
        val manual = PairOffsetSession(PairOffsetPreference(20_000, true)); manual.bind(token, initialAppliedMs = 20_000)
        val command = checkNotNull(manual.planManual(token, 90_000, 0))
        manual.dispatched(command, NativeSeekOutcome.REQUESTED)
        assertTrue(manual.adjustPending(token, 5_000))
        assertTrue(manual.adjustPending(token, -15_000))
        assertFalse(manual.adjustPending(Any(), 5_000))
        manual.observe(token, 1, 80_000, busy = false)
        assertTrue(manual.state.saving) // Applied; now saving.
        assertEquals(100_000L, manual.state.appliedOffsetMs)
        assertEquals(100_000L, manual.pendingSave?.preference?.offsetMs)

        // A saved-offset restore adjusted by a step becomes a saved manual change.
        val restore = PairOffsetSession(PairOffsetPreference(90_000, true)); restore.bind(token)
        val startup = checkNotNull(restore.planStartup(token, 0))
        restore.dispatched(startup, NativeSeekOutcome.REQUESTED)
        assertTrue(restore.adjustPending(token, 10_000))
        restore.observe(token, 1, 100_000, busy = false)
        assertEquals(100_000L, restore.state.appliedOffsetMs)
        assertEquals(100_000L, restore.pendingSave?.preference?.offsetMs)
        // Nothing pending: nothing to adjust.
        assertFalse(restore.adjustPending(token, 5_000))
    }

    @Test fun aSkipCancelsWithOneSeekThatRestoresAndMovesTheHeldFeed() {
        val fixture = Fixture()
        val before = fixture.relation()
        fixture.pair.shiftRelative(90_000)
        val frozen = checkNotNull(fixture.b.timingSnapshot().positionMs)
        fixture.advance(30_000); fixture.pair.poll()
        val plan = fixture.pair.seekTogether(-10_000)
        assertEquals(NativeSeekOutcome.REQUESTED, plan.outcome)
        assertNull(fixture.pair.delayBuild)
        assertEquals(listOf(frozen + 30_000 - 10_000), fixture.b.seeks)
        assertEquals(1, fixture.a.seeks.size)
        val end = checkNotNull(fixture.pair.lastDelayBuildEnd)
        assertTrue(end.canceled && end.restored)
        fixture.pair.poll()
        assertFalse(fixture.pair.busy)
        assertTrue(fixture.a.playing && fixture.b.playing)
        assertEquals(before, fixture.relation())
        assertEquals("$DELAY_BUILD_CANCELED.", fixture.pair.status)
        assertTrue(fixture.pair.requestedAdjustmentValid)
    }

    @Test fun aSkipThatCannotMoveStillCancelsAndRestores() {
        val fixture = Fixture()
        val before = fixture.relation()
        fixture.pair.shiftRelative(90_000)
        fixture.advance(30_000); fixture.pair.poll()
        // Both feeds are at their live points: nothing to skip forward to.
        val plan = fixture.pair.seekTogether(10_000)
        assertFalse(plan.outcome == NativeSeekOutcome.REQUESTED || plan.outcome == NativeSeekOutcome.CLAMPED)
        assertNull(fixture.pair.delayBuild)
        assertTrue(fixture.a.playing && fixture.b.playing)
        assertEquals(before, fixture.relation())
        assertTrue(checkNotNull(fixture.pair.lastDelayBuildEnd).restored)
    }

    @Test fun liveCancelsAndMovesBothForwardKeepingThePreviousOffset() {
        // A 58 s and B 38 s behind their live points; B has 30 s of history.
        val fixture = Fixture(historyA = 120_000, behindA = 66_000, historyB = 76_000, behindB = 46_000)
        val before = fixture.relation()
        val original = checkNotNull(fixture.b.timingSnapshot().positionMs)
        assertEquals(NativeSeekOutcome.REQUESTED, fixture.pair.shiftRelative(90_000).outcome)
        // B's 30 s of history is all within the hold margin: no jump.
        assertTrue(fixture.b.seeks.isEmpty())
        fixture.advance(30_000); fixture.pair.poll()
        val plan = fixture.pair.goLive()
        assertEquals(NativeSeekOutcome.REQUESTED, plan.outcome)
        assertTrue(plan.keepsOffset)
        assertEquals(B, plan.limitedBy) // B's restored position is the nearer to live.
        assertEquals(listOf(original + 30_000 + plan.movementMs), fixture.b.seeks)
        fixture.pair.poll()
        assertFalse(fixture.pair.busy)
        assertTrue(fixture.a.playing && fixture.b.playing)
        assertEquals(before, fixture.relation())
        assertTrue(checkNotNull(nativeBehindLivePointMs(fixture.b.timingSnapshot())) <= 100)
    }

    @Test fun backToLiveCancelsTheBuildEitherWay() {
        // Back to live on the other feed. It ends the offset anyway, so the
        // held feed just plays on from where it was held.
        val other = Fixture(historyA = 120_000, behindA = 30_000)
        assertEquals(NativeSeekOutcome.REQUESTED, other.pair.shiftRelative(90_000).outcome)
        other.advance(20_000); other.pair.poll()
        // Offered while building, although the pair is busy.
        assertTrue(other.pair.busy)
        assertEquals(NativeSeekOutcome.REQUESTED, other.pair.catchUpPlan(A).outcome)
        assertEquals(NativeSeekOutcome.REQUESTED, other.pair.catchUp(A).outcome)
        assertNull(other.pair.delayBuild)
        assertTrue(other.b.seeks.isEmpty())
        assertEquals(1, other.a.defaultSeeks)
        assertTrue(other.pair.status.startsWith("$DELAY_BUILD_CANCELED. "))
        other.pair.poll()
        assertFalse(other.pair.busy)
        assertTrue(other.a.playing && other.b.playing) // The pair was playing, so it resumes.

        // Back to live on the held feed needs no restore.
        val held = Fixture()
        held.pair.shiftRelative(90_000)
        held.advance(20_000); held.pair.poll()
        held.pair.catchUp(B)
        assertNull(held.pair.delayBuild)
        assertTrue(held.b.seeks.isEmpty())
        assertEquals(1, held.b.defaultSeeks)
        assertFalse(checkNotNull(held.pair.lastDelayBuildEnd).restored)
        held.pair.poll()
        assertTrue(held.a.playing && held.b.playing)
    }

    @Test fun theBuildStopsWhenTheOtherFeedStopsOrHistoryRunsOut() {
        val stopped = Fixture()
        stopped.pair.shiftRelative(90_000)
        stopped.advance(10_000)
        stopped.a.setTimingPlaying(false) // Paused outside the pair.
        stopped.pair.poll()
        assertNull(stopped.pair.delayBuild)
        assertFalse(stopped.pair.busy)
        assertEquals("Building delay stopped: A stopped playing; both paused.", stopped.pair.status)
        assertFalse(stopped.pair.requestedAdjustmentValid)
        assertFalse(checkNotNull(stopped.pair.lastDelayBuildEnd).canceled)

        // The window passing the held frame moves it back inside a few times,
        // then gives up.
        val evicted = Fixture()
        evicted.pair.shiftRelative(90_000)
        evicted.advance(10_000)
        repeat(DELAY_BUILD_REANCHOR_LIMIT) {
            evicted.b.start = evicted.b.content + 1_000 // The byte limit dropped the held point.
            evicted.pair.poll()
            assertNotNull(evicted.pair.delayBuild)
            assertEquals("B's oldest history moved on; the delay takes a little longer.", evicted.pair.status)
        }
        assertEquals(DELAY_BUILD_REANCHOR_LIMIT, evicted.b.seeks.size)
        evicted.b.start = evicted.b.content + 1_000
        evicted.pair.poll()
        assertNull(evicted.pair.delayBuild)
        assertEquals("Building delay stopped: B's recorded history ran out; both paused.", evicted.pair.status)

        val stalled = Fixture()
        stalled.pair.shiftRelative(90_000)
        stalled.a.ad = true // The other feed never plays on.
        stalled.advance(90_000 + DELAY_BUILD_SLACK_MS); stalled.pair.poll()
        assertNull(stalled.pair.delayBuild)
        assertEquals("Building delay stopped: A didn't play on long enough; both paused.", stalled.pair.status)
    }

    @Test fun aPausedPairDoesNotBuildAndSaysWhy() {
        val fixture = Fixture()
        assertTrue(fixture.pair.pause())
        assertEquals(NativeSeekOutcome.UNAVAILABLE, fixture.pair.shiftRelative(90_000).outcome)
        assertEquals(DELAY_BUILD_NEEDS_PLAYBACK, fixture.pair.status)
        assertNull(fixture.pair.delayBuild)
    }

    @Test fun stoppingOrDetachingEndsTheBuildCleanly() {
        // Detach (a member replaced): coordination ends without touching either feed.
        val detached = Fixture()
        detached.pair.shiftRelative(90_000)
        detached.advance(10_000); detached.pair.poll()
        detached.pair.detach()
        assertNull(detached.pair.delayBuild)
        assertFalse(detached.pair.busy)
        assertTrue(checkNotNull(detached.pair.lastDelayBuildEnd).canceled)
        detached.advance(120_000); detached.pair.poll()
        assertTrue(detached.b.seeks.isEmpty())
        assertEquals(0, detached.b.plays)
        assertNull(detached.pair.lastTimingSettlement)

        // The activity stops playback through its controller when it leaves
        // the foreground or is destroyed.
        val clock = longArrayOf(1_700_000_000_000L)
        val a = Live(clock, 60_000, 8_000)
        val b = Live(clock, 60_000, 8_000)
        val controller = PrototypePlaybackController({ true }, { clock[0] })
        controller.setMember(A, a); controller.setMember(B, b)
        val pair = checkNotNull(controller.pair)
        assertEquals(NativeSeekOutcome.REQUESTED, controller.shiftRelative(90_000).outcome)
        assertTrue(controller.busy)
        clock[0] += 10_000; controller.poll()
        controller.close()
        assertFalse(controller.busy)
        assertNull(pair.delayBuild)
        assertFalse(a.playing || b.playing)
        val plays = a.plays + b.plays
        val seeks = a.seeks.size + b.seeks.size
        clock[0] += 120_000; controller.poll(); pair.poll()
        assertEquals(plays, a.plays + b.plays)
        assertEquals(seeks, a.seeks.size + b.seeks.size)
        assertNull(pair.lastTimingSettlement)
    }

    @Test fun theSessionKeepsItsAppliedOffsetWhenACanceledBuildRestored() {
        val token = Any()
        val restored = PairOffsetSession(null); restored.bind(token)
        val command = checkNotNull(restored.planManual(token, 90_000, 0))
        restored.dispatched(command, NativeSeekOutcome.REQUESTED)
        assertTrue(restored.state.busy)
        restored.delayBuildEnded(token, restored = true, reason = "$DELAY_BUILD_CANCELED; the offset is unchanged.")
        assertFalse(restored.state.busy)
        assertEquals(0L, restored.state.appliedOffsetMs)
        assertNull(restored.pendingSave)
        assertEquals("$DELAY_BUILD_CANCELED; the offset is unchanged.", restored.state.message)
        // The next Timing change has a known baseline.
        assertEquals(10_000L, restored.planManual(token, 10_000, 0)?.targetOffsetMs)

        val unknown = PairOffsetSession(null); unknown.bind(token)
        val other = checkNotNull(unknown.planManual(token, 90_000, 0))
        unknown.dispatched(other, NativeSeekOutcome.REQUESTED)
        unknown.delayBuildEnded(token, restored = false, reason = "Building delay stopped.")
        assertNull(unknown.state.appliedOffsetMs)
        // Another pair's end changes nothing.
        unknown.delayBuildEnded(Any(), restored = true, reason = "x")
        assertEquals("Building delay stopped.", unknown.state.message)
    }
}
