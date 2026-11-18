package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeSeekOutcome
import org.junit.Assert.*
import org.junit.Test

class ViewerRetryIntentPolicyTest {
    private fun decision(policy: ViewerRetryIntentPolicy, automatic: Boolean? = null,
        incident: Boolean? = null, desired: Boolean = false, sibling: Boolean = true, held: Boolean = false,
        saved: Boolean = true) = policy.plan(saved, automatic, incident, desired, sibling, held)

    @Test fun backToLiveOptOutSurvivesManualRetryWithoutSuppressingPlaybackRejoin() {
        val policy = ViewerRetryIntentPolicy()
        policy.backToLiveDispatched()
        val retry = decision(policy)
        assertFalse(retry.restoreOffset)
        assertTrue(retry.skipOffsetStartup)
        assertTrue(retry.resumePlayback)
    }

    @Test fun sessionOptOutCannotBeOverriddenByAnOlderAutomaticOffsetReceiptOrIncident() {
        for (automatic in listOf(false, true)) {
            val policy = ViewerRetryIntentPolicy()
            policy.backToLiveDispatched()
            val retry = decision(policy, automatic, incident = true)
            assertFalse(retry.restoreOffset)
            assertTrue(retry.skipOffsetStartup)
            assertTrue(retry.resumePlayback)
        }
    }

    @Test fun explicitOffsetOffRemainsOffAcrossRetryUntilEnablingItActuallySettles() {
        val policy = ViewerRetryIntentPolicy()
        // The save/seek may still be pending; the retained preference can be on.
        policy.offsetToggleRequested(false)
        assertFalse(decision(policy).restoreOffset)
        assertTrue(decision(policy).skipOffsetStartup)
        policy.offsetToggleRequested(true)
        assertFalse(decision(policy).restoreOffset)
        assertTrue(decision(policy).skipOffsetStartup)
        policy.settledOffsetPreference(true)
        assertTrue(decision(policy).restoreOffset)
        assertFalse(decision(policy).skipOffsetStartup)
    }

    @Test fun requestedOnAfterBackToLiveDoesNotClearTheSessionOptOut() {
        val policy = ViewerRetryIntentPolicy()
        policy.backToLiveDispatched()
        policy.offsetToggleRequested(true)
        assertFalse(decision(policy).restoreOffset)
        assertTrue(decision(policy).skipOffsetStartup)
        policy.settledOffsetPreference(true)
        assertTrue(decision(policy).restoreOffset)
    }

    @Test fun refusedUnsettledAndCanceledOnCommandsCannotClearOffsetOffIntent() {
        for (outcome in listOf("refused", "unsettled", "wrong settlement", "canceled", "settled")) {
            val policy = ViewerRetryIntentPolicy()
            val token = Any()
            val session = PairOffsetSession(PairOffsetPreference(-25_000, false))
            session.bind(token)
            policy.offsetToggleRequested(false)
            val on = checkNotNull(session.planToggle(token, true, 0))
            policy.offsetToggleRequested(true)
            session.dispatched(on, if (outcome == "refused") NativeSeekOutcome.UNAVAILABLE else NativeSeekOutcome.REQUESTED)
            when (outcome) {
                "wrong settlement" -> session.observe(token, 1, -24_999, busy = false)
                "canceled" -> { session.cancelPending(token); session.observe(token, 1, -25_000, busy = false) }
                "settled" -> session.observe(token, 1, -25_000, busy = false)
            }
            session.pendingSave?.let { policy.settledOffsetPreference(it.preference.enabled) }
            val retry = decision(policy, automatic = true, incident = true)
            assertEquals(outcome, outcome == "settled", retry.restoreOffset)
            assertEquals(outcome, outcome != "settled", retry.skipOffsetStartup)
            assertEquals(outcome, outcome == "settled", session.pendingSave != null)
        }
    }

    @Test fun normalManualRetryKeepsTheSavedOffsetGuessAndTimingOwnedPlaybackIntent() {
        val policy = ViewerRetryIntentPolicy()
        val retry = decision(policy, desired = false, sibling = true)
        assertTrue(retry.restoreOffset)
        assertFalse(retry.skipOffsetStartup)
        assertTrue(retry.resumePlayback)
        assertFalse(decision(policy, saved = false).restoreOffset)
        assertFalse(decision(policy, desired = false, sibling = false).resumePlayback)
        assertTrue(decision(policy, desired = true, sibling = false).resumePlayback)
    }

    @Test fun explicitPlaybackHoldCannotBorrowStaleSiblingOrAutomaticResumeIntent() {
        val policy = ViewerRetryIntentPolicy()
        for (automatic in listOf(null, false, true)) {
            assertFalse(decision(policy, automatic, desired = false, sibling = true, held = true).resumePlayback)
        }
    }

    @Test fun automaticRetryRetainsExistingOffsetAndSiblingReplacementDecisionsWithoutOptOut() {
        val policy = ViewerRetryIntentPolicy()
        for (automatic in listOf(false, true)) for (incident in listOf(null, false, true)) {
            val retry = decision(policy, automatic, incident, sibling = false)
            assertEquals(automatic || incident == true, retry.restoreOffset)
            assertEquals(!automatic && incident != true, retry.skipOffsetStartup)
            assertTrue(retry.resumePlayback)
        }
    }

    @Test fun aNewViewerOwnsAFreshOffsetPolicyWhileThePreviousSessionRemainsOptedOut() {
        val previous = ViewerRetryIntentPolicy().apply { backToLiveDispatched() }
        assertFalse(decision(previous).restoreOffset)
        assertTrue(decision(ViewerRetryIntentPolicy()).restoreOffset)
        assertFalse(decision(previous).restoreOffset)
    }

    @Test fun aNewSettledMarkAfterBackToLiveReenablesRetryRestorationBeforeItsSaveFinishes() {
        for (delta in listOf(0L, 1_000L)) {
            val policy = ViewerRetryIntentPolicy()
            val token = Any()
            val session = PairOffsetSession(PairOffsetPreference(-25_000, true))
            session.bind(token)
            policy.backToLiveDispatched()
            session.invalidate(token)
            val correspondence = PairOffsetCorrespondence(500_000, 10_000, false, false, null, null)
            val command = checkNotNull(session.planManual(token, delta, 0, correspondence))
            session.dispatched(command, if (delta == 0L) NativeSeekOutcome.NO_CHANGE else NativeSeekOutcome.REQUESTED)
            if (delta != 0L) {
                assertNull(session.pendingSave)
                assertFalse(decision(policy).restoreOffset)
                session.observe(token, 1, delta, busy = false)
            }
            val save = checkNotNull(session.pendingSave)
            assertEquals(correspondence, save.preference.correspondence)
            policy.settledOffsetPreference(save.preference.enabled)
            assertTrue(decision(policy, saved = save.preference.enabled).restoreOffset)
            assertFalse(decision(policy).skipOffsetStartup)
            session.saveCompleted(save.id, false)
            assertTrue("Storage failure cannot undo the user's newly applied alignment", decision(policy).restoreOffset)
        }
    }

    @Test fun refusedUnsettledAndCanceledMarksDoNotUndoBackToLiveOptOut() {
        for (outcome in listOf("refused", "unsettled", "wrong settlement", "canceled")) {
            val policy = ViewerRetryIntentPolicy()
            val token = Any()
            val session = PairOffsetSession(PairOffsetPreference(-25_000, true))
            session.bind(token)
            policy.backToLiveDispatched()
            session.invalidate(token)
            val command = checkNotNull(session.planManual(token, 1_000, 0,
                PairOffsetCorrespondence(500_000, 10_000, false, false, null, null)))
            session.dispatched(command, if (outcome == "refused") NativeSeekOutcome.UNAVAILABLE else NativeSeekOutcome.REQUESTED)
            when (outcome) {
                "wrong settlement" -> session.observe(token, 1, 999, busy = false)
                "canceled" -> { session.cancelPending(token); session.observe(token, 1, 1_000, busy = false) }
            }
            session.pendingSave?.let { policy.settledOffsetPreference(it.preference.enabled) }
            assertNull(session.pendingSave)
            assertFalse(decision(policy).restoreOffset)
            assertTrue(decision(policy).skipOffsetStartup)
        }
    }

    @Test fun automaticOffsetRestorationPublishesNoUserPreferenceThatCouldClearOptOut() {
        val policy = ViewerRetryIntentPolicy()
        val token = Any()
        val session = PairOffsetSession(PairOffsetPreference(-25_000, true))
        session.bind(token)
        val startup = checkNotNull(session.planStartup(token, 0))
        session.dispatched(startup, NativeSeekOutcome.REQUESTED)
        session.observe(token, 1, -25_000, busy = false)
        policy.backToLiveDispatched()
        session.pendingSave?.let { policy.settledOffsetPreference(it.preference.enabled) }
        assertNull(session.pendingSave)
        assertFalse(decision(policy).restoreOffset)
    }
}
