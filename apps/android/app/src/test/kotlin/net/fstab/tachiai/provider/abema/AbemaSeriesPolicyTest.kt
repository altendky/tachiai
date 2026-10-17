package net.fstab.tachiai.provider.abema

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class AbemaSeriesPolicyTest {
    @Test fun immutableExactPagePolicyDoesNotAdmitOtherParentsOffsetsAccountsOrOrigins() {
        val operation = AbemaGuestBootstrapOperation.FETCH_SERIES
        val request = AbemaSeriesPageRequest("series-A", 20, "v1")
        val url = request.url()
        assertTrue(AbemaGuestBootstrapPolicy.allows(URI(url), false, "GET", operation, series = request))
        assertFalse(AbemaGuestBootstrapPolicy.allows(URI(url), true, "GET", operation, series = request))
        for (other in listOf(url.replace("series-A", "other"), url.replace("offset=20", "offset=40"), url + "&q=search",
            url.replace("seq", "-seq"), url.replace("limit=20", "limit=100"), url.replace("v1", "v2"),
            url.replace("api.p-c3-e.abema-tv.com", "api.abema.io"), url + "#fragment", url.replace("https://", "http://"),
            AbemaGuestBootstrapPolicy.EPISODE, AbemaGuestBootstrapPolicy.CHALLENGE,
            "https://api.p-c3-e.abema-tv.com/v1/mylist", "https://api.p-c3-e.abema-tv.com/v1/users/fixture"))
            assertFalse(AbemaGuestBootstrapPolicy.allows(URI(other), false, "GET", operation, series = request))
        val csp = AbemaGuestBootstrapPolicy.contentSecurityPolicy(operation, series = request)
        assertTrue(csp.contains(url)); assertTrue(csp.contains("media-src 'none'")); assertFalse(csp.contains("/v1/users/"))
        assertFalse(AbemaGuestBootstrapPolicy.allows(URI(url), false, "GET"))
    }
    @Test fun guestPrecedesAtMostOneStrictPreflightAndOneGetAndGetHeadersAreNeverRead() {
        val page = AbemaSeriesPageRequest("series-A")
        val admission = AbemaGuestRequestAdmission(AbemaGuestBootstrapOperation.FETCH_SERIES, series = page)
        val url = URI(page.url())
        assertFalse(admission.admit(url, false, "GET"))
        assertTrue(admission.admit(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST"))
        assertFalse(admission.admit(url, false, "OPTIONS", mapOf("Authorization" to "PRIVATE")))
        val headers = mapOf("Origin" to "https://abema.tv", "Access-Control-Request-Method" to "GET", "Access-Control-Request-Headers" to "authorization")
        assertTrue(admission.admit(url, false, "OPTIONS", headers)); assertFalse(admission.admit(url, false, "OPTIONS", headers))
        assertTrue(admission.admit(url, false, "GET")); assertFalse(admission.admit(url, false, "GET"))
        assertTrue(AbemaGuestBootstrapPolicy.corsHeaders("GET") { error("Private headers must not be read") }.isEmpty())
    }
}
