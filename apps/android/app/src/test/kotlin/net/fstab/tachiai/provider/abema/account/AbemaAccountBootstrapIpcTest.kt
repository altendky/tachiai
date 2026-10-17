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
    @Test fun validationStartIsClosedAndLeavesLegacyLinkStartUnchanged() {
        val old = mapOf("nonce" to nonce, "instance" to instance, "proof" to "a".repeat(64), "deadline" to 46000L)
        assertEquals(AbemaAccountBootstrapIpc.Mode.LINK, AbemaAccountBootstrapIpc.readStart(old, 1000)?.mode)
        val validation = old + ("operation" to "VALIDATION")
        assertEquals(AbemaAccountBootstrapIpc.Mode.VALIDATION, AbemaAccountBootstrapIpc.readStart(validation, 1000)?.mode)
        for (bad in listOf(old + ("operation" to "LINK"), old + ("operation" to "CONNECTED"),
            validation + ("extra" to "PRIVATE"), validation + ("deadline" to 46001L)))
            assertNull(AbemaAccountBootstrapIpc.readStart(bad, 1000))
        val fake = object : AbemaAccountBootstrap {
            override fun prepare(): AbemaAccountChallenge? = null
            override fun cancel() = Unit
            override fun close() = Unit
        }
        assertNull(fake.prepareValidation())
    }
    @Test fun validationResultRequiresMatchingModeNonceAndUnextendedOriginalDeadline() {
        val request = AbemaAccountBootstrapIpc.Start(nonce, instance, "a".repeat(64), 46000, AbemaAccountBootstrapIpc.Mode.VALIDATION)
        val fields = mapOf("nonce" to nonce, "outcome" to "VALIDATION_READY", "guestId" to "guest", "guestBearer" to "PRIVATE_BEARER", "deadline" to 46000L)
        val value = AbemaAccountBootstrapIpc.readResult(fields, 1000, request)!!
        assertNull(value.challenge); assertEquals(46000L, value.validation?.deadlineElapsedMs)
        assertFalse(value.toString().contains("PRIVATE")); assertFalse(value.validation.toString().contains("PRIVATE"))
        assertNull(AbemaAccountBootstrapIpc.readResult(fields, 1000))
        assertNull(AbemaAccountBootstrapIpc.readResult(result(), 1000, request))
        for (bad in listOf(fields + ("nonce" to "2".repeat(32)), fields + ("deadline" to 45999L),
            fields + ("deadline" to 46001L), fields + ("extra" to "PRIVATE"), fields + ("clientId" to "guest"),
            fields + ("guestBearer" to "PRIVATE\nSECRET"), fields + ("guestId" to "invalid/id"), fields + ("outcome" to "READY")))
            assertNull(AbemaAccountBootstrapIpc.readResult(bad, 1000, request))
        assertNull(AbemaAccountBootstrapIpc.readResult(fields, 46000, request))
        assertNull(AbemaAccountBootstrapIpc.readResult(fields, -1, request))
        assertNull(AbemaAccountBootstrapIpc.readResult(fields, 999, request))
        val refused = AbemaAccountBootstrapIpc.readResult(mapOf("nonce" to nonce, "outcome" to "REFUSED"), 1000, request)
        assertNotNull(refused); assertNull(refused?.challenge); assertNull(refused?.validation)
    }
    @Test fun validationAdmissionHasOneGuestAndBoundProfileOnlyWithNoChallengeTokenOrNavigation() {
        val op = AbemaGuestBootstrapOperation.PREPARE_ACCOUNT_VALIDATION
        val admission = AbemaGuestRequestAdmission(op)
        val guest = URI(AbemaGuestBootstrapPolicy.GUEST); val profile = URI("https://api.p-c3-e.abema-tv.com/v1/users/guest")
        val options = mapOf("Origin" to "https://abema.tv", "Access-Control-Request-Method" to "GET", "Access-Control-Request-Headers" to "authorization")
        assertFalse(admission.admit(profile, false, "GET")); assertFalse(admission.admit(profile, false, "OPTIONS", options))
        assertTrue(admission.admit(guest, false, "POST")); assertFalse(admission.admit(guest, false, "POST"))
        assertTrue(admission.admit(profile, false, "OPTIONS", options)); assertFalse(admission.admit(profile, false, "OPTIONS", options))
        assertFalse(admission.admit(URI("https://api.p-c3-e.abema-tv.com/v1/users/other"), false, "GET"))
        assertTrue(admission.admit(profile, false, "GET")); assertFalse(admission.admit(profile, false, "GET"))
        for (url in listOf(AbemaGuestBootstrapPolicy.CHALLENGE, AbemaGuestBootstrapPolicy.DEVICE_TOKEN,
            "https://abema.tv/api/auth/accessToken", AbemaGuestBootstrapPolicy.EPISODE))
            for (method in listOf("POST", "GET", "OPTIONS")) assertFalse(admission.admit(URI(url), false, method))
        assertFalse(admission.admit(URI("https://abema.tv/account/transfer"), true, "GET"))
        val csp = AbemaGuestBootstrapPolicy.contentSecurityPolicy(op)
        assertTrue(csp.contains(AbemaGuestBootstrapPolicy.GUEST)); assertTrue(csp.contains("/v1/users/"))
        assertFalse(csp.contains(AbemaGuestBootstrapPolicy.CHALLENGE)); assertFalse(csp.contains(AbemaGuestBootstrapPolicy.DEVICE_TOKEN))
    }
}
