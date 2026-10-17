package net.fstab.tachiai.feature.connections

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

// Synthetic public channel metadata through the real controller/store/codec.
// No authorization, provider requests or device-private records are used.
class TwitchScheduleManagementControllerTest {
    private val scheduled = 4_102_444_800_000L
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var writes = 0
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf(); writes++ }
    }
    private class Metadata(override val instanceId: String, var entry: CatalogEntry) : ProviderCatalog {
        override val providerId = ProviderId("twitch")
        override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, lookup = CatalogAccess.AVAILABLE,
            playback = CatalogAccess.NOT_VERIFIED)
        override fun browse(query: CatalogQuery) = CatalogResult.Value(CatalogPage(emptyList()))
        override fun lookup(input: String) = CatalogResult.Value(entry)
        override fun refresh(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        override fun resolve(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
    }
    private inner class Fixture(scope: CoroutineScope, availability: CatalogAvailability) {
        val instance = ProviderInstance("12345678-1234-1234-1234-123456789122", PrototypeService.TWITCH, "Schedule fixture")
        val entry = CatalogEntry(CatalogResource(ProviderId("twitch"), "broadcaster", "123", CatalogIntent.CHANNEL),
            "Scheduled channel fixture", availability, scheduled)
        val metadata = Metadata(instance.id, entry)
        val memory = Memory()
        val store = ConfiguredSourceStore(memory, instance.id, metadata.providerId)
        val controller = StreamManagementController(instance, metadata, store, { emptyList() }, scope, Dispatchers.Unconfined)
        fun reopen() = ConfiguredSourceStore(memory, instance.id, metadata.providerId).read { error("No legacy fallback") }
    }
    private suspend fun idle(controller: StreamManagementController) = withTimeout(5_000) {
        controller.state.first { !it.loading && !it.saving }
    }

    @Test fun previewWritesNothingAndExplicitAddRetainsScheduleWithIndependentlyObservedLiveOrOfflineState() = runBlocking {
        listOf(CatalogAvailability.LIVE, CatalogAvailability.OFFLINE).forEach { availability ->
            val fixture = Fixture(this, availability)
            try {
                fixture.controller.load(); idle(fixture.controller)
                fixture.controller.lookup("fixture-channel")
                val preview = idle(fixture.controller)
                assertEquals(listOf(fixture.entry), preview.entries)
                assertTrue(preview.configured!!.isEmpty())
                assertEquals(0, fixture.memory.writes); assertNull(fixture.memory.bytes)
                fixture.controller.add(preview.entries.single())
                val saved = idle(fixture.controller).configured!!.single()
                assertEquals(1, fixture.memory.writes)
                assertEquals(fixture.entry.resource, saved.entry.resource)
                assertEquals(CatalogIntent.CHANNEL, saved.entry.resource.intent)
                assertEquals(availability, saved.entry.availability)
                assertEquals(scheduled, saved.entry.scheduledStartEpochMs)
                assertEquals(listOf(saved), fixture.reopen())
            } finally { fixture.controller.close() }
        }
    }

    @Test fun aNewDatePreviewAndDuplicateAddCannotRefreshTheSavedSnapshotOrReplaceItsQualityAndIdentity() = runBlocking {
        val fixture = Fixture(this, CatalogAvailability.OFFLINE)
        try {
            fixture.controller.load(); idle(fixture.controller)
            fixture.controller.lookup("fixture-channel"); idle(fixture.controller)
            fixture.controller.add(fixture.entry)
            val saved = idle(fixture.controller).configured!!.single()
            val request = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO,
                NativeQualityCodec.AVC, 2_000_000, 1280, 720))
            val withQuality = fixture.store.saveQuality(saved.id, NativeQualityKind.VIDEO, request) { emptyList() }
            fixture.controller.load(); idle(fixture.controller)
            val bytes = fixture.memory.bytes!!.copyOf(); val writes = fixture.memory.writes
            fixture.metadata.entry = fixture.entry.copy(title = "New schedule preview", availability = CatalogAvailability.LIVE,
                scheduledStartEpochMs = scheduled + 86_400_000L)
            fixture.controller.lookup("fixture-channel")
            val preview = idle(fixture.controller)
            assertEquals(listOf(fixture.metadata.entry), preview.entries)
            assertEquals(withQuality, preview.configured)
            assertEquals(writes, fixture.memory.writes); assertArrayEquals(bytes, fixture.memory.bytes)
            fixture.controller.add(preview.entries.single())
            val duplicate = idle(fixture.controller).configured!!
            assertEquals(withQuality, duplicate)
            assertEquals(saved.id, duplicate.single().id)
            assertEquals(scheduled, duplicate.single().entry.scheduledStartEpochMs)
            assertEquals(CatalogAvailability.OFFLINE, duplicate.single().entry.availability)
            assertEquals(request, duplicate.single().quality.video)
            assertEquals(withQuality, fixture.reopen())
        } finally { fixture.controller.close() }
    }
}
