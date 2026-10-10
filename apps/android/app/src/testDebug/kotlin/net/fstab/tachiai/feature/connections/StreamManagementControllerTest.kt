package net.fstab.tachiai.feature.connections

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class StreamManagementControllerTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var failRead = false
        var failWrite = false
        var beforeRead: () -> Unit = {}
        var beforeWrite: () -> Unit = {}
        var writes = 0
        override fun read(): ByteArray? { beforeRead(); check(!failRead); return bytes?.copyOf() }
        override fun write(plaintext: ByteArray) { check(!failWrite); beforeWrite(); bytes = plaintext.copyOf(); writes++ }
    }
    private class Catalog(val instance: ProviderInstance, private val collectionAccess: CatalogAccess = CatalogAccess.AVAILABLE) : ProviderCatalog {
        override val instanceId = instance.id
        override val providerId = ProviderId(instance.service.name.lowercase(java.util.Locale.ROOT))
        val collectionId = if (instance.service == PrototypeService.TWITCH) "following" else "my_list"
        val collectionTitle = if (instance.service == PrototypeService.TWITCH) "Following" else "My List"
        val parent = CatalogResource(providerId, "series", "saved-show", CatalogIntent.COLLECTION)
        val offline = entry("offline-channel", CatalogIntent.CHANNEL, "Offline channel", CatalogAvailability.OFFLINE)
        val episode = entry("never-live-episode", CatalogIntent.VIDEO, "On-demand episode", CatalogAvailability.AVAILABLE)
        val future = entry("upcoming-broadcast", CatalogIntent.BROADCAST, "Upcoming broadcast", CatalogAvailability.UPCOMING)
        val series = CatalogEntry(parent, "Saved show")
        val children = listOf(episode, entry("second-episode", CatalogIntent.VIDEO, "Second episode", CatalogAvailability.EXPIRED))
        val items = listOf(offline, episode, future, series)
        var failure: CatalogFailure? = null
        var throwBrowse = false
        var throwCapabilities = false
        var browseTitle = "All"
        var initialCollectionId: String? = null
        var onBrowse: (CatalogQuery) -> Unit = {}
        var lastQuery: CatalogQuery? = null
        var browses = 0
        var lookupFailure: CatalogFailure? = null
        var lastLookupInput: String? = null
        @Volatile var closed = false
        val closedSignal = CompletableDeferred<Unit>()
        private fun entry(id: String, intent: CatalogIntent, title: String, availability: CatalogAvailability) =
            CatalogEntry(CatalogResource(providerId, if (intent == CatalogIntent.CHANNEL) "channel" else "program", id, intent),
                title, availability, if (availability == CatalogAvailability.UPCOMING) 10_000 else null)
        override fun capabilities(): CatalogCapabilities {
            if (throwCapabilities) throw IllegalStateException("sensitive-account-state")
            return CatalogCapabilities(browse = CatalogAccess.AVAILABLE, search = CatalogAccess.AVAILABLE,
            lookup = CatalogAccess.AVAILABLE, children = CatalogAccess.AVAILABLE,
            collections = listOf(CatalogCollection(collectionId, collectionTitle, collectionAccess),
                CatalogCollection("history", "History", CatalogAccess.UNSUPPORTED)),
            browseTitle = browseTitle, initialCollectionId = initialCollectionId)
        }
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
            browses++; lastQuery = query; onBrowse(query)
            if (throwBrowse) throw IllegalStateException("sensitive-provider-response")
            failure?.let { return CatalogResult.Failure(it, if (it == CatalogFailure.RATE_LIMITED) 30_000 else null) }
            if (query.parent != null && query.parent != parent) return CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
            val matches = (if (query.parent != null) children else items).filter {
                query.search == null || it.title.contains(query.search, true)
            }
            val next = "opaque:${query.collectionId}:${query.parent?.identity}:${query.search}/+page=2"
            return when (query.cursor) {
                null -> CatalogResult.Value(CatalogPage(matches.take(2), next.takeIf { matches.size > 2 }))
                next -> CatalogResult.Value(CatalogPage(matches.drop(2)))
                else -> CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
            }
        }
        override fun lookup(input: String): CatalogResult<CatalogEntry> {
            lastLookupInput = input
            lookupFailure?.let { return CatalogResult.Failure(it) }
            return items.singleOrNull { it.resource.identity == input }
                ?.let { CatalogResult.Value(it) } ?: CatalogResult.Failure(CatalogFailure.NOT_FOUND)
        }
        override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        override fun close() { closed = true; closedSignal.complete(Unit) }
    }
    private fun store(memory: Memory, instance: ProviderInstance) = ConfiguredSourceStore(memory, instance.id,
        ProviderId(instance.service.name.lowercase(java.util.Locale.ROOT)))
    private fun controller(scope: CoroutineScope, catalog: Catalog, memory: Memory,
        io: CoroutineDispatcher = Dispatchers.Unconfined, diagnostics: FailureReporter = FailureReporter.NONE,
        clockMs: () -> Long = System::currentTimeMillis) =
        StreamManagementController(catalog.instance, catalog, store(memory, catalog.instance), { emptyList() }, scope, io, diagnostics,
            clockMs = clockMs)
    private suspend fun idle(controller: StreamManagementController) = withTimeout(5_000) {
        controller.state.first { !it.loading && !it.saving }
    }

    @Test fun initialCollectionIsChosenOnceAndGlobalSearchNeverInheritsCollectionOrParent() = runBlocking {
        defaultProviderInstances().forEach { instance ->
            val catalog = Catalog(instance).apply { browseTitle = "Available streams"; initialCollectionId = collectionId }
            val controller = controller(this, catalog, Memory())
            controller.load(); var state = idle(controller)
            assertEquals(catalog.collectionId, state.query.collectionId)
            assertEquals(catalog.collectionId, catalog.lastQuery!!.collectionId)
            controller.search("Offline"); state = idle(controller)
            assertEquals(CatalogQuery(search = "Offline"), state.query)
            assertEquals(listOf(catalog.offline), state.entries)
            controller.children(catalog.parent); idle(controller)
            controller.search("On-demand"); state = idle(controller)
            assertEquals(CatalogQuery(search = "On-demand"), catalog.lastQuery)
            assertNull(state.query.parent); assertEquals(listOf(catalog.episode), state.entries)
            val calls = catalog.browses
            controller.search("   "); state = idle(controller)
            assertEquals(CatalogFailure.INVALID_INPUT, state.failure!!.reason)
            assertEquals(CatalogQuery(search = "On-demand"), state.query)
            assertEquals(calls, catalog.browses)
            controller.all(); idle(controller); controller.load(); state = idle(controller)
            assertEquals(CatalogQuery(), state.query)
            assertNull(catalog.lastQuery!!.collectionId)
            controller.close()
        }
    }

    @Test fun inaccessibleInitialCollectionKeepsConfiguredItemsAndDoesNotMasqueradeAsEmptySuccess() = runBlocking {
        val instance = defaultProviderInstances().last()
        val catalog = Catalog(instance, CatalogAccess.AUTHORIZATION_REQUIRED).apply {
            browseTitle = "Live channels"; initialCollectionId = collectionId
        }
        val memory = Memory()
        val configured = store(memory, instance).add(catalog.offline) { emptyList() }
        val controller = controller(this, catalog, memory)
        controller.load(); var state = idle(controller)
        assertEquals(configured, state.configured)
        assertEquals(CatalogQuery(collectionId = "following"), state.query)
        assertEquals(CatalogFailure.ACCESS_REQUIRED, state.failure!!.reason)
        assertEquals(0, catalog.browses)
        controller.all(); state = idle(controller)
        assertNull(state.failure); assertEquals(configured, state.configured)
        assertTrue(state.entries.isNotEmpty()); assertEquals(1, catalog.browses)
        controller.close()
    }

    @Test fun failedCapabilitiesReadDoesNotConsumeTheInitialSelection() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().first()).apply {
            initialCollectionId = collectionId; throwCapabilities = true
        }
        val controller = controller(this, catalog, Memory())
        controller.load(); assertEquals(CatalogFailure.TEMPORARY, idle(controller).failure!!.reason)
        catalog.throwCapabilities = false
        controller.retry(); val state = idle(controller)
        assertEquals(catalog.collectionId, state.query.collectionId)
        assertEquals(catalog.collectionId, catalog.lastQuery!!.collectionId)
        controller.close()
    }

    @Test fun lookupRetryKeepsExactInputRatherThanReopeningTheDefaultCollection() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last()).apply { initialCollectionId = collectionId }
        val controller = controller(this, catalog, Memory())
        controller.load(); idle(controller)
        val calls = catalog.browses
        catalog.lookupFailure = CatalogFailure.TEMPORARY
        controller.lookup(catalog.episode.resource.identity)
        assertEquals(CatalogFailure.TEMPORARY, idle(controller).failure!!.reason)
        catalog.lookupFailure = null
        controller.retry(); val state = idle(controller)
        assertEquals(catalog.episode.resource.identity, catalog.lastLookupInput)
        assertEquals(listOf(catalog.episode), state.entries)
        assertEquals(calls, catalog.browses)
        controller.close()
    }

    @Test fun twoProvidersUseOneControllerForSearchPagesCollectionsChildrenAndExactLookup() = runBlocking {
        defaultProviderInstances().forEach { instance ->
            val memory = Memory(); val catalog = Catalog(instance); val controller = controller(this, catalog, memory)
            controller.load(); var state = idle(controller)
            assertEquals(2, state.entries.size); assertNotNull(state.nextCursor)
            assertEquals(CatalogAvailability.OFFLINE, state.entries.first().availability)
            controller.more(); state = idle(controller)
            assertEquals(catalog.items, state.entries); assertNull(state.nextCursor)
            assertEquals(CatalogAvailability.UPCOMING, state.entries[2].availability)
            controller.search("On-demand"); state = idle(controller)
            assertEquals(listOf(catalog.episode), state.entries)
            controller.collection(catalog.collectionId); state = idle(controller)
            assertEquals(catalog.collectionId, state.query.collectionId)
            assertEquals(2, state.entries.size)
            controller.children(catalog.parent); state = idle(controller)
            assertEquals(catalog.children, state.entries)
            assertEquals(catalog.parent, state.query.parent); assertNull(state.query.collectionId)
            assertTrue(state.entries.all { it.resource.intent == CatalogIntent.VIDEO })
            controller.lookup(catalog.future.resource.identity); state = idle(controller)
            assertEquals(listOf(catalog.future), state.entries)
            controller.lookup("unknown-resource"); state = idle(controller)
            assertEquals(CatalogFailure.NOT_FOUND, state.failure!!.reason)
            controller.close()
        }
    }

    @Test fun allAndCollectionAddsDeduplicateAndLocalChangesPersistWithoutChangingProviderItems() = runBlocking {
        val instance = defaultProviderInstances().last(); val catalog = Catalog(instance); val memory = Memory()
        val controller = controller(this, catalog, memory)
        controller.load(); idle(controller)
        controller.add(catalog.offline); var state = idle(controller)
        val saved = state.configured!!.single()
        controller.collection(catalog.collectionId); idle(controller)
        controller.add(catalog.offline); state = idle(controller)
        assertEquals(listOf(saved), state.configured)
        controller.add(catalog.future); state = idle(controller)
        val future = state.configured!!.last()
        controller.move(future.id, -1); state = idle(controller)
        assertEquals(listOf(future.id, saved.id), state.configured!!.map { it.id })
        controller.remove(saved.id); idle(controller)
        controller.remove(future.id); state = idle(controller)
        assertTrue(state.configured!!.isEmpty())
        assertTrue(store(memory, instance).read { error("Committed empty list must remain empty") }.isEmpty())
        assertEquals(4, catalog.items.size)
        controller.close()
    }

    @Test fun capabilityFailuresAreDifferentFromEmptyListsAndPreserveSavedItems() = runBlocking {
        listOf(CatalogAccess.AUTHORIZATION_REQUIRED, CatalogAccess.RECONNECT_REQUIRED,
            CatalogAccess.SCOPE_REQUIRED, CatalogAccess.UNSUPPORTED, CatalogAccess.NOT_VERIFIED).forEach { access ->
            val instance = defaultProviderInstances().first(); val catalog = Catalog(instance, access)
            val controller = controller(this, catalog, Memory())
            controller.load(); idle(controller); controller.add(catalog.episode); val saved = idle(controller).configured
            val calls = catalog.browses
            controller.collection(catalog.collectionId); val state = idle(controller)
            assertEquals(access, state.capabilities!!.collections.first().access)
            assertNotNull(state.failure); assertEquals(saved, state.configured)
            assertEquals(calls, catalog.browses)
            controller.collection("history"); assertEquals(CatalogFailure.UNSUPPORTED, idle(controller).failure!!.reason)
            controller.close()
        }
    }

    @Test fun temporaryRateLimitAndThrownErrorsAreSafeAndRetryDoesNotEraseConfiguration() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last()); val observations = mutableListOf<FailureObservation>()
        val controller = controller(this, catalog, Memory(), diagnostics = FailureReporter({ observations.add(it) }))
        controller.load(); idle(controller); controller.add(catalog.episode); val saved = idle(controller).configured
        catalog.failure = CatalogFailure.RATE_LIMITED
        controller.all(); var state = idle(controller)
        assertEquals(30_000L, state.failure!!.retryAtEpochMs); assertEquals(saved, state.configured)
        catalog.failure = null; catalog.throwBrowse = true
        controller.retry(); state = idle(controller)
        assertEquals(CatalogFailure.TEMPORARY, state.failure!!.reason)
        assertEquals(FailureStage.CATALOG_LOAD, observations.single().stage)
        assertFalse(state.toString().contains("sensitive-provider-response"))
        assertFalse(observations.toString().contains("sensitive-provider-response"))
        catalog.throwBrowse = false; controller.retry(); state = idle(controller)
        assertNull(state.failure); assertEquals(saved, state.configured)
        controller.close()
    }

    @Test fun failedStorageReadBlocksWritesAndExplicitRetryReadsWithoutDefaultReplacement() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last()); val memory = Memory().apply { failRead = true }
        val observations = mutableListOf<FailureObservation>()
        val controller = controller(this, catalog, memory, diagnostics = FailureReporter({ observations.add(it) }))
        controller.load(); var state = idle(controller)
        assertTrue(state.storageFailed); assertNull(state.configured)
        controller.add(catalog.offline); assertEquals(0, memory.writes)
        assertEquals(FailureStage.CONFIGURED_SOURCES_READ, observations.single().stage)
        memory.failRead = false; controller.retry(); state = idle(controller)
        assertFalse(state.storageFailed); assertEquals(emptyList<ConfiguredSource>(), state.configured)
        assertNull(memory.bytes)
        controller.close()
    }

    @Test fun failedCapabilitiesRetryReloadsTheAdapterAndStorageFailuresAfterSuccessStillBlockWrites() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last()).apply { throwCapabilities = true }
        val memory = Memory(); val controller = controller(this, catalog, memory)
        controller.load(); var state = idle(controller)
        assertNull(state.capabilities); assertFalse(state.storageFailed)
        assertEquals(CatalogFailure.TEMPORARY, state.failure!!.reason)
        catalog.throwCapabilities = false; controller.retry(); state = idle(controller)
        assertNotNull(state.capabilities); assertNull(state.failure); assertEquals(2, state.entries.size)
        controller.add(catalog.offline); val saved = idle(controller).configured
        memory.failRead = true; controller.load(); state = idle(controller)
        assertTrue(state.storageFailed); assertEquals(saved, state.configured)
        val writes = memory.writes; controller.add(catalog.future); assertEquals(writes, memory.writes)
        memory.failRead = false; controller.retry(); state = idle(controller)
        assertFalse(state.storageFailed); assertEquals(saved, state.configured)
        controller.close()
    }

    @Test fun failedMutationsPreservePriorListAndReportBoundedDiagnostics() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last()); val memory = Memory()
        val observations = mutableListOf<FailureObservation>()
        val controller = controller(this, catalog, memory, diagnostics = FailureReporter({ observations.add(it) }))
        controller.load(); idle(controller); controller.add(catalog.offline); val saved = idle(controller).configured
        val bytes = memory.bytes!!.copyOf(); memory.failWrite = true
        controller.add(catalog.future); var state = idle(controller)
        assertEquals(saved, state.configured); assertNotNull(state.message); assertFalse(state.saving)
        assertArrayEquals(bytes, memory.bytes)
        assertEquals(FailureStage.CONFIGURED_SOURCES_WRITE, observations.single().stage)
        memory.failWrite = false; controller.add(catalog.future); state = idle(controller)
        assertEquals(2, state.configured!!.size)
        controller.close()
    }

    @Test fun twoInstancesKeepDistinctLocalListsAndWrongProviderResultsCannotBeAdded() = runBlocking {
        val first = defaultProviderInstances().last()
        val second = ProviderInstance("12345678-1234-1234-1234-123456789abc", first.service, "Second account")
        val firstCatalog = Catalog(first); val secondCatalog = Catalog(second)
        val firstMemory = Memory(); val secondMemory = Memory()
        val firstController = controller(this, firstCatalog, firstMemory)
        val secondController = controller(this, secondCatalog, secondMemory)
        firstController.load(); secondController.load(); idle(firstController); idle(secondController)
        firstController.add(firstCatalog.offline); val saved = idle(firstController).configured!!.single()
        assertTrue(idle(secondController).configured!!.isEmpty()); assertNull(secondMemory.bytes)
        secondController.add(secondCatalog.offline); val secondSaved = idle(secondController).configured!!.single()
        assertNotEquals(saved.id, secondSaved.id); assertNotEquals(saved.instanceId, secondSaved.instanceId)
        val wrong = firstCatalog.offline.copy(resource = firstCatalog.offline.resource.copy(providerId = ProviderId("abema")))
        firstController.add(wrong)
        assertEquals(CatalogFailure.INVALID_INPUT, firstController.state.value.failure!!.reason)
        assertEquals(listOf(saved), firstController.state.value.configured)
        firstController.close(); secondController.close()
    }

    @Test fun lateSearchResultsCannotReplaceAChangedQuery() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last()); val controller = controller(this, catalog, Memory(), Dispatchers.Default)
        controller.load(); idle(controller)
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        catalog.onBrowse = { query -> if (query.search == "Offline") {
            entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS))
        } }
        try {
            controller.search("Offline"); withTimeout(5_000) { entered.await() }
            controller.search("On-demand"); val state = idle(controller)
            assertEquals(listOf(catalog.episode), state.entries)
            release.countDown()
            assertEquals("On-demand", controller.state.value.query.search)
            assertEquals(listOf(catalog.episode), controller.state.value.entries)
        } finally { release.countDown(); controller.close() }
    }

    @Test fun acceptedWriteFinishesAfterCloseWhileLatePublicationAndCatalogUseAreStopped() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last()); val memory = Memory()
        val controller = controller(this, catalog, memory, Dispatchers.Default)
        controller.load(); idle(controller)
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        memory.beforeWrite = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            controller.add(catalog.offline); withTimeout(5_000) { entered.await() }
            val prior = controller.state.value
            controller.close()
            val cleared = controller.state.value
            assertEquals(prior.configured, cleared.configured)
            assertTrue(cleared.entries.isEmpty()); assertNull(cleared.nextCursor); assertNull(cleared.capabilities)
            assertEquals(CatalogQuery(), cleared.query); assertTrue(cleared.privacyRevision > prior.privacyRevision)
            assertTrue(StreamManagementWrites.pending(catalog.instance.id).isNotEmpty())
            withTimeout(5_000) { catalog.closedSignal.await() }
            release.countDown(); withTimeout(5_000) { controller.pendingMutation!!.join() }
            assertEquals(cleared, controller.state.value)
            assertEquals(catalog.offline, store(memory, catalog.instance).read { emptyList() }.single().entry)
            controller.all(); controller.add(catalog.future)
            assertEquals(cleared, controller.state.value)
            assertTrue(catalog.closed)
            assertTrue(StreamManagementWrites.pending(catalog.instance.id).isEmpty())
        } finally { release.countDown(); controller.close() }
    }

    @Test fun recreatedControllerReadsAfterAPreviouslyAcceptedWriteEvenWithNoOldControllerReference() = runBlocking {
        val instance = defaultProviderInstances().last(); val memory = Memory()
        val first = controller(this, Catalog(instance), memory, Dispatchers.Default)
        first.load(); idle(first)
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        memory.beforeWrite = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        val nextCatalog = Catalog(instance)
        val next = controller(this, nextCatalog, memory, Dispatchers.Default)
        try {
            first.add(nextCatalog.offline); withTimeout(5_000) { entered.await() }
            first.close(); next.load()
            assertNull(withTimeoutOrNull(100) { next.state.first { it.configured != null } })
            release.countDown(); val state = idle(next)
            assertEquals(nextCatalog.offline, state.configured!!.single().entry)
            assertFalse(state.storageFailed)
        } finally { release.countDown(); first.close(); next.close() }
    }

    @Test fun staleMutationCallbacksDuringReloadCannotRaceAnOlderReadSnapshot() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last()); val memory = Memory()
        val controller = controller(this, catalog, memory, Dispatchers.Default)
        controller.load(); idle(controller)
        controller.add(catalog.offline); val saved = idle(controller).configured!!
        withTimeout(5_000) { controller.pendingMutation!!.join() }
        val writes = memory.writes
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        memory.beforeRead = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            controller.load(); withTimeout(5_000) { entered.await() }
            controller.add(catalog.future); controller.remove(saved.single().id)
            assertFalse(controller.state.value.saving)
            assertTrue(StreamManagementWrites.pending(catalog.instance.id).isEmpty())
            release.countDown(); val state = idle(controller)
            assertEquals(saved, state.configured); assertEquals(writes, memory.writes)
        } finally { release.countDown(); controller.close() }
    }

    @Test fun retryWaitsForTheProviderDeadlineThenAllowsANewRequest() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last()); var now = 10_000L
        val controller = controller(this, catalog, Memory(), clockMs = { now })
        controller.load(); idle(controller)
        catalog.failure = CatalogFailure.RATE_LIMITED
        controller.all(); val limited = idle(controller)
        assertEquals(30_000L, limited.failure!!.retryAtEpochMs)
        val requests = catalog.browses
        catalog.failure = null
        controller.retry(); assertEquals(requests, catalog.browses)
        assertEquals(limited, controller.state.value)
        now = 29_999; controller.retry(); assertEquals(requests, catalog.browses)
        now = 30_000; controller.retry(); val state = idle(controller)
        assertEquals(requests + 1, catalog.browses); assertNull(state.failure)
        controller.close()
    }
}
