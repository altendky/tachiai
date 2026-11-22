package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class AbemaSeriesNewestBrowseTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val series = CatalogResource(ProviderId("abema"), "series", "series-A", CatalogIntent.COLLECTION)
    private fun entry(index: Int) = CatalogEntry(CatalogResource(ProviderId("abema"), "episode", "episode-$index", CatalogIntent.VIDEO), "Episode $index")
    private class Delegate(override val instanceId: String) : ProviderCatalog {
        override val providerId = ProviderId("abema")
        override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, lookup = CatalogAccess.AVAILABLE,
            refresh = CatalogAccess.AVAILABLE, playback = CatalogAccess.AVAILABLE)
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> = CatalogResult.Value(CatalogPage(emptyList()))
        override fun lookup(input: String): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.NOT_FOUND)
        override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.NOT_FOUND)
        override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
    }
    private val requests = mutableListOf<AbemaSeriesPageRequest>()
    private val catalog = AbemaSeriesChildrenCatalog(Delegate(id), { _ -> object : AbemaSeriesTransport {
        override fun fetch(request: AbemaSeriesPageRequest): CatalogResult<AbemaSeriesPage> {
            requests += request
            return CatalogResult.Value(abemaSeriesPage(request, if (request.offset == 0) List(20) { entry(100 - it) } else listOf(entry(80))))
        }
        override fun cancelActiveRequest() {}
        override fun close() {}
    } })

    @Test fun newestBrowseUsesDescendingOrderAcrossItsContinuation() {
        val first = (catalog.browse(CatalogQuery(parent = series, newest = true)) as CatalogResult.Value).value
        assertEquals("episode-100", first.entries.first().resource.identity)
        assertTrue(requests[0].newest); assertNull(requests[0].viewingTypes)
        assertTrue(requests[0].contentsUrl("series-A_eg1", "series-A_s1", listOf(1)).contains("&orderType=desc&limit=20&offset=0&"))
        val second = (catalog.browse(CatalogQuery(parent = series, cursor = first.nextCursor, newest = true)) as CatalogResult.Value).value
        assertEquals(listOf("episode-80"), second.entries.map { it.resource.identity })
        assertEquals("https://api.p-c3-e.abema-tv.com/v1/contentlist/episodeGroups/series-A_eg1/contents?seasonId=series-A_s1" +
            "&orderType=desc&limit=20&offset=20&includes=liveEvent%2Cslot&viewingTypes=1",
            requests[1].contentsUrl(requests[1].groupId!!, requests[1].seasonId!!, requests[1].viewingTypes!!))
    }

    // #622: ABEMA's series page lists newest first, so every surface uses that order.
    @Test fun everyBrowseUsesAbemasNewestFirstOrder() {
        val ascending = (catalog.browse(CatalogQuery(parent = series)) as CatalogResult.Value).value
        assertTrue(requests.last().newest)
        val next = (catalog.browse(CatalogQuery(parent = series, cursor = ascending.nextCursor, newest = true)) as CatalogResult.Value).value
        assertTrue(requests.last().newest)
        assertNotNull(next)
        assertThrows(IllegalArgumentException::class.java) { CatalogQuery(newest = true) }
    }

    @Test fun newestRequestsCrossTheMetadataIpcWithTheirOrder() {
        val fields = mapOf("nonce" to "a".repeat(32), "instance" to id, "parent" to "series-A", "offset" to 20, "season" to "series-A_s1",
            "group" to "series-A_eg1", "types" to "1,3", "order" to "desc", "proof" to "b".repeat(64), "deadline" to 10_000L)
        val decoded = checkNotNull(AbemaSeriesMetadataIpc.decodeStart(fields, 1_000))
        assertTrue(decoded.page.newest); assertEquals(listOf(1, 3), decoded.page.viewingTypes)
        assertEquals("https://api.p-c3-e.abema-tv.com/v1/contentlist/episodeGroups/series-A_eg1/contents?seasonId=series-A_s1" +
            "&orderType=desc&limit=20&offset=20&includes=liveEvent%2Cslot&viewingTypes=1%2C3", decoded.page.contentsUrl("series-A_eg1", "series-A_s1", listOf(1, 3)))
        for (order in listOf("-seq", "asc", "seq")) assertNull(AbemaSeriesMetadataIpc.decodeStart(fields + ("order" to order), 1_000))
        assertFalse(checkNotNull(AbemaSeriesMetadataIpc.decodeStart(fields - "order", 1_000)).page.newest)
    }
}
