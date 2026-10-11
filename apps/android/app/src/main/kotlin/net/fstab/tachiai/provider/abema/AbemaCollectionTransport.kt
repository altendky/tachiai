package net.fstab.tachiai.provider.abema

import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.catalog.*

internal const val ABEMA_MY_LIST_PAGE_LIMIT = 20
internal const val ABEMA_MY_LIST_RESPONSE_LIMIT = 512 * 1024

// Closed read-only endpoints. A collection's proof and private cache never
// establish access to another collection, even under the same account/route.
internal enum class AbemaCollectionKind(val collectionId: String, val title: String) {
    MY_LIST("my_list", "My List"), HISTORY("history", "History")
}

// This port is worker-only. AVAILABLE means a separately verified account and
// collection access, never merely that a protected record contains a token.
internal interface AbemaCollectionAccountAccess {
    val kind: AbemaCollectionKind get() = AbemaCollectionKind.MY_LIST
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
    val kind: AbemaCollectionKind,
    private val transportFactory: (() -> Boolean) -> AbemaCollectionTransport,
) {
    constructor(instanceId: String, contextKey: String, canRequest: () -> Boolean,
        transportFactory: (() -> Boolean) -> AbemaCollectionTransport) :
        this(instanceId, contextKey, canRequest, AbemaCollectionKind.MY_LIST, transportFactory)
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
internal class AbemaMyListPage(entries: List<CatalogEntry>, internal val providerNext: String? = null,
    val kind: AbemaCollectionKind = AbemaCollectionKind.MY_LIST) {
    val entries: List<CatalogEntry> = Collections.unmodifiableList(ArrayList(entries))
    init {
        require(entries.size <= ABEMA_MY_LIST_PAGE_LIMIT)
        require(providerNext == null || validAbemaCollectionContinuation(providerNext))
    }
    override fun toString() = "AbemaMyListPage(redacted)"
}

internal class AbemaMyListResponse(val status: Int, val page: AbemaMyListPage? = null, val retryAtEpochMs: Long? = null,
    val kind: AbemaCollectionKind = AbemaCollectionKind.MY_LIST) {
    init {
        require(status in 100..599)
        require((status == 200) == (page != null))
        require(page == null || page.kind == kind)
        require(retryAtEpochMs == null || status == 429 && retryAtEpochMs >= 0)
    }
    override fun toString() = "AbemaMyListResponse(redacted)"
}

internal interface AbemaCollectionTransport : AutoCloseable {
    val kind: AbemaCollectionKind get() = AbemaCollectionKind.MY_LIST
    // Preserve the original My List port for existing implementations/callers.
    // Production History transports refuse this legacy method.
    fun fetchMyList(next: String?): AbemaMyListResponse
    // Exactly one fixed read-only request selected by immutable kind; no
    // mutations/profile/login/refresh/playback. Non-200 bodies stay unread.
    fun fetch(next: String?): AbemaMyListResponse = fetchMyList(next)
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

internal fun abemaHistoryRequestUri(next: String? = null): URI {
    require(next == null || validAbemaCollectionContinuation(next))
    val suffix = next?.let { "&next=" + URLEncoder.encode(it, "UTF-8") } ?: ""
    return URI("https://api.p-c3-e.abema-tv.com/v1/video/viewing/histories?limit=20&include=liveEvent$suffix")
}

internal fun abemaCollectionRequestUri(kind: AbemaCollectionKind, next: String? = null): URI = when (kind) {
    AbemaCollectionKind.MY_LIST -> abemaMyListRequestUri(next)
    AbemaCollectionKind.HISTORY -> abemaHistoryRequestUri(next)
}
