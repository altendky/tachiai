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
        return base.copy(children = if (transportFactory != null && current(local)) CatalogAccess.AVAILABLE else CatalogAccess.NOT_VERIFIED,
            childrenTitle = "Episodes", childrenHeadingPrefix = "Episodes from")
    }
    override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
        val parent = query.parent ?: run { clear(); return delegate.browse(query) }
        if (parent.providerId != providerId || parent.kind !in setOf("series", "season") || parent.intent != CatalogIntent.COLLECTION)
            return CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
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
