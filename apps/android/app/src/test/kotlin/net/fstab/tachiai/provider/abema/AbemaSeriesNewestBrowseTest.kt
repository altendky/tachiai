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
            return CatalogResult.Value(AbemaSeriesPage(request.seriesId,
                if (request.offset == 0) List(20) { entry(100 - it) } else listOf(entry(80)), request.version ?: "v1", request.seasonId))
        }
        override fun cancelActiveRequest() {}
        override fun close() {}
    } })

    @Test fun newestBrowseUsesDescendingSeqAcrossItsContinuation() {
        val first = (catalog.browse(CatalogQuery(parent = series, newest = true)) as CatalogResult.Value).value
        assertEquals("episode-100", first.entries.first().resource.identity)
        assertEquals("https://api.p-c3-e.abema-tv.com/v1/video/series/series-A/programs?order=-seq&limit=20&offset=0", requests[0].url())
        val second = (catalog.browse(CatalogQuery(parent = series, cursor = first.nextCursor, newest = true)) as CatalogResult.Value).value
        assertEquals(listOf("episode-80"), second.entries.map { it.resource.identity })
        assertEquals("https://api.p-c3-e.abema-tv.com/v1/video/series/series-A/programs?order=-seq&limit=20&offset=20&seriesVersion=v1",
            requests[1].url())
    }

    @Test fun aContinuationCannotSwitchOrder() {
        val first = (catalog.browse(CatalogQuery(parent = series, newest = true)) as CatalogResult.Value).value
        assertEquals(CatalogFailure.INVALID_INPUT,
            (catalog.browse(CatalogQuery(parent = series, cursor = first.nextCursor)) as CatalogResult.Failure).reason)
        val ascending = (catalog.browse(CatalogQuery(parent = series)) as CatalogResult.Value).value
        assertFalse(requests.last().newest)
        assertEquals(CatalogFailure.INVALID_INPUT,
            (catalog.browse(CatalogQuery(parent = series, cursor = ascending.nextCursor, newest = true)) as CatalogResult.Failure).reason)
        assertThrows(IllegalArgumentException::class.java) { CatalogQuery(newest = true) }
    }

    @Test fun newestRequestsCrossTheMetadataIpcWithTheirOrder() {
        val fields = mapOf("nonce" to "a".repeat(32), "instance" to id, "parent" to "series-A", "offset" to 20, "version" to "v1",
            "season" to "season-A", "order" to "-seq", "proof" to "b".repeat(64), "deadline" to 10_000L)
        val decoded = checkNotNull(AbemaSeriesMetadataIpc.decodeStart(fields, 1_000))
        assertTrue(decoded.page.newest)
        assertEquals("https://api.p-c3-e.abema-tv.com/v1/video/series/series-A/programs?order=-seq&limit=20&offset=20&seriesVersion=v1&seasonId=season-A",
            decoded.page.url())
        assertNull(AbemaSeriesMetadataIpc.decodeStart(fields + ("order" to "seq"), 1_000))
        assertFalse(checkNotNull(AbemaSeriesMetadataIpc.decodeStart(fields - "order", 1_000)).page.newest)
    }
}
