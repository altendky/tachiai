package net.fstab.tachiai.provider.abema

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class AbemaSearchManagementTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null; var writes = 0
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf(); writes++ }
    }
    private fun row(kind: String, id: String) = CatalogEntry(abemaSearchResource(kind, id)!!, "Future $kind $id")
    private suspend fun idle(controller: StreamManagementController) = withTimeout(5000) { controller.state.first { !it.loading && !it.saving } }
    private class Delegate(override val instanceId: String) : ProviderCatalog {
        override val providerId = ProviderId("abema")
        override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, refresh = CatalogAccess.AVAILABLE,
            collections = listOf(CatalogCollection("my_list", "My List", CatalogAccess.AVAILABLE)))
        override fun browse(query: CatalogQuery) = CatalogResult.Value(CatalogPage(emptyList()))
        override fun lookup(input: String) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        override fun refresh(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.NOT_FOUND)
        override fun resolve(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
    }
    @Test fun globalSearchSeasonChildrenBackAndExplicitAddsPreserveExactConfiguredChoicesAndQuality() = runBlocking {
        val instance = ProviderInstance(UUID.randomUUID().toString(), PrototypeService.ABEMA, "Fixture")
        val memory = Memory(); val store = ConfiguredSourceStore(memory, instance.id, ProviderId("abema"))
        val rows = listOf(row("episode", "episode-A"), row("series", "series-A"), row("season", "series-A/season-A"),
            row("slot", "channel-A/slot-A"), row("live_event", "event-A"))
        var allowed = true; var omitted = false
        val searches = mutableListOf<AbemaSearchRequest>(); val children = mutableListOf<AbemaSeriesPageRequest>()
        val search = AbemaSearchCatalog(Delegate(instance.id), { gate -> object : AbemaSearchTransport {
            override fun fetch(request: AbemaSearchRequest): CatalogResult<AbemaSearchReply> {
                check(gate()); searches += request
                return CatalogResult.Value(AbemaSearchReply(request, listOf(AbemaSearchModule("episode", "Episodes",
                    if (omitted || request.moduleId != null) 0 else 6, if (omitted || request.moduleId != null) emptyList() else rows))))
            }
            override fun cancelActiveRequest() = Unit
            override fun close() = Unit
        } }, { allowed })
        val catalog = AbemaSeriesChildrenCatalog(search, { gate -> object : AbemaSeriesTransport {
            override fun fetch(request: AbemaSeriesPageRequest): CatalogResult<AbemaSeriesPage> {
                check(gate()); children += request
                return CatalogResult.Value(AbemaSeriesPage(request.seriesId, listOf(row("episode", "child-A")), "v1", request.seasonId))
            }
            override fun cancelActiveRequest() = Unit
            override fun close() = Unit
        } }, { allowed })
        val controller = StreamManagementController(instance, catalog, store, { emptyList() }, this, Dispatchers.Unconfined)
        try {
            controller.load(); idle(controller); controller.collection("my_list"); idle(controller)
            controller.search("Public query"); var state = idle(controller)
            assertNull(state.query.collectionId); assertEquals(rows, state.entries); assertNotNull(state.nextCursor)
            assertEquals(0, memory.writes); val cursor = state.nextCursor
            for (parent in rows.filter { it.resource.intent == CatalogIntent.COLLECTION }) {
                controller.children(parent.resource); state = idle(controller)
                assertEquals(parent.resource, state.query.parent); assertEquals("Back to search results", state.navigationBackLabel)
                assertEquals(listOf(row("episode", "child-A")), state.entries)
                assertTrue(controller.back()); state = idle(controller)
                assertEquals(rows, state.entries); assertEquals(cursor, state.nextCursor)
            }
            assertEquals(listOf(null, "season-A"), children.map { it.seasonId }); assertTrue(children.all { it.seriesId == "series-A" })
            rows.forEach { controller.add(it); idle(controller) }
            state = idle(controller); assertEquals(rows, state.configured!!.map { it.entry })
            val ids = state.configured!!.map { it.id }
            val quality = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC,
                bitrateBps = 1_000_000, width = 640, height = 360))
            store.saveQuality(ids.first(), NativeQualityKind.VIDEO, quality) { emptyList() }
            val persisted = memory.bytes!!.copyOf(); val writes = memory.writes
            controller.more(); state = idle(controller); assertEquals(3, searches.size); assertNull(state.nextCursor)
            assertEquals(listOf(null, null, "episode"), searches.map { it.moduleId })
            assertArrayEquals(persisted, memory.bytes); assertEquals(writes, memory.writes)
            controller.add(rows.first()); idle(controller)
            assertEquals(ids, store.read { error("No fallback") }.map { it.id })
            assertEquals(quality, store.read { error("No fallback") }.first().quality.video)
            omitted = true; controller.search("Public query"); state = idle(controller); assertTrue(state.entries.isEmpty())
            allowed = false; controller.search("Public query"); state = idle(controller); assertTrue(state.entries.isEmpty())
            val reopened = ConfiguredSourceStore(memory, instance.id, ProviderId("abema")).read { error("No fallback") }
            assertEquals(ids, reopened.map { it.id }); assertEquals(rows, reopened.map { it.entry })
            assertEquals(quality, reopened.first().quality.video)
            assertTrue(reopened.all { it.entry.availability == CatalogAvailability.UNKNOWN && it.entry.scheduledStartEpochMs == null })
        } finally { controller.close() }
    }
    @Test fun shared500LimitReportsTruncationWithoutAdditionalProviderReadsOrConfiguredWrites() = runBlocking {
        val instance = ProviderInstance(UUID.randomUUID().toString(), PrototypeService.ABEMA, "Fixture")
        val memory = Memory(); var fetches = 0
        val catalog = AbemaSearchCatalog(Delegate(instance.id), { object : AbemaSearchTransport {
            override fun fetch(request: AbemaSearchRequest): CatalogResult<AbemaSearchReply> {
                fetches++
                val start = if (request.moduleId == null) 0 else request.offset
                val count = if (request.moduleId == null) 8 else 20
                return CatalogResult.Value(AbemaSearchReply(request, listOf(AbemaSearchModule("episode", "Episodes", 1000,
                    List(count) { row("episode", "episode-${start + it}") }))))
            }
            override fun cancelActiveRequest() = Unit
            override fun close() = Unit
        } })
        val controller = StreamManagementController(instance, catalog, ConfiguredSourceStore(memory, instance.id, ProviderId("abema")),
            { emptyList() }, this, Dispatchers.Unconfined)
        try {
            controller.load(); idle(controller); controller.search("Public query"); idle(controller)
            repeat(25) { controller.more(); idle(controller) }
            val state = controller.state.value
            assertEquals(500, state.entries.size); assertTrue(state.resultsTruncated); assertNull(state.nextCursor)
            assertEquals(26, fetches); assertEquals(0, memory.writes); assertTrue(state.configured!!.isEmpty())
            controller.more(); idle(controller); assertEquals(26, fetches)
        } finally { controller.close() }
    }
}
