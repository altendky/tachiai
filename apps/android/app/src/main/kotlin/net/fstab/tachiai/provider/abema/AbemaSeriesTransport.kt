package net.fstab.tachiai.provider.abema

import java.net.URLEncoder
import java.util.Collections
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*

internal const val ABEMA_SERIES_PAGE_LIMIT = 20
internal const val ABEMA_SERIES_MAX_OFFSET = 9980
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
// Newest-first requests use the provider consumer's own descending `-seq`
// order and additionally project each row's broadcast time (epoch seconds).
internal class AbemaSeriesPageRequest(val seriesId: String, val offset: Int = 0, val version: String? = null, val seasonId: String? = null,
    val newest: Boolean = false) {
    init {
        require(validAbemaSeriesId(seriesId))
        require(seasonId == null || validAbemaSeriesId(seasonId) && seriesId.length + seasonId.length + 1 <= 256)
        require(offset in 0..ABEMA_SERIES_MAX_OFFSET && offset % ABEMA_SERIES_PAGE_LIMIT == 0)
        require(if (offset == 0) version == null else version != null && safeAbemaSeriesText(version, 256))
    }
    fun url(): String = "https://api.p-c3-e.abema-tv.com/v1/video/series/$seriesId/programs?order=${if (newest) "-seq" else "seq"}&limit=20&offset=$offset" +
        (version?.let { "&seriesVersion=" + URLEncoder.encode(it, "UTF-8") } ?: "") +
        (seasonId?.let { "&seasonId=$it" } ?: "")
    override fun toString() = "AbemaSeriesPageRequest(redacted)"
}
internal const val ABEMA_BROADCAST_AT_LIMIT_SECONDS = 100_000_000_000L
internal const val ABEMA_EPISODE_NUMBER_MAX = 999_999_999L
internal class AbemaSeriesPage(val seriesId: String, entries: List<CatalogEntry>, val version: String, val seasonId: String? = null,
    broadcastAtSeconds: List<Long?>? = null, episodeNumbers: List<Long?>? = null) {
    val entries: List<CatalogEntry> = Collections.unmodifiableList(entries.toList())
    // Present only for newest-first pages; null rows had no provider broadcast time.
    val broadcastAtSeconds: List<Long?>? = broadcastAtSeconds?.let { Collections.unmodifiableList(it.toList()) }
    // Present only for newest-first pages; null rows had no provider episode number.
    val episodeNumbers: List<Long?>? = episodeNumbers?.let { Collections.unmodifiableList(it.toList()) }
    init {
        require((broadcastAtSeconds == null) == (episodeNumbers == null))
        require(episodeNumbers == null || episodeNumbers.size == entries.size &&
            episodeNumbers.all { it == null || it in 1..ABEMA_EPISODE_NUMBER_MAX })
        require(broadcastAtSeconds == null || broadcastAtSeconds.size == entries.size &&
            broadcastAtSeconds.all { it == null || it in 1 until ABEMA_BROADCAST_AT_LIMIT_SECONDS })
        require(validAbemaSeriesId(seriesId) && entries.size <= ABEMA_SERIES_PAGE_LIMIT && safeAbemaSeriesText(version, 256))
        require(seasonId == null || validAbemaSeriesId(seasonId) && seriesId.length + seasonId.length + 1 <= 256)
        require(entries.all { it.resource.providerId == ProviderId("abema") && it.resource.kind == "episode" &&
            it.resource.intent == CatalogIntent.VIDEO && validAbemaSeriesId(it.resource.identity) &&
            safeAbemaSeriesText(it.title, 160) && it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null })
        require(entries.map { it.resource }.distinct().size == entries.size)
    }
    override fun toString() = "AbemaSeriesPage(redacted)"
}
internal interface AbemaSeriesTransport : AutoCloseable {
    fun fetch(request: AbemaSeriesPageRequest): CatalogResult<AbemaSeriesPage>
    fun cancelActiveRequest()
}
