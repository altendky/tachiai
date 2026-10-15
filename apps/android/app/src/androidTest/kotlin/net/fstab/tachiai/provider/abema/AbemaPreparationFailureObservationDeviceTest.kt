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
}
