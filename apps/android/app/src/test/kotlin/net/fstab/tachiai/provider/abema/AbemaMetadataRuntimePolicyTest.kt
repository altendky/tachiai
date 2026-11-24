package net.fstab.tachiai.provider.abema

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class AbemaMetadataRuntimePolicyTest {
    private val profileOperation = AbemaMetadataRuntimeOperation.VERIFY_PROFILE
    private val profile = URI("${AbemaMetadataRuntimePolicy.PROFILE_ORIGIN}/v1/users/FIXTURE_USER")
    private val preflight = mapOf("Origin" to "https://abema.tv", "Access-Control-Request-Method" to "GET",
        "Access-Control-Request-Headers" to "authorization")

    @Test fun sharedAccountAdmissionSkipsGuestAndRetainsExactOneMetadataRequestAndPreflight() {
        // A series continuation is a single contents request.
        val series = AbemaSeriesPageRequest("series-A", 20, "series-A_s1", "series-A_eg1", listOf(1))
        val cases = listOf(
            AbemaMetadataRuntimeOperation.FETCH_EPISODE to AbemaMetadataRuntimePolicy.episodeUrl("episode-A"),
            AbemaMetadataRuntimeOperation.FETCH_SERIES to series.contentsUrl("series-A_eg1", "series-A_s1", listOf(1)),
            AbemaMetadataRuntimeOperation.FETCH_SLOT to AbemaSlotRequest("channel-A", "slot-A").url(),
            AbemaMetadataRuntimeOperation.FETCH_SCHEDULE to AbemaMetadataRuntimePolicy.SCHEDULE,
            AbemaMetadataRuntimeOperation.FETCH_SEARCH to AbemaSearchRequest("Public query").url())
        for ((operation, url) in cases) {
            val admitted = AbemaMetadataRequestAdmission(operation,
                requestedEpisodeId = if (operation == AbemaMetadataRuntimeOperation.FETCH_EPISODE) "episode-A" else null,
                series = if (operation == AbemaMetadataRuntimeOperation.FETCH_SERIES) series else null,
                slot = if (operation == AbemaMetadataRuntimeOperation.FETCH_SLOT) AbemaSlotRequest("channel-A", "slot-A") else null,
                search = if (operation == AbemaMetadataRuntimeOperation.FETCH_SEARCH) AbemaSearchRequest("Public query") else null,
                accountBound = true)
            assertFalse(admitted.admit(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST"))
            assertFalse(admitted.admit(URI(AbemaMetadataRuntimePolicy.CHALLENGE), false, "POST"))
            assertFalse(admitted.admit(profile, false, "GET"))
            assertFalse(admitted.admit(URI("$url&other=true"), false, "GET"))
            assertTrue(admitted.admit(URI(url), false, "OPTIONS", preflight))
            assertFalse(admitted.admit(URI(url), false, "OPTIONS", preflight))
            assertTrue(admitted.admit(URI(url), false, "GET")); assertFalse(admitted.admit(URI(url), false, "GET"))
        }
        try { AbemaMetadataRequestAdmission(profileOperation, accountBound = true); fail("Diagnostic mode accepted account context") }
        catch (_: IllegalArgumentException) { }
        val policy = AbemaMetadataRuntimePolicy.contentSecurityPolicy(AbemaMetadataRuntimeOperation.FETCH_SCHEDULE, accountBound = true)
        assertFalse(policy.contains(AbemaMetadataRuntimePolicy.GUEST))
        assertTrue(policy.contains("connect-src ${AbemaMetadataRuntimePolicy.SCHEDULE};"))
    }

    @Test fun scheduleModeAdmitsOnlyCanonicalOneGuestOneGETWithBoundedPreflightAndNoResourceArgument() {
        val operation = AbemaMetadataRuntimeOperation.FETCH_SCHEDULE
        val uri = URI(AbemaMetadataRuntimePolicy.SCHEDULE)
        val request = AbemaMetadataRequestAdmission(operation)
        assertFalse(request.admit(uri, false, "GET"))
        assertTrue(request.admit(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST"))
        assertTrue(request.admit(uri, false, "OPTIONS", preflight)); assertFalse(request.admit(uri, false, "OPTIONS", preflight))
        assertTrue(request.admit(uri, false, "GET")); assertFalse(request.admit(uri, false, "GET"))
        for (url in listOf("${AbemaMetadataRuntimePolicy.SCHEDULE}?division=1", "${AbemaMetadataRuntimePolicy.SCHEDULE}/",
            AbemaMetadataRuntimePolicy.SCHEDULE.replace("dataSet", "%64ataSet"), "https://api.abema.io/v1/timetable/dataSet",
            AbemaMetadataRuntimePolicy.CHALLENGE, AbemaMetadataRuntimePolicy.DEVICE_TOKEN,
            "${AbemaMetadataRuntimePolicy.PROFILE_ORIGIN}/v1/users/private", AbemaMetadataRuntimePolicy.EPISODE))
            assertFalse(AbemaMetadataRuntimePolicy.allows(URI(url), false, "GET", operation))
        assertFalse(AbemaMetadataRuntimePolicy.allows(uri, true, "GET", operation))
        assertFalse(AbemaMetadataRuntimePolicy.allows(uri, false, "POST", operation))
        assertFalse(AbemaMetadataRuntimePolicy.allows(uri, false, "GET"))
        assertFalse(AbemaMetadataRuntimePolicy.allows(uri, false, "GET", operation, series = AbemaSeriesPageRequest("series-A")))
        assertTrue(AbemaMetadataRuntimePolicy.contentSecurityPolicy(operation).contains("${AbemaMetadataRuntimePolicy.SCHEDULE};"))
        assertFalse(AbemaMetadataRuntimePolicy.contentSecurityPolicy(operation).contains("/v1/users/"))
    }

    @Test fun onlyOwnedDocumentAssetsAndOneGuestMethodAreAdmitted() {
        assertTrue(AbemaMetadataRuntimePolicy.allows(URI(AbemaMetadataRuntimePolicy.PAGE), true, "GET"))
        assertFalse(AbemaMetadataRuntimePolicy.allows(URI(AbemaMetadataRuntimePolicy.PAGE), false, "GET"))
        for (file in listOf("control", "support", "utilities", "bundle", "legacy", "initialize")) {
            val uri = URI("https://abema.tv/_tachiai/guest/$file.js")
            assertTrue(AbemaMetadataRuntimePolicy.allows(uri, false, "GET"))
            assertFalse(AbemaMetadataRuntimePolicy.allows(uri, true, "GET"))
            assertFalse(AbemaMetadataRuntimePolicy.allows(uri, false, "POST"))
        }
        assertTrue(AbemaMetadataRuntimePolicy.allows(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST"))
        listOf("GET", "OPTIONS", "PUT").forEach {
            assertFalse(AbemaMetadataRuntimePolicy.allows(URI(AbemaMetadataRuntimePolicy.GUEST), false, it))
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
                assertFalse(AbemaMetadataRuntimePolicy.allows(URI(url), main, method))
        }
        assertFalse(AbemaMetadataRuntimePolicy.CONTENT_SECURITY_POLICY.contains("media-src https"))
        assertFalse(AbemaMetadataRuntimePolicy.CONTENT_SECURITY_POLICY.contains("api.p-c3"))
    }

    @Test fun explicitProfileModeAdmitsOnlyNormalizedIdentityPathAndBoundedPreflight() {
        for (method in listOf("GET", "OPTIONS")) {
            assertTrue(AbemaMetadataRuntimePolicy.allows(profile, false, method, profileOperation))
            assertFalse(AbemaMetadataRuntimePolicy.allows(profile, true, method, profileOperation))
            assertFalse(AbemaMetadataRuntimePolicy.allows(profile, false, method))
        }
        for (method in listOf("POST", "PUT", "DELETE", "HEAD"))
            assertFalse(AbemaMetadataRuntimePolicy.allows(profile, false, method, profileOperation))
        for (url in listOf("http://api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER",
            "https://api.p-c3-e.abema-tv.com:443/v1/users/FIXTURE_USER",
            "https://fixture@api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER",
            "https://api.p-c3-e.abema-tv.com/v1/users/%46IXTURE_USER",
            "https://api.p-c3-e.abema-tv.com/v1/users/../users/FIXTURE_USER",
            "https://api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER?x=1",
            "https://api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER#fragment",
            "https://api.p-c3-e.abema-tv.com/v1/users/", "$profile/children", "$profile/",
            "${AbemaMetadataRuntimePolicy.PROFILE_ORIGIN}/v1/users/${"A".repeat(257)}",
            "https://api.abema.io/v1/users/FIXTURE_USER", "https://other.invalid/v1/users/FIXTURE_USER",
            "https://api.p-c3-e.abema-tv.com/v1/channels", "https://api.p-c3-e.abema-tv.com/v1/media/token",
            "https://abema.tv/api/auth/accessToken", "https://abema.tv/api/auth/oauth/device/authorization")) {
            assertFalse(AbemaMetadataRuntimePolicy.allows(URI(url), false, "GET", profileOperation))
            assertFalse(AbemaMetadataRuntimePolicy.allows(URI(url), false, "OPTIONS", profileOperation))
        }
        assertEquals(AbemaMetadataRuntimePolicy.CONTENT_SECURITY_POLICY,
            AbemaMetadataRuntimePolicy.contentSecurityPolicy(AbemaMetadataRuntimeOperation.GUEST_ONLY))
        val csp = AbemaMetadataRuntimePolicy.contentSecurityPolicy(profileOperation)
        assertTrue(csp.contains("${AbemaMetadataRuntimePolicy.PROFILE_ORIGIN}/v1/users/;"))
        assertFalse(csp.contains("api.abema.io")); assertTrue(csp.contains("media-src 'none'"))
    }

    @Test fun attemptAdmissionRequiresGuestAndCapsExactProfileGETAndOPTIONSWithoutRetainingPublicState() {
        val request = AbemaMetadataRequestAdmission(profileOperation)
        assertFalse(request.admit(profile, false, "GET"))
        assertTrue(request.admit(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST"))
        assertFalse(request.admit(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST"))
        for (headers in listOf(emptyMap(), preflight - "Origin", preflight + ("Origin" to "https://other.invalid"),
            preflight + ("Access-Control-Request-Method" to "POST"),
            preflight + ("Access-Control-Request-Headers" to "authorization,content-type"),
            preflight + ("origin" to "https://abema.tv")))
            assertFalse(request.admit(profile, false, "OPTIONS", headers))
        assertTrue(request.admit(profile, false, "OPTIONS", preflight))
        assertFalse(request.admit(profile, false, "OPTIONS", preflight))
        assertFalse(request.admit(URI("${AbemaMetadataRuntimePolicy.PROFILE_ORIGIN}/v1/users/OTHER"), false, "GET"))
        assertTrue(request.admit(profile, false, "GET")); assertFalse(request.admit(profile, false, "GET"))
        assertFalse(request.toString().contains("FIXTURE_USER"))
        val cachedPreflight = AbemaMetadataRequestAdmission(profileOperation)
        assertTrue(cachedPreflight.admit(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST"))
        assertTrue(cachedPreflight.admit(profile, false, "GET"))
        assertFalse(cachedPreflight.admit(profile, false, "OPTIONS", preflight))
        val guestOnly = AbemaMetadataRequestAdmission(AbemaMetadataRuntimeOperation.GUEST_ONLY)
        assertTrue(guestOnly.admit(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST"))
        assertFalse(guestOnly.admit(profile, false, "GET"))
    }

    @Test fun episodeModeAdmitsOnlyOneFixedExactPathWithoutWideningGuestOrProfile() {
        val operation = AbemaMetadataRuntimeOperation.VERIFY_EPISODE
        val episode = URI(AbemaMetadataRuntimePolicy.EPISODE)
        for (method in listOf("GET", "OPTIONS")) {
            assertTrue(AbemaMetadataRuntimePolicy.allows(episode, false, method, operation))
            assertFalse(AbemaMetadataRuntimePolicy.allows(episode, true, method, operation))
            assertFalse(AbemaMetadataRuntimePolicy.allows(episode, false, method))
            assertFalse(AbemaMetadataRuntimePolicy.allows(episode, false, method, profileOperation))
        }
        assertFalse(AbemaMetadataRuntimePolicy.allows(profile, false, "GET", operation))
        for (method in listOf("POST", "PUT", "DELETE", "HEAD"))
            assertFalse(AbemaMetadataRuntimePolicy.allows(episode, false, method, operation))
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
            assertFalse(AbemaMetadataRuntimePolicy.allows(URI(url), false, "GET", operation))
            assertFalse(AbemaMetadataRuntimePolicy.allows(URI(url), false, "OPTIONS", operation))
        }
        val csp = AbemaMetadataRuntimePolicy.contentSecurityPolicy(operation)
        assertTrue(csp.contains("${AbemaMetadataRuntimePolicy.EPISODE};"))
        assertFalse(csp.contains("/v1/users/")); assertFalse(csp.contains("api.abema.io"))
        assertTrue(csp.contains("media-src 'none'"))
    }

    @Test fun episodeAdmissionRequiresGuestAndAllowsOnlyOnePrecedingSamePathPreflightAndGET() {
        val operation = AbemaMetadataRuntimeOperation.VERIFY_EPISODE
        val episode = URI(AbemaMetadataRuntimePolicy.EPISODE)
        val request = AbemaMetadataRequestAdmission(operation)
        assertFalse(request.admit(episode, false, "GET"))
        assertFalse(request.admit(episode, false, "OPTIONS", preflight))
        assertTrue(request.admit(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST"))
        assertFalse(request.admit(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST"))
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
        val withoutPreflight = AbemaMetadataRequestAdmission(operation)
        assertTrue(withoutPreflight.admit(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST"))
        assertTrue(withoutPreflight.admit(episode, false, "GET"))
        assertFalse(withoutPreflight.admit(episode, false, "OPTIONS", preflight))
    }

    @Test fun productionHeaderProjectionNeverReadsGETBearerAndRetainsOnlyCORSPreflightFields() {
        assertTrue(AbemaMetadataRuntimePolicy.corsHeaders("GET") { error("Must not inspect GET headers") }.isEmpty())
        val headers = preflight + mapOf("Authorization" to "Bearer PRIVATE_FIXTURE", "Cookie" to "PRIVATE_COOKIE",
            "User-Agent" to "FIXTURE")
        assertEquals(preflight, AbemaMetadataRuntimePolicy.corsHeaders("OPTIONS") { headers })
    }

    @Test fun catalogEpisodePolicyBindsTwoCanonicalPublicIdentitiesWithoutASampleFallback() {
        val operation = AbemaMetadataRuntimeOperation.FETCH_EPISODE
        for (id in listOf("fixture-A_123", "9".repeat(128))) {
            val url = AbemaMetadataRuntimePolicy.episodeUrl(id)
            val episode = URI(url)
            assertTrue(AbemaMetadataRuntimePolicy.allows(episode, false, "GET", operation, id))
            assertTrue(AbemaMetadataRuntimePolicy.allows(episode, false, "OPTIONS", operation, id))
            assertFalse(AbemaMetadataRuntimePolicy.allows(episode, true, "GET", operation, id))
            assertFalse(AbemaMetadataRuntimePolicy.allows(episode, false, "POST", operation, id))
            assertFalse(AbemaMetadataRuntimePolicy.allows(URI(AbemaMetadataRuntimePolicy.EPISODE), false, "GET", operation, id))
            assertFalse(AbemaMetadataRuntimePolicy.allows(profile, false, "GET", operation, id))
            for (other in listOf("$url?x=1", "$url#fragment", "$url/", "$url/children",
                "${AbemaMetadataRuntimePolicy.PROFILE_ORIGIN}/v1/video/programs/OTHER", "https://abema.tv/api/auth/accessToken"))
                assertFalse(AbemaMetadataRuntimePolicy.allows(URI(other), false, "GET", operation, id))
            val csp = AbemaMetadataRuntimePolicy.contentSecurityPolicy(operation, id)
            assertTrue(csp.contains("$url;")); assertFalse(csp.contains(AbemaMetadataRuntimePolicy.EPISODE))
            assertFalse(csp.contains("/v1/users/")); assertTrue(csp.contains("media-src 'none'"))
        }
    }

    @Test fun catalogEpisodeAdmissionNeedsFreshGuestAndAtMostOneMatchingPreflightAndGET() {
        val id = "fixture-B"
        val operation = AbemaMetadataRuntimeOperation.FETCH_EPISODE
        val episode = URI(AbemaMetadataRuntimePolicy.episodeUrl(id))
        val request = AbemaMetadataRequestAdmission(operation, id)
        assertFalse(request.admit(episode, false, "GET"))
        assertFalse(request.admit(episode, false, "OPTIONS", preflight))
        assertTrue(request.admit(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST"))
        assertFalse(request.admit(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST"))
        assertFalse(request.admit(URI(AbemaMetadataRuntimePolicy.EPISODE), false, "GET"))
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
            assertFalse(AbemaMetadataRuntimePolicy.validEpisodeId(id))
            assertThrows(IllegalArgumentException::class.java) { AbemaMetadataRuntimePolicy.episodeUrl(id) }
            assertThrows(IllegalArgumentException::class.java) {
                AbemaMetadataRequestAdmission(AbemaMetadataRuntimeOperation.FETCH_EPISODE, id)
            }
            assertFalse(AbemaMetadataRuntimePolicy.allows(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST",
                AbemaMetadataRuntimeOperation.FETCH_EPISODE, id))
        }
        for (operation in listOf(AbemaMetadataRuntimeOperation.GUEST_ONLY, AbemaMetadataRuntimeOperation.VERIFY_PROFILE,
            AbemaMetadataRuntimeOperation.VERIFY_EPISODE)) {
            assertThrows(IllegalArgumentException::class.java) { AbemaMetadataRequestAdmission(operation, "fixture-C") }
            assertThrows(IllegalArgumentException::class.java) { AbemaMetadataRuntimePolicy.contentSecurityPolicy(operation, "fixture-C") }
            assertFalse(AbemaMetadataRuntimePolicy.allows(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST", operation, "fixture-C"))
        }
    }

    @Test fun catalogEpisodeRequiresExplicitIdentityBeforeGuestDispatchOrCSP() {
        val operation = AbemaMetadataRuntimeOperation.FETCH_EPISODE
        assertThrows(IllegalArgumentException::class.java) { AbemaMetadataRequestAdmission(operation) }
        assertThrows(IllegalArgumentException::class.java) { AbemaMetadataRuntimePolicy.contentSecurityPolicy(operation) }
        assertFalse(AbemaMetadataRuntimePolicy.allows(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST", operation))
        assertFalse(AbemaMetadataRuntimePolicy.allows(URI(AbemaMetadataRuntimePolicy.EPISODE), false, "GET", operation))
    }
}
