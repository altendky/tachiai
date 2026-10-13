package net.fstab.tachiai.provider.twitch.catalog

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// Identity excludes mutable expiry: official validation can tighten the same
// pair without rotating it. Neither identity nor receipts enter UI or storage.
internal class TwitchCatalogMaintenanceOrigin(private val grant: TwitchCatalogStoredGrant) {
    val revision: Long get() = grant.revision
    fun matches(other: TwitchCatalogStoredGrant): Boolean = other.state == TwitchCatalogGrantState.READY &&
        grant.state == TwitchCatalogGrantState.READY && grant.generation == other.generation &&
        grant.revision == other.revision && grant.userId == other.userId && grant.savedAtMs == other.savedAtMs &&
        grant.localRetentionUntilMs == other.localRetentionUntilMs &&
        grant.credentials?.accessToken == other.credentials?.accessToken &&
        grant.credentials?.refreshToken == other.credentials?.refreshToken
    fun sameAs(other: TwitchCatalogMaintenanceOrigin): Boolean = matches(other.grant)
    override fun toString() = "TwitchCatalogMaintenanceOrigin(redacted)"
}

internal class TwitchCatalogMaintenanceOutcome(
    val summary: TwitchCatalogSessionSummary,
    val origin: TwitchCatalogMaintenanceOrigin? = null,
    val grant: TwitchCatalogStoredGrant? = null,
    val validationStartedMs: Long? = null,
    val validationDeadlineMs: Long? = null,
    val ownedMarker: TwitchCatalogStoredGrant? = null,
    val admissionStartedMs: Long? = null,
    val admissionDeadlineMs: Long? = null,
) {
    fun forAdmission(began: Long): TwitchCatalogMaintenanceOutcome =
        if (began < 0 || began > Long.MAX_VALUE - 30_000) TwitchCatalogMaintenanceOutcome(
            TwitchCatalogSessionSummary(TwitchCatalogSessionState.TEMPORARY_FAILURE, TwitchCatalogAuthFailure.NETWORK),
            admissionStartedMs = began, admissionDeadlineMs = began)
        else TwitchCatalogMaintenanceOutcome(summary, origin, grant, validationStartedMs, validationDeadlineMs,
            ownedMarker, began, began + 30_000)
    override fun toString() = "TwitchCatalogMaintenanceOutcome(redacted)"
}

// One instance is supplied by the Android registry for an exact storage/route
// owner. All protected work is on callers' workers; the monitor owns memory only.
internal class TwitchCatalogMaintenanceCoordinator(
    val store: TwitchCatalogGrantStore,
    private val transportFactory: (() -> Boolean) -> TwitchCatalogTransport,
    private val canCommit: () -> Boolean,
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onIdle: () -> Unit = {},
) {
    private val lock = Any()
    private val attachments = mutableSetOf<Attachment>()
    private var flight: Flight? = null
    private var poisoned = false
    private val retainedWorkers = mutableListOf<TwitchCatalogSession>()
    val isIdle: Boolean get() = synchronized(lock) { !poisoned && attachments.isEmpty() && flight == null }
    val canPublishLocally: Boolean get() = synchronized(lock) { !poisoned }

    internal inner class Attachment internal constructor(
        private val live: () -> Boolean, private val revisionCurrent: () -> Boolean,
    ) : AutoCloseable {
        @Volatile private var closed = false
        internal var origin: TwitchCatalogMaintenanceOrigin? = null
        internal var pending: TwitchCatalogMaintenanceOutcome? = null
        val isOpen: Boolean get() = !closed
        internal fun live(): Boolean = !closed && runCatching(live).getOrDefault(false)
        internal fun admitted(): Boolean = live() && runCatching(revisionCurrent).getOrDefault(false)
        fun enroll(grant: TwitchCatalogStoredGrant, receipt: TwitchCatalogMaintenanceOutcome? = null) = synchronized(lock) {
            if (!closed) {
                val previous = origin
                val proved = pending
                origin = TwitchCatalogMaintenanceOrigin(grant)
                pending = if (receipt != null && receipt.origin != null && previous != null && previous.sameAs(receipt.origin) &&
                    proved != null && proved.origin != null && proved.origin.sameAs(previous) && proved.grant != null &&
                    !TwitchCatalogMaintenanceOrigin(grant).matches(proved.grant))
                    TwitchCatalogMaintenanceOutcome(proved.summary, origin, proved.grant,
                        proved.validationStartedMs, proved.validationDeadlineMs, proved.ownedMarker) else null
            }
        }
        internal fun successor(expected: TwitchCatalogStoredGrant): TwitchCatalogMaintenanceOutcome? {
            if (!live()) return null
            return synchronized(lock) {
                pending?.takeIf { !closed && origin?.matches(expected) == true && it.origin?.matches(expected) == true }
            }
        }
        internal fun consume(outcome: TwitchCatalogMaintenanceOutcome) = synchronized(lock) {
            if (pending === outcome) pending = null
        }
        override fun close() {
            synchronized(lock) { closed = true; origin = null; pending = null; attachments.remove(this) }
            val active = synchronized(lock) { flight }
            if (active != null && !interested(active)) {
                try { active.worker.get()?.setForeground(false) }
                catch (_: Throwable) { synchronized(lock) {
                    poisoned = true
                    active.worker.get()?.let { if (retainedWorkers.none { old -> old === it }) retainedWorkers.add(it) }
                } }
            }
            notifyIdle()
        }
        override fun toString() = "TwitchCatalogMaintenanceAttachment(redacted)"
    }

    internal fun attach(live: () -> Boolean, revisionCurrent: () -> Boolean): Attachment =
        Attachment(live, revisionCurrent).also { synchronized(lock) { attachments.add(it) } }

    private class Flight(val began: Long, val deadline: Long, val expected: TwitchCatalogMaintenanceOrigin?,
        val unauthorized: Boolean) {
        val done = CountDownLatch(1)
        val worker = AtomicReference<TwitchCatalogSession?>()
        @Volatile var origin: TwitchCatalogMaintenanceOrigin? = expected
        @Volatile var validationStarted: Long? = null
        @Volatile var refreshing = unauthorized
        @Volatile var outcome: TwitchCatalogMaintenanceOutcome? = null
        val waiters = mutableSetOf<Attachment>()
    }
    private fun stopped() = TwitchCatalogMaintenanceOutcome(TwitchCatalogSessionSummary(TwitchCatalogSessionState.SUPERSEDED))
    private fun temporary() = TwitchCatalogMaintenanceOutcome(TwitchCatalogSessionSummary(
        TwitchCatalogSessionState.TEMPORARY_FAILURE, TwitchCatalogAuthFailure.NETWORK))
    private fun timeCurrent(began: Long, deadline: Long): Boolean = monotonicMs().let { it >= began && it < deadline }
    private fun interested(value: Flight): Boolean {
        val snapshot = synchronized(lock) { attachments.toList() to value.waiters.toSet() }
        return snapshot.first.any { attached ->
            attached.admitted() && (attached in snapshot.second || synchronized(lock) {
                val origin = value.origin
                origin != null && (attached.origin?.sameAs(origin) == true ||
                    attached.pending?.grant?.let(origin::matches) == true)
            })
        }
    }

    internal fun maintain(attached: Attachment, force: Boolean = false,
        unauthorized: TwitchCatalogMaintenanceOrigin? = null, began: Long = monotonicMs()): TwitchCatalogMaintenanceOutcome {
        if (began < 0 || began > Long.MAX_VALUE - 30_000) return temporary().forAdmission(began)
        if (!attached.live()) return stopped()
        val deadline = began + 30_000
        val notBefore = began.takeIf { force }
        fun timeout() = temporary().forAdmission(began)
        // One incompatible flight may be awaited, then one fresh flight may be
        // started. Contention never creates an unbounded retry loop or deadline.
        repeat(2) { attempt ->
            if (!attached.live() || !timeCurrent(began, deadline)) return timeout()
            var producer = false
            var compatible = true
            val owned = synchronized(lock) {
                if (poisoned || attached !in attachments) return stopped()
                val existing = flight
                if (existing == null) {
                    producer = true
                    Flight(began, deadline, unauthorized, unauthorized != null).also {
                        it.waiters.add(attached); flight = it
                    }
                } else {
                    compatible = (unauthorized == null || existing.refreshing) &&
                        (unauthorized == null || existing.origin?.sameAs(unauthorized) == true) &&
                        (notBefore == null || existing.validationStarted?.let { it >= notBefore } != false)
                    if (compatible) existing.waiters.add(attached)
                    existing
                }
            }
            if (producer) perform(owned)
            else if (!await(owned, attached, began, deadline)) return if (attached.live()) timeout() else stopped()
            if (compatible) {
                val result = owned.outcome ?: return timeout()
                if (notBefore != null && result.grant != null &&
                    (result.validationStartedMs == null || result.validationStartedMs < notBefore)) {
                    if (attempt == 1) return timeout()
                } else return result.forAdmission(began)
            }
        }
        return timeout()
    }

    private fun await(value: Flight, attached: Attachment, began: Long, deadline: Long): Boolean {
        while (value.done.count != 0L && attached.live() && timeCurrent(began, deadline)) {
            try { value.done.await(25, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        }
        return value.done.count == 0L && attached.live() && timeCurrent(began, deadline)
    }

    // A viewer may wait for one already owned flight, never start or join work.
    // Only its attachment's published proof is an adoption receipt; the flight's
    // result is deliberately not consulted, even after its cleanup has ended.
    internal fun awaitSuccessor(attached: Attachment, expected: TwitchCatalogStoredGrant,
        began: Long, canWait: () -> Boolean): TwitchCatalogMaintenanceOutcome? {
        fun failed() = temporary().forAdmission(began)
        if (began < 0 || began > Long.MAX_VALUE - 30_000) return failed()
        val deadline = began + 30_000
        fun admitted(): Boolean = attached.admitted() && canPublishLocally &&
            runCatching(canWait).getOrDefault(false) && timeCurrent(began, deadline)
        if (!admitted()) return failed()
        val captured = synchronized(lock) {
            if (poisoned || attached !in attachments || !attached.isOpen || attached.origin?.matches(expected) != true)
                return failed()
            flight?.takeIf { active ->
                active.origin?.let { origin -> origin.matches(expected) ||
                    attached.pending?.takeIf { it.origin?.matches(expected) == true }?.grant?.let(origin::matches) == true
                } == true
            }
        }
        if (captured != null) {
            while (captured.done.count != 0L) {
                if (!admitted()) return failed()
                try { captured.done.await(25, TimeUnit.MILLISECONDS) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); return failed() }
            }
            if (!admitted()) return failed()
            return attached.successor(expected)?.forAdmission(began) ?: failed()
        }
        val receipt = attached.successor(expected) ?: return null
        return if (admitted()) receipt.forAdmission(began) else failed()
    }

    private fun perform(value: Flight) {
        fun allowed(): Boolean {
            if (!timeCurrent(value.began, value.deadline) || !canPublishLocally || !interested(value)) return false
            if (!timeCurrent(value.began, value.deadline) || !canPublishLocally) return false
            if (!runCatching(canCommit).getOrDefault(false)) return false
            if (!timeCurrent(value.began, value.deadline) || !canPublishLocally) return false
            val stillInterested = interested(value)
            return stillInterested && canPublishLocally && timeCurrent(value.began, value.deadline)
        }
        val worker = TwitchCatalogSession(store, transportFactory, wallMs, monotonicMs, ::allowed)
        value.worker.set(worker)
        var result: TwitchCatalogMaintenanceOutcome = temporary()
        try {
            val response = worker.maintainExpected(value.expected, value.unauthorized,
                onOrigin = { grant -> value.origin = grant?.let(::TwitchCatalogMaintenanceOrigin) },
                onValidation = { value.validationStarted = it }, onRefresh = { value.refreshing = true })
            result = TwitchCatalogMaintenanceOutcome(response.summary, value.origin, response.lease?.grant,
                response.validationStartedMs, response.lease?.deadlineMs, response.ownedMarker)
        } catch (_: Throwable) { result = temporary() }
        finally {
            try { worker.close() } catch (_: Throwable) {
                synchronized(lock) { poisoned = true; if (retainedWorkers.none { it === worker }) retainedWorkers.add(worker) }
                result = temporary()
            }
            if (worker.cleanupUncertain) {
                synchronized(lock) { poisoned = true; if (retainedWorkers.none { it === worker }) retainedWorkers.add(worker) }
                result = temporary()
            }
            value.worker.set(null)
            if (result.grant != null && !allowed()) result = temporary()
            result = publish(result, value)
            value.outcome = result
            synchronized(lock) { if (flight === value) flight = null }
            value.done.countDown()
            notifyIdle()
        }
    }

    private fun publish(result: TwitchCatalogMaintenanceOutcome, value: Flight): TwitchCatalogMaintenanceOutcome {
        // A failed attempt supplies no replacement evidence and must not erase
        // another consumer's still-bound grant or previously proved successor.
        // The initiating/waiting callers receive this failure via flight.outcome.
        if (result.summary.state == TwitchCatalogSessionState.TEMPORARY_FAILURE &&
            result.grant == null && result.ownedMarker == null) return result
        if (result.summary.state in setOf(TwitchCatalogSessionState.SUPERSEDED, TwitchCatalogSessionState.PAUSED,
                TwitchCatalogSessionState.MISSING, TwitchCatalogSessionState.UNVERIFIED)) return result
        val origin = result.origin ?: return result
        val live = synchronized(lock) { attachments.toList() }.filter { it.live() }
        // All participant/owner callbacks precede this clock admission. The
        // publication monitor then checks only scalars and private identities.
        synchronized(lock) {
            val admittedAt = monotonicMs()
            if (result.grant != null && (poisoned || admittedAt < value.began || admittedAt >= value.deadline)) return temporary()
            for (attached in live) {
                if (!attached.isOpen || attached !in attachments) continue
                val enrolled = attached.origin ?: continue
                val matching = enrolled.sameAs(origin) ||
                    attached.pending?.grant?.let(origin::matches) == true
                if (matching) attached.pending = TwitchCatalogMaintenanceOutcome(result.summary, enrolled, result.grant,
                    result.validationStartedMs, result.validationDeadlineMs, result.ownedMarker)
            }
        }
        return result
    }
    private fun notifyIdle() { if (isIdle) runCatching(onIdle) }
    override fun toString() = "TwitchCatalogMaintenanceCoordinator(redacted)"
}
