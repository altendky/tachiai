package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeTimelineEvidence
import net.fstab.tachiai.platform.media.NativeHlsTimelineSample
import net.fstab.tachiai.platform.media.NativeTimelineConsistency
import net.fstab.tachiai.platform.media.compareNativeTimelineEvidence
import org.junit.Assert.*
import org.junit.Test

class PairOffsetCorrespondenceSessionTest {
    private fun hls(first: Long) = NativeTimelineEvidence.Hls((first..first + 1).map {
        NativeHlsTimelineSample(it, 1_700_000_000_000 + it * 2_000, 2_000, 0)
    })

    @Test fun overlappingWitnessesFollowTheRunningFeedWithoutMovingTheEventOrClaimingAnotherSave() {
        val original = PairOffsetPreference(-25_000, true, correspondence().copy(aTimeline = hls(0)))
        val session = PairOffsetSession(original); val first = Any(); session.bind(first)
        session.observeTimelines(first, hls(1), original.correspondence!!.bTimeline)
        val latest = checkNotNull(session.observeTimelines(first, hls(2), original.correspondence.bTimeline))
        assertEquals(original.correspondence.aMs, latest.aMs)
        assertEquals(hls(2), latest.aTimeline)
        assertEquals(original, session.state.preference); assertTrue(session.state.saved); assertNull(session.pendingSave)
        val replacement = Any(); session.bind(replacement)
        val refreshed = checkNotNull(session.observeTimelines(replacement, hls(3), original.correspondence.bTimeline))
        assertEquals(hls(3), refreshed.aTimeline)
        val restore = checkNotNull(session.planRestart(replacement, 0, -3_000))
        assertEquals(refreshed, restore.correspondence); assertNull(restore.targetOffsetMs)
    }

    @Test fun unknownOrConflictingWitnessCannotUpgradeAnOlderMappingOrAStalePair() {
        val original = PairOffsetPreference(-25_000, true, correspondence().copy(aTimeline = hls(0), bTimeline = null))
        val token = Any(); val session = PairOffsetSession(original); session.bind(token)
        assertNull(session.observeTimelines(Any(), hls(1), hls(1)))
        val unknown = checkNotNull(session.observeTimelines(token, hls(10), hls(10)))
        assertEquals(hls(0), unknown.aTimeline); assertNull(unknown.bTimeline)
        val changed = NativeTimelineEvidence.Hls(hls(0).samples.map { it.copy(startTimeMs = it.startTimeMs + 10_000) })
        val retained = checkNotNull(session.observeTimelines(token, changed, hls(1)))
        assertEquals(hls(0), retained.aTimeline)
        assertEquals(NativeTimelineConsistency.CHANGED, compareNativeTimelineEvidence(retained.aTimeline, changed))
    }

    @Test fun survivingWitnessKeepsFollowingOverlapsWhileTheOtherFeedIsAbsent() {
        val original = PairOffsetPreference(-25_000, true,
            correspondence().copy(aTimeline = hls(0), bTimeline = hls(0)))
        val session = PairOffsetSession(original); val first = Any(); session.bind(first)
        assertNull(session.observeUnboundTimelines(null, hls(1)))
        session.unbind()
        for (sequence in 1L..20L) {
            val current = checkNotNull(session.observeUnboundTimelines(null, hls(sequence)))
            assertEquals(hls(0), current.aTimeline)
            assertEquals(hls(sequence), current.bTimeline)
            assertEquals(original.correspondence!!.bMs, current.bMs)
        }
        assertNull(session.observeTimelines(first, hls(21), hls(21)))
        val replacement = Any(); session.bind(replacement)
        val current = checkNotNull(session.observeTimelines(replacement, hls(0), hls(21)))
        assertEquals(hls(21), current.bTimeline)
        assertEquals(original, session.state.preference)
        assertNull(session.pendingSave)
    }

    private fun correspondence(residual: Long = 0) = PairOffsetCorrespondence(
        1_700_000_000_000, 1_800_000_000_000, true, true,
        NativeTimelineEvidence.Dash(1_699_000_000_000, 0),
        NativeTimelineEvidence.Dash(1_799_000_000_000, 5_000), residual)

    private fun settle(session: PairOffsetSession, command: PairOffsetCommand) {
        session.dispatched(command, NativeSeekOutcome.REQUESTED)
        assertNull(session.pendingSave)
        session.observe(command.pairToken, command.beforeSettlementRevision + 1, command.deltaMs, true)
        assertNull(session.pendingSave)
        session.observe(command.pairToken, command.beforeSettlementRevision + 1, command.deltaMs, false)
    }

    private fun save(session: PairOffsetSession) {
        session.saveCompleted(checkNotNull(session.pendingSave).id, true)
    }

    @Test fun freshMarksBecomeDurableOnlyAfterTheirExactSettlement() {
        val token = Any(); val session = PairOffsetSession(null); session.bind(token)
        val marked = correspondence()
        val command = checkNotNull(session.planManual(token, -25_000, 0, marked))
        assertNull(session.state.preference)
        settle(session, command)
        assertEquals(PairOffsetPreference(-25_000, true, marked), session.pendingSave?.preference)
        assertEquals(-25_000L, session.state.appliedOffsetMs)
        assertTrue(session.state.enabledApplied)
        save(session)
        session.observe(token, 1, -25_000, false)
        assertNull(session.pendingSave)
    }

    @Test fun refusedCanceledAndWrongSettlementCannotReplaceOlderCorrespondence() {
        val original = PairOffsetPreference(-5_000, false, correspondence(1_000))
        for (failure in 0..3) {
            val token = Any(); val session = PairOffsetSession(original); session.bind(token)
            val command = checkNotNull(session.planManual(token, -25_000, 0,
                correspondence().copy(aMs = 1_700_100_000_000)))
            when (failure) {
                0 -> session.dispatched(command, NativeSeekOutcome.OUTSIDE_WINDOW)
                1 -> { session.dispatched(command, NativeSeekOutcome.REQUESTED); session.cancelPending(token) }
                2 -> { session.dispatched(command, NativeSeekOutcome.REQUESTED); session.observe(token, 1, -24_999, false) }
                3 -> { session.dispatched(command, NativeSeekOutcome.REQUESTED); session.bind(Any()) }
            }
            session.observe(token, 1, -25_000, false)
            assertEquals(original, session.state.preference)
            assertNull(session.pendingSave)
            assertFalse(session.state.busy)
        }
    }

    @Test fun coincidentMarksSaveWithoutInventingASettlementRevision() {
        val token = Any(); val session = PairOffsetSession(null); session.bind(token)
        val marked = correspondence()
        val command = checkNotNull(session.planManual(token, 0, 7, marked))
        session.dispatched(command, NativeSeekOutcome.NO_CHANGE)
        assertEquals(PairOffsetPreference(0, true, marked), session.pendingSave?.preference)
        assertTrue(session.state.enabledApplied)
        save(session)
        session.observe(token, 7, 99_999, false)
        assertEquals(0L, session.state.appliedOffsetMs)
        assertNull(session.pendingSave)
    }

    @Test fun manualFineTuningMovesResidualWhileKeepingTheOriginalEventAnchors() {
        val token = Any(); val session = PairOffsetSession(null); session.bind(token)
        val marked = correspondence()
        settle(session, checkNotNull(session.planManual(token, -25_000, 0, marked))); save(session)
        settle(session, checkNotNull(session.planManual(token, 2_000, 1)))
        assertEquals(PairOffsetPreference(-23_000, true, marked.copy(residualMs = 2_000)), session.pendingSave?.preference)
        save(session)
        settle(session, checkNotNull(session.planManual(token, -500, 2)))
        assertEquals(PairOffsetPreference(-23_500, true, marked.copy(residualMs = 1_500)), session.pendingSave?.preference)
    }

    @Test fun togglesPreserveEventAnchorsAndResidualAcrossExactReversal() {
        val token = Any(); val session = PairOffsetSession(null); session.bind(token)
        settle(session, checkNotNull(session.planManual(token, -25_000, 0, correspondence()))); save(session)
        settle(session, checkNotNull(session.planManual(token, 1_000, 1))); save(session)
        val original = checkNotNull(session.state.preference)
        val off = checkNotNull(session.planToggle(token, false, 2))
        assertEquals(24_000L, off.deltaMs)
        settle(session, off)
        assertEquals(original.copy(enabled = false), session.pendingSave?.preference)
        assertFalse(session.state.enabledApplied)
        save(session)
        val on = checkNotNull(session.planToggle(token, true, 3))
        assertEquals(-24_000L, on.deltaMs)
        settle(session, on)
        assertEquals(original, session.pendingSave?.preference)
        assertTrue(session.state.enabledApplied)
    }

    @Test fun timestampStartupUsesEffectiveMovementForOffAndOnWithoutReplacingFallback() {
        val token = Any(); val original = PairOffsetPreference(-25_000, true, correspondence(500))
        val session = PairOffsetSession(original); session.bind(token)
        val restore = checkNotNull(session.planStartup(token, 0, timelineCorrectionMs = -7_000))
        assertEquals(-7_000L, restore.deltaMs)
        settle(session, restore)
        assertEquals(-7_000L, session.state.appliedOffsetMs)
        assertEquals(original, session.state.preference); assertNull(session.pendingSave)
        val off = checkNotNull(session.planToggle(token, false, 1))
        assertEquals(7_000L, off.deltaMs)
        settle(session, off)
        assertEquals(original.copy(enabled = false), session.pendingSave?.preference); save(session)
        val on = checkNotNull(session.planToggle(token, true, 2))
        assertEquals(-7_000L, on.deltaMs)
        settle(session, on)
        assertEquals(original, session.pendingSave?.preference)
    }

    @Test fun freshMarksWithUnknownBaselineSaveTheirCorrespondenceAndRetainOldFallback() {
        val token = Any(); val original = PairOffsetPreference(-13_000, false, correspondence(1_000))
        val session = PairOffsetSession(original); session.bind(token); session.invalidate(token)
        val marked = correspondence().copy(bMs = 1_800_100_000_000)
        val command = checkNotNull(session.planManual(token, -2_000, 0, marked))
        assertNull(command.targetOffsetMs)
        settle(session, command)
        assertEquals(original.copy(enabled = true, correspondence = marked), session.pendingSave?.preference)
        assertNull(session.state.appliedOffsetMs)
        save(session)
        assertNull(session.planToggle(token, false, 1))
    }

    @Test fun freshUnknownBaselineZeroMarksDoNotRequireASdkSeekOrInventAScalar() {
        val token = Any(); val session = PairOffsetSession(null); session.bind(token); session.invalidate(token)
        val command = checkNotNull(session.planManual(token, 0, 0, correspondence()))
        session.dispatched(command, NativeSeekOutcome.NO_CHANGE)
        assertEquals(PairOffsetPreference(0, true, correspondence()), session.pendingSave?.preference)
        assertNull(session.state.appliedOffsetMs)
    }

    @Test fun manualFineTuningAfterFreshUnknownBaselineMarksStillUpdatesOnlyTheirResidual() {
        val token = Any(); val original = PairOffsetPreference(-13_000, false)
        val session = PairOffsetSession(original); session.bind(token); session.invalidate(token)
        settle(session, checkNotNull(session.planManual(token, -2_000, 0, correspondence()))); save(session)
        settle(session, checkNotNull(session.planManual(token, 750, 1)))
        assertEquals(original.copy(offsetMs = -12_250, enabled = true, correspondence = correspondence(750)), session.pendingSave?.preference)
        assertNull(session.state.appliedOffsetMs)
    }

    @Test fun correspondenceResidualOverflowRefusesWithoutDiscardingTheSavedAnchor() {
        val token = Any(); val session = PairOffsetSession(null); session.bind(token)
        val marked = correspondence(MAX_PAIR_OFFSET_MS)
        val first = checkNotNull(session.planManual(token, 0, 0, marked))
        session.dispatched(first, NativeSeekOutcome.NO_CHANGE); save(session)
        assertNull(session.planManual(token, 1, 0))
        assertEquals(PairOffsetPreference(0, true, marked), session.state.preference)
        assertEquals(0L, session.state.appliedOffsetMs)
        assertNull(session.pendingSave); assertFalse(session.state.busy)
    }

    @Test fun enablingDisabledCorrespondenceCanUseVerifiedTimelineMovementInsteadOfOldScalar() {
        val token = Any(); val original = PairOffsetPreference(-25_000, false, correspondence(500))
        val session = PairOffsetSession(original); session.bind(token)
        assertNull(session.planStartup(token, 0, timelineCorrectionMs = -7_000))
        val on = checkNotNull(session.planToggle(token, true, 0, timelineCorrectionMs = -7_000))
        assertEquals(-7_000L, on.deltaMs)
        settle(session, on)
        assertEquals(original.copy(enabled = true), session.pendingSave?.preference); save(session)
        val off = checkNotNull(session.planToggle(token, false, 1))
        assertEquals(7_000L, off.deltaMs)
        settle(session, off)
        assertEquals(original, session.pendingSave?.preference)
    }

    @Test fun replacementTimelineRestoreIsOnceOnlyAndNeverInventsAnInvertibleBaseline() {
        val original = PairOffsetPreference(-25_000, true, correspondence())
        val first = Any(); val replacement = Any(); val session = PairOffsetSession(original)
        session.bind(first); session.bind(replacement)
        assertNull(session.planRestart(first, 0, -3_000))
        val restore = checkNotNull(session.planRestart(replacement, 0, -3_000))
        assertNull(restore.targetOffsetMs)
        settle(session, restore)
        assertEquals(original, session.state.preference)
        assertNull(session.state.appliedOffsetMs); assertNull(session.pendingSave)
        assertNull(session.planRestart(replacement, 1, -3_000))
        assertNull(session.planToggle(replacement, false, 1))
        val newer = Any(); session.bind(newer); session.skipStartup()
        assertNull(session.planRestart(newer, 0, -3_000))
    }

    @Test fun canceledReplacementRestoreCannotRetryOrPublishALateSettlement() {
        val original = PairOffsetPreference(-25_000, true, correspondence())
        val token = Any(); val session = PairOffsetSession(original); session.bind(Any()); session.bind(token)
        val restore = checkNotNull(session.planRestart(token, 0, -3_000))
        session.dispatched(restore, NativeSeekOutcome.REQUESTED)
        session.cancelPending(token)
        session.observe(token, 1, -3_000, false)
        assertEquals(original, session.state.preference)
        assertNull(session.pendingSave); assertNull(session.state.appliedOffsetMs)
        assertNull(session.planRestart(token, 1, -3_000))
    }

    @Test fun reenableAfterOffUsesFreshTimelineCorrectionAndSubsequentOffUndoesThatExactMovement() {
        val original = PairOffsetPreference(-25_000, true, correspondence(500))
        val token = Any(); val session = PairOffsetSession(original); session.bind(token)
        settle(session, checkNotNull(session.planStartup(token, 0, -7_000)))
        settle(session, checkNotNull(session.planToggle(token, false, 1))); save(session)
        val updated = checkNotNull(session.planToggle(token, true, 2, timelineCorrectionMs = -9_000))
        assertEquals(-9_000L, updated.deltaMs)
        settle(session, updated)
        assertEquals(original, session.pendingSave?.preference); save(session)
        val off = checkNotNull(session.planToggle(token, false, 3))
        assertEquals(9_000L, off.deltaMs)
        settle(session, off)
        assertEquals(original.copy(enabled = false), session.pendingSave?.preference)
    }

    @Test fun manualAfterTimestampRestoreKeepsFallbackSeparateFromThisSessionsActualMovement() {
        val original = PairOffsetPreference(-25_000, true, correspondence())
        val token = Any(); val session = PairOffsetSession(original); session.bind(token)
        settle(session, checkNotNull(session.planStartup(token, 0, timelineCorrectionMs = -7_000)))
        val manual = checkNotNull(session.planManual(token, 1_000, 1))
        assertEquals(-6_000L, manual.targetOffsetMs)
        assertEquals(-24_000L, manual.fallbackOffsetMs)
        settle(session, manual)
        assertEquals(-6_000L, session.state.appliedOffsetMs)
        assertEquals(PairOffsetPreference(-24_000, true, correspondence(1_000)), session.pendingSave?.preference)
        save(session)
        val off = checkNotNull(session.planToggle(token, false, 2))
        assertEquals(6_000L, off.deltaMs)
        settle(session, off)
        assertEquals(PairOffsetPreference(-24_000, false, correspondence(1_000)), session.pendingSave?.preference)
    }
}
