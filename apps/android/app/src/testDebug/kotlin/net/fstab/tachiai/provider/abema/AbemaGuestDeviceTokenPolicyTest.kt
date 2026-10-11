package net.fstab.tachiai.provider.abema

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class AbemaGuestDeviceTokenPolicyTest {
    private val operation = AbemaGuestBootstrapOperation.OBSERVE_DEVICE_TOKEN
    private val guest = URI(AbemaGuestBootstrapPolicy.GUEST)
    private val profile = URI("${AbemaGuestBootstrapPolicy.PROFILE_ORIGIN}/v1/users/FIXTURE_USER")
    private val challenge = URI(AbemaGuestBootstrapPolicy.CHALLENGE)
    private val token = URI(AbemaGuestBootstrapPolicy.DEVICE_TOKEN)
    private val preflight = mapOf("Origin" to "https://abema.tv", "Access-Control-Request-Method" to "GET",
        "Access-Control-Request-Headers" to "authorization")

    @Test fun onlyObservationPurposeAllowsExactTokenPOSTWithoutBroadeningOlderOperationsOrMedia() {
        assertTrue(AbemaGuestBootstrapPolicy.allows(token, false, "POST", operation))
        for (method in listOf("GET", "OPTIONS", "PUT", "DELETE", "HEAD"))
            assertFalse(AbemaGuestBootstrapPolicy.allows(token, false, method, operation))
        assertFalse(AbemaGuestBootstrapPolicy.allows(token, true, "POST", operation))
        for (other in AbemaGuestBootstrapOperation.entries.filter { it != operation }) {
            val id = if (other == AbemaGuestBootstrapOperation.FETCH_EPISODE) "fixture-episode" else null
            assertFalse(AbemaGuestBootstrapPolicy.allows(token, false, "POST", other, id))
            assertFalse(AbemaGuestBootstrapPolicy.contentSecurityPolicy(other, id).contains(AbemaGuestBootstrapPolicy.DEVICE_TOKEN))
        }
        for (url in listOf("$token?extra=1", "$token#fragment", "$token/", "$token/child",
            "https://abema.tv:443/api/auth/oauth/device/token", "https://fixture@abema.tv/api/auth/oauth/device/token",
            "https://abema.tv/api/auth/oauth/device/%74oken", "https://abema.tv/api/auth/oauth/device/../device/token",
            "http://abema.tv/api/auth/oauth/device/token", "https://other.abema.tv/api/auth/oauth/device/token",
            "https://abema.tv/api/auth/accessToken", "https://abema.tv/api/auth/oauth/exchange/token",
            "https://abema.tv/account/transfer", AbemaGuestBootstrapPolicy.EPISODE))
            for (method in listOf("GET", "POST", "OPTIONS"))
                assertFalse(AbemaGuestBootstrapPolicy.allows(URI(url), false, method, operation))
        val csp = AbemaGuestBootstrapPolicy.contentSecurityPolicy(operation)
        assertTrue(csp.contains("connect-src $guest ${AbemaGuestBootstrapPolicy.PROFILE_ORIGIN}/v1/users/ $challenge $token;"))
        assertTrue(csp.contains("media-src 'none'")); assertTrue(csp.contains("form-action 'none'"))
        assertFalse(csp.contains("/accessToken")); assertFalse(csp.contains("/video/"))
    }

    @Test fun oneTokenAdmissionRequiresGuestMatchingProfileAndChallengeWithoutRepeatOrFallback() {
        for (withPreflight in listOf(false, true)) {
            val admission = AbemaGuestRequestAdmission(operation)
            assertFalse(admission.admit(token, false, "POST")); assertFalse(admission.admit(challenge, false, "POST"))
            assertTrue(admission.admit(guest, false, "POST")); assertFalse(admission.admit(token, false, "POST"))
            if (withPreflight) {
                assertTrue(admission.admit(profile, false, "OPTIONS", preflight))
                assertFalse(admission.admit(token, false, "POST"))
                assertFalse(admission.admit(URI("${AbemaGuestBootstrapPolicy.PROFILE_ORIGIN}/v1/users/OTHER"), false, "GET"))
                assertFalse(admission.admit(profile, false, "OPTIONS", preflight))
            }
            assertTrue(admission.admit(profile, false, "GET")); assertFalse(admission.admit(token, false, "POST"))
            assertTrue(admission.admit(challenge, false, "POST")); assertFalse(admission.admit(token, false, "GET"))
            assertFalse(admission.admit(token, false, "OPTIONS", preflight))
            assertTrue(admission.admit(token, false, "POST")); assertFalse(admission.admit(token, false, "POST"))
            assertFalse(admission.admit(challenge, false, "POST")); assertFalse(admission.admit(profile, false, "GET"))
            assertFalse(admission.admit(guest, false, "POST"))
            assertEquals("AbemaGuestRequestAdmission(redacted)", admission.toString())
        }
    }

    @Test fun nativeAdmissionNeverReadsTokenBearerOrBody() {
        assertTrue(AbemaGuestBootstrapPolicy.corsHeaders("POST") { error("No private token request headers") }.isEmpty())
        val challengeOnly = AbemaGuestRequestAdmission(AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION)
        assertTrue(challengeOnly.admit(guest, false, "POST")); assertTrue(challengeOnly.admit(profile, false, "GET"))
        assertTrue(challengeOnly.admit(challenge, false, "POST")); assertFalse(challengeOnly.admit(token, false, "POST"))
    }
}
