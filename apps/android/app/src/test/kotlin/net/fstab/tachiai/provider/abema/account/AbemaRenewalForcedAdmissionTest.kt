package net.fstab.tachiai.provider.abema.account

import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AbemaRenewalForcedAdmissionTest {
    @After fun close() = AbemaRenewalForcedAdmission.close()

    @Test fun admitsOnlyTheTriggeredGenerationBeforeItsLeadUntilClosedOrExpired() {
        val store = AbemaAccountGrantStore(UUID.randomUUID().toString(), MemoryAbemaAccountStorage())
        store.replace(decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1_000_000), 1_000_000, store.revision()) { true }
        val snapshot = store.readSnapshot()
        val record = checkNotNull(snapshot.record)
        val generation = AbemaRenewalGeneration(record.generation, record.acceptedWallMs, record.grant.expiresAtMs)
        // Just after acceptance, well before the lead.
        val wall = record.acceptedWallMs + 1
        assertTrue(wall < record.grant.expiresAtMs - ABEMA_RENEWAL_LEAD_MS)
        assertFalse(abemaRenewalSnapshotMatches(snapshot, snapshot, wall))
        AbemaRenewalForcedAdmission.open(generation.copy(generation = record.generation + 1), 60_000)
        assertFalse(abemaRenewalSnapshotMatches(snapshot, snapshot, wall))
        AbemaRenewalForcedAdmission.open(generation, 60_000)
        assertTrue(abemaRenewalSnapshotMatches(snapshot, snapshot, wall))
        assertTrue(abemaRenewalLeadReached(record, wall))
        AbemaRenewalForcedAdmission.close()
        assertFalse(abemaRenewalSnapshotMatches(snapshot, snapshot, wall))
        AbemaRenewalForcedAdmission.open(generation, 0)
        assertFalse(abemaRenewalLeadReached(record, wall))
    }
}
