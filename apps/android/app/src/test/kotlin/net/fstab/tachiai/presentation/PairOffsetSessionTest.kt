package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeSeekOutcome
import org.junit.Assert.*
import org.junit.Test

class PairOffsetSessionTest {
    private fun settle(session: PairOffsetSession, command: PairOffsetCommand) {
        session.dispatched(command, NativeSeekOutcome.REQUESTED)
        session.observe(command.pairToken, command.beforeSettlementRevision + 1, command.deltaMs, busy = true)
        assertTrue(session.state.busy)
        session.observe(command.pairToken, command.beforeSettlementRevision + 1, command.deltaMs, busy = false)
    }
    private fun save(session: PairOffsetSession) {
        val request = checkNotNull(session.pendingSave)
        session.saveCompleted(request.id, true)
    }

    @Test fun startupAppliesSavedCorrectionOnceAndNeverWritesAPreference() {
        val token = Any(); val original = PairOffsetPreference(-26_400, true)
        val session = PairOffsetSession(original); session.bind(token)
        val command = session.planStartup(token, 0)!!
        assertEquals(PairOffsetCommandOrigin.STARTUP, command.origin)
        assertEquals(-26_400L, command.deltaMs); assertEquals(-26_400L, command.targetOffsetMs)
        settle(session, command)
        assertEquals(-26_400L, session.state.appliedOffsetMs)
        assertEquals(original, session.state.preference)
        assertNull(session.pendingSave); assertFalse(session.state.busy)
        assertNull(session.planStartup(token, 1))
        session.observe(token, 1, -26_400, false)
        assertEquals(-26_400L, session.state.appliedOffsetMs)
    }

    @Test fun missingDisabledZeroAndExplicitTransportNeverAutomaticallyApply() {
        for (preference in listOf(null, PairOffsetPreference(5_000, false), PairOffsetPreference(0, true))) {
            val token = Any(); val session = PairOffsetSession(preference); session.bind(token)
            assertNull(session.planStartup(token, 0)); assertNull(session.pendingSave)
            assertEquals(0L, session.state.appliedOffsetMs)
        }
        val token = Any(); val session = PairOffsetSession(PairOffsetPreference(5_000, true))
        session.bind(token); session.skipStartup()
        assertNull(session.planStartup(token, 0)); assertEquals(0L, session.state.appliedOffsetMs)
    }

    @Test fun manualInputSupersedesUnstartedStartupAndStaleDispatchCannotApplyIt() {
        val token = Any(); val session = PairOffsetSession(PairOffsetPreference(-26_400, true)); session.bind(token)
        val startup = session.planStartup(token, 0)!!
        val manual = session.planManual(token, 1_000, 0)!!
        assertFalse(session.isCurrent(startup)); assertTrue(session.isCurrent(manual))
        session.dispatched(startup, NativeSeekOutcome.REQUESTED)
        settle(session, manual)
        assertEquals(PairOffsetPreference(1_000, true), session.state.preference)
        assertEquals(1_000L, session.state.appliedOffsetMs)
        assertNull(session.planStartup(token, 1))
    }

    @Test fun toggleOffUsesExactInverseAndRetainsValueForReenable() {
        val token = Any(); val session = PairOffsetSession(PairOffsetPreference(-26_400, true)); session.bind(token)
        settle(session, session.planStartup(token, 0)!!)
        val off = session.planToggle(token, false, 1)!!
        assertEquals(26_400L, off.deltaMs); assertEquals(0L, off.targetOffsetMs)
        settle(session, off)
        assertEquals(PairOffsetPreference(-26_400, false), session.state.preference)
        assertEquals(0L, session.state.appliedOffsetMs); assertTrue(session.state.saving)
        assertFalse(session.state.available)
        save(session)
        val on = session.planToggle(token, true, 2)!!
        assertEquals(-26_400L, on.deltaMs)
        settle(session, on)
        assertEquals(PairOffsetPreference(-26_400, true), session.state.preference)
        assertEquals(-26_400L, session.state.appliedOffsetMs)
    }

    @Test fun manualAndMarkCorrectionsRememberTheNewTotalOnlyAfterExactSettlement() {
        val token = Any(); val session = PairOffsetSession(null); session.bind(token)
        val first = session.planManual(token, -25_000, 0)!!
        session.dispatched(first, NativeSeekOutcome.REQUESTED)
        assertNull(session.state.preference); assertNull(session.pendingSave)
        session.observe(token, 1, -25_000, false)
        assertEquals(PairOffsetPreference(-25_000, true), session.pendingSave?.preference)
        save(session)
        settle(session, session.planManual(token, 5_000, 1)!!)
        assertEquals(PairOffsetPreference(-20_000, true), session.state.preference)
        assertEquals(-20_000L, session.state.appliedOffsetMs)
    }

    @Test fun definiteRefusalPreservesAppliedStateButUncertainDispatchDoesNot() {
        for (outcome in listOf(NativeSeekOutcome.UNAVAILABLE, NativeSeekOutcome.UNSUPPORTED,
            NativeSeekOutcome.AD_BLOCKED, NativeSeekOutcome.OUTSIDE_WINDOW, NativeSeekOutcome.NO_CHANGE)) {
            val token = Any(); val session = PairOffsetSession(null); session.bind(token)
            val command = session.planManual(token, 1_000, 0)!!
            session.dispatched(command, outcome)
            assertEquals(0L, session.state.appliedOffsetMs)
            assertNull(session.pendingSave); assertFalse(session.state.busy)
        }
        for (outcome in listOf(NativeSeekOutcome.UNAVAILABLE, NativeSeekOutcome.CLAMPED)) {
            val token = Any(); val session = PairOffsetSession(null); session.bind(token)
            session.dispatched(session.planManual(token, 1_000, 0)!!, outcome, movementUncertain = true)
            assertNull(session.state.appliedOffsetMs); assertNull(session.pendingSave)
        }
    }

    @Test fun timeoutWrongDeltaSkippedRevisionAndCancellationNeverSaveUnconfirmedMovement() {
        for ((revision, delta) in listOf(0L to 0L, 1L to 999L, 2L to 1_000L)) {
            val token = Any(); val session = PairOffsetSession(null); session.bind(token)
            val command = session.planManual(token, 1_000, 0)!!
            session.dispatched(command, NativeSeekOutcome.REQUESTED)
            session.observe(token, revision, delta, false)
            assertNull(session.state.appliedOffsetMs); assertNull(session.state.preference)
            assertNull(session.pendingSave); assertFalse(session.state.busy)
        }
        val token = Any(); val session = PairOffsetSession(null); session.bind(token)
        val command = session.planManual(token, 1_000, 0)!!
        session.dispatched(command, NativeSeekOutcome.REQUESTED)
        session.cancel(command)
        session.observe(token, 1, 1_000, false)
        assertNull(session.state.appliedOffsetMs); assertNull(session.pendingSave)
    }

    @Test fun replacementAndBackToLiveInvalidateOnlyTheSessionAnchor() {
        val original = PairOffsetPreference(5_000, true)
        for (replace in listOf(false, true)) {
            val first = Any(); val second = Any(); val session = PairOffsetSession(original); session.bind(first)
            val command = session.planStartup(first, 0)!!
            session.dispatched(command, NativeSeekOutcome.REQUESTED)
            if (replace) session.bind(second) else session.invalidate(first)
            session.observe(first, 1, 5_000, false)
            assertEquals(original, session.state.preference); assertNull(session.state.appliedOffsetMs)
            assertNull(session.pendingSave)
            assertNull(session.planToggle(if (replace) second else first, false, 1))
            assertNull(session.planStartup(if (replace) second else first, 1))
        }
    }

    @Test fun manualWithUnknownBaselineCanMoveButCannotOverwriteSavedPreference() {
        val token = Any(); val original = PairOffsetPreference(25_000, true)
        val session = PairOffsetSession(original); session.bind(token); session.invalidate(token)
        val manual = session.planManual(token, -5_000, 0)!!
        assertNull(manual.targetOffsetMs)
        settle(session, manual)
        assertEquals(original, session.state.preference); assertNull(session.state.appliedOffsetMs)
        assertNull(session.pendingSave)
    }

    @Test fun saveFailureKeepsMemoryValueAndStaleCompletionCannotClaimItWasSaved() {
        val token = Any(); val session = PairOffsetSession(null); session.bind(token)
        settle(session, session.planManual(token, 1_000, 0)!!)
        val first = session.pendingSave!!
        session.saveCompleted(first.id + 1, true)
        assertTrue(session.state.saving); assertFalse(session.state.saved)
        session.saveCompleted(first.id, false)
        assertEquals(PairOffsetPreference(1_000, true), session.state.preference)
        assertFalse(session.state.saved); assertFalse(session.state.saving)
        assertTrue(session.state.message!!.contains("could not be saved"))
        settle(session, session.planManual(token, 1_000, 1)!!)
        val second = session.pendingSave!!
        session.saveCompleted(first.id, true)
        assertEquals(second, session.pendingSave); assertFalse(session.state.saved)
        session.saveCompleted(second.id, true)
        assertTrue(session.state.saved); assertEquals(PairOffsetPreference(2_000, true), session.state.preference)
    }

    @Test fun pauseWithoutAcceptedCommandOrDuringSaveNeverLosesTheKnownAppliedState() {
        val token = Any(); val session = PairOffsetSession(null); session.bind(token)
        session.cancelPending(token)
        assertEquals(0L, session.state.appliedOffsetMs)
        settle(session, session.planManual(token, 1_000, 0)!!)
        session.cancelPending(token)
        assertEquals(1_000L, session.state.appliedOffsetMs); assertTrue(session.state.saving)
        save(session)
        val pending = session.planManual(token, 1_000, 1)!!
        session.dispatched(pending, NativeSeekOutcome.REQUESTED)
        session.cancelPending(token)
        assertNull(session.state.appliedOffsetMs)
    }

    @Test fun stalePairAndUntrackedSettlementCannotCreateAFalseAppliedAnchor() {
        val token = Any(); val session = PairOffsetSession(null); session.bind(token)
        assertNull(session.planManual(Any(), 1_000, 0))
        session.observe(token, 0, 0, false)
        session.observe(Any(), 1, 1_000, false)
        assertEquals(0L, session.state.appliedOffsetMs)
        session.observe(token, 1, 1_000, false)
        assertNull(session.state.appliedOffsetMs); assertNull(session.state.preference)
    }

    @Test fun boundsAndBusyInputCannotCommitOrRetryAnUnknownAdjustment() {
        val token = Any(); val session = PairOffsetSession(PairOffsetPreference(MAX_PAIR_OFFSET_MS, true)); session.bind(token)
        for (delta in listOf(Long.MIN_VALUE, Long.MAX_VALUE, MAX_PAIR_OFFSET_MS + 1))
            assertNull(session.planManual(token, delta, 0))
        assertEquals(0L, session.state.appliedOffsetMs)
        val command = session.planManual(token, 1_000, 0)!!
        session.dispatched(command, NativeSeekOutcome.REQUESTED)
        assertNull(session.planManual(token, 1_000, 0))
        assertNull(session.state.appliedOffsetMs); assertFalse(session.isCurrent(command))
        session.observe(token, 1, 1_000, false)
        assertNull(session.pendingSave)
    }

    @Test fun zeroToggleAndManualNeedNoSettlementOrSdkMovementButMaySaveUserIntent() {
        val token = Any(); val session = PairOffsetSession(PairOffsetPreference(0, true)); session.bind(token)
        val off = session.planToggle(token, false, 0)!!
        assertEquals(0L, off.deltaMs)
        session.dispatched(off, NativeSeekOutcome.NO_CHANGE)
        assertEquals(PairOffsetPreference(0, false), session.pendingSave?.preference)
        save(session)
        val manual = session.planManual(token, 0, 0)!!
        session.dispatched(manual, NativeSeekOutcome.NO_CHANGE)
        assertEquals(PairOffsetPreference(0, true), session.pendingSave?.preference)
        assertEquals(0L, session.state.appliedOffsetMs)
    }
}
