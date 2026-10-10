package net.fstab.tachiai.feature.connections

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

// Synthetic metadata through the actual controller, store and protected-record
// codec. No provider accounts, requests or device-private records are accessed.
class ConfiguredMetadataRefreshControllerTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var writes = 0
        var failRead = false
        var failWrite = false
        var beforeWrite: () -> Unit = {}
        override fun read(): ByteArray? { check(!failRead); return bytes?.copyOf() }
        override fun write(plaintext: ByteArray) {
            check(!failWrite); beforeWrite(); bytes = plaintext.copyOf(); writes++
        }
    }
    private class Metadata(override val instanceId: String, override val providerId: ProviderId,
        val initial: CatalogEntry) : ProviderCatalog {
        var refreshAccess = CatalogAccess.AVAILABLE
        var refreshed: CatalogResult<CatalogEntry> = CatalogResult.Value(initial.copy(title = "Updated channel",
            availability = CatalogAvailability.LIVE, scheduledStartEpochMs = 4_102_444_800_000L))
        var onRefresh: () -> Unit = {}
        val refreshes = mutableListOf<CatalogResource>()
        var browses = 0
        var lookups = 0
        val closedSignal = CompletableDeferred<Unit>()
        override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, search = CatalogAccess.AVAILABLE,
            lookup = CatalogAccess.AVAILABLE, refresh = refreshAccess,
            collections = listOf(CatalogCollection("following", "Following", CatalogAccess.AVAILABLE)),
            initialCollectionId = "following")
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
            browses++
            return CatalogResult.Value(CatalogPage(listOf(initial.copy(title = "Private discovery fixture")), "fixture-next"))
        }
        override fun lookup(input: String): CatalogResult<CatalogEntry> { lookups++; return CatalogResult.Value(initial) }
        override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> {
            refreshes.add(resource); onRefresh(); return refreshed
        }
        override fun resolve(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        override fun close() { closedSignal.complete(Unit) }
    }
    private inner class Fixture(scope: CoroutineScope, availability: CatalogAvailability = CatalogAvailability.UNKNOWN,
        io: CoroutineDispatcher = Dispatchers.Unconfined, service: PrototypeService = PrototypeService.TWITCH) {
        val instance = ProviderInstance(UUID.randomUUID().toString(), service, "Metadata fixture")
        val provider = ProviderId(service.name.lowercase(java.util.Locale.ROOT))
        val original = CatalogEntry(CatalogResource(provider, "channel", "123", CatalogIntent.CHANNEL), "Saved channel", availability)
        val memory = Memory()
        val store = ConfiguredSourceStore(memory, instance.id, provider)
        val saved = store.add(original) { emptyList() }.single()
        val metadata = Metadata(instance.id, provider, original)
        val observations = mutableListOf<FailureObservation>()
        var now = 1_000L
        val controller = StreamManagementController(instance, metadata, store, { emptyList() }, scope, io,
            diagnostics = FailureReporter({ observations.add(it) }),
            clockMs = { now })
        fun reopen() = ConfiguredSourceStore(memory, instance.id, provider).read { error("No legacy fallback") }
        suspend fun load() { controller.load(); idle(controller) }
    }
    private suspend fun idle(controller: StreamManagementController) = withTimeout(5_000) {
        controller.state.first { !it.loading && !it.saving }
    }

    @Test fun explicitRefreshReplacesUnknownOrOfflineMetadataAndRetainsExactLocalIdentityThroughReopen() = runBlocking {
        listOf(PrototypeService.TWITCH, PrototypeService.ABEMA).forEach { service ->
            listOf(CatalogAvailability.UNKNOWN, CatalogAvailability.OFFLINE).forEach { availability ->
                val fixture = Fixture(this, availability, service = service)
                try {
                    val writes = fixture.memory.writes
                    fixture.load(); val before = fixture.controller.state.value
                    assertEquals(writes, fixture.memory.writes)
                    fixture.controller.refresh(fixture.saved.id)
                    val state = idle(fixture.controller)
                    val updated = state.configured!!.single()
                    assertEquals(fixture.saved.id, updated.id); assertEquals(fixture.saved.instanceId, updated.instanceId)
                    assertEquals(fixture.original.resource, updated.entry.resource)
                    assertEquals((fixture.metadata.refreshed as CatalogResult.Value).value, updated.entry)
                    assertEquals(fixture.saved.quality, updated.quality)
                    assertEquals(listOf(fixture.original.resource), fixture.metadata.refreshes)
                    assertEquals(writes + 1, fixture.memory.writes); assertEquals(listOf(updated), fixture.reopen())
                    assertTrue(state.entries.isEmpty()); assertNull(state.nextCursor)
                    assertTrue(state.privacyRevision > before.privacyRevision); assertNull(state.failure)
                } finally { fixture.controller.close() }
            }
        }
    }

    @Test fun unavailableMetadataIsRetainedAndCurrentOrderAndQualityWinOverTheRetrievalSnapshot() = runBlocking {
        val fixture = Fixture(this, CatalogAvailability.OFFLINE)
        try {
            val second = fixture.store.add(fixture.original.copy(resource = fixture.original.resource.copy(identity = "456"),
                title = "Second saved channel")) { emptyList() }.last()
            fixture.load()
            val quality = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC,
                2_000_000, 1280, 720))
            val unavailable = fixture.original.copy(title = "Unavailable channel", availability = CatalogAvailability.UNAVAILABLE)
            fixture.metadata.refreshed = CatalogResult.Value(unavailable)
            fixture.metadata.onRefresh = {
                fixture.store.reorder(listOf(second.id, fixture.saved.id)) { emptyList() }
                fixture.store.saveQuality(fixture.saved.id, NativeQualityKind.VIDEO, quality) { emptyList() }
            }
            fixture.controller.refresh(fixture.saved.id)
            val state = idle(fixture.controller)
            assertEquals(listOf(second.id, fixture.saved.id), state.configured!!.map { it.id })
            assertEquals(unavailable, state.configured.last().entry)
            assertEquals(quality, state.configured.last().quality.video)
            assertEquals(second, state.configured.first()); assertEquals(state.configured, fixture.reopen())
            assertEquals(1, fixture.metadata.refreshes.size)
        } finally { fixture.controller.close() }
    }

    @Test fun returnedWrongIdentityIntentOrProviderCannotWriteOrChangeTheConfiguredSnapshot() = runBlocking {
        val fixture = Fixture(this)
        try {
            fixture.load(); val bytes = fixture.memory.bytes!!.copyOf(); val writes = fixture.memory.writes
            listOf(fixture.original.resource.copy(identity = "456"),
                fixture.original.resource.copy(intent = CatalogIntent.VIDEO),
                fixture.original.resource.copy(providerId = ProviderId("abema"))).forEach { wrong ->
                fixture.metadata.refreshed = CatalogResult.Value(CatalogEntry(wrong, "Wrong metadata"))
                fixture.controller.refresh(fixture.saved.id)
                val state = idle(fixture.controller)
                assertEquals(CatalogFailure.TEMPORARY, state.failure!!.reason)
                assertEquals(listOf(fixture.saved), state.configured)
                assertEquals(writes, fixture.memory.writes); assertArrayEquals(bytes, fixture.memory.bytes)
            }
            assertEquals(List(3) { fixture.original.resource }, fixture.metadata.refreshes)
            assertEquals(3, fixture.observations.size)
            assertTrue(fixture.observations.all { it.stage == FailureStage.CATALOG_LOAD })
        } finally { fixture.controller.close() }
    }

    @Test fun removalAndReaddDuringRefreshCannotResurrectOldUuidOrUpdateTheReplacement() = runBlocking {
        val fixture = Fixture(this)
        try {
            fixture.load(); var replacement: ConfiguredSource? = null; var bytes: ByteArray? = null; var writes = 0
            fixture.metadata.onRefresh = {
                fixture.store.remove(fixture.saved.id) { emptyList() }
                replacement = fixture.store.add(fixture.original) { emptyList() }.single()
                bytes = fixture.memory.bytes!!.copyOf(); writes = fixture.memory.writes
            }
            fixture.controller.refresh(fixture.saved.id)
            val state = idle(fixture.controller)
            assertEquals(CatalogFailure.TEMPORARY, state.failure!!.reason)
            assertNotEquals(fixture.saved.id, replacement!!.id)
            assertEquals(listOf(replacement), fixture.reopen())
            assertEquals(fixture.original, replacement!!.entry)
            assertEquals(writes, fixture.memory.writes); assertArrayEquals(bytes, fixture.memory.bytes)
            fixture.metadata.onRefresh = {}
            fixture.controller.retry(); idle(fixture.controller)
            assertEquals(List(2) { fixture.original.resource }, fixture.metadata.refreshes)
            assertEquals(listOf(replacement), fixture.reopen()); assertEquals(writes, fixture.memory.writes)
            fixture.controller.load(); idle(fixture.controller)
            fixture.controller.refresh(fixture.saved.id)
            assertEquals(CatalogFailure.INVALID_INPUT, idle(fixture.controller).failure!!.reason)
            assertEquals(2, fixture.metadata.refreshes.size)
        } finally { fixture.controller.close() }
    }

    @Test fun storageWriteFailureRetriesExactUuidAndReadFailureBlocksRefreshWithoutReplacingRecords() = runBlocking {
        val fixture = Fixture(this)
        try {
            fixture.load(); val bytes = fixture.memory.bytes!!.copyOf(); val writes = fixture.memory.writes
            fixture.memory.failWrite = true
            fixture.controller.refresh(fixture.saved.id)
            var state = idle(fixture.controller)
            assertEquals(listOf(fixture.saved), state.configured)
            assertEquals(CatalogFailure.TEMPORARY, state.failure!!.reason)
            assertEquals(FailureStage.CONFIGURED_SOURCES_WRITE, fixture.observations.single().stage)
            assertEquals(writes, fixture.memory.writes); assertArrayEquals(bytes, fixture.memory.bytes)
            val browses = fixture.metadata.browses
            fixture.memory.failWrite = false
            fixture.controller.retry(); state = idle(fixture.controller)
            assertEquals(List(2) { fixture.original.resource }, fixture.metadata.refreshes)
            assertEquals(browses, fixture.metadata.browses)
            assertEquals(fixture.saved.id, state.configured!!.single().id); assertNull(state.failure)
            assertEquals(state.configured, fixture.reopen())
            val committed = fixture.memory.bytes!!.copyOf()
            fixture.memory.failRead = true
            fixture.controller.load(); state = idle(fixture.controller)
            assertTrue(state.storageFailed)
            fixture.controller.refresh(fixture.saved.id)
            assertEquals(2, fixture.metadata.refreshes.size); assertArrayEquals(committed, fixture.memory.bytes)
        } finally { fixture.controller.close() }
    }

    @Test fun unsupportedOrDisconnectedCapabilityAndUnknownUuidNeverCallProviderRefresh() = runBlocking {
        listOf(CatalogAccess.UNSUPPORTED, CatalogAccess.NOT_VERIFIED, CatalogAccess.AUTHORIZATION_REQUIRED).forEach { access ->
            val fixture = Fixture(this)
            try {
                fixture.metadata.refreshAccess = access; fixture.load()
                val bytes = fixture.memory.bytes!!.copyOf(); val writes = fixture.memory.writes
                fixture.controller.refresh(fixture.saved.id)
                val state = idle(fixture.controller)
                assertEquals(when (access) {
                    CatalogAccess.UNSUPPORTED -> CatalogFailure.UNSUPPORTED
                    CatalogAccess.NOT_VERIFIED -> CatalogFailure.NOT_VERIFIED
                    else -> CatalogFailure.ACCESS_REQUIRED
                }, state.failure!!.reason)
                assertTrue(fixture.metadata.refreshes.isEmpty())
                assertEquals(listOf(fixture.saved), state.configured)
                assertEquals(writes, fixture.memory.writes); assertArrayEquals(bytes, fixture.memory.bytes)
            } finally { fixture.controller.close() }
        }
        val fixture = Fixture(this)
        try {
            fixture.load(); fixture.controller.refresh(UUID.randomUUID().toString())
            assertEquals(CatalogFailure.INVALID_INPUT, idle(fixture.controller).failure!!.reason)
            assertTrue(fixture.metadata.refreshes.isEmpty())
        } finally { fixture.controller.close() }
    }

    @Test fun rateLimitedRefreshRetriesOnlyItsUuidAfterDeadlineAndAnotherActionClearsTheTarget() = runBlocking {
        val fixture = Fixture(this)
        try {
            fixture.load(); fixture.controller.lookup("private lookup draft"); idle(fixture.controller)
            fixture.metadata.refreshed = CatalogResult.Failure(CatalogFailure.RATE_LIMITED, 5_000L)
            val before = fixture.controller.state.value
            fixture.controller.refresh(fixture.saved.id)
            var state = idle(fixture.controller)
            assertEquals(CatalogFailure.RATE_LIMITED, state.failure!!.reason)
            assertTrue(state.entries.isEmpty()); assertNull(state.nextCursor)
            assertNull(state.query.search); assertNull(state.query.parent)
            assertTrue(state.privacyRevision > before.privacyRevision)
            fixture.controller.retry(); fixture.controller.refresh(fixture.saved.id)
            assertEquals(1, fixture.metadata.refreshes.size)
            fixture.now = 5_000L
            fixture.metadata.refreshed = CatalogResult.Failure(CatalogFailure.TEMPORARY)
            fixture.controller.retry(); state = idle(fixture.controller)
            assertEquals(CatalogFailure.TEMPORARY, state.failure!!.reason)
            assertEquals(2, fixture.metadata.refreshes.size)
            assertEquals(1, fixture.metadata.lookups)
            fixture.metadata.refreshed = CatalogResult.Value(fixture.original.copy(title = "Finally refreshed"))
            fixture.controller.retry(); state = idle(fixture.controller)
            assertEquals("Finally refreshed", state.configured!!.single().entry.title)
            assertEquals(List(3) { fixture.original.resource }, fixture.metadata.refreshes)
            fixture.metadata.refreshed = CatalogResult.Failure(CatalogFailure.TEMPORARY)
            fixture.controller.refresh(fixture.saved.id); idle(fixture.controller)
            fixture.controller.search("new discovery query"); idle(fixture.controller)
            val refreshes = fixture.metadata.refreshes.size; val browses = fixture.metadata.browses
            fixture.controller.retry(); idle(fixture.controller)
            assertEquals(refreshes, fixture.metadata.refreshes.size); assertEquals(browses + 1, fixture.metadata.browses)
        } finally { fixture.controller.close() }
    }

    @Test fun accessLossClearsPrivateDiscoveryAndRetryIntentWhileRetainingConfiguredMetadata() = runBlocking {
        val fixture = Fixture(this)
        try {
            fixture.load(); fixture.controller.search("private account query"); idle(fixture.controller)
            val before = fixture.controller.state.value; val bytes = fixture.memory.bytes!!.copyOf()
            fixture.metadata.refreshed = CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)
            fixture.controller.refresh(fixture.saved.id)
            val state = idle(fixture.controller)
            assertEquals(CatalogFailure.ACCESS_REQUIRED, state.failure!!.reason)
            assertTrue(state.entries.isEmpty()); assertNull(state.nextCursor); assertNull(state.capabilities)
            assertNull(state.query.search); assertNull(state.query.parent)
            assertTrue(state.privacyRevision > before.privacyRevision)
            assertEquals(listOf(fixture.saved), state.configured); assertArrayEquals(bytes, fixture.memory.bytes)
            val browses = fixture.metadata.browses
            fixture.controller.retry(); idle(fixture.controller)
            assertEquals(1, fixture.metadata.refreshes.size); assertEquals(browses + 1, fixture.metadata.browses)
            assertArrayEquals(bytes, fixture.memory.bytes)
        } finally { fixture.controller.close() }
    }

    @Test fun providerExceptionPreservesSnapshotAndRetryRemainsAnExactRefreshWithSafeUiText() = runBlocking {
        val fixture = Fixture(this)
        try {
            fixture.load(); val bytes = fixture.memory.bytes!!.copyOf()
            fixture.metadata.onRefresh = { throw IllegalStateException("sensitive-provider-response") }
            fixture.controller.refresh(fixture.saved.id)
            var state = idle(fixture.controller)
            assertEquals(CatalogFailure.TEMPORARY, state.failure!!.reason)
            assertFalse(state.toString().contains("sensitive-provider-response"))
            assertEquals(FailureStage.CATALOG_LOAD, fixture.observations.single().stage)
            assertFalse(fixture.observations.toString().contains("sensitive-provider-response"))
            assertEquals(listOf(fixture.saved), state.configured); assertArrayEquals(bytes, fixture.memory.bytes)
            fixture.metadata.onRefresh = {}
            val browses = fixture.metadata.browses
            fixture.controller.retry(); state = idle(fixture.controller)
            assertEquals(2, fixture.metadata.refreshes.size); assertEquals(browses, fixture.metadata.browses)
            assertEquals(fixture.saved.id, state.configured!!.single().id)
        } finally { fixture.controller.close() }
    }

    @Test fun closeOrQuerySupersessionDuringRetrievalCannotAcceptLateRefreshWrite() = runBlocking {
        listOf(false, true).forEach { close ->
            val fixture = Fixture(this, io = Dispatchers.IO)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val response = CompletableDeferred<Unit>()
            try {
                fixture.load(); val bytes = fixture.memory.bytes!!.copyOf(); val writes = fixture.memory.writes
                fixture.metadata.onRefresh = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); response.complete(Unit) }
                fixture.controller.refresh(fixture.saved.id)
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                if (close) fixture.controller.close() else fixture.controller.all()
                release.countDown(); withTimeout(5_000) { response.await() }
                idle(fixture.controller)
                val pending = coroutineContext.job.children.toList()
                withTimeout(5_000) { pending.forEach { it.join() } }
                StreamManagementWrites.await(fixture.instance.id)
                assertEquals(writes, fixture.memory.writes); assertArrayEquals(bytes, fixture.memory.bytes)
                assertEquals(listOf(fixture.saved), fixture.reopen())
                assertEquals(1, fixture.metadata.refreshes.size)
                if (close) assertNull(fixture.controller.state.value.capabilities)
            } finally { release.countDown(); fixture.controller.close() }
        }
    }

    @Test fun acceptedTrackedRefreshWriteSurvivesCloseAndPublishesNoLaterUiState() = runBlocking {
        val fixture = Fixture(this, io = Dispatchers.IO)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            fixture.load(); val writes = fixture.memory.writes
            fixture.memory.beforeWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            fixture.controller.refresh(fixture.saved.id)
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertTrue(fixture.controller.state.value.saving)
            assertEquals(1, StreamManagementWrites.pending(fixture.instance.id).size)
            fixture.controller.close(); val closed = fixture.controller.state.value
            release.countDown(); withTimeout(5_000) { StreamManagementWrites.await(fixture.instance.id) }
            assertEquals(writes + 1, fixture.memory.writes)
            val saved = fixture.reopen().single()
            assertEquals(fixture.saved.id, saved.id)
            assertEquals((fixture.metadata.refreshed as CatalogResult.Value).value, saved.entry)
            assertEquals(closed, fixture.controller.state.value)
            assertTrue(StreamManagementWrites.pending(fixture.instance.id).isEmpty())
        } finally { release.countDown(); fixture.controller.close() }
    }
}
