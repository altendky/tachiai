package net.fstab.tachiai.provider.catalog

import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.validProviderInstanceId

// Public identity only. Adapters normalize URLs into these bounded identifiers;
// grants, signed media URLs and account/session objects never enter this model.
internal enum class CatalogIntent { CHANNEL, BROADCAST, VIDEO, COLLECTION }
internal enum class CatalogAvailability { UNKNOWN, UPCOMING, LIVE, OFFLINE, AVAILABLE, EXPIRED, UNAVAILABLE }
internal enum class CatalogAccess { AVAILABLE, AUTHORIZATION_REQUIRED, RECONNECT_REQUIRED, SCOPE_REQUIRED, UNSUPPORTED, NOT_VERIFIED }

internal data class CatalogResource(
    val providerId: ProviderId,
    val kind: String,
    val identity: String,
    val intent: CatalogIntent,
) {
    init {
        require(providerId.value.matches(Regex("[a-z][a-z0-9_-]{0,31}")))
        require(kind.matches(Regex("[a-z][a-z0-9_-]{0,31}")))
        require(identity.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}")))
    }
}

internal data class CatalogEntry(
    val resource: CatalogResource,
    val title: String,
    val availability: CatalogAvailability = CatalogAvailability.UNKNOWN,
    val scheduledStartEpochMs: Long? = null,
) {
    init {
        require(title == title.trim() && title.length in 1..160 &&
            title.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() })
        require(scheduledStartEpochMs == null || scheduledStartEpochMs >= 0)
    }
}

internal data class CatalogCollection(val id: String, val title: String, val access: CatalogAccess) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_-]{0,31}")))
        require(title == title.trim() && title.length in 1..64 &&
            title.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() })
    }
}

internal data class CatalogCapabilities(
    val browse: CatalogAccess = CatalogAccess.UNSUPPORTED,
    val search: CatalogAccess = CatalogAccess.UNSUPPORTED,
    val lookup: CatalogAccess = CatalogAccess.UNSUPPORTED,
    val children: CatalogAccess = CatalogAccess.UNSUPPORTED,
    val refresh: CatalogAccess = CatalogAccess.UNSUPPORTED,
    val playback: CatalogAccess = CatalogAccess.UNSUPPORTED,
    val collections: List<CatalogCollection> = emptyList(),
) {
    init { require(collections.size <= 16 && collections.map { it.id }.distinct().size == collections.size) }
}

// Cursors are transient and adapter-owned. Never serialize them as item IDs or
// diagnostics; adapters must bind them to query/account/session revision.
internal data class CatalogQuery(val collectionId: String? = null, val search: String? = null,
    val cursor: String? = null, val parent: CatalogResource? = null) {
    init {
        require(collectionId == null || parent == null)
        require(collectionId == null || collectionId.matches(Regex("[a-z][a-z0-9_-]{0,31}")))
        require(search == null || search.length in 1..160 && search.none { it.isISOControl() })
        require(cursor == null || cursor.length in 1..2048 && cursor.none { it.isISOControl() })
    }
}
internal data class CatalogPage(val entries: List<CatalogEntry>, val nextCursor: String? = null) {
    init { require(entries.size <= 100); CatalogQuery(cursor = nextCursor) }
}
internal enum class CatalogFailure { ACCESS_REQUIRED, UNSUPPORTED, NOT_VERIFIED, NOT_FOUND, RATE_LIMITED, TEMPORARY, INVALID_INPUT }
internal sealed interface CatalogResult<out T> {
    data class Value<T>(val value: T) : CatalogResult<T>
    data class Failure(val reason: CatalogFailure, val retryAtEpochMs: Long? = null) : CatalogResult<Nothing> {
        init { require(retryAtEpochMs == null || reason == CatalogFailure.RATE_LIMITED && retryAtEpochMs >= 0) }
    }
}

// Successful resolution retains exact public identity; provider session factories
// prepare it behind the existing native/browser contracts. A collection does not
// become an arbitrary playable child, and catalog access never implies entitlement.
internal data class CatalogPlaybackResource(val resource: CatalogResource) {
    init { require(resource.intent != CatalogIntent.COLLECTION) }
}

// Bound to one instance by construction. Implementations own its selected-route
// transport and account lifecycle. Calls run on a worker, not the UI thread.
internal interface ProviderCatalog : AutoCloseable {
    val providerId: ProviderId
    val instanceId: String
    fun capabilities(): CatalogCapabilities
    fun browse(query: CatalogQuery): CatalogResult<CatalogPage>
    fun lookup(input: String): CatalogResult<CatalogEntry>
    fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry>
    fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource>
    override fun close() {}
}

internal class ProviderCatalogRegistry(factories: Map<ProviderId, (String) -> ProviderCatalog>) {
    private val factories = factories.toMap()
    fun create(providerId: ProviderId, instanceId: String): ProviderCatalog? {
        require(validProviderInstanceId(instanceId))
        val catalog = factories[providerId]?.invoke(instanceId) ?: return null
        check(catalog.providerId == providerId && catalog.instanceId == instanceId)
        return catalog
    }
}
