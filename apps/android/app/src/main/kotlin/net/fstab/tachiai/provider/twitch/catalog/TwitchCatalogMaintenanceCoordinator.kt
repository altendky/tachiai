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
    private val schedule: ((Long, () -> Unit) -> AutoCloseable)? = null,
    private val dispatch: ((() -> Unit) -> Boolean)? = null,
) {
    private val lock = Any()
    private val attachments = mutableSetOf<Attachment>()
    private var flight: Flight? = null
    private var poisoned = false
    private val retainedWorkers = mutableListOf<TwitchCatalogSession>()
    private val retainedTimers = mutableListOf<AutoCloseable>()
    private class TimerProof(val grant: TwitchCatalogStoredGrant, val validated: Long, val deadline: Long) {
        val origin = TwitchCatalogMaintenanceOrigin(grant)
        val due = deadline - minOf(60_000L, maxOf(1L, (deadline - validated) / 2))
        var attempted = false
        var retryAt = due
    }
    private class TimerTicket(val proof: TimerProof) { var handle: AutoCloseable? = null }
    private var timerProof: TimerProof? = null
    private var timer: TimerTicket? = null
    private var queuedTimer: TimerTicket? = null
    val isIdle: Boolean get() = synchronized(lock) {
        !poisoned && attachments.isEmpty() && flight == null && timer == null && queuedTimer == null
    }
    val canPublishLocally: Boolean get() = synchronized(lock) { !poisoned }

    internal class TemporaryHint(val origin: TwitchCatalogMaintenanceOrigin, val summary: TwitchCatalogSessionSummary) {
        override fun toString() = "TwitchCatalogTemporaryHint(redacted)"
    }

    internal inner class Attachment internal constructor(
        private val live: () -> Boolean, private val revisionCurrent: () -> Boolean,
        private val onMaintenance: () -> Unit,
    ) : AutoCloseable {
        @Volatile private var closed = false
        internal var origin: TwitchCatalogMaintenanceOrigin? = null
        internal var pending: TwitchCatalogMaintenanceOutcome? = null
        private var temporaryHint: TemporaryHint? = null
        val isOpen: Boolean get() = !closed
        internal fun live(): Boolean = !closed && runCatching(live).getOrDefault(false)
        internal fun admitted(): Boolean = live() && runCatching(revisionCurrent).getOrDefault(false)
        fun enroll(grant: TwitchCatalogStoredGrant, receipt: TwitchCatalogMaintenanceOutcome? = null) {
            synchronized(lock) {
                if (!closed) {
                    val previous = origin
                    val proved = pending
                    origin = TwitchCatalogMaintenanceOrigin(grant)
                    if (temporaryHint?.origin?.matches(grant) != true) temporaryHint = null
                    pending = if (receipt != null && receipt.origin != null && previous != null && previous.sameAs(receipt.origin) &&
                        proved != null && proved.origin != null && proved.origin.sameAs(previous) && proved.grant != null &&
                        !TwitchCatalogMaintenanceOrigin(grant).matches(proved.grant))
                        TwitchCatalogMaintenanceOutcome(proved.summary, origin, proved.grant,
                            proved.validationStartedMs, proved.validationDeadlineMs, proved.ownedMarker) else null
                }
            }
            armTimer()
        }
        internal fun seedValidated(grant: TwitchCatalogStoredGrant, validated: Long, deadline: Long) {
            if (!admitted()) return
            synchronized(lock) {
                if (!closed && attachments.contains(this) && origin?.matches(grant) == true) {
                    temporaryHint = null; positiveLocked(grant, validated, deadline)
                }
            }
            armTimer()
        }
        internal fun signal() { if (live()) runCatching(onMaintenance) }
        internal fun successor(expected: TwitchCatalogStoredGrant): TwitchCatalogMaintenanceOutcome? {
            if (!live()) return null
            return synchronized(lock) {
                pending?.takeIf { !closed && origin?.matches(expected) == true && it.origin?.matches(expected) == true }
            }
        }
        internal fun consume(outcome: TwitchCatalogMaintenanceOutcome) = synchronized(lock) {
            if (pending === outcome) pending = null
        }
        internal fun temporary(expected: TwitchCatalogStoredGrant): TemporaryHint? {
            if (!live()) return null
            return synchronized(lock) { temporaryHint?.takeIf { !closed && it.origin.matches(expected) } }
        }
        internal fun consumeTemporary(value: TemporaryHint): Boolean = synchronized(lock) {
            if (temporaryHint !== value) false else { temporaryHint = null; true }
        }
        internal fun installTemporary(value: TemporaryHint) = synchronized(lock) { if (!closed) temporaryHint = value }
        internal fun clearTemporary() = synchronized(lock) { temporaryHint = null }
        override fun close() {
            synchronized(lock) { closed = true; origin = null; pending = null; temporaryHint = null; attachments.remove(this) }
            val active = synchronized(lock) { flight }
            if (active != null && !interested(active)) {
                try { active.worker.get()?.setForeground(false) }
                catch (_: Throwable) { synchronized(lock) {
                    poisoned = true
                    active.worker.get()?.let { if (retainedWorkers.none { old -> old === it }) retainedWorkers.add(it) }
                } }
            }
            armTimer()
            notifyIdle()
        }
        override fun toString() = "TwitchCatalogMaintenanceAttachment(redacted)"
    }

    internal fun attach(live: () -> Boolean, revisionCurrent: () -> Boolean,
        onMaintenance: () -> Unit = {}): Attachment =
        Attachment(live, revisionCurrent, onMaintenance).also { synchronized(lock) { attachments.add(it) } }

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
        unauthorized: TwitchCatalogMaintenanceOrigin? = null, began: Long = monotonicMs(),
        expected: TwitchCatalogMaintenanceOrigin? = null,
        notAfterMs: Long = Long.MAX_VALUE): TwitchCatalogMaintenanceOutcome {
        val required = unauthorized ?: expected
        if (unauthorized != null && expected != null && !unauthorized.sameAs(expected)) return stopped()
        if (began < 0 || began > Long.MAX_VALUE - 30_000) return temporary().forAdmission(began)
        if (!attached.live()) return stopped()
        val deadline = minOf(began + 30_000, notAfterMs)
        if (deadline <= began) return stopped()
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
                    Flight(began, deadline, required, unauthorized != null).also {
                        it.waiters.add(attached); flight = it
                    }
                } else {
                    compatible = (unauthorized == null || existing.refreshing) &&
                        (required == null || existing.origin?.sameAs(required) != false) &&
                        (notBefore == null || existing.validationStarted?.let { it >= notBefore } != false)
                    if (compatible) existing.waiters.add(attached)
                    existing
                }
            }
            if (producer) perform(owned)
            else if (!await(owned, attached, began, deadline)) return if (attached.live()) timeout() else stopped()
            if (compatible) {
                val result = owned.outcome ?: return timeout()
                if (required != null && result.origin?.sameAs(required) != true) {
                    if (attempt == 1) return stopped()
                } else if (notBefore != null && result.grant != null &&
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

    internal enum class ViewerMaintenanceState { IDLE, WORK, REFUSED }

    // This is a momentary operation-local probe, never authorization or an
    // adoption receipt. Unmatched/starting work stays on the existing slow path.
    internal fun viewerMaintenanceState(attached: Attachment, expected: TwitchCatalogStoredGrant): ViewerMaintenanceState {
        if (!attached.admitted()) return ViewerMaintenanceState.REFUSED
        return synchronized(lock) {
            if (poisoned || attached !in attachments || !attached.isOpen || attached.origin?.matches(expected) != true)
                ViewerMaintenanceState.REFUSED
            else if (flight != null || attached.pending != null) ViewerMaintenanceState.WORK
            else ViewerMaintenanceState.IDLE
        }
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
            // Completed healthy work may provide no replacement at all. Only
            // strict currentness of the caller's original grant can then admit
            // it; a failed wait above must never take that fallback.
            return attached.successor(expected)?.forAdmission(began)
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
            armTimer()
            notifyIdle()
        }
    }

    private fun publish(result: TwitchCatalogMaintenanceOutcome, value: Flight): TwitchCatalogMaintenanceOutcome {
        // A failed attempt supplies no replacement evidence and must not erase
        // another consumer's still-bound grant or previously proved successor.
        // The initiating/waiting callers receive this failure via flight.outcome.
        if (result.summary.state == TwitchCatalogSessionState.TEMPORARY_FAILURE &&
            result.grant == null && result.ownedMarker == null) {
            val origin = result.origin ?: value.origin ?: return result
            val live = synchronized(lock) { attachments.toList() }.filter { it.live() }
            val notified = synchronized(lock) {
                live.filter { attached ->
                    val matching = attached.isOpen && attached in attachments &&
                        (attached.origin?.sameAs(origin) == true || attached.pending?.grant?.let(origin::matches) == true)
                    if (matching) attached.installTemporary(TemporaryHint(origin, result.summary))
                    matching
                }
            }
            signal(notified)
            return result
        }
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
                if (matching) {
                    attached.clearTemporary()
                    attached.pending = TwitchCatalogMaintenanceOutcome(result.summary, enrolled, result.grant,
                        result.validationStartedMs, result.validationDeadlineMs, result.ownedMarker)
                }
            }
            if (result.grant != null && result.validationStartedMs != null && result.validationDeadlineMs != null)
                positiveLocked(result.grant, result.validationStartedMs, result.validationDeadlineMs, admittedAt)
            else if (timerProof?.origin?.sameAs(origin) == true) timerProof = null
        }
        // The dispatcher must enqueue work. Neither callbacks nor protected
        // adoption execute inline on the producer's session/owner monitors.
        signal(live.filter { attached -> synchronized(lock) { attached.pending != null && attached in attachments } })
        return result
    }

    private fun signal(targets: List<Attachment>) {
        targets.forEach { attached -> runCatching { dispatch?.invoke { attached.signal() } } }
    }

    private fun positiveLocked(grant: TwitchCatalogStoredGrant, validated: Long, deadline: Long, now: Long = monotonicMs()) {
        if (poisoned || validated < 0 || validated > now || validated > Long.MAX_VALUE - TWITCH_CATALOG_VALIDATION_INTERVAL_MS ||
            deadline <= now || deadline <= validated || deadline > validated + TWITCH_CATALOG_VALIDATION_INTERVAL_MS) return
        val previous = timerProof
        if (previous != null && validated < previous.validated) return
        val next = TimerProof(grant, validated, deadline)
        // A finite durable bound may not move after positive same-pair
        // validation. Do not spin on that unchanged short expiry.
        if (previous?.attempted == true && previous.origin.sameAs(next.origin) && deadline <= previous.deadline)
            next.attempted = true
        timerProof = next
    }

    private fun timerInterest(attached: Attachment, proof: TimerProof): Boolean = synchronized(lock) {
        attached in attachments && attached.isOpen && (attached.origin?.sameAs(proof.origin) == true ||
            attached.pending?.grant?.let(proof.origin::matches) == true)
    }
    private fun cancelTimer(handle: AutoCloseable?) {
        if (handle == null) return
        try { handle.close() } catch (_: Throwable) { synchronized(lock) {
            poisoned = true; retainedTimers.add(handle)
        } }
    }
    private fun armTimer() {
        if (schedule == null || dispatch == null) return
        val snapshot = synchronized(lock) { timerProof to attachments.toList() }
        val proof = snapshot.first
        val interested = proof != null && snapshot.second.any { it.live() && timerInterest(it, proof) }
        var cancelled: AutoCloseable? = null
        val ticket = synchronized(lock) {
            val now = monotonicMs()
            if (poisoned || proof == null || timerProof !== proof || !interested || now < proof.validated || now >= proof.deadline) {
                cancelled = timer?.handle; timer = null
                if (attachments.isEmpty() && flight == null) timerProof = null
                null
            } else {
                if (timer?.proof !== proof) { cancelled = timer?.handle; timer = null }
                if (timer != null || queuedTimer != null || flight != null || proof.attempted || proof.retryAt >= proof.deadline) null
                else TimerTicket(proof).also { timer = it }
            }
        }
        cancelTimer(cancelled)
        if (ticket == null) return
        val handle = try { schedule.invoke(maxOf(1L, ticket.proof.retryAt - monotonicMs())) { fireTimer(ticket) } }
            catch (_: Throwable) {
                synchronized(lock) { if (timer === ticket) { timer = null; ticket.proof.attempted = true } }
                return
            }
        val keep = synchronized(lock) { if (timer === ticket) { ticket.handle = handle; true } else false }
        if (!keep) cancelTimer(handle)
    }
    private fun fireTimer(ticket: TimerTicket) {
        val began = monotonicMs()
        val accepted = synchronized(lock) {
            if (timer !== ticket || timerProof !== ticket.proof || poisoned || queuedTimer != null) false
            else {
                timer = null
                if (began < ticket.proof.validated || began >= ticket.proof.deadline) {
                    ticket.proof.attempted = true; false
                } else if (began < ticket.proof.retryAt) false
                else { queuedTimer = ticket; true }
            }
        }
        if (!accepted) { armTimer(); return }
        val dispatched = runCatching { dispatch?.invoke { runTimer(ticket, began) } == true }.getOrDefault(false)
        if (!dispatched) finishTimer(ticket, temporary())
    }
    private fun runTimer(ticket: TimerTicket, began: Long) {
        var result = stopped()
        fun admitted(): Boolean = timeCurrent(began, minOf(began + 30_000, ticket.proof.deadline)) &&
            monotonicMs() >= ticket.proof.validated
        try {
            if (began < 0 || began > Long.MAX_VALUE - 30_000 || !admitted()) return
            val eligible = synchronized(lock) {
                if (queuedTimer !== ticket || timerProof !== ticket.proof || poisoned) emptyList() else attachments.toList()
            }
            val attached = eligible.firstOrNull { it.admitted() && timerInterest(it, ticket.proof) } ?: return
            if (!admitted() || !runCatching(canCommit).getOrDefault(false) ||
                !admitted() || !store.isStoredCurrent(ticket.proof.grant) || !admitted()) return
            val current = synchronized(lock) {
                if (queuedTimer !== ticket || timerProof !== ticket.proof || poisoned || ticket.proof.attempted) false
                else { ticket.proof.attempted = true; true }
            }
            if (current && admitted()) result = maintain(attached, began = began,
                expected = ticket.proof.origin, notAfterMs = ticket.proof.deadline)
        } catch (_: Throwable) {
            result = temporary()
        } finally { finishTimer(ticket, result) }
    }
    private fun finishTimer(ticket: TimerTicket, result: TwitchCatalogMaintenanceOutcome) {
        synchronized(lock) {
            if (queuedTimer === ticket) queuedTimer = null
            if (timerProof === ticket.proof) {
                if (result.summary.state == TwitchCatalogSessionState.TEMPORARY_FAILURE && !poisoned) {
                    val now = monotonicMs()
                    ticket.proof.attempted = false
                    ticket.proof.retryAt = if (now <= Long.MAX_VALUE - 60_000) now + 60_000 else Long.MAX_VALUE
                } else ticket.proof.attempted = true
            }
        }
        armTimer()
        notifyIdle()
    }
    private fun notifyIdle() { if (isIdle) runCatching(onIdle) }
    override fun toString() = "TwitchCatalogMaintenanceCoordinator(redacted)"
}
