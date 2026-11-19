package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class AbemaSeriesLatestTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val now = 1_800_000_000L // Epoch seconds.
    private val series = CatalogResource(ProviderId("abema"), "series", "series-A", CatalogIntent.COLLECTION)
    private fun episode(name: String) = CatalogEntry(CatalogResource(ProviderId("abema"), "episode", name, CatalogIntent.VIDEO), "Episode $name")
    private class Delegate(override val instanceId: String) : ProviderCatalog {
        override val providerId = ProviderId("abema")
        var latestCalls = 0
        override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, lookup = CatalogAccess.AVAILABLE,
            refresh = CatalogAccess.AVAILABLE, playback = CatalogAccess.AVAILABLE)
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> = CatalogResult.Value(CatalogPage(emptyList()))
        override fun lookup(input: String): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.NOT_FOUND)
        override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.NOT_FOUND)
        override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        override fun latest(collection: CatalogResource): CatalogResult<CatalogLatest> { latestCalls++; return super.latest(collection) }
    }
    // Rows are (episode id, broadcastAt seconds or null) in provider -seq order,
    // 20 per page. Episode numbers default to strictly decreasing by position.
    private inner class Fixture(val rows: List<Pair<String, Long?>>, val number: (Int) -> Long? = { 10_000L - it }) {
        val delegate = Delegate(id)
        val requests = mutableListOf<AbemaSeriesPageRequest>()
        var closes = 0
        var closeHook: () -> Unit = {}
        var version: (AbemaSeriesPageRequest) -> String = { "v1" }
        var allowed = true
        var transportAvailable = true
        val catalog = AbemaSeriesChildrenCatalog(delegate, { current -> object : AbemaSeriesTransport {
            override fun fetch(request: AbemaSeriesPageRequest): CatalogResult<AbemaSeriesPage> {
                check(current()); requests += request
                val page = rows.drop(request.offset).take(ABEMA_SERIES_PAGE_LIMIT)
                return CatalogResult.Value(AbemaSeriesPage(request.seriesId, page.map { episode(it.first) }, version(request),
                    request.seasonId, if (request.newest) page.map { it.second } else null,
                    if (request.newest) page.indices.map { number(request.offset + it) } else null))
            }
            override fun cancelActiveRequest() {}
            override fun close() { closes++; closeHook() }
        } }, { allowed }, wallMs = { now * 1000 })
        fun latest(collection: CatalogResource = series) = catalog.latest(collection)
    }
    private fun found(result: CatalogResult<CatalogLatest>) =
        ((result as CatalogResult.Value).value as CatalogLatest.Found).playback.resource.identity
    private fun refused(result: CatalogResult<CatalogLatest>) = ((result as CatalogResult.Value).value as CatalogLatest.Refused).reason

    @Test fun newestPublishedEpisodeComesFromTheProvidersDescendingOrder() {
        val f = Fixture(listOf("day-15" to now - 100, "day-14" to now - 86_500, "day-13" to now - 173_000))
        assertEquals("day-15", found(f.latest()))
        val request = f.requests.single()
        assertTrue(request.newest)
        assertEquals("https://api.p-c3-e.abema-tv.com/v1/video/series/series-A/programs?order=-seq&limit=20&offset=0", request.url())
        assertEquals(1, f.closes)
    }

    @Test fun upcomingEpisodesAreSkippedEvenBeyondTheFirstPage() {
        val upcoming = List(20) { "upcoming-$it" to now + 1_000 - it }
        val f = Fixture(upcoming + listOf("aired" to now, "older" to now - 10))
        assertEquals("aired", found(f.latest()))
        assertEquals(listOf(0, 20), f.requests.map { it.offset })
        // The continuation pins the first page's version and keeps the order.
        assertEquals(listOf(null, "v1"), f.requests.map { it.version })
        assertTrue(f.requests.all { it.newest })
    }

    @Test fun untimedRowsWithDecreasingEpisodeNumbersMatchTheObservedSumoListing() {
        // Shape observed for series 394-72 on 2026-11-19: no broadcastAt, numbers decreasing with gaps.
        val observed = listOf(9060L, 9030L, 8541L, 8540L, 8539L, 6759L, 6758L)
        val f = Fixture(observed.indices.map { "row-$it" to null }) { observed[it] }
        assertEquals("row-0", found(f.latest()))
        // An untimed row after an upcoming one is published and is chosen.
        assertEquals("untimed", found(Fixture(listOf("soon" to now + 5, "untimed" to null, "aired" to now - 5)).latest()))
    }

    @Test fun missingOrNonDecreasingEpisodeNumbersRefuse() {
        assertEquals(CatalogLatestRefusal.ORDER_UNVERIFIED,
            refused(Fixture(listOf("a" to null, "b" to null)) { if (it == 1) null else 50L }.latest()))
        assertEquals(CatalogLatestRefusal.ORDER_UNVERIFIED,
            refused(Fixture(listOf("a" to null, "b" to null)) { 50L }.latest()))
        assertEquals(CatalogLatestRefusal.ORDER_UNVERIFIED,
            refused(Fixture(listOf("a" to null, "b" to null, "c" to null)) { listOf(50L, 40L, 45L)[it] }.latest()))
        // A number that restarts on a later page read before the choice refuses too.
        val restart = Fixture(List(20) { "soon-$it" to now + 100 } + listOf("aired" to null)) { if (it < 20) 500L - it else 900L }
        assertEquals(CatalogLatestRefusal.ORDER_UNVERIFIED, refused(restart.latest()))
        assertEquals(2, restart.requests.size)
    }

    @Test fun broadcastOrderThatContradictsSequenceRefusesInsteadOfGuessing() {
        val f = Fixture(listOf("seq-last" to now - 1_000, "seq-earlier-but-newer" to now - 10))
        assertEquals(CatalogLatestRefusal.ORDER_UNVERIFIED, refused(f.latest()))
        // Contradictions anywhere on the chosen page, or across pages read, refuse too.
        assertEquals(CatalogLatestRefusal.ORDER_UNVERIFIED,
            refused(Fixture(listOf("a" to now - 10, "b" to now - 20, "c" to now - 5)).latest()))
        val crossPage = Fixture(List(20) { "soon-$it" to now + 100 - it } + listOf("restarted" to now + 500))
        assertEquals(CatalogLatestRefusal.ORDER_UNVERIFIED, refused(crossPage.latest()))
        assertEquals(2, crossPage.requests.size)
        // Equal times are not a contradiction.
        assertEquals("a", found(Fixture(listOf("a" to now - 10, "b" to now - 10)).latest()))
    }

    @Test fun emptyAndUnpublishedCollectionsAreReportedDistinctly() {
        assertEquals(CatalogLatestRefusal.EMPTY, refused(Fixture(emptyList()).latest()))
        assertEquals(CatalogLatestRefusal.NONE_PUBLISHED, refused(Fixture(listOf("soon" to now + 60)).latest()))
        // Past the page bound the newest cannot be established.
        val f = Fixture(List(ABEMA_LATEST_MAX_PAGES * 20 + 1) { "soon-$it" to now + 60 })
        assertEquals(CatalogLatestRefusal.ORDER_UNVERIFIED, refused(f.latest()))
        assertEquals(ABEMA_LATEST_MAX_PAGES, f.requests.size)
    }

    @Test fun seasonsKeepBothParentsAndOtherResourcesGoToTheDelegate() {
        val f = Fixture(listOf("s2-day-3" to now - 1))
        assertEquals("s2-day-3", found(f.latest(abemaSearchResource("season", "series-A/season-B")!!)))
        assertEquals("season-B", f.requests.single().seasonId)
        assertEquals(0, f.delegate.latestCalls)
        val channel = CatalogResource(ProviderId("abema"), "channel", "abema-news", CatalogIntent.CHANNEL)
        assertEquals(CatalogFailure.UNSUPPORTED, (f.latest(channel) as CatalogResult.Failure).reason)
        assertEquals(1, f.delegate.latestCalls)
        assertEquals(CatalogFailure.INVALID_INPUT, (f.latest(series.copy(identity = "series.dotted")) as CatalogResult.Failure).reason)
    }

    @Test fun versionDriftDuplicateRowsAndAccessLossRefuse() {
        val drift = Fixture(List(20) { "soon-$it" to now + 60 } + listOf("aired" to now - 1))
        drift.version = { request -> if (request.offset == 0) "v1" else "v2" }
        assertEquals(CatalogFailure.INVALID_INPUT, (drift.latest() as CatalogResult.Failure).reason)
        // A row repeated across pages is not a coherent newest-first listing.
        val duplicate = Fixture(List(20) { "soon-$it" to now + 60 } + listOf("soon-3" to now - 1))
        assertEquals(CatalogFailure.INVALID_INPUT, (duplicate.latest() as CatalogResult.Failure).reason)
        // Access lost after the first page stops before another request.
        val lost = Fixture(List(20) { "soon-$it" to now + 60 } + listOf("aired" to now - 1))
        lost.closeHook = { lost.allowed = false }
        assertEquals(CatalogFailure.ACCESS_REQUIRED, (lost.latest() as CatalogResult.Failure).reason)
        assertEquals(1, lost.requests.size)
        val closed = Fixture(listOf("aired" to now - 1)).apply { allowed = false }
        assertEquals(CatalogFailure.ACCESS_REQUIRED, (closed.latest() as CatalogResult.Failure).reason)
        assertTrue(closed.requests.isEmpty())
        val none = AbemaSeriesChildrenCatalog(Delegate(id), null)
        assertEquals(CatalogFailure.NOT_VERIFIED, (none.latest(series) as CatalogResult.Failure).reason)
    }

    @Test fun newestProjectionCarriesBroadcastSecondsOnlyForNewestRequests() {
        val newest = AbemaSeriesPageRequest("series-A", newest = true)
        val text = """{"seriesId":"series-A","version":"v1","programs":[{"id":"e1","title":"One","broadcastAt":1800000000,"number":9060},{"id":"e2","title":"Two","broadcastAt":null,"number":null}]}"""
        val page = checkNotNull(decodeAbemaSeriesProjection(text, newest))
        assertEquals(listOf(1_800_000_000L, null), page.broadcastAtSeconds)
        assertEquals(listOf(9060L, null), page.episodeNumbers)
        assertNull(decodeAbemaSeriesProjection(text.replace(",\"number\":9060", ""), newest))
        for (bad in listOf("0", "-1", "1000000000", "\"9060\"", "09060"))
            assertNull(bad, decodeAbemaSeriesProjection(text.replace("9060", bad), newest))
        assertNull(decodeAbemaSeriesProjection(text, AbemaSeriesPageRequest("series-A")))
        assertNull(decodeAbemaSeriesProjection(text.replace(",\"broadcastAt\":null", ""), newest))
        for (bad in listOf("0", "-1", "1.5", "\"1800000000\"", "100000000000", "01", "1e9"))
            assertNull(bad, decodeAbemaSeriesProjection(text.replace("1800000000", bad), newest))
        assertNull(checkNotNull(decodeAbemaSeriesProjection(text.replace(Regex(",\"broadcastAt\":[^}]*"), ""),
            AbemaSeriesPageRequest("series-A"))).broadcastAtSeconds)
    }

    @Test fun newestRequestsCrossTheMetadataIpcWithTheirOrderAndBroadcastTimes() {
        val request = AbemaSeriesPageRequest("series-A", 20, "v1", "season-A", newest = true)
        val start = AbemaSeriesMetadataIpc.Start("a".repeat(32), id, request, "b".repeat(64), 10_000)
        val fields = mapOf("nonce" to start.nonce, "instance" to id, "parent" to "series-A", "offset" to 20, "version" to "v1",
            "season" to "season-A", "order" to "-seq", "proof" to start.ownerProof, "deadline" to 10_000L)
        val decoded = checkNotNull(AbemaSeriesMetadataIpc.decodeStart(fields, 1_000))
        assertTrue(decoded.page.newest); assertEquals(request.url(), decoded.page.url())
        assertNull(AbemaSeriesMetadataIpc.decodeStart(fields + ("order" to "seq"), 1_000))
        assertFalse(checkNotNull(AbemaSeriesMetadataIpc.decodeStart(fields - "order", 1_000)).page.newest)
        val projection = """{"seriesId":"series-A","version":"v1","seasonId":"season-A","programs":[{"id":"e1","title":"One","broadcastAt":$now,"number":2},{"id":"e2","title":"Two","broadcastAt":null,"number":1}]}"""
        val result = mapOf("nonce" to start.nonce, "outcome" to "OK", "page" to projection)
        val decoded2 = checkNotNull(AbemaSeriesMetadataIpc.decodeResult(result, request)?.page)
        assertEquals(listOf(now, null), decoded2.broadcastAtSeconds)
        assertEquals(listOf(2L, 1L), decoded2.episodeNumbers)
        assertNull(AbemaSeriesMetadataIpc.decodeResult(result, AbemaSeriesPageRequest("series-A", 20, "v1", "season-A")))
    }
}
