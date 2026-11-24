package net.fstab.tachiai.provider.abema

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class AbemaSeriesPolicyTest {
    private val operation = AbemaMetadataRuntimeOperation.FETCH_SERIES
    private val headers = mapOf("Origin" to "https://abema.tv", "Access-Control-Request-Method" to "GET", "Access-Control-Request-Headers" to "authorization")
    private val first = AbemaSeriesPageRequest("series-A")
    private val series = first.seriesUrl()
    private val sections = first.sectionsUrl("series-A_eg1", "series-A_s10")
    private val contents = first.contentsUrl("series-A_eg1", "series-A_s10", listOf(1))
    private fun admission(request: AbemaSeriesPageRequest = first) =
        AbemaMetadataRequestAdmission(operation, series = request).also { assertTrue(it.admit(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST")) }
    private fun AbemaMetadataRequestAdmission.fetch(url: String, preflight: Boolean = true) =
        (!preflight || admit(URI(url), false, "OPTIONS", headers)) && admit(URI(url), false, "GET")

    @Test fun firstPageTemplatesAdmitOnlyExactSeriesBoundContentlistUris() {
        assertEquals("https://api.p-c3-e.abema-tv.com/v1/contentlist/series/series-A?includes=liveEvent%2Cslot", series)
        for (url in listOf(series, sections, contents, first.contentsUrl("series-A_eg5", "series-A_s2", listOf(1, 2, 4)))) {
            assertTrue(url, AbemaMetadataRuntimePolicy.allows(URI(url), false, "GET", operation, series = first))
            assertTrue(AbemaMetadataRuntimePolicy.allows(URI(url), false, "OPTIONS", operation, series = first))
            assertFalse(AbemaMetadataRuntimePolicy.allows(URI(url), true, "GET", operation, series = first))
            assertFalse(AbemaMetadataRuntimePolicy.allows(URI(url), false, "POST", operation, series = first))
            assertFalse(AbemaMetadataRuntimePolicy.allows(URI(url), false, "GET"))
        }
        for (other in listOf(series.replace("series-A", "other"), series.replace("%2C", ","), "$series&x=1", series.substringBefore('?'),
            sections.replace("series-A_eg1", "series-B_eg1"), sections.replace("series-A_s10", "other_s10"),
            sections.replace("series-A_eg1", "series-A_"), sections.replace("false", "true"), "$sections&x=1",
            sections.replace("?seasonId=series-A_s10&premiumUser=false", "?premiumUser=false&seasonId=series-A_s10"),
            contents.replace("limit=20", "limit=100"), contents.replace("offset=0", "offset=20"), contents.replace("asc", "desc"),
            contents.replace("viewingTypes=1", "viewingTypes=0"), contents.replace("viewingTypes=1", "viewingTypes=1%2C1"),
            contents.replace("viewingTypes=1", "viewingTypes=1%2C2%2C3%2C4%2C5"), contents.replace("viewingTypes=1", "viewingTypes=1,2"),
            contents.replace("viewingTypes=1", "viewingTypes=01"), contents.replace("%2Cslot", ",slot"), "$contents&x=1", "$contents#fragment",
            contents.replace("/contents?", "/other?"), contents.replace("https://", "http://"),
            contents.replace("api.p-c3-e.abema-tv.com", "api.abema.io"), contents.replace("api.p-c3-e.abema-tv.com", "api.p-c3-e.abema-tv.com:443"),
            "https://api.p-c3-e.abema-tv.com/v1/contentlist/episodeGroups/series-A_eg1/../contents?seasonId=series-A_s10",
            "https://api.p-c3-e.abema-tv.com/v1/video/series/series-A/programs?order=seq&limit=20&offset=0",
            AbemaMetadataRuntimePolicy.EPISODE, AbemaMetadataRuntimePolicy.CHALLENGE, "https://api.p-c3-e.abema-tv.com/v1/users/fixture"))
            assertFalse(other, AbemaMetadataRuntimePolicy.allows(URI(other), false, "GET", operation, series = first))
        val csp = AbemaMetadataRuntimePolicy.contentSecurityPolicy(operation, series = first)
        assertTrue(csp.contains("connect-src https://abema.tv/api/auth/login/guest " +
            "https://api.p-c3-e.abema-tv.com/v1/contentlist/series/series-A https://api.p-c3-e.abema-tv.com/v1/contentlist/episodeGroups/;"))
        assertTrue(csp.contains("media-src 'none'")); assertFalse(csp.contains("/v1/users/"))
        val tab = AbemaMetadataRuntimePolicy.contentSecurityPolicy(operation, series = AbemaSeriesPageRequest("series-A", groupId = "series-A_eg5"))
        assertTrue(tab.contains("/v1/contentlist/series/series-A https://api.p-c3-e.abema-tv.com/v1/contentlist/episodeGroups/series-A_eg5/;"))
        val next = AbemaSeriesPageRequest("series-A", 20, "series-A_s10", "series-A_eg1", listOf(1))
        assertTrue(AbemaMetadataRuntimePolicy.contentSecurityPolicy(operation, series = next, accountBound = true)
            .contains("connect-src https://api.p-c3-e.abema-tv.com/v1/contentlist/episodeGroups/series-A_eg1/;"))
    }
    @Test fun requestedSeasonGroupTypesAndOffsetBindTheTemplates() {
        val tab = AbemaSeriesPageRequest("series-A", groupId = "series-A_eg5")
        assertNotNull(AbemaMetadataRuntimePolicy.seriesStep(URI(tab.sectionsUrl("series-A_eg5", "series-A_s10")), tab))
        assertNull(AbemaMetadataRuntimePolicy.seriesStep(URI(tab.sectionsUrl("series-A_eg1", "series-A_s10")), tab))
        val season = AbemaSeriesPageRequest("series-A", seasonId = "series-A_s2")
        assertNotNull(AbemaMetadataRuntimePolicy.seriesStep(URI(season.contentsUrl("series-A_eg1", "series-A_s2", listOf(1))), season))
        assertNull(AbemaMetadataRuntimePolicy.seriesStep(URI(season.contentsUrl("series-A_eg1", "series-A_s10", listOf(1))), season))
        val next = AbemaSeriesPageRequest("series-A", 40, "series-A_s10", "series-A_eg1", listOf(1, 2), newest = true)
        val url = next.contentsUrl("series-A_eg1", "series-A_s10", listOf(1, 2))
        assertTrue(url.endsWith("&orderType=desc&limit=20&offset=40&includes=liveEvent%2Cslot&viewingTypes=1%2C2"))
        assertEquals(AbemaSeriesStep.CONTENTS, AbemaMetadataRuntimePolicy.seriesStep(URI(url), next)?.step)
        for (other in listOf(url.replace("viewingTypes=1%2C2", "viewingTypes=2%2C1"), url.replace("viewingTypes=1%2C2", "viewingTypes=1"),
            url.replace("offset=40", "offset=20"), url.replace("desc", "asc"), next.seriesUrl(), next.sectionsUrl("series-A_eg1", "series-A_s10")))
            assertNull(other, AbemaMetadataRuntimePolicy.seriesStep(URI(other), next))
    }
    @Test fun guestPrecedesTheChainInOrderWithAtMostOnePreflightAndGetPerUri() {
        val page = AbemaMetadataRequestAdmission(operation, series = first)
        assertFalse(page.admit(URI(series), false, "GET"))
        assertTrue(page.admit(URI(AbemaMetadataRuntimePolicy.GUEST), false, "POST"))
        assertFalse(page.admit(URI(series), false, "OPTIONS", mapOf("Authorization" to "PRIVATE")))
        assertTrue(page.admit(URI(series), false, "OPTIONS", headers)); assertFalse(page.admit(URI(series), false, "OPTIONS", headers))
        assertFalse(page.admit(URI(sections), false, "GET"))
        assertTrue(page.admit(URI(series), false, "GET")); assertFalse(page.admit(URI(series), false, "GET"))
        assertTrue(page.fetch(sections)); assertFalse(page.admit(URI(sections), false, "GET")); assertFalse(page.admit(URI(series), false, "GET"))
        assertFalse(page.admit(URI(first.contentsUrl("series-A_eg5", "series-A_s10", listOf(1))), false, "GET"))
        assertTrue(page.fetch(contents, preflight = false)); assertFalse(page.admit(URI(contents), false, "GET"))
        assertFalse(page.fetch(first.contentsUrl("series-A_eg1", "series-A_s10", listOf(2))))
        assertTrue(AbemaMetadataRuntimePolicy.corsHeaders("GET") { error("Private headers must not be read") }.isEmpty())
    }
    @Test fun skippedReorderedForeignAndRepeatedChainRequestsRefuse() {
        assertFalse(admission().fetch(sections)); assertFalse(admission().fetch(contents))
        assertFalse(admission().run { fetch(series); fetch(contents) })
        // Sections may name any series-bound group the JS read; contents must repeat it.
        assertTrue(admission().run { fetch(series) && fetch(first.sectionsUrl("series-A_eg5", "series-A_s10")) &&
            fetch(first.contentsUrl("series-A_eg5", "series-A_s10", listOf(3))) })
        assertFalse(admission().run { fetch(series); fetch(sections); fetch(first.contentsUrl("series-A_eg5", "series-A_s10", listOf(1))) })
        assertFalse(admission().run { fetch(series); fetch(sections); fetch(first.contentsUrl("series-A_eg1", "series-A_s2", listOf(1))) })
        assertFalse(admission().run { fetch(series); fetch(sections); fetch(first.sectionsUrl("series-A_eg5", "series-A_s10")) })
        assertFalse(admission().admit(URI(sections.replace("series-A_eg1", "series-B_eg1")), false, "GET"))
        // A preflight alone does not complete a step.
        assertFalse(admission().run { admit(URI(series), false, "OPTIONS", headers); fetch(sections) })
        val next = AbemaSeriesPageRequest("series-A", 20, "series-A_s10", "series-A_eg1", listOf(1))
        val continuation = next.contentsUrl("series-A_eg1", "series-A_s10", listOf(1))
        assertFalse(admission(next).fetch(next.seriesUrl())); assertFalse(admission(next).fetch(sections))
        assertTrue(admission(next).run { fetch(continuation) && !fetch(continuation) && !fetch(contents) })
    }

    // A refused preflight must leave the chain where it was, binding nothing.
    @Test fun aRefusedPreflightDoesNotAdvanceTheChain() {
        val admitted = admission()
        assertTrue(admitted.fetch(series))
        assertFalse(admitted.admit(URI(sections), false, "OPTIONS", headers + ("Origin" to "https://example.invalid")))
        val other = first.sectionsUrl("series-A_eg2", "series-A_s10")
        assertTrue(admitted.fetch(other))
        assertFalse(admitted.fetch(contents))
        assertTrue(admission().let { it.fetch(series) && it.fetch(other) && it.fetch(first.contentsUrl("series-A_eg2", "series-A_s10", listOf(1))) })
    }
}
