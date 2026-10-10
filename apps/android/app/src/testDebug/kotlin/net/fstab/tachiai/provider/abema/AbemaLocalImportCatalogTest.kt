package net.fstab.tachiai.provider.abema

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class AbemaLocalImportCatalogTest {
    private val owner = defaultProviderInstances().first { it.service == PrototypeService.ABEMA }
    private val setups = PrototypeSource.entries.associateWith { SourceSetup(name = it.title) }
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
    }
    private fun entry(catalog: ProviderCatalog, url: String) = (catalog.lookup(url) as CatalogResult.Value).value
    private suspend fun idle(controller: StreamManagementController) = withTimeout(5_000) {
        controller.state.first { !it.loading && !it.saving }
    }

    @Test fun localLookupPreservesSampleNamesAndReportsUnverifiedProviderAccess() {
        val custom = setups + (PrototypeSource.ABEMA_LIVE to SourceSetup(name = "My news"))
        val catalog = AbemaLocalImportCatalog(owner, custom)
        assertEquals(owner.id, catalog.instanceId)
        val capabilities = catalog.capabilities()
        assertEquals(CatalogAccess.AVAILABLE, capabilities.lookup)
        assertEquals(CatalogAccess.UNSUPPORTED, capabilities.children)
        assertEquals(CatalogAccess.UNSUPPORTED, capabilities.playback)
        assertEquals(CatalogAccess.NOT_VERIFIED, capabilities.collections.single().access)
        val samples = (catalog.browse(CatalogQuery()) as CatalogResult.Value).value.entries
        assertEquals("My news", samples.first().title)
        assertEquals(listOf(samples.first()), (catalog.browse(CatalogQuery(search = "news")) as CatalogResult.Value).value.entries)
        val imported = entry(catalog, "https://abema.tv/channels/sumo")
        assertEquals(CatalogAvailability.UNKNOWN, imported.availability)
        assertNull(imported.scheduledStartEpochMs)
        assertEquals(CatalogResult.Failure(CatalogFailure.NOT_VERIFIED), catalog.refresh(imported.resource))
        assertEquals(CatalogResult.Failure(CatalogFailure.NOT_VERIFIED), catalog.resolve(imported.resource))
        assertEquals(CatalogResult.Failure(CatalogFailure.NOT_VERIFIED), catalog.browse(CatalogQuery(collectionId = "my_list")))
        assertEquals(CatalogResult.Failure(CatalogFailure.INVALID_INPUT), catalog.lookup("https://abema.tv/account?token=fixture"))
        catalog.close()
        assertEquals(CatalogResult.Failure(CatalogFailure.TEMPORARY), catalog.lookup("https://abema.tv/channels/sumo"))
        assertEquals(CatalogResult.Failure(CatalogFailure.TEMPORARY), catalog.browse(CatalogQuery()))
        assertThrows(IllegalArgumentException::class.java) { AbemaLocalImportCatalog(defaultProviderInstances().last(), setups) }
    }

    @Test fun explicitImportsDeduplicatePersistAndRemainInstanceOwnedThroughUnavailableMyList() = runBlocking {
        val memory = Memory()
        val store = ConfiguredSourceStore(memory, owner.id, ProviderId("abema"))
        fun controller() = StreamManagementController(owner, AbemaLocalImportCatalog(owner, setups), store,
            { emptyList() }, this, Dispatchers.Unconfined)
        val first = controller()
        first.load(); idle(first)
        val links = listOf("https://abema.tv/channels/sumo",
            "https://abema.tv/channels/sumo/slots/A63wR6irBEskZM",
            "https://abema.tv/video/episode/394-72_s10_p845", "https://abema.tv/video/title/394-72")
        for (link in links) {
            first.lookup(link); val preview = idle(first).entries.single()
            assertEquals(CatalogAvailability.UNKNOWN, preview.availability)
            assertEquals(links.indexOf(link), first.state.value.configured!!.size)
            first.add(preview); idle(first)
        }
        first.lookup(" HTTPS://ABEMA.TV:443/channels/sumo "); val same = idle(first).entries.single()
        first.add(same); var state = idle(first)
        assertEquals(4, state.configured!!.size)
        val before = state.configured
        first.collection("my_list"); state = idle(first)
        assertEquals(CatalogFailure.NOT_VERIFIED, state.failure!!.reason)
        assertEquals(before, state.configured)
        first.move(before.last().id, -1); state = idle(first)
        assertEquals(CatalogIntent.COLLECTION, state.configured!![2].entry.resource.intent)
        val retained = state.configured
        first.close()
        val reopened = controller(); reopened.load(); state = idle(reopened)
        assertEquals(retained, state.configured)
        assertTrue(state.configured!!.all { it.instanceId == owner.id })
        assertFalse(String(memory.bytes!!, Charsets.ISO_8859_1).contains("https://"))
        val other = owner.copy(id = java.util.UUID.randomUUID().toString(), customName = "Other ABEMA")
        val otherStore = ConfiguredSourceStore(Memory(), other.id, ProviderId("abema"))
        assertTrue(otherStore.read { emptyList() }.isEmpty())
        assertThrows(IllegalStateException::class.java) { ConfiguredSourceStore(memory, other.id, ProviderId("abema")).read { emptyList() } }
        retained.forEach { reopened.remove(it.id); idle(reopened) }
        reopened.close()
        assertTrue(store.read { error("Empty saved list must not restore samples") }.isEmpty())
    }

    @Test fun selectionKeepsImportedIdentitiesAndNeverSubstitutesSamplePlayback() {
        val catalog = AbemaLocalImportCatalog(owner, setups)
        val routeOwner = owner.copy(setup = owner.setup.copy(route = SourceRouteChoice.system))
        fun selection(url: String): ConfiguredPrototypeSelectionResult {
            val configured = ConfiguredSource(java.util.UUID.randomUUID().toString(), owner.id, entry(catalog, url))
            return resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(configured.choice, configured.choice),
                listOf(configured), listOf(routeOwner))
        }
        listOf("https://abema.tv/channels/sumo", "https://abema.tv/channels/sumo/slots/A63wR6irBEskZM",
            "https://abema.tv/video/episode/394-72_s10_p845").forEach {
            assertEquals(ConfiguredPrototypeSelectionResult.Failure(ConfiguredPrototypeSelectionFailure.UNSUPPORTED), selection(it))
        }
        assertEquals(ConfiguredPrototypeSelectionResult.Failure(ConfiguredPrototypeSelectionFailure.COLLECTION),
            selection("https://abema.tv/video/title/394-72"))
        assertEquals(PrototypeSource.ABEMA_LIVE,
            (selection("https://abema.tv/channels/abema-news") as ConfiguredPrototypeSelectionResult.Ready).selection.a)
        assertEquals(PrototypeSource.ABEMA_REPLAY,
            (selection("https://abema.tv/video/episode/394-72_s10_p8529") as ConfiguredPrototypeSelectionResult.Ready).selection.a)
        catalog.close()
    }

    @Test fun importingKnownSampleKeepsItsCustomTitleLocalIdAndQualityDefaults() = runBlocking {
        val memory = Memory()
        val store = ConfiguredSourceStore(memory, owner.id, ProviderId("abema"))
        val quality = NativeQualityPreferences(video = NativeQualityRequest(
            NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC, 2_000_000, 1280, 720)))
        val legacy = legacyConfiguredSources(owner,
            setups + (PrototypeSource.ABEMA_LIVE to SourceSetup("My custom news")),
            mapOf(PrototypeSource.ABEMA_LIVE to quality))
        val controller = StreamManagementController(owner, AbemaLocalImportCatalog(owner, setups), store,
            { legacy }, this, Dispatchers.Unconfined)
        controller.load(); idle(controller)
        controller.lookup("https://abema.tv/channels/abema-news")
        val imported = idle(controller).entries.single()
        controller.add(imported)
        assertEquals(legacy, idle(controller).configured)
        assertEquals(legacy, store.read { error("Committed list expected") })
        controller.close()
    }
}
