package net.fstab.tachiai.provider.abema

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class AbemaSearchCatalogTest {
    private fun entry(id: String) = CatalogEntry(abemaSearchResource("episode", id)!!, "Future $id")
    private class Delegate : ProviderCatalog {
        override val providerId = ProviderId("abema"); override val instanceId = "12345678-1234-1234-1234-123456789abc"
        var queries = mutableListOf<CatalogQuery>(); var closes = 0
        override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, browseTitle = "Channels",
            collections = listOf(CatalogCollection("history", "History", CatalogAccess.RECONNECT_REQUIRED)))
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> { queries += query; return CatalogResult.Value(CatalogPage(emptyList())) }
        override fun lookup(input: String) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        override fun refresh(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.NOT_FOUND)
        override fun resolve(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        override fun close() { closes++ }
    }
    private inner class Fixture {
        val delegate = Delegate(); var allowed = true; var badClose = false; var closes = 0; var cancelled = 0
        val requests = mutableListOf<AbemaSearchRequest>()
        var gate: () -> Boolean = { allowed }
        var reply: (AbemaSearchRequest) -> CatalogResult<AbemaSearchReply> = { request ->
            CatalogResult.Value(AbemaSearchReply(request, listOf(AbemaSearchModule("episode", "Episodes", ABEMA_SEARCH_MAX_TOTAL,
                listOf(entry("episode-A"))))))
        }
        val catalog = AbemaSearchCatalog(delegate, { admitted -> object : AbemaSearchTransport {
            override fun fetch(request: AbemaSearchRequest): CatalogResult<AbemaSearchReply> { check(admitted()); requests += request; return reply(request) }
            override fun cancelActiveRequest() { cancelled++ }
            override fun close() { closes++; if (badClose) error("Fixture uncertain cleanup") }
        } }, { gate() })
        fun browse(cursor: String? = null, query: String = "Public query") = catalog.browse(CatalogQuery(search = query, cursor = cursor))
        fun value(result: CatalogResult<CatalogPage>) = (result as CatalogResult.Value).value
    }
    @Test fun summaryContinuesViaRealRawOffsetsAndModuleOrderDespiteOverlapAndChangingTotals() {
        val f = Fixture()
        f.reply = { request -> CatalogResult.Value(AbemaSearchReply(request, when {
            request.moduleId == null -> listOf(AbemaSearchModule("episode", "Episodes", 200, listOf(entry("A"))),
                AbemaSearchModule("timeshift", "Replays", 2, listOf(entry("B"))))
            request.moduleId == "episode" && request.offset == 0 -> listOf(AbemaSearchModule("episode", "Episodes", 21, listOf(entry("A"), entry("C"))))
            request.moduleId == "episode" -> listOf(AbemaSearchModule("episode", "Episodes", 20, emptyList()))
            else -> listOf(AbemaSearchModule("timeshift", "Replays", 1, listOf(entry("B"))))
        })) }
        val first = f.value(f.browse()); assertEquals(listOf(entry("A"), entry("B")), first.entries); assertNotNull(first.nextCursor)
        val second = f.value(f.browse(first.nextCursor)); assertEquals(listOf(entry("C")), second.entries)
        val third = f.value(f.browse(second.nextCursor)); assertTrue(third.entries.isEmpty()); assertNotNull(third.nextCursor)
        val fourth = f.value(f.browse(third.nextCursor)); assertTrue(fourth.entries.isEmpty()); assertNull(fourth.nextCursor)
        assertEquals(listOf(null to 0, "episode" to 0, "episode" to 20, "timeshift" to 0), f.requests.map { it.moduleId to it.offset })
        assertEquals(4, f.closes)
        assertEquals(CatalogFailure.INVALID_INPUT, (f.browse(first.nextCursor) as CatalogResult.Failure).reason)
    }
    @Test fun searchIsIndependentOfNamedCollectionsAndAmbiguousQueriesCannotFallback() {
        val f = Fixture(); val caps = f.catalog.capabilities()
        assertEquals(CatalogAccess.AVAILABLE, caps.search); assertEquals("Channels", caps.browseTitle)
        assertEquals(CatalogAccess.RECONNECT_REQUIRED, caps.collections.single().access)
        for (id in listOf("history", "my_list", "schedule")) f.catalog.browse(CatalogQuery(collectionId = id))
        assertEquals(3, f.delegate.queries.size); assertTrue(f.requests.isEmpty())
        assertEquals(CatalogFailure.INVALID_INPUT, (f.catalog.browse(CatalogQuery(collectionId = "history", search = "query")) as CatalogResult.Failure).reason)
        assertEquals(CatalogAccess.NOT_VERIFIED, AbemaSearchCatalog(f.delegate, null).capabilities().search)
    }
    @Test fun cursorCannotCrossQueryInstanceRestartOwnerOrOtherOperation() {
        for (mode in 0..4) {
            val f = Fixture(); val token = f.value(f.browse()).nextCursor!!
            when (mode) { 0 -> f.browse(query = "Other"); 1 -> f.catalog.lookup("input"); 2 -> f.catalog.browse(CatalogQuery())
                3 -> f.catalog.refresh(entry("A").resource); else -> f.catalog.resolve(entry("A").resource) }
            assertEquals(CatalogFailure.INVALID_INPUT, (f.browse(token) as CatalogResult.Failure).reason)
        }
        val f = Fixture(); val token = f.value(f.browse()).nextCursor!!
        assertEquals(CatalogFailure.INVALID_INPUT, (Fixture().browse(token) as CatalogResult.Failure).reason)
        f.allowed = false; assertEquals(CatalogFailure.ACCESS_REQUIRED, (f.browse(token) as CatalogResult.Failure).reason)
        f.allowed = true; assertEquals(CatalogFailure.INVALID_INPUT, (f.browse(token) as CatalogResult.Failure).reason)
    }
    @Test fun repeatedMutablePagesHitAnExplicitBoundRatherThanFalseExhaustionOrExtraDispatch() {
        val f = Fixture(); var page = f.value(f.browse())
        repeat(ABEMA_SEARCH_MAX_DETAIL_CALLS) { page = f.value(f.browse(page.nextCursor)); assertNotNull(page.nextCursor) }
        val dispatched = f.requests.size
        assertEquals(CatalogFailure.TEMPORARY, (f.browse(page.nextCursor) as CatalogResult.Failure).reason)
        assertEquals(dispatched, f.requests.size)
    }
    @Test fun malformedAndRefusedRepliesNeverBecomeEmptySuccessAndUncertainCleanupPoisonsReuse() {
        val f = Fixture(); f.reply = { CatalogResult.Failure(CatalogFailure.RATE_LIMITED, 1000) }
        assertEquals(CatalogResult.Failure(CatalogFailure.RATE_LIMITED, 1000), f.browse()); assertEquals(1, f.closes)
        val wrong = Fixture(); wrong.reply = { CatalogResult.Value(AbemaSearchReply(AbemaSearchRequest("Other"), emptyList())) }
        assertEquals(CatalogFailure.INVALID_INPUT, (wrong.browse() as CatalogResult.Failure).reason)
        val poisoned = Fixture(); poisoned.badClose = true
        assertEquals(CatalogFailure.TEMPORARY, (poisoned.browse() as CatalogResult.Failure).reason)
        assertEquals(CatalogFailure.ACCESS_REQUIRED, (poisoned.browse() as CatalogResult.Failure).reason); assertEquals(1, poisoned.requests.size)
        val empty = Fixture(); empty.reply = { request -> CatalogResult.Value(AbemaSearchReply(request, emptyList())) }
        assertTrue(empty.value(empty.browse()).entries.isEmpty()); assertNull(empty.value(empty.browse()).nextCursor)
    }
    @Test fun closeDuringHeldFinalOwnerReadCannotPublishEvenWhenReadReturnsTrue() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.reply = { request ->
            f.gate = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true }
            CatalogResult.Value(AbemaSearchReply(request, emptyList()))
        }
        val result = AtomicReference<CatalogResult<CatalogPage>>()
        val worker = Thread { result.set(f.browse()) }
        try { worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); f.catalog.close(); release.countDown(); worker.join(5000)
            assertFalse(worker.isAlive); assertEquals(CatalogFailure.ACCESS_REQUIRED, (result.get() as CatalogResult.Failure).reason)
            assertEquals(1, f.delegate.closes)
        } finally { release.countDown(); worker.join(5000) }
    }
}
