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

class AbemaSlotRefreshIntegrationTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null; var writes = 0
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf(); writes++ }
    }
    private suspend fun idle(controller: StreamManagementController) = withTimeout(5000) {
        controller.state.first { !it.loading && !it.saving }
    }
    @Test fun explicitSlotRefreshPreservesExactConfiguredIdentityLatestQualityOrderAndFutureUnavailableItems() = runBlocking {
        val owner = ProviderInstance(UUID.randomUUID().toString(), PrototypeService.ABEMA, "Fixture")
        val memory = Memory(); val store = ConfiguredSourceStore(memory, owner.id, ProviderId("abema"))
        fun legacy() = emptyList<ConfiguredSource>()
        val imported = parseAbemaPublicResource("https://abema.tv/channels/channel-A/slots/future-slot")!!
        val target = store.add(imported, ::legacy).single()
        val other = CatalogEntry(AbemaSlotRequest("channel-B", "unavailable-slot").resource, "Not yet live", CatalogAvailability.UNAVAILABLE)
        store.add(other, ::legacy)
        val requests = mutableListOf<CatalogResource>(); var fail = false; var closes = 0
        var order = emptyList<String>()
        val quality = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC, 1_000_000, 640, 360))
        var channelRequests = 0
        val metadata = AbemaChannelCatalog(owner.id, { object : AbemaChannelTransport {
            override fun fetch(): AbemaChannelResponse { channelRequests++; return AbemaChannelResponse(200) }
            override fun close() = Unit
        } }, slotTransportFactory = { gate ->
            object : AbemaSlotTransport {
                override fun fetch(resource: CatalogResource): CatalogResult<CatalogEntry> {
                    check(gate()); requests += resource
                    if (fail) return CatalogResult.Failure(CatalogFailure.NOT_FOUND)
                    order = store.read(::legacy).map { it.id }.reversed(); store.reorder(order, ::legacy)
                    store.saveQuality(target.id, NativeQualityKind.VIDEO, quality, ::legacy)
                    return CatalogResult.Value(CatalogEntry(resource, "Exact provider broadcast title"))
                }
                override fun cancelActiveRequest() = Unit
                override fun close() { closes++ }
            }
        })
        val catalog = AbemaLocalImportCatalog(owner, emptyMap(), metadata)
        val controller = StreamManagementController(owner, catalog, store, ::legacy, this, Dispatchers.Unconfined)
        try {
            controller.load(); idle(controller); val initialChannelRequests = channelRequests
            controller.refresh(target.id)
            var state = idle(controller); val refreshed = state.configured!!.single { it.id == target.id }
            assertEquals(listOf(imported.resource), requests); assertEquals(1, closes)
            assertEquals(initialChannelRequests, channelRequests)
            assertEquals(target.choice, refreshed.choice); assertEquals(imported.resource, refreshed.entry.resource)
            assertEquals("Exact provider broadcast title", refreshed.entry.title); assertEquals(CatalogAvailability.UNKNOWN, refreshed.entry.availability)
            assertNull(refreshed.entry.scheduledStartEpochMs); assertEquals(quality, refreshed.quality.video)
            assertEquals(order, state.configured!!.map { it.id }); assertEquals(other, state.configured!!.single { it.id != target.id }.entry)
            val before = memory.bytes!!.copyOf(); val writes = memory.writes
            fail = true; controller.refresh(target.id); state = idle(controller)
            assertEquals(CatalogFailure.NOT_FOUND, state.failure!!.reason)
            assertArrayEquals(before, memory.bytes); assertEquals(writes, memory.writes)
            assertEquals(refreshed, state.configured!!.single { it.id == target.id })
            assertEquals(state.configured, ConfiguredSourceStore(memory, owner.id, ProviderId("abema")).read { error("No reset") })
            assertEquals(CatalogFailure.NOT_VERIFIED, (catalog.resolve(imported.resource) as CatalogResult.Failure).reason)
        } finally { controller.close() }
    }
}
