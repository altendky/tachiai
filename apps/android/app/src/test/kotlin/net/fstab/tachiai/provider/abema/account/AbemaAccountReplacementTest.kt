package net.fstab.tachiai.provider.abema.account

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AbemaAccountReplacementTest {
    private class Fixture {
        val storage = MemoryAbemaAccountStorage()
        val store = AbemaAccountGrantStore(UUID.randomUUID().toString(), storage)
        val old = decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1_000_000)
        val next = decodedAbemaGrant(fixtureAccountToken("intended-account", "3000"), "intended-account", 2_000_001)
        init { store.replace(old, 1_000_000, store.revision()) { true } }
        val expected = store.readSnapshot()
        val prior = checkNotNull(storage.bytes).copyOf()
    }
    @Test fun acceptedReceiptProvesReplacementRatherThanObsoletePredecessor() {
        val f = Fixture()
        assertTrue(withAbemaAccountReplacement(f.store, f.next, f.next.userId, f.expected.revision, 1_000_000,
            { 2_000_001 }, canCommit = { true }) { receipt, _ ->
            assertFalse(abemaRenewalSnapshotMatches(f.expected, f.store.readSnapshot(), 2_000_001))
            abemaReplacementReceiptCurrent(f.store, receipt)
        })
        assertEquals(f.next.bearer, f.store.read()!!.grant.bearer)
    }
    @Test fun rejectingPublishedReceiptRollsBackBeforeReturning() {
        val f = Fixture(); var restored: AbemaAccountSnapshot? = null
        assertFalse(withAbemaAccountReplacement(f.store, f.next, f.next.userId, f.expected.revision, 1_000_000,
            { 2_000_001 }, onRestored = { restored = it }, canCommit = { true }) { _, _ -> false })
        assertArrayEquals(f.prior, f.storage.bytes); assertNotNull(restored)
        assertTrue(f.store.usable(restored!!.revision))
    }
    @Test fun laterForgetCannotBeUndoneByEarlierPublishedReceipt() {
        val f = Fixture()
        assertFalse(withAbemaAccountReplacement(f.store, f.next, f.next.userId, f.expected.revision, 1_000_000,
            { 2_000_001 }, canCommit = { true }) { receipt, _ ->
            f.store.invalidateForget(); f.store.forget()
            assertFalse(abemaReplacementReceiptCurrent(f.store, receipt)); false
        })
        assertNull(f.store.read())
    }
    @Test fun concurrentReadinessPollWaitsWhileValidReplacementIsPublishedButNotAccepted() {
        val f = Fixture()
        val coordinator = AbemaRenewalReadinessCoordinator<String>({ 100 }, { "a".repeat(32) })
        val ticket = checkNotNull(coordinator.check("instance", "predecessor", "owner").ticket)
        assertEquals("owner", coordinator.claim(ticket))
        val polling = Executors.newSingleThreadExecutor()
        try {
            assertTrue(withAbemaAccountReplacement(f.store, f.next, f.next.userId, f.expected.revision, 1_000_000,
                { 2_000_001 }, canCommit = { coordinator.current(ticket) }) { receipt, _ ->
                polling.submit<Boolean> {
                    assertTrue(f.store.read()!!.grant.expiresAtMs > 2_000_001)
                    assertEquals(AbemaRenewalReadinessCoordinator.Status.WAIT,
                        coordinator.observeClaimed("instance") { it == "owner" }!!.status)
                    assertTrue(coordinator.current(ticket))
                    abemaReplacementReceiptCurrent(f.store, receipt)
                }.get(2, TimeUnit.SECONDS)
            })
            coordinator.finish(ticket, true)
            assertNull(coordinator.observeClaimed("instance") { true })
        } finally { polling.shutdownNow(); assertTrue(polling.awaitTermination(2, TimeUnit.SECONDS)) }
    }
    @Test fun expiredCandidateOrWrongProfileCannotReachPublication() {
        for (wrongProfile in listOf(true, false)) {
            val f = Fixture()
            val accepted = runCatching {
                withAbemaAccountReplacement(f.store, f.next, if (wrongProfile) "other" else f.next.userId,
                    f.expected.revision, 1_000_000, { if (wrongProfile) 2_000_001 else 3_000_000 },
                    canCommit = { true }) { _, _ -> fail("Invalid candidate was published"); true }
            }.getOrDefault(false)
            assertFalse(accepted); assertArrayEquals(f.prior, f.storage.bytes)
        }
    }
}
