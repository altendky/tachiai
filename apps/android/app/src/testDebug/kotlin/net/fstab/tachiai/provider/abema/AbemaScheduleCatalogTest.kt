package net.fstab.tachiai.provider.abema

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class AbemaScheduleCatalogTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private fun entry(index: Int) = CatalogEntry(AbemaSlotRequest("channel-A", "slot-$index").resource, "Future slot $index")
    private class Delegate(override val instanceId: String) : ProviderCatalog {
        override val providerId = ProviderId("abema")
        var calls = 0; var closes = 0
        override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, search = CatalogAccess.AVAILABLE,
            refresh = CatalogAccess.AVAILABLE, browseTitle = "Channels",
            collections = listOf(CatalogCollection("my_list", "My List", CatalogAccess.AUTHORIZATION_REQUIRED)))
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> { calls++; return CatalogResult.Value(CatalogPage(emptyList())) }
        override fun lookup(input: String): CatalogResult<CatalogEntry> { calls++; return CatalogResult.Failure(CatalogFailure.NOT_FOUND) }
        override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> { calls++; return CatalogResult.Value(CatalogEntry(resource, "Refreshed exact slot")) }
        override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> { calls++; return CatalogResult.Value(CatalogPlaybackResource(resource)) }
        override fun close() { closes++ }
    }
    private inner class Fixture(count: Int = 101) {
        val delegate = Delegate(id)
        var allowed = true; var fetches = 0; var closes = 0; var cancels = 0; var badCleanup = false
        val requests = mutableListOf<AbemaScheduleRequest>()
        var afterClose: () -> Unit = {}
        var gate: () -> Boolean = { allowed }
        var response: () -> CatalogResult<AbemaScheduleSnapshot> = { CatalogResult.Value(AbemaScheduleSnapshot(List(count, ::entry))) }
        val catalog = AbemaScheduleCatalog(delegate, { admitted -> object : AbemaScheduleTransport {
            override fun fetch(request: AbemaScheduleRequest): CatalogResult<AbemaScheduleSnapshot> {
                check(admitted()); requests += request; fetches++; return response()
            }
            override fun cancelActiveRequest() { cancels++ }
            override fun close() { closes++; afterClose(); if (badCleanup) throw AssertionError("Fixture cleanup failure") }
        } }, { gate() })
        fun browse(cursor: String? = null) = catalog.browse(CatalogQuery(collectionId = "schedule", cursor = cursor))
        fun value(result: CatalogResult<CatalogPage>) = (result as CatalogResult.Value).value
    }
    private fun channel(value: String = "channel-A") = CatalogResource(ProviderId("abema"), "channel", value, CatalogIntent.CHANNEL)
    @Test fun seriesWrapperDelegatesChannelProgramsAndChannelContinuationCannotBecomeGlobalOrAnotherChannel() {
        val f = Fixture()
        f.response = { CatalogResult.Value(AbemaScheduleSnapshot(List(101, ::entry), request = AbemaScheduleRequest("channel-A"))) }
        val outer = AbemaSeriesChildrenCatalog(f.catalog, null)
        assertEquals(CatalogAccess.AVAILABLE, outer.capabilities().children)
        val first = f.value(outer.browse(CatalogQuery(parent = channel())))
        assertEquals(List(100, ::entry), first.entries); assertEquals(listOf(AbemaScheduleRequest("channel-A")), f.requests)
        val second = f.value(outer.browse(CatalogQuery(parent = channel(), cursor = first.nextCursor)))
        assertEquals(listOf(entry(100)), second.entries); assertEquals(1, f.fetches)
        val token = f.value(outer.browse(CatalogQuery(parent = channel()))).nextCursor
        assertEquals(CatalogFailure.INVALID_INPUT, (outer.browse(CatalogQuery(parent = channel("channel-B"), cursor = token)) as CatalogResult.Failure).reason)
        val token2 = f.value(outer.browse(CatalogQuery(parent = channel()))).nextCursor
        assertEquals(CatalogFailure.INVALID_INPUT, (f.browse(token2) as CatalogResult.Failure).reason)
        assertEquals(3, f.fetches); assertEquals(3, f.closes)
    }
    @Test fun channelPublicationRequiresExactRequestedBindingEvenForEmptySnapshots() {
        val f = Fixture(0)
        assertEquals(CatalogFailure.INVALID_INPUT, (f.catalog.browse(CatalogQuery(parent = channel())) as CatalogResult.Failure).reason)
        f.response = { CatalogResult.Value(AbemaScheduleSnapshot(emptyList(), request = AbemaScheduleRequest("channel-A"))) }
        assertTrue(f.value(f.catalog.browse(CatalogQuery(parent = channel()))).entries.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            AbemaScheduleSnapshot(listOf(entry(0)), request = AbemaScheduleRequest("channel-B"))
        }
        assertEquals(CatalogFailure.INVALID_INPUT, (f.catalog.browse(CatalogQuery(parent = channel("bad/id"))) as CatalogResult.Failure).reason)
        assertThrows(IllegalArgumentException::class.java) { CatalogQuery(parent = channel(), collectionId = "schedule") }
    }
    @Test fun channelProjectionCannotAdoptWrongOrAbsentBindingOrCrossChannelRows() {
        val request = AbemaScheduleRequest("channel-A")
        val empty = """{"slots":[],"truncated":false,"channelId":"channel-A"}"""
        assertEquals(request, decodeAbemaScheduleProjection(empty, request)!!.request)
        assertNull(decodeAbemaScheduleProjection(empty))
        assertNull(decodeAbemaScheduleProjection(empty, AbemaScheduleRequest("channel-B")))
        assertNull(decodeAbemaScheduleProjection("""{"slots":[],"truncated":false}""", request))
        assertNull(decodeAbemaScheduleProjection(empty.replace("[]", """[{"id":"slot-A","channelId":"channel-B","title":"Other"}]"""), request))
        assertNull(decodeAbemaScheduleProjection(empty.replace("\"channelId\":", "\"channelId\":\"channel-A\",\"\\u0063hannelId\":"), request))
    }
    @Test fun channelCloseCancelsActiveFetchAndCleanupFailurePoisonsFurtherRequests() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.response = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            CatalogResult.Value(AbemaScheduleSnapshot(listOf(entry(0)), request = AbemaScheduleRequest("channel-A")))
        }
        val result = AtomicReference<CatalogResult<CatalogPage>>()
        val worker = Thread { result.set(f.catalog.browse(CatalogQuery(parent = channel()))) }
        try {
            worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); f.catalog.close()
            assertEquals(1, f.cancels); release.countDown(); worker.join(5000); assertFalse(worker.isAlive)
            assertEquals(CatalogFailure.ACCESS_REQUIRED, (result.get() as CatalogResult.Failure).reason)
            assertEquals(1, f.closes)
        } finally { release.countDown(); worker.join(5000) }
        val failed = Fixture(); failed.badCleanup = true
        failed.response = { CatalogResult.Value(AbemaScheduleSnapshot(emptyList(), request = AbemaScheduleRequest("channel-A"))) }
        assertEquals(CatalogFailure.TEMPORARY, (failed.catalog.browse(CatalogQuery(parent = channel())) as CatalogResult.Failure).reason)
        assertEquals(CatalogFailure.ACCESS_REQUIRED, (failed.catalog.browse(CatalogQuery(parent = channel())) as CatalogResult.Failure).reason)
        assertEquals(1, failed.fetches)
    }
    @Test fun collectionIsPublicAlongsideChannelsAndAccountCollectionsAndDelegatesExactRefresh() {
        val f = Fixture(); val capabilities = f.catalog.capabilities()
        assertEquals("Channels", capabilities.browseTitle); assertEquals(CatalogAccess.AVAILABLE, capabilities.search)
        assertEquals(listOf("my_list", "schedule"), capabilities.collections.map { it.id })
        assertEquals(CatalogAccess.AUTHORIZATION_REQUIRED, capabilities.collections.first().access)
        assertEquals(CatalogCollection("schedule", "Schedule", CatalogAccess.AVAILABLE), capabilities.collections.last())
        assertEquals(CatalogAccess.NOT_VERIFIED, AbemaScheduleCatalog(f.delegate, null).capabilities().collections.last().access)
        val first = f.value(f.browse()); assertEquals(List(100, ::entry), first.entries); assertNotNull(first.nextCursor)
        assertEquals(1, f.fetches); assertEquals(1, f.closes)
        val second = f.value(f.browse(first.nextCursor)); assertEquals(listOf(entry(100)), second.entries); assertNull(second.nextCursor)
        assertEquals(1, f.fetches); assertEquals(1, f.closes)
        assertEquals(CatalogFailure.INVALID_INPUT, (f.browse(first.nextCursor) as CatalogResult.Failure).reason)
        assertEquals(entry(0).resource, (f.catalog.refresh(entry(0).resource) as CatalogResult.Value).value.resource)
        assertEquals(1, f.delegate.calls)
    }
    @Test fun singleUseContinuationIsSnapshotLocalAndCannotSurviveChangedQueryInstanceOwnerOrRestart() {
        for (action in 0..5) {
            val f = Fixture(); val token = f.value(f.browse()).nextCursor!!
            when (action) {
                0 -> f.catalog.browse(CatalogQuery())
                1 -> f.catalog.lookup("input")
                2 -> f.catalog.refresh(entry(0).resource)
                3 -> f.catalog.resolve(entry(0).resource)
                4 -> f.catalog.browse(CatalogQuery(collectionId = "schedule", search = "Changed"))
                else -> f.browse()
            }
            assertEquals(CatalogFailure.INVALID_INPUT, (f.browse(token) as CatalogResult.Failure).reason)
        }
        val f = Fixture(); val token = f.value(f.browse()).nextCursor!!
        assertEquals(CatalogFailure.INVALID_INPUT, (Fixture().browse(token) as CatalogResult.Failure).reason)
        f.allowed = false
        assertEquals(CatalogFailure.ACCESS_REQUIRED, (f.browse(token) as CatalogResult.Failure).reason)
        f.allowed = true; assertEquals(CatalogFailure.INVALID_INPUT, (f.browse(token) as CatalogResult.Failure).reason)
    }
    @Test fun maximumPrefixRetainsActual501stRowForShared500ResultTruncation() {
        val f = Fixture(501); var page = f.value(f.browse()); val rows = page.entries.toMutableList()
        repeat(4) { page = f.value(f.browse(page.nextCursor)); rows += page.entries }
        assertEquals(500, rows.size); assertNotNull(page.nextCursor)
        page = f.value(f.browse(page.nextCursor)); assertEquals(listOf(entry(500)), page.entries); assertNull(page.nextCursor)
        assertEquals(1, f.fetches)
        val empty = Fixture(0); assertTrue(empty.value(empty.browse()).entries.isEmpty()); assertEquals(1, empty.fetches)
    }
    @Test fun publicationRequiresConfirmedTransportCleanupAndOwnerStillCurrent() {
        val f = Fixture(); f.afterClose = { f.allowed = false }
        assertEquals(CatalogFailure.ACCESS_REQUIRED, (f.browse() as CatalogResult.Failure).reason); assertEquals(1, f.closes)
        val failed = Fixture(); failed.badCleanup = true
        assertEquals(CatalogFailure.TEMPORARY, (failed.browse() as CatalogResult.Failure).reason)
        assertEquals(CatalogFailure.ACCESS_REQUIRED, (failed.browse() as CatalogResult.Failure).reason); assertEquals(1, failed.fetches)
        val refusal = Fixture(); refusal.response = { CatalogResult.Failure(CatalogFailure.RATE_LIMITED, 1000) }
        assertEquals(CatalogResult.Failure(CatalogFailure.RATE_LIMITED, 1000), refusal.browse()); assertEquals(1, refusal.closes)
    }
    @Test fun closeDuringHeldOwnerReadDiscardsSnapshotAndCancelsActiveTransportWithoutLifecycleLocking() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.response = {
            f.gate = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true }
            CatalogResult.Value(AbemaScheduleSnapshot(listOf(entry(0))))
        }
        val result = AtomicReference<CatalogResult<CatalogPage>>()
        val worker = Thread { result.set(f.browse()) }
        try {
            worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); f.catalog.close()
            release.countDown(); worker.join(5000); assertFalse(worker.isAlive)
            assertEquals(CatalogFailure.ACCESS_REQUIRED, (result.get() as CatalogResult.Failure).reason)
            assertEquals(1, f.closes); assertEquals(1, f.delegate.closes)
        } finally { release.countDown(); worker.join(5000) }
    }
    @Test fun normalizedProjectionRefusesDuplicatesUnicodeUnknownFieldsBoundsAndFalseTruncation() {
        val valid = """{"slots":[{"id":"slot-A","channelId":"channel-A","title":"Future 雪 😀"}],"truncated":false}"""
        val snapshot = decodeAbemaScheduleProjection(valid)!!
        assertEquals("channel-A/slot-A", snapshot.entries.single().resource.identity)
        assertEquals(CatalogAvailability.UNKNOWN, snapshot.entries.single().availability); assertNull(snapshot.entries.single().scheduledStartEpochMs)
        for (text in listOf(valid + " true", valid.replace("false", "0"), valid.replace("false", "true"),
            valid.replace("\"slots\":", "\"extra\":0,\"slots\":"),
            valid.replace("\"truncated\":false", "\"truncated\":false,\"\\u0074runcated\":false"),
            valid.replace("Future 雪 😀", "\\ud800"), valid.replace("\"slot-A\"", "1"),
            " ".repeat(ABEMA_SCHEDULE_PROJECTION_BYTES + 1))) assertNull(decodeAbemaScheduleProjection(text))
        assertThrows(IllegalArgumentException::class.java) { AbemaScheduleSnapshot(listOf(entry(0), entry(0))) }
        assertThrows(IllegalArgumentException::class.java) { AbemaScheduleSnapshot(List(502, ::entry)) }
        assertThrows(IllegalArgumentException::class.java) { AbemaScheduleSnapshot(listOf(entry(0).copy(availability = CatalogAvailability.LIVE))) }
        val rows = List(501) { "{\"id\":\"slot-$it\",\"channelId\":\"channel-A\",\"title\":\"Future $it\"}" }.joinToString(",")
        assertTrue(decodeAbemaScheduleProjection("{\"slots\":[$rows],\"truncated\":true}")!!.truncated)
    }
}
