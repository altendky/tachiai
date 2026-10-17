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
        var lookupAccess = CatalogAccess.AVAILABLE
        var historyAccess = CatalogAccess.UNSUPPORTED
        var browseTitle = "All"
        var initialCollectionId: String? = null
        var childrenAccess = CatalogAccess.AVAILABLE
        var seriesAccess = CatalogAccess.AVAILABLE
        var onBrowse: (CatalogQuery) -> Unit = {}
        var lastQuery: CatalogQuery? = null
        var browses = 0
        var lookupFailure: CatalogFailure? = null
        var lastLookupInput: String? = null
        var onLookup: () -> Unit = {}
        var lookupEntry: CatalogEntry? = null
        var lookupCalls = 0
        var expireContinuations = false
        private var continuation: String? = null
        private var cursorSerial = 0
        @Volatile var closed = false
        val closedSignal = CompletableDeferred<Unit>()
        private fun entry(id: String, intent: CatalogIntent, title: String, availability: CatalogAvailability) =
            CatalogEntry(CatalogResource(providerId, if (intent == CatalogIntent.CHANNEL) "channel" else "program", id, intent),
                title, availability, if (availability == CatalogAvailability.UPCOMING) 10_000 else null)
        override fun capabilities(): CatalogCapabilities {
            if (throwCapabilities) throw IllegalStateException("sensitive-account-state")
            return CatalogCapabilities(browse = CatalogAccess.AVAILABLE, search = CatalogAccess.AVAILABLE,
            lookup = lookupAccess, children = childrenAccess,
            childCapabilities = listOf(CatalogChildrenCapability("series", CatalogIntent.COLLECTION, seriesAccess,
                "Browse episodes", "Episodes from")),
            collections = listOf(CatalogCollection(collectionId, collectionTitle, collectionAccess),
                CatalogCollection("history", "History", historyAccess)),
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
            if (expireContinuations && query.cursor != null && query.cursor != continuation)
                return CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
            val next = "opaque:${query.collectionId}:${query.parent?.identity}:${query.search}/+page=2" +
                if (expireContinuations) ":${++cursorSerial}" else ""
            val result = when {
                query.cursor == null -> CatalogResult.Value(CatalogPage(matches.take(2), next.takeIf { matches.size > 2 }))
                query.cursor == next || expireContinuations && query.cursor == continuation -> CatalogResult.Value(CatalogPage(matches.drop(2)))
                else -> CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
            }
            if (expireContinuations) continuation = (result as? CatalogResult.Value)?.value?.nextCursor
            return result
        }
        override fun lookup(input: String): CatalogResult<CatalogEntry> {
            lookupCalls++; onLookup()
            if (expireContinuations) continuation = null
            lastLookupInput = input
            lookupFailure?.let { return CatalogResult.Failure(it) }
            lookupEntry?.let { return CatalogResult.Value(it) }
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
    private suspend fun linkIdle(controller: StreamManagementController) = withTimeout(5_000) {
        controller.state.first { !it.loading && !it.saving && it.link?.loading != true }
    }

    @Test fun childRequestsRequireDeclaredKindIntentAndBothCurrentAccessGates() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().first())
        val memory = Memory()
        store(memory, catalog.instance).add(catalog.series) { emptyList() }
        val controller = controller(this, catalog, memory)
        controller.load(); idle(controller)
        for (resource in listOf(catalog.parent.copy(kind = "unknown"), catalog.episode.resource,
            catalog.parent.copy(intent = CatalogIntent.CHANNEL))) {
            val calls = catalog.browses
            controller.children(resource)
            assertEquals(CatalogFailure.UNSUPPORTED, idle(controller).failure!!.reason)
            assertEquals(calls, catalog.browses)
        }
        catalog.seriesAccess = CatalogAccess.NOT_VERIFIED
        controller.load(); idle(controller)
        var calls = catalog.browses
        controller.children(catalog.parent)
        assertEquals(CatalogFailure.NOT_VERIFIED, idle(controller).failure!!.reason)
        assertEquals(calls, catalog.browses)
        catalog.seriesAccess = CatalogAccess.AVAILABLE; catalog.childrenAccess = CatalogAccess.RECONNECT_REQUIRED
        controller.load(); idle(controller); calls = catalog.browses
        controller.children(catalog.parent)
        assertEquals(CatalogFailure.ACCESS_REQUIRED, idle(controller).failure!!.reason)
        assertEquals(calls, catalog.browses)
        catalog.childrenAccess = CatalogAccess.AVAILABLE
        controller.load(); idle(controller); controller.children(catalog.parent)
        val child = idle(controller)
        assertNull(child.failure); assertEquals("Episodes from Saved show", child.childHeading)
        assertEquals(catalog.children, child.entries)
        controller.close()
    }

    @Test fun backRestoresLoadedQueryCursorAndContextWhileKeepingChildAdds() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last())
        val memory = Memory()
        store(memory, catalog.instance).add(catalog.series) { emptyList() }
        val controller = controller(this, catalog, memory)
        controller.load(); idle(controller)
        controller.collection(catalog.collectionId); val parent = idle(controller)
        controller.children(catalog.parent); var child = idle(controller)
        assertEquals("Episodes from Saved show", child.childHeading)
        assertEquals("Back to Following", child.navigationBackLabel)
        assertTrue(child.retainedDiscoveryContextIds.contains(parent.discoveryContextId))
        assertNotEquals(parent.discoveryContextId, child.discoveryContextId)
        controller.add(catalog.episode); child = idle(controller)
        assertTrue(controller.back())
        val restored = idle(controller)
        assertEquals(parent.query, restored.query)
        assertEquals(parent.entries, restored.entries)
        assertEquals(parent.nextCursor, restored.nextCursor)
        assertEquals(parent.discoveryContextId, restored.discoveryContextId)
        assertEquals(child.configured, restored.configured)
        assertEquals(parent.privacyRevision, restored.privacyRevision)
        assertNull(restored.childHeading); assertNull(restored.navigationBackLabel)
        assertFalse(restored.retainedDiscoveryContextIds.contains(restored.discoveryContextId))
        val calls = catalog.browses
        assertFalse(controller.back()); assertEquals(calls, catalog.browses)
        controller.more(); assertEquals(catalog.items, idle(controller).entries)
        assertEquals(parent.query.copy(cursor = parent.nextCursor), catalog.lastQuery)
        controller.close()
    }

    @Test fun backDuringChildLoadRejectsLateSuccessAndAccessFailure() = runBlocking {
        for (lateFailure in listOf(null, CatalogFailure.ACCESS_REQUIRED)) {
            val catalog = Catalog(defaultProviderInstances().last())
            val controller = controller(this, catalog, Memory(), io = Dispatchers.IO)
            controller.load(); val parent = idle(controller)
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            catalog.onBrowse = { if (it.parent != null) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            controller.children(catalog.parent)
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val pending = coroutineContext[Job]!!.children.toList()
            assertTrue(controller.state.value.loading)
            assertTrue(controller.back())
            assertEquals(parent.entries, controller.state.value.entries)
            assertFalse(controller.state.value.loading)
            catalog.failure = lateFailure
            release.countDown(); pending.forEach { it.join() }
            val restored = controller.state.value
            assertEquals(parent.query, restored.query); assertEquals(parent.entries, restored.entries)
            assertEquals(parent.discoveryContextId, restored.discoveryContextId)
            assertNull(restored.failure); assertNull(restored.navigationBackLabel)
            controller.close()
        }
    }

    @Test fun backDuringAcceptedChildWriteKeepsSavingAndPublishesCommittedItems() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last()); val memory = Memory()
        val controller = controller(this, catalog, memory, io = Dispatchers.IO)
        controller.load(); val parent = idle(controller)
        controller.children(catalog.parent); idle(controller)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        memory.beforeWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        controller.add(catalog.episode)
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
        assertTrue(controller.back())
        assertTrue(controller.state.value.saving)
        assertEquals(parent.entries, controller.state.value.entries)
        release.countDown()
        val restored = idle(controller)
        assertEquals(catalog.episode.resource, restored.configured!!.single().entry.resource)
        assertEquals(parent.entries, restored.entries)
        controller.close()
    }

    @Test fun observedAccessLossAndRootTransitionsDiscardRetainedPrivateDiscovery() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last()); val controller = controller(this, catalog, Memory())
        controller.load(); idle(controller)
        controller.children(catalog.parent); val child = idle(controller)
        catalog.failure = CatalogFailure.ACCESS_REQUIRED
        controller.more() // No child continuation; use a new child request to observe access loss.
        controller.children(catalog.parent); var state = idle(controller)
        assertEquals(CatalogFailure.ACCESS_REQUIRED, state.failure!!.reason)
        assertTrue(state.privacyRevision > child.privacyRevision)
        assertTrue(state.entries.isEmpty()); assertTrue(state.retainedDiscoveryContextIds.isEmpty())
        assertFalse(controller.back())
        catalog.failure = null; controller.load(); idle(controller)
        controller.children(catalog.parent); idle(controller)
        controller.lookup(catalog.episode.resource.identity); state = idle(controller)
        assertNull(state.query.parent); assertNull(state.childHeading)
        assertFalse(controller.back()); assertTrue(state.retainedDiscoveryContextIds.isEmpty())
        controller.children(catalog.parent); idle(controller)
        assertTrue(controller.preview(catalog.offline)); assertFalse(controller.back())
        assertNull(controller.state.value.navigationBackLabel)
        controller.children(catalog.parent); idle(controller)
        controller.load(); state = idle(controller)
        assertFalse(controller.back()); assertTrue(state.retainedDiscoveryContextIds.isEmpty())
        controller.close()
        assertTrue(controller.state.value.retainedDiscoveryContextIds.isEmpty())
        assertNull(controller.state.value.childHeading)
    }

    @Test fun parentRetentionIsBoundedWithoutDroppingTheExistingReturnPath() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last()); val controller = controller(this, catalog, Memory())
        controller.load(); idle(controller)
        repeat(MAX_DISCOVERY_PARENTS) { controller.children(catalog.parent); idle(controller) }
        val before = controller.state.value
        val calls = catalog.browses
        controller.children(catalog.parent)
        assertEquals(CatalogFailure.INVALID_INPUT, controller.state.value.failure!!.reason)
        assertEquals(before.discoveryContextId, controller.state.value.discoveryContextId)
        assertEquals(MAX_DISCOVERY_PARENTS, controller.state.value.retainedDiscoveryContextIds.size)
        assertEquals(calls, catalog.browses)
        repeat(MAX_DISCOVERY_PARENTS) { assertTrue(controller.back()) }
        assertFalse(controller.back())
        controller.close()
    }

    @Test fun unverifiedHistorySkipsBrowseRetainsConfiguredEntriesAndCanReturnToConnectedCollections() = runBlocking {
        defaultProviderInstances().forEach { instance ->
            val catalog = Catalog(instance).apply { historyAccess = CatalogAccess.NOT_VERIFIED }
            val memory = Memory()
            val saved = store(memory, instance).add(catalog.offline) { emptyList() }
            val controller = controller(this, catalog, memory)
            try {
                controller.load(); val initial = idle(controller)
                assertTrue(initial.entries.isNotEmpty())
                val calls = catalog.browses
                val writes = memory.writes
                controller.collection("history"); val history = idle(controller)
                assertEquals(CatalogQuery(collectionId = "history"), history.query)
                assertEquals(CatalogFailure.NOT_VERIFIED, history.failure!!.reason)
                assertTrue(history.entries.isEmpty()); assertNull(history.nextCursor)
                assertEquals(initial.capabilities, history.capabilities)
                assertEquals(saved, history.configured)
                assertEquals(calls, catalog.browses); assertEquals(writes, memory.writes)
                controller.collection(catalog.collectionId); val connected = idle(controller)
                assertEquals(catalog.collectionId, connected.query.collectionId)
                assertNull(connected.failure); assertTrue(connected.entries.isNotEmpty())
                assertEquals(saved, connected.configured); assertEquals(calls + 1, catalog.browses)
                assertEquals(writes, memory.writes)
            } finally { controller.close() }
        }
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
            controller.search("On-demand")
            release.countDown()
            val state = idle(controller)
            assertEquals(listOf(catalog.episode), state.entries)
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

    @Test fun normalizedPublicPreviewClearsQueryAndPaginationWithoutLookupOrSavingUntilExplicitAdd() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().first())
        val memory = Memory()
        val controller = controller(this, catalog, memory)
        val entry = CatalogEntry(CatalogResource(catalog.providerId, "episode", "never-started", CatalogIntent.VIDEO),
            "Unstarted public episode", CatalogAvailability.UNKNOWN)
        assertFalse(controller.preview(entry))
        controller.load(); idle(controller)
        assertNotNull(controller.state.value.nextCursor)
        controller.children(catalog.parent); idle(controller)
        assertNotNull(controller.state.value.query.parent)
        val calls = catalog.browses
        assertTrue(controller.preview(entry))
        val preview = controller.state.value
        assertEquals(listOf(entry), preview.entries)
        assertEquals(CatalogQuery(), preview.query)
        assertNull(preview.nextCursor)
        assertEquals(calls, catalog.browses)
        assertNull(catalog.lastLookupInput)
        assertEquals(0, memory.writes)
        assertNull(memory.bytes)
        assertTrue(preview.configured!!.isEmpty())
        controller.add(entry); val saved = idle(controller).configured!!
        assertEquals(entry, saved.single().entry)
        assertEquals(1, memory.writes)
        controller.add(entry); idle(controller)
        assertEquals(saved, store(memory, catalog.instance).read { error("No legacy fallback") })
        controller.close()
        assertFalse(controller.preview(entry))
    }

    @Test fun tabsRestoreTheirRootRowsPaginationAndScrollIdentityWithoutWriting() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last()).apply { historyAccess = CatalogAccess.AVAILABLE }
        val memory = Memory(); val controller = controller(this, catalog, memory)
        controller.load(); val all = idle(controller)
        assertEquals(DISCOVERY_ALL_TAB, all.selectedTabId)
        controller.collection(catalog.collectionId); idle(controller)
        controller.more(); val following = idle(controller)
        controller.children(catalog.parent); idle(controller)
        controller.collection("history"); val history = idle(controller)
        assertFalse(controller.back())
        val calls = catalog.browses
        controller.collection(catalog.collectionId); var restored = idle(controller)
        assertEquals(following.entries, restored.entries)
        assertEquals(following.nextCursor, restored.nextCursor)
        assertEquals(following.discoveryContextId, restored.discoveryContextId)
        assertNull(restored.childHeading); assertNull(restored.navigationBackLabel)
        assertEquals(calls, catalog.browses)
        controller.all(); restored = idle(controller)
        assertEquals(all.discoveryContextId, restored.discoveryContextId)
        assertEquals(all.entries, restored.entries); assertEquals(all.nextCursor, restored.nextCursor)
        controller.search("Offline"); assertNull(idle(controller).selectedTabId)
        controller.collection("history"); restored = idle(controller)
        assertEquals(history.discoveryContextId, restored.discoveryContextId)
        assertEquals(history.entries, restored.entries)
        assertEquals(0, memory.writes)
        controller.close()
    }

    @Test fun restoredTabsBackAndDismissedLookupRebuildProviderExpiredContinuations() = runBlocking {
        for (operation in listOf("tab", "child", "lookup")) {
            val catalog = Catalog(defaultProviderInstances().last()).apply { expireContinuations = true }
            val controller = controller(this, catalog, Memory())
            controller.load(); val initial = idle(controller)
            when (operation) {
                "tab" -> { controller.collection(catalog.collectionId); idle(controller); controller.all() }
                "child" -> { controller.children(catalog.parent); idle(controller); assertTrue(controller.back()) }
                else -> { controller.openLink(); controller.resolveLink(catalog.offline.resource.identity); linkIdle(controller); controller.dismissLink() }
            }
            assertEquals(initial.entries, controller.state.value.entries)
            assertEquals(initial.discoveryContextId, controller.state.value.discoveryContextId)
            controller.more(); val restored = idle(controller)
            assertEquals(catalog.items, restored.entries)
            assertNull(restored.nextCursor); assertNull(restored.failure)
            assertEquals(initial.discoveryContextId, restored.discoveryContextId)
            controller.close()
        }
    }

    @Test fun tabSwitchCancelsLatePrivateResultAndObservedAccessLossInvalidatesEveryRoot() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last()).apply { historyAccess = CatalogAccess.AVAILABLE }
        val controller = controller(this, catalog, Memory(), Dispatchers.Default)
        controller.load(); val all = idle(controller)
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        catalog.onBrowse = { if (it.collectionId == catalog.collectionId) {
            entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS))
        } }
        try {
            controller.collection(catalog.collectionId); withTimeout(5_000) { entered.await() }
            val pending = coroutineContext[Job]!!.children.toList()
            controller.all()
            assertEquals(all.entries, controller.state.value.entries)
            catalog.failure = CatalogFailure.ACCESS_REQUIRED
            release.countDown(); pending.forEach { it.join() }
            assertNull(controller.state.value.failure)
            assertEquals(all.discoveryContextId, controller.state.value.discoveryContextId)
            catalog.onBrowse = {}
            controller.collection("history"); val lost = idle(controller)
            assertEquals(CatalogFailure.ACCESS_REQUIRED, lost.failure!!.reason)
            assertTrue(lost.entries.isEmpty()); assertTrue(lost.retainedDiscoveryContextIds.isEmpty())
            assertTrue(lost.privacyRevision > all.privacyRevision)
            catalog.failure = null
            controller.all(); val afterLoss = idle(controller)
            assertNotEquals(all.discoveryContextId, afterLoss.discoveryContextId)
            controller.load(); val afterLoad = idle(controller)
            assertNotEquals(afterLoss.discoveryContextId, afterLoad.discoveryContextId)
            assertTrue(afterLoad.retainedDiscoveryContextIds.isEmpty())
        } finally { release.countDown(); controller.close() }
        assertNull(controller.state.value.selectedTabId)
        assertTrue(controller.state.value.retainedDiscoveryContextIds.isEmpty())
    }

    @Test fun failedContinuationRebuildRetainsTheCachedRowsAndCanBeRetriedSafely() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last()).apply { expireContinuations = true }
        val controller = controller(this, catalog, Memory())
        controller.load(); val root = idle(controller)
        controller.collection(catalog.collectionId); idle(controller); controller.all(); idle(controller)
        catalog.failure = CatalogFailure.TEMPORARY
        controller.more(); val failed = idle(controller)
        assertEquals(CatalogFailure.TEMPORARY, failed.failure!!.reason)
        assertEquals(root.entries, failed.entries); assertEquals(root.discoveryContextId, failed.discoveryContextId)
        assertEquals(root.nextCursor, failed.nextCursor)
        catalog.failure = null
        controller.retry(); val recovered = idle(controller)
        assertEquals(catalog.items, recovered.entries); assertNull(recovered.failure); assertNull(recovered.nextCursor)
        controller.close()
    }

    @Test fun reloadFromChildrenReloadsTheSelectedTabOrGlobalSearchRoot() = runBlocking {
        for (searchRoot in listOf(false, true)) {
            val catalog = Catalog(defaultProviderInstances().last()).apply { initialCollectionId = collectionId }
            val controller = controller(this, catalog, Memory())
            controller.load(); idle(controller)
            if (searchRoot) { controller.search("Offline"); idle(controller) }
            val root = controller.state.value
            controller.children(catalog.parent); idle(controller)
            controller.load(); val restored = idle(controller)
            assertEquals(root.query.copy(cursor = null), restored.query)
            assertEquals(root.selectedTabId, restored.selectedTabId)
            assertEquals(root.entries, restored.entries)
            assertNotEquals(root.discoveryContextId, restored.discoveryContextId)
            assertTrue(restored.privacyRevision > root.privacyRevision)
            assertNull(restored.childHeading); assertNull(restored.navigationBackLabel)
            assertTrue(restored.retainedDiscoveryContextIds.isEmpty()); assertFalse(controller.back())
            controller.close()
        }
    }

    @Test fun unavailableTabRetentionIsBoundedAndReloadDiscardsEverySnapshot() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last())
        val controller = controller(this, catalog, Memory())
        controller.load(); idle(controller)
        repeat(MAX_DISCOVERY_TABS + 5) { controller.collection("unavailable-$it"); idle(controller) }
        assertTrue(controller.state.value.retainedDiscoveryContextIds.size <= MAX_DISCOVERY_TABS)
        controller.load(); idle(controller)
        assertTrue(controller.state.value.retainedDiscoveryContextIds.isEmpty())
        controller.close()
    }

    @Test fun linkResolutionIsAnExplicitPreviewAndWriteFailuresStayInTheDialog() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last()); val memory = Memory()
        val controller = controller(this, catalog, memory)
        controller.load(); val discovery = idle(controller)
        controller.openLink()
        controller.resolveLink(" ${catalog.episode.resource.identity} "); var state = linkIdle(controller)
        assertEquals(catalog.episode, state.link!!.entry)
        assertEquals(catalog.episode.resource.identity, catalog.lastLookupInput)
        assertEquals(0, memory.writes); assertTrue(state.configured!!.isEmpty())
        assertEquals(discovery.entries, state.entries); assertEquals(discovery.discoveryContextId, state.discoveryContextId)
        memory.failWrite = true
        controller.addLink(); state = linkIdle(controller)
        assertEquals(catalog.episode, state.link!!.entry)
        assertNotNull(state.link.message)
        assertNull(state.message); assertNull(state.failure); assertFalse(state.link.saving)
        assertTrue(state.configured!!.isEmpty())
        controller.editLink(); state = controller.state.value
        assertNull(state.link!!.entry); assertNull(state.link.message); assertNull(state.link.failure)
        memory.failWrite = false
        controller.resolveLink(catalog.future.resource.identity); linkIdle(controller)
        controller.addLink(); state = linkIdle(controller)
        assertNull(state.link); assertEquals(catalog.future, state.configured!!.single().entry)
        assertNull(state.message); assertEquals(discovery.entries, state.entries)
        assertEquals(1, memory.writes)
        controller.close()
    }

    @Test fun linkValidationLookupFailuresDuplicatesAndLimitNeverCommit() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().last()); val memory = Memory()
        val controller = controller(this, catalog, memory)
        controller.load(); idle(controller); controller.openLink()
        for (input in listOf(" ", "x".repeat(2049), "\n${catalog.offline.resource.identity}", "invisible\u200b")) {
            controller.resolveLink(input)
            assertEquals(CatalogFailure.INVALID_INPUT, controller.state.value.link!!.failure!!.reason)
        }
        assertEquals(0, catalog.lookupCalls)
        catalog.lookupFailure = CatalogFailure.NOT_FOUND
        controller.resolveLink("missing"); assertEquals(CatalogFailure.NOT_FOUND, linkIdle(controller).link!!.failure!!.reason)
        catalog.lookupFailure = null
        catalog.lookupEntry = catalog.offline.copy(resource = catalog.offline.resource.copy(providerId = ProviderId("abema")))
        controller.resolveLink("foreign"); assertEquals(CatalogFailure.INVALID_INPUT, linkIdle(controller).link!!.failure!!.reason)
        catalog.lookupEntry = null
        controller.resolveLink(catalog.offline.resource.identity); linkIdle(controller)
        controller.addLink(); idle(controller)
        val writes = memory.writes
        controller.openLink(); controller.resolveLink(catalog.offline.resource.identity); linkIdle(controller)
        controller.addLink()
        assertEquals("This stream is already configured.", controller.state.value.link!!.message)
        assertEquals(writes, memory.writes)
        controller.dismissLink(); controller.close()

        val fullMemory = Memory()
        repeat(MAX_CONFIGURED_SOURCES) { index -> store(fullMemory, catalog.instance).add(catalog.offline.copy(
            resource = catalog.offline.resource.copy(identity = "saved-$index"))) { emptyList() } }
        val full = controller(this, Catalog(catalog.instance), fullMemory)
        full.load(); idle(full); full.openLink(); full.resolveLink(catalog.offline.resource.identity); linkIdle(full)
        val fullWrites = fullMemory.writes
        full.addLink()
        assertEquals("Stream limit reached. Remove a stream before adding another.", full.state.value.link!!.message)
        assertEquals(fullWrites, fullMemory.writes)
        full.close()
    }

    @Test fun dismissAndReopenRejectsLateLookupAndAccessLossClearsPrivateTabSnapshots() = runBlocking {
        for (failure in listOf(null, CatalogFailure.ACCESS_REQUIRED)) {
            val catalog = Catalog(defaultProviderInstances().last()); val controller = controller(this, catalog, Memory(), Dispatchers.Default)
            controller.load(); val root = idle(controller)
            controller.collection(catalog.collectionId); idle(controller)
            val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
            catalog.onLookup = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
            try {
                controller.openLink(); controller.resolveLink(catalog.offline.resource.identity)
                withTimeout(5_000) { entered.await() }
                val pending = coroutineContext[Job]!!.children.toList()
                controller.dismissLink(); controller.openLink()
                catalog.lookupFailure = failure
                release.countDown(); pending.forEach { it.join() }
                assertEquals(StreamLinkState(), controller.state.value.link)
                assertNull(controller.state.value.failure)
                catalog.onLookup = {}
                catalog.lookupFailure = CatalogFailure.ACCESS_REQUIRED
                controller.resolveLink("account-item"); val lost = linkIdle(controller)
                assertEquals(CatalogFailure.ACCESS_REQUIRED, lost.link!!.failure!!.reason)
                assertTrue(lost.entries.isEmpty()); assertTrue(lost.retainedDiscoveryContextIds.isEmpty())
                assertTrue(lost.privacyRevision > root.privacyRevision)
            } finally { release.countDown(); controller.close() }
        }
    }

    @Test fun acceptedLinkWriteClosesOnlyAfterCommitAndSurvivesControllerReplacement() = runBlocking {
        val instance = defaultProviderInstances().last(); val memory = Memory(); val catalog = Catalog(instance)
        val first = controller(this, catalog, memory, Dispatchers.Default)
        first.load(); idle(first); first.openLink(); first.resolveLink(catalog.episode.resource.identity); linkIdle(first)
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        memory.beforeWrite = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        val next = controller(this, Catalog(instance), memory, Dispatchers.Default)
        try {
            first.addLink(); withTimeout(5_000) { entered.await() }
            assertTrue(first.state.value.link!!.saving)
            first.dismissLink(); assertNotNull(first.state.value.link)
            assertTrue(first.state.value.configured!!.isEmpty())
            first.close(); val closed = first.state.value
            next.load()
            assertNull(withTimeoutOrNull(100) { next.state.first { it.configured != null } })
            release.countDown(); val restored = idle(next)
            assertEquals(catalog.episode, restored.configured!!.single().entry)
            assertNull(restored.link); assertEquals(closed, first.state.value)
            assertEquals(1, memory.writes)
        } finally { release.countDown(); first.close(); next.close() }
    }

    @Test fun previewCannotBypassSuspendedLoadUnreadableStoreMissingCapabilitiesOrWrongProvider() = runBlocking {
        val catalog = Catalog(defaultProviderInstances().first())
        val memory = Memory()
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        memory.beforeRead = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        val controller = controller(this, catalog, memory, Dispatchers.Default)
        try {
            controller.load(); withTimeout(5_000) { entered.await() }
            assertFalse(controller.preview(catalog.future))
            assertEquals(0, memory.writes)
            release.countDown(); idle(controller)
            val foreign = catalog.future.copy(resource = catalog.future.resource.copy(providerId = ProviderId("twitch")))
            val before = controller.state.value
            assertFalse(controller.preview(foreign))
            assertEquals(before, controller.state.value)
            memory.failRead = true
            controller.load(); idle(controller)
            assertTrue(controller.state.value.storageFailed)
            assertFalse(controller.preview(catalog.future))
            memory.failRead = false; catalog.throwCapabilities = true
            controller.load(); idle(controller)
            assertNull(controller.state.value.capabilities)
            assertFalse(controller.preview(catalog.future))
            catalog.throwCapabilities = false; catalog.lookupAccess = CatalogAccess.AUTHORIZATION_REQUIRED
            controller.load(); idle(controller)
            assertFalse(controller.preview(catalog.future))
            assertEquals(0, memory.writes)
        } finally { release.countDown(); controller.close() }
    }
}
