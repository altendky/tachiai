package net.fstab.tachiai.provider.abema

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class AbemaGuestDeviceTokenPolicyTest {
    private val operation = AbemaMetadataRuntimeOperation.OBSERVE_DEVICE_TOKEN
    private val guest = URI(AbemaMetadataRuntimePolicy.GUEST)
    private val profile = URI("${AbemaMetadataRuntimePolicy.PROFILE_ORIGIN}/v1/users/FIXTURE_USER")
    private val challenge = URI(AbemaMetadataRuntimePolicy.CHALLENGE)
    private val token = URI(AbemaMetadataRuntimePolicy.DEVICE_TOKEN)
    private val preflight = mapOf("Origin" to "https://abema.tv", "Access-Control-Request-Method" to "GET",
        "Access-Control-Request-Headers" to "authorization")

    @Test fun onlyObservationPurposeAllowsExactTokenPOSTWithoutBroadeningOlderOperationsOrMedia() {
        assertTrue(AbemaMetadataRuntimePolicy.allows(token, false, "POST", operation))
        for (method in listOf("GET", "OPTIONS", "PUT", "DELETE", "HEAD"))
            assertFalse(AbemaMetadataRuntimePolicy.allows(token, false, method, operation))
        assertFalse(AbemaMetadataRuntimePolicy.allows(token, true, "POST", operation))
        for (other in AbemaMetadataRuntimeOperation.entries.filter { it != operation }) {
            val id = if (other == AbemaMetadataRuntimeOperation.FETCH_EPISODE) "fixture-episode" else null
            val series = if (other == AbemaMetadataRuntimeOperation.FETCH_SERIES) AbemaSeriesPageRequest("fixture-series") else null
            val slot = if (other == AbemaMetadataRuntimeOperation.FETCH_SLOT) AbemaSlotRequest("fixture-channel", "fixture-slot") else null
            val search = if (other == AbemaMetadataRuntimeOperation.FETCH_SEARCH) AbemaSearchRequest("fixture query") else null
            val replay = if (other == AbemaMetadataRuntimeOperation.FETCH_REPLAY_LENGTHS)
                AbemaReplayLengthRequest(listOf(AbemaSlotRequest("fixture-channel", "fixture-slot"))) else null
            assertFalse(AbemaMetadataRuntimePolicy.allows(token, false, "POST", other, id, series, slot, search, replay))
            assertFalse(AbemaMetadataRuntimePolicy.contentSecurityPolicy(other, id, series, slot, search, replay = replay).contains(AbemaMetadataRuntimePolicy.DEVICE_TOKEN))
        }
        for (url in listOf("$token?extra=1", "$token#fragment", "$token/", "$token/child",
            "https://abema.tv:443/api/auth/oauth/device/token", "https://fixture@abema.tv/api/auth/oauth/device/token",
            "https://abema.tv/api/auth/oauth/device/%74oken", "https://abema.tv/api/auth/oauth/device/../device/token",
            "http://abema.tv/api/auth/oauth/device/token", "https://other.abema.tv/api/auth/oauth/device/token",
            "https://abema.tv/api/auth/accessToken", "https://abema.tv/api/auth/oauth/exchange/token",
            "https://abema.tv/account/transfer", AbemaMetadataRuntimePolicy.EPISODE))
            for (method in listOf("GET", "POST", "OPTIONS"))
                assertFalse(AbemaMetadataRuntimePolicy.allows(URI(url), false, method, operation))
        val csp = AbemaMetadataRuntimePolicy.contentSecurityPolicy(operation)
        assertTrue(csp.contains("connect-src $guest ${AbemaMetadataRuntimePolicy.PROFILE_ORIGIN}/v1/users/ $challenge $token;"))
        assertTrue(csp.contains("media-src 'none'")); assertTrue(csp.contains("form-action 'none'"))
        assertFalse(csp.contains("/accessToken")); assertFalse(csp.contains("/video/"))
    }

    @Test fun oneTokenAdmissionRequiresGuestMatchingProfileAndChallengeWithoutRepeatOrFallback() {
        for (withPreflight in listOf(false, true)) {
            val admission = AbemaMetadataRequestAdmission(operation)
            assertFalse(admission.admit(token, false, "POST")); assertFalse(admission.admit(challenge, false, "POST"))
            assertTrue(admission.admit(guest, false, "POST")); assertFalse(admission.admit(token, false, "POST"))
            if (withPreflight) {
                assertTrue(admission.admit(profile, false, "OPTIONS", preflight))
                assertFalse(admission.admit(token, false, "POST"))
                assertFalse(admission.admit(URI("${AbemaMetadataRuntimePolicy.PROFILE_ORIGIN}/v1/users/OTHER"), false, "GET"))
                assertFalse(admission.admit(profile, false, "OPTIONS", preflight))
            }
            assertTrue(admission.admit(profile, false, "GET")); assertFalse(admission.admit(token, false, "POST"))
            assertTrue(admission.admit(challenge, false, "POST")); assertFalse(admission.admit(token, false, "GET"))
            assertFalse(admission.admit(token, false, "OPTIONS", preflight))
            assertTrue(admission.admit(token, false, "POST")); assertFalse(admission.admit(token, false, "POST"))
            assertFalse(admission.admit(challenge, false, "POST")); assertFalse(admission.admit(profile, false, "GET"))
            assertFalse(admission.admit(guest, false, "POST"))
            assertEquals("AbemaMetadataRequestAdmission(redacted)", admission.toString())
        }
    }

    @Test fun nativeAdmissionNeverReadsTokenBearerOrBody() {
        assertTrue(AbemaMetadataRuntimePolicy.corsHeaders("POST") { error("No private token request headers") }.isEmpty())
        val challengeOnly = AbemaMetadataRequestAdmission(AbemaMetadataRuntimeOperation.VERIFY_DEVICE_AUTHORIZATION)
        assertTrue(challengeOnly.admit(guest, false, "POST")); assertTrue(challengeOnly.admit(profile, false, "GET"))
        assertTrue(challengeOnly.admit(challenge, false, "POST")); assertFalse(challengeOnly.admit(token, false, "POST"))
    }
}
