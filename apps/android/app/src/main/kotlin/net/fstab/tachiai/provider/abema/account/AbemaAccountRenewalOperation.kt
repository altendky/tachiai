package net.fstab.tachiai.provider.abema.account

import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.provider.abema.AbemaCatalogRouteOwner

internal class AbemaRenewalOperationOutcome(val result: AbemaAutomaticRenewalResult, val cleanupConfirmed: Boolean) {
    override fun toString() = "AbemaRenewalOperationOutcome(${result.name}, cleanupConfirmed=$cleanupConfirmed)"
}

// Owns a bounded renewal, not a foreground session or an account lease. UI
// observers may detach without changing its lifetime. No authority is restored
// after process death, and no candidate or pending operation is persisted.
internal class AbemaAccountRenewalOperation(
    private val binding: AbemaAccountConnectionBinding,
    private val owner: AbemaCatalogRouteOwner,
    private val expected: AbemaAccountSnapshot,
    private val deadline: Long,
    private val browser: AbemaRenewalBrowser,
    private val inbox: AbemaEmailGrantInbox,
    private val worker: Executor,
    private val cancellation: Executor,
    private val post: (() -> Unit) -> Unit,
    private val delay: (Long, () -> Unit) -> Unit,
    private val elapsedMs: () -> Long,
    private val wallMs: () -> Long,
    private val scopeCurrent: () -> Boolean,
    private val beforeApproval: (String, Long, () -> Boolean) -> Boolean,
    private val completed: (AbemaRenewalOperationOutcome) -> Unit,
) {
    private val lock = Any()
    private val nonce = UUID.randomUUID().toString().replace("-", "")
    private val transport = AtomicReference<AbemaAccountTransport?>()
    private var lastElapsed = -1L
    private var lastWall = -1L
    private var refusedClock = false
    private var started = false
    private var ended = false
    private var revoked = false
    private var committed = false
    private var requested: AbemaAutomaticRenewalResult? = null
    private var workers = 0
    private var watching = false
    private var browserStarted = false
    private var browserResultSeen = false
    private var browserSettled = false
    private var cleanupUncertain = false
    private var cleanupAlarm = false
    private var registered = false
    private var candidate: AbemaAccountGrant? = null
    private val retained = mutableListOf<Any>()

    // Only scalar time/configuration checks. In particular this gate is safe
    // inside the store CAS; protected registry reads belong outside that lock.
    private fun scalarCurrent(): Boolean {
        val local = synchronized(lock) {
            val elapsed = elapsedMs()
            val wall = wallMs()
            if (elapsed < 0 || wall < 0 || elapsed < lastElapsed || wall < lastWall) refusedClock = true
            lastElapsed = maxOf(lastElapsed, elapsed); lastWall = maxOf(lastWall, wall)
            !ended && !revoked && !committed && !refusedClock && elapsed < deadline
        }
        return local && binding.routeCurrent && scopeCurrent()
    }
    private fun originalCurrent(): Boolean = scalarCurrent() && binding.sameOwner(owner) &&
        binding.store.usable(expected.revision) && abemaRenewalSnapshotMatches(expected,
            binding.store.readSnapshot(), wallMs()) && binding.store.usable(expected.revision) && scalarCurrent()

    fun start() {
        synchronized(lock) { check(!started); started = true }
        val began = elapsedMs()
        if (began < 0 || deadline <= began || deadline - began > 90_000 || !scalarCurrent()) {
            requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE); return
        }
        delay(deadline - began) { requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE) }
        execute {
            if (!originalCurrent() || !beforeApproval(owner.instance.id, deadline, ::originalCurrent) || !originalCurrent()) {
                requestEnd(if (elapsedMs() >= deadline) AbemaAutomaticRenewalResult.TEMPORARY_FAILURE
                    else AbemaAutomaticRenewalResult.CANCELLED)
                return@execute
            }
            if (!scalarCurrent()) { requestEnd(AbemaAutomaticRenewalResult.CANCELLED); return@execute }
            inbox.register(nonce, owner.instance.id, owner.proof(), deadline) { value ->
                // The inbox invokes this under its own cancellation lock.
                // Capture only scalars; validation follows browser cleanup.
                synchronized(lock) { if (!ended && !revoked && candidate == null) candidate = value }
            }
            val keep = synchronized(lock) {
                if (ended || revoked) false else { registered = true; true }
            }
            if (!keep) { inbox.unregister(nonce); return@execute }
            post {
                val launch = synchronized(lock) {
                    if (!scalarCurrent() || !registered) false else { browserStarted = true; true }
                }
                if (!launch) {
                    requestEnd(AbemaAutomaticRenewalResult.CANCELLED); return@post
                }
                try {
                    browser.start(AbemaApprovalBrowserRequest(owner.instance.id, owner.proof(), "https://abema.tv/account",
                        deadline, nonce, resetBrowserSession = false, automaticReturn = true), ::browserCompleted)
                } catch (_: Throwable) {
                    synchronized(lock) { cleanupUncertain = true; browserSettled = true }
                    requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE)
                }
            }
        }
        watch()
    }

    private fun watch() {
        if (synchronized(lock) { ended || committed || requested != null }) return
        if (!scalarCurrent()) {
            requestEnd(if (synchronized(lock) { refusedClock } || elapsedMs() >= deadline)
                AbemaAutomaticRenewalResult.TEMPORARY_FAILURE else AbemaAutomaticRenewalResult.CANCELLED)
            return
        }
        val check = synchronized(lock) { if (watching) false else { watching = true; true } }
        if (check) execute {
            try { if (!originalCurrent()) requestEnd(AbemaAutomaticRenewalResult.CANCELLED) }
            finally { synchronized(lock) { watching = false } }
        }
        delay(1000, ::watch)
    }

    private fun browserCompleted(result: AbemaRenewalBrowserResult) {
        val accept = synchronized(lock) {
            if (ended || browserResultSeen) false else { browserResultSeen = true; true }
        }
        if (!accept) return
        val released = result.cleanupConfirmed && runCatching(browser::release).getOrDefault(false)
        synchronized(lock) {
            if (!released) cleanupUncertain = true
            browserSettled = true
        }
        if (!released || result != AbemaRenewalBrowserResult.DELIVERED || !scalarCurrent()) {
            requestEnd(if (result == AbemaRenewalBrowserResult.CANCELLED) AbemaAutomaticRenewalResult.CANCELLED
                else AbemaAutomaticRenewalResult.TEMPORARY_FAILURE)
            return
        }
        val grant = synchronized(lock) { candidate.also { candidate = null } }
        if (grant == null) { requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE); return }
        execute { verifyAndAdopt(grant) }
    }

    private fun verifyAndAdopt(grant: AbemaAccountGrant) {
        if (!originalCurrent() || decodedAbemaGrant(grant.bearer, grant.userId, wallMs()).expiresAtMs != grant.expiresAtMs) {
            requestEnd(AbemaAutomaticRenewalResult.CANCELLED); return
        }
        fun eligible() = originalCurrent() && wallMs() < grant.expiresAtMs
        fun <T> request(action: (AbemaAccountTransport) -> T): T {
            if (!eligible()) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            val owned = binding.transport(owner, ::eligible)
            check(transport.compareAndSet(null, owned))
            try {
                val value = action(owned)
                if (!eligible()) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
                return value
            } finally {
                try { owned.close() }
                catch (_: Throwable) {
                    synchronized(lock) { cleanupUncertain = true; retained.add(owned) }
                    throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
                }
                finally { transport.compareAndSet(owned, null) }
            }
        }
        val profile = request { it.profile(grant.userId, grant.bearer) }
        if (profile !is AbemaAccountProfileResult.Identity || profile.id != grant.userId) {
            requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE); return
        }
        val email = request { it.emailRegistration(grant.userId, grant.bearer) }
        if (email != AbemaAccountEmailResult.REGISTERED) {
            requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE); return
        }
        if (profile.id != expected.record?.grant?.userId) {
            // Positive, natively verified DIFFERENT registered account. This
            // means choose the saved account, never "an email code is needed".
            requestEnd(if (eligible()) AbemaAutomaticRenewalResult.INTERACTION_REQUIRED
                else AbemaAutomaticRenewalResult.CANCELLED)
            return
        }
        if (!eligible() || !abemaRenewalCandidateExtends(expected, grant, wallMs())) {
            requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE); return
        }
        val adopted = withAbemaAccountReplacement(binding.store, grant, profile.id, expected.revision,
            checkNotNull(expected.record).acceptedWallMs, wallMs, canCommit = ::scalarCurrent) { receipt, _ ->
            val replacement = receipt.record
            val verified = replacement != null && scalarCurrent() && binding.sameOwner(owner) &&
                replacement.generation == checkNotNull(expected.record).generation + 1 &&
                abemaReplacementReceiptCurrent(binding.store, receipt) && scalarCurrent()
            synchronized(lock) {
                if (!verified || !scalarCurrent() || wallMs() !in lastWall until grant.expiresAtMs ||
                    !binding.store.usable(receipt.revision)) false
                else {
                    // This is the logical completion point. Cancellation after
                    // it is a subsequent action; earlier cancellation rolls back.
                    committed = true; requested = AbemaAutomaticRenewalResult.SUCCESS; true
                }
            }
        }
        if (!adopted) requestEnd(AbemaAutomaticRenewalResult.CANCELLED)
    }

    private fun execute(action: () -> Unit) {
        synchronized(lock) { if (ended) return; workers++ }
        try { worker.execute {
            try { action() }
            catch (failure: AbemaAccountException) {
                if (failure.category == AbemaAccountFailure.CLEANUP_FAILED) synchronized(lock) { cleanupUncertain = true }
                requestEnd(when (failure.category) {
                    AbemaAccountFailure.STORAGE_FAILED -> AbemaAutomaticRenewalResult.STORAGE_FAILURE
                    AbemaAccountFailure.CANCELLED -> AbemaAutomaticRenewalResult.CANCELLED
                    else -> AbemaAutomaticRenewalResult.TEMPORARY_FAILURE
                })
            } catch (_: Throwable) { requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE) }
            finally { synchronized(lock) { workers-- }; maybeFinish() }
        } } catch (_: Throwable) {
            synchronized(lock) { workers-- }
            requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE)
        }
    }

    fun cancel() = requestEnd(AbemaAutomaticRenewalResult.CANCELLED)
    private fun requestEnd(result: AbemaAutomaticRenewalResult) {
        val begin = synchronized(lock) {
            if (ended || committed) return
            revoked = true
            if (requested == null) requested = result
            val first = !cleanupAlarm; cleanupAlarm = true; first
        }
        // Never enter the inbox while holding the operation monitor: delivery
        // takes its cancellation lock before invoking the scalar receiver.
        inbox.unregister(nonce)
        synchronized(lock) { registered = false; candidate = null }
        post { if (synchronized(lock) { browserStarted && !browserSettled }) runCatching(browser::cancel) }
        transport.get()?.let { owned ->
            try { cancellation.execute { runCatching(owned::cancelActiveRequest) } }
            catch (_: Throwable) { synchronized(lock) { cleanupUncertain = true } }
        }
        if (begin) delay(7000) {
            synchronized(lock) {
                if (!ended && (workers != 0 || browserStarted && !browserSettled || transport.get() != null)) cleanupUncertain = true
            }
            maybeFinish(force = true)
        }
        maybeFinish()
    }
    private fun maybeFinish(force: Boolean = false) {
        val outcome = synchronized(lock) {
            val result = requested ?: return
            if (ended || !force && (workers != 0 || browserStarted && !browserSettled || transport.get() != null)) return
            ended = true
            AbemaRenewalOperationOutcome(if (cleanupUncertain) AbemaAutomaticRenewalResult.TEMPORARY_FAILURE else result,
                !cleanupUncertain)
        }
        inbox.unregister(nonce)
        synchronized(lock) { registered = false; candidate = null }
        post { completed(outcome) }
    }
    override fun toString() = "AbemaAccountRenewalOperation(redacted)"
}
