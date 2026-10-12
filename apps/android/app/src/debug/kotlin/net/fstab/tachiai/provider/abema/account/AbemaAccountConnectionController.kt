package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.fstab.tachiai.provider.abema.AbemaCatalogRouteOwner
import net.fstab.tachiai.platform.diagnostics.*

internal interface AbemaAccountConnectionBinding {
    val store: AbemaAccountGrantStore
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
) : AutoCloseable {
    private val lock = Any()
    @Volatile private var foreground = initiallyForeground
    @Volatile private var closed = false
    private var revision = 0L
    private var busy = false
    private val forgetting = AtomicBoolean()
    private val transport = AtomicReference<AbemaAccountTransport?>()
    private val bootstrap = AtomicReference<AbemaAccountBootstrap?>()
    private class Link(val challenge: AbemaAccountChallenge, val owner: AbemaCatalogRouteOwner,
        val storeRevision: Long) {
        var handedOff = false; var polls = 0; var pollingBegan: Long? = null; var nextPollAt = 0L
        override fun toString() = "AbemaAccountConnectionLink(redacted)"
    }
    @Volatile private var link: Link? = null
    private class EmailLink(val owner: AbemaCatalogRouteOwner, val nonce: String,
        val deadline: Long, val storeRevision: Long) {
        var handedOff = false
        var candidate: AbemaAccountGrant? = null
        override fun toString() = "AbemaEmailConnectionLink(redacted)"
    }
    @Volatile private var emailLink: EmailLink? = null
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
        mutable.value = AbemaAccountConnectionState(session.status(), progress, busy || forgetting.get(),
            !busy && foreground && link?.let { !it.handedOff && elapsedMs() < it.challenge.deadlineElapsedMs } == true,
            !busy && foreground && (emailLink?.let { it.handedOff && it.candidate != null && elapsedMs() < it.deadline } == true ||
                link?.let { it.handedOff && elapsedMs() >= it.nextPollAt && elapsedMs() < pollDeadline(it) } == true))
    }
    private fun current(run: Long) = synchronized(lock) { !closed && foreground && revision == run }
    private fun same(run: Long, owner: AbemaCatalogRouteOwner) = current(run) && binding.sameOwner(owner) && current(run)
    private fun run(progress: AbemaAccountProgress, action: (Long) -> Unit) {
        val run = synchronized(lock) {
            if (closed || !foreground || busy || forgetting.get()) return
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
                if (current(run)) { link = null; clearEmailLink(); session.failed(error.category) }
            }
            catch (_: Exception) { diagnostics.report(FailureStage.CATALOG_AUTH_REQUEST); if (current(run)) { link = null; clearEmailLink(); session.failed(AbemaAccountFailure.TEMPORARY) } }
            finally { post { synchronized(lock) {
                if (revision == run && busy) { busy = false; if (!closed) publish() }
            } } }
        }
    }
    fun load() = run(AbemaAccountProgress.READING) { run ->
        val owner = binding.owner()
        if (!same(run, owner)) return@run
        capturedOwner = owner; session.load()
    }
    fun connectEmail(launch: (AbemaApprovalBrowserRequest) -> Boolean) = run(AbemaAccountProgress.PREPARING) { run ->
        val inbox = emailInbox ?: throw AbemaAccountException(AbemaAccountFailure.NOT_VERIFIED)
        val owner = binding.owner()
        if (!same(run, owner)) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
        val now = elapsedMs()
        if (now < 0 || now > Long.MAX_VALUE - 600000) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
        val prepared = EmailLink(owner, UUID.randomUUID().toString().replace("-", ""), now + 600000, binding.store.revision())
        val prior = synchronized(lock) {
            if (!current(run)) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            emailLink.also { emailLink = prepared }
        }
        prior?.let { inbox.unregister(it.nonce); it.candidate = null }
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
        post { if (current(run) && emailLink === prepared) {
            capturedOwner = owner; prepared.handedOff = true; busy = false
            publish(AbemaAccountProgress.WAITING_APPROVAL)
            val request = AbemaApprovalBrowserRequest(owner.instance.id, owner.proof(), "https://abema.tv/account",
                prepared.deadline, prepared.nonce)
            if (!launch(request)) failEmail(prepared, AbemaAccountFailure.TEMPORARY)
        } else inbox.unregister(prepared.nonce) }
        delay(prepared.deadline - elapsedMs()) {
            if (elapsedMs() >= prepared.deadline) failEmail(prepared, AbemaAccountFailure.EXPIRED)
        }
    }
    fun finishEmailConnection() {
        val prepared = synchronized(lock) { emailLink?.takeIf { it.handedOff && it.candidate != null } } ?: return
        run(AbemaAccountProgress.CHECKING) { run ->
            val grant = checkNotNull(prepared.candidate)
            val beganWall = wallMs()
            if (decodedAbemaGrant(grant.bearer, grant.userId, beganWall).expiresAtMs != grant.expiresAtMs)
                throw AbemaAccountException(AbemaAccountFailure.REFUSED)
            fun grantCurrent(): Boolean {
                val now = wallMs()
                if (now < beganWall || now >= grant.expiresAtMs) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
                return emailLink === prepared && binding.store.usable(prepared.storeRevision)
            }
            val profile = request(run, prepared.owner, prepared.deadline, ::grantCurrent) { it.profile(grant.userId, grant.bearer) }
            if (profile !is AbemaAccountProfileResult.Identity || profile.id != grant.userId)
                throw AbemaAccountException(AbemaAccountFailure.REFUSED)
            val email = request(run, prepared.owner, prepared.deadline, ::grantCurrent) { it.emailRegistration(grant.userId, grant.bearer) }
            if (email != AbemaAccountEmailResult.REGISTERED) throw AbemaAccountException(AbemaAccountFailure.REFUSED)
            if (!session.acceptReplacement(grant, profile.id, prepared.storeRevision) {
                    same(run, prepared.owner) && emailLink === prepared && elapsedMs() < prepared.deadline
                }) throw AbemaAccountException(AbemaAccountFailure.STORAGE_FAILED)
            post { if (current(run) && emailLink === prepared) { clearEmailLink(); publish() } }
        }
    }
    private fun failEmail(prepared: EmailLink, category: AbemaAccountFailure) {
        val run = synchronized(lock) {
            if (closed || emailLink !== prepared) return
            revision
        }
        worker.execute {
            if (!clearEmailLink(prepared) || !current(run) || synchronized(lock) { busy }) return@execute
            // Failure can preserve a validated prior grant, which requires a
            // protected owner check. Never evaluate it in a UI callback.
            session.failed(category)
            post { if (current(run) && emailLink == null) publish() }
        }
    }
    private fun clearEmailLink(expected: EmailLink? = null): Boolean {
        val prior = synchronized(lock) {
            if (expected != null && emailLink !== expected) return false
            emailLink.also { emailLink = null }
        } ?: return false
        emailInbox?.unregister(prior.nonce); prior.candidate = null
        return true
    }
    private fun prepare(run: Long, owner: AbemaCatalogRouteOwner): AbemaAccountChallenge {
        val prepared = binding.bootstrap(owner, { same(run, owner) }, { current(run) })
        bootstrap.set(prepared)
        try { return prepared.prepare() ?: throw AbemaAccountException(AbemaAccountFailure.NOT_VERIFIED) }
        finally { try { prepared.close() } finally { bootstrap.compareAndSet(prepared, null) } }
    }
    private fun <T> request(run: Long, owner: AbemaCatalogRouteOwner, deadline: Long? = null,
        additionalGate: () -> Boolean = { true }, action: (AbemaAccountTransport) -> T): T {
        fun allowed() = same(run, owner) && additionalGate() && (deadline == null || elapsedMs() < deadline)
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
    fun revalidate() = run(AbemaAccountProgress.CHECKING) { run ->
        // Rechecking a saved grant revokes prior leases until its fresh response
        // is accepted; a refusal must not retain an earlier Connected status.
        session.invalidateValidation()
        val value = session.candidate() ?: return@run
        val owner = binding.owner()
        capturedOwner = owner
        val profile = request(run, owner) { it.profile(value.grant.userId, value.grant.bearer) }
        if (profile !is AbemaAccountProfileResult.Identity || profile.id != value.grant.userId ||
            request(run, owner) { it.emailRegistration(value.grant.userId, value.grant.bearer) } != AbemaAccountEmailResult.REGISTERED ||
            !session.acceptExisting(value, profile.id))
            throw AbemaAccountException(AbemaAccountFailure.REFUSED)
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
        synchronized(lock) { foreground = value }
        session.setForeground(value)
        if (!value) { cancel(true); publish() } else {
            publish()
            val prepared = link
            if (prepared != null && prepared.nextPollAt > elapsedMs()) {
                val run = synchronized(lock) { revision }
                delay(prepared.nextPollAt - elapsedMs()) { if (current(run) && link === prepared) publish() }
            }
        }
    }
    private fun cancel(retainHandoff: Boolean) {
        synchronized(lock) { revision++; busy = false; if (!retainHandoff || link?.handedOff != true) link = null }
        if (!retainHandoff || emailLink?.handedOff != true) clearEmailLink()
        bootstrap.get()?.cancel()
        transport.get()?.let { active -> cancellation.execute { runCatching { active.cancelActiveRequest() } } }
    }
    fun cancelAction() { cancel(false); publish() }
    override fun close() { synchronized(lock) { closed = true }; cancel(false); session.setForeground(false) }
    override fun toString() = "AbemaAccountConnectionController(redacted)"
}
