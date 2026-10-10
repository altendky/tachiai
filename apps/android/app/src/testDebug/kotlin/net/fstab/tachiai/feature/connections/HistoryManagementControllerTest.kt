package net.fstab.tachiai.feature.connections

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

// Synthetic public fixtures only. This exercises the shared consumer and its
// real configured-record codec; neither provider history nor accounts are read.
class HistoryManagementControllerTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var writes = 0
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf(); writes++ }
    }
    private class History(override val instanceId: String, override val providerId: ProviderId) : ProviderCatalog {
        fun video(id: Int, availability: CatalogAvailability = CatalogAvailability.AVAILABLE) = CatalogEntry(
            CatalogResource(providerId, "video", "fixture-$id", CatalogIntent.VIDEO), "History fixture $id", availability)
        val episode = video(1)
        val expired = video(2, CatalogAvailability.EXPIRED)
        val missing = video(3, CatalogAvailability.UNAVAILABLE)
        val series = CatalogEntry(CatalogResource(providerId, "series", "fixture-series", CatalogIntent.COLLECTION),
            "Unavailable fixture series", CatalogAvailability.UNAVAILABLE)
        val inventory = listOf(episode, series, expired, missing)
        var pages = listOf(listOf(episode, series), emptyList(), listOf(episode, expired, missing))
        var access = CatalogAccess.AVAILABLE
        var failure: CatalogFailure? = null
        var failCapabilities = false
        var emptyHistory = false
        val queries = mutableListOf<CatalogQuery>()
        val lookups = mutableListOf<String>()
        var beforeBrowse: (CatalogQuery) -> Unit = {}
        val closed = CompletableDeferred<Unit>()
        override fun capabilities(): CatalogCapabilities {
            check(!failCapabilities) { "Synthetic capability validation failure" }
            return CatalogCapabilities(browse = CatalogAccess.AVAILABLE,
                search = CatalogAccess.AVAILABLE, lookup = CatalogAccess.AVAILABLE, children = CatalogAccess.AVAILABLE,
                collections = listOf(CatalogCollection("history", "History", access)), initialCollectionId = "history")
        }
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
            queries.add(query); beforeBrowse(query)
            failure?.let { return CatalogResult.Failure(it) }
            if (query.parent == series.resource) return CatalogResult.Value(CatalogPage(listOf(episode)))
            if (query.collectionId != "history") return CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
            if (emptyHistory) return CatalogResult.Value(CatalogPage(emptyList()))
            val index = query.cursor?.removePrefix("fixture-page-")?.toIntOrNull() ?: 0
            if (index !in pages.indices) return CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
            return CatalogResult.Value(CatalogPage(pages[index], if (index < pages.lastIndex) "fixture-page-${index + 1}" else null))
        }
        override fun lookup(input: String): CatalogResult<CatalogEntry> {
            lookups.add(input)
            failure?.let { return CatalogResult.Failure(it) }
            return inventory.singleOrNull { it.resource.identity == input }?.let { CatalogResult.Value(it) }
                ?: CatalogResult.Failure(CatalogFailure.NOT_FOUND)
        }
        override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        override fun close() { closed.complete(Unit) }
    }
    private class Fixture(scope: CoroutineScope, service: PrototypeService = PrototypeService.TWITCH,
        io: CoroutineDispatcher = Dispatchers.Unconfined) {
        val instance = defaultProviderInstances().single { it.service == service }
        val history = History(instance.id, ProviderId(service.name.lowercase(java.util.Locale.ROOT)))
        val memory = Memory()
        val store = ConfiguredSourceStore(memory, instance.id, history.providerId)
        val controller = StreamManagementController(instance, history, store, { emptyList() }, scope, io)
    }
    private suspend fun idle(controller: StreamManagementController) = withTimeout(5_000) {
        controller.state.first { !it.loading && !it.saving }
    }

    @Test fun populatedEmptyAndPagedHistoryUseExplicitAddWithExactUnavailableIdentities() = runBlocking {
        PrototypeService.entries.forEach { service ->
            val fixture = Fixture(this, service); val controller = fixture.controller; val history = fixture.history
            controller.load(); var state = idle(controller)
            assertEquals(listOf(history.episode, history.series), state.entries)
            assertTrue(state.configured!!.isEmpty()); assertNull(fixture.memory.bytes)
            controller.more(); state = idle(controller)
            assertEquals(listOf(history.episode, history.series), state.entries)
            assertEquals("fixture-page-2", state.nextCursor)
            controller.more(); state = idle(controller)
            assertEquals(history.inventory, state.entries); assertNull(state.nextCursor)
            assertEquals(0, fixture.memory.writes)
            history.inventory.forEach { controller.add(it); idle(controller) }
            controller.add(history.episode); state = idle(controller)
            assertEquals(history.inventory.map { it.resource }, state.configured!!.map { it.entry.resource })
            assertEquals(history.inventory, fixture.store.read { error("Saved history imports must not fall back") }.map { it.entry })
            controller.children(history.series.resource); state = idle(controller)
            assertEquals(listOf(history.episode), state.entries)
            assertEquals(CatalogIntent.VIDEO, state.entries.single().resource.intent)
            val removed = state.configured!!.first().id
            controller.remove(removed); state = idle(controller)
            assertEquals(history.inventory.drop(1), state.configured!!.map { it.entry })
            assertEquals(4, history.inventory.size)
            history.emptyHistory = true; controller.collection("history"); state = idle(controller)
            assertTrue(state.entries.isEmpty()); assertNull(state.failure)
            assertEquals(history.inventory.drop(1), state.configured!!.map { it.entry })
            controller.close()
        }
    }

    @Test fun accessLossDuringMoreClearsPrivateCacheAndRetryRevalidatesTheSameHistoryCollection() = runBlocking {
        val fixture = Fixture(this); val controller = fixture.controller; val history = fixture.history
        controller.load(); idle(controller); controller.add(history.episode)
        val before = idle(controller); val bytes = fixture.memory.bytes!!.copyOf()
        history.failure = CatalogFailure.ACCESS_REQUIRED
        controller.more(); var state = idle(controller)
        assertTrue(state.entries.isEmpty()); assertNull(state.nextCursor); assertNull(state.capabilities)
        assertEquals(CatalogQuery(collectionId = "history"), state.query)
        assertTrue(state.privacyRevision > before.privacyRevision)
        assertEquals(before.configured, state.configured); assertArrayEquals(bytes, fixture.memory.bytes)
        assertEquals(CatalogFailure.ACCESS_REQUIRED, state.failure!!.reason)
        history.failure = null; controller.retry(); state = idle(controller)
        assertEquals(listOf(history.episode, history.series), state.entries)
        assertTrue(history.queries.all { it.collectionId == "history" })
        assertEquals(before.configured, state.configured)
        controller.close()
    }

    @Test fun lostLookupAccessDropsItsInputInsteadOfReplayingItAfterRevalidation() = runBlocking {
        val fixture = Fixture(this); val controller = fixture.controller
        controller.load(); idle(controller)
        fixture.history.failure = CatalogFailure.ACCESS_REQUIRED
        controller.lookup("unvalidated-fixture-input"); idle(controller)
        fixture.history.failure = null; controller.retry(); idle(controller)
        assertEquals(listOf("unvalidated-fixture-input"), fixture.history.lookups)
        assertEquals("history", fixture.history.queries.last().collectionId)
        assertNull(fixture.memory.bytes)
        controller.close()
    }

    @Test fun failedRevalidationCannotLeaveOldHistoryRowsOrDraftQueryButPreservesConfiguredItems() = runBlocking {
        val fixture = Fixture(this); val controller = fixture.controller; val history = fixture.history
        controller.load(); idle(controller); controller.add(history.episode)
        val before = idle(controller)
        history.failCapabilities = true
        controller.load(); var state = idle(controller)
        assertTrue(state.entries.isEmpty()); assertNull(state.nextCursor); assertNull(state.capabilities)
        assertEquals(CatalogQuery(collectionId = "history"), state.query)
        assertTrue(state.privacyRevision > before.privacyRevision)
        assertEquals(before.configured, state.configured); assertFalse(state.storageFailed)
        assertEquals(CatalogFailure.TEMPORARY, state.failure!!.reason)
        history.failCapabilities = false; controller.retry(); state = idle(controller)
        assertEquals(listOf(history.episode, history.series), state.entries)
        assertTrue(history.queries.all { it.collectionId == "history" })
        controller.close()
    }

    @Test fun invalidatedContinuationClearsOldAccountRowsAndReloadsOnlyTheRequestedCollection() = runBlocking {
        val fixture = Fixture(this); val controller = fixture.controller; val history = fixture.history
        controller.load(); val before = idle(controller)
        history.failure = CatalogFailure.INVALID_INPUT
        controller.more(); var state = idle(controller)
        assertTrue(state.entries.isEmpty()); assertNull(state.nextCursor); assertNull(state.capabilities)
        assertEquals(CatalogQuery(collectionId = "history"), state.query)
        assertTrue(state.privacyRevision > before.privacyRevision)
        history.failure = null; controller.retry(); state = idle(controller)
        assertEquals(listOf(history.episode, history.series), state.entries)
        assertTrue(history.queries.all { it.collectionId == "history" })
        // A malformed fresh query does not invalidate unrelated capabilities.
        controller.search("invalid fixture query"); state = idle(controller)
        assertEquals(CatalogFailure.INVALID_INPUT, state.failure!!.reason)
        assertNotNull(state.capabilities)
        controller.close()
    }

    @Test fun closeClearsImmediatelyAndLateHistoryPageCannotRestoreTheCache() = runBlocking {
        val fixture = Fixture(this, io = Dispatchers.Default); val controller = fixture.controller
        controller.load(); idle(controller); controller.add(fixture.history.episode)
        val before = idle(controller)
        val entered = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        fixture.history.beforeBrowse = { if (it.cursor != null) {
            entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)); returned.complete(Unit)
        } }
        try {
            controller.more(); withTimeout(5_000) { entered.await() }
            controller.close(); val cleared = controller.state.value
            assertTrue(cleared.entries.isEmpty()); assertNull(cleared.nextCursor); assertNull(cleared.capabilities)
            assertEquals(CatalogQuery(), cleared.query); assertEquals(before.configured, cleared.configured)
            assertTrue(cleared.privacyRevision > before.privacyRevision)
            withTimeout(5_000) { fixture.history.closed.await() }
            release.countDown(); withTimeout(5_000) { returned.await() }
            // A closed controller refuses both new actions and old publication.
            controller.load(); controller.collection("history"); controller.lookup("fixture-1")
            assertEquals(cleared, controller.state.value)
            assertEquals(before.configured, fixture.store.read { emptyList() })
        } finally { release.countDown(); controller.close() }
    }

    @Test fun uniqueHistoryResultsAreBoundedAndNewQueriesResetTheLimit() = runBlocking {
        val fixture = Fixture(this); val controller = fixture.controller; val history = fixture.history
        history.pages = (0..5).map { page -> (1..100).map { history.video(page * 100 + it) } }
        controller.load(); idle(controller)
        repeat(4) { controller.more(); idle(controller) }
        var state = controller.state.value
        assertEquals(MAX_DISCOVERY_RESULTS, state.entries.size)
        assertEquals((1..500).map { "fixture-$it" }, state.entries.map { it.resource.identity })
        assertTrue(state.resultsTruncated); assertNull(state.nextCursor)
        val calls = history.queries.size
        controller.more(); assertEquals(calls, history.queries.size)
        assertNull(fixture.memory.bytes)
        history.pages = listOf(listOf(history.missing))
        controller.collection("history"); state = idle(controller)
        assertFalse(state.resultsTruncated); assertEquals(listOf(history.missing), state.entries)
        controller.close()
    }

    @Test fun exactlyCompleteLimitDoesNotClaimWithheldResultsAndOverflowKeepsFirstUniqueEntries() = runBlocking {
        val fixture = Fixture(this); val controller = fixture.controller; val history = fixture.history
        history.pages = (0..4).map { page -> (1..100).map { history.video(page * 100 + it) } }
        controller.load(); idle(controller); repeat(4) { controller.more(); idle(controller) }
        assertEquals(500, controller.state.value.entries.size); assertFalse(controller.state.value.resultsTruncated)
        history.pages = (0..4).map { page -> (1..90).map { history.video(page * 90 + it) } } +
            listOf((451..550).map { history.video(it) })
        controller.collection("history"); idle(controller); repeat(5) { controller.more(); idle(controller) }
        assertEquals((1..500).map { "fixture-$it" }, controller.state.value.entries.map { it.resource.identity })
        assertTrue(controller.state.value.resultsTruncated); assertNull(controller.state.value.nextCursor)
        controller.close()
    }
}
