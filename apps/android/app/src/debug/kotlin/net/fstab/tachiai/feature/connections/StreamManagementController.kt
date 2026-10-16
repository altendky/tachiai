package net.fstab.tachiai.feature.connections

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*

internal const val MAX_DISCOVERY_RESULTS = 500
internal const val MAX_DISCOVERY_PARENTS = 8

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
    private val writes = CoroutineScope(SupervisorJob() + io)
    private var browseJob: Job? = null
    private var lastLookup: String? = null
    private var lastRefreshId: String? = null
    private var initialQueryChosen = false
    private data class DiscoveryFrame(val id: Long, val query: CatalogQuery, val entries: List<CatalogEntry>,
        val nextCursor: String?, val resultsTruncated: Boolean, val heading: String?, val backLabel: String?,
        val failure: CatalogResult.Failure?, val lookup: String?)
    private val parents = ArrayDeque<DiscoveryFrame>()
    @Volatile private var closed = false
    @Volatile var pendingMutation: Job? = null
        private set

    private fun publish(change: (StreamManagementState) -> StreamManagementState) = synchronized(stateLock) {
        if (!closed) mutableState.value = change(mutableState.value)
    }
    private fun publishBrowse(revision: Long, change: (StreamManagementState) -> StreamManagementState) = publish {
        if (current(revision)) change(it) else it
    }
    private fun resetNavigation() = synchronized(stateLock) {
        parents.clear()
        publish { it.copy(discoveryContextId = discoveryContexts.incrementAndGet(), retainedDiscoveryContextIds = emptyList(),
            childHeading = null, navigationBackLabel = null) }
    }
    private fun current(revision: Long) = !closed && browseRevision.get() == revision
    private fun cancelBrowse() { browseRevision.incrementAndGet(); browseJob?.cancel(); browseJob = null }
    private fun valid(entries: List<CatalogEntry>): List<CatalogEntry> = entries.also {
        check(it.all { entry -> entry.resource.providerId == catalog.providerId })
    }
    private fun clearDiscovery(failure: CatalogResult.Failure, keepCapabilities: Boolean = false) {
        lastLookup = null
        lastRefreshId = null
        resetNavigation()
        publish { it.copy(entries = emptyList(), nextCursor = null, query = CatalogQuery(collectionId = it.query.collectionId),
            capabilities = if (keepCapabilities) it.capabilities else null,
            failure = failure, loading = false, resultsTruncated = false, privacyRevision = privacyRevisions.incrementAndGet()) }
    }
    private fun failed(result: CatalogResult.Failure, keepCapabilities: Boolean = false, revision: Long? = null) = synchronized(stateLock) {
        if (revision != null && !current(revision)) return@synchronized
        if (result.reason == CatalogFailure.ACCESS_REQUIRED) clearDiscovery(result, keepCapabilities)
        else publish { it.copy(failure = result) }
    }

    fun load() {
        if (closed || state.value.saving) return
        cancelBrowse()
        resetNavigation()
        val revision = browseRevision.get()
        lastLookup = null
        lastRefreshId = null
        publish { it.copy(loading = true, storageFailed = false, capabilities = null, message = null, failure = null,
            entries = emptyList(), nextCursor = null, query = CatalogQuery(collectionId = it.query.collectionId),
            resultsTruncated = false, privacyRevision = privacyRevisions.incrementAndGet()) }
        browseJob = scope.launch {
            var stage = FailureStage.CONFIGURED_SOURCES_READ
            try {
                StreamManagementWrites.await(instance.id)
                val configured = withContext(io) { store.read(legacy) }
                if (!current(revision)) return@launch
                publishBrowse(revision) { it.copy(configured = configured) }
                stage = FailureStage.CATALOG_LOAD
                val capabilities = withContext(io) { catalog.capabilities() }
                if (!current(revision)) return@launch
                val query = if (initialQueryChosen) state.value.query.copy(cursor = null)
                    else CatalogQuery(collectionId = capabilities.initialCollectionId)
                initialQueryChosen = true
                publishBrowse(revision) { it.copy(capabilities = capabilities, query = query, entries = emptyList(), nextCursor = null,
                    resultsTruncated = false) }
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
            query.parent != null -> capabilities.children
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
        val result = if (permission == CatalogAccess.AVAILABLE) withContext(io) { catalog.browse(query) }
            else unavailable(permission)
        if (!current(revision)) return
        when (result) {
            is CatalogResult.Value -> {
                val entries = valid(result.value.entries)
                check(result.value.nextCursor == null || result.value.nextCursor != query.cursor)
                publishBrowse(revision) {
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
        if (closed || state.value.saving || state.value.configured == null || state.value.storageFailed) return
        lastLookup = null
        lastRefreshId = null
        initialQueryChosen = true
        cancelBrowse()
        if (!append && !preserveContext) resetNavigation()
        val revision = browseRevision.get()
        publish { it.copy(query = query.copy(cursor = null), loading = true, failure = null, message = null,
            entries = if (append) it.entries else emptyList(), nextCursor = if (append) it.nextCursor else null,
            resultsTruncated = if (append) it.resultsTruncated else false) }
        browseJob = scope.launch {
            try { browseCurrent(revision, query, append) }
            catch (_: CancellationException) { }
            catch (error: Exception) {
                if (current(revision)) { diagnostics.report(FailureStage.CATALOG_LOAD, error)
                    publishBrowse(revision) { it.copy(failure = CatalogResult.Failure(CatalogFailure.TEMPORARY)) } }
            } finally { publishBrowse(revision) { it.copy(loading = false) } }
        }
    }
    fun all() = browse(CatalogQuery())
    fun search(text: String) {
        lastRefreshId = null
        val trimmed = text.trim()
        val query = if (trimmed.isEmpty()) null else runCatching { CatalogQuery(search = trimmed) }.getOrNull()
        if (query == null) publish { it.copy(failure = CatalogResult.Failure(CatalogFailure.INVALID_INPUT)) } else browse(query)
    }
    fun collection(id: String) = browse(CatalogQuery(collectionId = id))
    fun children(resource: CatalogResource) {
        lastRefreshId = null
        if (resource.providerId != catalog.providerId) { publish { it.copy(failure = CatalogResult.Failure(CatalogFailure.INVALID_INPUT)) }; return }
        val snapshot = state.value
        if (closed || snapshot.loading || snapshot.saving || snapshot.configured == null || snapshot.storageFailed) return
        synchronized(stateLock) {
            if (parents.size >= MAX_DISCOVERY_PARENTS) {
                publish { it.copy(failure = CatalogResult.Failure(CatalogFailure.INVALID_INPUT)) }
                return
            }
            parents.addLast(DiscoveryFrame(snapshot.discoveryContextId, snapshot.query, snapshot.entries.toList(), snapshot.nextCursor,
                snapshot.resultsTruncated, snapshot.childHeading, snapshot.navigationBackLabel, snapshot.failure, lastLookup))
            val title = (snapshot.entries.firstOrNull { it.resource == resource } ?:
                snapshot.configured.firstOrNull { it.entry.resource == resource }?.entry)?.title ?: resource.identity
            val label = when {
                snapshot.query.parent != null -> "Back to ${snapshot.childHeading ?: "items"}"
                lastLookup != null -> "Back to lookup results"
                snapshot.query.search != null -> "Back to search results"
                snapshot.query.collectionId != null -> "Back to ${snapshot.capabilities?.collections?.firstOrNull { it.id == snapshot.query.collectionId }?.title ?: "results"}"
                else -> "Back to ${snapshot.capabilities?.browseTitle ?: "results"}"
            }
            publish { it.copy(discoveryContextId = discoveryContexts.incrementAndGet(), retainedDiscoveryContextIds = parents.map { it.id },
                childHeading = "${snapshot.capabilities?.childrenHeadingPrefix ?: "Items under"} $title", navigationBackLabel = label) }
        }
        browse(CatalogQuery(parent = resource), preserveContext = true)
    }
    fun back(): Boolean = synchronized(stateLock) {
        if (closed) return@synchronized false
        val frame = parents.removeLastOrNull() ?: return@synchronized false
        cancelBrowse()
        lastLookup = frame.lookup
        lastRefreshId = null
        publish { it.copy(query = frame.query, entries = frame.entries, nextCursor = frame.nextCursor,
            resultsTruncated = frame.resultsTruncated, childHeading = frame.heading, navigationBackLabel = frame.backLabel,
            discoveryContextId = frame.id, retainedDiscoveryContextIds = parents.map { it.id }, failure = frame.failure, loading = false) }
        true
    }
    fun more() { if (!state.value.loading) state.value.nextCursor?.let { browse(state.value.query.copy(cursor = it), true) } }
    fun retry() {
        if (closed || state.value.loading || state.value.saving) return
        state.value.failure?.retryAtEpochMs?.let { if (clockMs() < it) return }
        if (state.value.storageFailed || state.value.configured == null) load()
        else lastRefreshId?.let(::refresh) ?: if (state.value.capabilities == null) load()
        else lastLookup?.let(::lookup) ?: browse(state.value.query, preserveContext = true)
    }

    fun lookup(input: String) {
        if (closed || state.value.loading || state.value.saving || state.value.configured == null || state.value.storageFailed) return
        lastRefreshId = null
        if (input.length !in 1..2048 || input.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) {
            publish { it.copy(failure = CatalogResult.Failure(CatalogFailure.INVALID_INPUT)) }; return
        }
        cancelBrowse(); val revision = browseRevision.get()
        resetNavigation()
        lastLookup = input
        publish { it.copy(query = CatalogQuery(collectionId = it.query.collectionId), loading = true, failure = null, entries = emptyList(), nextCursor = null, message = null,
            resultsTruncated = false) }
        browseJob = scope.launch {
            try {
                val permission = state.value.capabilities?.lookup ?: CatalogAccess.NOT_VERIFIED
                val result = if (permission == CatalogAccess.AVAILABLE) withContext(io) { catalog.lookup(input) } else unavailable(permission)
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

    // Public share entries are already normalized at the share boundary. This
    // only presents a preview; explicit Add remains the sole persistent action.
    fun preview(entry: CatalogEntry): Boolean {
        val currentState = state.value
        if (closed || currentState.loading || currentState.saving || currentState.configured == null || currentState.storageFailed ||
            currentState.capabilities?.lookup != CatalogAccess.AVAILABLE) return false
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
                failure = null, message = null, resultsTruncated = false, privacyRevision = it.privacyRevision + 1)
        }
        return previewed
    }

    fun refresh(id: String) {
        val snapshot = state.value
        if (closed || snapshot.loading || snapshot.saving || snapshot.configured == null || snapshot.storageFailed) return
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
                val result = withContext(io) { catalog.refresh(source.entry.resource) }
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

    private fun mutate(refreshId: String? = null, expectedBrowseRevision: Long? = null, action: () -> List<ConfiguredSource>): Job? {
        val job = synchronized(stateLock) {
            if (closed || state.value.saving || state.value.configured == null || state.value.storageFailed ||
                expectedBrowseRevision == null && state.value.loading ||
                expectedBrowseRevision != null && !current(expectedBrowseRevision)) return null
            if (refreshId == null) lastRefreshId = null
            val revision = mutationRevision.incrementAndGet()
            publish { it.copy(saving = true, loading = if (expectedBrowseRevision != null) false else it.loading, message = null) }
            // Track acceptance under the close gate, but start disk work only
            // after releasing it. Accepted writes survive Activity destruction.
            val accepted = writes.launch(start = CoroutineStart.LAZY) {
                try {
                    val configured = action()
                    if (!closed && mutationRevision.get() == revision) publish {
                        if (refreshId != null) lastRefreshId = null
                        it.copy(configured = configured, failure = if (refreshId != null) null else it.failure,
                            message = null) }
                } catch (error: Exception) {
                    diagnostics.report(FailureStage.CONFIGURED_SOURCES_WRITE, error)
                    if (!closed && mutationRevision.get() == revision)
                        publish { it.copy(failure = if (refreshId != null) CatalogResult.Failure(CatalogFailure.TEMPORARY) else it.failure,
                            message = "Configured streams could not be saved. Retry; the list was not replaced.") }
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
            mutableState.value = mutableState.value.let { it.copy(entries = emptyList(), nextCursor = null,
                capabilities = null, query = CatalogQuery(), failure = null, loading = false,
                resultsTruncated = false, privacyRevision = privacyRevisions.incrementAndGet(), retainedDiscoveryContextIds = emptyList(),
                childHeading = null, navigationBackLabel = null) }
        }
        cancelBrowse(); mutationRevision.incrementAndGet()
        // Adapter close can cancel blocking requests; keep it off the UI thread.
        CoroutineScope(io).launch { diagnostics.cleanup(FailureStage.CATALOG_CLOSE) { catalog.close() } }
        if (pendingMutation?.isCompleted != false) writes.cancel()
    }
}
