package net.fstab.tachiai.feature.connections

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*

internal const val MAX_DISCOVERY_RESULTS = 500
internal const val MAX_DISCOVERY_PARENTS = 8
internal const val MAX_DISCOVERY_TABS = 17
internal const val DISCOVERY_ALL_TAB = "@all"

internal data class StreamLinkState(
    val loading: Boolean = false,
    val entry: CatalogEntry? = null,
    val failure: CatalogResult.Failure? = null,
    val message: String? = null,
    val saving: Boolean = false,
)

internal data class StreamManagementState(
    val instance: ProviderInstance,
    val configured: List<ConfiguredSource>? = null,
    val capabilities: CatalogCapabilities? = null,
    val query: CatalogQuery = CatalogQuery(),
    val entries: List<CatalogEntry> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val storageFailed: Boolean = false,
    val failure: CatalogResult.Failure? = null,
    val message: String? = null,
    val catalogNotice: String? = null,
    val privacyRevision: Long = 0,
    val resultsTruncated: Boolean = false,
    val discoveryContextId: Long = 0,
    val retainedDiscoveryContextIds: List<Long> = emptyList(),
    val childHeading: String? = null,
    val navigationBackLabel: String? = null,
    val selectedTabId: String? = null,
    val link: StreamLinkState? = null,
)

// Activity/controller recreation keeps accepted writes visible in this process.
// The returning Activity waits before finish, including when its parent picker
// lives in another process. Process death preserves only committed records.
internal object StreamManagementWrites {
    private val lock = Any()
    private val jobs = mutableMapOf<String, MutableSet<Job>>()
    fun track(instanceId: String, job: Job) {
        synchronized(lock) { jobs.getOrPut(instanceId) { mutableSetOf() }.add(job) }
        job.invokeOnCompletion { synchronized(lock) {
            jobs[instanceId]?.let { it.remove(job); if (it.isEmpty()) jobs.remove(instanceId) }
        } }
    }
    fun pending(instanceId: String): List<Job> = synchronized(lock) { jobs[instanceId]?.toList().orEmpty() }
    suspend fun await(instanceId: String) {
        while (true) {
            val pending = pending(instanceId)
            if (pending.isEmpty()) return
            pending.forEach { it.join() }
        }
    }
}

internal class StreamManagementController(
    private val instance: ProviderInstance,
    private val catalog: ProviderCatalog,
    private val store: ConfiguredSourceStore,
    private val legacy: () -> List<ConfiguredSource>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: FailureReporter = FailureReporter.NONE,
    notice: String? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private companion object { val privacyRevisions = AtomicLong(); val discoveryContexts = AtomicLong() }
    init {
        require(catalog.instanceId == instance.id && catalog.providerId ==
            ProviderId(instance.service.name.lowercase(java.util.Locale.ROOT)))
    }
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(StreamManagementState(instance, catalogNotice = notice,
        privacyRevision = privacyRevisions.incrementAndGet(), discoveryContextId = discoveryContexts.incrementAndGet()))
    val state: StateFlow<StreamManagementState> = mutableState
    private val browseRevision = AtomicLong()
    private val mutationRevision = AtomicLong()
    private val linkRevision = AtomicLong()
    private val catalogCalls = Mutex()
    private val writes = CoroutineScope(SupervisorJob() + io)
    private var browseJob: Job? = null
    private var linkJob: Job? = null
    private var lastLookup: String? = null
    private var lastRefreshId: String? = null
    private var initialQueryChosen = false
    private data class DiscoveryFrame(val id: Long, val query: CatalogQuery, val entries: List<CatalogEntry>,
        val nextCursor: String?, val resultsTruncated: Boolean, val heading: String?, val backLabel: String?,
        val failure: CatalogResult.Failure?, val lookup: String?)
    private val parents = ArrayDeque<DiscoveryFrame>()
    private val roots = linkedMapOf<String, DiscoveryFrame>()
    private val staleContinuations = mutableSetOf<Long>()
    @Volatile private var closed = false
    @Volatile var pendingMutation: Job? = null
        private set

    private fun publish(change: (StreamManagementState) -> StreamManagementState) = synchronized(stateLock) {
        if (!closed) mutableState.value = change(mutableState.value)
    }
    private fun publishBrowse(revision: Long, change: (StreamManagementState) -> StreamManagementState) = publish {
        if (current(revision)) change(it) else it
    }
    private fun retainedIds(currentId: Long = state.value.discoveryContextId) =
        (roots.values.map { it.id } + parents.map { it.id }).distinct().filterNot { it == currentId }
    private fun frame(snapshot: StreamManagementState) = DiscoveryFrame(snapshot.discoveryContextId,
        snapshot.query, snapshot.entries.toList(), snapshot.nextCursor, snapshot.resultsTruncated,
        snapshot.childHeading, snapshot.navigationBackLabel, snapshot.failure, lastLookup)
    private fun retainRoot() = synchronized(stateLock) {
        val snapshot = state.value
        val tab = snapshot.selectedTabId ?: return@synchronized
        val root = if (snapshot.query.parent != null) parents.firstOrNull()
            else if (!snapshot.loading || snapshot.entries.isNotEmpty()) frame(snapshot) else null
        if (root != null && root.query.parent == null && root.query.search == null && root.lookup == null) {
            roots.remove(tab)
            roots[tab] = root
            while (roots.size > MAX_DISCOVERY_TABS) roots.remove(roots.keys.first())
        }
    }
    private fun resetNavigation(clearRoots: Boolean = true) = synchronized(stateLock) {
        parents.clear()
        if (clearRoots) roots.clear()
        if (clearRoots) staleContinuations.clear()
        val id = discoveryContexts.incrementAndGet()
        publish { it.copy(discoveryContextId = id, retainedDiscoveryContextIds = retainedIds(id),
            childHeading = null, navigationBackLabel = null) }
    }
    private fun current(revision: Long) = !closed && browseRevision.get() == revision
    private fun cancelBrowse() { browseRevision.incrementAndGet(); browseJob?.cancel(); browseJob = null }
    private fun cancelLink() { linkRevision.incrementAndGet(); linkJob?.cancel(); linkJob = null }
    private fun valid(entries: List<CatalogEntry>): List<CatalogEntry> = entries.also {
        check(it.all { entry -> entry.resource.providerId == catalog.providerId })
    }
    // Provider adapters may discard opaque continuations whenever another
    // query or lookup runs. Retained rows are safe to show, but their cursors
    // must be rebuilt before requesting another page.
    private suspend fun <T> catalogCall(action: () -> T): T = withContext(io) {
        catalogCalls.withLock {
            synchronized(stateLock) {
                staleContinuations.retainAll(retainedIds() + state.value.discoveryContextId)
                staleContinuations.add(state.value.discoveryContextId)
                staleContinuations.addAll(retainedIds())
            }
            action()
        }
    }
    private fun clearDiscovery(failure: CatalogResult.Failure, keepCapabilities: Boolean = false, keepLink: Boolean = false) {
        lastLookup = null
        lastRefreshId = null
        if (!keepLink) cancelLink()
        resetNavigation()
        publish { it.copy(entries = emptyList(), nextCursor = null, query = CatalogQuery(collectionId = it.query.collectionId),
            capabilities = if (keepCapabilities) it.capabilities else null,
            failure = failure, loading = false, resultsTruncated = false, privacyRevision = privacyRevisions.incrementAndGet(),
            link = if (keepLink) it.link else null) }
    }
    private fun failed(result: CatalogResult.Failure, keepCapabilities: Boolean = false, revision: Long? = null) = synchronized(stateLock) {
        if (revision != null && !current(revision)) return@synchronized
        if (result.reason == CatalogFailure.ACCESS_REQUIRED) clearDiscovery(result, keepCapabilities)
        else publish { it.copy(failure = result) }
    }

    fun load() {
        if (closed || state.value.saving) return
        val reloadQuery = synchronized(stateLock) {
            val snapshot = state.value
            when {
                snapshot.query.parent == null -> snapshot.query.copy(cursor = null)
                snapshot.selectedTabId != null -> CatalogQuery(collectionId = snapshot.selectedTabId.takeUnless { it == DISCOVERY_ALL_TAB })
                else -> parents.firstOrNull()?.query?.copy(cursor = null) ?: CatalogQuery()
            }
        }
        cancelBrowse()
        cancelLink()
        resetNavigation()
        val revision = browseRevision.get()
        lastLookup = null
        lastRefreshId = null
        publish { it.copy(loading = true, storageFailed = false, capabilities = null, message = null, failure = null,
            entries = emptyList(), nextCursor = null, query = CatalogQuery(collectionId = it.query.collectionId),
            resultsTruncated = false, privacyRevision = privacyRevisions.incrementAndGet(), link = null) }
        browseJob = scope.launch {
            var stage = FailureStage.CONFIGURED_SOURCES_READ
            try {
                StreamManagementWrites.await(instance.id)
                val configured = withContext(io) { store.read(legacy) }
                if (!current(revision)) return@launch
                publishBrowse(revision) { it.copy(configured = configured) }
                stage = FailureStage.CATALOG_LOAD
                val capabilities = catalogCall { catalog.capabilities() }
                if (!current(revision)) return@launch
                val query = if (initialQueryChosen) reloadQuery
                    else CatalogQuery(collectionId = capabilities.initialCollectionId)
                initialQueryChosen = true
                publishBrowse(revision) { it.copy(capabilities = capabilities, query = query, entries = emptyList(), nextCursor = null,
                    resultsTruncated = false, selectedTabId = if (query.search != null) null else query.collectionId ?: DISCOVERY_ALL_TAB) }
                browseCurrent(revision, query, false)
            } catch (_: CancellationException) {
                // Query cancellation never changes persisted configured entries.
            } catch (error: Exception) {
                if (current(revision)) {
                    val storage = stage == FailureStage.CONFIGURED_SOURCES_READ
                    diagnostics.report(stage, error)
                    publishBrowse(revision) { it.copy(storageFailed = storage, failure = if (storage) null else
                        CatalogResult.Failure(CatalogFailure.TEMPORARY),
                        message = if (storage) "Configured streams could not be read. Nothing was replaced. Retry to continue." else null) }
                }
            } finally { publishBrowse(revision) { it.copy(loading = false) } }
        }
    }

    private fun access(query: CatalogQuery): CatalogAccess {
        val capabilities = state.value.capabilities ?: return CatalogAccess.NOT_VERIFIED
        return when {
            query.parent != null -> capabilities.childrenFor(query.parent, catalog.providerId)?.access ?: CatalogAccess.UNSUPPORTED
            query.collectionId != null -> capabilities.collections.singleOrNull { it.id == query.collectionId }?.access ?: CatalogAccess.UNSUPPORTED
            query.search != null -> capabilities.search
            else -> capabilities.browse
        }
    }
    private fun unavailable(access: CatalogAccess) = CatalogResult.Failure(when (access) {
        CatalogAccess.UNSUPPORTED -> CatalogFailure.UNSUPPORTED
        CatalogAccess.NOT_VERIFIED -> CatalogFailure.NOT_VERIFIED
        else -> CatalogFailure.ACCESS_REQUIRED
    })
    private suspend fun browseCurrent(revision: Long, query: CatalogQuery, append: Boolean) {
        val permission = access(query)
        val result = if (permission == CatalogAccess.AVAILABLE) catalogCall { catalog.browse(query) }
            else unavailable(permission)
        if (!current(revision)) return
        when (result) {
            is CatalogResult.Value -> {
                val entries = valid(result.value.entries)
                check(result.value.nextCursor == null || result.value.nextCursor != query.cursor)
                publishBrowse(revision) {
                    staleContinuations.remove(it.discoveryContextId)
                    val unique = (if (append) it.entries + entries else entries).distinctBy { entry -> entry.resource }
                    val truncated = unique.size > MAX_DISCOVERY_RESULTS ||
                        unique.size == MAX_DISCOVERY_RESULTS && result.value.nextCursor != null
                    it.copy(entries = unique.take(MAX_DISCOVERY_RESULTS), resultsTruncated = truncated,
                        nextCursor = if (truncated) null else result.value.nextCursor, failure = null)
                }
            }
            is CatalogResult.Failure -> synchronized(stateLock) {
                if (current(revision)) {
                    if (append && result.reason == CatalogFailure.INVALID_INPUT) clearDiscovery(result)
                    else failed(result, keepCapabilities = permission != CatalogAccess.AVAILABLE, revision = revision)
                }
            }
        }
    }

    private fun browse(query: CatalogQuery, append: Boolean = false, preserveContext: Boolean = false) {
        if (closed || state.value.saving || state.value.configured == null || state.value.storageFailed || state.value.link != null) return
        lastLookup = null
        lastRefreshId = null
        initialQueryChosen = true
        cancelBrowse()
        if (!append && !preserveContext) {
            retainRoot()
            resetNavigation(clearRoots = false)
        }
        val revision = browseRevision.get()
        publish { it.copy(query = query.copy(cursor = null), loading = true, failure = null, message = null,
            entries = if (append) it.entries else emptyList(), nextCursor = if (append) it.nextCursor else null,
            resultsTruncated = if (append) it.resultsTruncated else false,
            selectedTabId = if (!append && !preserveContext && query.search != null) null else it.selectedTabId) }
        browseJob = scope.launch {
            try { browseCurrent(revision, query, append) }
            catch (_: CancellationException) { }
            catch (error: Exception) {
                if (current(revision)) { diagnostics.report(FailureStage.CATALOG_LOAD, error)
                    publishBrowse(revision) { it.copy(failure = CatalogResult.Failure(CatalogFailure.TEMPORARY)) } }
            } finally { publishBrowse(revision) { it.copy(loading = false) } }
        }
    }
    private fun selectTab(id: String, query: CatalogQuery) = synchronized(stateLock) {
        val snapshot = state.value
        if (closed || snapshot.saving || snapshot.configured == null || snapshot.storageFailed || snapshot.link != null) return@synchronized
        if (snapshot.selectedTabId == id && snapshot.query.parent == null && !snapshot.loading && lastLookup == null) {
            browse(query, preserveContext = true)
            return@synchronized
        }
        retainRoot()
        cancelBrowse()
        parents.clear()
        lastLookup = null
        lastRefreshId = null
        initialQueryChosen = true
        val cached = roots[id]
        if (cached != null) {
            publish { it.copy(selectedTabId = id, query = cached.query, entries = cached.entries,
                nextCursor = cached.nextCursor, resultsTruncated = cached.resultsTruncated,
                discoveryContextId = cached.id, retainedDiscoveryContextIds = retainedIds(cached.id),
                childHeading = null, navigationBackLabel = null, failure = cached.failure, loading = false, message = null) }
        } else {
            resetNavigation(clearRoots = false)
            publish { it.copy(selectedTabId = id) }
            browse(query, preserveContext = true)
        }
    }
    fun all() = selectTab(DISCOVERY_ALL_TAB, CatalogQuery())
    fun search(text: String) {
        lastRefreshId = null
        val trimmed = text.trim()
        val query = if (trimmed.isEmpty()) null else runCatching { CatalogQuery(search = trimmed) }.getOrNull()
        if (query == null) publish { it.copy(failure = CatalogResult.Failure(CatalogFailure.INVALID_INPUT)) } else browse(query)
    }
    fun collection(id: String) = selectTab(id, CatalogQuery(collectionId = id))
    fun children(resource: CatalogResource) {
        lastRefreshId = null
        if (resource.providerId != catalog.providerId) { publish { it.copy(failure = CatalogResult.Failure(CatalogFailure.INVALID_INPUT)) }; return }
        val snapshot = state.value
        if (closed || snapshot.loading || snapshot.saving || snapshot.configured == null || snapshot.storageFailed || snapshot.link != null) return
        synchronized(stateLock) {
            if (parents.size >= MAX_DISCOVERY_PARENTS) {
                publish { it.copy(failure = CatalogResult.Failure(CatalogFailure.INVALID_INPUT)) }
                return
            }
            parents.addLast(frame(snapshot))
            val title = (snapshot.entries.firstOrNull { it.resource == resource } ?:
                snapshot.configured.firstOrNull { it.entry.resource == resource }?.entry)?.title ?: resource.identity
            val label = when {
                snapshot.query.parent != null -> "Back to ${snapshot.childHeading ?: "items"}"
                lastLookup != null -> "Back to lookup results"
                snapshot.query.search != null -> "Back to search results"
                snapshot.query.collectionId != null -> "Back to ${snapshot.capabilities?.collections?.firstOrNull { it.id == snapshot.query.collectionId }?.title ?: "results"}"
                else -> "Back to ${snapshot.capabilities?.browseTitle ?: "results"}"
            }
            val id = discoveryContexts.incrementAndGet()
            publish { it.copy(discoveryContextId = id, retainedDiscoveryContextIds = retainedIds(id),
                childHeading = "${snapshot.capabilities?.childrenFor(resource, catalog.providerId)?.headingPrefix ?: "Items under"} $title", navigationBackLabel = label) }
        }
        browse(CatalogQuery(parent = resource), preserveContext = true)
    }
    fun back(): Boolean = synchronized(stateLock) {
        if (closed || state.value.link != null) return@synchronized false
        val frame = parents.removeLastOrNull() ?: return@synchronized false
        cancelBrowse()
        lastLookup = frame.lookup
        lastRefreshId = null
        publish { it.copy(query = frame.query, entries = frame.entries, nextCursor = frame.nextCursor,
            resultsTruncated = frame.resultsTruncated, childHeading = frame.heading, navigationBackLabel = frame.backLabel,
            discoveryContextId = frame.id, retainedDiscoveryContextIds = retainedIds(frame.id), failure = frame.failure, loading = false) }
        true
    }
    fun more() = synchronized(stateLock) {
        val snapshot = state.value
        val cursor = snapshot.nextCursor ?: return@synchronized
        if (closed || snapshot.loading || snapshot.saving || snapshot.link != null || snapshot.storageFailed) return@synchronized
        if (snapshot.discoveryContextId !in staleContinuations) {
            browse(snapshot.query.copy(cursor = cursor), true)
            return@synchronized
        }
        cancelBrowse()
        val revision = browseRevision.get()
        publish { it.copy(loading = true, failure = null, message = null) }
        browseJob = scope.launch {
            try {
                var query = snapshot.query.copy(cursor = null)
                val fresh = linkedMapOf<CatalogResource, CatalogEntry>()
                val cursors = mutableSetOf<String>()
                var pages = 0
                while (current(revision)) {
                    check(++pages <= MAX_DISCOVERY_RESULTS)
                    val permission = access(query)
                    val result = if (permission == CatalogAccess.AVAILABLE) catalogCall { catalog.browse(query) } else unavailable(permission)
                    if (!current(revision)) return@launch
                    when (result) {
                        is CatalogResult.Failure -> { failed(result, keepCapabilities = permission != CatalogAccess.AVAILABLE, revision = revision); return@launch }
                        is CatalogResult.Value -> {
                            valid(result.value.entries).forEach { fresh[it.resource] = it }
                            val next = result.value.nextCursor
                            check(next == null || cursors.add(next))
                            val truncated = fresh.size > MAX_DISCOVERY_RESULTS || fresh.size == MAX_DISCOVERY_RESULTS && next != null
                            if (fresh.size > snapshot.entries.size || next == null || truncated) {
                                publishBrowse(revision) {
                                    staleContinuations.remove(it.discoveryContextId)
                                    it.copy(entries = fresh.values.take(MAX_DISCOVERY_RESULTS), nextCursor = if (truncated) null else next,
                                        resultsTruncated = truncated, failure = null)
                                }
                                return@launch
                            }
                            query = query.copy(cursor = next)
                        }
                    }
                }
            } catch (_: CancellationException) { }
            catch (error: Exception) {
                if (current(revision)) {
                    diagnostics.report(FailureStage.CATALOG_LOAD, error)
                    failed(CatalogResult.Failure(CatalogFailure.TEMPORARY), revision = revision)
                }
            } finally { publishBrowse(revision) { it.copy(loading = false) } }
        }
    }
    fun retry() {
        if (closed || state.value.loading || state.value.saving) return
        state.value.failure?.retryAtEpochMs?.let { if (clockMs() < it) return }
        if (state.value.storageFailed || state.value.configured == null) load()
        else lastRefreshId?.let(::refresh) ?: if (state.value.capabilities == null) load()
        else lastLookup?.let(::lookup) ?: if (synchronized(stateLock) {
            state.value.nextCursor != null && state.value.discoveryContextId in staleContinuations
        }) more() else browse(state.value.query, preserveContext = true)
    }

    fun lookup(input: String) {
        if (closed || state.value.loading || state.value.saving || state.value.configured == null || state.value.storageFailed || state.value.link != null) return
        lastRefreshId = null
        if (input.length !in 1..2048 || input.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) {
            publish { it.copy(failure = CatalogResult.Failure(CatalogFailure.INVALID_INPUT)) }; return
        }
        cancelBrowse(); val revision = browseRevision.get()
        resetNavigation()
        lastLookup = input
        publish { it.copy(query = CatalogQuery(collectionId = it.query.collectionId), loading = true, failure = null, entries = emptyList(), nextCursor = null, message = null,
            resultsTruncated = false, selectedTabId = null) }
        browseJob = scope.launch {
            try {
                val permission = state.value.capabilities?.lookup ?: CatalogAccess.NOT_VERIFIED
                val result = if (permission == CatalogAccess.AVAILABLE) catalogCall { catalog.lookup(input) } else unavailable(permission)
                if (current(revision)) when (result) {
                    is CatalogResult.Value -> publishBrowse(revision) { it.copy(entries = valid(listOf(result.value))) }
                    is CatalogResult.Failure -> failed(result, keepCapabilities = permission != CatalogAccess.AVAILABLE, revision = revision)
                }
            } catch (_: CancellationException) { }
            catch (error: Exception) { if (current(revision)) { diagnostics.report(FailureStage.CATALOG_LOAD, error)
                publishBrowse(revision) { it.copy(failure = CatalogResult.Failure(CatalogFailure.TEMPORARY)) } } }
            finally { publishBrowse(revision) { it.copy(loading = false) } }
        }
    }

    fun openLink() = synchronized(stateLock) {
        val snapshot = state.value
        if (closed || snapshot.loading || snapshot.saving || snapshot.storageFailed || snapshot.configured == null ||
            snapshot.link != null || snapshot.capabilities?.lookup == null || snapshot.capabilities.lookup == CatalogAccess.UNSUPPORTED) return@synchronized
        cancelLink()
        publish { it.copy(link = StreamLinkState()) }
    }

    fun dismissLink() = synchronized(stateLock) {
        if (state.value.link?.saving == true) return@synchronized
        cancelLink()
        publish { it.copy(link = null) }
    }

    fun editLink() = synchronized(stateLock) {
        val dialog = state.value.link ?: return@synchronized
        if (closed || state.value.saving || dialog.loading || dialog.saving) return@synchronized
        cancelLink()
        publish { it.copy(link = StreamLinkState()) }
    }

    private fun currentLink(revision: Long) = !closed && linkRevision.get() == revision && state.value.link != null
    private fun publishLink(revision: Long, change: (StreamLinkState) -> StreamLinkState) = publish {
        if (currentLink(revision)) it.copy(link = change(checkNotNull(it.link))) else it
    }

    fun resolveLink(input: String) = synchronized(stateLock) {
        val snapshot = state.value
        val dialog = snapshot.link ?: return@synchronized
        if (closed || snapshot.loading || snapshot.saving || dialog.saving || snapshot.storageFailed || snapshot.configured == null) return@synchronized
        dialog.failure?.retryAtEpochMs?.let { if (clockMs() < it) return@synchronized }
        cancelLink()
        val revision = linkRevision.get()
        val normalized = input.trim()
        if (normalized.isEmpty() || input.length !in 1..2048 || input.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) {
            publishLink(revision) { StreamLinkState(failure = CatalogResult.Failure(CatalogFailure.INVALID_INPUT)) }
            return@synchronized
        }
        publishLink(revision) { StreamLinkState(loading = true) }
        linkJob = scope.launch {
            try {
                val permission = state.value.capabilities?.lookup ?: CatalogAccess.NOT_VERIFIED
                val result = if (permission == CatalogAccess.AVAILABLE)
                    catalogCall { catalog.lookup(normalized) } else unavailable(permission)
                synchronized(stateLock) {
                    if (!currentLink(revision)) return@synchronized
                    when (result) {
                        is CatalogResult.Value -> publishLink(revision) {
                            if (result.value.resource.providerId == catalog.providerId) StreamLinkState(entry = result.value)
                            else StreamLinkState(failure = CatalogResult.Failure(CatalogFailure.INVALID_INPUT))
                        }
                        is CatalogResult.Failure -> {
                            if (result.reason == CatalogFailure.ACCESS_REQUIRED)
                                clearDiscovery(result, keepCapabilities = permission != CatalogAccess.AVAILABLE, keepLink = true)
                            publishLink(revision) { StreamLinkState(failure = result) }
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Dismissal and supersession never publish a late resolution.
            } catch (error: Exception) {
                if (currentLink(revision)) {
                    diagnostics.report(FailureStage.CATALOG_LOAD, error)
                    publishLink(revision) { StreamLinkState(failure = CatalogResult.Failure(CatalogFailure.TEMPORARY)) }
                }
            } finally { publishLink(revision) { it.copy(loading = false) } }
        }
    }

    fun addLink() = synchronized(stateLock) {
        val snapshot = state.value
        val dialog = snapshot.link ?: return@synchronized
        val entry = dialog.entry ?: return@synchronized
        if (closed || snapshot.loading || snapshot.saving || dialog.loading || dialog.saving || snapshot.storageFailed || snapshot.configured == null) return@synchronized
        val message = when {
            snapshot.configured.any { it.entry.resource == entry.resource } -> "This stream is already configured."
            snapshot.configured.size >= MAX_CONFIGURED_SOURCES -> "Stream limit reached. Remove a stream before adding another."
            entry.resource.providerId != catalog.providerId -> "This stream belongs to another provider."
            else -> null
        }
        if (message != null) { publish { it.copy(link = dialog.copy(message = message)) }; return@synchronized }
        val revision = linkRevision.get()
        mutate(expectedLinkRevision = revision) { store.add(entry, legacy) }
        Unit
    }

    // Public share entries are already normalized at the share boundary. This
    // only presents a preview; explicit Add remains the sole persistent action.
    fun preview(entry: CatalogEntry): Boolean {
        val currentState = state.value
        if (closed || currentState.loading || currentState.saving || currentState.configured == null || currentState.storageFailed ||
            currentState.capabilities?.lookup != CatalogAccess.AVAILABLE || currentState.link != null) return false
        if (entry.resource.providerId != catalog.providerId) return false
        cancelBrowse()
        resetNavigation()
        lastLookup = null
        lastRefreshId = null
        initialQueryChosen = true
        var previewed = false
        publish {
            previewed = true
            it.copy(query = CatalogQuery(), entries = listOf(entry), nextCursor = null,
                failure = null, message = null, resultsTruncated = false, privacyRevision = it.privacyRevision + 1,
                selectedTabId = null)
        }
        return previewed
    }

    fun refresh(id: String) {
        val snapshot = state.value
        if (closed || snapshot.loading || snapshot.saving || snapshot.configured == null || snapshot.storageFailed || snapshot.link != null) return
        snapshot.failure?.retryAtEpochMs?.let { if (clockMs() < it) return }
        val source = snapshot.configured.singleOrNull { it.id == id }
        if (source == null || source.instanceId != instance.id || source.entry.resource.providerId != catalog.providerId) {
            lastRefreshId = null
            publish { it.copy(failure = CatalogResult.Failure(CatalogFailure.INVALID_INPUT)) }
            return
        }
        val permission = snapshot.capabilities?.refresh ?: CatalogAccess.NOT_VERIFIED
        if (permission != CatalogAccess.AVAILABLE) {
            lastRefreshId = null
            failed(unavailable(permission), keepCapabilities = true)
            return
        }
        cancelBrowse(); val revision = browseRevision.get()
        resetNavigation()
        lastLookup = null
        lastRefreshId = id
        publish { it.copy(loading = true, failure = null, message = null, entries = emptyList(), nextCursor = null,
            query = CatalogQuery(collectionId = it.query.collectionId), resultsTruncated = false,
            privacyRevision = privacyRevisions.incrementAndGet()) }
        browseJob = scope.launch {
            try {
                val result = catalogCall { catalog.refresh(source.entry.resource) }
                if (!current(revision)) return@launch
                when (result) {
                    is CatalogResult.Value -> {
                        check(result.value.resource == source.entry.resource)
                        // Close/supersession before this boundary discards retrieval.
                        // An accepted write then owns its independent lifecycle and
                        // the store rereads the latest UUID/order/quality atomically.
                        mutate(refreshId = id, expectedBrowseRevision = revision) { store.refresh(id, result.value, legacy) }
                    }
                    is CatalogResult.Failure -> failed(result, revision = revision)
                }
            } catch (_: CancellationException) { }
            catch (error: Exception) {
                if (current(revision)) {
                    diagnostics.report(FailureStage.CATALOG_LOAD, error)
                    publishBrowse(revision) { it.copy(failure = CatalogResult.Failure(CatalogFailure.TEMPORARY)) }
                }
            } finally { publishBrowse(revision) { it.copy(loading = false) } }
        }
    }

    private fun mutate(refreshId: String? = null, expectedBrowseRevision: Long? = null,
        expectedLinkRevision: Long? = null, action: () -> List<ConfiguredSource>): Job? {
        val job = synchronized(stateLock) {
            if (closed || state.value.saving || state.value.configured == null || state.value.storageFailed ||
                expectedBrowseRevision == null && state.value.loading ||
                expectedBrowseRevision != null && !current(expectedBrowseRevision) ||
                expectedLinkRevision == null && state.value.link != null ||
                expectedLinkRevision != null && !currentLink(expectedLinkRevision)) return null
            if (refreshId == null) lastRefreshId = null
            val revision = mutationRevision.incrementAndGet()
            publish { it.copy(saving = true, loading = if (expectedBrowseRevision != null) false else it.loading, message = null,
                link = if (expectedLinkRevision != null) it.link?.copy(saving = true, message = null) else it.link) }
            // Track acceptance under the close gate, but start disk work only
            // after releasing it. Accepted writes survive Activity destruction.
            val accepted = writes.launch(start = CoroutineStart.LAZY) {
                try {
                    val configured = action()
                    if (!closed && mutationRevision.get() == revision) publish {
                        if (refreshId != null) lastRefreshId = null
                        it.copy(configured = configured, failure = if (refreshId != null) null else it.failure,
                            message = null, link = if (expectedLinkRevision != null && currentLink(expectedLinkRevision)) null else it.link) }
                } catch (error: Exception) {
                    diagnostics.report(FailureStage.CONFIGURED_SOURCES_WRITE, error)
                    if (!closed && mutationRevision.get() == revision)
                        publish { it.copy(failure = if (refreshId != null) CatalogResult.Failure(CatalogFailure.TEMPORARY) else it.failure,
                            message = if (expectedLinkRevision == null) "Configured streams could not be saved. Retry; the list was not replaced." else it.message,
                            link = if (expectedLinkRevision != null && currentLink(expectedLinkRevision))
                                it.link?.copy(saving = false, message = "This stream could not be saved. Try Add again.") else it.link) }
                } finally {
                    if (!closed && mutationRevision.get() == revision) publish { it.copy(saving = false) }
                    if (closed) writes.cancel()
                }
            }
            pendingMutation = accepted; StreamManagementWrites.track(instance.id, accepted)
            accepted
        }
        job.start()
        return job
    }
    fun add(entry: CatalogEntry) {
        if (entry.resource.providerId != catalog.providerId) { publish { it.copy(failure = CatalogResult.Failure(CatalogFailure.INVALID_INPUT)) }; return }
        mutate { store.add(entry, legacy) }
    }
    fun remove(id: String) { mutate { store.remove(id, legacy) } }
    fun move(id: String, delta: Int) { mutate { store.move(id, delta, legacy) } }
    fun reorder(ids: List<String>): Job? {
        val requested = ids.toList()
        return mutate { store.reorder(requested, legacy) }
    }
    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            lastLookup = null
            lastRefreshId = null
            parents.clear()
            roots.clear()
            staleContinuations.clear()
            mutableState.value = mutableState.value.let { it.copy(entries = emptyList(), nextCursor = null,
                capabilities = null, query = CatalogQuery(), failure = null, loading = false,
                resultsTruncated = false, privacyRevision = privacyRevisions.incrementAndGet(), retainedDiscoveryContextIds = emptyList(),
                childHeading = null, navigationBackLabel = null, selectedTabId = null, link = null) }
        }
        cancelBrowse(); cancelLink(); mutationRevision.incrementAndGet()
        // Adapter close can cancel blocking requests; keep it off the UI thread.
        CoroutineScope(io).launch { diagnostics.cleanup(FailureStage.CATALOG_CLOSE) { catalog.close() } }
        if (pendingMutation?.isCompleted != false) writes.cancel()
    }
}
