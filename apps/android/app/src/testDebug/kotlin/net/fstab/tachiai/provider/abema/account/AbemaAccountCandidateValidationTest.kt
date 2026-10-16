package net.fstab.tachiai.provider.abema.account

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AbemaAccountCandidateValidationTest {
    @Test fun sameCandidateSharesProfileValidationButAnotherCandidateMustValidateItsOwnAccess() {
        val instance = UUID.randomUUID().toString()
        val storage = MemoryAbemaAccountStorage()
        val store = AbemaAccountGrantStore(instance, storage)
        store.replace(decodedAbemaGrant(fixtureAccountToken(), "intended-account", 10000), 10000, store.revision()) { true }
        val saved = storage.bytes!!.copyOf()
        val registry = AbemaAccountAccessOwnerRegistry()
        val profiles = AtomicInteger(); val emails = AtomicInteger(); val closes = AtomicInteger()
        var allowed = true
        fun owner(proof: String) = registry.owner(instance, proof) {
            AbemaAccountAccessOwner(instance, proof, store, { 10000L }, { 1000L }, { true }, { gate ->
                object : AbemaAccountTransport {
                    override fun profile(id: String, bearer: String): AbemaAccountProfileResult {
                        assertTrue(gate()); profiles.incrementAndGet()
                        return if (allowed) AbemaAccountProfileResult.Identity(id) else AbemaAccountProfileResult.Rejected
                    }
                    override fun emailRegistration(id: String, bearer: String): AbemaAccountEmailResult {
                        assertTrue(gate()); emails.incrementAndGet(); return AbemaAccountEmailResult.REGISTERED
                    }
                    override fun token(challenge: AbemaAccountChallenge): AbemaAccountTokenResult = error("Validation must not issue a token")
                    override fun cancelActiveRequest() = Unit
                    override fun close() { closes.incrementAndGet() }
                }
            }, { true })
        }
        val firstProof = "a".repeat(64); val secondProof = "b".repeat(64)
        val firstOwner = owner(firstProof)
        val first = firstOwner.attach(); val peer = owner(firstProof).attach()
        try {
            assertSame(firstOwner, owner(firstProof))
            val firstLease = (first.acquire() as AbemaAccountAccessResult.Account).lease
            val peerLease = (peer.acquire() as AbemaAccountAccessResult.Account).lease
            assertEquals(1, profiles.get()); assertEquals(1, emails.get()); assertEquals(1, closes.get())
            // The grant and accepted first-candidate proof remain alive. A
            // different candidate must still make its own profile request.
            allowed = false
            val secondOwner = owner(secondProof); assertNotSame(firstOwner, secondOwner)
            val rejected = secondOwner.attach()
            try {
                assertEquals(AbemaAccountFailure.REFUSED, (rejected.acquire() as AbemaAccountAccessResult.Failed).category)
                assertEquals(2, profiles.get()); assertEquals(1, emails.get()); assertEquals(2, closes.get())
                assertTrue(firstLease.checkWorker()); assertTrue(peerLease.checkWorker())
                assertTrue((peer.acquire() as AbemaAccountAccessResult.Account).lease.checkWorker())
                assertEquals(2, profiles.get()); assertArrayEquals(saved, storage.bytes)
            } finally { rejected.close() }
        } finally { first.close(); peer.close() }
    }
}
