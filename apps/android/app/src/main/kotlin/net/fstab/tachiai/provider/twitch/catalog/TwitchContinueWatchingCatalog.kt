package net.fstab.tachiai.provider.twitch.catalog

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*

// Optional provider-owned shelf. A valid Following grant alone never proves
// private collection access; refusal never clears that independently valid grant.
internal class TwitchContinueWatchingCatalog(
    private val delegate: ProviderCatalog,
    private val session: TwitchCatalogSession,
    private val transportFactory: (() -> Boolean) -> TwitchContinueWatchingTransport,
    private val canUse: () -> Boolean,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val cleanupWaitMs: Long = 1000,
) : ProviderCatalog by delegate {
    init { require(delegate.providerId == ProviderId("twitch") && delegate.instanceId == session.instanceId && cleanupWaitMs in 1..5000) }
    private class Admission(val revision: Long, val lease: TwitchCatalogLease, val began: Long)
    private class Proof(val admission: Admission, var seed: TwitchContinueWatchingPage?)
    private class Active(val transport: TwitchContinueWatchingTransport, waitMs: Long) {
        val close = TwitchCollectionCleanupReceipt(transport, TwitchContinueWatchingTransport::close, waitMs, cleanupWorkers)
    }
    private companion object {
        const val DEADLINE_MS = 30_000L
        const val INVALID_SENTINEL = "tachiai-invalid-bearer"
        private val cleanupWorkers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(4), { task -> Thread(task, "twitch-collection-owner-close").apply { isDaemon = true } })
    }
    private val operationLock = Any()
    private val lock = Any()
    private var revision = 0L
    private var closed = false
    private var delegateClosed = false
    private var proof: Proof? = null
    private var active: Active? = null

    private fun local(admission: Admission): Boolean = synchronized(lock) {
        !closed && revision == admission.revision && !TwitchContinueWatchingAdmission.poisoned(instanceId)
    }
    // Protected reads and route-owner callbacks stay off the memory monitor.
    // The second memory check rejects a close/pause racing a held final read.
    private fun current(admission: Admission): Boolean {
        if (!local(admission)) return false
        val now = monotonicMs()
        if (now < admission.began || now - admission.began >= DEADLINE_MS) return false
        val allowed = try { canUse() && session.isCurrent(admission.lease) } catch (_: Throwable) { false }
        return allowed && local(admission) && monotonicMs().let { it >= admission.began && it - admission.began < DEADLINE_MS }
    }
    private fun requireCurrent(admission: Admission) {
        if (!current(admission)) throw TwitchContinueWatchingException(TwitchContinueWatchingFailure.CANCELLED)
    }
    private fun clearProof() = synchronized(lock) { proof = null }
    private fun poison(owner: Any) {
        synchronized(lock) { proof = null; closed = true; revision++ }
        TwitchContinueWatchingAdmission.poison(instanceId, owner)
        TwitchContinueWatchingAdmission.poison(instanceId, this)
    }
    private fun request(admission: Admission, token: String): TwitchContinueWatchingResponse {
        requireCurrent(admission)
        val transport = transportFactory { current(admission) }
        val owned = Active(transport, cleanupWaitMs)
        synchronized(lock) { active = owned }
        var returning = false
        try {
            requireCurrent(admission)
            if (transport.collectionId != TWITCH_CONTINUE_WATCHING_ID)
                throw TwitchContinueWatchingException(TwitchContinueWatchingFailure.POLICY_REFUSED)
            val response = transport.fetch(token, admission.lease.userId, wallMs())
            requireCurrent(admission)
            if (response.collectionId != TWITCH_CONTINUE_WATCHING_ID)
                throw TwitchContinueWatchingException(TwitchContinueWatchingFailure.INVALID_RESPONSE)
            if (response.page?.userId?.let { it != admission.lease.userId } == true)
                throw TwitchContinueWatchingException(TwitchContinueWatchingFailure.USER_MISMATCH)
            returning = true
            return response
        } finally {
            if (!owned.close.await() || !transport.cleanupConfirmed) {
                poison(owned)
                throw TwitchContinueWatchingException(TwitchContinueWatchingFailure.CLEANUP_FAILED)
            }
            synchronized(lock) { if (active === owned) active = null }
            if (returning) requireCurrent(admission)
        }
    }
    private fun admission(): Admission? {
        if (synchronized(lock) { closed } || TwitchContinueWatchingAdmission.poisoned(instanceId) ||
            !try { canUse() } catch (_: Throwable) { false }) return null
        val local = synchronized(lock) { revision }
        val lease = session.validate().lease ?: return null
        return Admission(local, lease, monotonicMs()).takeIf(::current)
    }
    private fun verified(admission: Admission): Proof {
        val existing = synchronized(lock) { proof }
        if (existing != null && existing.admission.lease === admission.lease && current(existing.admission)) return existing
        clearProof()
        // Only an observed HTTP denial proves the sentinel lacked access. A
        // 200/null currentUser or guessed GraphQL error text does not prove it.
        val denied = request(admission, INVALID_SENTINEL)
        if (denied.status !in setOf(401, 403)) throw TwitchContinueWatchingException(TwitchContinueWatchingFailure.POLICY_REFUSED)
        val accepted = request(admission, admission.lease.accessToken)
        if (accepted.status != 200) throw TwitchContinueWatchingException(TwitchContinueWatchingFailure.POLICY_REFUSED)
        requireCurrent(admission)
        val value = Proof(admission, checkNotNull(accepted.page))
        synchronized(lock) { if (!local(admission)) throw TwitchContinueWatchingException(TwitchContinueWatchingFailure.CANCELLED); proof = value }
        return value
    }
    override fun capabilities(): CatalogCapabilities = synchronized(operationLock) {
        val original = delegate.capabilities()
        val access = if (original.browse != CatalogAccess.AVAILABLE) { clearProof(); original.browse } else try {
            val admitted = admission()
            if (admitted == null) { clearProof(); CatalogAccess.NOT_VERIFIED }
            else { val checked = verified(admitted); requireCurrent(checked.admission); CatalogAccess.AVAILABLE }
        } catch (_: Throwable) { clearProof(); CatalogAccess.NOT_VERIFIED }
        original.copy(collections = original.collections + CatalogCollection(TWITCH_CONTINUE_WATCHING_ID, "Continue watching", access))
    }
    override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
        if (query.collectionId != TWITCH_CONTINUE_WATCHING_ID) {
            synchronized(lock) { proof?.seed = null }
            return delegate.browse(query)
        }
        if (query.cursor != null || query.search != null || query.parent != null)
            return CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
        return synchronized(operationLock) {
            try {
                val admitted = admission() ?: return@synchronized CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
                val checked = verified(admitted)
                requireCurrent(checked.admission)
                val seed = synchronized(lock) { checked.seed.also { checked.seed = null } }
                val page = seed ?: request(admitted, admitted.lease.accessToken).let { response ->
                    if (response.status != 200) throw TwitchContinueWatchingException(TwitchContinueWatchingFailure.POLICY_REFUSED)
                    checkNotNull(response.page)
                }
                requireCurrent(admitted)
                // A seed can only publish under the exact proof's live lease.
                requireCurrent(checked.admission)
                CatalogResult.Value(CatalogPage(page.entries))
            } catch (error: Throwable) {
                clearProof()
                val category = (error as? TwitchContinueWatchingException)?.category
                CatalogResult.Failure(if (category == TwitchContinueWatchingFailure.NETWORK_FAILED) CatalogFailure.TEMPORARY else CatalogFailure.NOT_VERIFIED)
            }
        }
    }
    override fun close() {
        val pending = synchronized(lock) {
            if (delegateClosed) return
            delegateClosed = true
            closed = true; revision++; proof = null; active
        }
        // Both operations signal/schedule; the worker owns final confirmation.
        pending?.close?.start()
        delegate.close()
    }
    override fun toString() = "TwitchContinueWatchingCatalog(redacted)"
}
