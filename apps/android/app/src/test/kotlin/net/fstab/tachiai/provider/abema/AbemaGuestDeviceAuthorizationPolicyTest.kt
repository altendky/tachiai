package net.fstab.tachiai.provider.abema

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class AbemaGuestDeviceAuthorizationPolicyTest {
    private val operation = AbemaMetadataRuntimeOperation.VERIFY_DEVICE_AUTHORIZATION
    private val guest = URI(AbemaMetadataRuntimePolicy.GUEST)
    private val profile = URI("${AbemaMetadataRuntimePolicy.PROFILE_ORIGIN}/v1/users/FIXTURE_USER")
    private val challenge = URI(AbemaMetadataRuntimePolicy.CHALLENGE)
    private val preflight = mapOf("Origin" to "https://abema.tv", "Access-Control-Request-Method" to "GET",
        "Access-Control-Request-Headers" to "authorization")

    @Test fun challengePurposeAllowsOnlyTheExactPOSTAndProfileWithoutBroadeningExistingOperations() {
        assertTrue(AbemaMetadataRuntimePolicy.allows(challenge, false, "POST", operation))
        for (method in listOf("GET", "OPTIONS", "PUT", "DELETE", "HEAD"))
            assertFalse(AbemaMetadataRuntimePolicy.allows(challenge, false, method, operation))
        assertFalse(AbemaMetadataRuntimePolicy.allows(challenge, true, "POST", operation))
        for (other in AbemaMetadataRuntimeOperation.entries.filter { it !in setOf(operation,
            AbemaMetadataRuntimeOperation.OBSERVE_DEVICE_TOKEN, AbemaMetadataRuntimeOperation.PREPARE_ACCOUNT_LINK) }) {
            val id = if (other == AbemaMetadataRuntimeOperation.FETCH_EPISODE) "fixture-episode" else null
            val series = if (other == AbemaMetadataRuntimeOperation.FETCH_SERIES) AbemaSeriesPageRequest("fixture-series") else null
            val slot = if (other == AbemaMetadataRuntimeOperation.FETCH_SLOT) AbemaSlotRequest("fixture-channel", "fixture-slot") else null
            val search = if (other == AbemaMetadataRuntimeOperation.FETCH_SEARCH) AbemaSearchRequest("fixture query") else null
            val replay = if (other == AbemaMetadataRuntimeOperation.FETCH_REPLAY_LENGTHS)
                AbemaReplayLengthRequest(listOf(AbemaSlotRequest("fixture-channel", "fixture-slot"))) else null
            assertFalse(AbemaMetadataRuntimePolicy.allows(challenge, false, "POST", other, id, series, slot, search, replay))
            assertFalse(AbemaMetadataRuntimePolicy.contentSecurityPolicy(other, id, series, slot, search, replay = replay).contains(AbemaMetadataRuntimePolicy.CHALLENGE))
        }
        for (method in listOf("GET", "OPTIONS"))
            assertTrue(AbemaMetadataRuntimePolicy.allows(profile, false, method, operation))
        assertFalse(AbemaMetadataRuntimePolicy.allows(URI(AbemaMetadataRuntimePolicy.EPISODE), false, "GET", operation))
        for (url in listOf("$challenge?extra=1", "$challenge#fragment", "$challenge/", "$challenge/child",
            "https://abema.tv:443/api/auth/oauth/device/authorization", "https://fixture@abema.tv/api/auth/oauth/device/authorization",
            "https://abema.tv/api/auth/oauth/device/%61uthorization", "https://abema.tv/api/auth/oauth/device/../device/authorization",
            "http://abema.tv/api/auth/oauth/device/authorization", "https://other.abema.tv/api/auth/oauth/device/authorization",
            "https://abema.tv/api/auth/oauth/device/token", "https://abema.tv/api/auth/accessToken",
            "https://abema.tv/account/transfer", "https://api.p-c3-e.abema-tv.com/v1/channels"))
            for (method in listOf("GET", "POST", "OPTIONS"))
                assertFalse(AbemaMetadataRuntimePolicy.allows(URI(url), false, method, operation))
        val csp = AbemaMetadataRuntimePolicy.contentSecurityPolicy(operation)
        assertTrue(csp.contains("connect-src ${AbemaMetadataRuntimePolicy.GUEST} ${AbemaMetadataRuntimePolicy.PROFILE_ORIGIN}/v1/users/ ${AbemaMetadataRuntimePolicy.CHALLENGE};"))
        assertTrue(csp.contains("media-src 'none'")); assertTrue(csp.contains("form-action 'none'"))
        assertFalse(csp.contains("/device/token")); assertFalse(csp.contains("/accessToken")); assertFalse(csp.contains("/video/"))
    }

    @Test fun explicitAccountLinkAdmitsOneMatchedChallengeWithoutTokenPollingOrOtherGuestExpansion() {
        val linking = AbemaMetadataRuntimeOperation.PREPARE_ACCOUNT_LINK
        val admission = AbemaMetadataRequestAdmission(linking)
        val token = URI(AbemaMetadataRuntimePolicy.DEVICE_TOKEN)
        assertFalse(admission.admit(challenge, false, "POST"))
        assertTrue(admission.admit(guest, false, "POST"))
        assertFalse(admission.admit(challenge, false, "POST"))
        assertTrue(admission.admit(profile, false, "OPTIONS", preflight))
        assertFalse(admission.admit(URI("${AbemaMetadataRuntimePolicy.PROFILE_ORIGIN}/v1/users/OTHER"), false, "GET"))
        assertTrue(admission.admit(profile, false, "GET"))
        assertTrue(admission.admit(challenge, false, "POST"))
        assertFalse(admission.admit(challenge, false, "POST"))
        assertFalse(admission.admit(token, false, "POST"))
        assertFalse(AbemaMetadataRuntimePolicy.allows(token, false, "POST", linking))
        assertTrue(AbemaMetadataRuntimePolicy.contentSecurityPolicy(linking).contains(AbemaMetadataRuntimePolicy.CHALLENGE))
        assertFalse(AbemaMetadataRuntimePolicy.contentSecurityPolicy(linking).contains(AbemaMetadataRuntimePolicy.DEVICE_TOKEN))
        for (existing in listOf(AbemaMetadataRuntimeOperation.GUEST_ONLY, AbemaMetadataRuntimeOperation.VERIFY_PROFILE,
            AbemaMetadataRuntimeOperation.VERIFY_EPISODE, AbemaMetadataRuntimeOperation.FETCH_EPISODE)) {
            val id = if (existing == AbemaMetadataRuntimeOperation.FETCH_EPISODE) "fixture-episode" else null
            assertFalse(AbemaMetadataRuntimePolicy.allows(challenge, false, "POST", existing, id))
            assertFalse(AbemaMetadataRuntimePolicy.allows(token, false, "POST", existing, id))
        }
    }

    @Test fun accountValidationAdmitsOnlyOneGuestAndMatchingProfileWithoutLinkingOrMetadataExpansion() {
        val validation = AbemaMetadataRuntimeOperation.PREPARE_ACCOUNT_VALIDATION
        val admission = AbemaMetadataRequestAdmission(validation)
        val token = URI(AbemaMetadataRuntimePolicy.DEVICE_TOKEN)
        assertFalse(admission.admit(profile, false, "GET"))
        assertTrue(admission.admit(guest, false, "POST"))
        assertFalse(admission.admit(guest, false, "POST"))
        assertTrue(admission.admit(profile, false, "OPTIONS", preflight))
        assertFalse(admission.admit(profile, false, "OPTIONS", preflight))
        assertFalse(admission.admit(URI("${AbemaMetadataRuntimePolicy.PROFILE_ORIGIN}/v1/users/OTHER"), false, "GET"))
        assertTrue(admission.admit(profile, false, "GET"))
        assertFalse(admission.admit(profile, false, "GET"))
        for (destination in listOf(challenge, token, URI(AbemaMetadataRuntimePolicy.EPISODE)))
            for (method in listOf("GET", "POST", "OPTIONS"))
                assertFalse(AbemaMetadataRuntimePolicy.allows(destination, false, method, validation))
        assertFalse(AbemaMetadataRuntimePolicy.allows(profile, true, "GET", validation))
        val csp = AbemaMetadataRuntimePolicy.contentSecurityPolicy(validation)
        assertTrue(csp.contains(AbemaMetadataRuntimePolicy.GUEST))
        assertTrue(csp.contains(AbemaMetadataRuntimePolicy.PROFILE_ORIGIN))
        assertFalse(csp.contains(AbemaMetadataRuntimePolicy.CHALLENGE))
        assertFalse(csp.contains(AbemaMetadataRuntimePolicy.DEVICE_TOKEN))
        assertFalse(csp.contains("/v1/video/"))
    }

    @Test fun admissionRequiresGuestThenOneMatchingProfileGETThenOneChallengePOST() {
        for (withPreflight in listOf(false, true)) {
            val admission = AbemaMetadataRequestAdmission(operation)
            assertFalse(admission.admit(challenge, false, "POST"))
            assertFalse(admission.admit(profile, false, "GET"))
            assertTrue(admission.admit(guest, false, "POST")); assertFalse(admission.admit(guest, false, "POST"))
            assertFalse(admission.admit(challenge, false, "POST"))
            if (withPreflight) {
                assertFalse(admission.admit(profile, false, "OPTIONS", preflight + ("Access-Control-Request-Method" to "POST")))
                assertTrue(admission.admit(profile, false, "OPTIONS", preflight))
                assertFalse(admission.admit(challenge, false, "POST"))
                assertFalse(admission.admit(profile, false, "OPTIONS", preflight))
                assertFalse(admission.admit(URI("${AbemaMetadataRuntimePolicy.PROFILE_ORIGIN}/v1/users/OTHER"), false, "GET"))
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
        assertTrue(AbemaMetadataRuntimePolicy.corsHeaders("POST") { error("Do not read a challenge bearer") }.isEmpty())
        val admission = AbemaMetadataRequestAdmission(operation)
        assertTrue(admission.admit(guest, false, "POST"))
        assertTrue(admission.admit(profile, false, "GET"))
        assertTrue(admission.admit(challenge, false, "POST"))
        assertEquals("AbemaMetadataRequestAdmission(redacted)", admission.toString())
    }
}
