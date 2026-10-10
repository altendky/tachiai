package net.fstab.tachiai.provider.twitch.catalog

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal const val TWITCH_CATALOG_VALIDATION_INTERVAL_MS = 60 * 60 * 1000L
internal enum class TwitchCatalogSessionState {
    MISSING, UNVERIFIED, CONNECTED, PAUSED, TEMPORARY_FAILURE,
    RECONNECT_REQUIRED, STORAGE_FAILURE, SUPERSEDED,
}
internal data class TwitchCatalogSessionSummary(
    val state: TwitchCatalogSessionState,
    val failure: TwitchCatalogAuthFailure? = null,
    val expiresAtMs: Long? = null,
)
internal class TwitchCatalogSessionResult(
    val summary: TwitchCatalogSessionSummary,
    val lease: TwitchCatalogLease? = null,
) {
    override fun toString() = "TwitchCatalogSessionResult($summary, redacted)"
}
internal class TwitchCatalogLease internal constructor(
    internal val grant: TwitchCatalogStoredGrant,
    internal val localRevision: Long,
    internal val deadlineMs: Long,
) {
    val accessToken: String get() = checkNotNull(grant.credentials).accessToken
    val userId: String get() = checkNotNull(grant.userId)
    val scopes: Set<String> get() = setOf(TWITCH_CATALOG_SCOPE)
    override fun toString() = "TwitchCatalogLease(redacted)"
}

// Blocking worker methods use only the owner's currently selected route. The
// Android owner supplies hourly foreground scheduling and canCommit rereads.
// Lifecycle invalidation is immediate and does not wait for HTTP or file locks.
internal class TwitchCatalogSession(
    private val store: TwitchCatalogGrantStore,
    private val transportFactory: () -> TwitchCatalogTransport,
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val canCommit: () -> Boolean = { true },
) : AutoCloseable {
    val instanceId: String get() = store.instanceId
    private val operationLock = Any()
    private val localRevision = AtomicLong()
    private val active = AtomicReference<TwitchCatalogTransport?>()
    @Volatile private var foreground = true
    @Volatile private var closed = false
    @Volatile private var lease: TwitchCatalogLease? = null
    @Volatile private var lastSummary = TwitchCatalogSessionSummary(TwitchCatalogSessionState.UNVERIFIED)
    @Volatile private var observedGeneration: String? = null
    @Volatile private var clearRevision: Long? = null
    @Volatile private var clearGeneration: String? = null

    private fun endLocalLease() {
        localRevision.incrementAndGet()
        lease = null
        active.getAndSet(null)?.close()
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        endLocalLease()
        lastSummary = TwitchCatalogSessionSummary(if (value) TwitchCatalogSessionState.UNVERIFIED else TwitchCatalogSessionState.PAUSED)
    }

    fun invalidate() {
        clearGeneration = lease?.grant?.generation ?: observedGeneration
        clearRevision = store.invalidate()
        endLocalLease()
        lastSummary = TwitchCatalogSessionSummary(TwitchCatalogSessionState.SUPERSEDED)
    }

    override fun close() {
        closed = true
        endLocalLease()
        lastSummary = TwitchCatalogSessionSummary(TwitchCatalogSessionState.SUPERSEDED)
    }

    private fun allowed(local: Long, revision: Long): Boolean =
        !closed && foreground && local == localRevision.get() && store.isRevisionCurrent(revision) && canCommit()

    private fun stopped(): TwitchCatalogSessionResult = TwitchCatalogSessionResult(TwitchCatalogSessionSummary(
        if (!foreground && !closed) TwitchCatalogSessionState.PAUSED else TwitchCatalogSessionState.SUPERSEDED))

    private fun result(state: TwitchCatalogSessionState, failure: TwitchCatalogAuthFailure? = null,
        expiresAtMs: Long? = null, value: TwitchCatalogLease? = null): TwitchCatalogSessionResult {
        lease = value
        return TwitchCatalogSessionResult(TwitchCatalogSessionSummary(state, failure, expiresAtMs).also { lastSummary = it }, value)
    }

    private fun storeFailure(error: TwitchCatalogStoreException): TwitchCatalogSessionResult =
        if (error.failure == TwitchCatalogStoreFailure.CLIENT_MISMATCH)
            result(TwitchCatalogSessionState.RECONNECT_REQUIRED, TwitchCatalogAuthFailure.CLIENT_MISMATCH)
        else result(TwitchCatalogSessionState.STORAGE_FAILURE)

    fun readSummary(): TwitchCatalogSessionSummary = synchronized(operationLock) {
        if (closed) return@synchronized TwitchCatalogSessionSummary(TwitchCatalogSessionState.SUPERSEDED)
        if (!foreground) return@synchronized TwitchCatalogSessionSummary(TwitchCatalogSessionState.PAUSED)
        try {
            lease?.let { if (isCurrent(it)) return@synchronized lastSummary }
            lease = null
            if (lastSummary.state == TwitchCatalogSessionState.STORAGE_FAILURE ||
                lastSummary.state == TwitchCatalogSessionState.TEMPORARY_FAILURE) return@synchronized lastSummary
            val grant = store.read()
            observedGeneration = grant?.generation
            summary(grant)
        } catch (error: TwitchCatalogStoreException) {
            storeFailure(error).summary
        }
    }

    private fun summary(grant: TwitchCatalogStoredGrant?): TwitchCatalogSessionSummary = TwitchCatalogSessionSummary(
        when (grant?.state) {
            null, TwitchCatalogGrantState.CLEARED -> TwitchCatalogSessionState.MISSING
            TwitchCatalogGrantState.READY -> if (store.isRevisionCurrent(grant.revision))
                TwitchCatalogSessionState.UNVERIFIED else TwitchCatalogSessionState.SUPERSEDED
            TwitchCatalogGrantState.RECONNECT, TwitchCatalogGrantState.REFRESHING -> TwitchCatalogSessionState.RECONNECT_REQUIRED
        })

    fun beginConnection(): TwitchCatalogConnectionAttempt = synchronized(operationLock) {
        endLocalLease()
        try {
            store.beginConnection().also {
                clearRevision = null; clearGeneration = null
                observedGeneration = it.generation
                lastSummary = TwitchCatalogSessionSummary(TwitchCatalogSessionState.RECONNECT_REQUIRED)
            }
        } catch (error: TwitchCatalogStoreException) {
            storeFailure(error)
            throw error
        }
    }

    fun accept(approved: TwitchCatalogAuthorizationResult.Approved, attempt: TwitchCatalogConnectionAttempt): TwitchCatalogSessionResult =
        synchronized(operationLock) {
            val local = localRevision.get()
            try {
                if (!allowed(local, attempt.revision)) return@synchronized stopped()
                val now = monotonicMs()
                val remaining = approved.deadlineMs - now
                if (remaining <= 0) return@synchronized result(TwitchCatalogSessionState.RECONNECT_REQUIRED, TwitchCatalogAuthFailure.EXPIRED)
                if (approved.validatedAtMs != null && approved.validatedAtMs > now)
                    return@synchronized result(TwitchCatalogSessionState.RECONNECT_REQUIRED, TwitchCatalogAuthFailure.INVALID_RESPONSE)
                val replacement = TwitchCatalogReplacement(approved.credentials, approved.validation,
                    minOf(remaining, approved.credentials.expiresInMs, approved.validation.expiresInMs))
                val grant = store.commitConnection(attempt, replacement) { allowed(local, attempt.revision) }
                    ?: return@synchronized stopped()
                observedGeneration = grant.generation
                if (!allowed(local, grant.revision)) return@synchronized stopped()
                val validatedAt = approved.validatedAtMs ?: return@synchronized result(TwitchCatalogSessionState.UNVERIFIED)
                connected(grant, local, minOf(approved.deadlineMs,
                    Math.addExact(validatedAt, TWITCH_CATALOG_VALIDATION_INTERVAL_MS)))
            } catch (error: TwitchCatalogStoreException) {
                storeFailure(error)
            }
        }

    fun forget(): TwitchCatalogSessionResult {
        endLocalLease()
        return try {
            if (!store.forget(clearRevision, clearGeneration)) stopped() else {
                clearRevision = null; clearGeneration = null; observedGeneration = null
                result(TwitchCatalogSessionState.MISSING)
            }
        } catch (error: TwitchCatalogStoreException) {
            storeFailure(error)
        }
    }

    // A consumer must repeat this worker check immediately before using a token;
    // it includes the durable generation, not just a process-local revision.
    fun isCurrent(value: TwitchCatalogLease): Boolean =
        lease === value && monotonicMs() < value.deadlineMs &&
            allowed(value.localRevision, value.grant.revision) && store.isStoredCurrent(value.grant)

    // Queued UI publication only: no owner callback, protected store or HTTP.
    // This supplements, never replaces, worker-side durable ownership checks.
    fun isLocallyCurrent(value: TwitchCatalogLease): Boolean =
        lease === value && !closed && foreground && value.localRevision == localRevision.get() &&
            monotonicMs() < value.deadlineMs && store.isRevisionCurrent(value.grant.revision)

    private fun <T> withTransport(local: Long, revision: Long, action: (TwitchCatalogTransport) -> T): T {
        check(allowed(local, revision))
        val transport = transportFactory()
        check(active.compareAndSet(null, transport))
        try {
            check(allowed(local, revision))
            return action(transport)
        } finally {
            active.compareAndSet(transport, null)
            transport.close()
        }
    }

    private fun connected(grant: TwitchCatalogStoredGrant, local: Long, validationDeadline: Long): TwitchCatalogSessionResult {
        if (!allowed(local, grant.revision) || !store.isStoredCurrent(grant)) return stopped()
        val remaining = checkNotNull(grant.expiresAtMs) - wallMs()
        if (wallMs() < checkNotNull(grant.savedAtMs) || remaining <= 0)
            return result(TwitchCatalogSessionState.RECONNECT_REQUIRED, TwitchCatalogAuthFailure.EXPIRED)
        val now = monotonicMs()
        val deadline = minOf(Math.addExact(now, remaining), validationDeadline)
        if (deadline <= now) return result(TwitchCatalogSessionState.UNVERIFIED, TwitchCatalogAuthFailure.EXPIRED)
        val value = TwitchCatalogLease(grant, local, deadline)
        observedGeneration = grant.generation
        return result(TwitchCatalogSessionState.CONNECTED, expiresAtMs = grant.expiresAtMs, value = value)
    }

    fun validate(force: Boolean = false): TwitchCatalogSessionResult = synchronized(operationLock) {
        val local = localRevision.get()
        var observed: TwitchCatalogStoredGrant? = null
        if (closed || !foreground) return@synchronized stopped()
        try {
            if (!force) lease?.let { if (isCurrent(it)) return@synchronized TwitchCatalogSessionResult(lastSummary, it) }
            lease = null
            val grant = store.read()
            observed = grant
            observedGeneration = grant?.generation
            if (grant?.state != TwitchCatalogGrantState.READY) return@synchronized result(summary(grant).state)
            if (!allowed(local, grant.revision)) return@synchronized stopped()
            if (wallMs() < checkNotNull(grant.savedAtMs)) {
                store.requireReconnect(grant)
                return@synchronized result(TwitchCatalogSessionState.RECONNECT_REQUIRED, TwitchCatalogAuthFailure.EXPIRED)
            }
            if (wallMs() >= checkNotNull(grant.expiresAtMs)) return@synchronized refreshed(grant, local)
            val started = monotonicMs()
            val response = withTransport(local, grant.revision) { it.validate(checkNotNull(grant.credentials).accessToken) }
            if (!allowed(local, grant.revision)) return@synchronized stopped()
            if (response.status == 401) return@synchronized refreshed(grant, local)
            if (response.status >= 500) return@synchronized result(TwitchCatalogSessionState.TEMPORARY_FAILURE, TwitchCatalogAuthFailure.NETWORK)
            val validation = parseTwitchCatalogValidation(response, grant.userId)
            connected(grant, local, minOf(Math.addExact(started, validation.expiresInMs),
                Math.addExact(started, TWITCH_CATALOG_VALIDATION_INTERVAL_MS)))
        } catch (error: TwitchCatalogAuthException) {
            validationFailure(error.failure, local, observed)
        } catch (_: IOException) {
            if (local != localRevision.get() || !foreground || closed) stopped()
            else result(TwitchCatalogSessionState.TEMPORARY_FAILURE, TwitchCatalogAuthFailure.NETWORK)
        } catch (error: TwitchCatalogStoreException) {
            storeFailure(error)
        } catch (error: java.util.concurrent.CancellationException) {
            lease = null
            throw error
        } catch (_: Exception) {
            if (local != localRevision.get() || !foreground || closed) stopped()
            else result(TwitchCatalogSessionState.TEMPORARY_FAILURE, TwitchCatalogAuthFailure.NETWORK)
        }
    }

    private fun validationFailure(failure: TwitchCatalogAuthFailure, local: Long,
        grant: TwitchCatalogStoredGrant?): TwitchCatalogSessionResult {
        if (grant == null || !allowed(local, grant.revision)) return stopped()
        try { if (!store.isStoredCurrent(grant)) return stopped() }
        catch (error: TwitchCatalogStoreException) { return storeFailure(error) }
        if (failure in setOf(TwitchCatalogAuthFailure.CLIENT_MISMATCH, TwitchCatalogAuthFailure.USER_MISMATCH,
                TwitchCatalogAuthFailure.SCOPE_MISMATCH, TwitchCatalogAuthFailure.REJECTED, TwitchCatalogAuthFailure.EXPIRED)) {
            try { if (grant.state == TwitchCatalogGrantState.READY) store.requireReconnect(grant) }
            catch (error: TwitchCatalogStoreException) { return storeFailure(error) }
            return result(TwitchCatalogSessionState.RECONNECT_REQUIRED, failure)
        }
        return result(TwitchCatalogSessionState.TEMPORARY_FAILURE, failure)
    }

    fun onUnauthorized(value: TwitchCatalogLease): TwitchCatalogSessionResult = synchronized(operationLock) {
        val local = localRevision.get()
        if (lease !== value || !allowed(local, value.grant.revision)) return@synchronized stopped()
        lease = null
        refreshed(value.grant, local)
    }

    private fun refreshed(grant: TwitchCatalogStoredGrant, local: Long): TwitchCatalogSessionResult {
        var validationDeadline = 0L
        try {
            val replacement = store.refresh(grant, { allowed(local, grant.revision) }) { old ->
                withTransport(local, grant.revision) { transport ->
                    val started = monotonicMs()
                    val token = parseTwitchCatalogToken(transport.refresh(old.refreshToken))
                    check(allowed(local, grant.revision))
                    val tokenDeadline = token.expiresInMs?.let { Math.addExact(started, it) }
                    val acceptanceDeadline = tokenDeadline ?: Math.addExact(started, TWITCH_CATALOG_UNKNOWN_GRANT_VALIDATION_MS)
                    val validating = monotonicMs()
                    if (validating >= acceptanceDeadline) throw TwitchCatalogAuthException(TwitchCatalogAuthFailure.EXPIRED)
                    val validation = parseTwitchCatalogValidation(transport.validate(token.accessToken), grant.userId)
                    check(allowed(local, grant.revision))
                    if (monotonicMs() >= acceptanceDeadline) throw TwitchCatalogAuthException(TwitchCatalogAuthFailure.EXPIRED)
                    val validatedDeadline = Math.addExact(validating, validation.expiresInMs)
                    val deadline = tokenDeadline?.let { minOf(it, validatedDeadline) } ?: validatedDeadline
                    val remaining = deadline - monotonicMs()
                    if (remaining <= 0) throw TwitchCatalogAuthException(TwitchCatalogAuthFailure.EXPIRED)
                    val credentials = token.validatedCredentials(validation)
                    validationDeadline = minOf(deadline, Math.addExact(validating, TWITCH_CATALOG_VALIDATION_INTERVAL_MS))
                    TwitchCatalogReplacement(credentials, validation,
                        minOf(remaining, credentials.expiresInMs, validation.expiresInMs))
                }
            } ?: return stopped()
            return connected(replacement, local, validationDeadline)
        } catch (error: TwitchCatalogStoreException) {
            return storeFailure(error)
        } catch (error: java.util.concurrent.CancellationException) {
            lease = null
            throw error
        } catch (error: TwitchCatalogAuthException) {
            return if (local != localRevision.get() || !foreground || closed) stopped()
            else result(TwitchCatalogSessionState.RECONNECT_REQUIRED, error.failure)
        } catch (_: Exception) {
            // A refresh request may have consumed its single-use credential.
            // Even a transient/lost response must never retry the old pair.
            return if (local != localRevision.get() || !foreground || closed) stopped()
            else result(TwitchCatalogSessionState.RECONNECT_REQUIRED, TwitchCatalogAuthFailure.NETWORK)
        }
    }

    override fun toString() = "TwitchCatalogSession(redacted)"
}
