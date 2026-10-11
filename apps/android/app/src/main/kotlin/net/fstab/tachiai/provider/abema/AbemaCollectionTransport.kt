package net.fstab.tachiai.provider.abema

import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.catalog.*

internal const val ABEMA_MY_LIST_PAGE_LIMIT = 20
internal const val ABEMA_MY_LIST_RESPONSE_LIMIT = 512 * 1024

// This port is worker-only. AVAILABLE means a separately verified account and
// collection access, never merely that a protected record contains a token.
internal interface AbemaCollectionAccountAccess {
    fun access(): CatalogAccess
    fun lease(): AbemaCollectionLease?
}

// The binding supplies an opaque digest of route ownership plus durable grant
// generation/process revision. It must not hash a bearer or provider account ID.
// Private grants stay captured inside the binding's worker-only transport factory.
internal class AbemaCollectionLease(
    val instanceId: String,
    internal val contextKey: String,
    private val canRequest: () -> Boolean,
    private val transportFactory: (() -> Boolean) -> AbemaCollectionTransport,
) {
    init {
        require(validProviderInstanceId(instanceId))
        require(contextKey.matches(Regex("[0-9a-f]{64}")))
    }
    fun current(): Boolean = try { canRequest() } catch (_: Throwable) { false }
    fun createTransport(admission: () -> Boolean): AbemaCollectionTransport = transportFactory(admission)
    override fun toString() = "AbemaCollectionLease(redacted)"
}

internal enum class AbemaCollectionFailure {
    INVALID_RESPONSE, RESPONSE_LIMIT, CANCELLED, NETWORK_FAILED, CLEANUP_FAILED,
    POLICY_REFUSED, COOKIE_HANDLER_REFUSED, TIME_LIMIT_REACHED,
}
internal class AbemaCollectionException(val category: AbemaCollectionFailure) :
    Exception(category.name, null, false, false)

// Only the necessary public projection survives parsing. Dataset bodies,
// progress, entitlement fields and provider continuation values are never saved.
internal class AbemaMyListPage(entries: List<CatalogEntry>, internal val providerNext: String? = null) {
    val entries: List<CatalogEntry> = Collections.unmodifiableList(ArrayList(entries))
    init {
        require(entries.size <= ABEMA_MY_LIST_PAGE_LIMIT)
        require(providerNext == null || validAbemaCollectionContinuation(providerNext))
    }
    override fun toString() = "AbemaMyListPage(redacted)"
}

internal class AbemaMyListResponse(val status: Int, val page: AbemaMyListPage? = null, val retryAtEpochMs: Long? = null) {
    init {
        require(status in 100..599)
        require((status == 200) == (page != null))
        require(retryAtEpochMs == null || status == 429 && retryAtEpochMs >= 0)
    }
    override fun toString() = "AbemaMyListResponse(redacted)"
}

internal interface AbemaCollectionTransport : AutoCloseable {
    // Exactly one fixed read-only My List request; never membership mutations,
    // history, profile, login, refresh or playback. Non-200 bodies stay unread.
    fun fetchMyList(next: String?): AbemaMyListResponse
    fun cancelActiveRequest() = Unit
}

internal fun validAbemaCollectionContinuation(value: String): Boolean = value.length in 1..2048 &&
    value.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() || Character.isSurrogate(it) }

// Proven first-party page contract. The routed binding attaches its grant in
// memory; neither it nor a continuation may enter a log or exception message.
internal fun abemaMyListRequestUri(next: String? = null): URI {
    require(next == null || validAbemaCollectionContinuation(next))
    val suffix = next?.let { "&next=" + URLEncoder.encode(it, "UTF-8") } ?: ""
    return URI("https://api.p-c3-e.abema-tv.com/v1/mylist?limit=20&order=updated_at_desc&withDataSet=true&include=liveEvent$suffix")
}
