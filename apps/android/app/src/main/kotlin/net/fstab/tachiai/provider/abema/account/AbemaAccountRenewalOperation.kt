package net.fstab.tachiai.provider.abema.account

import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.provider.abema.AbemaCatalogRouteOwner
import net.fstab.tachiai.platform.network.ProviderStartupCleanupAdmission
import net.fstab.tachiai.platform.network.ProviderTransportEvidence
import net.fstab.tachiai.provider.abema.account.AbemaRenewalDiagnostic.Phase
import net.fstab.tachiai.provider.abema.account.AbemaRenewalDiagnostic.Reason
import net.fstab.tachiai.provider.abema.account.AbemaRenewalDiagnostic.Progress

internal class AbemaRenewalOperationOutcome(val result: AbemaAutomaticRenewalResult, val cleanupConfirmed: Boolean,
    val diagnostic: AbemaRenewalDiagnostic = AbemaRenewalDiagnostic(),
    val producerDiagnostic: AbemaRenewalDiagnostic? = null) {
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
    private val beforeApproval: (String, Long, () -> Boolean) -> ProviderStartupCleanupAdmission,
    private val completed: (AbemaRenewalOperationOutcome) -> Unit,
) {
    private val diagnostics = AbemaRenewalDiagnostics()
    private var producerDiagnostic: AbemaRenewalDiagnostic? = null
    @Volatile private var phase = Phase.ADMISSION
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
        diagnostics.progress(Progress.STARTED)
        val began = elapsedMs()
        if (began < 0 || deadline <= began || deadline - began > 90_000 || !scalarCurrent()) {
            requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE,
                if (began < 0 || synchronized(lock) { refusedClock }) Reason.CLOCK_REFUSED
                else if (deadline <= began) Reason.DEADLINE else Reason.REFUSED); return
        }
        delay(deadline - began) { requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, Reason.DEADLINE, Phase.LIFETIME) }
        execute {
            if (!originalCurrent()) {
                requestEnd(if (elapsedMs() >= deadline) AbemaAutomaticRenewalResult.TEMPORARY_FAILURE
                    else AbemaAutomaticRenewalResult.CANCELLED, Reason.OWNER_CHANGED)
                return@execute
            }
            phase = Phase.STARTUP_CLEANUP
            val admission = beforeApproval(owner.instance.id, deadline, ::originalCurrent)
            if (admission != ProviderStartupCleanupAdmission.READY || !originalCurrent()) {
                requestEnd(if (elapsedMs() >= deadline) AbemaAutomaticRenewalResult.TEMPORARY_FAILURE
                    else AbemaAutomaticRenewalResult.CANCELLED, Reason.REFUSED, startup = admission)
                return@execute
            }
            diagnostics.progress(Progress.STARTUP_READY)
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
                    phase = Phase.BROWSER_START
                    browser.start(AbemaApprovalBrowserRequest(owner.instance.id, owner.proof(), "https://abema.tv/account",
                        deadline, nonce, resetBrowserSession = false, automaticReturn = true), ::browserCompleted)
                } catch (_: Throwable) {
                    synchronized(lock) { cleanupUncertain = true; browserSettled = true }
                    requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, Reason.EXCEPTION)
                }
            }
        }
        watch()
    }

    private fun watch() {
        if (synchronized(lock) { ended || committed || requested != null }) return
        if (!scalarCurrent()) {
            val clockRefused = synchronized(lock) { refusedClock }
            val expired = clockRefused || elapsedMs() >= deadline
            requestEnd(if (expired)
                AbemaAutomaticRenewalResult.TEMPORARY_FAILURE else AbemaAutomaticRenewalResult.CANCELLED,
                if (clockRefused) Reason.CLOCK_REFUSED else if (expired) Reason.DEADLINE else Reason.OWNER_CHANGED,
                if (expired) Phase.LIFETIME else Phase.OPERATION)
            return
        }
        val check = synchronized(lock) { if (watching) false else { watching = true; true } }
        if (check) execute {
            try { if (!originalCurrent()) requestEnd(AbemaAutomaticRenewalResult.CANCELLED, Reason.OWNER_CHANGED, Phase.OPERATION) }
            finally { synchronized(lock) { watching = false } }
        }
        delay(1000, ::watch)
    }

    private fun browserCompleted(result: AbemaRenewalBrowserResult) {
        val accept = synchronized(lock) {
            if (ended || browserResultSeen) false else { browserResultSeen = true; true }
        }
        if (!accept) return
        val producer = browser.producerDiagnostic
        synchronized(lock) { if (producerDiagnostic == null) producerDiagnostic = producer }
        diagnostics.absorb(browser.diagnostic)
        val released = result.cleanupConfirmed && runCatching(browser::release).getOrDefault(false)
        if (!released) diagnostics.cleanup(false)
        synchronized(lock) {
            if (!released) cleanupUncertain = true
            browserSettled = true
        }
        if (!released || result != AbemaRenewalBrowserResult.DELIVERED || !scalarCurrent()) {
            requestEnd(if (result == AbemaRenewalBrowserResult.CANCELLED) AbemaAutomaticRenewalResult.CANCELLED
                else AbemaAutomaticRenewalResult.TEMPORARY_FAILURE,
                if (!released) Reason.CLEANUP_REFUSED else if (result == AbemaRenewalBrowserResult.CANCELLED) Reason.CANCELLED else Reason.REFUSED,
                if (!released) Phase.CLEANUP else Phase.BROWSER_START)
            return
        }
        val grant = synchronized(lock) { candidate.also { candidate = null } }
        if (grant == null) { requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, Reason.CANDIDATE_MISSING, Phase.CANDIDATE); return }
        diagnostics.progress(Progress.CANDIDATE_DELIVERED)
        execute { verifyAndAdopt(grant) }
    }

    private fun verifyAndAdopt(grant: AbemaAccountGrant) {
        phase = Phase.CANDIDATE
        if (!originalCurrent()) { requestEnd(AbemaAutomaticRenewalResult.CANCELLED, Reason.OWNER_CHANGED); return }
        if (decodedAbemaGrant(grant.bearer, grant.userId, wallMs()).expiresAtMs != grant.expiresAtMs) {
            requestEnd(AbemaAutomaticRenewalResult.CANCELLED, Reason.CANDIDATE_INVALID); return
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
            } catch (failure: AbemaAccountException) {
                diagnostics.refuse(phase, Reason.ACCOUNT_FAILURE, account = failure.category, transport = failure.transportFailure)
                throw failure
            } catch (failure: Throwable) {
                diagnostics.refuse(phase, Reason.EXCEPTION)
                throw failure
            } finally {
                try { owned.close() }
                catch (_: Throwable) {
                    diagnostics.cleanup(false)
                    diagnostics.refuse(Phase.CLEANUP, Reason.CLEANUP_REFUSED)
                    synchronized(lock) { cleanupUncertain = true; retained.add(owned) }
                    throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
                }
                finally { transport.compareAndSet(owned, null) }
            }
        }
        fun profileRefusal(value: AbemaAccountProfileResult) = when (value) {
            AbemaAccountProfileResult.Rejected -> Reason.PROFILE_REJECTED
            AbemaAccountProfileResult.Inconclusive -> Reason.PROFILE_INCONCLUSIVE
            else -> Reason.IDENTITY_MISMATCH
        }
        fun emailRefusal(value: AbemaAccountEmailResult) = when (value) {
            AbemaAccountEmailResult.UNREGISTERED -> Reason.EMAIL_UNREGISTERED
            AbemaAccountEmailResult.REJECTED -> Reason.EMAIL_REJECTED
            else -> Reason.EMAIL_INCONCLUSIVE
        }
        // Observe returned refusals before request's finally can fail closing
        // the transport. The result/adoption decisions below remain unchanged.
        phase = Phase.PROFILE
        val profile = request { transport -> transport.profile(grant.userId, grant.bearer).also { value ->
            if (value !is AbemaAccountProfileResult.Identity || value.id != grant.userId)
                diagnostics.refuse(Phase.PROFILE, profileRefusal(value))
        } }
        if (profile !is AbemaAccountProfileResult.Identity || profile.id != grant.userId) {
            requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, profileRefusal(profile)); return
        }
        diagnostics.progress(Progress.PROFILE_VERIFIED)
        phase = Phase.EMAIL
        val email = request { transport -> transport.emailRegistration(grant.userId, grant.bearer).also { value ->
            if (value != AbemaAccountEmailResult.REGISTERED) diagnostics.refuse(Phase.EMAIL, emailRefusal(value))
        } }
        if (email != AbemaAccountEmailResult.REGISTERED) {
            requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, emailRefusal(email)); return
        }
        diagnostics.progress(Progress.EMAIL_VERIFIED)
        phase = Phase.ADOPTION
        if (profile.id != expected.record?.grant?.userId) {
            // Positive, natively verified DIFFERENT registered account. This
            // means choose the saved account, never "an email code is needed".
            requestEnd(if (eligible()) AbemaAutomaticRenewalResult.INTERACTION_REQUIRED
                else AbemaAutomaticRenewalResult.CANCELLED, Reason.ACCOUNT_DIFFERENT)
            return
        }
        if (!eligible()) { requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE); return }
        if (!abemaRenewalCandidateExtends(expected, grant, wallMs())) {
            requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, Reason.CANDIDATE_NOT_EXTENDING); return
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
        if (!adopted) requestEnd(AbemaAutomaticRenewalResult.CANCELLED, Reason.ADOPTION_REFUSED)
        else diagnostics.progress(Progress.ADOPTED)
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
                }, Reason.ACCOUNT_FAILURE, account = failure.category, transportEvidence = failure.transportFailure)
            } catch (_: Throwable) { requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, Reason.EXCEPTION) }
            finally { synchronized(lock) { workers-- }; maybeFinish() }
        } } catch (_: Throwable) {
            synchronized(lock) { workers-- }
            requestEnd(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, Reason.EXCEPTION)
        }
    }

    fun cancel() = requestEnd(AbemaAutomaticRenewalResult.CANCELLED, Reason.CANCELLED, Phase.OPERATION)
    private fun requestEnd(result: AbemaAutomaticRenewalResult, reason: Reason = Reason.REFUSED, at: Phase = phase,
        account: AbemaAccountFailure? = null, transportEvidence: ProviderTransportEvidence? = null,
        startup: ProviderStartupCleanupAdmission? = null) {
        val begin = synchronized(lock) {
            if (ended || committed) return
            diagnostics.refuse(at, reason, account = account, transport = transportEvidence, startup = startup)
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
            diagnostics.cleanup(!cleanupUncertain)
            if (cleanupUncertain) diagnostics.refuse(Phase.CLEANUP, Reason.CLEANUP_REFUSED)
            AbemaRenewalOperationOutcome(if (cleanupUncertain) AbemaAutomaticRenewalResult.TEMPORARY_FAILURE else result,
                !cleanupUncertain, diagnostics.freeze(), producerDiagnostic)
        }
        inbox.unregister(nonce)
        synchronized(lock) { registered = false; candidate = null }
        post { completed(outcome) }
    }
    override fun toString() = "AbemaAccountRenewalOperation(redacted)"
}
