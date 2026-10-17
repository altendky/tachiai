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

// Actual ABEMA adapter -> generic controller -> configured record codec, using
// only memory stores and a synthetic episode transport. No provider/device data.
class AbemaEpisodeRefreshIntegrationTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var writes = 0
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf(); writes++ }
    }
    private suspend fun idle(controller: StreamManagementController) = withTimeout(5_000) {
        controller.state.first { !it.loading && !it.saving }
    }

    @Test fun explicitEpisodeRefreshUpdatesMetadataAndPreservesLatestLocalConfigurationAndStoredSourceLabels() = runBlocking {
        val owner = ProviderInstance(UUID.randomUUID().toString(), PrototypeService.ABEMA, "Episode fixture")
        val provider = ProviderId("abema")
        val sourceMemory = Memory()
        val sourceStore = SourceSetupStore(sourceMemory)
        sourceStore.save(PrototypeSource.ABEMA_REPLAY, SourceSetup("My stored replay label"))
        val sourceBytes = checkNotNull(sourceMemory.bytes).copyOf()
        val setups = sourceStore.read()
        val memory = Memory()
        val store = ConfiguredSourceStore(memory, owner.id, provider)
        fun legacy() = legacyConfiguredSources(owner, setups, emptyMap())
        val original = store.read(::legacy)
        val target = original.single { it.entry.resource.kind == "episode" }
        assertEquals("My stored replay label", target.entry.title)

        val otherOwner = ProviderInstance(UUID.randomUUID().toString(), PrototypeService.ABEMA, "Other fixture")
        val otherMemory = Memory()
        val otherStore = ConfiguredSourceStore(otherMemory, otherOwner.id, provider)
        otherStore.add(CatalogEntry(CatalogResource(provider, "episode", "other-owner-episode", CatalogIntent.VIDEO), "Other title")) {
            emptyList()
        }
        val otherBytes = checkNotNull(otherMemory.bytes).copyOf()
        val otherWrites = otherMemory.writes

        val requests = mutableListOf<CatalogResource>()
        val updated = CatalogEntry(target.entry.resource, "Verified provider episode title", CatalogAvailability.UNKNOWN)
        val quality = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC,
            2_000_000, 1280, 720))
        var latestOrder = emptyList<String>()
        var closes = 0
        val metadata = AbemaChannelCatalog(owner.id, { object : AbemaChannelTransport {
            override fun fetch() = AbemaChannelResponse(200)
            override fun close() = Unit
        } }, episodeTransportFactory = { gate -> object : AbemaEpisodeTransport {
            override fun fetch(resource: CatalogResource): CatalogResult<CatalogEntry> {
                assertTrue(gate()); requests.add(resource)
                latestOrder = store.read(::legacy).map { it.id }.reversed()
                store.reorder(latestOrder, ::legacy)
                store.saveQuality(target.id, NativeQualityKind.VIDEO, quality, ::legacy)
                return CatalogResult.Value(updated)
            }
            override fun cancelActiveRequest() = Unit
            override fun close() { closes++ }
        } })
        val catalog = AbemaLocalImportCatalog(owner, setups, metadata)
        // Import stays local even though episode metadata access is available.
        val imported = (catalog.lookup("https://abema.tv/video/episode/future-fixture") as CatalogResult.Value).value
        assertEquals(CatalogAvailability.UNKNOWN, imported.availability)
        assertTrue(requests.isEmpty())
        val saved = store.add(imported, ::legacy)
        val writesBeforeRefresh = memory.writes
        val choice = target.choice
        val controller = StreamManagementController(owner, catalog, store, ::legacy, this, Dispatchers.Unconfined)
        try {
            controller.load(); val loaded = idle(controller)
            assertEquals(saved, loaded.configured); assertTrue(requests.isEmpty())
            controller.refresh(target.id)
            val state = idle(controller)
            val configured = checkNotNull(state.configured)
            assertEquals(listOf(target.entry.resource), requests); assertEquals(1, closes)
            assertEquals(latestOrder, configured.map { it.id })
            val refreshed = configured.single { it.id == target.id }
            assertEquals(target.id, refreshed.id); assertEquals(owner.id, refreshed.instanceId)
            assertEquals(updated, refreshed.entry); assertEquals(quality, refreshed.quality.video)
            assertEquals(target.quality.audio, refreshed.quality.audio)
            assertEquals(refreshed, choice.resolve(configured))
            assertEquals(saved.filterNot { it.id == target.id }.toSet(), configured.filterNot { it.id == target.id }.toSet())
            assertEquals(writesBeforeRefresh + 3, memory.writes)
            assertEquals(configured, ConfiguredSourceStore(memory, owner.id, provider).read { error("No legacy fallback") })
            assertArrayEquals(sourceBytes, sourceMemory.bytes)
            assertEquals("My stored replay label", sourceStore.read().getValue(PrototypeSource.ABEMA_REPLAY).name)
            assertArrayEquals(otherBytes, otherMemory.bytes); assertEquals(otherWrites, otherMemory.writes)
            assertTrue(state.entries.isEmpty()); assertNull(state.nextCursor); assertNull(state.failure)
        } finally { controller.close() }
    }
}
