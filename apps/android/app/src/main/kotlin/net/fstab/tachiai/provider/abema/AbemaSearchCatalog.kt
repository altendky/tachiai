package net.fstab.tachiai.provider.abema

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.provider.catalog.*

// Summary prefixes continue through real module GETs. Provider offsets may
// overlap/omit changing results; these pages do not claim a server snapshot.
internal class AbemaSearchCatalog(
    private val delegate: ProviderCatalog,
    private val transportFactory: ((canRequest: () -> Boolean) -> AbemaSearchTransport)?,
    private val canPublish: () -> Boolean = { true },
) : ProviderCatalog by delegate {
    init { require(delegate.providerId.value == "abema" && net.fstab.tachiai.presentation.validProviderInstanceId(delegate.instanceId)) }
    private data class Descriptor(val id: String)
    private class Continuation(val token: String, val query: String, val modules: List<Descriptor>, val index: Int,
        val offset: Int, val seen: Set<CatalogResource>, val calls: Int, val revision: Long)
    private val lock = Any()
    private val operation = Any()
    private val active = AtomicReference<AbemaSearchTransport?>()
    private var revision = 0L
    private var continuation: Continuation? = null
    @Volatile private var closed = false
    @Volatile private var poisoned = false
    companion object { private val retained = java.util.concurrent.ConcurrentLinkedQueue<Any>() }
    private fun admitted(local: Long) = !closed && !poisoned && synchronized(lock) { revision == local }
    private fun current(local: Long) = admitted(local) && runCatching(canPublish).getOrDefault(false) && admitted(local)
    private fun clear() = synchronized(lock) { revision++; continuation = null }
    override fun capabilities(): CatalogCapabilities {
        val base = delegate.capabilities(); val local = synchronized(lock) { revision }
        return base.copy(search = if (transportFactory != null && current(local)) CatalogAccess.AVAILABLE else CatalogAccess.NOT_VERIFIED)
    }
    override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
        if (query.search == null) { clear(); return delegate.browse(query) }
        if (query.parent != null || query.collectionId != null || !safeAbemaSeriesText(query.search, 160))
            return CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
        return synchronized(operation) {
            if (query.cursor == null) clear()
            val local = synchronized(lock) { revision }
            if (!current(local)) { clear(); return@synchronized CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
            val saved = synchronized(lock) {
                if (query.cursor == null) null else continuation?.takeIf { it.token == query.cursor && it.query == query.search && it.revision == local }
            }
            if (query.cursor != null && saved == null) { clear(); return@synchronized CatalogResult.Failure(CatalogFailure.INVALID_INPUT) }
            synchronized(lock) { continuation = null }
            if (saved != null && (saved.calls >= ABEMA_SEARCH_MAX_DETAIL_CALLS || saved.offset > ABEMA_SEARCH_MAX_OFFSET)) {
                clear(); return@synchronized CatalogResult.Failure(CatalogFailure.TEMPORARY)
            }
            val request = AbemaSearchRequest(query.search, saved?.let { it.modules[it.index].id }, saved?.offset ?: 0)
            val factory = transportFactory ?: return@synchronized CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
            var transport: AbemaSearchTransport? = null; var cleanup = true
            val result = try {
                transport = factory { current(local) }; active.set(transport)
                if (!current(local)) CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) else transport.fetch(request)
            } catch (_: Throwable) { CatalogResult.Failure(CatalogFailure.TEMPORARY) }
            finally {
                try { transport?.close() } catch (_: Throwable) {
                    cleanup = false; poisoned = true; closed = true; clear(); retained.add(this); transport?.let(retained::add)
                }
                if (cleanup) active.compareAndSet(transport, null)
            }
            if (!cleanup) return@synchronized CatalogResult.Failure(CatalogFailure.TEMPORARY)
            if (!current(local)) { clear(); return@synchronized CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
            val reply = when (result) { is CatalogResult.Failure -> return@synchronized result; is CatalogResult.Value -> result.value }
            if (reply.request.query != request.query || reply.request.moduleId != request.moduleId || reply.request.offset != request.offset)
                return@synchronized CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
            val rows = reply.modules.flatMap { it.entries }.distinctBy { it.resource }
            val entries = rows.filter { it.resource !in saved?.seen.orEmpty() }
            val seen = saved?.seen.orEmpty() + rows.map { it.resource }
            if (seen.size > ABEMA_SEARCH_MAX_RETAINED) { clear(); return@synchronized CatalogResult.Failure(CatalogFailure.TEMPORARY) }
            val modules = saved?.modules ?: reply.modules.filter { it.total > it.entries.size }.map { Descriptor(it.id) }
            val index = if (saved == null) 0 else if (saved.offset + 20 < reply.modules.single().total) saved.index else saved.index + 1
            val offset = if (saved != null && index == saved.index) saved.offset + 20 else 0
            val next = if (index < modules.size) UUID.randomUUID().toString() else null
            // An over-bound continuation is retained only to produce an
            // explicit refusal on More; null must mean observed exhaustion.
            val published = synchronized(lock) {
                if (!admitted(local)) false else {
                    continuation = next?.let { Continuation(it, query.search, modules, index, offset, seen,
                        (saved?.calls ?: 0) + if (saved == null) 0 else 1, local) }; true
                }
            }
            if (!published || !current(local)) { clear(); return@synchronized CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
            CatalogResult.Value(CatalogPage(entries, next))
        }
    }
    override fun lookup(input: String): CatalogResult<CatalogEntry> { clear(); return delegate.lookup(input) }
    override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> { clear(); return delegate.refresh(resource) }
    override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> { clear(); return delegate.resolve(resource) }
    override fun close() {
        closed = true; clear()
        val transport = active.get()
        try { transport?.cancelActiveRequest() } catch (_: Throwable) { poisoned = true; retained.add(this); transport?.let(retained::add) }
        delegate.close()
    }
    override fun toString() = "AbemaSearchCatalog(redacted)"
}
