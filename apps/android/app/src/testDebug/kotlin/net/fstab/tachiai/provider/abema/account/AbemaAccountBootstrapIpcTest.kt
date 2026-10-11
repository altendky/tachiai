package net.fstab.tachiai.provider.abema.account

import java.net.URI
import net.fstab.tachiai.provider.abema.*
import org.junit.Assert.*
import org.junit.Test

class AbemaAccountBootstrapIpcTest {
    private val nonce = "1".repeat(32)
    private val instance = "12345678-1234-1234-1234-123456789abc"
    private fun result(): Map<String, Any> = fixtureAccountChallenge().let { mapOf("nonce" to nonce, "outcome" to "READY",
        "guestId" to it.guestId, "guestBearer" to it.guestBearer, "clientId" to it.clientId, "deviceCode" to it.deviceCode,
        "tokenBody" to it.tokenBody, "verificationUri" to it.verificationUri, "deadline" to it.deadlineElapsedMs) }
    @Test fun privateStartRequiresExactOwnerNonceInstanceAndBoundOriginalDeadline() {
        val fields = mapOf("nonce" to nonce, "instance" to instance, "proof" to "a".repeat(64), "deadline" to 46000L)
        assertNotNull(AbemaAccountBootstrapIpc.readStart(fields, 1000))
        for (bad in listOf(fields + ("deadline" to 46001L), fields + ("deadline" to 1000L), fields + ("proof" to "private"),
            fields + ("instance" to "other"), fields + ("extra" to "private"))) assertNull(AbemaAccountBootstrapIpc.readStart(bad, 1000))
        assertNull(AbemaAccountBootstrapIpc.readNonce(mapOf("nonce" to nonce, "extra" to "private")))
    }
    @Test fun privateResultRejectsExpiredMismatchedBindingWireUnknownFieldsAndOversizedDestination() {
        assertNotNull(AbemaAccountBootstrapIpc.readResult(result(), 1000)?.challenge)
        for (bad in listOf(result() + ("deadline" to 1000L), result() + ("deadline" to 3601001L),
            result() + ("clientId" to "other"), result() + ("tokenBody" to "{}"), result() + ("verificationUri" to "https://evil.invalid/"),
            result() + ("extra" to "private"), result() + ("outcome" to "CONNECTED")))
            assertNull(AbemaAccountBootstrapIpc.readResult(bad, 1000))
        val refused = AbemaAccountBootstrapIpc.readResult(mapOf("nonce" to nonce, "outcome" to "REFUSED"), 1000)
        assertNotNull(refused); assertNull(refused?.challenge)
    }
    @Test fun privatePreparationHasOnlyGuestProfileChallengeAndNeverWebTokenOrLoginNavigation() {
        val op = AbemaGuestBootstrapOperation.PREPARE_ACCOUNT_LINK
        val admission = AbemaGuestRequestAdmission(op)
        val guest = URI(AbemaGuestBootstrapPolicy.GUEST)
        val profile = URI("https://api.p-c3-e.abema-tv.com/v1/users/guest")
        val challenge = URI(AbemaGuestBootstrapPolicy.CHALLENGE)
        assertFalse(admission.admit(challenge, false, "POST"))
        assertTrue(admission.admit(guest, false, "POST")); assertFalse(admission.admit(guest, false, "POST"))
        assertTrue(admission.admit(profile, false, "OPTIONS", mapOf("Origin" to "https://abema.tv", "Access-Control-Request-Method" to "GET", "Access-Control-Request-Headers" to "authorization")))
        assertFalse(admission.admit(URI("https://api.p-c3-e.abema-tv.com/v1/users/other"), false, "GET"))
        assertTrue(admission.admit(profile, false, "GET")); assertTrue(admission.admit(challenge, false, "POST")); assertFalse(admission.admit(challenge, false, "POST"))
        assertFalse(admission.admit(URI(AbemaGuestBootstrapPolicy.DEVICE_TOKEN), false, "POST"))
        assertFalse(admission.admit(URI("https://abema.tv/api/auth/accessToken"), false, "POST"))
        assertFalse(admission.admit(URI("https://abema.tv/account/transfer"), true, "GET"))
        val csp = AbemaGuestBootstrapPolicy.contentSecurityPolicy(op)
        assertTrue(csp.contains(AbemaGuestBootstrapPolicy.CHALLENGE)); assertFalse(csp.contains(AbemaGuestBootstrapPolicy.DEVICE_TOKEN))
    }
}
