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

class AbemaSeriesManagementTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null; var writes = 0
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf(); writes++ }
    }
    private suspend fun idle(c: StreamManagementController) = withTimeout(5000) { c.state.first { !it.loading && !it.saving } }
    @Test fun realSharedChildrenBackAndExplicitAddPreserveCollectionEpisodeUUIDOrderAndQuality() = runBlocking {
        val instance = ProviderInstance(UUID.randomUUID().toString(), PrototypeService.ABEMA, "Fixture")
        val memory = Memory(); val store = ConfiguredSourceStore(memory, instance.id, ProviderId("abema"))
        val parent = parseAbemaPublicResource("https://abema.tv/video/title/series-A")!!
        var allowed = true; val requests = mutableListOf<AbemaSeriesPageRequest>()
        val delegate = object : ProviderCatalog {
            override val providerId = ProviderId("abema"); override val instanceId = instance.id
            override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, lookup = CatalogAccess.AVAILABLE)
            override fun browse(query: CatalogQuery) = CatalogResult.Value(CatalogPage(emptyList()))
            override fun lookup(input: String) = CatalogResult.Value(parent)
            override fun refresh(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
            override fun resolve(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        }
        val catalog = AbemaSeriesChildrenCatalog(delegate, { gate -> object : AbemaSeriesTransport {
            override fun fetch(request: AbemaSeriesPageRequest): CatalogResult<AbemaSeriesPage> {
                check(gate()); requests += request
                val rows = (request.offset until if (request.offset == 0) 20 else 21).map { index ->
                    "{\"id\":\"episode-$index\",\"title\":\"Not-live episode $index\"}"
                }.joinToString(",")
                return CatalogResult.Value(decodeAbemaSeriesProjection("{\"seriesId\":\"series-A\",\"version\":\"v1\",\"programs\":[$rows]}", request)!!)
            }
            override fun cancelActiveRequest() = Unit
            override fun close() = Unit
        } }, { allowed })
        val controller = StreamManagementController(instance, catalog, store, { emptyList() }, this, Dispatchers.Unconfined)
        try {
            controller.load(); idle(controller); controller.lookup("https://abema.tv/video/title/series-A"); idle(controller)
            assertEquals(0, memory.writes); controller.add(parent); var state = idle(controller)
            val collectionId = state.configured!!.single().id
            val quality = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC,
                bitrateBps = 1_000_000, width = 640, height = 360))
            store.saveQuality(collectionId, NativeQualityKind.VIDEO, quality) { emptyList() }
            val before = memory.bytes!!.copyOf(); val writes = memory.writes
            controller.children(parent.resource); state = idle(controller)
            assertEquals(parent.resource, state.query.parent); assertEquals(20, state.entries.size); assertNotNull(state.nextCursor)
            assertEquals(1, state.configured!!.size); assertArrayEquals(before, memory.bytes); assertEquals(writes, memory.writes)
            controller.more(); state = idle(controller); assertEquals(21, state.entries.size); assertNull(state.nextCursor)
            assertTrue(state.entries.all { it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null })
            val episode = state.entries.last(); controller.add(episode); state = idle(controller)
            val episodeId = state.configured!!.last().id
            controller.add(episode); state = idle(controller)
            assertEquals(listOf(collectionId, episodeId), state.configured!!.map { it.id }); assertEquals(quality, state.configured!!.first().quality.video)
            assertTrue(controller.back()); state = idle(controller)
            assertEquals(listOf(parent), state.entries); assertNull(state.query.parent)
            val reopened = ConfiguredSourceStore(memory, instance.id, ProviderId("abema")).read { error("No fallback") }
            assertEquals(listOf(parent, episode), reopened.map { it.entry }); assertEquals(quality, reopened.first().quality.video)
            val persisted = memory.bytes!!.copyOf(); controller.children(parent.resource); idle(controller)
            allowed = false; controller.more(); state = idle(controller)
            assertTrue(state.entries.isEmpty()); assertNull(state.nextCursor); assertNull(state.query.parent)
            assertEquals(listOf(collectionId, episodeId), state.configured!!.map { it.id }); assertArrayEquals(persisted, memory.bytes)
        } finally { controller.close() }
    }
}
