package net.fstab.tachiai.provider.abema

import org.junit.Assert.*
import org.junit.Test

class AbemaSlotAuthorityReceiptTest {
    private fun scope(client: Any = Any(), run: Long = 1, deadline: Long = 5000) =
        AbemaSlotAuthorityScope(client, "instance", "route-owner", "exact-source", "channel", "slot", run, deadline)
    private fun receipt(scope: AbemaSlotAuthorityScope, expiry: Long = 10000) = checkNotNull(
        AbemaSlotAuthorityReceipt.publish(scope, expiry, 1000, 100, 1000, 1200, 200))

    @Test fun freshPublicationUsesOriginalReadinessAndPlaybackBudgets() {
        val owner = scope()
        assertNull(AbemaSlotAuthorityReceipt.publish(owner, 10000, 1000, 100, 1000, 1200, 1000))
        assertNull(AbemaSlotAuthorityReceipt.publish(owner, 10000, 1000, 100, 6000, 1200, 200))
        assertNull(AbemaSlotAuthorityReceipt.publish(owner, 10000, 1000, 100, 1000, 999, 200))
        assertNull(AbemaSlotAuthorityReceipt.publish(owner, 10000, 1000, 100, 1000, 1200, 99))
        assertNull(AbemaSlotAuthorityReceipt.publish(owner, 1200, 1000, 100, 1000, 1200, 200))
        assertNull(AbemaSlotAuthorityReceipt.publish(owner, Long.MAX_VALUE, 1000, 100, 1000, 1200, 200))
        assertTrue(receipt(owner).current(owner, 1300, 300))
    }

    @Test fun originalPlaybackDeadlineAndSubscriptionExpiryRemainIndependentAndSticky() {
        val owner = scope()
        val budget = receipt(owner)
        assertTrue(budget.current(owner, 1500, 4999))
        assertFalse(budget.current(owner, 1500, 5000))
        assertFalse(budget.current(owner, 1500, 4000))
        val expiry = receipt(owner, 2000)
        assertTrue(expiry.current(owner, 1999, 300))
        assertFalse(expiry.current(owner, 2000, 300))
        assertFalse(expiry.current(owner, 1999, 300))
    }

    @Test fun wallAndMonotonicRollbackEndAuthorityWithoutStorageReads() {
        for (wallRollback in listOf(true, false)) {
            val owner = scope(); val proof = receipt(owner)
            assertTrue(proof.current(owner, 1500, 500))
            assertFalse(proof.current(owner, if (wallRollback) 1499 else 1500,
                if (wallRollback) 500 else 499))
            assertFalse(proof.current(owner, 1600, 600))
        }
    }

    @Test fun authorityCannotMoveToAnotherClientSourceRouteSlotOrRun() {
        val owner = scope()
        val replacements = listOf(scope(), scope(owner.client, run = 2),
            AbemaSlotAuthorityScope(owner.client, owner.instanceId, "replacement-route", owner.sourceProof,
                owner.channelId, owner.slotId, owner.run, owner.playbackDeadline),
            AbemaSlotAuthorityScope(owner.client, owner.instanceId, owner.ownerProof, "replacement-source",
                owner.channelId, "other-slot", owner.run, owner.playbackDeadline),
            scope(owner.client))
        for (replacement in replacements) {
            val proof = receipt(owner)
            assertFalse(proof.current(replacement, 1300, 300))
            assertFalse(proof.current(owner, 1400, 400))
        }
        assertFalse(owner.toString().contains("route-owner"))
        assertFalse(receipt(owner).toString().contains("10000"))
    }
}
