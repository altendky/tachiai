package net.fstab.tachiai.provider.abema.account

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AbemaReadinessRecordAdmissionTest {
    @Test fun publicationDuringProtectedOwnerOrRevisionCheckJoinsInsteadOfInvalidating() {
        for (duringOwnerCheck in listOf(true, false)) {
            val storage = MemoryAbemaAccountStorage()
            val store = AbemaAccountGrantStore(UUID.randomUUID().toString(), storage)
            val old = decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1_000_000)
            store.replace(old, 1_000_000, store.revision()) { true }
            val snapshot = store.readSnapshot()
            val coordinator = AbemaRenewalReadinessCoordinator<String>({ 100 }, { "a".repeat(32) })
            // The real service's first fast-path observation found no claim,
            // then captured the predecessor snapshot above.
            assertNull(coordinator.observeClaimed("instance") { true })
            var ticket: String? = null
            var invalidated = false
            var revisionChecks = 0
            fun publish() {
                ticket = checkNotNull(coordinator.check("instance", "predecessor", "owner").ticket)
                assertEquals("owner", coordinator.claim(checkNotNull(ticket)))
                val replacement = decodedAbemaGrant(fixtureAccountToken("intended-account", "3000"), "intended-account", 2_000_001)
                assertTrue(withAbemaAccountReplacement(store, replacement, replacement.userId, snapshot.revision,
                    1_000_000, { 2_000_001 }, canCommit = { true }) { receipt, _ ->
                    abemaReplacementReceiptCurrent(store, receipt)
                })
            }
            val decision = abemaReadinessRecordAdmission(
                ownerCurrent = { if (duringOwnerCheck) publish(); true },
                claimed = { coordinator.observeClaimed("instance") { it == "owner" } },
                recordCurrent = {
                    revisionChecks++
                    if (!duringOwnerCheck) publish()
                    store.usable(snapshot.revision)
                }, refused = {
                    invalidated = true; coordinator.invalidate("instance")
                    AbemaRenewalReadinessCoordinator.Decision(AbemaRenewalReadinessCoordinator.Status.FAILURE)
                })
            assertEquals(AbemaRenewalReadinessCoordinator.Status.WAIT, decision!!.status)
            assertFalse(invalidated)
            assertTrue(coordinator.current(checkNotNull(ticket)))
            assertEquals(if (duringOwnerCheck) 0 else 1, revisionChecks)
            assertTrue(store.read()!!.grant.expiresAtMs > 2_000_001)
        }
    }

    @Test fun actualOwnerLossAndUnclaimedStaleRecordStillRefuse() {
        for (ownerMatches in listOf(true, false)) {
            var refused = 0
            val decision = abemaReadinessRecordAdmission({ ownerMatches }, { null }, { false }, {
                refused++; AbemaRenewalReadinessCoordinator.Decision(AbemaRenewalReadinessCoordinator.Status.FAILURE)
            })
            assertEquals(AbemaRenewalReadinessCoordinator.Status.FAILURE, decision!!.status)
            assertEquals(1, refused)
        }
        assertNull(abemaReadinessRecordAdmission({ true }, { null }, { true }, {
            fail("Current unclaimed record was refused"); AbemaRenewalReadinessCoordinator.Decision(AbemaRenewalReadinessCoordinator.Status.FAILURE)
        }))
    }
}
