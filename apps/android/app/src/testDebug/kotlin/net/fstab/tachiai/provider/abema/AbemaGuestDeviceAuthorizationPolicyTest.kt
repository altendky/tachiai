package net.fstab.tachiai.provider.abema

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class AbemaGuestDeviceAuthorizationPolicyTest {
    private val operation = AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION
    private val guest = URI(AbemaGuestBootstrapPolicy.GUEST)
    private val profile = URI("${AbemaGuestBootstrapPolicy.PROFILE_ORIGIN}/v1/users/FIXTURE_USER")
    private val challenge = URI(AbemaGuestBootstrapPolicy.CHALLENGE)
    private val preflight = mapOf("Origin" to "https://abema.tv", "Access-Control-Request-Method" to "GET",
        "Access-Control-Request-Headers" to "authorization")

    @Test fun challengePurposeAllowsOnlyTheExactPOSTAndProfileWithoutBroadeningExistingOperations() {
        assertTrue(AbemaGuestBootstrapPolicy.allows(challenge, false, "POST", operation))
        for (method in listOf("GET", "OPTIONS", "PUT", "DELETE", "HEAD"))
            assertFalse(AbemaGuestBootstrapPolicy.allows(challenge, false, method, operation))
        assertFalse(AbemaGuestBootstrapPolicy.allows(challenge, true, "POST", operation))
        for (other in AbemaGuestBootstrapOperation.entries.filter { it !in setOf(operation, AbemaGuestBootstrapOperation.OBSERVE_DEVICE_TOKEN) }) {
            val id = if (other == AbemaGuestBootstrapOperation.FETCH_EPISODE) "fixture-episode" else null
            assertFalse(AbemaGuestBootstrapPolicy.allows(challenge, false, "POST", other, id))
            assertFalse(AbemaGuestBootstrapPolicy.contentSecurityPolicy(other, id).contains(AbemaGuestBootstrapPolicy.CHALLENGE))
        }
        for (method in listOf("GET", "OPTIONS"))
            assertTrue(AbemaGuestBootstrapPolicy.allows(profile, false, method, operation))
        assertFalse(AbemaGuestBootstrapPolicy.allows(URI(AbemaGuestBootstrapPolicy.EPISODE), false, "GET", operation))
        for (url in listOf("$challenge?extra=1", "$challenge#fragment", "$challenge/", "$challenge/child",
            "https://abema.tv:443/api/auth/oauth/device/authorization", "https://fixture@abema.tv/api/auth/oauth/device/authorization",
            "https://abema.tv/api/auth/oauth/device/%61uthorization", "https://abema.tv/api/auth/oauth/device/../device/authorization",
            "http://abema.tv/api/auth/oauth/device/authorization", "https://other.abema.tv/api/auth/oauth/device/authorization",
            "https://abema.tv/api/auth/oauth/device/token", "https://abema.tv/api/auth/accessToken",
            "https://abema.tv/account/transfer", "https://api.p-c3-e.abema-tv.com/v1/channels"))
            for (method in listOf("GET", "POST", "OPTIONS"))
                assertFalse(AbemaGuestBootstrapPolicy.allows(URI(url), false, method, operation))
        val csp = AbemaGuestBootstrapPolicy.contentSecurityPolicy(operation)
        assertTrue(csp.contains("connect-src ${AbemaGuestBootstrapPolicy.GUEST} ${AbemaGuestBootstrapPolicy.PROFILE_ORIGIN}/v1/users/ ${AbemaGuestBootstrapPolicy.CHALLENGE};"))
        assertTrue(csp.contains("media-src 'none'")); assertTrue(csp.contains("form-action 'none'"))
        assertFalse(csp.contains("/device/token")); assertFalse(csp.contains("/accessToken")); assertFalse(csp.contains("/video/"))
    }

    @Test fun admissionRequiresGuestThenOneMatchingProfileGETThenOneChallengePOST() {
        for (withPreflight in listOf(false, true)) {
            val admission = AbemaGuestRequestAdmission(operation)
            assertFalse(admission.admit(challenge, false, "POST"))
            assertFalse(admission.admit(profile, false, "GET"))
            assertTrue(admission.admit(guest, false, "POST")); assertFalse(admission.admit(guest, false, "POST"))
            assertFalse(admission.admit(challenge, false, "POST"))
            if (withPreflight) {
                assertFalse(admission.admit(profile, false, "OPTIONS", preflight + ("Access-Control-Request-Method" to "POST")))
                assertTrue(admission.admit(profile, false, "OPTIONS", preflight))
                assertFalse(admission.admit(challenge, false, "POST"))
                assertFalse(admission.admit(profile, false, "OPTIONS", preflight))
                assertFalse(admission.admit(URI("${AbemaGuestBootstrapPolicy.PROFILE_ORIGIN}/v1/users/OTHER"), false, "GET"))
            }
            assertTrue(admission.admit(profile, false, "GET"))
            assertFalse(admission.admit(profile, false, "GET"))
            assertFalse(admission.admit(profile, false, "OPTIONS", preflight))
            assertFalse(admission.admit(challenge, false, "OPTIONS", preflight))
            assertTrue(admission.admit(challenge, false, "POST"))
            assertFalse(admission.admit(challenge, false, "POST"))
            assertFalse(admission.admit(guest, false, "POST"))
            assertFalse(admission.toString().contains("FIXTURE_USER"))
        }
    }

    @Test fun challengePOSTAdmissionNeverReadsBearerHeadersOrIntroducesAProfileOrChallengeProjection() {
        assertTrue(AbemaGuestBootstrapPolicy.corsHeaders("POST") { error("Do not read a challenge bearer") }.isEmpty())
        val admission = AbemaGuestRequestAdmission(operation)
        assertTrue(admission.admit(guest, false, "POST"))
        assertTrue(admission.admit(profile, false, "GET"))
        assertTrue(admission.admit(challenge, false, "POST"))
        assertEquals("AbemaGuestRequestAdmission(redacted)", admission.toString())
    }
}
