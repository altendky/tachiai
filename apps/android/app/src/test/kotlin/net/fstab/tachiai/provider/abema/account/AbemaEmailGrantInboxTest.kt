package net.fstab.tachiai.provider.abema.account

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class AbemaEmailGrantInboxTest {
    private val nonce = "01".repeat(16)
    private val instance = "00000000-0000-0000-0000-000000000001"
    private val otherInstance = "00000000-0000-0000-0000-000000000002"
    private val proof = "01".repeat(32)
    private val deadline = 1000L
    private fun grant(id: String = "fixture-account", expirySeconds: Long = 10): AbemaAccountGrant {
        val claims = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"sub\":\"$id\",\"exp\":$expirySeconds}".toByteArray())
        return AbemaAccountGrant(id, "e30.$claims.c2ln", expirySeconds * 1000)
    }
    private fun fields(value: AbemaAccountGrant = grant()): Map<String, Any> = mapOf(
        "nonce" to nonce, "instance" to instance, "proof" to proof, "deadline" to deadline,
        "userId" to value.userId, "bearer" to value.bearer, "expiry" to value.expiresAtMs)

    @Test fun correctBindingDeliversOnceAndHidesPrivateValues() {
        val inbox = AbemaEmailGrantInbox({ 100 }, { 1000 })
        val received = mutableListOf<AbemaAccountGrant>()
        inbox.register(nonce, instance, proof, deadline, received::add)
        val value = grant()
        assertTrue(inbox.deliver(nonce, instance, proof, deadline, value))
        assertSame(value, received.single())
        assertFalse(inbox.deliver(nonce, instance, proof, deadline, value))
        assertEquals("AbemaEmailGrantInbox(redacted)", inbox.toString())
    }

    @Test fun wrongNonceInstanceOwnerOrDeadlineCannotConsumeTheRegistration() {
        val inbox = AbemaEmailGrantInbox({ 100 }, { 1000 })
        var calls = 0
        inbox.register(nonce, instance, proof, deadline) { calls++ }
        assertFalse(inbox.deliver("02".repeat(16), instance, proof, deadline, grant()))
        assertFalse(inbox.deliver(nonce, otherInstance, proof, deadline, grant()))
        assertFalse(inbox.deliver(nonce, instance, "02".repeat(32), deadline, grant()))
        assertFalse(inbox.deliver(nonce, instance, proof, deadline + 1, grant()))
        assertEquals(0, calls)
        assertTrue(inbox.deliver(nonce, instance, proof, deadline, grant()))
        assertEquals(1, calls)
    }

    @Test fun cancellationAndOriginalDeadlinePreventDelivery() {
        var now = 100L
        val inbox = AbemaEmailGrantInbox({ now }, { 1000 })
        var calls = 0
        inbox.register(nonce, instance, proof, deadline) { calls++ }
        inbox.unregister(nonce)
        assertFalse(inbox.deliver(nonce, instance, proof, deadline, grant()))
        inbox.register(nonce, instance, proof, deadline) { calls++ }
        now = deadline
        assertFalse(inbox.deliver(nonce, instance, proof, deadline, grant()))
        now = 100
        assertFalse(inbox.deliver(nonce, instance, proof, deadline, grant()))
        assertEquals(0, calls)
    }

    @Test fun expiredMalformedOrInconsistentGrantNeverReachesReceiver() {
        val inbox = AbemaEmailGrantInbox({ 100 }, { 1000 })
        var calls = 0
        inbox.register(nonce, instance, proof, deadline) { calls++ }
        val valid = grant()
        for (value in listOf(grant(expirySeconds = 1), AbemaAccountGrant("other", valid.bearer, valid.expiresAtMs),
            AbemaAccountGrant(valid.userId, valid.bearer, valid.expiresAtMs + 1),
            AbemaAccountGrant(valid.userId, "not-a-jwt", valid.expiresAtMs)))
            assertFalse(inbox.deliver(nonce, instance, proof, deadline, value))
        assertEquals(0, calls)
        assertTrue(inbox.deliver(nonce, instance, proof, deadline, valid))
    }

    @Test fun malformedOrDuplicateRegistrationIsRefused() {
        val inbox = AbemaEmailGrantInbox({ 100 }, { 1000 })
        for ((badNonce, badInstance, badProof) in listOf(Triple("", instance, proof),
            Triple("AB".repeat(16), instance, proof), Triple(nonce, "abema", proof))) {
            assertThrows(IllegalArgumentException::class.java) {
                inbox.register(badNonce, badInstance, badProof, deadline) { fail() }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { inbox.register(nonce, instance, proof, 100) { fail() } }
        inbox.register(nonce, instance, proof, deadline) { }
        assertThrows(IllegalArgumentException::class.java) { inbox.register(nonce, instance, proof, deadline) { fail() } }
    }

    @Test fun privateEnvelopeRequiresSameUidClosedSchemaAndVerifiedGrant() {
        val parsed = AbemaEmailGrantEnvelope.decode(fields(), 10000, 10000, 1000)
        assertNotNull(parsed)
        assertEquals(nonce, parsed!!.nonce)
        assertEquals("AbemaEmailGrantEnvelope(redacted)", parsed.toString())
        assertNull(AbemaEmailGrantEnvelope.decode(fields(), 10001, 10000, 1000))
        assertNull(AbemaEmailGrantEnvelope.decode(fields(), -1, -1, 1000))
        for (invalid in listOf(fields() + ("extra" to "value"), fields() - "bearer",
            fields() + ("expiry" to 10001L), fields() + ("userId" to "other"),
            fields() + ("nonce" to "bad"), fields() + ("proof" to "bad"),
            fields() + ("instance" to "bad"), fields() + ("deadline" to -1L),
            fields() + ("bearer" to "bad"), fields() + ("expiry" to "10000")))
            assertNull(AbemaEmailGrantEnvelope.decode(invalid, 10000, 10000, 1000))
        assertNull(AbemaEmailGrantEnvelope.decode(fields(), 10000, 10000, 10000))
    }
}
