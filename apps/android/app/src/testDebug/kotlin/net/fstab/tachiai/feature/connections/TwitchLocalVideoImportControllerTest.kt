package net.fstab.tachiai.feature.connections

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.twitch.catalog.TwitchLocalVideoImportCatalog
import org.junit.Assert.*
import org.junit.Test

// Synthetic metadata only: exercise the actual decorator, shared controller and
// configured-record codec without authorization, provider requests or device data.
class TwitchLocalVideoImportControllerTest {
    private val videoUrl = "https://www.twitch.tv/videos/789"
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var writes = 0
        var failRead = false
        override fun read(): ByteArray? { check(!failRead); return bytes?.copyOf() }
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf(); writes++ }
    }
    private class Metadata(override val instanceId: String) : ProviderCatalog {
        override val providerId = ProviderId("twitch")
        val video = CatalogEntry(CatalogResource(providerId, "video", "789", CatalogIntent.VIDEO),
            "Connected fixture video", CatalogAvailability.AVAILABLE)
        val channel = CatalogEntry(CatalogResource(providerId, "broadcaster", "123", CatalogIntent.CHANNEL),
            "Private fixture Following channel", CatalogAvailability.OFFLINE)
        var access = CatalogAccess.AUTHORIZATION_REQUIRED
        var lookupFailure: CatalogResult.Failure? = null
        var browseFailure: CatalogResult.Failure? = null
        var beforeLookup: () -> Unit = {}
        val lookups = mutableListOf<String>()
        val queries = mutableListOf<CatalogQuery>()
        val closed = CompletableDeferred<Unit>()
        override fun capabilities() = CatalogCapabilities(browse = access, search = access, lookup = access,
            children = access, refresh = access, playback = CatalogAccess.NOT_VERIFIED,
            collections = listOf(CatalogCollection("following", "Following", access)),
            browseTitle = "Live channels", initialCollectionId = "following")
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
            queries.add(query)
            return browseFailure ?: CatalogResult.Value(CatalogPage(listOf(channel, video), "fixture-next"))
        }
        override fun lookup(input: String): CatalogResult<CatalogEntry> {
            lookups.add(input); beforeLookup()
            return lookupFailure ?: CatalogResult.Value(video)
        }
        override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        override fun close() { closed.complete(Unit) }
    }
    private class Fixture(scope: CoroutineScope, io: CoroutineDispatcher = Dispatchers.Unconfined) {
        val instance = ProviderInstance("12345678-1234-1234-1234-123456789120", PrototypeService.TWITCH, "Import fixture")
        val metadata = Metadata(instance.id)
        val memory = Memory()
        val store = ConfiguredSourceStore(memory, instance.id, metadata.providerId)
        val catalog = TwitchLocalVideoImportCatalog(metadata)
        val controller = StreamManagementController(instance, catalog, store, { emptyList() }, scope, io)
        fun reopen() = ConfiguredSourceStore(memory, instance.id, metadata.providerId).read {
            error("Committed configured imports must not fall back")
        }
    }
    private suspend fun idle(controller: StreamManagementController) = withTimeout(5_000) {
        controller.state.first { !it.loading && !it.saving }
    }

    @Test fun localPreviewRequiresAddAndDeduplicatesWithConnectedMetadataWithoutLosingQuality() = runBlocking {
        val fixture = Fixture(this); val controller = fixture.controller
        try {
            controller.load(); var state = idle(controller)
            val capabilities = checkNotNull(state.capabilities)
            assertEquals(CatalogAccess.AVAILABLE, capabilities.lookup)
            assertEquals(CatalogAccess.AUTHORIZATION_REQUIRED, capabilities.browse)
            assertEquals(CatalogAccess.AUTHORIZATION_REQUIRED, capabilities.search)
            assertEquals(CatalogAccess.AUTHORIZATION_REQUIRED, capabilities.children)
            assertEquals(CatalogAccess.AUTHORIZATION_REQUIRED, capabilities.refresh)
            assertEquals(CatalogAccess.AUTHORIZATION_REQUIRED, capabilities.collections.single().access)
            assertEquals(CatalogAccess.NOT_VERIFIED, capabilities.playback)
            assertTrue(fixture.metadata.queries.isEmpty()); assertNull(fixture.memory.bytes)
            controller.lookup(videoUrl); state = idle(controller)
            val local = state.entries.single()
            assertEquals(fixture.metadata.video.resource, local.resource)
            assertEquals("Public Twitch video 789 (metadata not checked)", local.title)
            assertEquals(CatalogAvailability.UNKNOWN, local.availability); assertNull(local.scheduledStartEpochMs)
            assertTrue(state.configured!!.isEmpty()); assertEquals(0, fixture.memory.writes)
            assertTrue(fixture.metadata.lookups.isEmpty())
            controller.add(local); state = idle(controller)
            val id = state.configured!!.single().id
            val request = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO,
                NativeQualityCodec.AVC, 2_000_000, 1280, 720))
            val saved = fixture.store.saveQuality(id, NativeQualityKind.VIDEO, request) { emptyList() }
            fixture.metadata.access = CatalogAccess.AVAILABLE
            controller.load(); idle(controller)
            controller.lookup(videoUrl); state = idle(controller)
            assertEquals(listOf(fixture.metadata.video), state.entries)
            controller.add(state.entries.single()); state = idle(controller)
            assertEquals(saved, state.configured); assertEquals(saved, fixture.reopen())
            assertEquals(request, state.configured!!.single().quality.video)
            assertEquals(local, state.configured!!.single().entry)
            assertEquals(listOf(videoUrl), fixture.metadata.lookups)
        } finally { controller.close() }
    }

    @Test fun disconnectedChannelInputsAndSensitiveUrlsAreRejectedWithoutMetadataRequestsOrWrites() = runBlocking {
        val fixture = Fixture(this); val controller = fixture.controller
        try {
            controller.load(); idle(controller)
            listOf("789", "fixturechannel", "https://twitch.tv/fixturechannel", "https://twitch.tv/789",
                "$videoUrl?token=private-fixture", "$videoUrl#private-fixture", "https://twitch.tv/videos/0789")
                .forEach { input ->
                    controller.lookup(input); val state = idle(controller)
                    assertEquals(CatalogFailure.INVALID_INPUT, state.failure!!.reason)
                    assertTrue(state.entries.isEmpty()); assertTrue(state.configured!!.isEmpty())
                }
            controller.all(); assertEquals(CatalogFailure.ACCESS_REQUIRED, idle(controller).failure!!.reason)
            controller.search("private fixture search"); idle(controller)
            controller.collection("following"); idle(controller)
            controller.children(fixture.metadata.channel.resource); idle(controller)
            assertTrue(fixture.metadata.queries.isEmpty()); assertTrue(fixture.metadata.lookups.isEmpty())
            assertEquals(0, fixture.memory.writes); assertNull(fixture.memory.bytes)
            fixture.metadata.access = CatalogAccess.NOT_VERIFIED
            controller.load(); var state = idle(controller)
            assertEquals(CatalogAccess.NOT_VERIFIED, state.capabilities!!.collections.single().access)
            assertEquals(CatalogAccess.NOT_VERIFIED, state.capabilities!!.browse)
            controller.lookup(videoUrl); state = idle(controller)
            assertEquals(CatalogAvailability.UNKNOWN, state.entries.single().availability)
            assertTrue(fixture.metadata.lookups.isEmpty()); assertNull(fixture.memory.bytes)
        } finally { controller.close() }
    }

    @Test fun unreadableConfiguredStorageBlocksLocalImportAndAddUntilExplicitRecovery() = runBlocking {
        val fixture = Fixture(this); val controller = fixture.controller
        val saved = fixture.store.add(fixture.metadata.video) { emptyList() }
        val bytes = fixture.memory.bytes!!.copyOf(); val writes = fixture.memory.writes
        fixture.memory.failRead = true
        try {
            controller.load(); var state = idle(controller)
            assertTrue(state.storageFailed); assertNull(state.capabilities); assertNull(state.configured)
            controller.lookup(videoUrl); controller.add(fixture.metadata.video)
            assertEquals(writes, fixture.memory.writes); assertArrayEquals(bytes, fixture.memory.bytes)
            assertTrue(fixture.metadata.lookups.isEmpty())
            fixture.memory.failRead = false; controller.retry(); state = idle(controller)
            assertFalse(state.storageFailed); assertEquals(saved, state.configured)
            controller.lookup(videoUrl); state = idle(controller)
            assertEquals(CatalogAvailability.UNKNOWN, state.entries.single().availability)
            controller.add(state.entries.single()); state = idle(controller)
            assertEquals(saved, state.configured); assertEquals(saved, fixture.reopen())
        } finally { controller.close() }
    }

    @Test fun connectedLookupFailuresAreNeverConvertedIntoLocalUnknownMetadata() = runBlocking {
        listOf(CatalogFailure.TEMPORARY, CatalogFailure.NOT_FOUND, CatalogFailure.RATE_LIMITED,
            CatalogFailure.ACCESS_REQUIRED).forEach { reason ->
            val fixture = Fixture(this); val controller = fixture.controller
            fixture.metadata.access = CatalogAccess.AVAILABLE
            val expected = CatalogResult.Failure(reason, if (reason == CatalogFailure.RATE_LIMITED) 50_000 else null)
            fixture.metadata.lookupFailure = expected
            try {
                controller.load(); idle(controller)
                controller.lookup(videoUrl); val state = idle(controller)
                assertEquals(expected, state.failure); assertTrue(state.entries.isEmpty())
                assertEquals(listOf(videoUrl), fixture.metadata.lookups)
                assertNull(fixture.memory.bytes)
            } finally { controller.close() }
        }
    }

    @Test fun accessLossClearsPrivatePagesAndRequiresReloadBeforeFreshLocalLookup() = runBlocking {
        val fixture = Fixture(this); val controller = fixture.controller
        fixture.metadata.access = CatalogAccess.AVAILABLE
        try {
            controller.load(); idle(controller); controller.add(fixture.metadata.video)
            val before = idle(controller); val bytes = fixture.memory.bytes!!.copyOf()
            assertNotNull(before.nextCursor); assertTrue(before.entries.contains(fixture.metadata.channel))
            fixture.metadata.access = CatalogAccess.AUTHORIZATION_REQUIRED
            fixture.metadata.browseFailure = CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)
            controller.more(); var state = idle(controller)
            assertTrue(state.entries.isEmpty()); assertNull(state.nextCursor); assertNull(state.capabilities)
            assertEquals(CatalogQuery(collectionId = "following"), state.query)
            assertTrue(state.privacyRevision > before.privacyRevision)
            assertEquals(before.configured, state.configured); assertArrayEquals(bytes, fixture.memory.bytes)
            controller.lookup(videoUrl); state = idle(controller)
            assertTrue(state.entries.isEmpty()); assertTrue(fixture.metadata.lookups.isEmpty())
            controller.load(); state = idle(controller)
            assertEquals(CatalogAccess.AVAILABLE, state.capabilities!!.lookup)
            assertTrue(state.entries.isEmpty()); assertEquals(2, fixture.metadata.queries.size)
            controller.lookup(videoUrl); state = idle(controller)
            assertEquals(fixture.metadata.video.resource, state.entries.single().resource)
            assertEquals(CatalogAvailability.UNKNOWN, state.entries.single().availability)
            assertEquals(before.configured, state.configured)
            assertTrue(fixture.metadata.lookups.isEmpty()); assertArrayEquals(bytes, fixture.memory.bytes)
        } finally { controller.close() }
    }

    @Test fun closeClearsImmediatelyAndLateConnectedLookupCannotPublishOrSave() = runBlocking {
        val fixture = Fixture(this, Dispatchers.Default); val controller = fixture.controller
        fixture.metadata.access = CatalogAccess.AVAILABLE
        val entered = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        try {
            controller.load(); idle(controller); controller.add(fixture.metadata.video)
            val before = idle(controller)
            fixture.metadata.beforeLookup = {
                entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)); returned.complete(Unit)
            }
            controller.lookup(videoUrl); withTimeout(5_000) { entered.await() }
            controller.close(); val closed = controller.state.value
            assertTrue(closed.entries.isEmpty()); assertNull(closed.nextCursor); assertNull(closed.capabilities)
            assertEquals(CatalogQuery(), closed.query); assertEquals(before.configured, closed.configured)
            withTimeout(5_000) { fixture.metadata.closed.await() }
            release.countDown(); withTimeout(5_000) { returned.await() }
            controller.load(); controller.lookup(videoUrl); controller.add(fixture.metadata.video)
            assertEquals(closed, controller.state.value); assertEquals(before.configured, fixture.reopen())
        } finally { release.countDown(); controller.close() }
    }
}
