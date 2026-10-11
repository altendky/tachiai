package net.fstab.tachiai.provider.abema

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class AbemaGuestBootstrapPolicyTest {
    private val profileOperation = AbemaGuestBootstrapOperation.VERIFY_PROFILE
    private val profile = URI("${AbemaGuestBootstrapPolicy.PROFILE_ORIGIN}/v1/users/FIXTURE_USER")
    private val preflight = mapOf("Origin" to "https://abema.tv", "Access-Control-Request-Method" to "GET",
        "Access-Control-Request-Headers" to "authorization")

    @Test fun onlyOwnedDocumentAssetsAndOneGuestMethodAreAdmitted() {
        assertTrue(AbemaGuestBootstrapPolicy.allows(URI(AbemaGuestBootstrapPolicy.PAGE), true, "GET"))
        assertFalse(AbemaGuestBootstrapPolicy.allows(URI(AbemaGuestBootstrapPolicy.PAGE), false, "GET"))
        for (file in listOf("control", "support", "utilities", "bundle", "legacy", "initialize")) {
            val uri = URI("https://abema.tv/_tachiai/guest/$file.js")
            assertTrue(AbemaGuestBootstrapPolicy.allows(uri, false, "GET"))
            assertFalse(AbemaGuestBootstrapPolicy.allows(uri, true, "GET"))
            assertFalse(AbemaGuestBootstrapPolicy.allows(uri, false, "POST"))
        }
        assertTrue(AbemaGuestBootstrapPolicy.allows(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST"))
        listOf("GET", "OPTIONS", "PUT").forEach {
            assertFalse(AbemaGuestBootstrapPolicy.allows(URI(AbemaGuestBootstrapPolicy.GUEST), false, it))
        }
    }

    @Test fun accountProfileChallengeRefreshPlaybackAndURLVariationsRefuse() {
        for (url in listOf("https://abema.tv/", "https://abema.tv/auth/login",
            "https://abema.tv/api/auth/oauth/device/authorization", "https://abema.tv/api/auth/oauth/device/token",
            "https://abema.tv/api/auth/accessToken", "https://abema.tv/api/auth/sessions", "https://abema.tv/api/user",
            "https://api.p-c3-e.abema-tv.com/v1/users/fixture", "https://api.p-c3-e.abema-tv.com/v1/media/token",
            "https://api.abema.io/v1/channels", "https://license.p-c3-e.abema-tv.com/abematv-dash",
            "https://abema.tv/_tachiai/native/control.js", "https://abema.tv/assets/unreviewed.js",
            "http://abema.tv/api/auth/login/guest", "https://abema.tv:443/api/auth/login/guest",
            "https://fixture@abema.tv/api/auth/login/guest", "https://abema.tv/api/auth/login/%67uest",
            "https://abema.tv/api/auth/login/../login/guest", "https://abema.tv/api/auth/login/guest?token=fixture",
            "https://abema.tv/api/auth/login/guest#fragment", "https://other.abema.tv/api/auth/login/guest")) {
            for (main in listOf(false, true)) for (method in listOf("GET", "POST"))
                assertFalse(AbemaGuestBootstrapPolicy.allows(URI(url), main, method))
        }
        assertFalse(AbemaGuestBootstrapPolicy.CONTENT_SECURITY_POLICY.contains("media-src https"))
        assertFalse(AbemaGuestBootstrapPolicy.CONTENT_SECURITY_POLICY.contains("api.p-c3"))
    }

    @Test fun explicitProfileModeAdmitsOnlyNormalizedIdentityPathAndBoundedPreflight() {
        for (method in listOf("GET", "OPTIONS")) {
            assertTrue(AbemaGuestBootstrapPolicy.allows(profile, false, method, profileOperation))
            assertFalse(AbemaGuestBootstrapPolicy.allows(profile, true, method, profileOperation))
            assertFalse(AbemaGuestBootstrapPolicy.allows(profile, false, method))
        }
        for (method in listOf("POST", "PUT", "DELETE", "HEAD"))
            assertFalse(AbemaGuestBootstrapPolicy.allows(profile, false, method, profileOperation))
        for (url in listOf("http://api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER",
            "https://api.p-c3-e.abema-tv.com:443/v1/users/FIXTURE_USER",
            "https://fixture@api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER",
            "https://api.p-c3-e.abema-tv.com/v1/users/%46IXTURE_USER",
            "https://api.p-c3-e.abema-tv.com/v1/users/../users/FIXTURE_USER",
            "https://api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER?x=1",
            "https://api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER#fragment",
            "https://api.p-c3-e.abema-tv.com/v1/users/", "$profile/children", "$profile/",
            "${AbemaGuestBootstrapPolicy.PROFILE_ORIGIN}/v1/users/${"A".repeat(257)}",
            "https://api.abema.io/v1/users/FIXTURE_USER", "https://other.invalid/v1/users/FIXTURE_USER",
            "https://api.p-c3-e.abema-tv.com/v1/channels", "https://api.p-c3-e.abema-tv.com/v1/media/token",
            "https://abema.tv/api/auth/accessToken", "https://abema.tv/api/auth/oauth/device/authorization")) {
            assertFalse(AbemaGuestBootstrapPolicy.allows(URI(url), false, "GET", profileOperation))
            assertFalse(AbemaGuestBootstrapPolicy.allows(URI(url), false, "OPTIONS", profileOperation))
        }
        assertEquals(AbemaGuestBootstrapPolicy.CONTENT_SECURITY_POLICY,
            AbemaGuestBootstrapPolicy.contentSecurityPolicy(AbemaGuestBootstrapOperation.GUEST_ONLY))
        val csp = AbemaGuestBootstrapPolicy.contentSecurityPolicy(profileOperation)
        assertTrue(csp.contains("${AbemaGuestBootstrapPolicy.PROFILE_ORIGIN}/v1/users/;"))
        assertFalse(csp.contains("api.abema.io")); assertTrue(csp.contains("media-src 'none'"))
    }

    @Test fun attemptAdmissionRequiresGuestAndCapsExactProfileGETAndOPTIONSWithoutRetainingPublicState() {
        val request = AbemaGuestRequestAdmission(profileOperation)
        assertFalse(request.admit(profile, false, "GET"))
        assertTrue(request.admit(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST"))
        assertFalse(request.admit(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST"))
        for (headers in listOf(emptyMap(), preflight - "Origin", preflight + ("Origin" to "https://other.invalid"),
            preflight + ("Access-Control-Request-Method" to "POST"),
            preflight + ("Access-Control-Request-Headers" to "authorization,content-type"),
            preflight + ("origin" to "https://abema.tv")))
            assertFalse(request.admit(profile, false, "OPTIONS", headers))
        assertTrue(request.admit(profile, false, "OPTIONS", preflight))
        assertFalse(request.admit(profile, false, "OPTIONS", preflight))
        assertFalse(request.admit(URI("${AbemaGuestBootstrapPolicy.PROFILE_ORIGIN}/v1/users/OTHER"), false, "GET"))
        assertTrue(request.admit(profile, false, "GET")); assertFalse(request.admit(profile, false, "GET"))
        assertFalse(request.toString().contains("FIXTURE_USER"))
        val cachedPreflight = AbemaGuestRequestAdmission(profileOperation)
        assertTrue(cachedPreflight.admit(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST"))
        assertTrue(cachedPreflight.admit(profile, false, "GET"))
        assertFalse(cachedPreflight.admit(profile, false, "OPTIONS", preflight))
        val guestOnly = AbemaGuestRequestAdmission(AbemaGuestBootstrapOperation.GUEST_ONLY)
        assertTrue(guestOnly.admit(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST"))
        assertFalse(guestOnly.admit(profile, false, "GET"))
    }

    @Test fun episodeModeAdmitsOnlyOneFixedExactPathWithoutWideningGuestOrProfile() {
        val operation = AbemaGuestBootstrapOperation.VERIFY_EPISODE
        val episode = URI(AbemaGuestBootstrapPolicy.EPISODE)
        for (method in listOf("GET", "OPTIONS")) {
            assertTrue(AbemaGuestBootstrapPolicy.allows(episode, false, method, operation))
            assertFalse(AbemaGuestBootstrapPolicy.allows(episode, true, method, operation))
            assertFalse(AbemaGuestBootstrapPolicy.allows(episode, false, method))
            assertFalse(AbemaGuestBootstrapPolicy.allows(episode, false, method, profileOperation))
        }
        assertFalse(AbemaGuestBootstrapPolicy.allows(profile, false, "GET", operation))
        for (method in listOf("POST", "PUT", "DELETE", "HEAD"))
            assertFalse(AbemaGuestBootstrapPolicy.allows(episode, false, method, operation))
        for (url in listOf("$episode?x=1", "$episode#fragment", "$episode/", "$episode/children",
            "https://api.p-c3-e.abema-tv.com/v1/video/programs/OTHER",
            "https://api.p-c3-e.abema-tv.com/v1/video/programs/%3394-72_s10_p8529",
            "https://api.p-c3-e.abema-tv.com/v1/video/../video/programs/394-72_s10_p8529",
            "https://api.p-c3-e.abema-tv.com:443/v1/video/programs/394-72_s10_p8529",
            "https://fixture@api.p-c3-e.abema-tv.com/v1/video/programs/394-72_s10_p8529",
            "http://api.p-c3-e.abema-tv.com/v1/video/programs/394-72_s10_p8529",
            "https://api.abema.io/v1/video/programs/394-72_s10_p8529",
            "https://api.p-c3-e.abema-tv.com.other.invalid/v1/video/programs/394-72_s10_p8529",
            "https://api.p-c3-e.abema-tv.com/v1/media/token", "https://abema.tv/api/auth/accessToken")) {
            assertFalse(AbemaGuestBootstrapPolicy.allows(URI(url), false, "GET", operation))
            assertFalse(AbemaGuestBootstrapPolicy.allows(URI(url), false, "OPTIONS", operation))
        }
        val csp = AbemaGuestBootstrapPolicy.contentSecurityPolicy(operation)
        assertTrue(csp.contains("${AbemaGuestBootstrapPolicy.EPISODE};"))
        assertFalse(csp.contains("/v1/users/")); assertFalse(csp.contains("api.abema.io"))
        assertTrue(csp.contains("media-src 'none'"))
    }

    @Test fun episodeAdmissionRequiresGuestAndAllowsOnlyOnePrecedingSamePathPreflightAndGET() {
        val operation = AbemaGuestBootstrapOperation.VERIFY_EPISODE
        val episode = URI(AbemaGuestBootstrapPolicy.EPISODE)
        val request = AbemaGuestRequestAdmission(operation)
        assertFalse(request.admit(episode, false, "GET"))
        assertFalse(request.admit(episode, false, "OPTIONS", preflight))
        assertTrue(request.admit(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST"))
        assertFalse(request.admit(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST"))
        assertFalse(request.admit(profile, false, "GET"))
        for (headers in listOf(emptyMap(), preflight - "Origin", preflight + ("Origin" to "https://other.invalid"),
            preflight + ("Access-Control-Request-Method" to "POST"),
            preflight + ("Access-Control-Request-Headers" to "authorization,content-type"),
            preflight + ("origin" to "https://abema.tv")))
            assertFalse(request.admit(episode, false, "OPTIONS", headers))
        assertTrue(request.admit(episode, false, "OPTIONS", preflight))
        assertFalse(request.admit(episode, false, "OPTIONS", preflight))
        assertTrue(request.admit(episode, false, "GET"))
        assertFalse(request.admit(episode, false, "GET"))
        assertFalse(request.admit(episode, false, "OPTIONS", preflight))
        val withoutPreflight = AbemaGuestRequestAdmission(operation)
        assertTrue(withoutPreflight.admit(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST"))
        assertTrue(withoutPreflight.admit(episode, false, "GET"))
        assertFalse(withoutPreflight.admit(episode, false, "OPTIONS", preflight))
    }

    @Test fun productionHeaderProjectionNeverReadsGETBearerAndRetainsOnlyCORSPreflightFields() {
        assertTrue(AbemaGuestBootstrapPolicy.corsHeaders("GET") { error("Must not inspect GET headers") }.isEmpty())
        val headers = preflight + mapOf("Authorization" to "Bearer PRIVATE_FIXTURE", "Cookie" to "PRIVATE_COOKIE",
            "User-Agent" to "FIXTURE")
        assertEquals(preflight, AbemaGuestBootstrapPolicy.corsHeaders("OPTIONS") { headers })
    }
}
