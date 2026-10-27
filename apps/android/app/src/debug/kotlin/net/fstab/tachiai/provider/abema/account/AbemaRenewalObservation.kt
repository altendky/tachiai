package net.fstab.tachiai.provider.abema.account

// Debug-fixture observation, never account authority. The original projection
// stays in-process; outputs contain only closed states and failed predicates.
internal class AbemaRenewalObservation(
    private val expected: AbemaAccountSnapshot,
    private val completion: () -> AbemaRenewalReadinessCoordinator.Completion,
    private val ownerCurrent: () -> Boolean,
    private val snapshot: () -> AbemaAccountSnapshot,
    private val usable: (Long) -> Boolean,
    private val receiptCurrent: (AbemaAccountSnapshot) -> Boolean,
    private val wallMs: () -> Long,
) {
    enum class Step { WAIT, REPLACED, REFUSED }
    enum class Guard { NONE, OWNER, COMPLETION_LOST, COMPLETION_REFUSED, COMPLETION_TIMEOUT,
        COMPLETION_MISSING, SNAPSHOT, REVISION, USABLE, GENERATION, ACCOUNT, BEARER,
        EXPIRY, ACCEPTED_WALL, DECODED_EXPIRY }
    var observedCompletion = AbemaRenewalReadinessCoordinator.Completion.ABSENT
        private set
    var firstFailedGuard = Guard.NONE
        private set
    private var sawOperation = false
    private var replaced = false
    private var accepted: AbemaAccountSnapshot? = null
    private fun refuse(guard: Guard): Step {
        if (firstFailedGuard == Guard.NONE) firstFailedGuard = guard
        return Step.REFUSED
    }
    fun poll(): Step {
        if (firstFailedGuard != Guard.NONE) return Step.REFUSED
        if (!ownerCurrent()) return refuse(Guard.OWNER)
        observedCompletion = completion()
        when (observedCompletion) {
            AbemaRenewalReadinessCoordinator.Completion.ABSENT ->
                return if (sawOperation) refuse(Guard.COMPLETION_LOST) else Step.WAIT
            AbemaRenewalReadinessCoordinator.Completion.PENDING -> { sawOperation = true; return Step.WAIT }
            AbemaRenewalReadinessCoordinator.Completion.READY -> sawOperation = true
            else -> return refuse(Guard.COMPLETION_REFUSED)
        }
        // A completed operation, rather than an advanced/stable revision, admits
        // this read. Revalidate the successor even on subsequent observations.
        val old = expected.record ?: return refuse(Guard.SNAPSHOT)
        if (accepted?.let { !receiptCurrent(it) } == true) return refuse(Guard.USABLE)
        val current = snapshot()
        val record = current.record ?: return refuse(Guard.SNAPSHOT)
        if (current.revision <= expected.revision) return refuse(Guard.REVISION)
        if (!usable(current.revision)) return refuse(Guard.USABLE)
        if (old.generation == Long.MAX_VALUE || record.generation != old.generation + 1) return refuse(Guard.GENERATION)
        if (record.grant.userId != old.grant.userId) return refuse(Guard.ACCOUNT)
        if (record.grant.bearer == old.grant.bearer) return refuse(Guard.BEARER)
        val now = wallMs()
        if (record.grant.expiresAtMs <= old.grant.expiresAtMs || record.grant.expiresAtMs <= now) return refuse(Guard.EXPIRY)
        if (record.acceptedWallMs < old.acceptedWallMs || now < record.acceptedWallMs) return refuse(Guard.ACCEPTED_WALL)
        val decoded = runCatching { decodedAbemaGrant(record.grant.bearer, record.grant.userId, now).expiresAtMs }
        if (decoded.getOrNull() != record.grant.expiresAtMs) return refuse(Guard.DECODED_EXPIRY)
        if (!ownerCurrent()) return refuse(Guard.OWNER)
        if (!receiptCurrent(current)) return refuse(Guard.USABLE)
        if (accepted == null) accepted = current
        replaced = true
        return Step.REPLACED
    }
    fun deadline(): Step = when {
        firstFailedGuard != Guard.NONE -> Step.REFUSED
        replaced -> Step.REPLACED
        else -> refuse(if (sawOperation) Guard.COMPLETION_TIMEOUT else Guard.COMPLETION_MISSING)
    }
}

internal class AbemaRenewalProbeEvidence {
    enum class Phase { BASELINE, OWNER, EXPIRED_INPUT, HOST_LAUNCH, COMPLETION, REPLACEMENT, DISCOVERY,
        FINAL_REPLACEMENT, HOST_CLOSE, PRESERVATION, FINISHED }
    enum class Guard { NONE, EXCEPTION, ROUTE, SNAPSHOT, OWNER, EXPIRED_INPUT, ACCOUNT_FILE,
        COMPLETION, HOST_REFUSAL, DEADLINE, DISCOVERY, HOST_CLOSE, PRESERVATION }
    var phase = Phase.BASELINE
    var firstFailedPhase = Phase.BASELINE
        private set
    var firstFailedGuard = Guard.NONE
        private set
    fun fail(guard: Guard) {
        if (firstFailedGuard == Guard.NONE) { firstFailedPhase = phase; firstFailedGuard = guard }
    }
    fun require(value: Boolean, guard: Guard) {
        if (!value) { fail(guard); throw AssertionError("Closed renewal observation guard refused") }
    }
    // Each teardown check runs even if its predecessor throws; the first
    // observation failure survives both teardown failures.
    fun finish(close: () -> Unit, preservation: () -> Unit) {
        phase = Phase.HOST_CLOSE
        try { close() } catch (_: Throwable) { fail(Guard.HOST_CLOSE) }
        phase = Phase.PRESERVATION
        try { preservation() } catch (_: Throwable) { fail(Guard.PRESERVATION) }
        phase = Phase.FINISHED
    }
}
