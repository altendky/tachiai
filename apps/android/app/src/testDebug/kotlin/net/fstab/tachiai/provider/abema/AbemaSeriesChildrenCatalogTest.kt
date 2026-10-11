package net.fstab.tachiai.provider.abema

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class AbemaSeriesChildrenCatalogTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private fun parent(value: String = "series-A") = CatalogResource(ProviderId("abema"), "series", value, CatalogIntent.COLLECTION)
    private fun entry(index: Int) = CatalogEntry(CatalogResource(ProviderId("abema"), "episode", "episode-$index", CatalogIntent.VIDEO), "Future episode $index")
    private class Delegate(override val instanceId: String, provider: String = "abema") : ProviderCatalog {
        override val providerId = ProviderId(provider)
        var calls = 0
        override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, lookup = CatalogAccess.AVAILABLE,
            refresh = CatalogAccess.AVAILABLE, playback = CatalogAccess.AVAILABLE,
            collections = listOf(CatalogCollection("my_list", "My List", CatalogAccess.AVAILABLE)))
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> { calls++; return CatalogResult.Value(CatalogPage(emptyList())) }
        override fun lookup(input: String): CatalogResult<CatalogEntry> { calls++; return CatalogResult.Failure(CatalogFailure.NOT_FOUND) }
        override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> { calls++; return CatalogResult.Failure(CatalogFailure.NOT_FOUND) }
        override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> { calls++; return CatalogResult.Failure(CatalogFailure.UNSUPPORTED) }
    }
    private inner class Fixture {
        val delegate = Delegate(id); val requests = mutableListOf<AbemaSeriesPageRequest>()
        var allowed = true; var closes = 0; var cancels = 0; var failedClose = false
        var response: (AbemaSeriesPageRequest) -> CatalogResult<AbemaSeriesPage> = { request ->
            CatalogResult.Value(AbemaSeriesPage(request.seriesId,
                if (request.offset == 0) List(20, ::entry) else listOf(entry(20)), request.version ?: "v1"))
        }
        var afterClose: () -> Unit = {}
        var gate: () -> Boolean = { allowed }
        val catalog = AbemaSeriesChildrenCatalog(delegate, { current -> object : AbemaSeriesTransport {
            override fun fetch(request: AbemaSeriesPageRequest): CatalogResult<AbemaSeriesPage> {
                check(current()); requests += request; return response(request)
            }
            override fun cancelActiveRequest() { cancels++ }
            override fun close() { closes++; afterClose(); if (failedClose) throw AssertionError("Fixture cleanup failure") }
        } }, { gate() })
        fun first(value: String = "series-A") = catalog.browse(CatalogQuery(parent = parent(value)))
        fun value(result: CatalogResult<CatalogPage>) = (result as CatalogResult.Value).value
    }
    @Test fun strictProjectionBindsTwoParentsAndDiscardsUnsupportedResponseOffset() {
        for (parent in listOf("series-A", "series-B")) {
            val text = """{"seriesId":"$parent","version":"v1","programs":[{"id":"episode-1","title":"Future 雪 😀"}]}"""
            val page = decodeAbemaSeriesProjection(text, AbemaSeriesPageRequest(parent))!!
            assertEquals(parent, page.seriesId); assertEquals("episode-1", page.entries.single().resource.identity)
            assertEquals(CatalogAvailability.UNKNOWN, page.entries.single().availability); assertNull(page.entries.single().scheduledStartEpochMs)
            assertNull(decodeAbemaSeriesProjection(text, AbemaSeriesPageRequest("other")))
        }
        assertEquals("https://api.p-c3-e.abema-tv.com/v1/video/series/series-A/programs?order=seq&limit=20&offset=20&seriesVersion=v+%2B%26%2F%E9%9B%AA",
            AbemaSeriesPageRequest("series-A", 20, "v +&/雪").url())
    }
    @Test fun strictNormalizedProjectionRefusesDuplicatesWrongTypesUnicodeAndDrift() {
        val request = AbemaSeriesPageRequest("series-A")
        val valid = """{"seriesId":"series-A","version":"v1","programs":[]}"""
        for (text in listOf(valid + " trailing", valid.replace("\"v1\"", "1"), valid.replace("\"v1\"", "null"),
            valid.replace("\"v1\"", "\"\""), valid.replace("\"programs\":[]", "\"programs\":{},\"extra\":1"),
            valid.replace("\"version\":\"v1\"", "\"version\":\"v1\",\"\\u0076ersion\":\"v2\""),
            """{"seriesId":"series-A","version":"v1","programs":[{"id":"episode-1","title":"\ud800"}]}""",
            """{"seriesId":"series-A","version":"v1","programs":[{"id":"episode-1","title":"First"},{"id":"episode-1","title":"Second"}]}"""))
            assertNull(decodeAbemaSeriesProjection(text, request))
        assertNull(decodeAbemaSeriesProjection(valid, AbemaSeriesPageRequest("series-A", 20, "different")))
        assertNull(decodeAbemaSeriesProjection(" ".repeat(32769), request))
        assertThrows(IllegalArgumentException::class.java) { AbemaSeriesPageRequest("series-A", 1, "v") }
        assertThrows(IllegalArgumentException::class.java) { AbemaSeriesPageRequest("series-A", 10000, "v") }
    }
    @Test fun pagesUseBoundSingleUseHandlesAndNeverImplicitlyResolveCollection() {
        val f = Fixture(); val first = f.value(f.first())
        assertEquals(List(20, ::entry), first.entries); assertNotNull(first.nextCursor)
        assertFalse(first.nextCursor!!.contains("v1")); assertEquals(1, f.closes)
        val next = f.value(f.catalog.browse(CatalogQuery(parent = parent(), cursor = first.nextCursor)))
        assertEquals(listOf(entry(20)), next.entries); assertNull(next.nextCursor)
        assertEquals(listOf(0, 20), f.requests.map { it.offset }); assertEquals(listOf(null, "v1"), f.requests.map { it.version })
        assertEquals(CatalogFailure.INVALID_INPUT, (f.catalog.browse(CatalogQuery(parent = parent(), cursor = first.nextCursor)) as CatalogResult.Failure).reason)
        assertEquals(CatalogFailure.UNSUPPORTED, (f.catalog.resolve(parent()) as CatalogResult.Failure).reason)
        assertEquals(CatalogAccess.AVAILABLE, f.catalog.capabilities().collections.single().access)
        assertEquals("Episodes", f.catalog.capabilities().childrenTitle)
    }
    @Test fun parentInstanceProviderAndChangedQueryCannotResurrectContinuations() {
        assertThrows(IllegalArgumentException::class.java) { AbemaSeriesChildrenCatalog(Delegate(id, "twitch"), null) }
        assertThrows(IllegalArgumentException::class.java) { AbemaSeriesChildrenCatalog(Delegate("bad"), null) }
        for (action in 0..3) {
            val f = Fixture(); val handle = f.value(f.first()).nextCursor!!
            when (action) { 0 -> f.catalog.browse(CatalogQuery()); 1 -> f.catalog.lookup("text")
                2 -> f.catalog.refresh(entry(0).resource); else -> f.catalog.resolve(entry(0).resource) }
            assertEquals(CatalogFailure.INVALID_INPUT, (f.catalog.browse(CatalogQuery(parent = parent(), cursor = handle)) as CatalogResult.Failure).reason)
            assertEquals(1, f.requests.size)
        }
        val a = Fixture(); val b = Fixture(); val handle = a.value(a.first()).nextCursor
        assertEquals(CatalogFailure.INVALID_INPUT, (b.catalog.browse(CatalogQuery(parent = parent(), cursor = handle)) as CatalogResult.Failure).reason)
        assertEquals(CatalogFailure.INVALID_INPUT, (a.catalog.browse(CatalogQuery(parent = parent("other"), cursor = handle)) as CatalogResult.Failure).reason)
    }
    @Test fun wrongReturnedParentVersionAndRepeatedEpisodeRefuseInsteadOfClaimingEmpty() {
        for (mode in 0..2) {
            val f = Fixture(); val handle = f.value(f.first()).nextCursor
            f.response = { CatalogResult.Value(AbemaSeriesPage(if (mode == 0) "other" else "series-A",
                listOf(entry(if (mode == 2) 0 else 20)), if (mode == 1) "changed" else "v1")) }
            assertEquals(CatalogFailure.INVALID_INPUT, (f.catalog.browse(CatalogQuery(parent = parent(), cursor = handle)) as CatalogResult.Failure).reason)
            assertEquals(2, f.closes)
        }
        val f = Fixture(); f.response = { CatalogResult.Value(AbemaSeriesPage(it.seriesId, emptyList(), "v1")) }
        val page = f.value(f.first()); assertTrue(page.entries.isEmpty()); assertNull(page.nextCursor)
    }
    @Test fun lateOwnerLossAfterCursorPublicationClearsRefusesAndCannotReuseOldHandle() {
        val f = Fixture(); var closedChecks = 0
        f.afterClose = { f.gate = { ++closedChecks == 1 } }
        assertEquals(CatalogFailure.ACCESS_REQUIRED, (f.first() as CatalogResult.Failure).reason)
        assertEquals(2, closedChecks); assertEquals(1, f.closes)
        f.afterClose = {}; f.gate = { true }
        assertEquals(20, f.value(f.first()).entries.size)
    }
    @Test fun closeOrQueryChangeDuringHeldFinalOwnerReadCannotPublishPageOrContinuation() {
        for (close in listOf(false, true)) {
            val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
            var checks = 0
            f.afterClose = { f.gate = {
                if (++checks == 2) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                true
            } }
            val result = AtomicReference<CatalogResult<CatalogPage>>()
            val worker = Thread { result.set(f.first()) }
            try {
                worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
                if (close) f.catalog.close() else f.catalog.lookup("changed query")
                release.countDown(); worker.join(5000)
                assertFalse(worker.isAlive); assertEquals(CatalogFailure.ACCESS_REQUIRED, (result.get() as CatalogResult.Failure).reason)
                assertEquals(1, f.requests.size); assertEquals(1, f.closes)
            } finally { release.countDown(); worker.join(5000); f.catalog.close() }
        }
    }
    @Test fun closeDuringRetrievalRejectsLatePageAndSignalsActiveTransport() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.response = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); CatalogResult.Value(AbemaSeriesPage(it.seriesId, listOf(entry(0)), "v")) }
        val result = AtomicReference<CatalogResult<CatalogPage>>()
        val worker = Thread { result.set(f.first()) }
        try {
            worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); f.catalog.close(); release.countDown(); worker.join(5000)
            assertFalse(worker.isAlive); assertEquals(CatalogFailure.ACCESS_REQUIRED, (result.get() as CatalogResult.Failure).reason)
            assertEquals(1, f.cancels); assertEquals(1, f.closes)
        } finally { release.countDown(); worker.join(5000) }
    }
    @Test fun cleanupFailurePoisonsRetainsAndDoesNotPublishOrDispatchAgain() {
        val f = Fixture(); f.failedClose = true
        assertEquals(CatalogFailure.TEMPORARY, (f.first() as CatalogResult.Failure).reason)
        f.failedClose = false; assertEquals(CatalogFailure.ACCESS_REQUIRED, (f.first() as CatalogResult.Failure).reason)
        assertEquals(1, f.requests.size); assertEquals(CatalogAccess.NOT_VERIFIED, f.catalog.capabilities().children)
    }
}
