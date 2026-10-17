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

class AbemaScheduleManagementTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null; var writes = 0
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf(); writes++ }
    }
    private suspend fun idle(controller: StreamManagementController) = withTimeout(5000) { controller.state.first { !it.loading && !it.saving } }
    @Test fun sharedCollectionAddsOnlyExplicitNotLiveSlotsAndPreservesConfiguredUUIDQualityAcrossAccessLossAndUpstreamOmission() = runBlocking {
        val instance = ProviderInstance(UUID.randomUUID().toString(), PrototypeService.ABEMA, "Fixture")
        val memory = Memory(); val store = ConfiguredSourceStore(memory, instance.id, ProviderId("abema"))
        var allowed = true; var rows = List(101) { CatalogEntry(AbemaSlotRequest("channel-A", "slot-$it").resource, "Future slot $it") }
        var fetches = 0
        val delegate = object : ProviderCatalog {
            override val providerId = ProviderId("abema"); override val instanceId = instance.id
            override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, browseTitle = "Channels")
            override fun browse(query: CatalogQuery) = CatalogResult.Value(CatalogPage(emptyList()))
            override fun lookup(input: String) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
            override fun refresh(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.NOT_FOUND)
            override fun resolve(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        }
        val catalog = AbemaScheduleCatalog(delegate, { gate -> object : AbemaScheduleTransport {
            override fun fetch(request: AbemaScheduleRequest): CatalogResult<AbemaScheduleSnapshot> { check(gate()); fetches++; return CatalogResult.Value(AbemaScheduleSnapshot(rows)) }
            override fun cancelActiveRequest() = Unit
            override fun close() = Unit
        } }, { allowed })
        val controller = StreamManagementController(instance, catalog, store, { emptyList() }, this, Dispatchers.Unconfined)
        try {
            controller.load(); idle(controller); controller.collection("schedule"); var state = idle(controller)
            assertEquals(0, memory.writes); assertEquals(100, state.entries.size); assertTrue(state.configured!!.isEmpty())
            val chosen = state.entries.first(); controller.add(chosen); state = idle(controller)
            val savedId = state.configured!!.single().id
            val quality = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC,
                bitrateBps = 1_000_000, width = 640, height = 360))
            store.saveQuality(savedId, NativeQualityKind.VIDEO, quality) { emptyList() }
            val before = memory.bytes!!.copyOf(); val writes = memory.writes
            controller.more(); state = idle(controller)
            assertEquals(101, state.entries.size); assertNull(state.nextCursor); assertEquals(1, fetches)
            assertTrue(state.entries.all { it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null })
            assertArrayEquals(before, memory.bytes); assertEquals(writes, memory.writes)
            controller.add(chosen); state = idle(controller); assertEquals(savedId, state.configured!!.single().id)
            assertEquals(quality, store.read { error("No fallback") }.single().quality.video)
            rows = emptyList(); controller.collection("schedule"); state = idle(controller)
            assertTrue(state.entries.isEmpty()); assertEquals(savedId, state.configured!!.single().id)
            val persisted = memory.bytes!!.copyOf(); allowed = false; controller.collection("schedule"); state = idle(controller)
            assertTrue(state.entries.isEmpty()); assertNull(state.nextCursor)
            assertEquals(savedId, state.configured!!.single().id); assertArrayEquals(persisted, memory.bytes)
            val reopened = ConfiguredSourceStore(memory, instance.id, ProviderId("abema")).read { error("No fallback") }
            assertEquals(chosen, reopened.single().entry); assertEquals(quality, reopened.single().quality.video)
        } finally { controller.close() }
    }
    @Test fun shared500ResultLimitReportsRealSnapshotTruncationWithoutExtraRequestsOrConfiguredWrites() = runBlocking {
        val instance = ProviderInstance(UUID.randomUUID().toString(), PrototypeService.ABEMA, "Fixture")
        val memory = Memory(); var fetches = 0
        val delegate = object : ProviderCatalog {
            override val providerId = ProviderId("abema"); override val instanceId = instance.id
            override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE)
            override fun browse(query: CatalogQuery) = CatalogResult.Value(CatalogPage(emptyList()))
            override fun lookup(input: String) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
            override fun refresh(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
            override fun resolve(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        }
        val catalog = AbemaScheduleCatalog(delegate, { object : AbemaScheduleTransport {
            override fun fetch(request: AbemaScheduleRequest): CatalogResult<AbemaScheduleSnapshot> {
                fetches++; return CatalogResult.Value(AbemaScheduleSnapshot(List(501) {
                    CatalogEntry(AbemaSlotRequest("channel-A", "slot-$it").resource, "Future slot $it") }, true))
            }
            override fun cancelActiveRequest() = Unit
            override fun close() = Unit
        } })
        val controller = StreamManagementController(instance, catalog, ConfiguredSourceStore(memory, instance.id, ProviderId("abema")),
            { emptyList() }, this, Dispatchers.Unconfined)
        try {
            controller.load(); idle(controller); controller.collection("schedule"); idle(controller)
            repeat(4) { controller.more(); idle(controller) }
            val state = controller.state.value
            assertEquals(500, state.entries.size); assertTrue(state.resultsTruncated); assertNull(state.nextCursor)
            assertTrue(state.configured!!.isEmpty()); assertEquals(0, memory.writes); assertEquals(1, fetches)
            controller.more(); idle(controller); assertEquals(1, fetches)
        } finally { controller.close() }
    }
}
