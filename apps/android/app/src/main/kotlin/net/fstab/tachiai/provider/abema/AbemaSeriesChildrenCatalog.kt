package net.fstab.tachiai.provider.abema

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.provider.catalog.*

// Only explicit series children; public/account discovery remains with its
// delegate. No collection is resolved into an implicit playable episode.
internal class AbemaSeriesChildrenCatalog(
    private val delegate: ProviderCatalog,
    private val transportFactory: ((canRequest: () -> Boolean) -> AbemaSeriesTransport)?,
    private val canPublish: () -> Boolean = { true },
    // Replay lengths of listed finished broadcasts (#672), on the same runtime.
    private val replayTransportFactory: ((canRequest: () -> Boolean) -> AbemaReplayLengthTransport)? = null,
) : ProviderCatalog by delegate {
    init { require(delegate.providerId.value == "abema" && net.fstab.tachiai.presentation.validProviderInstanceId(delegate.instanceId)) }
    private class Cursor(val parent: CatalogResource, val request: AbemaSeriesPageRequest,
        val revision: Long, val seen: Set<CatalogResource>, val groups: List<AbemaSeriesGroup>)
    private val lock = Any()
    private val operation = Any()
    private val active = AtomicReference<AbemaSeriesTransport?>()
    private val replayActive = AtomicReference<AbemaReplayLengthTransport?>()
    private val cursors = linkedMapOf<String, Cursor>()
    private var revision = 0L
    @Volatile private var closed = false
    @Volatile private var poisoned = false
    companion object { private val retained = java.util.concurrent.ConcurrentLinkedQueue<Any>() }
    private fun admitted(local: Long) = !closed && !poisoned && synchronized(lock) { revision == local }
    private fun current(local: Long) = admitted(local) &&
        (try { canPublish() } catch (_: Throwable) { false }) && admitted(local)
    private fun clear() = synchronized(lock) { revision++; cursors.clear() }
    override fun capabilities(): CatalogCapabilities {
        val base = delegate.capabilities()
        val local = synchronized(lock) { revision }
        val access = if (transportFactory != null && current(local)) CatalogAccess.AVAILABLE else CatalogAccess.NOT_VERIFIED
        return base.copy(children = if (current(local) && (transportFactory != null || base.children == CatalogAccess.AVAILABLE))
            CatalogAccess.AVAILABLE else CatalogAccess.NOT_VERIFIED,
            childrenTitle = "Browse", childrenHeadingPrefix = "From",
            childCapabilities = base.childCapabilities.filterNot { it.kind in setOf("series", "season") && it.intent == CatalogIntent.COLLECTION }
                .map { if (base.children == CatalogAccess.AVAILABLE) it else it.copy(access = base.children) } +
                listOf("series", "season").map { CatalogChildrenCapability(it, CatalogIntent.COLLECTION, access, "Browse episodes", "Episodes from") })
    }
    override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
        val parent = query.parent ?: run { clear(); return delegate.browse(query) }
        if (parent.providerId != providerId || parent.kind !in setOf("series", "season") || parent.intent != CatalogIntent.COLLECTION) {
            clear(); return delegate.browse(query)
        }
        val season = if (parent.kind == "season") abemaSeasonParent(parent) else null
        if ((parent.kind == "series" && !validAbemaSeriesId(parent.identity) || parent.kind == "season" && season == null) ||
            query.collectionId != null || query.search != null)
            return CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
        val seriesId = season?.first ?: parent.identity
        val seasonId = season?.second
        // Season parents map to that season; a group must belong to this series.
        if (seasonId != null && !boundAbemaSeriesChild(seriesId, seasonId) ||
            query.group != null && !boundAbemaSeriesChild(seriesId, query.group)) return CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
        return synchronized(operation) {
            val local = synchronized(lock) { revision }
            if (!current(local)) { clear(); return@synchronized CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
            val factory = transportFactory ?: return@synchronized CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
            val saved = synchronized(lock) {
                if (query.cursor == null) { cursors.clear(); null }
                else cursors.remove(query.cursor)
            }
            if (query.cursor != null && (saved == null || saved.parent != parent || saved.revision != local ||
                    query.group != null && query.group != saved.request.groupId))
                return@synchronized CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
            // ABEMA's own series page lists each episode group newest first (#622),
            // so every surface uses that order whatever the generic query asks.
            val request = saved?.request ?: AbemaSeriesPageRequest(seriesId, seasonId = seasonId, groupId = query.group, newest = true)
            var transport: AbemaSeriesTransport? = null
            var cleanup = true
            val result = try {
                transport = factory { current(local) }
                active.set(transport)
                if (!current(local)) CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) else transport.fetch(request)
            } catch (_: Throwable) { CatalogResult.Failure(CatalogFailure.TEMPORARY) }
            finally {
                try { transport?.close() } catch (_: Throwable) {
                    cleanup = false; poisoned = true; closed = true; clear()
                    retained.add(this); transport?.let(retained::add)
                }
                if (cleanup) active.compareAndSet(transport, null)
            }
            if (!cleanup) return@synchronized CatalogResult.Failure(CatalogFailure.TEMPORARY)
            if (!current(local)) { clear(); return@synchronized CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
            val page = when (result) {
                is CatalogResult.Failure -> return@synchronized result
                is CatalogResult.Value -> result.value
            }
            val seen = saved?.seen.orEmpty()
            if (!page.answers(request)) {
                clear(); return@synchronized CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
            }
            // A newest-first group can gain items between pages (a new broadcast
            // during a tournament), shifting rows already shown onto this page.
            val fresh = page.entries.filterNot { it.resource in seen }
            if (page.more && request.offset == ABEMA_SERIES_MAX_OFFSET) {
                clear(); return@synchronized CatalogResult.Failure(CatalogFailure.TEMPORARY)
            }
            val next = if (page.more) UUID.randomUUID().toString() else null
            // Continuations repeat the first page's group list for the caller.
            val groups = page.groups ?: saved?.groups.orEmpty()
            val admitted = synchronized(lock) {
                if (closed || poisoned || revision != local) false else {
                if (next != null) {
                    cursors[next] = Cursor(parent, request.next(page), local, seen + fresh.map { it.resource }, groups)
                    while (cursors.size > 16) cursors.remove(cursors.keys.first())
                }
                true }
            }
            if (!admitted) return@synchronized CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)
            if (!current(local)) { clear(); return@synchronized CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
            CatalogResult.Value(CatalogPage(fresh, next, groups.map { CatalogChildGroup(it.id, it.name) }, page.groupId))
        }
    }
    // Listed broadcasts only; continuations and their cursors are untouched.
    override fun replayLengths(resources: List<CatalogResource>, canContinue: () -> Boolean): CatalogResult<Map<CatalogResource, Long>> {
        val slots = resources.map { AbemaSlotRequest.from(it) ?: return CatalogResult.Failure(CatalogFailure.INVALID_INPUT) }
        if (slots.isEmpty() || slots.size > ABEMA_REPLAY_LENGTH_LIMIT || resources.distinct().size != resources.size)
            return CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
        return synchronized(operation) {
            val local = synchronized(lock) { revision }
            if (!current(local)) return@synchronized CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)
            val factory = replayTransportFactory ?: return@synchronized CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
            var transport: AbemaReplayLengthTransport? = null
            var cleanup = true
            val result: CatalogResult<Map<CatalogResource, Long>> = try {
                transport = factory { current(local) && canContinue() }
                replayActive.set(transport)
                // A run reads what its time limit allows and reports the rest
                // unread; a few more runs finish one listing page.
                val found = linkedMapOf<CatalogResource, Long>()
                var pending = slots
                var outcome: CatalogResult<Map<CatalogResource, Long>>? = null
                for (run in 1..ABEMA_REPLAY_LENGTH_RUNS) {
                    // Checked after publishing the transport, so a concurrent
                    // cancelReplayLengths either sees it or is seen here.
                    if (!canContinue()) { outcome = CatalogResult.Failure(CatalogFailure.TEMPORARY); break }
                    if (!current(local)) { outcome = CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED); break }
                    when (val reply = transport.fetch(AbemaReplayLengthRequest(pending))) {
                        is CatalogResult.Failure -> { outcome = reply; break }
                        is CatalogResult.Value -> { found += reply.value.seconds; pending = reply.value.unread }
                    }
                    if (pending.isEmpty()) break
                }
                // Lengths already read are kept even if a later run fails.
                if (found.isNotEmpty() || outcome == null) CatalogResult.Value(found) else outcome
            } catch (_: Throwable) { CatalogResult.Failure(CatalogFailure.TEMPORARY) }
            finally {
                try { transport?.close() } catch (_: Throwable) {
                    cleanup = false; poisoned = true; closed = true; clear()
                    retained.add(this); transport?.let(retained::add)
                }
                if (cleanup) replayActive.compareAndSet(transport, null)
            }
            if (!cleanup) return@synchronized CatalogResult.Failure(CatalogFailure.TEMPORARY)
            if (!current(local)) return@synchronized CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)
            result
        }
    }
    override fun cancelReplayLengths() {
        val transport = replayActive.get()
        try { transport?.cancelActiveRequest() } catch (_: Throwable) {
            poisoned = true; retained.add(this); transport?.let(retained::add)
        }
    }
    override fun lookup(input: String): CatalogResult<CatalogEntry> { clear(); return delegate.lookup(input) }
    override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> { clear(); return delegate.refresh(resource) }
    override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> { clear(); return delegate.resolve(resource) }
    override fun close() {
        closed = true; clear()
        val transport = active.get()
        try { transport?.cancelActiveRequest() } catch (_: Throwable) {
            poisoned = true; retained.add(this); transport?.let(retained::add)
        }
        cancelReplayLengths()
        delegate.close()
    }
    override fun toString() = "AbemaSeriesChildrenCatalog(redacted)"
}
