package net.fstab.tachiai.provider.abema.account

import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentMap

internal interface AbemaRenewalOwnedOperation {
    fun start()
    fun cancel()
}

// No ticket, work, Network or operation escapes through this RAM-only handle.
internal class AbemaRenewalOwnerLease internal constructor(
    private val activateOwned: () -> Boolean,
    private val cancelOwned: () -> Unit,
    private val readOutcome: () -> AbemaRenewalOperationOutcome?,
) {
    // This acknowledges dispatch only. A false return is not a cleanup receipt;
    // partially delivered dispatch still requires the exact operation's outcome.
    fun activate() = activateOwned()
    fun cancel() = cancelOwned()
    fun outcome() = readOutcome()
    override fun toString() = "AbemaRenewalOwnerLease(redacted)"
}

internal sealed interface AbemaRenewalReservation {
    class Owned(val lease: AbemaRenewalOwnerLease) : AbemaRenewalReservation
    class Observed(val status: AbemaRenewalReadinessCoordinator.Status) : AbemaRenewalReservation
    class NoWork(val status: AbemaRenewalReadinessCoordinator.Status) : AbemaRenewalReservation
    data object Deferred : AbemaRenewalReservation
}

// Owns admission/dispatch only. Account CAS ordering remains entirely in the
// actual operation: cancel never invalidates its coordinator gate before taking
// that operation's cancellation lock, and cleanup receipts alone release work.
internal class AbemaRenewalOwnedAdmission<W, O : AbemaRenewalOwnedOperation>(
    private val coordinator: AbemaRenewalReadinessCoordinator<W>,
    private val operations: ConcurrentMap<String, O>,
    private val validate: (String, W) -> Boolean,
    private val dispatch: (() -> Unit) -> Boolean,
    private val elapsed: () -> Long,
    // Absolute original elapsed deadline, never a new relative lifetime.
    private val schedulePending: (Long, () -> Unit) -> Boolean,
    private val authoritative: (W, AbemaRenewalOperationOutcome) -> Unit = { _, _ -> },
) {
    private enum class Phase { CONSTRUCTING, RESERVED, ACTIVATING, ACTIVE, CANCELLING, FINISHING, FINISHED }
    private class Entry<W, O>(val ticket: String, val work: W, val began: Long, val deadline: Long,
        val admitted: () -> Boolean,
        val notify: (AbemaRenewalOperationOutcome) -> Unit) {
        lateinit var lease: AbemaRenewalOwnerLease
        var operation: O? = null
        var phase = Phase.CONSTRUCTING
        var cancelSent = false
        var delivered = false
        var forceUnknown = false
        var earlyOutcome: AbemaRenewalOperationOutcome? = null
        var outcome: AbemaRenewalOperationOutcome? = null
    }
    private val lock = Any()
    // The lease identity is the generation; neither a ticket nor instance ID
    // alone can authorize control of the operation registered for that lease.
    private val entries = IdentityHashMap<AbemaRenewalOwnerLease, Entry<W, O>>()

    fun reserve(instance: String, scope: Any, value: W, deadline: Long,
        admitted: () -> Boolean,
        construct: (String, W, (AbemaRenewalOperationOutcome) -> Unit) -> O,
        notify: (AbemaRenewalOperationOutcome) -> Unit = {},
    ): AbemaRenewalReservation {
        retireExpiredPending()
        if (!permitted(admitted)) return AbemaRenewalReservation.Deferred
        val began = runCatching(elapsed).getOrNull() ?: return AbemaRenewalReservation.Deferred
        val decision = coordinator.check(instance, scope, value, deadline)
        if (decision.status != AbemaRenewalReadinessCoordinator.Status.LAUNCH)
            return AbemaRenewalReservation.Observed(decision.status)
        val ticket = checkNotNull(decision.ticket)
        if (!permitted(admitted)) {
            coordinator.cancelUnclaimed(ticket)
            return AbemaRenewalReservation.Deferred
        }
        val work = coordinator.claim(ticket)
        if (work == null) {
            coordinator.finish(ticket, false)
            return AbemaRenewalReservation.Deferred
        }
        if (!permitted(admitted) || !runCatching { validate(ticket, work) }.getOrDefault(false) || !permitted(admitted)) {
            // There is no constructed/dispatched operation or external resource yet.
            coordinator.finish(ticket, false)
            return AbemaRenewalReservation.Deferred
        }
        val entry = Entry<W, O>(ticket, work, began, deadline, admitted, notify)
        entry.lease = AbemaRenewalOwnerLease({ activate(entry) }, { cancel(entry) },
            { synchronized(lock) { entry.outcome } })
        val operation = try { construct(ticket, work) { complete(entry, it) } } catch (_: Throwable) {
            coordinator.finish(ticket, false)
            return AbemaRenewalReservation.Deferred
        }
        val published = synchronized(lock) {
            entry.operation = operation
            entries[entry.lease] = entry
            entry.phase = Phase.RESERVED
            operations.putIfAbsent(ticket, operation) == null
        }
        if (!published) {
            // Never cancel/replace the differently registered operation. Keep
            // this constructed owner too; the identity fault has no close receipt.
            synchronized(lock) {
                entry.cancelSent = true; entry.phase = Phase.FINISHED; entry.outcome = unknown()
            }
            runCatching(operation::cancel)
            coordinator.finish(ticket, AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE, false)
            return AbemaRenewalReservation.Deferred
        }
        if (synchronized(lock) { entry.earlyOutcome != null }) {
            failUnknown(entry)
            return AbemaRenewalReservation.Deferred
        }
        if (!permitted(admitted)) {
            cancelPending(entry)
            return AbemaRenewalReservation.Deferred
        }
        val armed = runCatching { schedulePending(entry.deadline) { expirePending(entry) } }.getOrDefault(false)
        if (!armed || !permitted(admitted)) {
            cancelPending(entry)
            return AbemaRenewalReservation.Deferred
        }
        val deliver = synchronized(lock) {
            if (!registered(entry) || entry.phase != Phase.RESERVED || entry.outcome != null) false
            else { entry.delivered = true; true }
        }
        if (!deliver) return AbemaRenewalReservation.Deferred
        return AbemaRenewalReservation.Owned(entry.lease)
    }

    // Also used before observers join a claim, so delayed timer delivery cannot
    // keep zero-resource pending work ahead of ordinary foreground readiness.
    fun retireExpiredPending() {
        val pending = synchronized(lock) {
            entries.values.filter { registered(it) && it.outcome == null &&
                it.phase in setOf(Phase.RESERVED, Phase.ACTIVATING) }
        }
        pending.forEach(::expirePending)
    }

    private fun expirePending(entry: Entry<W, O>) {
        val pending = synchronized(lock) {
            registered(entry) && entry.outcome == null && entry.phase in setOf(Phase.RESERVED, Phase.ACTIVATING)
        }
        if (!pending) return
        val now = runCatching(elapsed).getOrNull()
        if (now != null && now >= 0 && now >= entry.began && now < entry.deadline) return
        cancelPending(entry)
    }

    private fun cancelPending(entry: Entry<W, O>) {
        val operation = synchronized(lock) {
            if (!registered(entry) || entry.outcome != null || entry.cancelSent ||
                entry.phase !in setOf(Phase.RESERVED, Phase.ACTIVATING)) null
            else {
                entry.phase = Phase.CANCELLING; entry.cancelSent = true
                entry.operation
            }
        } ?: return
        cancelOperation(entry, operation)
    }

    private fun registered(entry: Entry<W, O>): Boolean =
        entries[entry.lease] === entry && entry.operation?.let { operations[entry.ticket] === it } == true

    // Admission applies only to this still-owned transition. Evaluate the
    // caller's gate outside the monitor, then atomically claim either progress
    // or cancellation; a losing/stale invocation has no cancellation authority.
    private fun transition(entry: Entry<W, O>, expected: Phase, next: Phase): Pair<Boolean, O>? {
        val waiting = synchronized(lock) {
            registered(entry) && entry.phase == expected && entry.outcome == null
        }
        if (!waiting) return null
        val eligible = permitted(entry.admitted) && coordinator.current(entry.ticket)
        return synchronized(lock) {
            if (!registered(entry) || entry.phase != expected || entry.outcome != null) null
            else {
                if (eligible) entry.phase = next
                else { entry.phase = Phase.CANCELLING; entry.cancelSent = true }
                eligible to checkNotNull(entry.operation)
            }
        }
    }

    private fun activate(entry: Entry<W, O>): Boolean {
        val claimed = transition(entry, Phase.RESERVED, Phase.ACTIVATING) ?: return false
        if (!claimed.first) {
            cancelOperation(entry, claimed.second)
            return false
        }
        val posted = runCatching { dispatch { launch(entry) } }.getOrDefault(false)
        if (!posted) cancel(entry)
        return posted
    }

    private fun launch(entry: Entry<W, O>) {
        val claimed = transition(entry, Phase.ACTIVATING, Phase.ACTIVE) ?: return
        if (!claimed.first) cancelOperation(entry, claimed.second)
        else try { claimed.second.start() } catch (_: Throwable) { cancel(entry) }
    }

    private fun cancel(entry: Entry<W, O>) {
        val operation = synchronized(lock) {
            if (!registered(entry) || entry.cancelSent || entry.phase in setOf(Phase.FINISHING, Phase.FINISHED)) null
            else {
                entry.cancelSent = true
                entry.phase = Phase.CANCELLING
                entry.operation
            }
        } ?: return
        cancelOperation(entry, operation)
    }

    private fun cancelOperation(entry: Entry<W, O>, operation: O) {
        // Do not finish/invalidate coordinator work before this actual cancel.
        // In particular, commit-before-cancel may already have won the operation lock.
        try { operation.cancel() } catch (_: Throwable) { finishUnknown(entry) }
    }

    private fun failUnknown(entry: Entry<W, O>) {
        synchronized(lock) { if (entry.outcome == null) entry.forceUnknown = true }
        cancel(entry)
        finishUnknown(entry)
    }

    private fun finishUnknown(entry: Entry<W, O>) = complete(entry, unknown())

    private fun complete(entry: Entry<W, O>, received: AbemaRenewalOperationOutcome) {
        val accepted = synchronized(lock) {
            if (entry.operation == null) { if (entry.earlyOutcome == null) entry.earlyOutcome = received; false }
            else if (!registered(entry) || entry.outcome != null || entry.phase == Phase.FINISHING) false
            else {
                if (entry.phase in setOf(Phase.CONSTRUCTING, Phase.RESERVED)) entry.forceUnknown = true
                entry.phase = Phase.FINISHING
                true
            }
        }
        if (!accepted) return
        val outcome = if (synchronized(lock) { entry.forceUnknown }) unknown(received) else received
        // Notification/invalidation delivery cannot rewrite an actual cleanup verdict.
        runCatching { authoritative(entry.work, outcome) }
        synchronized(lock) {
            entry.outcome = outcome
            entry.phase = Phase.FINISHED
            if (outcome.cleanupConfirmed) {
                entry.operation?.let { operations.remove(entry.ticket, it) }
                entries.remove(entry.lease)
            }
        }
        coordinator.finish(entry.ticket, status(outcome.result), outcome.cleanupConfirmed,
            outcome.diagnostic, outcome.producerDiagnostic)
        if (synchronized(lock) { entry.delivered }) runCatching { entry.notify(outcome) }
    }

    override fun toString() = "AbemaRenewalOwnedAdmission(redacted)"

    companion object {
        private fun permitted(gate: () -> Boolean) = runCatching(gate).getOrDefault(false)
        internal fun unknown(prior: AbemaRenewalOperationOutcome? = null) = AbemaRenewalOperationOutcome(
            AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, false,
            prior?.diagnostic?.copy(cleanup = AbemaRenewalDiagnostic.Cleanup.UNCONFIRMED)
                ?: AbemaRenewalDiagnostic(AbemaRenewalDiagnostic.Phase.CLEANUP, AbemaRenewalDiagnostic.Reason.CLEANUP_REFUSED,
                    cleanup = AbemaRenewalDiagnostic.Cleanup.UNCONFIRMED), prior?.producerDiagnostic)
        internal fun status(result: AbemaAutomaticRenewalResult) = when (result) {
            AbemaAutomaticRenewalResult.SUCCESS -> AbemaRenewalReadinessCoordinator.Status.READY
            AbemaAutomaticRenewalResult.INTERACTION_REQUIRED -> AbemaRenewalReadinessCoordinator.Status.INTERACTION_REQUIRED
            AbemaAutomaticRenewalResult.STORAGE_FAILURE -> AbemaRenewalReadinessCoordinator.Status.STORAGE_FAILURE
            AbemaAutomaticRenewalResult.TEMPORARY_FAILURE -> AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE
            AbemaAutomaticRenewalResult.CANCELLED -> AbemaRenewalReadinessCoordinator.Status.FAILURE
        }
    }
}
