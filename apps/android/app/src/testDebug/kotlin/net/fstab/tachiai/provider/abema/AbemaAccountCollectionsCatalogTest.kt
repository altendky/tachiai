package net.fstab.tachiai.provider.abema

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class AbemaAccountCollectionsCatalogTest {
    private class Public(override val instanceId: String) : ProviderCatalog {
        override val providerId = ProviderId("abema")
        val entry = CatalogEntry(CatalogResource(providerId, "channel", "news", CatalogIntent.CHANNEL), "Public channel")
        val caps = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, search = CatalogAccess.AVAILABLE,
            lookup = CatalogAccess.AVAILABLE, refresh = CatalogAccess.AVAILABLE, browseTitle = "Channels",
            collections = listOf(CatalogCollection("my_list", "My List", CatalogAccess.NOT_VERIFIED),
                CatalogCollection("history", "History", CatalogAccess.NOT_VERIFIED)))
        var browseCalls = 0; var lookupCalls = 0; var refreshCalls = 0; var resolveCalls = 0; var closes = 0
        override fun capabilities() = caps
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> { browseCalls++; return CatalogResult.Value(CatalogPage(listOf(entry))) }
        override fun lookup(input: String): CatalogResult<CatalogEntry> { lookupCalls++; return CatalogResult.Value(entry) }
        override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> { refreshCalls++; return CatalogResult.Value(entry) }
        override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> { resolveCalls++; return CatalogResult.Failure(CatalogFailure.NOT_VERIFIED) }
        override fun close() { closes++ }
    }
    private class Access(val instance: String) : AbemaCollectionAccountAccess {
        var permission = CatalogAccess.AVAILABLE
        var context = "a".repeat(64)
        var owns = true
        var leaseInstance = instance
        var creates = 0
        var closes = 0
        var cancels = 0
        var closeFailure = false
        var cancelFailure = false
        var admission: (() -> Boolean)? = null
        var beforeFetch: () -> Unit = {}
        var response = AbemaMyListResponse(200, parseAbemaMyListResponse(page("first", "provider-next")))
        val requests = mutableListOf<String?>()
        override fun access() = permission
        override fun lease(): AbemaCollectionLease {
            val captured = context
            return AbemaCollectionLease(leaseInstance, captured, { owns && context == captured }) { gate ->
                creates++; admission = gate
                object : AbemaCollectionTransport {
                    override fun fetchMyList(next: String?): AbemaMyListResponse {
                        check(gate()); requests.add(next); beforeFetch(); return response
                    }
                    override fun cancelActiveRequest() { cancels++; if (cancelFailure) throw AssertionError("Private fixture text") }
                    override fun close() { closes++; if (closeFailure) throw AssertionError("Private fixture text") }
                }
            }
        }
    }
    private class Fixture {
        val id = UUID.randomUUID().toString()
        val public = Public(id)
        val access = Access(id)
        var foreground = true
        val catalog = AbemaAccountCollectionsCatalog(public, access, { foreground }, { 1_000 })
    }

    @Test fun accountCapabilitiesAreSeparateAndPublicDiscoveryRemainsDelegated() {
        val f = Fixture()
        f.access.permission = CatalogAccess.AUTHORIZATION_REQUIRED
        val denied = f.catalog.capabilities()
        assertEquals(f.public.caps.copy(collections = listOf(CatalogCollection("my_list", "My List", CatalogAccess.AUTHORIZATION_REQUIRED),
            CatalogCollection("history", "History", CatalogAccess.NOT_VERIFIED))), denied)
        failure(f.catalog.browse(CatalogQuery(collectionId = "my_list")), CatalogFailure.ACCESS_REQUIRED)
        failure(f.catalog.browse(CatalogQuery(collectionId = "history")), CatalogFailure.NOT_VERIFIED)
        assertEquals(0, f.access.creates)
        assertEquals(f.public.entry, (f.catalog.lookup("news") as CatalogResult.Value).value)
        f.catalog.browse(CatalogQuery()); f.catalog.refresh(f.public.entry.resource); f.catalog.resolve(f.public.entry.resource)
        assertEquals(listOf(1, 1, 1, 1), listOf(f.public.browseCalls, f.public.lookupCalls, f.public.refreshCalls, f.public.resolveCalls))
        f.access.permission = CatalogAccess.AVAILABLE
        assertEquals(CatalogAccess.AVAILABLE, f.catalog.capabilities().collections.single { it.id == "my_list" }.access)
        assertEquals(CatalogAccess.NOT_VERIFIED, f.catalog.capabilities().collections.single { it.id == "history" }.access)
        f.catalog.close(); assertEquals(1, f.public.closes)
    }

    @Test fun exactTypedPagesUseOpaqueSingleUseContinuationAndConfirmedCloseBeforePublication() {
        val f = Fixture(); f.catalog.capabilities()
        val first = value(f.catalog.browse(CatalogQuery(collectionId = "my_list")))
        assertEquals("first", first.entries.single().resource.identity)
        assertNotNull(first.nextCursor); assertNotEquals("provider-next", first.nextCursor)
        assertEquals(1, f.access.closes)
        f.access.response = AbemaMyListResponse(200, parseAbemaMyListResponse(page("second")))
        val second = value(f.catalog.browse(CatalogQuery(collectionId = "my_list", cursor = first.nextCursor)))
        assertEquals("second", second.entries.single().resource.identity)
        assertNull(second.nextCursor)
        assertEquals(listOf(null, "provider-next"), f.access.requests)
        failure(f.catalog.browse(CatalogQuery(collectionId = "my_list", cursor = first.nextCursor)), CatalogFailure.INVALID_INPUT)
        assertEquals(2, f.access.requests.size)
        f.catalog.close()
    }

    @Test fun generationRouteAndInstanceChangesCannotReusePrivateContinuationOrPublishLatePage() {
        val f = Fixture()
        val first = value(f.catalog.browse(CatalogQuery(collectionId = "my_list")))
        f.access.context = "b".repeat(64)
        failure(f.catalog.browse(CatalogQuery(collectionId = "my_list", cursor = first.nextCursor)), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(1, f.access.requests.size)
        val replacement = value(f.catalog.browse(CatalogQuery(collectionId = "my_list")))
        assertNotNull(replacement.nextCursor)
        f.access.beforeFetch = { f.access.context = "c".repeat(64) }
        failure(f.catalog.browse(CatalogQuery(collectionId = "my_list", cursor = replacement.nextCursor)), CatalogFailure.ACCESS_REQUIRED)
        // The late third request was dispatched under the captured owner, then
        // rejected after its generation changed; its transport must still close.
        assertEquals(listOf(null, null, "provider-next"), f.access.requests)
        assertEquals(3, f.access.closes)
        f.access.beforeFetch = {}
        f.access.leaseInstance = UUID.randomUUID().toString()
        failure(f.catalog.browse(CatalogQuery(collectionId = "my_list")), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(3, f.access.creates)
        assertEquals(3, f.access.requests.size)
        f.catalog.close()
    }

    @Test fun searchesAndChildrenNeverInventAnAccountEndpointOrAliasAndPublicQueriesInvalidateHandles() {
        val f = Fixture()
        val first = value(f.catalog.browse(CatalogQuery(collectionId = "my_list")))
        failure(f.catalog.browse(CatalogQuery(collectionId = "my_list", search = "query")), CatalogFailure.UNSUPPORTED)
        failure(f.catalog.browse(CatalogQuery(collectionId = "my_list", cursor = first.nextCursor)), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(1, f.access.requests.size)
        val next = value(f.catalog.browse(CatalogQuery(collectionId = "my_list")))
        f.catalog.browse(CatalogQuery())
        failure(f.catalog.browse(CatalogQuery(collectionId = "my_list", cursor = next.nextCursor)), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(2, f.access.requests.size)
        assertEquals(1, f.public.browseCalls)
        f.catalog.close()
    }

    @Test fun refusalRateAndMalformedTransportProjectionDoNotFallBackToPublicOrUnverifiedHistory() {
        val f = Fixture()
        for (status in listOf(401, 403, 404, 500, 503)) {
            f.access.response = AbemaMyListResponse(status)
            failure(f.catalog.browse(CatalogQuery(collectionId = "my_list")),
                if (status == 401 || status == 403) CatalogFailure.ACCESS_REQUIRED else CatalogFailure.TEMPORARY)
        }
        f.access.response = AbemaMyListResponse(429, retryAtEpochMs = Long.MAX_VALUE)
        val limited = f.catalog.browse(CatalogQuery(collectionId = "my_list")) as CatalogResult.Failure
        assertEquals(CatalogFailure.RATE_LIMITED, limited.reason); assertEquals(86_401_000L, limited.retryAtEpochMs)
        f.access.response = AbemaMyListResponse(200, AbemaMyListPage(listOf(f.public.entry)))
        failure(f.catalog.browse(CatalogQuery(collectionId = "my_list")), CatalogFailure.TEMPORARY)
        assertEquals(0, f.public.browseCalls)
        assertEquals(7, f.access.closes)
        f.catalog.close()
    }

    @Test fun cleanupErrorRetainsPoisonedAdmissionAcrossNewCatalogAndCannotPublishOrRetry() {
        val f = Fixture(); f.access.closeFailure = true
        failure(f.catalog.browse(CatalogQuery(collectionId = "my_list")), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(CatalogAccess.NOT_VERIFIED, f.catalog.capabilities().collections.single { it.id == "my_list" }.access)
        f.access.closeFailure = false
        failure(f.catalog.browse(CatalogQuery(collectionId = "my_list")), CatalogFailure.ACCESS_REQUIRED)
        val reopened = AbemaAccountCollectionsCatalog(Public(f.id), f.access)
        failure(reopened.browse(CatalogQuery(collectionId = "my_list")), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(1, f.access.creates); assertEquals(1, f.access.closes)
        f.catalog.close(); reopened.close()
    }

    @Test fun tokenPresentButStaleLeaseAndFailedWorkerCannotClaimAccessOrLeakExceptionText() {
        val f = Fixture()
        f.access.owns = false
        assertEquals(CatalogAccess.NOT_VERIFIED, f.catalog.capabilities().collections.single { it.id == "my_list" }.access)
        failure(f.catalog.browse(CatalogQuery(collectionId = "my_list")), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(0, f.access.creates)
        f.access.owns = true
        for (error in listOf(IllegalStateException("Private fixture text"), AssertionError("Private fixture text"))) {
            f.access.beforeFetch = { throw error }
            val result = f.catalog.browse(CatalogQuery(collectionId = "my_list"))
            failure(result, CatalogFailure.TEMPORARY)
            assertFalse(result.toString().contains("Private"))
        }
        assertEquals(2, f.access.closes)
        assertEquals("AbemaCollectionLease(redacted)", f.access.lease().toString())
        f.catalog.close()
    }

    @Test fun privateCancellationFailureCannotBeLaunderedByLaterSuccessfulWorkerClose() {
        val f = Fixture()
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        f.access.cancelFailure = true
        f.access.beforeFetch = { entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)) }
        val result = AtomicReference<CatalogResult<CatalogPage>>()
        val worker = Thread { result.set(f.catalog.browse(CatalogQuery(collectionId = "my_list"))) }
        worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        f.catalog.close(); finish.countDown(); worker.join(5_000); assertFalse(worker.isAlive)
        failure(result.get(), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(1, f.access.closes)
        val replacement = AbemaAccountCollectionsCatalog(Public(f.id), f.access)
        failure(replacement.browse(CatalogQuery(collectionId = "my_list")), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(1, f.access.creates)
        replacement.close()
    }

    @Test fun closeAndForegroundLossInvalidateImmediatelyWhileFetchWorkerOwnsFinalCleanup() {
        for (close in listOf(false, true)) {
            val f = Fixture()
            val entered = CountDownLatch(1); val finish = CountDownLatch(1)
            f.access.beforeFetch = { entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)) }
            val result = AtomicReference<CatalogResult<CatalogPage>>()
            val worker = Thread { result.set(f.catalog.browse(CatalogQuery(collectionId = "my_list"))) }
            worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            if (close) { f.catalog.close(); assertEquals(1, f.access.cancels) } else f.foreground = false
            assertFalse(f.access.admission!!.invoke())
            assertEquals(0, f.access.closes)
            finish.countDown(); worker.join(5_000); assertFalse(worker.isAlive)
            failure(result.get(), CatalogFailure.ACCESS_REQUIRED)
            assertEquals(1, f.access.closes)
            f.catalog.close()
        }
    }

    private fun value(result: CatalogResult<CatalogPage>) = (result as CatalogResult.Value).value
    private fun failure(result: CatalogResult<*>, reason: CatalogFailure) {
        assertTrue(result is CatalogResult.Failure); assertEquals(reason, (result as CatalogResult.Failure).reason)
    }
    companion object {
        private fun page(id: String, next: String? = null): String = """{"mylist":[{"contentId":"$id","contentType":"CONTENT_TYPE_PROGRAM"}],"dataSet":{"programs":[{"id":"$id","title":"Episode $id"}]},"paging":{"next":"${next ?: ""}"}}"""
    }
}
