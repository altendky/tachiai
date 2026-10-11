package net.fstab.tachiai.provider.abema

import java.net.URLEncoder
import java.util.Collections
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*

// Local admission bounds, not provider maxima or a completeness promise.
internal const val ABEMA_SEARCH_PROJECTION_BYTES = 64 * 1024
internal const val ABEMA_SEARCH_MAX_OFFSET = 9980
internal const val ABEMA_SEARCH_MAX_TOTAL = 1_000_000
internal const val ABEMA_SEARCH_MAX_RETAINED = 600
internal const val ABEMA_SEARCH_MAX_DETAIL_CALLS = 64
internal val ABEMA_SEARCH_MODULE_IDS = setOf("ppv", "nowonair", "package", "episode", "timeshift", "scheduled")

internal class AbemaSearchRequest(val query: String, val moduleId: String? = null, val offset: Int = 0) {
    init {
        require(safeAbemaSeriesText(query, 160))
        require(moduleId == null && offset == 0 || moduleId in ABEMA_SEARCH_MODULE_IDS &&
            offset in 0..ABEMA_SEARCH_MAX_OFFSET && offset % 20 == 0)
    }
    fun url(): String = "https://api.p-c3-e.abema-tv.com/v1/search/modules" +
        (moduleId?.let { "/$it" } ?: "") + "?query=" + URLEncoder.encode(query, "UTF-8") + "&device=WEB_MOBILE" +
        (if (moduleId == null) "&itemLimit=8" else "&limit=20&offset=$offset")
    override fun toString() = "AbemaSearchRequest(redacted)"
}

internal fun abemaSearchResource(kind: String, identity: String): CatalogResource? = runCatching {
    val intent = when (kind) {
        "episode" -> CatalogIntent.VIDEO
        "series", "season" -> CatalogIntent.COLLECTION
        "slot", "live_event" -> CatalogIntent.BROADCAST
        else -> return null
    }
    val resource = CatalogResource(ProviderId("abema"), kind, identity, intent)
    when (kind) {
        "slot" -> require(AbemaSlotRequest.from(resource) != null)
        "season" -> require(abemaSeasonParent(resource) != null)
        else -> require(validAbemaSeriesId(identity))
    }
    resource
}.getOrNull()

internal fun abemaSeasonParent(resource: CatalogResource): Pair<String, String>? {
    if (resource.providerId.value != "abema" || resource.kind != "season" || resource.intent != CatalogIntent.COLLECTION) return null
    val parts = resource.identity.split('/')
    return if (parts.size == 2 && parts.all(::validAbemaSeriesId)) parts[0] to parts[1] else null
}

internal class AbemaSearchModule(val id: String, val title: String, val total: Int, entries: List<CatalogEntry>) {
    val entries: List<CatalogEntry> = Collections.unmodifiableList(entries.toList())
    init {
        require(id in ABEMA_SEARCH_MODULE_IDS && safeAbemaSeriesText(title, 64))
        require(total in 0..ABEMA_SEARCH_MAX_TOTAL && total >= entries.size && entries.size <= 20)
        require(entries.map { it.resource }.distinct().size == entries.size)
        require(entries.all { abemaSearchResource(it.resource.kind, it.resource.identity) == it.resource &&
            safeAbemaSeriesText(it.title, 160) && it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null })
    }
    override fun toString() = "AbemaSearchModule(redacted)"
}
internal class AbemaSearchReply(val request: AbemaSearchRequest, modules: List<AbemaSearchModule>) {
    val modules: List<AbemaSearchModule> = Collections.unmodifiableList(modules.toList())
    init {
        require(modules.size <= ABEMA_SEARCH_MODULE_IDS.size && modules.map { it.id }.distinct().size == modules.size)
        if (request.moduleId == null) require(modules.all { it.entries.size <= 8 })
        else require(modules.size == 1 && modules.single().id == request.moduleId)
        require(modules.flatMap { it.entries }.groupBy { it.resource }.values.all { rows -> rows.map { it.title }.distinct().size == 1 })
    }
    override fun toString() = "AbemaSearchReply(redacted)"
}
internal interface AbemaSearchTransport : AutoCloseable {
    fun fetch(request: AbemaSearchRequest): CatalogResult<AbemaSearchReply>
    fun cancelActiveRequest()
}
