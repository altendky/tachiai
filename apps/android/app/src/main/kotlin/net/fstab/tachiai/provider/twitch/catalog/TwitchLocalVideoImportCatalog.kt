package net.fstab.tachiai.provider.twitch.catalog

import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.catalog.*

// Public video URLs carry an exact video identity. Login aliases still need the
// connected adapter's Get Users lookup; this decorator never guesses an account
// or downgrades a failed connected request to unverified local success.
internal class TwitchLocalVideoImportCatalog(
    private val delegate: ProviderCatalog,
    private val canImport: () -> Boolean = { true },
) : ProviderCatalog {
    override val providerId = delegate.providerId
    override val instanceId = delegate.instanceId
    init { require(providerId == ProviderId("twitch") && validProviderInstanceId(instanceId)) }

    private enum class LookupMode { UNCHECKED, LOCAL, CONNECTED }
    private data class Snapshot(val revision: Long, val mode: LookupMode)
    private val lock = Any()
    private var closed = false
    private var revision = 0L
    private var mode = LookupMode.UNCHECKED

    private fun deniedCapabilities() = CatalogCapabilities(
        browse = CatalogAccess.NOT_VERIFIED, search = CatalogAccess.NOT_VERIFIED,
        lookup = CatalogAccess.NOT_VERIFIED, children = CatalogAccess.NOT_VERIFIED,
        refresh = CatalogAccess.NOT_VERIFIED, playback = CatalogAccess.NOT_VERIFIED,
    )
    private fun denied() = CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)

    private fun owned(): Boolean {
        if (synchronized(lock) { closed }) return false
        val allowed = try { canImport() } catch (_: Exception) { false }
        return synchronized(lock) {
            if (!allowed && !closed) { revision++; mode = LookupMode.UNCHECKED }
            allowed && !closed
        }
    }
    private fun snapshot(): Snapshot? {
        if (!owned()) return null
        return synchronized(lock) { if (closed) null else Snapshot(revision, mode) }
    }
    private fun current(snapshot: Snapshot): Boolean = owned() && synchronized(lock) {
        !closed && revision == snapshot.revision
    }

    override fun capabilities(): CatalogCapabilities {
        if (!owned()) return deniedCapabilities()
        val reading = synchronized(lock) {
            if (closed) return deniedCapabilities()
            mode = LookupMode.UNCHECKED
            Snapshot(++revision, mode)
        }
        // No capability or lookup I/O runs under the memory lock. Close and an
        // owner loss invalidate outstanding work before delegate cleanup.
        val capabilities = try { delegate.capabilities() }
            catch (error: Exception) {
                if (!current(reading)) return deniedCapabilities()
                throw error
            }
        if (!current(reading)) return deniedCapabilities()
        return synchronized(lock) {
            if (closed || revision != reading.revision) return deniedCapabilities()
            mode = if (capabilities.lookup == CatalogAccess.AVAILABLE) LookupMode.CONNECTED else LookupMode.LOCAL
            capabilities.copy(lookup = CatalogAccess.AVAILABLE)
        }
    }

    override fun lookup(input: String): CatalogResult<CatalogEntry> {
        val reading = snapshot() ?: return denied()
        val result = when (reading.mode) {
            LookupMode.UNCHECKED -> return denied()
            LookupMode.CONNECTED -> return delegated(reading) { delegate.lookup(input) }
            LookupMode.LOCAL -> {
                val video = try { parseTwitchCatalogPublicInput(input) as? TwitchCatalogPublicInput.VideoId }
                    catch (_: TwitchHelixException) { null }
                if (video == null) CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
                else CatalogResult.Value(CatalogEntry(
                    CatalogResource(providerId, "video", video.id, CatalogIntent.VIDEO),
                    "Public Twitch video ${video.id} (metadata not checked)", CatalogAvailability.UNKNOWN,
                ))
            }
        }
        return if (current(reading)) result else denied()
    }

    private fun <T> delegated(action: () -> CatalogResult<T>): CatalogResult<T> {
        val reading = snapshot() ?: return denied()
        return delegated(reading, action)
    }
    private fun <T> delegated(reading: Snapshot, action: () -> CatalogResult<T>): CatalogResult<T> {
        val result = try { action() }
            catch (error: Exception) {
                if (!current(reading)) return denied()
                throw error
            }
        return if (current(reading)) result else denied()
    }
    override fun browse(query: CatalogQuery) = delegated { delegate.browse(query) }
    override fun refresh(resource: CatalogResource) = delegated { delegate.refresh(resource) }
    override fun resolve(resource: CatalogResource) = delegated { delegate.resolve(resource) }

    override fun close() {
        val first = synchronized(lock) {
            if (closed) false else { closed = true; revision++; mode = LookupMode.UNCHECKED; true }
        }
        if (first) delegate.close()
    }
    override fun toString() = "TwitchLocalVideoImportCatalog(redacted)"
}
