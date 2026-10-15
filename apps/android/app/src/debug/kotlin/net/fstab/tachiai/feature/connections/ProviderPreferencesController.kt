package net.fstab.tachiai.feature.connections

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.fstab.tachiai.presentation.*

internal data class ProviderPreferenceState(
    val instance: ProviderInstance,
    val instances: List<ProviderInstance>,
    val nameDraft: String = instance.customName.orEmpty(),
    val selectedRoute: SourceRouteChoice? = instance.setup.route,
    val saving: Boolean = false,
    val error: String? = null,
    val validName: Boolean = true,
)

// Accepted writes belong to the process rather than a settings Activity. A new
// editor/list waits for them before reading, including after its old host closes.
internal object ProviderPreferenceWrites {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob())
    private val jobs = mutableMapOf<String, MutableSet<Job>>()
    private val tails = mutableMapOf<String, Job>()

    internal fun enqueue(id: String, io: CoroutineDispatcher, action: () -> Unit): Job {
        val job = synchronized(lock) {
            val previous = tails[id]
            scope.launch(io, start = CoroutineStart.LAZY) { previous?.join(); action() }.also { next ->
                jobs.getOrPut(id) { mutableSetOf() }.add(next)
                tails[id] = next
                next.invokeOnCompletion { synchronized(lock) {
                    jobs[id]?.let { it.remove(next); if (it.isEmpty()) jobs.remove(id) }
                    if (tails[id] === next) tails.remove(id)
                } }
            }
        }
        job.start()
        return job
    }

    suspend fun await(id: String) {
        while (true) {
            val pending = synchronized(lock) { jobs[id]?.toList().orEmpty() }
            if (pending.isEmpty()) return
            pending.forEach { it.join() }
        }
    }

    suspend fun awaitAll() {
        while (true) {
            val pending = synchronized(lock) { jobs.values.flatMap { it.toList() } }
            if (pending.isEmpty()) return
            pending.forEach { it.join() }
        }
    }
}

internal class ProviderPreferencesController(
    initial: ProviderInstance,
    instances: List<ProviderInstance>,
    private val save: (String?, SourceRouteChoice) -> List<ProviderInstance>,
    private val dispatch: ((() -> Unit) -> Unit),
    private val onChangeStarted: () -> Unit,
    private val onCommitted: (ProviderInstance) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private data class Change(val name: String?, val useCommittedName: Boolean, val route: SourceRouteChoice)
    private val lock = Any()
    private val mutableState = MutableStateFlow(ProviderPreferenceState(initial, instances))
    val state: StateFlow<ProviderPreferenceState> = mutableState
    private var committed = initial
    private var pending = 0
    private var revision = 0L
    private var closed = false
    private var failedChange: Change? = null

    private fun normalizedName(draft: String): String? = draft.trim().ifEmpty { null }
    private fun validName(draft: String): Boolean {
        val name = normalizedName(draft) ?: committed.defaultName
        return validSourceSetupName(name) && state.value.instances.none {
            it.id != committed.id && it.service == committed.service && sameProviderInstanceName(it.name, name)
        }
    }

    fun nameChanged(draft: String) = synchronized(lock) {
        if (closed || draft == state.value.nameDraft) return@synchronized
        val valid = validName(draft)
        mutableState.value = state.value.copy(nameDraft = draft, validName = valid)
        val route = state.value.selectedRoute
        if (valid && route != null) accept(Change(normalizedName(draft), false, route))
    }

    fun routeChanged(route: SourceRouteChoice) = synchronized(lock) {
        require(route.mode != SourceRouteMode.SOURCE_DEFAULT)
        if (closed || (route == state.value.selectedRoute && state.value.error == null)) return@synchronized
        accept(Change(normalizedName(state.value.nameDraft), !state.value.validName, route))
    }

    fun retry() = synchronized(lock) {
        if (closed || pending != 0) return@synchronized
        failedChange?.let(::accept)
    }

    private fun accept(change: Change) {
        if (pending == 0 && !change.useCommittedName && change.name == committed.customName &&
            change.route == committed.setup.route && state.value.error == null) return
        val current = ++revision
        pending++
        failedChange = null
        mutableState.value = state.value.copy(selectedRoute = change.route, saving = true, error = null)
        onChangeStarted()
        ProviderPreferenceWrites.enqueue(committed.id, io) {
            val name = synchronized(lock) { if (change.useCommittedName) committed.customName else change.name }
            val result = runCatching { save(name, change.route).also { entries ->
                check(entries.single { it.id == committed.id }.service == committed.service)
            } }
            synchronized(lock) {
                result.onSuccess { entries ->
                    committed = entries.single { it.id == committed.id }
                    if (!closed) mutableState.value = state.value.copy(instance = committed, instances = entries)
                }
                pending--
                if (pending == 0) {
                    if (!closed) {
                        failedChange = if (result.isFailure) change else null
                        mutableState.value = state.value.copy(selectedRoute = committed.setup.route,
                            saving = false, error = if (result.isFailure) "Changes could not be saved. Retry." else null,
                            validName = validName(state.value.nameDraft))
                    }
                    val acknowledged = committed
                    // Also restore the durable route after a wholly failed batch;
                    // the error and retry draft remain visible in state.
                    if (!closed) dispatch { synchronized(lock) {
                        if (!closed && pending == 0 && revision == current) onCommitted(acknowledged)
                    } }
                }
            }
        }
    }

    override fun close() = synchronized(lock) { closed = true }
}
