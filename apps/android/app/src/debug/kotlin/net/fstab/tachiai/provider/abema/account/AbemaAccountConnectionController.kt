package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.fstab.tachiai.provider.abema.AbemaCatalogRouteOwner
import net.fstab.tachiai.platform.diagnostics.*

internal interface AbemaAccountConnectionBinding {
    val store: AbemaAccountGrantStore
    // Memory-only configuration admission, independent of foreground/cleanup.
    val routeCurrent: Boolean get() = true
    fun owner(): AbemaCatalogRouteOwner
    fun sameOwner(owner: AbemaCatalogRouteOwner): Boolean
    fun bootstrap(owner: AbemaCatalogRouteOwner, gate: () -> Boolean, foreground: () -> Boolean): AbemaAccountBootstrap
    fun transport(owner: AbemaCatalogRouteOwner, gate: () -> Boolean): AbemaAccountTransport
}
internal enum class AbemaAccountProgress { IDLE, READING, PREPARING, CHECKING, APPROVAL_READY, WAITING_APPROVAL, POLLING, FORGETTING, ENFORCEMENT_PROVEN, ENFORCEMENT_NOT_PROVEN }
internal data class AbemaAccountConnectionState(val status: AbemaAccountStatus = AbemaAccountStatus.MISSING,
    val progress: AbemaAccountProgress = AbemaAccountProgress.READING, val busy: Boolean = false,
    val canApprove: Boolean = false, val canContinue: Boolean = false)

// StateFlow contains finite public categories only. Approved identity, browser
// destination and grants remain private and are never restored after death.
internal class AbemaAccountConnectionController(
    private val binding: AbemaAccountConnectionBinding,
    private val worker: Executor, private val cancellation: Executor,
    private val post: (() -> Unit) -> Unit, private val delay: (Long, () -> Unit) -> Unit,
    private val elapsedMs: () -> Long, private val wallMs: () -> Long,
    initiallyForeground: Boolean = true,
    private val diagnostics: FailureReporter = FailureReporter.NONE,
    private val emailInbox: AbemaEmailGrantInbox? = null,
    private val accountAccess: ((AbemaCatalogRouteOwner) -> AbemaAccountAccessOwner.Attachment)? = null,
    private val beforeEmailApproval: (String, Long, () -> Boolean) -> Boolean = { _, _, gate -> gate() },
) : AutoCloseable {
    private val lock = Any()
    @Volatile private var foreground = initiallyForeground
    @Volatile private var closed = false
    private var revision = 0L
    private var busy = false
    private val forgetting = AtomicBoolean()
    private val transport = AtomicReference<AbemaAccountTransport?>()
    private val bootstrap = AtomicReference<AbemaAccountBootstrap?>()
    private class SharedAccess(val attachment: AbemaAccountAccessOwner.Attachment) {
        @Volatile var lease: AbemaAccountAccessLease? = null
    }
    private val sharedAccess = AtomicReference<SharedAccess?>()
    private class Link(val challenge: AbemaAccountChallenge, val owner: AbemaCatalogRouteOwner,
        val storeRevision: Long) {
        var handedOff = false; var polls = 0; var pollingBegan: Long? = null; var nextPollAt = 0L
        override fun toString() = "AbemaAccountConnectionLink(redacted)"
    }
    @Volatile private var link: Link? = null
    private class EmailLink(val owner: AbemaCatalogRouteOwner, val nonce: String,
        val deadline: Long, val storeRevision: Long, val renewal: Renewal? = null) {
        var handedOff = false
        var validating = false
        var candidate: AbemaAccountGrant? = null
        var browserReturned = false
        override fun toString() = "AbemaEmailConnectionLink(redacted)"
    }
    @Volatile private var emailLink: EmailLink? = null
    private class Renewal(val expected: AbemaAccountSnapshot, val beganElapsed: Long, val deadline: Long, wall: Long,
        val completed: (AbemaAutomaticRenewalResult) -> Unit) {
        val finished = AtomicBoolean()
        val lastWall = AtomicLong(wall)
        val lastElapsed = AtomicLong(beganElapsed)
        val refusedClock = AtomicBoolean()
        fun clock(elapsed: Long, wall: Long): Boolean {
            if (elapsed < lastElapsed.getAndUpdate { maxOf(it, elapsed) } || wall < lastWall.getAndUpdate { maxOf(it, wall) } ||
                elapsed < 0 || wall < 0) refusedClock.set(true)
            return !refusedClock.get()
        }
        override fun toString() = "AbemaAutomaticRenewal(redacted)"
    }
    @Volatile private var automaticRenewal: Renewal? = null
    private val session = AbemaAccountSession(binding.store, wallMs) {
        !closed && foreground && capturedOwner?.let(binding::sameOwner) == true
    }
    @Volatile private var capturedOwner: AbemaCatalogRouteOwner? = null
    private val mutable = MutableStateFlow(AbemaAccountConnectionState())
    val state: StateFlow<AbemaAccountConnectionState> = mutable
    private fun pollDeadline(prepared: Link): Long {
        val began = prepared.pollingBegan ?: return prepared.challenge.deadlineElapsedMs
        val bounded = if (began > Long.MAX_VALUE - 300000) Long.MAX_VALUE else began + 300000
        return minOf(prepared.challenge.deadlineElapsedMs, bounded)
    }
    private fun publish(progress: AbemaAccountProgress = AbemaAccountProgress.IDLE) {
        if (sharedAccess.get()?.lease?.current() == false) session.invalidateValidation()
        mutable.value = AbemaAccountConnectionState(session.status(), progress, busy || forgetting.get(),
            !busy && foreground && link?.let { !it.handedOff && elapsedMs() < it.challenge.deadlineElapsedMs } == true,
            !busy && foreground && (emailLink?.let { it.handedOff && it.candidate != null && elapsedMs() < it.deadline } == true ||
                link?.let { it.handedOff && elapsedMs() >= it.nextPollAt && elapsedMs() < pollDeadline(it) } == true))
    }
    private fun current(run: Long) = synchronized(lock) { !closed && foreground && revision == run }
    private fun same(run: Long, owner: AbemaCatalogRouteOwner) = current(run) && binding.sameOwner(owner) && current(run)
    private fun run(progress: AbemaAccountProgress, action: (Long) -> Unit) { runAction(progress, action) }
    private fun runAction(progress: AbemaAccountProgress, action: (Long) -> Unit): Boolean {
        val run = synchronized(lock) {
            if (closed || !foreground || busy || forgetting.get()) return false
            busy = true; ++revision
        }
        publish(progress)
        worker.execute {
            try { action(run) }
            catch (error: AbemaAccountException) {
                diagnostics.report(when (error.category) {
                    AbemaAccountFailure.STORAGE_FAILED -> FailureStage.CATALOG_AUTH_STORAGE
                    AbemaAccountFailure.CLEANUP_FAILED -> FailureStage.CATALOG_AUTH_CLEANUP
                    else -> FailureStage.CATALOG_AUTH_REQUEST
                })
                if (current(run)) { detachAccess(run); link = null
                    val result = renewalFailure(error.category)
                    if (!clearEmailLink(result = result)) automaticRenewal?.let { completeRenewal(it, result) }
                    session.failed(error.category)
                }
            }
            catch (_: Exception) { diagnostics.report(FailureStage.CATALOG_AUTH_REQUEST); if (current(run)) { detachAccess(run); link = null
                if (!clearEmailLink(result = AbemaAutomaticRenewalResult.TEMPORARY_FAILURE))
                    automaticRenewal?.let { completeRenewal(it, AbemaAutomaticRenewalResult.TEMPORARY_FAILURE) }
                session.failed(AbemaAccountFailure.TEMPORARY)
            } }
            finally { post { synchronized(lock) {
                if (revision == run && busy) { busy = false; if (!closed) publish() }
            } } }
        }
        return true
    }
    private fun maintenanceAllowed() = link == null && emailLink == null && automaticRenewal == null && !forgetting.get()
    fun load() {
        if (!maintenanceAllowed()) return
        run(AbemaAccountProgress.READING, ::validateSaved)
    }
    fun connectEmail(launch: (AbemaApprovalBrowserRequest) -> Boolean) = beginEmail(false, launch)
    fun signInAgain(launch: (AbemaApprovalBrowserRequest) -> Boolean) = beginEmail(true, launch)
    fun renewEmail(expectedSnapshot: AbemaAccountSnapshot, launch: (AbemaApprovalBrowserRequest) -> Boolean,
        onComplete: (AbemaAutomaticRenewalResult) -> Unit, originalDeadlineElapsedMs: Long? = null) {
        val renewal = try {
            val began = elapsedMs()
            if (began < 0 || began > Long.MAX_VALUE - 90000) {
                post { runCatching { onComplete(AbemaAutomaticRenewalResult.INTERACTION_REQUIRED) } }; return
            }
            val deadline = minOf(began + 90000, originalDeadlineElapsedMs ?: Long.MAX_VALUE)
            if (deadline <= began) {
                post { runCatching { onComplete(AbemaAutomaticRenewalResult.INTERACTION_REQUIRED) } }; return
            }
            Renewal(expectedSnapshot, began, deadline, wallMs(), onComplete)
        }
        catch (_: Exception) { post { runCatching { onComplete(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE) } }; return }
        val admitted = synchronized(lock) {
            if (closed || !foreground || busy || forgetting.get() || link != null || emailLink != null || automaticRenewal != null) false
            else { automaticRenewal = renewal; true }
        }
        if (!admitted) { completeRenewal(renewal, AbemaAutomaticRenewalResult.CANCELLED); return }
        try { beginEmail(false, launch, renewal) }
        catch (_: Exception) {
            synchronized(lock) { if (automaticRenewal === renewal) { revision++; busy = false } }
            completeRenewal(renewal, AbemaAutomaticRenewalResult.TEMPORARY_FAILURE)
            if (!closed) publish()
        }
    }
    private fun beginEmail(resetBrowserSession: Boolean, launch: (AbemaApprovalBrowserRequest) -> Boolean,
        renewal: Renewal? = null) {
        val admitted = runAction(AbemaAccountProgress.PREPARING) { run ->
        if (renewal != null) {
            if (!current(run) || !renewal.clock(elapsedMs(), wallMs())) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            if (elapsedMs() >= renewal.deadline) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
        }
        detachAccess(run)
        val inbox = emailInbox ?: throw AbemaAccountException(AbemaAccountFailure.NOT_VERIFIED)
        val owner = binding.owner()
        if (!same(run, owner)) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
        val now = elapsedMs()
        val lifetime = if (renewal == null) 600000L else 90000L
        if (now < 0 || now > Long.MAX_VALUE - lifetime) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
        if (renewal != null && (!renewal.clock(now, wallMs()) || automaticRenewal !== renewal ||
            !renewalOriginalCurrent(renewal))) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
        val expires = renewal?.deadline ?: now + lifetime
        if (now >= expires)
            throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
        val prepared = EmailLink(owner, UUID.randomUUID().toString().replace("-", ""), expires,
            renewal?.expected?.revision ?: binding.store.revision(), renewal)
        val prior = synchronized(lock) {
            if (!current(run)) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            emailLink.also { emailLink = prepared }
        }
        prior?.let { inbox.unregister(it.nonce); it.candidate = null; it.renewal?.let { value -> completeRenewal(value, AbemaAutomaticRenewalResult.CANCELLED) } }
        inbox.register(prepared.nonce, owner.instance.id, owner.proof(), prepared.deadline) { candidate ->
            synchronized(lock) {
                if (closed || emailLink !== prepared || !prepared.handedOff || elapsedMs() >= prepared.deadline) return@register
                prepared.candidate = candidate
            }
            publish(AbemaAccountProgress.WAITING_APPROVAL)
        }
        if (!current(run) || emailLink !== prepared) {
            inbox.unregister(prepared.nonce)
            throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
        }
        // This worker remains in the main app process, which owns catalog
        // retirement. The separate browser process cannot observe that registry.
        fun approvalCurrent() = same(run, owner) && emailLink === prepared &&
            elapsedMs() < prepared.deadline && binding.store.usable(prepared.storeRevision) &&
            (renewal == null || renewalOriginalCurrent(renewal))
        if (!beforeEmailApproval(owner.instance.id, prepared.deadline, ::approvalCurrent) || !approvalCurrent())
            throw AbemaAccountException(if (elapsedMs() >= prepared.deadline) AbemaAccountFailure.EXPIRED
                else if (!same(run, owner)) AbemaAccountFailure.CANCELLED else AbemaAccountFailure.TEMPORARY)
        post { if (current(run) && emailLink === prepared) {
            if (elapsedMs() >= prepared.deadline) { failEmail(prepared, AbemaAccountFailure.EXPIRED); return@post }
            if (renewal != null) {
                if (!renewal.clock(elapsedMs(), wallMs())) { failEmail(prepared, AbemaAccountFailure.CANCELLED); return@post }
                if (elapsedMs() >= prepared.deadline) { failEmail(prepared, AbemaAccountFailure.EXPIRED); return@post }
            }
            capturedOwner = owner; prepared.handedOff = true; busy = false
            publish(AbemaAccountProgress.WAITING_APPROVAL)
            val request = AbemaApprovalBrowserRequest(owner.instance.id, owner.proof(), "https://abema.tv/account",
                prepared.deadline, prepared.nonce, resetBrowserSession, automaticReturn = renewal != null)
            val launched = if (renewal == null) launch(request) else runCatching { launch(request) }.getOrDefault(false)
            if (!launched) failEmail(prepared, AbemaAccountFailure.TEMPORARY)
        } else inbox.unregister(prepared.nonce) }
        delay(prepared.deadline - elapsedMs()) {
            if (elapsedMs() >= prepared.deadline) failEmail(prepared, AbemaAccountFailure.EXPIRED)
        }
        }
        if (!admitted && renewal != null) completeRenewal(renewal, AbemaAutomaticRenewalResult.CANCELLED)
    }
    fun emailBrowserReturned() {
        val prepared = synchronized(lock) {
            emailLink?.takeIf { !closed && it.handedOff }?.also { if (it.candidate != null) it.browserReturned = true }
        } ?: return
        if (prepared.candidate == null) {
            if (prepared.renewal != null) failEmail(prepared, AbemaAccountFailure.NOT_VERIFIED)
            return
        }
        if (foreground && emailLink === prepared) finishEmailConnection()
    }
    fun finishEmailConnection() {
        val prepared = synchronized(lock) { emailLink?.takeIf { it.handedOff && it.candidate != null } } ?: return
        run(AbemaAccountProgress.CHECKING) { run ->
            synchronized(lock) { prepared.browserReturned = false; prepared.validating = true }
            val grant = checkNotNull(prepared.candidate)
            val beganWall = wallMs()
            val renewal = prepared.renewal
            if (renewal != null && (!renewal.clock(elapsedMs(), beganWall) || !renewalOriginalCurrent(renewal)))
                throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            if (renewal != null && !abemaRenewalCandidateExtends(renewal.expected, grant, beganWall))
                throw AbemaAccountException(AbemaAccountFailure.REFUSED)
            if (decodedAbemaGrant(grant.bearer, grant.userId, beganWall).expiresAtMs != grant.expiresAtMs)
                throw AbemaAccountException(AbemaAccountFailure.REFUSED)
            fun grantCurrent(): Boolean {
                val now = wallMs()
                if (renewal != null && !renewal.clock(elapsedMs(), now)) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
                if (now < beganWall || now >= grant.expiresAtMs) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
                return emailLink === prepared && binding.store.usable(prepared.storeRevision) &&
                    (renewal == null || renewalOriginalCurrent(renewal))
            }
            val profile = request(run, prepared.owner, prepared.deadline, ::grantCurrent) { it.profile(grant.userId, grant.bearer) }
            if (renewal != null && profile === AbemaAccountProfileResult.Inconclusive)
                throw AbemaAccountException(AbemaAccountFailure.TEMPORARY)
            if (profile !is AbemaAccountProfileResult.Identity || profile.id != grant.userId)
                throw AbemaAccountException(AbemaAccountFailure.REFUSED)
            val email = request(run, prepared.owner, prepared.deadline, ::grantCurrent) { it.emailRegistration(grant.userId, grant.bearer) }
            if (renewal != null && email == AbemaAccountEmailResult.INCONCLUSIVE)
                throw AbemaAccountException(AbemaAccountFailure.TEMPORARY)
            if (email != AbemaAccountEmailResult.REGISTERED) throw AbemaAccountException(AbemaAccountFailure.REFUSED)
            if (!session.acceptReplacement(grant, profile.id, prepared.storeRevision) {
                    same(run, prepared.owner) && emailLink === prepared && elapsedMs() < prepared.deadline &&
                        (renewal == null || automaticRenewal === renewal && renewal.clock(elapsedMs(), wallMs()))
                }) throw AbemaAccountException(if (renewal != null && (!same(run, prepared.owner) || emailLink !== prepared ||
                    !renewal.clock(elapsedMs(), wallMs())))
                    AbemaAccountFailure.CANCELLED else AbemaAccountFailure.STORAGE_FAILED)
            val replacement = if (renewal == null) null else binding.store.readSnapshot().also { saved ->
                val value = saved.record
                if (!same(run, prepared.owner) || emailLink !== prepared || !renewal.clock(elapsedMs(), wallMs()) ||
                    elapsedMs() >= prepared.deadline || !binding.store.usable(saved.revision) || value == null ||
                    value.grant.userId != grant.userId || value.grant.bearer != grant.bearer || value.grant.expiresAtMs != grant.expiresAtMs ||
                    value.generation != checkNotNull(renewal.expected.record).generation + 1 || value.acceptedWallMs < beganWall)
                    throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            }
            post { if (current(run) && emailLink === prepared) {
                if (renewal == null) clearEmailLink()
                else if (elapsedMs() >= prepared.deadline) clearEmailLink(prepared, AbemaAutomaticRenewalResult.INTERACTION_REQUIRED)
                else clearEmailLink(prepared, AbemaAutomaticRenewalResult.SUCCESS) {
                    current(run) && elapsedMs() < prepared.deadline && binding.store.usable(checkNotNull(replacement).revision) &&
                        session.status() == AbemaAccountStatus.CONNECTED && renewal.clock(elapsedMs(), wallMs()) &&
                        wallMs() < grant.expiresAtMs && elapsedMs() < prepared.deadline
                }
                publish()
            } }
        }
    }
    private fun failEmail(prepared: EmailLink, category: AbemaAccountFailure) {
        val run = synchronized(lock) {
            if (closed || emailLink !== prepared) return
            revision
        }
        worker.execute {
            if (!clearEmailLink(prepared, renewalFailure(category)) || !current(run) || synchronized(lock) { busy }) return@execute
            // Failure can preserve a validated prior grant, which requires a
            // protected owner check. Never evaluate it in a UI callback.
            session.failed(category)
            post { if (current(run) && emailLink == null) publish() }
        }
    }
    private fun clearEmailLink(expected: EmailLink? = null, result: AbemaAutomaticRenewalResult = AbemaAutomaticRenewalResult.CANCELLED,
        successAllowed: () -> Boolean = { true }): Boolean {
        val prior = synchronized(lock) {
            if (expected != null && emailLink !== expected) return false
            emailLink.also { emailLink = null }
        } ?: return false
        emailInbox?.unregister(prior.nonce); prior.candidate = null
        prior.renewal?.let { completeRenewal(it, result, successAllowed) }
        return true
    }
    private fun renewalOriginalCurrent(renewal: Renewal): Boolean {
        val now = wallMs()
        return automaticRenewal === renewal && renewal.clock(elapsedMs(), now) && binding.store.usable(renewal.expected.revision) &&
            abemaRenewalSnapshotMatches(renewal.expected, binding.store.readSnapshot(), now) &&
            binding.store.usable(renewal.expected.revision)
    }
    private fun renewalFailure(category: AbemaAccountFailure): AbemaAutomaticRenewalResult = when (category) {
        AbemaAccountFailure.STORAGE_FAILED -> AbemaAutomaticRenewalResult.STORAGE_FAILURE
        AbemaAccountFailure.CANCELLED -> AbemaAutomaticRenewalResult.CANCELLED
        AbemaAccountFailure.REFUSED, AbemaAccountFailure.NOT_VERIFIED, AbemaAccountFailure.EXPIRED,
        AbemaAccountFailure.TIME_LIMIT_REACHED -> AbemaAutomaticRenewalResult.INTERACTION_REQUIRED
        else -> AbemaAutomaticRenewalResult.TEMPORARY_FAILURE
    }
    private fun completeRenewal(renewal: Renewal, result: AbemaAutomaticRenewalResult,
        successAllowed: () -> Boolean = { true }) {
        if (!renewal.finished.compareAndSet(false, true)) return
        synchronized(lock) { if (automaticRenewal === renewal) automaticRenewal = null }
        post {
            val value = if (result == AbemaAutomaticRenewalResult.SUCCESS && !runCatching(successAllowed).getOrDefault(false))
                AbemaAutomaticRenewalResult.CANCELLED else result
            runCatching { renewal.completed(value) }
        }
    }
    private fun prepare(run: Long, owner: AbemaCatalogRouteOwner): AbemaAccountChallenge {
        val prepared = binding.bootstrap(owner, { same(run, owner) }, { current(run) })
        bootstrap.set(prepared)
        try { return prepared.prepare() ?: throw AbemaAccountException(AbemaAccountFailure.NOT_VERIFIED) }
        finally { try { prepared.close() } finally { bootstrap.compareAndSet(prepared, null) } }
    }
    private fun <T> request(run: Long, owner: AbemaCatalogRouteOwner, deadline: Long? = null,
        additionalGate: () -> Boolean = { true }, checkOperationTime: () -> Unit = {}, action: (AbemaAccountTransport) -> T): T {
        fun allowed(): Boolean {
            checkOperationTime()
            val permitted = same(run, owner) && additionalGate() && (deadline == null || elapsedMs() < deadline)
            checkOperationTime()
            return permitted
        }
        fun requireAllowed() {
            if (deadline != null && elapsedMs() >= deadline) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
            val permitted = allowed()
            if (deadline != null && elapsedMs() >= deadline) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
            if (!permitted) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
        }
        requireAllowed()
        val active = binding.transport(owner, ::allowed)
        transport.set(active)
        try {
            val result = action(active)
            requireAllowed()
            return result
        } finally { try { active.close() } finally { transport.compareAndSet(active, null) } }
    }
    private fun enforcement(run: Long, owner: AbemaCatalogRouteOwner, challenge: AbemaAccountChallenge,
        deadline: Long = challenge.deadlineElapsedMs): Boolean {
        if (elapsedMs() >= deadline) return false
        val positive = request(run, owner, deadline) { it.profile(challenge.guestId, challenge.guestBearer) }
        if (positive !is AbemaAccountProfileResult.Identity || positive.id != challenge.guestId) return false
        // Deliberately invalid public sentinel, never a saved/account token.
        val negative = request(run, owner, deadline) { it.profile(challenge.guestId, "tachiai-invalid-bearer") }
        return negative === AbemaAccountProfileResult.Rejected && same(run, owner) && elapsedMs() < deadline
    }
    // Explicit opt-in instrumentation only; never a prerequisite for setup or reuse.
    fun checkEnforcement() = run(AbemaAccountProgress.CHECKING) { run ->
        val owner = binding.owner(); val challenge = prepare(run, owner)
        val proven = enforcement(run, owner, challenge)
        post { if (current(run)) { busy = false; publish(if (proven) AbemaAccountProgress.ENFORCEMENT_PROVEN else AbemaAccountProgress.ENFORCEMENT_NOT_PROVEN) } }
    }
    fun connect() {
        run(AbemaAccountProgress.PREPARING) { run ->
            detachAccess(run)
            val owner = binding.owner(); val storeRevision = binding.store.revision()
            val challenge = prepare(run, owner)
            if (!same(run, owner)) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            post { if (current(run)) {
                capturedOwner = owner
                link = Link(challenge, owner, storeRevision)
                busy = false; publish(AbemaAccountProgress.APPROVAL_READY)
            } }
        }
    }
    // URI crosses only this explicit protected Activity callback. Foreground
    // loss retains the challenge but cancels all active requests and leases.
    fun handoff(launch: (String) -> Boolean) = handoffBrowser { launch(it.verificationUri) }
    fun handoffBrowser(launch: (AbemaApprovalBrowserRequest) -> Boolean) {
        val prepared = synchronized(lock) { link?.takeIf { !busy && foreground && !closed && !it.handedOff && elapsedMs() < it.challenge.deadlineElapsedMs } } ?: return
        run(AbemaAccountProgress.CHECKING) { run ->
            if (elapsedMs() >= prepared.challenge.deadlineElapsedMs) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
            if (!same(run, prepared.owner)) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            if (elapsedMs() >= prepared.challenge.deadlineElapsedMs) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
            post { if (current(run) && link === prepared) {
                if (elapsedMs() >= prepared.challenge.deadlineElapsedMs) {
                    link = null; busy = false
                    mutable.value = AbemaAccountConnectionState(AbemaAccountStatus.RECONNECT_REQUIRED, AbemaAccountProgress.IDLE)
                    return@post
                }
                prepared.handedOff = true; busy = false; publish(AbemaAccountProgress.WAITING_APPROVAL)
                if (elapsedMs() >= prepared.challenge.deadlineElapsedMs) {
                    link = null; prepared.handedOff = false
                    mutable.value = AbemaAccountConnectionState(AbemaAccountStatus.RECONNECT_REQUIRED, AbemaAccountProgress.IDLE)
                    return@post
                }
                if (!current(run) || link !== prepared) return@post
                val request = AbemaApprovalBrowserRequest(prepared.owner.instance.id, prepared.owner.proof(),
                    prepared.challenge.verificationUri, prepared.challenge.deadlineElapsedMs)
                if (!launch(request)) { prepared.handedOff = false; publish(AbemaAccountProgress.APPROVAL_READY) }
            } }
        }
    }
    fun continueApproval() {
        val prepared = synchronized(lock) { link?.takeIf { it.handedOff } } ?: return
        run(AbemaAccountProgress.POLLING) { run ->
            val now = elapsedMs()
            if (now < prepared.nextPollAt) return@run
            val began = prepared.pollingBegan ?: now.also { prepared.pollingBegan = it }
            val deadline = pollDeadline(prepared)
            if (link !== prepared || now < began || now >= deadline || prepared.polls >= 60)
                throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
            prepared.polls++
            when (val result = request(run, prepared.owner, deadline) { it.token(prepared.challenge) }) {
                AbemaAccountTokenResult.Pending -> {
                    prepared.nextPollAt = elapsedMs() + 5000
                    post { if (current(run) && link === prepared) {
                    if (elapsedMs() >= deadline) {
                        link = null; busy = false
                        mutable.value = AbemaAccountConnectionState(AbemaAccountStatus.RECONNECT_REQUIRED, AbemaAccountProgress.IDLE)
                        return@post
                    }
                    busy = false; publish(AbemaAccountProgress.WAITING_APPROVAL)
                    delay(5000) { if (current(run) && link === prepared) {
                        if (elapsedMs() >= deadline) {
                            link = null
                            mutable.value = AbemaAccountConnectionState(AbemaAccountStatus.RECONNECT_REQUIRED, AbemaAccountProgress.IDLE)
                        } else continueApproval()
                    } }
                    } }
                }
                is AbemaAccountTokenResult.Granted -> {
                    if (result.grant.userId == prepared.challenge.guestId) throw AbemaAccountException(AbemaAccountFailure.REFUSED)
                    val profile = request(run, prepared.owner, deadline) { it.profile(result.grant.userId, result.grant.bearer) }
                    if (profile !is AbemaAccountProfileResult.Identity || profile.id != result.grant.userId || profile.id == prepared.challenge.guestId)
                        throw AbemaAccountException(AbemaAccountFailure.REFUSED)
                    if (elapsedMs() >= deadline) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
                    if (!session.acceptReplacement(result.grant, profile.id, prepared.storeRevision) { same(run, prepared.owner) && elapsedMs() < deadline })
                        throw AbemaAccountException(if (elapsedMs() >= deadline) AbemaAccountFailure.EXPIRED else AbemaAccountFailure.STORAGE_FAILED)
                    post { if (current(run) && link === prepared) link = null }
                }
                is AbemaAccountTokenResult.Failed -> {
                    post { if (current(run) && link === prepared) link = null }
                    throw AbemaAccountException(result.category)
                }
            }
        }
    }
    fun revalidate() {
        if (!maintenanceAllowed()) return
        run(AbemaAccountProgress.CHECKING, ::validateSaved)
    }
    private fun validateSaved(run: Long) {
        if (!maintenanceAllowed() || !current(run)) return
        session.invalidateValidation()
        val began = elapsedMs()
        if (began < 0 || began > Long.MAX_VALUE - 30_000)
            throw AbemaAccountException(AbemaAccountFailure.TIME_LIMIT_REACHED)
        val deadline = began + 30_000
        fun checkTime() {
            val now = elapsedMs()
            if (now < began || now >= deadline) throw AbemaAccountException(AbemaAccountFailure.TIME_LIMIT_REACHED)
        }
        checkTime()
        val owner = binding.owner()
        checkTime()
        val matchedOwner = same(run, owner)
        checkTime()
        if (!matchedOwner) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
        capturedOwner = owner
        session.load()
        checkTime()
        val candidate = session.candidate()
        checkTime()
        val value = candidate ?: run { detachAccess(run); return }
        fun grantCurrent(): Boolean {
            checkTime()
            val matchedOwner = same(run, owner)
            checkTime()
            if (!matchedOwner) return false
            if (wallMs() >= value.grant.expiresAtMs) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
            val current = session.candidate() === value
            checkTime()
            val matched = current && same(run, owner)
            checkTime()
            return matched
        }
        post { synchronized(lock) { if (current(run) && busy) publish(AbemaAccountProgress.CHECKING) } }
        accountAccess?.let { factory ->
            val attached = factory(owner)
            val access = SharedAccess(attached)
            var installed = false
            val prior = synchronized(lock) {
                if (current(run)) { installed = true; sharedAccess.getAndSet(access) } else null
            }
            prior?.attachment?.close()
            if (!installed) { attached.close(); throw AbemaAccountException(AbemaAccountFailure.CANCELLED) }
            val acquired = attached.acquire(began, deadline)
            if (acquired is AbemaAccountAccessResult.Failed && acquired.category in
                setOf(AbemaAccountFailure.CLEANUP_FAILED, AbemaAccountFailure.EXPIRED))
                throw AbemaAccountException(acquired.category)
            checkTime()
            val lease = when (acquired) {
                is AbemaAccountAccessResult.Account -> acquired.lease
                is AbemaAccountAccessResult.Failed -> throw AbemaAccountException(acquired.category)
                is AbemaAccountAccessResult.Guest -> throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            }
            val grant = lease.grant ?: throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            if (grant.userId != value.grant.userId || grant.bearer != value.grant.bearer ||
                grant.expiresAtMs != value.grant.expiresAtMs || !lease.checkWorker())
                throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            checkTime()
            if (!attached.cleanupConfirmed) throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
            if (!grantCurrent() || !lease.current() || sharedAccess.get() !== access)
                throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            if (!session.acceptExisting(value, grant.userId)) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            try {
                if (!grantCurrent() || !lease.current() || sharedAccess.get() !== access)
                    throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
                access.lease = lease
            } catch (error: Throwable) {
                session.invalidateValidation()
                throw error
            }
            return
        }
        val profile = request(run, owner, additionalGate = ::grantCurrent, checkOperationTime = ::checkTime) {
            it.profile(value.grant.userId, value.grant.bearer)
        }
        checkTime()
        when (profile) {
            AbemaAccountProfileResult.Inconclusive -> throw AbemaAccountException(AbemaAccountFailure.TEMPORARY)
            AbemaAccountProfileResult.Rejected -> throw AbemaAccountException(AbemaAccountFailure.REFUSED)
            is AbemaAccountProfileResult.Identity -> if (profile.id != value.grant.userId)
                throw AbemaAccountException(AbemaAccountFailure.REFUSED)
        }
        val email = request(run, owner, additionalGate = ::grantCurrent, checkOperationTime = ::checkTime) {
            it.emailRegistration(value.grant.userId, value.grant.bearer)
        }
        checkTime()
        when (email) {
            AbemaAccountEmailResult.INCONCLUSIVE -> throw AbemaAccountException(AbemaAccountFailure.TEMPORARY)
            AbemaAccountEmailResult.REGISTERED -> Unit
            else -> throw AbemaAccountException(AbemaAccountFailure.REFUSED)
        }
        if (!grantCurrent()) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
        if (!session.acceptExisting(value, profile.id))
            throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
        try {
            if (!grantCurrent()) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
        } catch (error: Throwable) {
            // A final owner/time check can fail after accepting the matching
            // profile. Never let failed() preserve that just-admitted lease.
            session.invalidateValidation()
            throw error
        }
    }
    fun forget() {
        if (closed) return
        if (!forgetting.compareAndSet(false, true)) return
        cancel(false); session.forgetImmediately(); publish(AbemaAccountProgress.FORGETTING)
        worker.execute {
            try { binding.store.forget() } catch (_: Exception) { diagnostics.report(FailureStage.CATALOG_AUTH_STORAGE); session.failed(AbemaAccountFailure.STORAGE_FAILED) }
            finally { forgetting.set(false) }
            post { if (!closed) publish() }
        }
    }
    fun setForeground(value: Boolean) {
        var cancellation: Cancellation? = null
        val run = synchronized(lock) {
            if (closed || foreground == value) return
            foreground = value
            session.setForeground(value)
            if (!value) cancellation = revokeAction(true)
            publish()
            revision
        }
        cancellation?.let(::cancelResources)
        if (value && current(run)) {
            if (emailLink?.browserReturned == true) finishEmailConnection()
            val prepared = link
            if (prepared != null && prepared.nextPollAt > elapsedMs()) {
                delay(prepared.nextPollAt - elapsedMs()) { if (current(run) && link === prepared) publish() }
            }
            if (current(run) && maintenanceAllowed()) load()
        }
    }
    private fun detachAccess(run: Long? = null) {
        val prior = synchronized(lock) {
            if (run != null && !current(run)) return
            sharedAccess.getAndSet(null)
        }
        prior?.attachment?.close()
    }
    private class Cancellation(val bootstrap: AbemaAccountBootstrap?, val transport: AbemaAccountTransport?, val email: EmailLink?,
        val access: SharedAccess?, val renewal: Renewal?)
    private fun revokeAction(retainHandoff: Boolean): Cancellation = synchronized(lock) {
        revision++; busy = false
        if (!retainHandoff || link?.handedOff != true) link = null
        val removedEmail = emailLink?.takeUnless { retainHandoff && it.handedOff && (it.renewal == null || !it.validating) }
            ?.also { emailLink = null }
        val removedRenewal = automaticRenewal?.takeUnless { retainHandoff && emailLink?.renewal === it && emailLink?.handedOff == true }
            ?.also { automaticRenewal = null }
        Cancellation(bootstrap.get(), transport.get(), removedEmail, sharedAccess.getAndSet(null), removedRenewal)
    }
    private fun cancelResources(pending: Cancellation) {
        pending.access?.attachment?.close()
        pending.email?.let { emailInbox?.unregister(it.nonce); it.candidate = null }
        pending.renewal?.let { completeRenewal(it, AbemaAutomaticRenewalResult.CANCELLED) }
        pending.bootstrap?.cancel()
        pending.transport?.let { active -> cancellation.execute { runCatching { active.cancelActiveRequest() } } }
    }
    private fun cancel(retainHandoff: Boolean) = cancelResources(revokeAction(retainHandoff))
    fun cancelAction() { cancel(false); publish() }
    override fun close() { synchronized(lock) { closed = true }; cancel(false); session.setForeground(false) }
    override fun toString() = "AbemaAccountConnectionController(redacted)"
}
