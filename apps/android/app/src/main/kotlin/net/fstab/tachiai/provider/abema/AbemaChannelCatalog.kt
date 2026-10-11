package net.fstab.tachiai.provider.abema

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.catalog.*

// One instance/route-owned public snapshot. No account data, source URL,
// entitlement or live state is inferred from membership in the channel list.
internal class AbemaChannelCatalog(
    override val instanceId: String,
    private val transportFactory: (canRequest: () -> Boolean) -> AbemaChannelTransport,
    private val canPublish: () -> Boolean = { true },
    private val wallMs: () -> Long = System::currentTimeMillis,
) : ProviderCatalog {
    init { require(validProviderInstanceId(instanceId)) }
    override val providerId = ProviderId("abema")
    private val operationLock = Any()
    private val snapshotLock = Any()
    private val revision = AtomicLong()
    private val active = AtomicReference<AbemaChannelTransport?>()
    @Volatile private var closed = false
    private class Snapshot(val revision: Long, val search: String?, val entries: List<CatalogEntry>,
        var cursor: String?, var offset: Int)
    private var snapshot: Snapshot? = null
    private class Abort(val reason: CatalogFailure, val retryAt: Long? = null) : Exception(reason.name)
    private fun available() = !closed && try { canPublish() } catch (_: Exception) { false }
    private fun locallyCurrent(local: Long) = !closed && local == revision.get()
    private fun current(local: Long) = locallyCurrent(local) && available()
    private fun requireCurrent(local: Long) { if (!current(local)) throw Abort(CatalogFailure.ACCESS_REQUIRED) }
    private fun requireLocal(local: Long) { if (!locallyCurrent(local)) throw Abort(CatalogFailure.ACCESS_REQUIRED) }
    private fun clearSnapshot() = synchronized(snapshotLock) { snapshot = null }

    override fun capabilities(): CatalogCapabilities {
        val access = if (available()) CatalogAccess.AVAILABLE else CatalogAccess.NOT_VERIFIED
        if (access != CatalogAccess.AVAILABLE) clearSnapshot()
        return CatalogCapabilities(browse = access, search = access, lookup = access, refresh = access,
            playback = CatalogAccess.NOT_VERIFIED, browseTitle = "Channels",
            collections = listOf(CatalogCollection("my_list", "My List", CatalogAccess.NOT_VERIFIED),
                CatalogCollection("history", "History", CatalogAccess.NOT_VERIFIED)))
    }

    private fun <T> operation(resetSnapshot: Boolean = false, work: (Long) -> T): CatalogResult<T> = synchronized(operationLock) {
        if (resetSnapshot) clearSnapshot()
        val local = revision.get()
        try {
            requireCurrent(local)
            val value = work(local)
            requireCurrent(local)
            CatalogResult.Value(value)
        } catch (error: Abort) {
            if (error.reason == CatalogFailure.ACCESS_REQUIRED) clearSnapshot()
            CatalogResult.Failure(error.reason, error.retryAt)
        } catch (_: Exception) {
            if (!current(local)) { clearSnapshot(); CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
            else CatalogResult.Failure(CatalogFailure.TEMPORARY)
        }
    }

    private fun channels(local: Long): List<CatalogEntry> {
        requireCurrent(local)
        val transport = transportFactory { current(local) }
        if (!active.compareAndSet(null, transport)) {
            transport.close()
            throw Abort(CatalogFailure.TEMPORARY)
        }
        val response = try {
            requireCurrent(local)
            transport.fetch()
        } finally {
            active.compareAndSet(transport, null)
            transport.close()
        }
        requireCurrent(local)
        when (response.status) {
            200 -> {
                if (response.entries.size > 500 || response.entries.any { !channel(it.resource) ||
                    it.availability != CatalogAvailability.UNKNOWN || it.scheduledStartEpochMs != null } ||
                    response.entries.map { it.resource }.distinct().size != response.entries.size)
                    throw Abort(CatalogFailure.TEMPORARY)
                return response.entries.toList()
            }
            429 -> {
                val now = wallMs().coerceAtLeast(0)
                val maximum = if (now > Long.MAX_VALUE - 86_400_000) Long.MAX_VALUE else now + 86_400_000
                val fallback = if (now > Long.MAX_VALUE - 60_000) Long.MAX_VALUE else now + 60_000
                throw Abort(CatalogFailure.RATE_LIMITED, (response.retryAtEpochMs ?: fallback).coerceIn(now, maximum))
            }
            else -> throw Abort(CatalogFailure.TEMPORARY)
        }
    }

    private fun search(query: CatalogQuery): String? = query.search?.trim()?.also {
        if (it.isEmpty() || it.any { character -> Character.getType(character) == Character.FORMAT.toInt() })
            throw Abort(CatalogFailure.INVALID_INPUT)
    }
    private fun page(local: Long, search: String?, entries: List<CatalogEntry>): CatalogPage {
        requireCurrent(local)
        val next = if (entries.size > 20) UUID.randomUUID().toString() else null
        synchronized(snapshotLock) {
            requireLocal(local)
            snapshot = if (next == null) null else Snapshot(local, search, entries, next, 20)
        }
        return CatalogPage(entries.take(20), next)
    }

    override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
        if (query.collectionId != null) {
            return synchronized(operationLock) {
                clearSnapshot()
                CatalogResult.Failure(if (query.collectionId in setOf("my_list", "history"))
                    CatalogFailure.NOT_VERIFIED else CatalogFailure.INVALID_INPUT)
            }
        }
        if (query.parent != null) return synchronized(operationLock) {
            clearSnapshot(); CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        }
        return operation(resetSnapshot = query.cursor == null) { local ->
            val search = search(query)
            if (query.cursor != null) {
                synchronized(snapshotLock) {
                    val saved = snapshot ?: throw Abort(CatalogFailure.INVALID_INPUT)
                    if (saved.revision != local || saved.search != search || saved.cursor != query.cursor)
                        throw Abort(CatalogFailure.INVALID_INPUT)
                    requireLocal(local)
                    val entries = saved.entries.drop(saved.offset).take(20)
                    saved.offset += entries.size
                    val next = if (saved.offset < saved.entries.size) UUID.randomUUID().toString() else null
                    saved.cursor = next
                    if (next == null) snapshot = null
                    CatalogPage(entries, next)
                }
            } else {
                val entries = channels(local).filter { search == null || it.title.contains(search, true) ||
                    it.resource.identity.contains(search, true) }
                page(local, search, entries)
            }
        }
    }

    private fun channel(resource: CatalogResource) = resource.providerId == providerId && resource.kind == "channel" &&
        resource.intent == CatalogIntent.CHANNEL && resource.identity.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))

    override fun lookup(input: String): CatalogResult<CatalogEntry> = operation(resetSnapshot = true) { local ->
        val imported = parseAbemaPublicResource(input)
        val identity = imported?.resource?.takeIf(::channel)?.identity ?: input.trim(' ')
        if (!identity.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")))
            throw Abort(CatalogFailure.INVALID_INPUT)
        channels(local).singleOrNull { it.resource.identity == identity } ?: imported?.takeIf { channel(it.resource) }
            ?: throw Abort(CatalogFailure.NOT_FOUND)
    }

    override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> = operation(resetSnapshot = true) { local ->
        if (!channel(resource)) throw Abort(if (resource.providerId == providerId)
            CatalogFailure.NOT_VERIFIED else CatalogFailure.INVALID_INPUT)
        channels(local).singleOrNull { it.resource == resource } ?: throw Abort(CatalogFailure.NOT_FOUND)
    }

    override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> =
        CatalogResult.Failure(if (resource.providerId == providerId) CatalogFailure.NOT_VERIFIED else CatalogFailure.INVALID_INPUT)

    override fun close() {
        closed = true
        revision.incrementAndGet()
        clearSnapshot()
        active.getAndSet(null)?.close()
    }
    override fun toString() = "AbemaChannelCatalog(redacted)"
}
