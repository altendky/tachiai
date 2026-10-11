package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
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
            !busy && foreground && link?.let { it.handedOff && elapsedMs() >= it.nextPollAt && elapsedMs() < pollDeadline(it) } == true)
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
                if (current(run)) { link = null; session.failed(error.category) }
            }
            catch (_: Exception) { diagnostics.report(FailureStage.CATALOG_AUTH_REQUEST); if (current(run)) { link = null; session.failed(AbemaAccountFailure.TEMPORARY) } }
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
    private fun prepare(run: Long, owner: AbemaCatalogRouteOwner): AbemaAccountChallenge {
        val prepared = binding.bootstrap(owner, { same(run, owner) }, { current(run) })
        bootstrap.set(prepared)
        try { return prepared.prepare() ?: throw AbemaAccountException(AbemaAccountFailure.NOT_VERIFIED) }
        finally { try { prepared.close() } finally { bootstrap.compareAndSet(prepared, null) } }
    }
    private fun <T> request(run: Long, owner: AbemaCatalogRouteOwner, deadline: Long? = null, action: (AbemaAccountTransport) -> T): T {
        fun allowed() = same(run, owner) && (deadline == null || elapsedMs() < deadline)
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
    fun checkEnforcement() = run(AbemaAccountProgress.CHECKING) { run ->
        val owner = binding.owner(); val challenge = prepare(run, owner)
        val proven = enforcement(run, owner, challenge)
        post { if (current(run)) { busy = false; publish(if (proven) AbemaAccountProgress.ENFORCEMENT_PROVEN else AbemaAccountProgress.ENFORCEMENT_NOT_PROVEN) } }
    }
    fun connect() {
        run(AbemaAccountProgress.PREPARING) { run ->
            val owner = binding.owner(); val storeRevision = binding.store.revision()
            val challenge = prepare(run, owner)
            if (!enforcement(run, owner, challenge))
                throw AbemaAccountException(AbemaAccountFailure.NOT_VERIFIED)
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
                    // Fresh evidence before durable acceptance; no stale pre-
                    // browser proof is promoted to account introspection.
                    if (!enforcement(run, prepared.owner, prepared.challenge, deadline)) throw AbemaAccountException(AbemaAccountFailure.NOT_VERIFIED)
                    if (elapsedMs() >= deadline) throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
                    if (!session.acceptReplacement(result.grant, profile.id, true, prepared.storeRevision) { same(run, prepared.owner) && elapsedMs() < deadline })
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
        val value = session.candidate() ?: return@run
        val owner = binding.owner()
        capturedOwner = owner
        val challenge = prepare(run, owner)
        if (value.grant.userId == challenge.guestId || !enforcement(run, owner, challenge)) throw AbemaAccountException(AbemaAccountFailure.NOT_VERIFIED)
        val profile = request(run, owner) { it.profile(value.grant.userId, value.grant.bearer) }
        if (profile !is AbemaAccountProfileResult.Identity || !session.acceptExisting(value, profile.id, true))
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
        bootstrap.get()?.cancel()
        transport.get()?.let { active -> cancellation.execute { runCatching { active.cancelActiveRequest() } } }
    }
    fun cancelAction() { cancel(false); publish() }
    override fun close() { synchronized(lock) { closed = true }; cancel(false); session.setForeground(false) }
    override fun toString() = "AbemaAccountConnectionController(redacted)"
}
