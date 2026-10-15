package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.presentation.PrototypeFailureReason
import org.junit.Assert.*
import org.junit.Test

class AbemaPreparationFailureObservationDeviceTest {
    private fun helper(failure: AbemaCachedHelperFailure) = AbemaCachedHelperMetadata(
        "SLOT_REGION_CDN", 4, 9328, 0, failure)

    @Test fun nativeDeadlineDoesNotInventHelperTimer() {
        val observed = AbemaPreparationFailureObservation()
        observed.capture(PrototypeFailureReason.PREPARATION_TIMEOUT, true, helper(AbemaCachedHelperFailure.NONE))
        val snapshot = checkNotNull(observed.snapshot)
        assertTrue(snapshot.nativeDeadlineReached)
        assertEquals(AbemaCachedHelperFailure.NONE, snapshot.helper?.failure)
    }

    @Test fun returnedHelperTimerRemainsSeparateFromNativeDeadline() {
        val observed = AbemaPreparationFailureObservation()
        observed.capture(PrototypeFailureReason.PREPARATION_TIMEOUT, false, helper(AbemaCachedHelperFailure.TIMER_EXPIRED))
        val snapshot = checkNotNull(observed.snapshot)
        assertFalse(snapshot.nativeDeadlineReached)
        assertEquals(AbemaCachedHelperFailure.TIMER_EXPIRED, snapshot.helper?.failure)
    }

    @Test fun firstFailureSurvivesCleanupAndLateCallbacks() {
        val observed = AbemaPreparationFailureObservation()
        observed.capture(PrototypeFailureReason.PREPARATION_TIMEOUT, true, helper(AbemaCachedHelperFailure.NONE))
        val first = observed.snapshot
        observed.capture(PrototypeFailureReason.STOPPED, false, helper(AbemaCachedHelperFailure.OWNED_CANCELLED))
        observed.seal()
        observed.capture(PrototypeFailureReason.STOPPED, false, null)
        assertSame(first, observed.snapshot)
    }

    @Test fun missingCallbackAllowsPreDisposalFallbackButNeverPostDisposalCapture() {
        val observed = AbemaPreparationFailureObservation()
        observed.capture(null, false, null)
        assertNull(observed.snapshot)
        observed.capture(PrototypeFailureReason.STOPPED, false, null)
        assertEquals(PrototypeFailureReason.STOPPED, observed.snapshot?.nativeReason)
        val sealed = AbemaPreparationFailureObservation()
        sealed.seal()
        sealed.capture(PrototypeFailureReason.PREPARATION_TIMEOUT, true, helper(AbemaCachedHelperFailure.TIMER_EXPIRED))
        assertNull(sealed.snapshot)
    }

    @Test fun helperPrefixWithoutNativeFailureIsProgressNotAnInventedTimeout() {
        val observed = AbemaPreparationFailureObservation()
        val prefix = helper(AbemaCachedHelperFailure.NONE)
        observed.capture(null, true, prefix)
        val snapshot = checkNotNull(observed.snapshot)
        assertSame(prefix, snapshot.helper)
        assertNull(snapshot.nativeReason)
        assertFalse(snapshot.nativeDeadlineReached)
        assertTrue(snapshot.closedSummary().startsWith("observation=PROGRESS "))
        assertFalse(snapshot.closedSummary().contains("PREPARATION_TIMEOUT"))
    }

    @Test fun nativeProgressUpdatesUntilFirstActualReasonThenKeepsThatExactSnapshot() {
        val observed = AbemaPreparationFailureObservation()
        val queued = AbemaNativePreparationSnapshot(AbemaNativePreparationPhase.WORKER_QUEUED,
            sourcePending = true, licensePending = false, nativeHostCreated = false, workerFailure = null)
        observed.capture(null, false, null, queued)
        assertSame(queued, observed.snapshot?.preparation)
        val reading = queued.copy(phase = AbemaNativePreparationPhase.MANIFEST_READ)
        observed.capture(null, false, helper(AbemaCachedHelperFailure.NONE), reading)
        assertSame(reading, observed.snapshot?.preparation)
        val workerFailure = reading.copy(sourcePending = false,
            workerFailure = AbemaNativePreparationWorkerFailure(AbemaNativePreparationPhase.MANIFEST_READ,
                AbemaNativePreparationCategory.IO))
        observed.capture(PrototypeFailureReason.NETWORK_FAILED, false, helper(AbemaCachedHelperFailure.NONE), workerFailure)
        val first = checkNotNull(observed.snapshot)
        assertSame(workerFailure, first.preparation)
        assertEquals(PrototypeFailureReason.NETWORK_FAILED, first.nativeReason)
        assertTrue(first.closedSummary().startsWith("observation=FAILURE "))
        observed.capture(PrototypeFailureReason.STOPPED, false, helper(AbemaCachedHelperFailure.OWNED_CANCELLED), queued)
        assertSame(first, observed.snapshot)
    }

    @Test fun sealingProgressBeforeCleanupBlocksBothSyntheticReasonAndLateProgress() {
        val observed = AbemaPreparationFailureObservation()
        val progress = AbemaNativePreparationSnapshot(AbemaNativePreparationPhase.SOURCE_SELECT,
            sourcePending = true, licensePending = true, nativeHostCreated = false, workerFailure = null)
        observed.capture(null, false, null, progress)
        val beforeCleanup = checkNotNull(observed.snapshot)
        observed.seal()
        observed.capture(PrototypeFailureReason.STOPPED, false, helper(AbemaCachedHelperFailure.OWNED_CANCELLED), progress)
        observed.capture(null, false, null, progress.copy(phase = AbemaNativePreparationPhase.HOST_CREATED,
            nativeHostCreated = true))
        assertSame(beforeCleanup, observed.snapshot)
        assertNull(beforeCleanup.nativeReason)
    }

    @Test fun unobservedPreparationDoesNotManufactureEvidenceOrEraseExistingProgress() {
        val observed = AbemaPreparationFailureObservation()
        val unobserved = AbemaNativePreparationSnapshot(AbemaNativePreparationPhase.UNOBSERVED,
            sourcePending = false, licensePending = false, nativeHostCreated = false, workerFailure = null)
        assertFalse(unobserved.observed)
        observed.capture(null, true, null, unobserved)
        assertNull(observed.snapshot)
        observed.capture(null, false, helper(AbemaCachedHelperFailure.NONE))
        val prefix = observed.snapshot
        observed.capture(null, false, null, unobserved)
        assertSame(prefix, observed.snapshot)
    }

    @Test fun pendingWorkAndCreatedHostAreProgressWithoutPlaybackAcceptance() {
        val observed = AbemaPreparationFailureObservation()
        val created = AbemaNativePreparationSnapshot(AbemaNativePreparationPhase.HOST_CREATED,
            sourcePending = true, licensePending = true, nativeHostCreated = true, workerFailure = null)
        observed.capture(null, false, null, created)
        val snapshot = checkNotNull(observed.snapshot)
        assertNull(snapshot.nativeReason)
        assertSame(created, snapshot.preparation)
        assertTrue(snapshot.closedSummary().startsWith("observation=PROGRESS "))
        assertFalse(snapshot.closedSummary().contains("ACCEPTED"))
        assertFalse(snapshot.closedSummary().contains("SUCCESS"))
    }
}
