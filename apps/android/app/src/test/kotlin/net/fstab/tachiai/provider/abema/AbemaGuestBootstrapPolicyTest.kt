package net.fstab.tachiai.provider.abema

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class AbemaGuestBootstrapPolicyTest {
    private val profileOperation = AbemaGuestBootstrapOperation.VERIFY_PROFILE
    private val profile = URI("${AbemaGuestBootstrapPolicy.PROFILE_ORIGIN}/v1/users/FIXTURE_USER")
    private val preflight = mapOf("Origin" to "https://abema.tv", "Access-Control-Request-Method" to "GET",
        "Access-Control-Request-Headers" to "authorization")

    @Test fun sharedAccountAdmissionSkipsGuestAndRetainsExactOneMetadataRequestAndPreflight() {
        val cases = listOf(
            AbemaGuestBootstrapOperation.FETCH_EPISODE to AbemaGuestBootstrapPolicy.episodeUrl("episode-A"),
            AbemaGuestBootstrapOperation.FETCH_SERIES to AbemaSeriesPageRequest("series-A").url(),
            AbemaGuestBootstrapOperation.FETCH_SLOT to AbemaSlotRequest("channel-A", "slot-A").url(),
            AbemaGuestBootstrapOperation.FETCH_SCHEDULE to AbemaGuestBootstrapPolicy.SCHEDULE,
            AbemaGuestBootstrapOperation.FETCH_SEARCH to AbemaSearchRequest("Public query").url())
        for ((operation, url) in cases) {
            val admitted = AbemaGuestRequestAdmission(operation,
                requestedEpisodeId = if (operation == AbemaGuestBootstrapOperation.FETCH_EPISODE) "episode-A" else null,
                series = if (operation == AbemaGuestBootstrapOperation.FETCH_SERIES) AbemaSeriesPageRequest("series-A") else null,
                slot = if (operation == AbemaGuestBootstrapOperation.FETCH_SLOT) AbemaSlotRequest("channel-A", "slot-A") else null,
                search = if (operation == AbemaGuestBootstrapOperation.FETCH_SEARCH) AbemaSearchRequest("Public query") else null,
                accountBound = true)
            assertFalse(admitted.admit(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST"))
            assertFalse(admitted.admit(URI(AbemaGuestBootstrapPolicy.CHALLENGE), false, "POST"))
            assertFalse(admitted.admit(profile, false, "GET"))
            assertFalse(admitted.admit(URI("$url&other=true"), false, "GET"))
            assertTrue(admitted.admit(URI(url), false, "OPTIONS", preflight))
            assertFalse(admitted.admit(URI(url), false, "OPTIONS", preflight))
            assertTrue(admitted.admit(URI(url), false, "GET")); assertFalse(admitted.admit(URI(url), false, "GET"))
        }
        try { AbemaGuestRequestAdmission(profileOperation, accountBound = true); fail("Diagnostic mode accepted account context") }
        catch (_: IllegalArgumentException) { }
        val policy = AbemaGuestBootstrapPolicy.contentSecurityPolicy(AbemaGuestBootstrapOperation.FETCH_SCHEDULE, accountBound = true)
        assertFalse(policy.contains(AbemaGuestBootstrapPolicy.GUEST))
        assertTrue(policy.contains("connect-src ${AbemaGuestBootstrapPolicy.SCHEDULE};"))
    }

    @Test fun scheduleModeAdmitsOnlyCanonicalOneGuestOneGETWithBoundedPreflightAndNoResourceArgument() {
        val operation = AbemaGuestBootstrapOperation.FETCH_SCHEDULE
        val uri = URI(AbemaGuestBootstrapPolicy.SCHEDULE)
        val request = AbemaGuestRequestAdmission(operation)
        assertFalse(request.admit(uri, false, "GET"))
        assertTrue(request.admit(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST"))
        assertTrue(request.admit(uri, false, "OPTIONS", preflight)); assertFalse(request.admit(uri, false, "OPTIONS", preflight))
        assertTrue(request.admit(uri, false, "GET")); assertFalse(request.admit(uri, false, "GET"))
        for (url in listOf("${AbemaGuestBootstrapPolicy.SCHEDULE}?division=1", "${AbemaGuestBootstrapPolicy.SCHEDULE}/",
            AbemaGuestBootstrapPolicy.SCHEDULE.replace("dataSet", "%64ataSet"), "https://api.abema.io/v1/timetable/dataSet",
            AbemaGuestBootstrapPolicy.CHALLENGE, AbemaGuestBootstrapPolicy.DEVICE_TOKEN,
            "${AbemaGuestBootstrapPolicy.PROFILE_ORIGIN}/v1/users/private", AbemaGuestBootstrapPolicy.EPISODE))
            assertFalse(AbemaGuestBootstrapPolicy.allows(URI(url), false, "GET", operation))
        assertFalse(AbemaGuestBootstrapPolicy.allows(uri, true, "GET", operation))
        assertFalse(AbemaGuestBootstrapPolicy.allows(uri, false, "POST", operation))
        assertFalse(AbemaGuestBootstrapPolicy.allows(uri, false, "GET"))
        assertFalse(AbemaGuestBootstrapPolicy.allows(uri, false, "GET", operation, series = AbemaSeriesPageRequest("series-A")))
        assertTrue(AbemaGuestBootstrapPolicy.contentSecurityPolicy(operation).contains("${AbemaGuestBootstrapPolicy.SCHEDULE};"))
        assertFalse(AbemaGuestBootstrapPolicy.contentSecurityPolicy(operation).contains("/v1/users/"))
    }

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

    @Test fun catalogEpisodePolicyBindsTwoCanonicalPublicIdentitiesWithoutASampleFallback() {
        val operation = AbemaGuestBootstrapOperation.FETCH_EPISODE
        for (id in listOf("fixture-A_123", "9".repeat(128))) {
            val url = AbemaGuestBootstrapPolicy.episodeUrl(id)
            val episode = URI(url)
            assertTrue(AbemaGuestBootstrapPolicy.allows(episode, false, "GET", operation, id))
            assertTrue(AbemaGuestBootstrapPolicy.allows(episode, false, "OPTIONS", operation, id))
            assertFalse(AbemaGuestBootstrapPolicy.allows(episode, true, "GET", operation, id))
            assertFalse(AbemaGuestBootstrapPolicy.allows(episode, false, "POST", operation, id))
            assertFalse(AbemaGuestBootstrapPolicy.allows(URI(AbemaGuestBootstrapPolicy.EPISODE), false, "GET", operation, id))
            assertFalse(AbemaGuestBootstrapPolicy.allows(profile, false, "GET", operation, id))
            for (other in listOf("$url?x=1", "$url#fragment", "$url/", "$url/children",
                "${AbemaGuestBootstrapPolicy.PROFILE_ORIGIN}/v1/video/programs/OTHER", "https://abema.tv/api/auth/accessToken"))
                assertFalse(AbemaGuestBootstrapPolicy.allows(URI(other), false, "GET", operation, id))
            val csp = AbemaGuestBootstrapPolicy.contentSecurityPolicy(operation, id)
            assertTrue(csp.contains("$url;")); assertFalse(csp.contains(AbemaGuestBootstrapPolicy.EPISODE))
            assertFalse(csp.contains("/v1/users/")); assertTrue(csp.contains("media-src 'none'"))
        }
    }

    @Test fun catalogEpisodeAdmissionNeedsFreshGuestAndAtMostOneMatchingPreflightAndGET() {
        val id = "fixture-B"
        val operation = AbemaGuestBootstrapOperation.FETCH_EPISODE
        val episode = URI(AbemaGuestBootstrapPolicy.episodeUrl(id))
        val request = AbemaGuestRequestAdmission(operation, id)
        assertFalse(request.admit(episode, false, "GET"))
        assertFalse(request.admit(episode, false, "OPTIONS", preflight))
        assertTrue(request.admit(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST"))
        assertFalse(request.admit(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST"))
        assertFalse(request.admit(URI(AbemaGuestBootstrapPolicy.EPISODE), false, "GET"))
        assertFalse(request.admit(profile, false, "GET"))
        assertFalse(request.admit(episode, false, "OPTIONS", preflight + ("Access-Control-Request-Headers" to "authorization,content-type")))
        assertTrue(request.admit(episode, false, "OPTIONS", preflight))
        assertFalse(request.admit(episode, false, "OPTIONS", preflight))
        assertTrue(request.admit(episode, false, "GET"))
        assertFalse(request.admit(episode, false, "GET"))
        assertFalse(request.admit(episode, false, "OPTIONS", preflight))
    }

    @Test fun unsafeEpisodeIdentitiesAndDiagnosticResourceOverridesRefuseBeforeAdmission() {
        for (id in listOf("", "-first", "_first", "x".repeat(129), "日本", "a/b", "a.b", "a%2fb",
            "a?query", "a#fragment", "has space", "has\nline")) {
            assertFalse(AbemaGuestBootstrapPolicy.validEpisodeId(id))
            assertThrows(IllegalArgumentException::class.java) { AbemaGuestBootstrapPolicy.episodeUrl(id) }
            assertThrows(IllegalArgumentException::class.java) {
                AbemaGuestRequestAdmission(AbemaGuestBootstrapOperation.FETCH_EPISODE, id)
            }
            assertFalse(AbemaGuestBootstrapPolicy.allows(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST",
                AbemaGuestBootstrapOperation.FETCH_EPISODE, id))
        }
        for (operation in listOf(AbemaGuestBootstrapOperation.GUEST_ONLY, AbemaGuestBootstrapOperation.VERIFY_PROFILE,
            AbemaGuestBootstrapOperation.VERIFY_EPISODE)) {
            assertThrows(IllegalArgumentException::class.java) { AbemaGuestRequestAdmission(operation, "fixture-C") }
            assertThrows(IllegalArgumentException::class.java) { AbemaGuestBootstrapPolicy.contentSecurityPolicy(operation, "fixture-C") }
            assertFalse(AbemaGuestBootstrapPolicy.allows(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST", operation, "fixture-C"))
        }
    }

    @Test fun catalogEpisodeRequiresExplicitIdentityBeforeGuestDispatchOrCSP() {
        val operation = AbemaGuestBootstrapOperation.FETCH_EPISODE
        assertThrows(IllegalArgumentException::class.java) { AbemaGuestRequestAdmission(operation) }
        assertThrows(IllegalArgumentException::class.java) { AbemaGuestBootstrapPolicy.contentSecurityPolicy(operation) }
        assertFalse(AbemaGuestBootstrapPolicy.allows(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST", operation))
        assertFalse(AbemaGuestBootstrapPolicy.allows(URI(AbemaGuestBootstrapPolicy.EPISODE), false, "GET", operation))
    }
}
