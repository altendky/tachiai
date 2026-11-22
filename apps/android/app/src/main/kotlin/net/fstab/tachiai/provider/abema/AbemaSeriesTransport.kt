package net.fstab.tachiai.provider.abema

import java.util.Collections
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*

internal const val ABEMA_SERIES_PAGE_LIMIT = 20
internal const val ABEMA_SERIES_MAX_OFFSET = 9980
internal const val ABEMA_SERIES_MAX_GROUPS = 16
internal const val ABEMA_SERIES_CONTENTLIST = "https://api.p-c3-e.abema-tv.com/v1/contentlist"
internal fun validAbemaSeriesId(value: String) = value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
internal fun safeAbemaSeriesText(value: String, maximum: Int): Boolean {
    if (value.length !in 1..maximum || value != value.trim()) return false
    var at = 0
    while (at < value.length) {
        val first = value[at++]
        val point = if (Character.isHighSurrogate(first)) {
            if (at == value.length || !Character.isLowSurrogate(value[at])) return false
            Character.toCodePoint(first, value[at++])
        } else { if (Character.isLowSurrogate(first)) return false; first.code }
        if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT.toInt()) return false
    }
    return true
}
// Season and episode-group IDs were observed as `{seriesId}_s{n}` and
// `{seriesId}_eg{n}`; only the shared charset and this parent prefix are required.
internal fun boundAbemaSeriesChild(seriesId: String, id: String) =
    validAbemaSeriesId(id) && id.length > seriesId.length + 1 && id.startsWith(seriesId + "_")
internal fun validAbemaViewingTypes(values: List<Int>) =
    values.size in 1..4 && values.distinct().size == values.size && values.all { it in 1..99 }

// The contentlist chain ABEMA's series page uses (#622). A first page (no
// viewing types yet) reads the series structure and the chosen group's
// sections before its contents; a continuation carries season, group and
// viewing types and reads only the contents. Newest-first is `desc`.
internal class AbemaSeriesPageRequest(val seriesId: String, val offset: Int = 0, val seasonId: String? = null,
    val groupId: String? = null, viewingTypes: List<Int>? = null, val newest: Boolean = false) {
    val viewingTypes: List<Int>? = viewingTypes?.let { Collections.unmodifiableList(it.toList()) }
    init {
        require(validAbemaSeriesId(seriesId))
        require(seasonId == null || boundAbemaSeriesChild(seriesId, seasonId))
        require(groupId == null || boundAbemaSeriesChild(seriesId, groupId))
        require(viewingTypes == null || validAbemaViewingTypes(viewingTypes) && seasonId != null && groupId != null)
        require(offset in 0..ABEMA_SERIES_MAX_OFFSET && offset % ABEMA_SERIES_PAGE_LIMIT == 0)
        require(offset == 0 || viewingTypes != null)
    }
    fun seriesUrl() = "$ABEMA_SERIES_CONTENTLIST/series/$seriesId?includes=liveEvent%2Cslot"
    fun sectionsUrl(group: String, season: String): String {
        require(boundAbemaSeriesChild(seriesId, group) && boundAbemaSeriesChild(seriesId, season))
        return "$ABEMA_SERIES_CONTENTLIST/episodeGroups/$group/sections?seasonId=$season&premiumUser=false"
    }
    fun contentsUrl(group: String, season: String, types: List<Int>): String {
        require(boundAbemaSeriesChild(seriesId, group) && boundAbemaSeriesChild(seriesId, season) && validAbemaViewingTypes(types))
        return "$ABEMA_SERIES_CONTENTLIST/episodeGroups/$group/contents?seasonId=$season&orderType=${if (newest) "desc" else "asc"}" +
            "&limit=20&offset=$offset&includes=liveEvent%2Cslot&viewingTypes=${types.joinToString("%2C")}"
    }
    // Private binding input only; never logged or exported.
    fun key() = listOf(seriesId, seasonId.orEmpty(), groupId.orEmpty(), viewingTypes?.joinToString(",").orEmpty(),
        offset, if (newest) "desc" else "asc").joinToString("|")
    fun next(page: AbemaSeriesPage) = AbemaSeriesPageRequest(seriesId, offset + ABEMA_SERIES_PAGE_LIMIT, page.seasonId,
        page.groupId, page.viewingTypes, newest)
    override fun toString() = "AbemaSeriesPageRequest(redacted)"
}
internal class AbemaSeriesGroup(val id: String, val name: String) {
    override fun toString() = "AbemaSeriesGroup(redacted)"
}
// One group's contents in ABEMA's order: on-demand episodes and broadcast slots.
// groups is present only on a first page; more means the provider page was full.
internal class AbemaSeriesPage(val seriesId: String, val seasonId: String?, val seasonName: String?, groups: List<AbemaSeriesGroup>?,
    val groupId: String?, viewingTypes: List<Int>, entries: List<CatalogEntry>, val more: Boolean) {
    val groups: List<AbemaSeriesGroup>? = groups?.let { Collections.unmodifiableList(it.toList()) }
    val viewingTypes: List<Int> = Collections.unmodifiableList(viewingTypes.toList())
    val entries: List<CatalogEntry> = Collections.unmodifiableList(entries.toList())
    init {
        require(validAbemaSeriesId(seriesId))
        require(seasonId == null || boundAbemaSeriesChild(seriesId, seasonId))
        require(seasonName == null || seasonId != null && safeAbemaSeriesText(seasonName, 160))
        require(groups == null || groups.size <= ABEMA_SERIES_MAX_GROUPS && groups.map { it.id }.distinct().size == groups.size &&
            groups.all { boundAbemaSeriesChild(seriesId, it.id) && safeAbemaSeriesText(it.name, 64) })
        require(groupId == null || seasonId != null && boundAbemaSeriesChild(seriesId, groupId) && groups?.any { it.id == groupId } != false)
        require(groups == null || groups.isEmpty() == (groupId == null))
        require(viewingTypes.isEmpty() || groupId != null && validAbemaViewingTypes(viewingTypes))
        require(entries.size <= ABEMA_SERIES_PAGE_LIMIT && (entries.isEmpty() && !more || viewingTypes.isNotEmpty()))
        require(entries.size < ABEMA_SERIES_PAGE_LIMIT || more)
        require(entries.all { entry -> val resource = entry.resource
            resource.providerId == ProviderId("abema") && safeAbemaSeriesText(entry.title, 160) &&
                entry.availability == CatalogAvailability.UNKNOWN && entry.scheduledStartEpochMs == null &&
                (resource.kind == "episode" && resource.intent == CatalogIntent.VIDEO && validAbemaSeriesId(resource.identity) ||
                    AbemaSlotRequest.from(resource) != null) })
        require(entries.map { it.resource }.distinct().size == entries.size)
    }
    // Exactly the requested parent, any requested season/group, and either a
    // first page's group list or a continuation's unchanged viewing types.
    fun answers(request: AbemaSeriesPageRequest) = seriesId == request.seriesId &&
        (request.seasonId == null || seasonId == request.seasonId) && (request.groupId == null || groupId == request.groupId) &&
        if (request.viewingTypes == null) groups != null else groups == null && viewingTypes == request.viewingTypes
    override fun toString() = "AbemaSeriesPage(redacted)"
}
internal interface AbemaSeriesTransport : AutoCloseable {
    fun fetch(request: AbemaSeriesPageRequest): CatalogResult<AbemaSeriesPage>
    fun cancelActiveRequest()
}
