package net.fstab.tachiai.provider.abema.account

import org.junit.Assert.*
import org.junit.Test

class AbemaRenewalReadinessCoordinatorTest {
    private class Fixture {
        var now = 100L
        var nonce = 0
        val coordinator = AbemaRenewalReadinessCoordinator<String>({ now }, { (++nonce).toString(16).padStart(32, '0') },
            lifetimeMs = 1000, cooldownMs = 500)
    }
    @Test fun sameRecordConsumersJoinOneFlightAndOnlyOneClaimObtainsPrivateWork() {
        val f = Fixture()
        val first = f.coordinator.check("instance", "owner-record", "private")
        assertEquals(AbemaRenewalReadinessCoordinator.Status.LAUNCH, first.status)
        assertEquals(AbemaRenewalReadinessCoordinator.Status.WAIT, f.coordinator.check("instance", "owner-record", "other").status)
        assertEquals("private", f.coordinator.claim(checkNotNull(first.ticket)))
        assertNull(f.coordinator.claim(checkNotNull(first.ticket)))
        assertEquals(1, f.nonce)
        f.coordinator.finish(checkNotNull(first.ticket), true)
        assertEquals(AbemaRenewalReadinessCoordinator.Status.READY, f.coordinator.check("instance", "owner-record", "other").status)
    }
    @Test fun sharedBrowserSerializesDifferentInstancesWithoutDiscardingOriginalFlight() {
        val f = Fixture()
        val first = checkNotNull(f.coordinator.check("a", "route", "a").ticket)
        assertEquals(AbemaRenewalReadinessCoordinator.Status.WAIT, f.coordinator.check("b", "route", "b").status)
        assertTrue(f.coordinator.current(first))
        assertEquals("a", f.coordinator.claim(first)); f.coordinator.finish(first, false)
        assertEquals(AbemaRenewalReadinessCoordinator.Status.LAUNCH, f.coordinator.check("b", "route", "b").status)
    }
    @Test fun canceledOrFailedAttemptSuppressesImmediateResumeLoopAndLateSuccess() {
        for (claimed in listOf(false, true)) {
            val f = Fixture(); val first = checkNotNull(f.coordinator.check("a", "record", "a").ticket)
            if (claimed) assertEquals("a", f.coordinator.claim(first))
            f.coordinator.finish(first, false); f.coordinator.finish(first, true)
            assertNull(f.coordinator.claim(first)); assertFalse(f.coordinator.current(first))
            assertEquals(AbemaRenewalReadinessCoordinator.Status.FAILURE, f.coordinator.check("a", "record", "a").status)
            assertEquals(1, f.nonce)
            f.now += 500
            assertEquals(AbemaRenewalReadinessCoordinator.Status.LAUNCH, f.coordinator.check("a", "record", "a").status)
        }
    }
    @Test fun typedTemporaryInteractionAndStorageFailuresSurviveCoalescingAndCooldownWithoutAnotherLaunch() {
        for (status in listOf(AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE,
            AbemaRenewalReadinessCoordinator.Status.INTERACTION_REQUIRED, AbemaRenewalReadinessCoordinator.Status.STORAGE_FAILURE)) {
            val f = Fixture(); val first = checkNotNull(f.coordinator.check("a", "record", "private").ticket)
            assertEquals("private", f.coordinator.claim(first))
            f.coordinator.finish(first, status)
            assertEquals(status, f.coordinator.check("a", "record", "private").status)
            f.coordinator.finish(first, true)
            assertEquals(status, f.coordinator.check("a", "record", "private").status)
            assertEquals(1, f.nonce)
        }
    }
    @Test fun changedOwnerOrRecordInvalidatesOldClaimAndAllowsOnlyNewCapturedProjection() {
        val f = Fixture(); val old = checkNotNull(f.coordinator.check("a", "old-owner", "old-record").ticket)
        val fresh = checkNotNull(f.coordinator.check("a", "new-owner", "new-record").ticket)
        assertFalse(f.coordinator.current(old)); assertNull(f.coordinator.claim(old))
        f.coordinator.finish(old, true)
        assertEquals("new-record", f.coordinator.claim(fresh)); assertTrue(f.coordinator.current(fresh))
    }
    @Test fun selectedCandidateChangeCannotAdoptAnotherRoutesReadyResultOrClaimedTicket() {
        val f = Fixture()
        val instance = "00000000-0000-0000-0000-000000000001"
        val firstOwner = AbemaReadinessTarget(instance, "a".repeat(64))
        val secondOwner = firstOwner.copy(ownerProof = "b".repeat(64))
        val first = checkNotNull(f.coordinator.check(instance, firstOwner, "first-route-work").ticket)
        assertEquals("first-route-work", f.coordinator.claim(first))
        f.coordinator.finish(first, true)
        assertEquals(AbemaRenewalReadinessCoordinator.Status.READY,
            f.coordinator.check(instance, firstOwner, "ignored").status)
        val second = checkNotNull(f.coordinator.check(instance, secondOwner, "second-route-work").ticket)
        assertNotEquals(first, second)
        assertEquals("second-route-work", f.coordinator.claim(second))
        assertNull(f.coordinator.claim(first))
        assertTrue(f.coordinator.current(second))
    }
    @Test fun delayedOldCandidateFailureCannotCancelTheNewTicketButExactOwnerRevocationStillDoes() {
        var nonce = 0
        val coordinator = AbemaRenewalReadinessCoordinator<AbemaReadinessTarget>({ 100 },
            { (++nonce).toString(16).padStart(32, '0') })
        val instance = "00000000-0000-0000-0000-000000000001"
        val firstOwner = AbemaReadinessTarget(instance, "a".repeat(64))
        val secondOwner = firstOwner.copy(ownerProof = "b".repeat(64))
        val first = checkNotNull(coordinator.check(instance, firstOwner, firstOwner).ticket)
        val second = checkNotNull(coordinator.check(instance, secondOwner, secondOwner).ticket)
        assertFalse(coordinator.current(first))
        coordinator.invalidate(instance) { it.ownerProof == firstOwner.ownerProof }
        assertTrue(coordinator.current(second))
        assertEquals(secondOwner, coordinator.claim(second))
        coordinator.invalidate(instance) { it.ownerProof == secondOwner.ownerProof }
        assertFalse(coordinator.current(second))
        coordinator.finish(second, true)
        assertEquals(AbemaRenewalReadinessCoordinator.Status.FAILURE,
            coordinator.check(instance, secondOwner, secondOwner).status)
    }
    @Test fun staleClaimedBrowserKeepsExclusiveReservationUntilItsOwnerFinishes() {
        val f = Fixture(); val old = checkNotNull(f.coordinator.check("a", "old", "private").ticket)
        assertEquals("private", f.coordinator.claim(old))
        assertEquals(AbemaRenewalReadinessCoordinator.Status.WAIT, f.coordinator.check("a", "new", "new").status)
        assertFalse(f.coordinator.current(old))
        f.coordinator.cancelUnclaimed(old)
        assertEquals(AbemaRenewalReadinessCoordinator.Status.WAIT, f.coordinator.check("b", "other", "other").status)
        f.coordinator.finish(old, false)
        assertEquals(AbemaRenewalReadinessCoordinator.Status.LAUNCH, f.coordinator.check("a", "new", "new").status)
    }
    @Test fun forgetInvalidatesAlreadyClaimedFlightAndNeverLaundersLateCompletion() {
        val f = Fixture(); val old = checkNotNull(f.coordinator.check("a", "record", "private").ticket)
        assertEquals("private", f.coordinator.claim(old)); f.coordinator.invalidate("a")
        f.coordinator.finish(old, true)
        assertFalse(f.coordinator.current(old))
        assertEquals(AbemaRenewalReadinessCoordinator.Status.FAILURE, f.coordinator.check("a", "record", "private").status)
    }
    @Test fun originalDeadlineSurvivesJoiningClaimAndLateSuccess() {
        val f = Fixture(); val old = checkNotNull(f.coordinator.check("a", "record", "private", 150).ticket)
        f.now = 149; assertEquals("private", f.coordinator.claim(old))
        assertEquals(AbemaRenewalReadinessCoordinator.Status.WAIT, f.coordinator.check("a", "record", "private", 5000).status)
        f.now = 150; assertFalse(f.coordinator.current(old)); f.coordinator.finish(old, true)
        assertEquals(AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE, f.coordinator.check("a", "record", "private").status)
        assertNull(f.coordinator.claim(old))
    }
    @Test fun rollbackIsStickyAcrossClaimAndReturnAndCannotCreateAnotherTicket() {
        val f = Fixture(); val old = checkNotNull(f.coordinator.check("a", "record", "private").ticket)
        f.now--; assertFalse(f.coordinator.current(old)); f.now += 100
        assertNull(f.coordinator.claim(old)); f.coordinator.finish(old, true)
        assertEquals(AbemaRenewalReadinessCoordinator.Status.FAILURE, f.coordinator.check("a", "record", "private").status)
        assertEquals(1, f.nonce)
    }
    @Test fun invalidTicketExpiredDeadlineAndUnclaimedSuccessNeverAdmit() {
        val f = Fixture(); assertNull(f.coordinator.claim("unknown"))
        assertEquals(AbemaRenewalReadinessCoordinator.Status.FAILURE, f.coordinator.check("a", "record", "private", f.now).status)
        val old = checkNotNull(f.coordinator.check("a", "record", "private").ticket)
        f.coordinator.finish(old, true)
        assertEquals(AbemaRenewalReadinessCoordinator.Status.FAILURE, f.coordinator.check("a", "record", "private").status)
        assertEquals("AbemaRenewalReadinessCoordinator(redacted)", f.coordinator.toString())
        assertEquals("AbemaRenewalDecision(FAILURE)", f.coordinator.check("a", "record", "private").toString())
    }
}
