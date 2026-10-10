package net.fstab.tachiai.feature.connections

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*

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
    init {
        require(catalog.instanceId == instance.id && catalog.providerId ==
            ProviderId(instance.service.name.lowercase(java.util.Locale.ROOT)))
    }
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(StreamManagementState(instance, catalogNotice = notice))
    val state: StateFlow<StreamManagementState> = mutableState
    private val browseRevision = AtomicLong()
    private val mutationRevision = AtomicLong()
    private val writes = CoroutineScope(SupervisorJob() + io)
    private var browseJob: Job? = null
    private var lastLookup: String? = null
    @Volatile private var closed = false
    @Volatile var pendingMutation: Job? = null
        private set

    private fun publish(change: (StreamManagementState) -> StreamManagementState) = synchronized(stateLock) {
        if (!closed) mutableState.value = change(mutableState.value)
    }
    private fun current(revision: Long) = !closed && browseRevision.get() == revision
    private fun cancelBrowse() { browseRevision.incrementAndGet(); browseJob?.cancel(); browseJob = null }
    private fun valid(entries: List<CatalogEntry>): List<CatalogEntry> = entries.also {
        check(it.all { entry -> entry.resource.providerId == catalog.providerId })
    }

    fun load() {
        if (closed || state.value.saving) return
        cancelBrowse()
        val revision = browseRevision.get()
        publish { it.copy(loading = true, storageFailed = false, capabilities = null, message = null, failure = null) }
        browseJob = scope.launch {
            var stage = FailureStage.CONFIGURED_SOURCES_READ
            try {
                StreamManagementWrites.await(instance.id)
                val configured = withContext(io) { store.read(legacy) }
                if (!current(revision)) return@launch
                publish { it.copy(configured = configured) }
                stage = FailureStage.CATALOG_LOAD
                val capabilities = withContext(io) { catalog.capabilities() }
                if (!current(revision)) return@launch
                publish { it.copy(capabilities = capabilities) }
                browseCurrent(revision, state.value.query.copy(cursor = null), false)
            } catch (_: CancellationException) {
                // Query cancellation never changes persisted configured entries.
            } catch (error: Exception) {
                if (current(revision)) {
                    val storage = stage == FailureStage.CONFIGURED_SOURCES_READ
                    diagnostics.report(stage, error)
                    publish { it.copy(storageFailed = storage, failure = if (storage) null else
                        CatalogResult.Failure(CatalogFailure.TEMPORARY),
                        message = if (storage) "Configured streams could not be read. Nothing was replaced. Retry to continue." else null) }
                }
            } finally { if (current(revision)) publish { it.copy(loading = false) } }
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
                publish { it.copy(entries = (if (append) it.entries + entries else entries).distinctBy { entry -> entry.resource },
                    nextCursor = result.value.nextCursor, failure = null) }
            }
            is CatalogResult.Failure -> publish { it.copy(failure = result) }
        }
    }

    private fun browse(query: CatalogQuery, append: Boolean = false) {
        if (closed || state.value.saving || state.value.configured == null || state.value.storageFailed) return
        lastLookup = null
        cancelBrowse()
        val revision = browseRevision.get()
        publish { it.copy(query = query.copy(cursor = null), loading = true, failure = null, message = null,
            entries = if (append) it.entries else emptyList(), nextCursor = if (append) it.nextCursor else null) }
        browseJob = scope.launch {
            try { browseCurrent(revision, query, append) }
            catch (_: CancellationException) { }
            catch (error: Exception) {
                if (current(revision)) { diagnostics.report(FailureStage.CATALOG_LOAD, error)
                    publish { it.copy(failure = CatalogResult.Failure(CatalogFailure.TEMPORARY)) } }
            } finally { if (current(revision)) publish { it.copy(loading = false) } }
        }
    }
    fun all() = browse(CatalogQuery())
    fun search(text: String) {
        val query = runCatching { state.value.query.copy(search = text.trim().ifEmpty { null }, cursor = null) }.getOrNull()
        if (query == null) publish { it.copy(failure = CatalogResult.Failure(CatalogFailure.INVALID_INPUT)) } else browse(query)
    }
    fun collection(id: String) = browse(CatalogQuery(collectionId = id))
    fun children(resource: CatalogResource) {
        if (resource.providerId != catalog.providerId) { publish { it.copy(failure = CatalogResult.Failure(CatalogFailure.INVALID_INPUT)) }; return }
        browse(CatalogQuery(parent = resource))
    }
    fun more() { if (!state.value.loading) state.value.nextCursor?.let { browse(state.value.query.copy(cursor = it), true) } }
    fun retry() {
        if (closed || state.value.loading || state.value.saving) return
        state.value.failure?.retryAtEpochMs?.let { if (clockMs() < it) return }
        if (state.value.storageFailed || state.value.configured == null || state.value.capabilities == null) load()
        else lastLookup?.let(::lookup) ?: browse(state.value.query)
    }

    fun lookup(input: String) {
        if (closed || state.value.loading || state.value.saving || state.value.configured == null || state.value.storageFailed) return
        if (input.length !in 1..2048 || input.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) {
            publish { it.copy(failure = CatalogResult.Failure(CatalogFailure.INVALID_INPUT)) }; return
        }
        cancelBrowse(); val revision = browseRevision.get()
        lastLookup = input
        publish { it.copy(loading = true, failure = null, entries = emptyList(), nextCursor = null, message = null) }
        browseJob = scope.launch {
            try {
                val permission = state.value.capabilities?.lookup ?: CatalogAccess.NOT_VERIFIED
                val result = if (permission == CatalogAccess.AVAILABLE) withContext(io) { catalog.lookup(input) } else unavailable(permission)
                if (current(revision)) when (result) {
                    is CatalogResult.Value -> publish { it.copy(entries = valid(listOf(result.value))) }
                    is CatalogResult.Failure -> publish { it.copy(failure = result) }
                }
            } catch (_: CancellationException) { }
            catch (error: Exception) { if (current(revision)) { diagnostics.report(FailureStage.CATALOG_LOAD, error)
                publish { it.copy(failure = CatalogResult.Failure(CatalogFailure.TEMPORARY)) } } }
            finally { if (current(revision)) publish { it.copy(loading = false) } }
        }
    }

    private fun mutate(action: () -> List<ConfiguredSource>) {
        if (closed || state.value.loading || state.value.saving || state.value.configured == null || state.value.storageFailed) return
        val revision = mutationRevision.incrementAndGet()
        publish { it.copy(saving = true, message = null) }
        // Accepted local writes survive Activity pause/destruction. Closing this
        // controller invalidates UI publication, not the independent write job.
        val job = writes.launch(start = CoroutineStart.LAZY) {
            try {
                val configured = action()
                if (!closed && mutationRevision.get() == revision) publish { it.copy(configured = configured,
                    message = "Configured streams saved on this device.") }
            } catch (error: Exception) {
                diagnostics.report(FailureStage.CONFIGURED_SOURCES_WRITE, error)
                if (!closed && mutationRevision.get() == revision)
                    publish { it.copy(message = "Configured streams could not be saved. Retry; the list was not replaced.") }
            } finally {
                if (!closed && mutationRevision.get() == revision) publish { it.copy(saving = false) }
                if (closed) writes.cancel()
            }
        }
        pendingMutation = job; StreamManagementWrites.track(instance.id, job); job.start()
    }
    fun add(entry: CatalogEntry) {
        if (entry.resource.providerId != catalog.providerId) { publish { it.copy(failure = CatalogResult.Failure(CatalogFailure.INVALID_INPUT)) }; return }
        mutate { store.add(entry, legacy) }
    }
    fun remove(id: String) = mutate { store.remove(id, legacy) }
    fun move(id: String, delta: Int) = mutate { store.move(id, delta, legacy) }
    override fun close() {
        synchronized(stateLock) { if (closed) return; closed = true }
        cancelBrowse(); mutationRevision.incrementAndGet()
        // Adapter close can cancel blocking requests; keep it off the UI thread.
        CoroutineScope(io).launch { diagnostics.cleanup(FailureStage.CATALOG_CLOSE) { catalog.close() } }
        if (pendingMutation?.isCompleted != false) writes.cancel()
    }
}
