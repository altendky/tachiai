package net.fstab.tachiai.provider.abema

import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.abema.account.*
import net.fstab.tachiai.provider.catalog.*

// access/lease/current/fetch are worker-only: sameOwner may read protected
// stores. close only revokes cheap admission and schedules cancellation. This
// binding never starts account connection, writes a grant or opens a browser.
internal class AbemaCollectionAccountBinding(
    private val store: AbemaAccountGrantStore,
    private val instanceId: String,
    private val ownerProof: String,
    private val sameOwner: () -> Boolean,
    private val canRemainActive: () -> Boolean,
    private val prepareValidationFactory: (() -> Boolean, () -> Boolean) -> AbemaAccountBootstrap,
    private val profileTransportFactory: (() -> Boolean) -> AbemaAccountTransport,
    private val collectionTransportFactory: (() -> String?, () -> Boolean) -> AbemaCollectionTransport,
    private val elapsedMs: () -> Long,
    private val wallMs: () -> Long,
    private val onCleanupFailure: () -> Unit,
    private val cancellation: Executor = cancellationWorkers,
    private val cleanupWaitMs: Long = 1000,
) : AbemaCollectionAccountAccess, AutoCloseable {
    companion object {
        private const val INVALID_BEARER = "tachiai-invalid-bearer"
        private val cancellationWorkers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(32), { action -> Thread(action, "abema-collection-cancel").apply { isDaemon = true } })
        // Whole resources may wait for their own native/route close receipts.
        // Never occupy that inner receipt pool while awaiting resource.close.
        private val cleanupWorkers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(32), { action -> Thread(action, "abema-collection-close").apply { isDaemon = true } })
        private val retained = ConcurrentHashMap.newKeySet<Pending>()
    }
    init {
        require(validProviderInstanceId(instanceId) && ownerProof.matches(Regex("[a-f0-9]{64}")))
        require(cleanupWaitMs in 1..5000)
    }
    private class Denied(val access: CatalogAccess = CatalogAccess.NOT_VERIFIED) : Exception(null, null, false, false)
    // Exactly one bounded attempt per action. A timeout, rejection, Throwable
    // or reentrant await is sticky; a late return never confirms uncertainty.
    // Each receipt keeps the actual resource alive, including queued actions.
    private class Receipt(val resource: AutoCloseable, private val action: () -> Unit,
        private val executor: Executor, private val wait: Long, private val onFailure: () -> Unit = {}) {
        private val started = AtomicBoolean()
        private val finished = CountDownLatch(1)
        private val failed = AtomicBoolean()
        @Volatile private var successful = false
        @Volatile private var thread: Thread? = null
        fun start() {
            if (!started.compareAndSet(false, true)) return
            try { executor.execute {
                thread = Thread.currentThread()
                try { action(); successful = true } catch (_: Throwable) { failed.set(true) }
                finally { finished.countDown() }
                if (failed.get()) try { onFailure() } catch (_: Throwable) { }
            } } catch (_: Throwable) {
                failed.set(true); finished.countDown()
                try { onFailure() } catch (_: Throwable) { }
            }
        }
        fun await(): Boolean {
            start()
            if (failed.get()) return false
            if (finished.count != 0L && thread === Thread.currentThread()) { failed.set(true); return false }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wait)
            var interrupted = false
            try {
                while (finished.count != 0L) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) { failed.set(true); return false }
                    try { finished.await(remaining, TimeUnit.NANOSECONDS) } catch (_: InterruptedException) { interrupted = true }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
            return successful && !failed.get()
        }
        override fun toString() = "AbemaCollectionCleanupReceipt(redacted)"
    }
    private class Owner(val resource: AutoCloseable, cancel: () -> Unit, wait: Long,
        cancellation: Executor, onFailure: () -> Unit) {
        private val lock = Any()
        private val close = Receipt(resource, resource::close, cleanupWorkers, wait)
        private val cancellation = Receipt(resource, cancel, cancellation, wait, onFailure)
        private var cancellationRequested = false
        private var finishing = false
        fun cancel() {
            val signal = synchronized(lock) {
                if (finishing || cancellationRequested) false else { cancellationRequested = true; true }
            }
            if (signal) cancellation.start()
        }
        fun cleanup(): Boolean {
            val cancelled = synchronized(lock) { finishing = true; cancellationRequested }
            // A potentially running cancel may still own native resources.
            // Its uncertain completion must not be followed by route release.
            if (cancelled && !cancellation.await()) return false
            return close.await()
        }
        override fun toString() = "AbemaCollectionOwner(redacted)"
    }
    private class Pending(val binding: AbemaCollectionAccountBinding) {
        @Volatile var owner: Owner? = null
        override fun toString() = "AbemaCollectionPending(redacted)"
    }
    private class Verified(val run: Long, val revision: Long, val record: AbemaStoredAccount,
        val lease: AbemaAccountLease, val contextKey: String) { val revoked = AtomicBoolean() }
    private val operations = Any()
    private val state = Any()
    private val revision = AtomicLong()
    private val closed = AtomicBoolean()
    private val poisoned = AtomicBoolean()
    private val cleanupNotified = AtomicBoolean()
    private val active = ConcurrentHashMap.newKeySet<Pending>()
    private var attempted = false
    private var selectedAccess = CatalogAccess.NOT_VERIFIED
    private var verified: Verified? = null
    private var seed: AbemaMyListResponse? = null
    private var lastElapsed = -1L
    private var lastWall = -1L
    private val session = AbemaAccountSession(store, wallMs) { workerOwnerCurrent(revision.get()) }

    private fun cheapCurrent(run: Long): Boolean = !closed.get() && !poisoned.get() && revision.get() == run &&
        try { canRemainActive() } catch (_: Throwable) { false }
    private fun clockCurrent(): Boolean {
        val elapsed = elapsedMs(); val wall = wallMs()
        return synchronized(state) {
            if (elapsed < 0 || wall < 0 || elapsed < lastElapsed || wall < lastWall) false
            else { lastElapsed = elapsed; lastWall = wall; true }
        }
    }
    private fun workerOwnerCurrent(run: Long): Boolean = cheapCurrent(run) && clockCurrent() &&
        try { sameOwner() && cheapCurrent(run) && clockCurrent() } catch (_: Throwable) { false }
    private fun requireCurrent(gate: () -> Boolean) { if (!gate()) throw Denied() }
    private fun poison() {
        poisoned.set(true); revision.incrementAndGet()
        synchronized(state) { seed = null; verified = null; selectedAccess = CatalogAccess.NOT_VERIFIED }
        session.setForeground(false)
        if (cleanupNotified.compareAndSet(false, true)) try { onCleanupFailure() } catch (_: Throwable) { }
    }
    private fun scheduleCancel(pending: Pending) {
        val owner = pending.owner ?: return
        owner.cancel()
    }
    private fun <T : AutoCloseable, R> owned(factory: () -> T, cancel: (T) -> Unit,
        gate: () -> Boolean, onPending: (Pending) -> Unit = {}, action: (T) -> R): R {
        val pending = Pending(this); active.add(pending); retained.add(pending)
        try {
            onPending(pending)
            requireCurrent(gate)
            val resource = factory()
            pending.owner = Owner(resource, { cancel(resource) }, cleanupWaitMs, cancellation, ::poison)
            if (!cheapCurrent(revision.get())) scheduleCancel(pending)
            requireCurrent(gate)
            val value = action(resource)
            requireCurrent(gate)
            return value
        } finally {
            val owner = pending.owner
            if (owner != null && !owner.cleanup()) { poison(); throw Denied() }
            if (!poisoned.get()) { active.remove(pending); retained.remove(pending) }
            requireCurrent(gate)
        }
    }
    override fun access(): CatalogAccess = synchronized(operations) {
        if (!attempted) {
            attempted = true
            val result = try { validate(); CatalogAccess.AVAILABLE }
            catch (error: Denied) { error.access }
            catch (_: Throwable) { CatalogAccess.NOT_VERIFIED }
            synchronized(state) { if (verified == null || result != CatalogAccess.AVAILABLE) {
                seed = null; verified = null
                selectedAccess = if (closed.get() || poisoned.get() || result == CatalogAccess.AVAILABLE) CatalogAccess.NOT_VERIFIED else result
            } }
        }
        val value = synchronized(state) { verified }
        if (value != null && !current(value)) invalidate(value)
        synchronized(state) { selectedAccess }
    }
    private fun validate() {
        val run = revision.get()
        if (!workerOwnerCurrent(run)) throw Denied()
        val savedRevision = store.revision()
        session.load()
        if (!store.usable(savedRevision)) throw Denied()
        val record = session.candidate() ?: throw Denied(when (session.status()) {
            AbemaAccountStatus.MISSING -> CatalogAccess.AUTHORIZATION_REQUIRED
            AbemaAccountStatus.RECONNECT_REQUIRED -> CatalogAccess.RECONNECT_REQUIRED
            else -> CatalogAccess.NOT_VERIFIED
        })
        val began = elapsedMs()
        if (began < 0 || began > Long.MAX_VALUE - 45000) throw Denied()
        var deadline = began + 45000
        fun gate() = workerOwnerCurrent(run) && store.usable(savedRevision) && wallMs() >= record.acceptedWallMs &&
            wallMs() < record.grant.expiresAtMs && elapsedMs() >= began && elapsedMs() < deadline && cheapCurrent(run)
        val context = owned({ prepareValidationFactory(::gate) { cheapCurrent(run) } }, AbemaAccountBootstrap::cancel, ::gate) {
            it.prepareValidation() ?: throw Denied()
        }
        val contextNow = elapsedMs()
        if (context.deadlineElapsedMs <= contextNow || context.deadlineElapsedMs - contextNow > 45000 || context.guestId == record.grant.userId) throw Denied()
        deadline = minOf(deadline, context.deadlineElapsedMs)
        requireCurrent(::gate)
        val profileId = owned({ profileTransportFactory(::gate) }, AbemaAccountTransport::cancelActiveRequest, ::gate) {
            val positive = it.profile(context.guestId, context.guestBearer)
            requireCurrent(::gate)
            if (positive !is AbemaAccountProfileResult.Identity || positive.id != context.guestId) throw Denied()
            if (it.profile(context.guestId, INVALID_BEARER) !== AbemaAccountProfileResult.Rejected) throw Denied()
            requireCurrent(::gate)
            val account = it.profile(record.grant.userId, record.grant.bearer)
            if (account !is AbemaAccountProfileResult.Identity || account.id != record.grant.userId || account.id == context.guestId) throw Denied()
            account.id
        }
        requireCurrent(::gate)
        if (!session.acceptExisting(record, profileId, true)) throw Denied()
        val accountLease = session.lease() ?: throw Denied()
        fun listGate() = gate() && accountLease.usable() && gate()
        val negative = owned({ collectionTransportFactory({ INVALID_BEARER.takeIf { listGate() } }, ::listGate) },
            AbemaCollectionTransport::cancelActiveRequest, ::listGate) { it.fetchMyList(null) }
        if (negative.status !in setOf(401, 403)) throw Denied()
        val positive = owned({ collectionTransportFactory({ record.grant.bearer.takeIf { listGate() } }, ::listGate) },
            AbemaCollectionTransport::cancelActiveRequest, ::listGate) { it.fetchMyList(null) }
        if (positive.status in setOf(401, 403)) throw Denied(CatalogAccess.AUTHORIZATION_REQUIRED)
        if (positive.status != 200 || positive.page?.let(::validPage) != true) throw Denied()
        requireCurrent(::listGate)
        val key = MessageDigest.getInstance("SHA-256").digest("$ownerProof:${record.generation}:$savedRevision".toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val value = Verified(run, savedRevision, record, accountLease, key)
        synchronized(state) {
            if (!cheapCurrent(run) || !store.usable(savedRevision)) throw Denied()
            verified = value; seed = positive; selectedAccess = CatalogAccess.AVAILABLE
        }
        if (!listGate()) { invalidate(value); throw Denied() }
    }
    private fun current(value: Verified): Boolean {
        val valid = try { !value.revoked.get() && cheapCurrent(value.run) && store.usable(value.revision) && clockCurrent() && value.lease.usable() &&
            cheapCurrent(value.run) && store.usable(value.revision) && clockCurrent()
        } catch (_: Throwable) { false }
        if (!valid) invalidate(value)
        return valid
    }
    private fun invalidate(value: Verified) {
        value.revoked.set(true)
        val expired = try { wallMs() < value.record.acceptedWallMs || wallMs() >= value.record.grant.expiresAtMs } catch (_: Throwable) { false }
        synchronized(state) { if (verified === value) {
            seed = null; verified = null
            selectedAccess = if (expired) CatalogAccess.RECONNECT_REQUIRED else CatalogAccess.NOT_VERIFIED
        } }
    }
    private fun collectionRefused(value: Verified) {
        value.revoked.set(true)
        synchronized(state) { if (verified === value) {
            seed = null; verified = null; selectedAccess = CatalogAccess.AUTHORIZATION_REQUIRED
        } }
    }
    override fun lease(): AbemaCollectionLease? {
        if (access() != CatalogAccess.AVAILABLE) return null
        val value = synchronized(state) { verified } ?: return null
        if (!current(value)) { invalidate(value); return null }
        return AbemaCollectionLease(instanceId, value.contextKey, { current(value) }) { admission ->
            object : AbemaCollectionTransport {
                private val stopped = AtomicBoolean()
                private val fetched = AtomicBoolean()
                private val pending = AtomicReference<Pending?>()
                private fun gate() = !stopped.get() && current(value) && try { admission() } catch (_: Throwable) { false }
                override fun fetchMyList(next: String?): AbemaMyListResponse {
                    if (!fetched.compareAndSet(false, true)) throw AbemaCollectionException(AbemaCollectionFailure.POLICY_REFUSED)
                    try {
                        requireCurrent(::gate)
                        val first = synchronized(state) { if (next == null && verified === value) seed.also { seed = null } else null }
                        if (first != null) { requireCurrent(::gate); return first }
                        val response = owned({ collectionTransportFactory({ value.record.grant.bearer.takeIf { gate() } }, ::gate) },
                            AbemaCollectionTransport::cancelActiveRequest, ::gate, onPending = pending::set) { it.fetchMyList(next) }
                        // The owned request has completed confirmed cleanup.
                        // This observed collection refusal does not diagnose
                        // provider account expiry or alter the saved grant.
                        if (response.status in setOf(401, 403)) collectionRefused(value)
                        return response
                    } catch (error: AbemaCollectionException) { throw error }
                    catch (_: Throwable) { throw AbemaCollectionException(if (poisoned.get()) AbemaCollectionFailure.CLEANUP_FAILED else AbemaCollectionFailure.CANCELLED) }
                }
                override fun cancelActiveRequest() { stopped.set(true); pending.get()?.let(::scheduleCancel) }
                override fun close() = cancelActiveRequest()
                override fun toString() = "AbemaBoundCollectionTransport(redacted)"
            }
        }
    }
    private fun validPage(page: AbemaMyListPage): Boolean = page.entries.map { it.resource }.distinct().size == page.entries.size && page.entries.all {
        val resource = it.resource
        resource.providerId == ProviderId("abema") && it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null &&
            safeMyListTitle(it.title) && it.title == it.title.trim() && when (resource.kind) {
                "episode" -> resource.intent == CatalogIntent.VIDEO && AbemaGuestBootstrapPolicy.validEpisodeId(resource.identity)
                "series", "slot_group" -> resource.intent == CatalogIntent.COLLECTION && AbemaGuestBootstrapPolicy.validEpisodeId(resource.identity)
                "live_event" -> resource.intent == CatalogIntent.BROADCAST && AbemaGuestBootstrapPolicy.validEpisodeId(resource.identity)
                "slot" -> resource.intent == CatalogIntent.BROADCAST && resource.identity.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}/[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
                else -> false
            }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        revision.incrementAndGet()
        synchronized(state) { seed = null; verified = null; selectedAccess = CatalogAccess.NOT_VERIFIED }
        session.setForeground(false)
        active.forEach(::scheduleCancel)
    }
    override fun toString() = "AbemaCollectionAccountBinding(redacted)"
}
