package net.fstab.tachiai.provider.abema

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.catalog.*

// The public adapter remains authoritative for all ordinary discovery/playback.
// My List is an account-owned, read-only projection with explicit local Add.
internal class AbemaAccountCollectionsCatalog(
    private val delegate: ProviderCatalog,
    private val account: AbemaCollectionAccountAccess,
    private val canUse: () -> Boolean = { true },
    private val wallMs: () -> Long = System::currentTimeMillis,
) : ProviderCatalog {
    override val providerId: ProviderId = delegate.providerId
    override val instanceId: String = delegate.instanceId
    init { require(providerId == ProviderId("abema") && validProviderInstanceId(instanceId)) }
    private val operations = Any()
    private val state = Any()
    private val revision = AtomicLong()
    private val active = AtomicReference<AbemaCollectionTransport?>()
    @Volatile private var closed = false
    private class Continuation(val context: String, val revision: Long, val providerNext: String)
    private var continuation: Pair<String, Continuation>? = null
    private var observedContext: String? = null
    private class Abort(val failure: CatalogFailure, val retryAt: Long? = null) : Exception(failure.name, null, false, false)

    private fun localCurrent(local: Long) = !closed && revision.get() == local && !CollectionRetention.poisoned(instanceId)
    private fun usable(): Boolean = !closed && try { canUse() } catch (_: Throwable) { false }
    private fun clear() = synchronized(state) { continuation = null; observedContext = null }
    private fun access(): CatalogAccess {
        if (!usable() || CollectionRetention.poisoned(instanceId)) return CatalogAccess.NOT_VERIFIED
        return try { account.access() } catch (_: Throwable) { CatalogAccess.NOT_VERIFIED }
    }
    private fun lease(): AbemaCollectionLease {
        if (access() != CatalogAccess.AVAILABLE) { clear(); throw Abort(CatalogFailure.ACCESS_REQUIRED) }
        val value = try { account.lease() } catch (_: Throwable) { null }
        if (value == null || value.instanceId != instanceId || !value.current()) {
            clear(); throw Abort(CatalogFailure.ACCESS_REQUIRED)
        }
        return value
    }
    private fun current(local: Long, lease: AbemaCollectionLease): Boolean = localCurrent(local) && usable() && lease.current()
    private fun requireCurrent(local: Long, lease: AbemaCollectionLease) {
        if (!current(local, lease)) { clear(); throw Abort(CatalogFailure.ACCESS_REQUIRED) }
    }

    override fun capabilities(): CatalogCapabilities {
        val public = delegate.capabilities()
        var selected = access()
        if (selected == CatalogAccess.AVAILABLE) {
            try {
                val lease = lease()
                synchronized(state) {
                    if (observedContext != null && observedContext != lease.contextKey) continuation = null
                    observedContext = lease.contextKey
                }
            } catch (_: Throwable) { selected = CatalogAccess.NOT_VERIFIED; clear() }
        } else clear()
        val collections = public.collections.filter { it.id != "my_list" && it.id != "history" } +
            listOf(CatalogCollection("my_list", "My List", selected), CatalogCollection("history", "History", CatalogAccess.NOT_VERIFIED))
        return public.copy(collections = collections)
    }

    override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
        if (query.collectionId == "history") { clear(); return CatalogResult.Failure(CatalogFailure.NOT_VERIFIED) }
        if (query.collectionId != "my_list") { clear(); return delegate.browse(query) }
        return synchronized(operations) {
            val local = revision.get()
            try {
                if (query.search != null || query.parent != null) { clear(); throw Abort(CatalogFailure.UNSUPPORTED) }
                val lease = lease()
                requireCurrent(local, lease)
                val providerNext = synchronized(state) {
                    if (query.cursor == null) {
                        continuation = null; observedContext = lease.contextKey; null
                    } else {
                        val saved = continuation
                        continuation = null // Every continuation is single-use, including failed attempts.
                        if (observedContext != lease.contextKey)
                            throw Abort(CatalogFailure.ACCESS_REQUIRED)
                        if (saved == null) throw Abort(CatalogFailure.INVALID_INPUT)
                        if (saved.second.context != lease.contextKey) throw Abort(CatalogFailure.ACCESS_REQUIRED)
                        if (saved.first != query.cursor || saved.second.revision != local)
                            throw Abort(CatalogFailure.INVALID_INPUT)
                        saved.second.providerNext
                    }
                }
                val response = request(local, lease, providerNext)
                requireCurrent(local, lease)
                when (response.status) {
                    200 -> {
                        val page = response.page ?: throw Abort(CatalogFailure.TEMPORARY)
                        if (page.entries.any { !validEntry(it) } || page.entries.map { it.resource }.distinct().size != page.entries.size)
                            throw Abort(CatalogFailure.TEMPORARY)
                        val next = page.providerNext?.let { UUID.randomUUID().toString() }
                        synchronized(state) {
                            // Protected owner reads run before/after this small
                            // publication lock, never underneath close's lock.
                            if (!localCurrent(local)) throw Abort(CatalogFailure.ACCESS_REQUIRED)
                            observedContext = lease.contextKey
                            continuation = next?.let { it to Continuation(lease.contextKey, local, page.providerNext!!) }
                        }
                        requireCurrent(local, lease)
                        CatalogResult.Value(CatalogPage(page.entries, next))
                    }
                    401, 403 -> { clear(); CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
                    429 -> CatalogResult.Failure(CatalogFailure.RATE_LIMITED, boundedRetry(response.retryAtEpochMs))
                    else -> CatalogResult.Failure(CatalogFailure.TEMPORARY)
                }
            } catch (error: Abort) {
                if (error.failure == CatalogFailure.ACCESS_REQUIRED) clear()
                CatalogResult.Failure(error.failure, error.retryAt)
            } catch (_: Throwable) {
                if (!localCurrent(local) || !usable()) { clear(); CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
                else CatalogResult.Failure(CatalogFailure.TEMPORARY)
            }
        }
    }

    private fun request(local: Long, lease: AbemaCollectionLease, next: String?): AbemaMyListResponse {
        requireCurrent(local, lease)
        // Keep this owner reachable while factory/fetch/cleanup completes even
        // when its UI/controller has closed and discarded all local references.
        CollectionRetention.retain(this)
        var transport: AbemaCollectionTransport? = null
        try {
            transport = lease.createTransport { current(local, lease) }
            check(active.compareAndSet(null, transport))
            requireCurrent(local, lease)
            return transport.fetchMyList(next)
        } finally {
            if (transport != null) {
                try {
                    transport.close()
                    if (!CollectionRetention.poisoned(instanceId)) active.compareAndSet(transport, null)
                    CollectionRetention.release(this)
                } catch (_: Throwable) {
                    // No successful repeated close may launder an uncertain
                    // cleanup. Keep the exact transport strongly retained.
                    CollectionRetention.poison(instanceId, this)
                    revision.incrementAndGet(); clear()
                    throw AbemaCollectionException(AbemaCollectionFailure.CLEANUP_FAILED)
                }
            } else CollectionRetention.release(this)
        }
    }

    private fun validEntry(entry: CatalogEntry): Boolean {
        val resource = entry.resource
        if (resource.providerId != providerId || entry.availability != CatalogAvailability.UNKNOWN ||
            entry.scheduledStartEpochMs != null || !safeMyListTitle(entry.title) || entry.title != entry.title.trim()) return false
        val id = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
        return when (resource.kind) {
            "episode" -> resource.intent == CatalogIntent.VIDEO && resource.identity.matches(id)
            "series", "slot_group" -> resource.intent == CatalogIntent.COLLECTION && resource.identity.matches(id)
            "live_event" -> resource.intent == CatalogIntent.BROADCAST && resource.identity.matches(id)
            "slot" -> resource.intent == CatalogIntent.BROADCAST && resource.identity.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}/[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            else -> false
        }
    }
    private fun boundedRetry(supplied: Long?): Long {
        val now = wallMs().coerceAtLeast(0)
        val maximum = if (now > Long.MAX_VALUE - 86_400_000) Long.MAX_VALUE else now + 86_400_000
        val fallback = if (now > Long.MAX_VALUE - 60_000) Long.MAX_VALUE else now + 60_000
        return (supplied ?: fallback).coerceIn(now, maximum)
    }

    override fun lookup(input: String): CatalogResult<CatalogEntry> { clear(); return delegate.lookup(input) }
    override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> { clear(); return delegate.refresh(resource) }
    override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> { clear(); return delegate.resolve(resource) }
    override fun close() {
        if (closed) return
        closed = true; revision.incrementAndGet(); clear()
        try { active.get()?.cancelActiveRequest() } catch (_: Throwable) { CollectionRetention.poison(instanceId, this) }
        // fetch's worker owns confirmed transport close; do not drop its reference
        // or claim synchronous cleanup while a request is still in flight.
        try { delegate.close() } catch (_: Throwable) { CollectionRetention.poison(instanceId, this) }
    }
    override fun toString() = "AbemaAccountCollectionsCatalog(redacted)"
}

// Process-owned memory only. Unknown cleanup blocks new collection admission for
// this UUID and retains the exact owner/resources until process exit. No grants,
// account identifiers, cursors or private body values are persisted here.
private object CollectionRetention {
    private val retained = HashSet<AbemaAccountCollectionsCatalog>()
    private val poisoned = HashSet<String>()
    @Synchronized fun retain(owner: AbemaAccountCollectionsCatalog) { retained.add(owner) }
    @Synchronized fun release(owner: AbemaAccountCollectionsCatalog) { if (!poisoned.contains(owner.instanceId)) retained.remove(owner) }
    @Synchronized fun poison(instanceId: String, owner: AbemaAccountCollectionsCatalog) { poisoned.add(instanceId); retained.add(owner) }
    @Synchronized fun poisoned(instanceId: String) = poisoned.contains(instanceId)
}
