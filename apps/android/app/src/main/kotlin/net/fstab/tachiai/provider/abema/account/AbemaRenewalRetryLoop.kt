package net.fstab.tachiai.provider.abema.account

// Waits before each automatic retry of a foreground readiness renewal (#635).
internal val ABEMA_RENEWAL_RETRY_BACKOFF_MS = listOf(2_000L, 5_000L, 15_000L)
// A retry starts only if at least this much of the caller's deadline remains.
internal const val ABEMA_RENEWAL_RETRY_MINIMUM_ATTEMPT_MS = 20_000L

// Main-process RAM only (#635 step 1). When a foreground readiness renewal it
// launched or joined ends with a retryable result, polling callers keep seeing
// WAIT while the same exact scope (owner proof and grant snapshot) relaunches
// after a short backoff. Every relaunch still passes the caller's fresh owner
// capture, the coordinator's one-renewal-per-device slot and the caller's
// original deadline; nothing extends a limit. Renewal jobs pass retry = false
// and keep their own ledger policy and Network.
internal class AbemaRenewalRetryLoop<W, O : AbemaRenewalOwnedOperation>(
    private val coordinator: AbemaRenewalReadinessCoordinator<W>,
    private val admission: AbemaRenewalOwnedAdmission<W, O>,
    private val instanceOf: (W) -> String,
    private val scopeOf: (W) -> Any,
    private val elapsed: () -> Long,
    // Closed constants only.
    private val log: (String) -> Unit = {},
    private val backoffMs: List<Long> = ABEMA_RENEWAL_RETRY_BACKOFF_MS,
    private val minimumAttemptMs: Long = ABEMA_RENEWAL_RETRY_MINIMUM_ATTEMPT_MS,
    // A failure older than this is forgotten rather than retried.
    private val windowMs: Long = AbemaReadinessIpc.LIMIT_MS,
) {
    enum class Reason { TEMPORARY_FAILURE, CANCELLED }
    private class Loop(val scope: Any) {
        var retries = 0
        var failed: Long? = null
        var reason: Reason? = null
    }
    private sealed interface Verdict {
        data object Ordinary : Verdict
        data object Wait : Verdict
        data object Stop : Verdict
        class Relaunch(val attempt: Int, val reason: Reason) : Verdict
    }
    private val loops = HashMap<String, Loop>()

    fun reserve(instance: String, scope: Any, value: W, deadline: Long, admitted: () -> Boolean,
        construct: (String, W, (AbemaRenewalOperationOutcome) -> Unit) -> O,
        notify: (AbemaRenewalOperationOutcome) -> Unit = {}, retry: Boolean,
    ): AbemaRenewalReservation {
        if (!retry) return admission.reserve(instance, scope, value, deadline, admitted, construct, notify)
        // Only a remembered temporary failure with a confirmed cleanup receipt,
        // or one the coordinator has already forgotten, can continue a loop.
        val remembered = coordinator.evidence(instance) { runCatching { scopeOf(it) == scope }.getOrDefault(false) }.completion
        val verdict = if (remembered == AbemaRenewalReadinessCoordinator.Completion.ABSENT ||
            remembered == AbemaRenewalReadinessCoordinator.Completion.TEMPORARY_FAILURE) decide(instance, scope, deadline)
            else Verdict.Ordinary
        val relaunch = when (verdict) {
            Verdict.Wait -> return AbemaRenewalReservation.Observed(AbemaRenewalReadinessCoordinator.Status.WAIT)
            Verdict.Stop -> return AbemaRenewalReservation.NoWork(AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE)
            Verdict.Ordinary -> null
            is Verdict.Relaunch -> verdict
        }
        val reservation = admission.reserve(instance, scope, value, deadline, admitted, construct, notify,
            relaunch = relaunch != null)
        if (reservation is AbemaRenewalReservation.Owned) {
            if (relaunch == null) forget(instance)
            else {
                launched(instance, scope)
                log("RETRY attempt=${relaunch.attempt} reason=${relaunch.reason.name}")
            }
        }
        return reservation
    }

    // The owned admission's authoritative receipt, delivered before the
    // coordinator publishes the terminal status that polling callers read.
    @Synchronized fun completed(work: W, outcome: AbemaRenewalOperationOutcome, ownerCancelled: Boolean) {
        val instance = runCatching { instanceOf(work) }.getOrNull() ?: return
        val scope = runCatching { scopeOf(work) }.getOrNull()
        val now = runCatching(elapsed).getOrNull()
        if (scope == null || now == null || now < 0 || outcome.result == AbemaAutomaticRenewalResult.SUCCESS) {
            loops.remove(instance); return
        }
        val loop = loops[instance]?.takeIf { it.scope == scope } ?: Loop(scope).also { loops[instance] = it }
        loop.failed = now
        loop.reason = abemaRenewalRetryReason(outcome, ownerCancelled)
    }

    @Synchronized private fun decide(instance: String, scope: Any, deadline: Long): Verdict {
        val loop = loops[instance]?.takeIf { it.scope == scope } ?: return Verdict.Ordinary
        val failed = loop.failed ?: return Verdict.Ordinary
        val reason = loop.reason ?: return Verdict.Ordinary
        val now = runCatching(elapsed).getOrNull()
        if (now == null || now < failed || now - failed >= windowMs) { loops.remove(instance); return Verdict.Ordinary }
        // Exhausted: the coordinator's remembered result answers ordinarily,
        // keeping its brief replay and a later deliberate Retry.
        if (loop.retries >= backoffMs.size) return Verdict.Ordinary
        val due = failed + backoffMs[loop.retries]
        if (deadline - minimumAttemptMs < maxOf(due, now)) return Verdict.Stop
        if (now < due) return Verdict.Wait
        return Verdict.Relaunch(loop.retries + 1, reason)
    }

    @Synchronized private fun launched(instance: String, scope: Any) {
        val loop = loops[instance]?.takeIf { it.scope == scope } ?: return
        loop.retries++; loop.failed = null; loop.reason = null
    }

    // An ordinary launch starts a fresh loop of retries for its own result.
    @Synchronized private fun forget(instance: String) { loops.remove(instance) }

    override fun toString() = "AbemaRenewalRetryLoop(redacted)"
}

// Retried: a temporary failure, including an operation or browser OWNER_CHANGED,
// and a cancellation this caller did not request. Never retried: unconfirmed
// cleanup, a refused clock, or the approval browser refusing admission (for
// example while a manual approval holds it). Other results are final here.
internal fun abemaRenewalRetryReason(outcome: AbemaRenewalOperationOutcome,
    ownerCancelled: Boolean): AbemaRenewalRetryLoop.Reason? {
    if (!outcome.cleanupConfirmed || ownerCancelled) return null
    if (listOfNotNull(outcome.diagnostic, outcome.producerDiagnostic).any {
            it.reason == AbemaRenewalDiagnostic.Reason.CLOCK_REFUSED ||
                it.phase == AbemaRenewalDiagnostic.Phase.ADMISSION && it.reason == AbemaRenewalDiagnostic.Reason.REFUSED
        }) return null
    return when (outcome.result) {
        AbemaAutomaticRenewalResult.TEMPORARY_FAILURE -> AbemaRenewalRetryLoop.Reason.TEMPORARY_FAILURE
        AbemaAutomaticRenewalResult.CANCELLED -> AbemaRenewalRetryLoop.Reason.CANCELLED
        else -> null
    }
}
