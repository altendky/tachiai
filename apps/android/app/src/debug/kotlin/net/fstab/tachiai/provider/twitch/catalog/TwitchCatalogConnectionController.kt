package net.fstab.tachiai.provider.twitch.catalog

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.fstab.tachiai.provider.twitch.*
import net.fstab.tachiai.platform.diagnostics.*

internal class TwitchCatalogConnectionOwner(val instanceId: String, val name: String,
    val routeTitle: String, private val ownershipKey: String, val routeUsable: Boolean = true) {
    fun sameOwnership(other: TwitchCatalogConnectionOwner) = instanceId == other.instanceId &&
        routeUsable == other.routeUsable && ownershipKey == other.ownershipKey
    override fun toString() = "TwitchCatalogConnectionOwner(redacted)"
}

// Construction and all binding methods are worker-only. The Android binding owns
// the captured saved route; transport checks that owner again on every request.
internal interface TwitchCatalogConnectionBinding : AutoCloseable {
    fun owner(): TwitchCatalogConnectionOwner
    fun session(): TwitchCatalogSession
    fun transport(canRequest: () -> Boolean): TwitchCatalogTransport
    // UI admission only. Implementations must not read stores or open routes.
    fun canPublishLocally(): Boolean = true
}

internal enum class TwitchCatalogConnectionOperation { READ, CONNECT, VALIDATE, FORGET }
internal data class TwitchCatalogConnectionState(
    val name: String? = null, val routeTitle: String? = null,
    val ready: Boolean = false, val hasSavedGrant: Boolean = false, val canForget: Boolean = false,
    val status: String = "Reading catalog account…", val operation: TwitchCatalogConnectionOperation? = null,
    val phase: DeviceAuthPhase? = null, val activation: DeviceActivation? = null, val message: String? = null,
) { val busy get() = operation != null }

// Accepted Forget survives a closing controller and gates a recreated activity
// and return to the picker. The durable store handles cross-process generations.
internal object TwitchCatalogConnectionWrites {
    private val lock = Any()
    private val pending = mutableMapOf<String, MutableSet<Job>>()
    private val failed = mutableSetOf<String>()
    fun track(id: String, job: Job) {
        synchronized(lock) { pending.getOrPut(id) { mutableSetOf() }.add(job) }
        job.invokeOnCompletion { synchronized(lock) {
            pending[id]?.let { it.remove(job); if (it.isEmpty()) pending.remove(id) }
        } }
    }
    fun markFailure(id: String, failure: Boolean) = synchronized(lock) {
        if (failure) failed.add(id) else failed.remove(id)
        Unit
    }
    fun isFailed(id: String) = synchronized(lock) { id in failed }
    fun isPending(id: String) = synchronized(lock) { pending[id]?.isNotEmpty() == true }
    suspend fun await(id: String) {
        while (true) {
            val jobs = synchronized(lock) { pending[id]?.toList().orEmpty() }
            if (jobs.isEmpty()) return
            jobs.forEach { it.join() }
        }
    }
}

internal class TwitchCatalogConnectionController(
    private val binding: TwitchCatalogConnectionBinding,
    private val owner: TwitchCatalogConnectionOwner,
    private val session: TwitchCatalogSession,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val waitMs: suspend (Long) -> Unit = { delay(it) },
    initiallyForeground: Boolean = false,
    private val diagnostics: FailureReporter = FailureReporter.NONE,
) : AutoCloseable {
    private val lock = Any()
    private val mutable = MutableStateFlow(TwitchCatalogConnectionState(owner.name, owner.routeTitle))
    val state: StateFlow<TwitchCatalogConnectionState> = mutable
    private val foreground = DeviceAuthorizationForeground(false)
    private val revision = AtomicLong()
    private val active = AtomicReference<TwitchCatalogTransport?>()
    private var operation: Job? = null
    private var resumeJob: Job? = null
    private var hourly: Job? = null
    @Volatile private var closed = false
    @Volatile private var desiredForeground = initiallyForeground
    @Volatile private var ownerBlocked = false

    init { session.setForeground(false); if (initiallyForeground) setForeground(true) }
    private fun publish(change: (TwitchCatalogConnectionState) -> TwitchCatalogConnectionState) = synchronized(lock) {
        if (!closed) mutable.value = change(mutable.value)
    }
    private fun matchesOwner(): Boolean = try { owner.sameOwnership(binding.owner()) }
        catch (error: Exception) { diagnostics.report(FailureStage.CATALOG_AUTH_OWNER, error); throw error }
    private fun current(token: Long) = !closed && revision.get() == token
    private fun summary(value: TwitchCatalogSessionSummary, token: Long? = null) {
        if (token != null && !current(token)) return
        if (value.state == TwitchCatalogSessionState.STORAGE_FAILURE) diagnostics.report(FailureStage.CATALOG_AUTH_STORAGE)
        val saved = value.state != TwitchCatalogSessionState.MISSING
        publish { it.copy(ready = owner.routeUsable && foreground.isForeground && value.state != TwitchCatalogSessionState.STORAGE_FAILURE &&
                !TwitchCatalogConnectionWrites.isFailed(owner.instanceId),
            hasSavedGrant = saved, canForget = saved || TwitchCatalogConnectionWrites.isFailed(owner.instanceId),
            status = if (!owner.routeUsable) "Saved route unavailable. Catalog access is blocked; local Forget remains available." else when (value.state) {
                TwitchCatalogSessionState.MISSING -> "No saved catalog account."
                TwitchCatalogSessionState.CONNECTED -> "Catalog account validated for this foreground session."
                TwitchCatalogSessionState.UNVERIFIED, TwitchCatalogSessionState.PAUSED -> "Saved catalog account needs validation."
                TwitchCatalogSessionState.TEMPORARY_FAILURE -> "Catalog account unavailable temporarily. Check the saved route and retry."
                TwitchCatalogSessionState.STORAGE_FAILURE -> "Catalog account storage failed. Access remains blocked; retry Forget or reopen."
                else -> "Catalog account needs reconnection."
            }) }
    }
    fun setForeground(value: Boolean) {
        if (closed) return
        desiredForeground = value
        foreground.setForeground(false)
        session.setForeground(false)
        active.get()?.cancelActiveRequest()
        resumeJob?.cancel(); hourly?.cancel()
        publish { it.copy(ready = false, activation = if (it.operation == TwitchCatalogConnectionOperation.CONNECT) it.activation else null) }
        if (!value || ownerBlocked) {
            if (state.value.operation != TwitchCatalogConnectionOperation.CONNECT) {
                cancelOperation()
                publish { it.copy(operation = null, activation = null) }
            }
            return
        }
        resumeJob = scope.launch {
            try {
                withContext(io) {
                    TwitchCatalogConnectionWrites.await(owner.instanceId)
                    check(matchesOwner())
                }
                if (closed || !desiredForeground) return@launch
                foreground.setForeground(true); session.setForeground(true)
                if (TwitchCatalogConnectionWrites.isFailed(owner.instanceId)) {
                    publish { it.copy(canForget = true, ready = false,
                        status = "Catalog account could not be cleared. Access remains blocked. Retry Forget.") }
                } else if (!owner.routeUsable) {
                    val saved = withContext(io) { session.readSummary() }
                    summary(saved)
                } else if (state.value.operation != TwitchCatalogConnectionOperation.CONNECT) revalidate()
                hourly = scope.launch {
                    while (isActive) { delay(60 * 60 * 1000L); if (foreground.isForeground && !state.value.busy) revalidate() }
                }
            } catch (_: CancellationException) { }
            catch (error: Exception) {
                diagnostics.report(FailureStage.CATALOG_AUTH_OWNER, error)
                cancelOperation()
                publish { it.copy(ready = false, canForget = true, operation = null, activation = null,
                    message = "Saved provider instance or route changed or could not be read. Close and reopen this connection.") }
            }
        }
    }
    fun connect() = start(TwitchCatalogConnectionOperation.CONNECT) { token ->
        val attempt = session.beginConnection()
        val request = binding.transport { current(token) && foreground.isForeground }
        active.set(request)
        val result = try { requestTwitchCatalogAuthorization(request, foreground, clockMs, waitMs,
            onPhase = { phase -> if (current(token)) publish { it.copy(phase = phase) } },
            onActivation = { activation -> if (current(token)) publish { it.copy(activation = activation) } }) }
        finally { active.compareAndSet(request, null) }
        if (result is TwitchCatalogAuthorizationResult.Approved) {
            check(current(token) && foreground.isForeground && matchesOwner())
            val accepted = session.accept(result, attempt)
            try { check(matchesOwner()) }
            catch (error: Exception) {
                diagnostics.report(FailureStage.CATALOG_AUTH_OWNER, error)
                ownerBlocked = true
                foreground.setForeground(false); session.setForeground(false)
                publish { it.copy(ready = false, canForget = true,
                    status = "Catalog access is blocked until this connection is reopened.") }
                throw error
            }
            summary(accepted.summary, token)
        } else diagnostics.report(FailureStage.CATALOG_AUTH_REQUEST)
    }
    fun revalidate() = start(TwitchCatalogConnectionOperation.VALIDATE) { token ->
        val result = session.validate(force = true)
        if (result.summary.state !in setOf(TwitchCatalogSessionState.CONNECTED, TwitchCatalogSessionState.MISSING,
                TwitchCatalogSessionState.PAUSED)) diagnostics.report(FailureStage.CATALOG_AUTH_REQUEST)
        summary(result.summary, token)
    }
    private fun start(kind: TwitchCatalogConnectionOperation, work: suspend (Long) -> Unit) {
        if (closed || !owner.routeUsable || !foreground.isForeground || state.value.busy || TwitchCatalogConnectionWrites.isFailed(owner.instanceId)) return
        val token = revision.incrementAndGet()
        publish { it.copy(operation = kind, phase = if (kind == TwitchCatalogConnectionOperation.CONNECT) null else it.phase,
            activation = null, message = null) }
        operation = scope.launch(io) {
            try {
                TwitchCatalogConnectionWrites.await(owner.instanceId)
                check(matchesOwner())
                summary(session.readSummary(), token)
                check(current(token) && foreground.isForeground)
                work(token)
            } catch (_: CancellationException) { }
            catch (error: Exception) {
                diagnostics.report(FailureStage.CATALOG_AUTH_REQUEST, error)
                if (current(token)) publish { it.copy(message = "Catalog action could not complete. Check the saved route and reopen or retry.") }
            }
            finally { if (current(token)) publish { it.copy(operation = null, activation = null) } }
        }
    }
    fun forget() {
        if (closed || TwitchCatalogConnectionWrites.isPending(owner.instanceId)) return
        cancelOperation(); session.invalidate()
        publish { it.copy(operation = TwitchCatalogConnectionOperation.FORGET, ready = false, activation = null, message = null) }
        val write = CoroutineScope(SupervisorJob() + io).launch(start = CoroutineStart.LAZY) {
            var failed = true
            try {
                val result = session.forget()
                failed = result.summary.state != TwitchCatalogSessionState.MISSING
                if (failed) diagnostics.report(FailureStage.CATALOG_AUTH_STORAGE)
                TwitchCatalogConnectionWrites.markFailure(owner.instanceId, failed)
                summary(result.summary)
            } catch (error: Exception) {
                diagnostics.report(FailureStage.CATALOG_AUTH_STORAGE, error)
                TwitchCatalogConnectionWrites.markFailure(owner.instanceId, true)
            }
            finally { publish { it.copy(operation = null, ready = owner.routeUsable && !failed && foreground.isForeground,
                canForget = failed, message = if (failed) "Catalog account could not be cleared. Access remains blocked. Retry Forget." else "Catalog account forgotten on this device.") } }
        }
        TwitchCatalogConnectionWrites.track(owner.instanceId, write); write.start()
    }
    private fun cancelOperation() {
        revision.incrementAndGet(); operation?.cancel(); operation = null
        active.getAndSet(null)?.let { transport ->
            runCatching { transport.close() }.onFailure { diagnostics.report(FailureStage.CATALOG_AUTH_CLEANUP, it) }
        }
    }
    fun cancel(phase: DeviceAuthPhase = DeviceAuthPhase.CANCELLED) {
        cancelOperation()
        publish { it.copy(operation = null, phase = phase, activation = null) }
    }
    override fun close() {
        if (closed) return
        closed = true; desiredForeground = false; foreground.setForeground(false); session.setForeground(false)
        resumeJob?.cancel(); hourly?.cancel(); cancelOperation()
        CoroutineScope(SupervisorJob() + io).launch {
            TwitchCatalogConnectionWrites.await(owner.instanceId)
            runCatching { session.close() }.onFailure { diagnostics.report(FailureStage.CATALOG_AUTH_CLEANUP, it) }
            runCatching { binding.close() }.onFailure { diagnostics.report(FailureStage.CATALOG_AUTH_CLEANUP, it) }
        }
    }
}
