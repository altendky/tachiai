package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.platform.network.ProviderTransportEvidence

internal class AbemaRenewalJobPreflight(val target: AbemaReadinessTarget, val generation: AbemaRenewalGeneration) {
    override fun toString() = "AbemaRenewalJobPreflight(redacted)"
}

// One platform invocation, not the resource owner. Ending a job never releases
// the actual operation; only the owned admission's cleanup receipt can do that.
internal class AbemaRenewalJobRun<N : Any>(
    private val key: AbemaRenewalJobKey,
    private val network: N,
    private val deadline: Long,
    private val ledger: AbemaRenewalJobLedger,
    private val worker: Executor,
    private val post: (() -> Unit) -> Boolean,
    private val elapsed: () -> Long,
    private val wall: () -> Long,
    private val current: () -> Boolean,
    private val inspect: (String) -> AbemaRenewalJobPreflight?,
    private val reserve: (AbemaRenewalJobPreflight, N, Long, () -> Boolean,
        (AbemaRenewalOperationOutcome) -> Unit) -> AbemaRenewalReservation,
    private val refresh: () -> Unit,
    private val finished: () -> Unit,
    // Closed constants only: disposition and invocation-end names.
    private val log: (String) -> Unit = {},
    // RAM evidence of a renewal in flight for this preflight's exact account
    // scope (#635 step 4), or null. It carries no Network, ticket or control.
    private val join: (AbemaRenewalJobPreflight) -> (() -> AbemaRenewalReadinessCoordinator.Evidence)? = { null },
    private val delay: (Long, () -> Unit) -> Boolean = { _, _ -> false },
    private val joinPollMs: Long = 500,
) {
    private val active = AtomicBoolean(true)
    private val ended = AtomicBoolean()
    private val callbackDelivered = AtomicBoolean()
    private val stopRequested = AtomicBoolean()
    private val lease = AtomicReference<AbemaRenewalOwnerLease?>()
    private var attempt: AbemaRenewalJobAttempt? = null // Account worker only.
    private var receiptHandled = false // Account worker only.
    private var followup: AbemaRenewalJobKey? = null // A not-yet-armed retry, account worker only.
    private var joined: (() -> AbemaRenewalReadinessCoordinator.Evidence)? = null // Account worker only.

    private fun admitted() = active.get() && runCatching { current() && elapsed() >= 0 && elapsed() < deadline }.getOrDefault(false)

    fun start(): Boolean = try {
        worker.execute { begin() }
        true
    } catch (_: Throwable) { active.set(false); false }

    // Synchronous operation cancellation serializes against its adoption lock.
    // Durable deferral follows on the worker; RUNNING already protects a crash.
    // An early attempt is the exception: the grant is still valid, and the
    // ledger's own settlement moves a stopped early attempt to the next early
    // step or natural expiry. Deferring it here would race that settlement
    // (the job deadline and the operation deadline are the same instant) and
    // leave nothing scheduled before the grant expires.
    fun stop() {
        if (callbackDelivered.get()) return // Retired invocations have no successor control.
        stopRequested.set(true)
        active.set(false)
        lease.get()?.let { runCatching(it::cancel) }
        try { worker.execute {
            val early = attempt?.early == true
            log("JOB_STOPPED early=$early")
            // A joined renewal belongs to its foreground owner and has no lease
            // to report back: the job settles its own attempt as a stop.
            if (joined != null) completed(ABEMA_JOIN_LEFT)
            else if (!early) runCatching { ledger.defer(followup ?: key, AbemaRenewalJobDisposition.DEFERRED_STOP) }
            finish()
        } } catch (_: Throwable) { finish() }
    }

    private fun begin() {
        try {
            if (!admitted()) { defer(AbemaRenewalJobDisposition.DEFERRED_STOP); return }
            val entry = ledger.snapshot().singleOrNull { it.key == key }
            if (entry == null || !entry.pending) { finish(); return }
            val before = inspect(key.instance)
            if (!admitted()) { defer(AbemaRenewalJobDisposition.DEFERRED_STOP); return }
            if (before == null) {
                ledger.reconcile(key.instance, null); finish(); refresh(); return
            }
            if (before.generation != entry.generation) {
                ledger.reconcile(key.instance, before.generation); finish(); refresh(); return
            }
            if (wall() < entry.dueWallMs) { finish(); refresh(); return }
            when (val reservation = reserve(before, network, deadline, ::admitted, ::completed)) {
                is AbemaRenewalReservation.Owned -> {
                    // Publish control before any consume or activation step.
                    lease.set(reservation.lease)
                    if (!admitted()) {
                        reservation.lease.cancel(); defer(AbemaRenewalJobDisposition.DEFERRED_STOP); return
                    }
                    val again = inspect(key.instance)
                    if (!admitted() || again == null || again.generation != before.generation ||
                        again.target.ownerProof != before.target.ownerProof) {
                        reservation.lease.cancel(); defer(AbemaRenewalJobDisposition.DEFERRED_STOP); return
                    }
                    attempt = ledger.consume(key, before.generation, wall())
                    if (attempt == null) { reservation.lease.cancel(); finish(); return }
                    if (!admitted()) {
                        reservation.lease.cancel(); defer(AbemaRenewalJobDisposition.DEFERRED_STOP); return
                    }
                    // A false dispatch acknowledgement is not a cleanup verdict.
                    reservation.lease.activate()
                    reservation.lease.outcome()?.let(::completed)
                }
                is AbemaRenewalReservation.NoWork -> {
                    if (reservation.status == AbemaRenewalReadinessCoordinator.Status.READY) {
                        finish(); refresh()
                    } else defer(if (reservation.status == AbemaRenewalReadinessCoordinator.Status.STORAGE_FAILURE)
                        AbemaRenewalJobDisposition.DEFERRED_STORAGE else AbemaRenewalJobDisposition.DEFERRED_FAILURE)
                }
                is AbemaRenewalReservation.Observed -> {
                    // An observer never cancels foreign work. Only a renewal in
                    // flight for this exact account scope is joined (#635 step 4)
                    // and counts as this job's attempt; other work (another
                    // instance's, a changed scope's or a remembered result) defers.
                    val probe = if (reservation.status == AbemaRenewalReadinessCoordinator.Status.WAIT)
                        runCatching { join(before) }.getOrNull() else null
                    if (probe == null || !admitted() || runCatching(probe).getOrNull()?.completion !=
                        AbemaRenewalReadinessCoordinator.Completion.PENDING) {
                        defer(AbemaRenewalJobDisposition.DEFERRED_FAILURE); return
                    }
                    val token = ledger.consume(key, before.generation, wall()) ?: run { finish(); return }
                    attempt = token; joined = probe
                    log("JOB_JOINED early=${token.early}")
                    poll()
                }
                AbemaRenewalReservation.Deferred -> defer(if (admitted())
                    AbemaRenewalJobDisposition.DEFERRED_FAILURE else AbemaRenewalJobDisposition.DEFERRED_STOP)
            }
        } catch (error: Throwable) {
            lease.get()?.let { runCatching(it::cancel) }
            defer(if (error is AbemaAccountException && error.category == AbemaAccountFailure.STORAGE_FAILED)
                AbemaRenewalJobDisposition.DEFERRED_STORAGE else AbemaRenewalJobDisposition.DEFERRED_FAILURE)
        }
    }

    // Observes the joined renewal within this job's own deadline. Leaving it at
    // the deadline or a stop never cancels or extends that work.
    private fun poll() {
        if (receiptHandled || ended.get()) return
        val probe = joined ?: return
        val outcome = abemaJoinedRenewalOutcome(runCatching(probe).getOrNull())
        when {
            outcome != null -> completed(outcome)
            !admitted() -> completed(ABEMA_JOIN_LEFT)
            !runCatching { delay(joinPollMs) { runCatching { worker.execute(::poll) } } }.getOrDefault(false) ->
                completed(ABEMA_JOIN_LEFT)
        }
    }

    private fun completed(outcome: AbemaRenewalOperationOutcome) {
        try { worker.execute {
            if (receiptHandled) return@execute
            receiptHandled = true
            active.set(false)
            val token = attempt
            try {
                if (token != null) {
                    val disposition = when {
                        !outcome.cleanupConfirmed -> AbemaRenewalJobDisposition.DEFERRED_CLEANUP
                        outcome.result == AbemaAutomaticRenewalResult.SUCCESS -> AbemaRenewalJobDisposition.COMPLETED
                        outcome.result == AbemaAutomaticRenewalResult.INTERACTION_REQUIRED -> AbemaRenewalJobDisposition.DEFERRED_INTERACTION
                        outcome.result == AbemaAutomaticRenewalResult.STORAGE_FAILURE -> AbemaRenewalJobDisposition.DEFERRED_STORAGE
                        outcome.result == AbemaAutomaticRenewalResult.CANCELLED -> AbemaRenewalJobDisposition.DEFERRED_STOP
                        else -> AbemaRenewalJobDisposition.DEFERRED_FAILURE
                    }
                    val evidence = outcome.diagnostic
                    val retrySafe = !stopRequested.get() && runCatching(current).getOrDefault(false) &&
                        outcome.cleanupConfirmed && outcome.result == AbemaAutomaticRenewalResult.TEMPORARY_FAILURE &&
                        evidence.transport?.let { failure -> ProviderTransportEvidence(failure,
                            checkNotNull(evidence.transportPhase), evidence.transportCancelled).retryableAvailability } == true
                    val settled = ledger.settle(token, disposition, wall(), retrySafe)
                    followup = settled?.takeIf { it.pending }?.key
                    log("JOB_SETTLED outcome=${outcome.result.name} early=${token.early} " +
                        "disposition=${settled?.disposition?.name ?: "NONE"}")
                }
            } finally {
                if (outcome.cleanupConfirmed) lease.set(null)
                finish(); refresh()
            }
        } } catch (_: Throwable) {
            // The durable RUNNING marker is left for cold interrupted deferral.
            active.set(false); finish()
        }
    }

    private fun defer(disposition: AbemaRenewalJobDisposition) {
        active.set(false)
        runCatching { ledger.defer(key, disposition) }
        finish()
    }

    private fun finish() {
        if (!ended.compareAndSet(false, true)) return
        // This callback ends only the platform invocation, never resource ownership.
        val callback = { if (callbackDelivered.compareAndSet(false, true)) finished() }
        try { if (!post(callback)) callback() } catch (_: Throwable) { runCatching(callback) }
    }
    override fun toString() = "AbemaRenewalJobRun(redacted)"
}

// The job stopped observing a joined renewal (deadline, stop or no poll):
// settled like its own cancelled attempt, without touching the joined work.
private val ABEMA_JOIN_LEFT = AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.CANCELLED, true)

// Adopts a joined renewal's published result as the job's own outcome; null
// while it is still in flight. Only the job's ledger policy decides any retry.
internal fun abemaJoinedRenewalOutcome(evidence: AbemaRenewalReadinessCoordinator.Evidence?): AbemaRenewalOperationOutcome? {
    val diagnostic = evidence?.diagnostic ?: AbemaRenewalDiagnostic()
    return when (evidence?.completion) {
        AbemaRenewalReadinessCoordinator.Completion.PENDING -> null
        AbemaRenewalReadinessCoordinator.Completion.READY -> AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.SUCCESS, true)
        AbemaRenewalReadinessCoordinator.Completion.TEMPORARY_FAILURE ->
            AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, true, diagnostic)
        AbemaRenewalReadinessCoordinator.Completion.INTERACTION_REQUIRED ->
            AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.INTERACTION_REQUIRED, true, diagnostic)
        AbemaRenewalReadinessCoordinator.Completion.STORAGE_FAILURE ->
            AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.STORAGE_FAILURE, true, diagnostic)
        AbemaRenewalReadinessCoordinator.Completion.CLEANUP_UNCONFIRMED ->
            AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, false, diagnostic)
        // A final failure carries no retry evidence of the job's own.
        AbemaRenewalReadinessCoordinator.Completion.FAILURE ->
            AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, true)
        // Retired without a result (for example its owner cancelled it) or unreadable.
        AbemaRenewalReadinessCoordinator.Completion.ABSENT, null -> ABEMA_JOIN_LEFT
    }
}
