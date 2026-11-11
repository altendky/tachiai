package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeTimelineEvidence
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import org.junit.Assert.*
import org.junit.Test

class PairOffsetRetryEstimateTest {
    private fun replay(position: Long) = NativeTimingSnapshot(position, Long.MAX_VALUE, position,
        null, null, null, false, false, true, true, false, true, true, 3, 1f)
    private fun live(lag: Long, duration: Long = 120_000) = replay(duration - lag).copy(
        durationMs = duration, live = true, dynamic = true, windowStartMs = 1_700_000_000_000)

    @Test fun resettingEitherDelayedFeedRestoresItsLostDelayWithTheCorrectSign() {
        val a = PairOffsetRetryEstimate()
        a.remember(live(30_000), live(5_000), 1_000_000)
        assertEquals(-25_000L, a.correctionMs(live(5_000), live(5_000), 1_010_000))
        val b = PairOffsetRetryEstimate()
        b.remember(live(5_000), live(30_000), 1_000_000)
        assertEquals(25_000L, b.correctionMs(live(5_000), live(5_000), 1_010_000))
    }

    @Test fun survivingDelayedSiblingAlreadyContainsItsCorrectionAndMustNotReceiveItTwice() {
        for ((a, b) in listOf(live(30_000) to live(5_000), live(5_000) to live(30_000))) {
            val estimate = PairOffsetRetryEstimate()
            estimate.remember(a, b, 1_000_000)
            assertEquals(0L, estimate.correctionMs(a, b, 1_010_000))
        }
    }

    @Test fun splitAlignmentRestoresOnlyTheContributionLostByTheReplacedFeed() {
        // SDK baselines were A lag5s/B lag12s. Alignment held A back18s and
        // advanced B7s. Restoring the full -25s after either retry would double it.
        val estimate = PairOffsetRetryEstimate()
        estimate.remember(live(23_000), live(5_000), 1_000_000)
        assertEquals(-18_000L, estimate.correctionMs(live(5_000), live(5_000), 1_010_000))
        assertEquals(-7_000L, estimate.correctionMs(live(23_000), live(12_000), 1_010_000))
    }

    @Test fun liveWindowSlidesAndUnrelatedAbsoluteProviderOriginsDoNotChangeTheEstimate() {
        val estimate = PairOffsetRetryEstimate()
        estimate.remember(live(30_000), live(5_000).copy(windowStartMs = 9_000_000_000_000), 1_000_000)
        val a = live(30_000, 150_000).copy(windowStartMs = null)
        val b = live(5_000, 60_000).copy(windowStartMs = 5)
        assertEquals(0L, estimate.correctionMs(a, b, 1_030_000))
    }

    @Test fun mixedLiveReplayUsesElapsedProgressRatherThanSubtractingClockOrigins() {
        val first = PairOffsetRetryEstimate()
        first.remember(live(30_000), replay(10_000), 1_000_000)
        assertEquals(0L, first.correctionMs(live(30_000), replay(20_000), 1_010_000))
        assertEquals(-25_000L, first.correctionMs(live(5_000), replay(20_000), 1_010_000))
        val reversed = PairOffsetRetryEstimate()
        reversed.remember(replay(10_000), live(30_000), 1_000_000)
        assertEquals(25_000L, reversed.correctionMs(replay(20_000), live(5_000), 1_010_000))
    }

    @Test fun replayPairRestoresProgressLostByOneReplayRestart() {
        val estimate = PairOffsetRetryEstimate()
        estimate.remember(replay(500_000), replay(10_000), 1_000_000)
        assertEquals(0L, estimate.correctionMs(replay(510_000), replay(20_000), 1_010_000))
        assertEquals(510_000L, estimate.correctionMs(replay(0), replay(20_000), 1_010_000))
    }

    @Test fun invalidCurrentOrRememberedSnapshotsNeverBecomeAUsableWitness() {
        val good = live(5_000)
        val invalid = listOf(null, good.copy(positionMs = null), good.copy(positionMs = -1),
            good.copy(durationMs = null), good.copy(durationMs = 0), good.copy(positionMs = 120_001),
            good.copy(state = 2), good.copy(playingAd = true), good.copy(playing = false),
            good.copy(playWhenReady = false), good.copy(speed = 1.1f), good.copy(speed = Float.NaN))
        for (bad in invalid) for (onA in listOf(false, true)) {
            val a = if (onA) bad else good; val b = if (onA) good else bad
            val estimate = PairOffsetRetryEstimate()
            estimate.remember(good, good, 1_000_000)
            assertNull(estimate.correctionMs(a, b, 1_010_000))
            val empty = PairOffsetRetryEstimate()
            empty.remember(a, b, 1_000_000)
            assertNull(empty.correctionMs(good, good, 1_010_000))
        }
    }

    @Test fun invalidRefreshPreservesLastValidWitnessButClockKindOrClockRollbackRefuses() {
        val estimate = PairOffsetRetryEstimate()
        estimate.remember(live(30_000), live(5_000), 1_000_000)
        estimate.remember(live(5_000).copy(state = 2), live(5_000), 1_005_000)
        assertEquals(-25_000L, estimate.correctionMs(live(5_000), live(5_000), 1_010_000))
        assertNull(estimate.correctionMs(replay(100_000), live(5_000), 1_010_000))
        assertNull(estimate.correctionMs(live(5_000), replay(100_000), 1_010_000))
        assertNull(estimate.correctionMs(live(5_000), live(5_000), 999_999))
        val empty = PairOffsetRetryEstimate(); empty.remember(live(5_000), live(5_000), -1)
        assertNull(empty.correctionMs(live(5_000), live(5_000), 10))
    }

    @Test fun arithmeticOverflowAndCorrectionBoundsReturnUnavailableRatherThanWrongSign() {
        val overflow = PairOffsetRetryEstimate()
        overflow.remember(live(Long.MAX_VALUE, Long.MAX_VALUE), replay(0), 0)
        assertNull(overflow.correctionMs(live(0, Long.MAX_VALUE), replay(0), Long.MAX_VALUE))
        val bounds = PairOffsetRetryEstimate(); bounds.remember(replay(0), replay(0), 0)
        assertEquals(-MAX_PAIR_OFFSET_MS, bounds.correctionMs(replay(MAX_PAIR_OFFSET_MS), replay(0), 1))
        assertEquals(MAX_PAIR_OFFSET_MS, bounds.correctionMs(replay(0), replay(MAX_PAIR_OFFSET_MS), 1))
        assertNull(bounds.correctionMs(replay(MAX_PAIR_OFFSET_MS + 1), replay(0), 1))
        assertNull(bounds.correctionMs(replay(0), replay(Long.MAX_VALUE), 1))
    }

    private fun marked() = PairOffsetCorrespondence(1_700_000_000_000, 1_800_000_000_000,
        true, true, NativeTimelineEvidence.Dash(1_699_000_000_000, 0), NativeTimelineEvidence.Dash(1_799_000_000_000, 0))
    private fun restarted(preference: PairOffsetPreference?): Pair<PairOffsetSession, Any> {
        val token = Any(); val session = PairOffsetSession(preference)
        session.bind(Any()); session.bind(token)
        return session to token
    }
    private fun settle(session: PairOffsetSession, command: PairOffsetCommand) {
        session.dispatched(command, NativeSeekOutcome.REQUESTED)
        session.observe(command.pairToken, command.beforeSettlementRevision + 1, command.deltaMs, false)
    }

    @Test fun retryPrefersVerifiedCorrespondenceThenRuntimeEstimateThenSavedScalar() {
        val original = PairOffsetPreference(-25_000, true, marked())
        for ((verified, estimate, expected) in listOf(Triple(-3_000L, -7_000L, -3_000L),
            Triple(null, -7_000L, -7_000L), Triple(null, null, -25_000L))) {
            val (session, token) = restarted(original)
            val command = checkNotNull(session.planRestart(token, 0, verified, estimate))
            assertEquals(expected, command.deltaMs)
            assertEquals(verified != null, command.timelineRestore)
            assertEquals(if (verified != null) marked() else null, command.correspondence)
            settle(session, command)
            assertEquals(original, session.state.preference)
            assertNull(session.state.appliedOffsetMs); assertNull(session.pendingSave)
            assertNull(session.planRestart(token, 1, verified, estimate))
        }
    }

    @Test fun scalarOnlyRetryNeverPretendsThatAnUnboundTimelineCorrectionWasVerified() {
        val original = PairOffsetPreference(-25_000, true)
        for ((estimate, expected) in listOf(-7_000L to -7_000L, null to -25_000L)) {
            val (session, token) = restarted(original)
            val command = checkNotNull(session.planRestart(token, 0, -3_000, estimate))
            assertEquals(expected, command.deltaMs)
            assertFalse(command.timelineRestore); assertNull(command.correspondence)
            settle(session, command)
            assertEquals(original, session.state.preference)
            assertNull(session.state.appliedOffsetMs); assertNull(session.pendingSave)
        }
    }

    @Test fun newerSettledTimingEditCannotReuseAnOlderPlayingRelationshipBeforeItsNextSample() {
        val estimate = PairOffsetRetryEstimate()
        estimate.remember(live(30_000), live(5_000), 1_000_000)
        estimate.clear() // Accepted newer Mark/manual/toggle, then failure before a new playing sample.
        val correction = estimate.correctionMs(live(5_000), live(5_000), 1_010_000)
        assertNull(correction)
        val (session, token) = restarted(PairOffsetPreference(-20_000, true))
        val restore = checkNotNull(session.planRestart(token, 0, null, correction))
        assertEquals(-20_000L, restore.deltaMs)
        assertNull(restore.correspondence)
    }

    @Test fun zeroRuntimeEstimateDoesNotReapplyScalarOrInventASettlement() {
        val original = PairOffsetPreference(-25_000, true)
        val (session, token) = restarted(original)
        val command = checkNotNull(session.planRestart(token, 4, null, 0))
        assertEquals(0L, command.deltaMs)
        session.dispatched(command, NativeSeekOutcome.NO_CHANGE)
        assertEquals(original, session.state.preference)
        assertNull(session.state.appliedOffsetMs); assertNull(session.pendingSave)
        assertTrue(session.state.enabledApplied)
        assertNull(session.planRestart(token, 4, null, 0))
    }

    @Test fun disabledMissingCanceledAndStaleRetryCannotRestoreOrSaveAnEstimate() {
        for (preference in listOf(null, PairOffsetPreference(-25_000, false, marked()))) {
            val (session, token) = restarted(preference)
            assertNull(session.planRestart(token, 0, -3_000, -7_000))
            assertNull(session.pendingSave)
        }
        val original = PairOffsetPreference(-25_000, true)
        for (cancel in listOf(false, true)) {
            val (session, token) = restarted(original)
            val command = checkNotNull(session.planRestart(token, 0, null, -7_000))
            session.dispatched(command, NativeSeekOutcome.REQUESTED)
            if (cancel) session.cancelPending(token) else session.bind(Any())
            session.observe(token, 1, -7_000, false)
            assertEquals(original, session.state.preference)
            assertNull(session.pendingSave); assertNull(session.state.appliedOffsetMs)
            assertNull(session.planRestart(token, 1, null, -7_000))
        }
    }

    @Test fun refusedOrUnconfirmedRetryIsOnceOnlyAndDoesNotPromoteItsUnknownBaseline() {
        val original = PairOffsetPreference(-25_000, true)
        for (failure in 0..2) {
            val (session, token) = restarted(original)
            val command = checkNotNull(session.planRestart(token, 0, null, -7_000))
            when (failure) {
                0 -> session.dispatched(command, NativeSeekOutcome.UNAVAILABLE)
                1 -> session.dispatched(command, NativeSeekOutcome.CLAMPED, movementUncertain = true)
                2 -> { session.dispatched(command, NativeSeekOutcome.REQUESTED); session.observe(token, 1, -6_999, false) }
            }
            assertEquals(original, session.state.preference)
            assertNull(session.pendingSave); assertNull(session.state.appliedOffsetMs)
            assertNull(session.planRestart(token, 1, null, -7_000))
            assertNull(session.planToggle(token, false, 1))
        }
    }
}
