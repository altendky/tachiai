package net.fstab.tachiai.provider.abema

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.provider.catalog.*

internal const val ABEMA_LATEST_MAX_PAGES = 5

// Series children; public/account discovery remains with its delegate. Browsing
// never turns a collection into a playable episode. Only the explicit latest()
// default (#549) chooses one, from the provider's own newest-first order.
internal class AbemaSeriesChildrenCatalog(
    private val delegate: ProviderCatalog,
    private val transportFactory: ((canRequest: () -> Boolean) -> AbemaSeriesTransport)?,
    private val canPublish: () -> Boolean = { true },
    private val wallMs: () -> Long = System::currentTimeMillis,
) : ProviderCatalog by delegate {
    init { require(delegate.providerId.value == "abema" && net.fstab.tachiai.presentation.validProviderInstanceId(delegate.instanceId)) }
    private class Cursor(val parent: CatalogResource, val request: AbemaSeriesPageRequest,
        val revision: Long, val seen: Set<String>)
    private val lock = Any()
    private val operation = Any()
    private val active = AtomicReference<AbemaSeriesTransport?>()
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
        return synchronized(operation) {
            val local = synchronized(lock) { revision }
            if (!current(local)) { clear(); return@synchronized CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
            val factory = transportFactory ?: return@synchronized CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
            val saved = synchronized(lock) {
                if (query.cursor == null) { cursors.clear(); null }
                else cursors.remove(query.cursor)
            }
            if (query.cursor != null && (saved == null || saved.parent != parent || saved.revision != local))
                return@synchronized CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
            val request = saved?.request ?: AbemaSeriesPageRequest(seriesId, seasonId = seasonId)
            val page = when (val result = fetchPage(local, factory, request)) {
                is CatalogResult.Failure -> return@synchronized result
                is CatalogResult.Value -> result.value
            }
            val seen = saved?.seen.orEmpty()
            if (page.seriesId != seriesId || page.seasonId != seasonId || request.version != null && page.version != request.version || page.entries.any { it.resource.identity in seen }) {
                clear(); return@synchronized CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
            }
            if (page.entries.size == ABEMA_SERIES_PAGE_LIMIT && request.offset == ABEMA_SERIES_MAX_OFFSET) {
                clear(); return@synchronized CatalogResult.Failure(CatalogFailure.TEMPORARY)
            }
            val next = if (page.entries.size == ABEMA_SERIES_PAGE_LIMIT) UUID.randomUUID().toString() else null
            val admitted = synchronized(lock) {
                if (closed || poisoned || revision != local) false else {
                if (next != null) {
                    cursors[next] = Cursor(parent, AbemaSeriesPageRequest(seriesId, request.offset + 20, page.version, seasonId), local,
                        seen + page.entries.map { it.resource.identity })
                    while (cursors.size > 16) cursors.remove(cursors.keys.first())
                }
                true }
            }
            if (!admitted) return@synchronized CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)
            if (!current(local)) { clear(); return@synchronized CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
            CatalogResult.Value(CatalogPage(page.entries, next))
        }
    }
    // Caller holds the operation lock. One owned transport per page; unconfirmed
    // cleanup poisons this catalog exactly as browsing does.
    private fun fetchPage(local: Long, factory: (() -> Boolean) -> AbemaSeriesTransport,
        request: AbemaSeriesPageRequest): CatalogResult<AbemaSeriesPage> {
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
        if (!cleanup) return CatalogResult.Failure(CatalogFailure.TEMPORARY)
        if (!current(local)) { clear(); return CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
        return result
    }

    // Pages the provider's descending `-seq` order (its own newest-first view)
    // and returns the first row broadcast by now; later broadcast times are
    // upcoming and skipped. Every row read, through the end of the chosen
    // page, must keep broadcast times non-increasing, and no row before the
    // choice may lack a broadcast time. Otherwise seq and broadcast order
    // disagree or a row cannot be placed, and the newest is not established.
    // Rows after the chosen page are not read.
    override fun latest(collection: CatalogResource): CatalogResult<CatalogLatest> {
        clear()
        if (collection.providerId != providerId || collection.kind !in setOf("series", "season") ||
            collection.intent != CatalogIntent.COLLECTION) return delegate.latest(collection)
        val season = if (collection.kind == "season") abemaSeasonParent(collection) else null
        if (collection.kind == "series" && !validAbemaSeriesId(collection.identity) || collection.kind == "season" && season == null)
            return CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
        val seriesId = season?.first ?: collection.identity
        val seasonId = season?.second
        return synchronized(operation) {
            val local = synchronized(lock) { revision }
            if (!current(local)) { clear(); return@synchronized CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
            val factory = transportFactory ?: return@synchronized CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
            var request = AbemaSeriesPageRequest(seriesId, seasonId = seasonId, newest = true)
            val seen = mutableSetOf<String>()
            var any = false
            var previous: Long? = null
            repeat(ABEMA_LATEST_MAX_PAGES) {
                val page = when (val result = fetchPage(local, factory, request)) {
                    is CatalogResult.Failure -> return@synchronized result
                    is CatalogResult.Value -> result.value
                }
                val broadcasts = page.broadcastAtSeconds
                if (broadcasts == null || page.seriesId != seriesId || page.seasonId != seasonId ||
                    request.version != null && page.version != request.version ||
                    page.entries.any { !seen.add(it.resource.identity) }) {
                    clear(); return@synchronized CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
                }
                any = any || page.entries.isNotEmpty()
                val nowSeconds = wallMs() / 1000
                val unverified = CatalogResult.Value(CatalogLatest.Refused(CatalogLatestRefusal.ORDER_UNVERIFIED))
                var chosen: Int? = null
                for ((index, time) in broadcasts.withIndex()) {
                    if (time == null) { if (chosen == null) return@synchronized unverified else continue }
                    if (previous != null && time > previous!!) return@synchronized unverified
                    previous = time
                    if (chosen == null && time <= nowSeconds) chosen = index
                }
                if (chosen != null)
                    return@synchronized CatalogResult.Value(CatalogLatest.Found(CatalogPlaybackResource(page.entries[chosen].resource)))
                if (page.entries.size < ABEMA_SERIES_PAGE_LIMIT) return@synchronized CatalogResult.Value(CatalogLatest.Refused(
                    if (any) CatalogLatestRefusal.NONE_PUBLISHED else CatalogLatestRefusal.EMPTY))
                request = AbemaSeriesPageRequest(seriesId, request.offset + ABEMA_SERIES_PAGE_LIMIT, page.version, seasonId, newest = true)
            }
            // Bounded: more upcoming rows than this cannot establish the newest.
            CatalogResult.Value(CatalogLatest.Refused(CatalogLatestRefusal.ORDER_UNVERIFIED))
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
        delegate.close()
    }
    override fun toString() = "AbemaSeriesChildrenCatalog(redacted)"
}
