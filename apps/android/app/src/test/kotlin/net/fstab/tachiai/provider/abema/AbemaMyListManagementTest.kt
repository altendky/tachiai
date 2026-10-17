package net.fstab.tachiai.provider.abema

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.fstab.tachiai.feature.connections.ConfiguredSourceStore
import net.fstab.tachiai.feature.connections.StreamManagementController
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

// Actual parser -> adapter -> shared controller -> private configured codec.
// All account pages are synthetic; no provider/account/network or disk is used.
class AbemaMyListManagementTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var writes = 0
        var fails = false
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { check(!fails); bytes = plaintext.copyOf(); writes++ }
    }
    private class Fixture(scope: CoroutineScope) {
        val instance = ProviderInstance(UUID.randomUUID().toString(), PrototypeService.ABEMA, "ABEMA fixture")
        val memory = Memory()
        val store = ConfiguredSourceStore(memory, instance.id, ProviderId("abema"))
        var access = CatalogAccess.AVAILABLE
        var generation = "a".repeat(64)
        var status = 200
        var empty = false
        var requests = 0
        var closes = 0
        var publicQueries = 0
        var childQueries = 0
        val public = object : ProviderCatalog {
            override val instanceId = instance.id
            override val providerId = ProviderId("abema")
            override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE,
                children = CatalogAccess.NOT_VERIFIED, browseTitle = "Channels")
            override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
                if (query.parent != null) { childQueries++; return CatalogResult.Failure(CatalogFailure.NOT_VERIFIED) }
                publicQueries++; return CatalogResult.Value(CatalogPage(emptyList()))
            }
            override fun lookup(input: String): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
            override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
            override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        }
        val catalog = AbemaAccountCollectionsCatalog(public, object : AbemaCollectionAccountAccess {
            override fun access() = access
            override fun lease(): AbemaCollectionLease {
                val captured = generation
                return AbemaCollectionLease(instance.id, captured, { access == CatalogAccess.AVAILABLE && captured == generation }) { admission ->
                    object : AbemaCollectionTransport {
                        override fun fetchMyList(next: String?): AbemaMyListResponse {
                            check(admission()); requests++
                            return if (status != 200) AbemaMyListResponse(status) else AbemaMyListResponse(200,
                                parseAbemaMyListResponse(if (empty) """{"mylist":[],"dataSet":{}}""" else
                                    if (next == null) FIRST else SECOND))
                        }
                        override fun close() { closes++ }
                    }
                }
            }
        })
        val controller = StreamManagementController(instance, catalog, store, { emptyList() }, scope, Dispatchers.Unconfined)
    }
    private suspend fun idle(controller: StreamManagementController) = withTimeout(5_000) {
        controller.state.first { !it.loading && !it.saving }
    }

    @Test fun browsingNeverWritesExplicitAddDeduplicatesAndRetainsKindsQualityAndLocalRemoval() = runBlocking {
        val f = Fixture(this); val c = f.controller
        c.load(); idle(c); c.collection("my_list"); var state = idle(c)
        assertEquals(listOf("episode", "series", "slot_group"), state.entries.map { it.resource.kind })
        assertEquals(0, f.memory.writes); assertTrue(state.configured!!.isEmpty())
        val first = state.entries.toList()
        c.more(); state = idle(c)
        assertEquals(listOf("episode", "series", "slot_group", "slot", "live_event"), state.entries.map { it.resource.kind })
        assertNull(state.nextCursor); assertEquals(0, f.memory.writes)
        val all = state.entries.toList()
        all.forEach { c.add(it); idle(c) }
        val saved = idle(c).configured!!
        val quality = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC,
            bitrateBps = 1_000_000, width = 640, height = 360))
        f.store.saveQuality(saved.first().id, NativeQualityKind.VIDEO, quality, { emptyList() })
        c.add(first.first()); state = idle(c)
        assertEquals(saved.map { it.id }, state.configured!!.map { it.id })
        assertEquals(quality, state.configured!!.first().quality.video)
        val reopened = ConfiguredSourceStore(f.memory, f.instance.id, ProviderId("abema")).read { error("No defaults") }
        assertEquals(all, reopened.map { it.entry })
        assertEquals(quality, reopened.first().quality.video)
        assertFalse(f.memory.bytes!!.toString(Charsets.UTF_8).contains("private-provider-next"))
        assertTrue(reopened.all { it.entry.availability == CatalogAvailability.UNKNOWN && it.entry.scheduledStartEpochMs == null })
        // Group/series stay exact collections: no automatic playable child.
        assertEquals(CatalogIntent.COLLECTION, reopened[2].entry.resource.intent)
        assertTrue(f.catalog.resolve(reopened[2].entry.resource) is CatalogResult.Failure)
        assertEquals(0, f.childQueries)
        val requestCount = f.requests
        c.remove(reopened.first().id); state = idle(c)
        assertEquals(all.drop(1), state.configured!!.map { it.entry })
        assertEquals(requestCount, f.requests)
        f.empty = true; c.collection("my_list"); state = idle(c)
        assertTrue(state.entries.isEmpty()); assertNull(state.failure)
        assertEquals(all.drop(1), state.configured!!.map { it.entry })
        c.close()
    }

    @Test fun accessLossAndReplacementDuringMoreClearPrivatePagesButKeepAcceptedConfiguredItems() = runBlocking {
        for (replace in listOf(false, true)) {
            val f = Fixture(this); val c = f.controller
            c.load(); idle(c); c.collection("my_list"); val before = idle(c)
            c.add(before.entries.first()); val configured = idle(c).configured!!
            val bytes = f.memory.bytes!!.copyOf()
            if (replace) f.generation = "b".repeat(64) else f.status = 401
            c.more(); var state = idle(c)
            assertEquals(CatalogFailure.ACCESS_REQUIRED, state.failure!!.reason)
            assertTrue(state.entries.isEmpty()); assertNull(state.nextCursor); assertNull(state.capabilities)
            assertEquals(CatalogQuery(collectionId = "my_list"), state.query)
            assertTrue(state.privacyRevision > before.privacyRevision)
            assertEquals(configured, state.configured); assertArrayEquals(bytes, f.memory.bytes)
            f.status = 200; c.retry(); state = idle(c)
            assertEquals(before.entries, state.entries)
            assertEquals(configured, state.configured)
            assertEquals(1, f.publicQueries) // Initial explicit public view only; no guest/All fallback.
            assertEquals(f.requests, f.closes)
            c.close()
        }
    }

    @Test fun failedConfiguredWritePreservesRecordsAndHistoryRemainsUnverified() = runBlocking {
        val f = Fixture(this); val c = f.controller
        c.load(); idle(c); c.collection("my_list"); val before = idle(c)
        f.memory.fails = true; c.add(before.entries.first()); var state = idle(c)
        assertTrue(state.message!!.startsWith("Configured streams could not be saved."))
        assertTrue(state.configured!!.isEmpty()); assertNull(f.memory.bytes)
        assertEquals(0, f.memory.writes)
        f.memory.fails = false; c.close()
        val f2 = Fixture(this); f2.controller.load(); idle(f2.controller)
        f2.controller.collection("history"); state = idle(f2.controller)
        assertEquals(CatalogFailure.NOT_VERIFIED, state.failure!!.reason)
        assertEquals(0, f2.requests); assertNull(f2.memory.bytes)
        f2.controller.close()
    }

    companion object {
        private const val FIRST = """{"mylist":[{"contentId":"episode-1","contentType":"CONTENT_TYPE_PROGRAM"},{"contentId":"series-1","contentType":"CONTENT_TYPE_SERIES"},{"contentId":"group-1","contentType":"CONTENT_TYPE_SLOT_GROUP"}],"dataSet":{"programs":[{"id":"episode-1","title":"Episode"}],"series":[{"id":"series-1","title":"Series"}],"slotGroups":[{"id":"group-1","title":"Group"}]},"paging":{"next":"private-provider-next"}}"""
        private const val SECOND = """{"mylist":[{"contentId":"episode-1","contentType":"CONTENT_TYPE_PROGRAM"},{"contentId":"slot-1","contentType":"CONTENT_TYPE_SLOT"},{"contentId":"event-1","contentType":"CONTENT_TYPE_LIVE_EVENT"}],"dataSet":{"programs":[{"id":"episode-1","title":"Episode"}],"slots":[{"id":"slot-1","title":"Future slot","channelId":"sumo","startAt":9999999999}],"liveEvents":[{"id":"event-1","title":"Event","alias":"not-the-id"}]}}"""
    }
}
