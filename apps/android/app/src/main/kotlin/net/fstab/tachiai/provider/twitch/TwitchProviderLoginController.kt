package net.fstab.tachiai.provider.twitch

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class ProviderLoginOperation { READ, CONNECT, REVALIDATE, FORGET }

// Availability is a local storage observation, not proof of current provider acceptance.
// No token, lease, account identity or response enters observable/saved UI state.
internal data class TwitchProviderLoginState(
    val storage: SavedAuthorizationState? = null,
    val expiresAtMs: Long? = null,
    val operation: ProviderLoginOperation? = null,
    val phase: DeviceAuthPhase? = null,
    val activation: DeviceActivation? = null,
    val message: String? = null,
) {
    val busy get() = operation != null
}

// UI-thread commands; only the injected worker dispatcher accesses protected storage or HTTP.
// The fixed LOCAL repository is also used by playback and the retained diagnostic cases.
internal class TwitchProviderLoginController(
    private val cache: TwitchSavedAuthorization,
    private val scope: CoroutineScope,
    private val transportFactory: (() -> Boolean) -> TwitchDeviceTransport,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val waitMs: suspend (Long) -> Unit = { delay(it) },
    initiallyForeground: Boolean = true,
) : AutoCloseable {
    init { require(cache.profile == TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL) }
    private val foreground = DeviceAuthorizationForeground(initiallyForeground)
    private val revision = AtomicLong()
    private val transport = AtomicReference<TwitchDeviceTransport?>()
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(TwitchProviderLoginState())
    val state: StateFlow<TwitchProviderLoginState> = mutableState
    private var job: Job? = null
    private data class ForgetResult(val outcome: SavedAuthorizationState, val storage: SavedAuthorizationState, val expiresAtMs: Long?)
    @Volatile private var pendingForget: Deferred<ForgetResult>? = null
    @Volatile private var closed = false

    private fun current(attempt: Long) = !closed && revision.get() == attempt
    private fun publish(attempt: Long, change: (TwitchProviderLoginState) -> TwitchProviderLoginState) = synchronized(stateLock) {
        if (current(attempt)) mutableState.value = change(mutableState.value)
    }
    private fun readSummary(): Pair<SavedAuthorizationState, Long?> {
        val loaded = cache.read()
        return loaded.state to loaded.lease?.record?.expiresAtMs
    }
    private fun register(attempt: Long, request: TwitchDeviceTransport) = synchronized(stateLock) {
        if (!current(attempt)) { request.close(); throw CancellationException() }
        transport.set(request)
    }
    private fun start(operation: ProviderLoginOperation, action: suspend (Long) -> Unit) {
        if (closed || job != null || !foreground.isForeground ||
            (operation != ProviderLoginOperation.FORGET && pendingForget?.isCompleted == false)) return
        val attempt = revision.incrementAndGet()
        publish(attempt) { it.copy(operation = operation,
            phase = if (operation == ProviderLoginOperation.READ) it.phase else null,
            activation = null, message = if (operation == ProviderLoginOperation.READ) it.message else null) }
        val worker = scope.launch(start = CoroutineStart.LAZY) {
            try { action(attempt) }
            catch (_: CancellationException) { /* Cancellation owns status and invalidates publication. */ }
            catch (_: Exception) {
                publish(attempt) { it.copy(message = "Twitch login could not be read or updated. Nothing was replaced automatically.") }
            } finally {
                if (current(attempt)) {
                    transport.getAndSet(null)?.close()
                    job = null
                    publish(attempt) { it.copy(operation = null, activation = null) }
                }
            }
        }
        job = worker
        worker.start()
    }

    fun refresh() = start(ProviderLoginOperation.READ) { attempt ->
        val summary = withContext(io) { readSummary() }
        publish(attempt) { it.copy(storage = summary.first, expiresAtMs = summary.second) }
    }

    fun connect() = start(ProviderLoginOperation.CONNECT) { attempt ->
        val result = withContext(io) {
            val saveRevision = cache.revision()
            var saved: SavedAuthorizationState? = null
            val request = transportFactory { current(attempt) && foreground.isForeground }
            register(attempt, request)
            try {
                val phase = authorizeTwitchDevice(cache.profile.clientId, request,
                    onPhase = { next -> publish(attempt) { it.copy(phase = next,
                        activation = it.activation.takeUnless { next.terminal }) } },
                    onActivation = { prompt -> publish(attempt) { it.copy(activation = prompt) } },
                    foreground = foreground, clockMs = clockMs, waitMs = waitMs,
                    providerProfile = cache.profile, retainSmartTvLocally = true,
                    onProviderClientValidated = { token, deadline ->
                        publish(attempt) { it.copy(activation = null) }
                        if (current(attempt) && foreground.isForeground)
                            saved = cache.saveValidated(token, deadline, saveRevision)
                    })
                Triple(phase, saved, readSummary())
            } finally { transport.compareAndSet(request, null); request.close() }
        }
        publish(attempt) { it.copy(phase = result.first, storage = result.third.first, expiresAtMs = result.third.second,
            message = if (result.second == SavedAuthorizationState.SAVED) "Twitch login saved on this device."
                else if (result.first == DeviceAuthPhase.SUCCEEDED) "Twitch approved the login, but it could not be saved. Reconnect to retry."
                else null) }
    }

    fun revalidate() = start(ProviderLoginOperation.REVALIDATE) { attempt ->
        val result = withContext(io) {
            val deadline = clockMs() + SMART_TV_LIFETIME_INSPECTION_MS
            val request = transportFactory { current(attempt) && foreground.isForeground && clockMs() < deadline }
            register(attempt, request)
            try {
                val checked = extendSavedTwitchLocalRetention(cache, request, foreground, clockMs)
                checked.outcome to readSummary()
            } finally { transport.compareAndSet(request, null); request.close() }
        }
        publish(attempt) { it.copy(storage = result.second.first, expiresAtMs = result.second.second,
            message = when (result.first) {
                LocalRetentionExtensionOutcome.SAVED -> "Twitch accepted the saved login. Local retention was updated."
                LocalRetentionExtensionOutcome.NO_TOKEN -> "No saved Twitch login. Connect to continue."
                LocalRetentionExtensionOutcome.EXPIRED -> "The saved Twitch login expired. Reconnect to continue."
                LocalRetentionExtensionOutcome.VALIDATION_REJECTED -> "Twitch rejected the saved login. Reconnect to continue."
                LocalRetentionExtensionOutcome.NETWORK_FAILED -> "Could not reach Twitch. The saved login was not extended."
                LocalRetentionExtensionOutcome.SUPERSEDED -> "The saved login changed. Retry with the current login."
                else -> "The saved login could not be read or updated. Reconnect to retry."
            }) }
    }

    fun forget() {
        if (closed || !foreground.isForeground || pendingForget?.isCompleted == false) return
        cancel()
        // Accept the bounded local clear independently of the editor's scope.
        // Cancellation may stop observing it, but cannot abandon it behind an
        // already-entered save. The repository lock orders that save before clear.
        val clear = CoroutineScope(io).async(start = CoroutineStart.LAZY) {
            val outcome = cache.forget()
            val summary = readSummary()
            ForgetResult(outcome, summary.first, summary.second)
        }
        pendingForget = clear
        observeForget(clear)
        clear.invokeOnCompletion {
            if (!closed) scope.launch {
                if (!closed && job == null && state.value.operation == ProviderLoginOperation.FORGET) observeForget(clear)
            }
        }
        // Await may start this lazy worker; explicitly start it too so a
        // cancelled UI scope cannot prevent the already-accepted clear.
        clear.start()
    }

    private fun observeForget(clear: Deferred<ForgetResult>) = start(ProviderLoginOperation.FORGET) { attempt ->
        val result = clear.await()
        publish(attempt) { it.copy(storage = result.storage, expiresAtMs = result.expiresAtMs,
            message = if (result.outcome == SavedAuthorizationState.FORGOTTEN) "Twitch login forgotten on this device."
                else "The saved login could not be cleared. Retry Forget before using playback.") }
    }

    fun setForeground(value: Boolean) {
        foreground.setForeground(value)
        if (!value && state.value.operation != ProviderLoginOperation.CONNECT && job != null) cancel()
        if (value && job == null) {
            val clear = pendingForget
            if (clear != null && state.value.operation == ProviderLoginOperation.FORGET) observeForget(clear) else refresh()
        }
    }

    fun cancel(phase: DeviceAuthPhase = DeviceAuthPhase.CANCELLED) {
        synchronized(stateLock) {
            revision.incrementAndGet()
            job?.cancel(); job = null
            transport.getAndSet(null)?.close()
            val clearing = pendingForget?.isCompleted == false
            mutableState.value = mutableState.value.copy(
                operation = if (clearing) ProviderLoginOperation.FORGET else null,
                activation = null, phase = if (clearing) null else phase,
                message = if (clearing) "Forget is still clearing the saved login on this device."
                    else "Login action cancelled. Reconnect needs a new activation code.")
        }
    }

    override fun close() {
        closed = true
        foreground.setForeground(false)
        cancel()
    }
}
