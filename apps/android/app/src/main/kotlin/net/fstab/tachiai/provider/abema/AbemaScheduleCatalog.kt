package net.fstab.tachiai.provider.abema

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.provider.catalog.*

// A single routed guest snapshot, locally paged. Account collections, exact
// refresh and channels remain with the delegate; a schedule is not playback.
internal class AbemaScheduleCatalog(
    private val delegate: ProviderCatalog,
    private val transportFactory: ((canRequest: () -> Boolean) -> AbemaScheduleTransport)?,
    private val canPublish: () -> Boolean = { true },
) : ProviderCatalog by delegate {
    init { require(delegate.providerId.value == "abema" && net.fstab.tachiai.presentation.validProviderInstanceId(delegate.instanceId)) }
    private class Continuation(val token: String, val offset: Int, val revision: Long, val request: AbemaScheduleRequest)
    private val lock = Any()
    private val operation = Any()
    private val active = AtomicReference<AbemaScheduleTransport?>()
    private var revision = 0L
    private var snapshot: AbemaScheduleSnapshot? = null
    private var continuation: Continuation? = null
    @Volatile private var closed = false
    @Volatile private var poisoned = false
    companion object { private val retained = java.util.concurrent.ConcurrentLinkedQueue<Any>() }
    private fun admitted(local: Long) = !closed && !poisoned && synchronized(lock) { revision == local }
    private fun current(local: Long) = admitted(local) && runCatching(canPublish).getOrDefault(false) && admitted(local)
    private fun clear() = synchronized(lock) { revision++; snapshot = null; continuation = null }
    override fun capabilities(): CatalogCapabilities {
        val base = delegate.capabilities()
        val local = synchronized(lock) { revision }
        val access = if (transportFactory != null && current(local)) CatalogAccess.AVAILABLE else CatalogAccess.NOT_VERIFIED
        return base.copy(collections = base.collections.filterNot { it.id == "schedule" } + CatalogCollection("schedule", "Schedule", access),
            children = access, childrenTitle = "Programs", childrenHeadingPrefix = "Programs from")
    }
    override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
        val parent = query.parent
        if (parent == null && query.collectionId != "schedule") { clear(); return delegate.browse(query) }
        if (parent != null && (parent.providerId != providerId || parent.kind != "channel" || parent.intent != CatalogIntent.CHANNEL)) {
            clear(); return delegate.browse(query)
        }
        if (query.search != null || parent != null && (query.collectionId != null || !AbemaSlotRequest.validId(parent.identity))) {
            clear(); return CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
        }
        val request = AbemaScheduleRequest(parent?.identity)
        return synchronized(operation) {
            if (query.cursor == null) clear()
            val local = synchronized(lock) { revision }
            if (!current(local)) { clear(); return@synchronized CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
            val offset = synchronized(lock) {
                if (query.cursor == null) 0 else continuation?.takeIf { it.token == query.cursor && it.revision == local && it.request == request }?.offset
            } ?: run { clear(); return@synchronized CatalogResult.Failure(CatalogFailure.INVALID_INPUT) }
            synchronized(lock) { continuation = null }
            val pageSnapshot = if (query.cursor != null) synchronized(lock) { snapshot }
            else {
                val factory = transportFactory ?: return@synchronized CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
                var transport: AbemaScheduleTransport? = null
                var cleanup = true
                val result = try {
                    transport = factory { current(local) }; active.set(transport)
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
                when (result) {
                    is CatalogResult.Failure -> return@synchronized result
                    is CatalogResult.Value -> result.value
                }
            } ?: return@synchronized CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
            if (!current(local)) { clear(); return@synchronized CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
            if (pageSnapshot.request != request) { clear(); return@synchronized CatalogResult.Failure(CatalogFailure.INVALID_INPUT) }
            val entries = pageSnapshot.entries.drop(offset).take(ABEMA_SCHEDULE_PAGE_LIMIT)
            val nextOffset = offset + entries.size
            val token = if (nextOffset < pageSnapshot.entries.size) UUID.randomUUID().toString() else null
            val published = synchronized(lock) {
                if (!admitted(local)) false else {
                    snapshot = pageSnapshot.takeIf { token != null }
                    continuation = token?.let { Continuation(it, nextOffset, local, request) }; true
                }
            }
            if (!published || !current(local)) { clear(); return@synchronized CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
            CatalogResult.Value(CatalogPage(entries, token))
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
    override fun toString() = "AbemaScheduleCatalog(redacted)"
}
