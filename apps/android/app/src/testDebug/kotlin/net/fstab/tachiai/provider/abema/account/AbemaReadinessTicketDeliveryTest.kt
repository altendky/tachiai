package net.fstab.tachiai.provider.abema.account

import org.junit.Assert.*
import org.junit.Test

class AbemaReadinessTicketDeliveryTest {
    private class Fixture {
        var nonce = 0
        val coordinator = AbemaRenewalReadinessCoordinator<String>({ 100 }, { (++nonce).toString(16).padStart(32, '0') })
        val delivery = AbemaReadinessTicketDelivery(coordinator::cancelUnclaimed)
        fun check() = coordinator.check("instance", "same-record", "private-work")
    }
    @Test fun serviceDestroyedBeforeWorkerProducesReplyCancelsUnclaimedCheckWithoutCooldown() {
        val f = Fixture(); f.delivery.close()
        val late = f.check()
        assertFalse(f.delivery.created(late)); assertNull(f.coordinator.claim(checkNotNull(late.ticket)))
        assertEquals(AbemaRenewalReadinessCoordinator.Status.LAUNCH, f.check().status)
    }
    @Test fun destroyingServiceWithQueuedMainReplyReleasesReservationEvenWhenRunnableNeverRuns() {
        val f = Fixture(); val queued = f.check()
        assertTrue(f.delivery.created(queued)); f.delivery.close()
        assertFalse(f.coordinator.current(checkNotNull(queued.ticket)))
        assertEquals(AbemaRenewalReadinessCoordinator.Status.LAUNCH, f.check().status)
        f.delivery.drop(queued)
    }
    @Test fun deadlineOrSendFailureDropsOnlyItsExactUnclaimedTicket() {
        val f = Fixture(); val dropped = f.check()
        assertTrue(f.delivery.created(dropped)); f.delivery.drop(dropped)
        val replacement = f.check(); assertTrue(f.delivery.created(replacement))
        f.delivery.drop(dropped)
        assertEquals("private-work", f.coordinator.claim(checkNotNull(replacement.ticket)))
    }
    @Test fun successfulDeliveryTransfersTicketAndDestroyCannotCancelClaimedBrowserOwner() {
        val f = Fixture(); val sent = f.check()
        assertTrue(f.delivery.created(sent)); f.delivery.delivered(sent); f.delivery.close()
        val ticket = checkNotNull(sent.ticket)
        assertEquals("private-work", f.coordinator.claim(ticket))
        f.delivery.drop(sent)
        assertTrue(f.coordinator.current(ticket))
        assertEquals(AbemaRenewalReadinessCoordinator.Status.WAIT, f.check().status)
    }
    @Test fun staleExactSameUidLaunchCancelsAfterDetachAndAllowsImmediateSameRecordSuccessor() {
        val f = Fixture(); val late = f.check()
        val nonce = "a".repeat(32)
        val fields = mapOf<String, Any?>("nonce" to nonce, "sequence" to 1L,
            "status" to "LAUNCH", "ticket" to late.ticket)
        assertTrue(AbemaReadinessIpc.cancelStaleLaunch(fields, nonce, 1, 42, 42, f.coordinator::cancelUnclaimed))
        assertNull(f.coordinator.claim(checkNotNull(late.ticket)))
        assertEquals(AbemaRenewalReadinessCoordinator.Status.LAUNCH, f.check().status)
        assertEquals("AbemaReadinessTicketDelivery(redacted)", f.delivery.toString())
    }
    @Test fun staleReplyCannotCancelForeignNonceSequenceUidOrMalformedAndNonLaunchMessages() {
        val f = Fixture(); val active = f.check(); val nonce = "a".repeat(32)
        val fields = mapOf<String, Any?>("nonce" to nonce, "sequence" to 1L, "status" to "LAUNCH", "ticket" to active.ticket)
        for ((returned, sender, own) in listOf(Triple(fields + ("nonce" to "b".repeat(32)), 42, 42),
            Triple(fields + ("sequence" to 2L), 42, 42), Triple(fields, 41, 42), Triple(fields, -1, 42),
            Triple(fields + ("extra" to true), 42, 42), Triple(fields + ("ticket" to "malformed"), 42, 42),
            Triple(fields - "ticket" + ("status" to "WAIT"), 42, 42)))
            assertFalse(AbemaReadinessIpc.cancelStaleLaunch(returned, nonce, 1, sender, own, f.coordinator::cancelUnclaimed))
        assertEquals("private-work", f.coordinator.claim(checkNotNull(active.ticket)))
    }
}
