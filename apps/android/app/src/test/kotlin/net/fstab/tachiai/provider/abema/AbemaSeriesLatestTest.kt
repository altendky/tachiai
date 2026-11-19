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
    // Rows are (episode id, broadcastAt seconds or null) in provider -seq order, 20 per page.
    private inner class Fixture(val rows: List<Pair<String, Long?>>) {
        val delegate = Delegate(id)
        val requests = mutableListOf<AbemaSeriesPageRequest>()
        var closes = 0
        var version: (AbemaSeriesPageRequest) -> String = { "v1" }
        var allowed = true
        var transportAvailable = true
        val catalog = AbemaSeriesChildrenCatalog(delegate, { current -> object : AbemaSeriesTransport {
            override fun fetch(request: AbemaSeriesPageRequest): CatalogResult<AbemaSeriesPage> {
                check(current()); requests += request
                val page = rows.drop(request.offset).take(ABEMA_SERIES_PAGE_LIMIT)
                return CatalogResult.Value(AbemaSeriesPage(request.seriesId, page.map { episode(it.first) }, version(request),
                    request.seasonId, if (request.newest) page.map { it.second } else null))
            }
            override fun cancelActiveRequest() {}
            override fun close() { closes++ }
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

    @Test fun anEpisodeWithoutBroadcastTimeIsPublishedButCannotOutrankATimedOne() {
        assertEquals("web-only", found(Fixture(listOf("web-only" to null, "other" to null)).latest()))
        assertEquals(CatalogLatestRefusal.ORDER_UNVERIFIED, refused(Fixture(listOf("web-only" to null, "aired" to now - 5)).latest()))
    }

    @Test fun broadcastOrderThatContradictsSequenceRefusesInsteadOfGuessing() {
        val f = Fixture(listOf("seq-last" to now - 1_000, "seq-earlier-but-newer" to now - 10))
        assertEquals(CatalogLatestRefusal.ORDER_UNVERIFIED, refused(f.latest()))
        // Equal times are not a contradiction.
        assertEquals("a", found(Fixture(listOf("a" to now - 10, "b" to now - 10)).latest()))
    }

    @Test fun emptyAndUnpublishedCollectionsAreReportedDistinctly() {
        assertEquals(CatalogLatestRefusal.EMPTY, refused(Fixture(emptyList()).latest()))
        assertEquals(CatalogLatestRefusal.NONE_PUBLISHED, refused(Fixture(listOf("soon" to now + 60)).latest()))
        val f = Fixture(List(ABEMA_LATEST_MAX_PAGES * 20 + 1) { "soon-$it" to now + 60 })
        assertEquals(CatalogLatestRefusal.NONE_PUBLISHED, refused(f.latest()))
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
        val closed = Fixture(listOf("aired" to now - 1)).apply { allowed = false }
        assertEquals(CatalogFailure.ACCESS_REQUIRED, (closed.latest() as CatalogResult.Failure).reason)
        assertTrue(closed.requests.isEmpty())
        val none = AbemaSeriesChildrenCatalog(Delegate(id), null)
        assertEquals(CatalogFailure.NOT_VERIFIED, (none.latest(series) as CatalogResult.Failure).reason)
    }

    @Test fun newestProjectionCarriesBroadcastSecondsOnlyForNewestRequests() {
        val newest = AbemaSeriesPageRequest("series-A", newest = true)
        val text = """{"seriesId":"series-A","version":"v1","programs":[{"id":"e1","title":"One","broadcastAt":1800000000},{"id":"e2","title":"Two","broadcastAt":null}]}"""
        val page = checkNotNull(decodeAbemaSeriesProjection(text, newest))
        assertEquals(listOf(1_800_000_000L, null), page.broadcastAtSeconds)
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
        val projection = """{"seriesId":"series-A","version":"v1","seasonId":"season-A","programs":[{"id":"e1","title":"One","broadcastAt":$now},{"id":"e2","title":"Two","broadcastAt":null}]}"""
        val result = mapOf("nonce" to start.nonce, "outcome" to "OK", "page" to projection)
        assertEquals(listOf(now, null), checkNotNull(AbemaSeriesMetadataIpc.decodeResult(result, request)?.page).broadcastAtSeconds)
        assertNull(AbemaSeriesMetadataIpc.decodeResult(result, AbemaSeriesPageRequest("series-A", 20, "v1", "season-A")))
    }
}
