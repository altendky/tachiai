package net.fstab.tachiai.platform.network

import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.presentation.MAX_PROVIDER_ROUTES

internal enum class ProviderStartupFailure {
    TRANSPORT, CONFIGURATION_UNAVAILABLE, CHOICE_REQUIRED, AUTHENTICATION, ENTITLEMENT, DRM, TLS,
    REGION, HTTP, RATE_LIMITED, MALFORMED, UNKNOWN, CANCELLED, DEADLINE, REVOKED, CLEANUP_UNCONFIRMED,
}

internal data class ProviderStartupRejection(val reason: ProviderStartupFailure,
    val transport: ProviderTransportEvidence? = null) {
    val retryable get() = reason == ProviderStartupFailure.CONFIGURATION_UNAVAILABLE ||
        reason == ProviderStartupFailure.TRANSPORT && transport?.retryableAvailability == true
}

// cancel only signals. Closing and joining resources belongs to the calling
// worker. The receipt must remain false after any unconfirmed close attempt.
internal interface ProviderStartupLease {
    fun cancel()
    fun closeAndConfirm(): Boolean
}

internal sealed interface ProviderStartupAttempt<out T> {
    class Admitted<T>(val value: T, val lease: ProviderStartupLease? = null) : ProviderStartupAttempt<T> {
        override fun toString() = "ProviderStartupAttempt.Admitted(redacted)"
    }
    data class Rejected(val failure: ProviderStartupRejection) : ProviderStartupAttempt<Nothing>
    data object RequiresMutation : ProviderStartupAttempt<Nothing>
}

internal sealed interface ProviderStartupResult<out T> {
    class Ready<T>(val owner: ProviderEffectiveRoute, val value: T) : ProviderStartupResult<T> {
        override fun toString() = "ProviderStartupResult.Ready(redacted)"
    }
    class HeldForMutation(val owner: ProviderEffectiveRoute) : ProviderStartupResult<Nothing> {
        override fun toString() = "ProviderStartupResult.HeldForMutation(redacted)"
    }
    data class Failed(val failure: ProviderStartupRejection) : ProviderStartupResult<Nothing>
}

internal enum class ProviderStartupPhase {
    IDLE, TRYING, CLEANING, SELECTED, HELD_FOR_MUTATION, FAILED, CANCELLED, EXPIRED, REVOKED, CLEANUP_UNCONFIRMED,
}
internal data class ProviderStartupProgress(val phase: ProviderStartupPhase, val ordinal: Int, val count: Int,
    val label: String? = null, val failure: ProviderStartupFailure? = null, val exhausted: Boolean = false)

internal class ProviderStartupAttemptContext internal constructor(
    val deadlineElapsedMs: Long,
    private val now: () -> Long,
    private val admitted: () -> Boolean,
    private val ownLease: (ProviderStartupLease) -> Unit,
) {
    val remainingMs get() = (deadlineElapsedMs - now()).coerceAtLeast(0)
    // Once an admitted/held owner outlives startup, its retained resources use
    // the scope's lifetime gate rather than the exhausted startup time budget.
    fun canContinue() = admitted()
    fun <T : ProviderStartupLease> own(lease: T): T = lease.also(ownLease)
    override fun toString() = "ProviderStartupAttemptContext(status only)"
}

// One per instance and runtime scope. This synchronous API runs on an existing
// provider worker; it serializes a first real read, not a backend/cache probe.
// Later consumers receive their own response on the sticky route. They never
// reuse another resource's response, nor automatically switch after admission.
internal class ProviderRouteStartup(
    private val pool: ProviderRoutePoolSnapshot,
    private val deadlineElapsedMs: Long,
    private val nowElapsedMs: () -> Long,
    private val isCurrent: () -> Boolean,
    private val onProgress: (ProviderStartupProgress) -> Unit = {},
    private val scheduleDeadline: (Long, () -> Unit) -> AutoCloseable = { delay, action ->
        val future = alarms.schedule(Runnable { action() }, delay.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        AutoCloseable { future.cancel(false) }
    },
    private val canPublishLocally: () -> Boolean = { true },
) : AutoCloseable {
    companion object {
        private val alarms = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "provider-startup-deadline").apply { isDaemon = true }
        }
    }
    private class Receipt(val lease: ProviderStartupLease) {
        var cancellationAttempted = false
        var cancellationFailed = false
        var cancellationDone: CompletableFuture<Unit>? = null
        var closeAttempted = false
        var confirmed = false
    }
    private val operation = Any()
    private val lock = Any()
    private val leases = IdentityHashMap<ProviderStartupLease, Receipt>()
    private val cleanupFailureObservers = mutableListOf<() -> Unit>()
    private val closed = AtomicBoolean()
    private var stopped: ProviderStartupRejection? = null
    private var exhausted = false
    @Volatile var selectedRoute: ProviderEffectiveRoute? = null
        private set
    @Volatile var admitted: Boolean = false
        private set
    @Volatile var progress = ProviderStartupProgress(ProviderStartupPhase.IDLE, 0, pool.candidates.size)
        private set
    val cleanupConfirmed get() = synchronized(lock) { leases.isEmpty() }

    init { require(pool.candidates.size <= MAX_PROVIDER_ROUTES && deadlineElapsedMs >= 0) }

    private fun publish(phase: ProviderStartupPhase, ordinal: Int = progress.ordinal, label: String? = progress.label,
        failure: ProviderStartupFailure? = null) {
        val snapshot = synchronized(lock) {
            val terminal = stopped?.reason
            ProviderStartupProgress(terminal?.let(::terminalPhase) ?: phase, ordinal, pool.candidates.size, label,
                terminal ?: failure, exhausted)
                .also { progress = it }
        }
        runCatching { onProgress(snapshot) }
    }

    private fun terminalPhase(reason: ProviderStartupFailure) = when (reason) {
        ProviderStartupFailure.CANCELLED -> ProviderStartupPhase.CANCELLED
        ProviderStartupFailure.DEADLINE -> ProviderStartupPhase.EXPIRED
        ProviderStartupFailure.REVOKED -> ProviderStartupPhase.REVOKED
        ProviderStartupFailure.CLEANUP_UNCONFIRMED -> ProviderStartupPhase.CLEANUP_UNCONFIRMED
        else -> ProviderStartupPhase.FAILED
    }

    private fun stop(failure: ProviderStartupRejection) {
        val actual = synchronized(lock) {
            if (failure.reason == ProviderStartupFailure.DEADLINE && selectedRoute != null) return
            if (stopped == null || failure.reason == ProviderStartupFailure.CLEANUP_UNCONFIRMED) stopped = failure
            checkNotNull(stopped)
        }
        publish(terminalPhase(actual.reason), failure = actual.reason)
        if (actual.reason == ProviderStartupFailure.CLEANUP_UNCONFIRMED) notifyCleanupFailure()
        signalCancellation()
    }

    private fun notifyCleanupFailure() {
        synchronized(lock) { cleanupFailureObservers.toList() }.forEach { runCatching(it) }
    }

    fun observeCleanupFailure(observer: () -> Unit) {
        val uncertain = synchronized(lock) {
            cleanupFailureObservers += observer
            stopped?.reason == ProviderStartupFailure.CLEANUP_UNCONFIRMED
        }
        if (uncertain) runCatching(observer)
    }

    private fun signalCancellation() {
        val pending = synchronized(lock) {
            leases.values.filter { !it.cancellationAttempted && !it.closeAttempted }.onEach {
                it.cancellationAttempted = true; it.cancellationDone = CompletableFuture()
            }.toList()
        }
        pending.forEach { receipt ->
            try {
                if (runCatching { receipt.lease.cancel() }.isFailure) {
                    synchronized(lock) { receipt.cancellationFailed = true; stopped = ProviderStartupRejection(ProviderStartupFailure.CLEANUP_UNCONFIRMED) }
                    publish(ProviderStartupPhase.CLEANUP_UNCONFIRMED, failure = ProviderStartupFailure.CLEANUP_UNCONFIRMED)
                    notifyCleanupFailure()
                }
            } finally {
                checkNotNull(receipt.cancellationDone).complete(Unit)
            }
        }
    }

    fun cancel() = stop(ProviderStartupRejection(ProviderStartupFailure.CANCELLED))
    fun revoke() = stop(ProviderStartupRejection(ProviderStartupFailure.REVOKED))
    private fun failure() = synchronized(lock) { stopped }
    private fun memoryCurrent(): Boolean {
        if (failure() != null) return false
        // This predicate is a cheap process-memory quarantine gate. Protected
        // pool/profile reconciliation remains confined to current() on workers.
        if (!runCatching(canPublishLocally).getOrDefault(false)) {
            stop(ProviderStartupRejection(ProviderStartupFailure.CLEANUP_UNCONFIRMED)); return false
        }
        if (selectedRoute == null && nowElapsedMs() >= deadlineElapsedMs) { stop(ProviderStartupRejection(ProviderStartupFailure.DEADLINE)); return false }
        return true
    }
    private fun current(): Boolean {
        if (!memoryCurrent()) return false
        if (!runCatching(isCurrent).getOrDefault(false)) { revoke(); return false }
        return memoryCurrent()
    }
    fun canContinue() = memoryCurrent()
    fun checkCurrent() = current()

    private fun bind(owner: ProviderEffectiveRoute, validated: Boolean): Boolean {
        val accepted = synchronized(lock) {
            if (stopped != null || selectedRoute == null && nowElapsedMs() >= deadlineElapsedMs) false
            else { selectedRoute = owner; admitted = admitted || validated; true }
        }
        if (!accepted && failure() == null) stop(ProviderStartupRejection(ProviderStartupFailure.DEADLINE))
        return accepted
    }

    private fun own(lease: ProviderStartupLease) {
        synchronized(lock) { leases.getOrPut(lease) { Receipt(lease) } }
        if (failure() != null) signalCancellation()
        // Allocation/registration is worker-only, including a stale callback.
        // Such a callback must finish its late resource's cleanup rather than
        // stranding it after a prior retirement already received its receipt.
        if (closed.get()) synchronized(operation) { cleanup(synchronized(lock) { listOfNotNull(leases[lease]) }) }
    }

    private fun cleanup(values: List<Receipt>): Boolean {
        if (values.isNotEmpty()) publish(ProviderStartupPhase.CLEANING)
        var confirmed = true
        values.forEach { receipt ->
            if (!receipt.closeAttempted) {
                val cancellation = synchronized(lock) { receipt.closeAttempted = true; receipt.cancellationDone }
                // A worker waits for an already-issued signal before freeing
                // its native handle. A lifecycle cancel never waits for close.
                cancellation?.join()
                receipt.confirmed = runCatching { receipt.lease.closeAndConfirm() }.getOrDefault(false) &&
                    synchronized(lock) { !receipt.cancellationFailed }
            }
            if (!receipt.confirmed) confirmed = false
            else synchronized(lock) { leases.remove(receipt.lease) }
        }
        if (!confirmed) stop(ProviderStartupRejection(ProviderStartupFailure.CLEANUP_UNCONFIRMED))
        return confirmed
    }

    // Known local auth/bootstrap mutation holds the configured first candidate
    // without claiming that any provider response admitted it.
    fun holdFirstForMutation(): ProviderStartupResult<Nothing> = synchronized(operation) {
        val proof = selectedRoute?.proof ?: pool.candidates.firstOrNull()?.proof
        if (proof != null) return@synchronized holdForMutation(proof)
        if (!current()) { cleanup(synchronized(lock) { leases.values.toList() }); return@synchronized ProviderStartupResult.Failed(checkNotNull(failure())) }
        stop(ProviderStartupRejection(ProviderStartupFailure.CHOICE_REQUIRED))
        ProviderStartupResult.Failed(checkNotNull(failure()))
    }

    // Recreated transaction scopes may hold an exact candidate from a matched
    // immutable handoff pool. This is not a validated HTTP-success claim.
    fun holdForMutation(expectedProof: String): ProviderStartupResult<Nothing> = synchronized(operation) {
        if (!current()) { cleanup(synchronized(lock) { leases.values.toList() }); return@synchronized ProviderStartupResult.Failed(checkNotNull(failure())) }
        if (selectedRoute?.let { it.proof != expectedProof } == true) {
            revoke(); cleanup(synchronized(lock) { leases.values.toList() })
            return@synchronized ProviderStartupResult.Failed(checkNotNull(failure()))
        }
        val owner = runCatching { pool.resolve(expectedProof) }.getOrElse { error ->
            val reason = if ((error as? ProviderRoutePoolException)?.reason == ProviderRoutePoolFailure.PROFILE_MISSING)
                ProviderStartupFailure.CONFIGURATION_UNAVAILABLE else ProviderStartupFailure.REVOKED
            stop(ProviderStartupRejection(reason))
            return@synchronized ProviderStartupResult.Failed(checkNotNull(failure()))
        }
        if (!current()) return@synchronized ProviderStartupResult.Failed(checkNotNull(failure()))
        if (!bind(owner, false)) return@synchronized ProviderStartupResult.Failed(checkNotNull(failure()))
        publish(ProviderStartupPhase.HELD_FOR_MUTATION, pool.candidates.indexOf(owner.candidate) + 1, owner.choice.title)
        if (!current()) { cleanup(synchronized(lock) { leases.values.toList() }); return@synchronized ProviderStartupResult.Failed(checkNotNull(failure())) }
        ProviderStartupResult.HeldForMutation(owner)
    }

    fun <T> runReadOnly(attempt: (ProviderEffectiveRoute, ProviderStartupAttemptContext) -> ProviderStartupAttempt<T>): ProviderStartupResult<T> =
        synchronized(operation) {
            if (!current()) { cleanup(synchronized(lock) { leases.values.toList() }); return@synchronized ProviderStartupResult.Failed(checkNotNull(failure())) }
            val alarm = if (selectedRoute == null) scheduleDeadline((deadlineElapsedMs - nowElapsedMs()).coerceAtLeast(0)) {
                if (selectedRoute == null) stop(ProviderStartupRejection(ProviderStartupFailure.DEADLINE))
            } else AutoCloseable {}
            try {
                val fixed = selectedRoute
                val candidates = if (fixed == null) pool.candidates.distinctBy { it.routeIdentity } else listOf(fixed.candidate)
                var last = ProviderStartupRejection(ProviderStartupFailure.CHOICE_REQUIRED)
                for (candidate in candidates) {
                    if (!current()) break
                    if (!candidate.available) { last = ProviderStartupRejection(ProviderStartupFailure.CONFIGURATION_UNAVAILABLE); continue }
                    val owner = pool.resolve(candidate.proof)
                    publish(ProviderStartupPhase.TRYING, pool.candidates.indexOf(candidate) + 1, candidate.choice.title)
                    if (!current()) break
                    val before = synchronized(lock) { leases.values.toList() }
                    val attemptActive = AtomicBoolean(true)
                    val context = ProviderStartupAttemptContext(if (fixed == null) deadlineElapsedMs else Long.MAX_VALUE,
                        nowElapsedMs, { attemptActive.get() && memoryCurrent() }, { lease ->
                            own(lease)
                            // Late allocation after a failed attempt's cleanup
                            // cannot be adopted by a newer winning candidate.
                            if (!attemptActive.get() && !closed.get()) stop(ProviderStartupRejection(ProviderStartupFailure.CLEANUP_UNCONFIRMED))
                        })
                    val result = try { attempt(owner, context) } catch (error: Throwable) {
                        val evidence = providerTransportEvidence(error)
                        ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.TRANSPORT, evidence))
                    }
                    if (result !is ProviderStartupAttempt.Admitted) attemptActive.set(false)
                    val created = synchronized(lock) { leases.values.filter { it !in before } }
                    val retain = (result as? ProviderStartupAttempt.Admitted)?.lease
                    val registered = retain == null || synchronized(lock) { leases.containsKey(retain) }
                    if (!registered) own(checkNotNull(retain))
                    val retainAllowed = registered && result is ProviderStartupAttempt.Admitted && current()
                    if (!cleanup(created.filterNot { retainAllowed && it.lease === retain })) break
                    if (!retainAllowed && retain != null && !cleanup(synchronized(lock) { listOfNotNull(leases[retain]) })) break
                    if (!registered) { attemptActive.set(false); stop(ProviderStartupRejection(ProviderStartupFailure.UNKNOWN)); break }
                    if (!current()) { cleanup(synchronized(lock) { leases.values.toList() }); break }
                    when (result) {
                        is ProviderStartupAttempt.Admitted -> {
                            if (!bind(owner, true)) { cleanup(synchronized(lock) { leases.values.toList() }); break }
                            publish(ProviderStartupPhase.SELECTED)
                            if (!current()) { cleanup(synchronized(lock) { leases.values.toList() }); break }
                            return@synchronized ProviderStartupResult.Ready(owner, result.value)
                        }
                        ProviderStartupAttempt.RequiresMutation -> {
                            if (!bind(owner, false)) break
                            publish(ProviderStartupPhase.HELD_FOR_MUTATION)
                            if (!current()) { cleanup(synchronized(lock) { leases.values.toList() }); break }
                            return@synchronized ProviderStartupResult.HeldForMutation(owner)
                        }
                        is ProviderStartupAttempt.Rejected -> {
                            last = result.failure
                            if (fixed != null || !last.retryable) { stop(last); break }
                        }
                    }
                }
                if (failure() == null) {
                    if (candidates.isNotEmpty() && last.retryable) synchronized(lock) { exhausted = true }
                    stop(last)
                }
                cleanup(synchronized(lock) { leases.values.toList() })
                ProviderStartupResult.Failed(checkNotNull(failure()))
            } finally { alarm.close() }
        }

    // Worker only. Cancellation is separately available to lifecycle/main code.
    override fun close() {
        closed.set(true)
        cancel()
        synchronized(operation) { cleanup(synchronized(lock) { leases.values.toList() }) }
    }
    override fun toString() = "ProviderRouteStartup(status only)"
}
