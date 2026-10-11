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
    private val episodeTransportFactory: ((canRequest: () -> Boolean) -> AbemaEpisodeTransport)? = null,
) : ProviderCatalog {
    init { require(validProviderInstanceId(instanceId)) }
    override val providerId = ProviderId("abema")
    private val operationLock = Any()
    private val snapshotLock = Any()
    private val revision = AtomicLong()
    private val active = AtomicReference<AbemaChannelTransport?>()
    private val activeEpisode = AtomicReference<AbemaEpisodeTransport?>()
    @Volatile private var closed = false
    private class Snapshot(val revision: Long, val search: String?, val entries: List<CatalogEntry>,
        var cursor: String?, var offset: Int)
    private var snapshot: Snapshot? = null
    private class Abort(val reason: CatalogFailure, val retryAt: Long? = null) : Exception(reason.name)
    private fun available() = !closed && try { canPublish() } catch (_: Exception) { false }
    private fun locallyCurrent(local: Long) = !closed && local == revision.get()
    private fun current(local: Long) = locallyCurrent(local) && available()
    private fun requireCurrent(local: Long, ownerFailure: CatalogFailure = CatalogFailure.ACCESS_REQUIRED) {
        if (!current(local)) throw Abort(ownerFailure)
    }
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

    private fun <T> operation(resetSnapshot: Boolean = false,
        ownerFailure: CatalogFailure = CatalogFailure.ACCESS_REQUIRED, work: (Long) -> T): CatalogResult<T> = synchronized(operationLock) {
        if (resetSnapshot) clearSnapshot()
        val local = revision.get()
        try {
            requireCurrent(local, ownerFailure)
            val value = work(local)
            requireCurrent(local, ownerFailure)
            CatalogResult.Value(value)
        } catch (error: Abort) {
            if (error.reason == CatalogFailure.ACCESS_REQUIRED) clearSnapshot()
            CatalogResult.Failure(error.reason, error.retryAt)
        } catch (_: Exception) {
            if (!current(local)) { clearSnapshot(); CatalogResult.Failure(ownerFailure) }
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
            429 -> throw Abort(CatalogFailure.RATE_LIMITED, boundedRetryAt(response.retryAtEpochMs))
            else -> throw Abort(CatalogFailure.TEMPORARY)
        }
    }

    private fun boundedRetryAt(supplied: Long?): Long {
        val now = wallMs().coerceAtLeast(0)
        val maximum = if (now > Long.MAX_VALUE - 86_400_000) Long.MAX_VALUE else now + 86_400_000
        val fallback = if (now > Long.MAX_VALUE - 60_000) Long.MAX_VALUE else now + 60_000
        return (supplied ?: fallback).coerceIn(now, maximum)
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

    private fun episode(resource: CatalogResource) = resource.providerId == providerId && resource.kind == "episode" &&
        resource.intent == CatalogIntent.VIDEO && resource.identity.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))

    private fun validEpisodeEntry(entry: CatalogEntry, expected: CatalogResource): Boolean {
        if (entry.resource != expected || entry.availability != CatalogAvailability.UNKNOWN || entry.scheduledStartEpochMs != null ||
            entry.title.length !in 1..160 || entry.title != entry.title.trim()) return false
        var index = 0
        while (index < entry.title.length) {
            val first = entry.title[index++]
            val codePoint = if (Character.isHighSurrogate(first)) {
                if (index == entry.title.length || !Character.isLowSurrogate(entry.title[index])) return false
                Character.toCodePoint(first, entry.title[index++])
            } else {
                if (Character.isLowSurrogate(first)) return false
                first.code
            }
            if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT.toInt()) return false
        }
        return true
    }

    private fun episodeMetadata(local: Long, resource: CatalogResource): CatalogEntry {
        val factory = episodeTransportFactory ?: throw Abort(CatalogFailure.NOT_VERIFIED)
        requireCurrent(local, CatalogFailure.TEMPORARY)
        val transport = factory { current(local) }
        if (!activeEpisode.compareAndSet(null, transport)) {
            transport.close()
            throw Abort(CatalogFailure.TEMPORARY)
        }
        val response = try {
            requireCurrent(local, CatalogFailure.TEMPORARY)
            transport.fetch(resource)
        } finally {
            try { transport.close() } catch (error: Exception) {
                // Unknown cleanup cannot admit another metadata attempt. The
                // transport/coordinator retains any unsafe runtime/route.
                closed = true
                revision.incrementAndGet()
                clearSnapshot()
                throw error
            } finally { activeEpisode.compareAndSet(transport, null) }
        }
        requireCurrent(local, CatalogFailure.TEMPORARY)
        return when (response) {
            is CatalogResult.Value -> response.value.also {
                if (!validEpisodeEntry(it, resource)) throw Abort(CatalogFailure.TEMPORARY)
            }
            is CatalogResult.Failure -> when (response.reason) {
                CatalogFailure.RATE_LIMITED -> throw Abort(CatalogFailure.RATE_LIMITED, boundedRetryAt(response.retryAtEpochMs))
                CatalogFailure.TEMPORARY, CatalogFailure.NOT_FOUND, CatalogFailure.NOT_VERIFIED -> throw Abort(response.reason)
                else -> throw Abort(CatalogFailure.TEMPORARY)
            }
        }
    }

    override fun lookup(input: String): CatalogResult<CatalogEntry> = operation(resetSnapshot = true) { local ->
        val imported = parseAbemaPublicResource(input)
        val identity = imported?.resource?.takeIf(::channel)?.identity ?: input.trim(' ')
        if (!identity.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")))
            throw Abort(CatalogFailure.INVALID_INPUT)
        channels(local).singleOrNull { it.resource.identity == identity } ?: imported?.takeIf { channel(it.resource) }
            ?: throw Abort(CatalogFailure.NOT_FOUND)
    }

    override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> {
        val episodeRequest = resource.providerId == providerId && resource.kind == "episode"
        return operation(resetSnapshot = true,
            ownerFailure = if (episodeRequest) CatalogFailure.TEMPORARY else CatalogFailure.ACCESS_REQUIRED) { local ->
            if (episodeRequest) {
                if (!episode(resource)) throw Abort(CatalogFailure.INVALID_INPUT)
                episodeMetadata(local, resource)
            } else {
                if (!channel(resource)) throw Abort(if (resource.providerId == providerId)
                    CatalogFailure.NOT_VERIFIED else CatalogFailure.INVALID_INPUT)
                channels(local).singleOrNull { it.resource == resource } ?: throw Abort(CatalogFailure.NOT_FOUND)
            }
        }
    }

    override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> =
        CatalogResult.Failure(if (resource.providerId == providerId) CatalogFailure.NOT_VERIFIED else CatalogFailure.INVALID_INPUT)

    override fun close() {
        closed = true
        revision.incrementAndGet()
        clearSnapshot()
        val channelTransport = active.getAndSet(null)
        val episodeTransport = activeEpisode.getAndSet(null)
        try { channelTransport?.close() }
        finally { episodeTransport?.let { transport ->
            try { transport.cancelActiveRequest() } finally { transport.close() }
        } }
    }
    override fun toString() = "AbemaChannelCatalog(redacted)"
}
