package net.fstab.tachiai.provider.abema.account

import java.security.MessageDigest

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
    private val evidence: () -> AbemaRenewalReadinessCoordinator.Evidence = {
        AbemaRenewalReadinessCoordinator.Evidence(completion())
    },
) {
    enum class Step { WAIT, REPLACED, REFUSED }
    enum class Guard { NONE, OWNER, COMPLETION_LOST, COMPLETION_REFUSED, COMPLETION_TIMEOUT,
        COMPLETION_MISSING, SNAPSHOT, REVISION, USABLE, GENERATION, ACCOUNT, BEARER,
        EXPIRY, ACCEPTED_WALL, DECODED_EXPIRY }
    var observedCompletion = AbemaRenewalReadinessCoordinator.Completion.ABSENT
        private set
    var observedDiagnostic: AbemaRenewalDiagnostic? = null
        private set
    var observedProducerDiagnostic: AbemaRenewalDiagnostic? = null
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
        val captured = evidence()
        observedCompletion = captured.completion
        observedDiagnostic = captured.diagnostic
        observedProducerDiagnostic = captured.producerDiagnostic
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
    enum class Phase { BASELINE, OWNER, EXPIRED_INPUT, HOST_LAUNCH, MINIMIZING, BACKGROUND, RETURN, COMPLETION, REPLACEMENT, DISCOVERY,
        FINAL_REPLACEMENT, HOST_CLOSE, PRESERVATION, FINISHED }
    enum class Guard { NONE, EXCEPTION, ROUTE, SNAPSHOT, OWNER, EXPIRED_INPUT, ACCOUNT_FILE,
        COMPLETION, BACKGROUND, HOST_RETURN, HOST_REFUSAL, DEADLINE, DISCOVERY, HOST_CLOSE, PRESERVATION }
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

// Fixture evidence only. Completion must still be pending after the exact host
// stops, then reach verified replacement without an intervening Activity resume.
internal class AbemaRenewalBackgroundObservation {
    data class Snapshot(val phase: Phase, val guard: Guard, val hostIsStopped: Boolean)
    enum class Phase { IDLE, MINIMIZING, STOPPED_PENDING, READY, RETURN_ALLOWED }
    enum class Guard { NONE, PENDING_BEFORE_HOME, HOST_NOT_STOPPED, PENDING_AFTER_STOP, RESUMED, HOST_DESTROYED, COMPLETION, RETURN }
    @Volatile var phase = Phase.IDLE
        private set
    @Volatile var firstFailedGuard = Guard.NONE
        private set
    @Volatile var hostIsStopped = false
        private set
    private var observing = false
    private var frozen: Snapshot? = null
    @Synchronized fun freeze(): Snapshot {
        frozen?.let { return it }
        observing = false
        return Snapshot(phase, firstFailedGuard, hostIsStopped).also { frozen = it }
    }
    private fun refuse(guard: Guard): Boolean {
        if (firstFailedGuard == Guard.NONE) firstFailedGuard = guard
        return false
    }
    @Synchronized fun begin(completion: AbemaRenewalReadinessCoordinator.Completion): Boolean {
        if (frozen != null) return false
        if (firstFailedGuard != Guard.NONE) return false
        if (phase != Phase.IDLE || completion != AbemaRenewalReadinessCoordinator.Completion.PENDING)
            return refuse(Guard.PENDING_BEFORE_HOME)
        hostIsStopped = false
        observing = true
        phase = Phase.MINIMIZING
        return true
    }
    @Synchronized fun hostStopped() { if (observing && frozen == null) hostIsStopped = true }
    @Synchronized fun hostStarted() {
        if (frozen != null) return
        if (observing && hostIsStopped) refuse(Guard.HOST_NOT_STOPPED)
        hostIsStopped = false
    }
    @Synchronized fun activityResumed() { if (observing && frozen == null) refuse(Guard.RESUMED) }
    @Synchronized fun hostDestroyed() { if (observing && frozen == null) { hostIsStopped = false; refuse(Guard.HOST_DESTROYED) } }
    @Synchronized fun afterStop(completion: AbemaRenewalReadinessCoordinator.Completion): Boolean {
        if (frozen != null) return false
        if (firstFailedGuard != Guard.NONE) return false
        if (phase != Phase.MINIMIZING || !hostIsStopped) return refuse(Guard.HOST_NOT_STOPPED)
        if (completion != AbemaRenewalReadinessCoordinator.Completion.PENDING) return refuse(Guard.PENDING_AFTER_STOP)
        phase = Phase.STOPPED_PENDING
        return true
    }
    @Synchronized fun sample(completion: AbemaRenewalReadinessCoordinator.Completion, replacementVerified: Boolean): Boolean {
        if (frozen != null) return false
        if (firstFailedGuard != Guard.NONE) return false
        if (!hostIsStopped) return refuse(Guard.HOST_NOT_STOPPED)
        if (phase != Phase.STOPPED_PENDING && phase != Phase.READY) return refuse(Guard.COMPLETION)
        return when {
            completion == AbemaRenewalReadinessCoordinator.Completion.PENDING && !replacementVerified && phase == Phase.STOPPED_PENDING -> true
            completion == AbemaRenewalReadinessCoordinator.Completion.READY && replacementVerified -> { phase = Phase.READY; true }
            else -> refuse(Guard.COMPLETION)
        }
    }
    @Synchronized fun allowReturn(): Boolean {
        if (frozen != null) return false
        if (firstFailedGuard != Guard.NONE) return false
        if (phase != Phase.READY || !hostIsStopped) return refuse(Guard.RETURN)
        observing = false
        phase = Phase.RETURN_ALLOWED
        return true
    }
}

internal data class AbemaRenewalOtherMutations(val changed: Int, val added: Int, val removed: Int, val kind: Kind) {
    enum class Kind { NONE, DIAGNOSTIC_JOURNAL_ONLY, OTHER_PROTECTED_RECORD, MIXED }
}

// Only counts/categories leave the fixture; record identities and digests stay
// private. This summary never relaxes its strict preservation assertion.
internal fun abemaRenewalOtherMutations(before: Map<String, ByteArray>, after: Map<String, ByteArray>, selected: String): AbemaRenewalOtherMutations {
    val changed = (before.keys + after.keys).filter { key -> key != selected &&
        (before[key] == null || after[key] == null || !MessageDigest.isEqual(before.getValue(key), after.getValue(key))) }
    val journal = changed.any { it == "no_backup/failure-diagnostics.tsv" }
    val other = changed.any { it != "no_backup/failure-diagnostics.tsv" }
    val kind = when {
        journal && other -> AbemaRenewalOtherMutations.Kind.MIXED
        journal -> AbemaRenewalOtherMutations.Kind.DIAGNOSTIC_JOURNAL_ONLY
        other -> AbemaRenewalOtherMutations.Kind.OTHER_PROTECTED_RECORD
        else -> AbemaRenewalOtherMutations.Kind.NONE
    }
    return AbemaRenewalOtherMutations(changed.count { it in before && it in after }.coerceAtMost(64),
        changed.count { it !in before }.coerceAtMost(64), changed.count { it !in after }.coerceAtMost(64), kind)
}
