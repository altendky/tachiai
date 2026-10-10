package net.fstab.tachiai.provider.twitch.catalog

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

internal enum class TwitchHelixFailure { INVALID_INPUT, INVALID_RESPONSE }
internal class TwitchHelixException(val failure: TwitchHelixFailure) : Exception(failure.name)

internal class TwitchCatalogBroadcaster(val id: String, val login: String, val name: String, val live: Boolean? = null) {
    init { require(validTwitchCatalogId(id) && validTwitchCatalogLogin(login) && validTwitchCatalogText(name)) }
    override fun toString() = "TwitchCatalogBroadcaster(redacted)"
}
internal class TwitchCatalogLiveStream(val broadcastId: String, val broadcaster: TwitchCatalogBroadcaster) {
    init { require(validTwitchCatalogId(broadcastId) && broadcaster.live == true) }
    override fun toString() = "TwitchCatalogLiveStream(redacted)"
}
internal class TwitchCatalogVideo(val id: String, val broadcasterId: String, val title: String) {
    init { require(validTwitchCatalogId(id) && validTwitchCatalogId(broadcasterId) && validTwitchCatalogText(title)) }
    override fun toString() = "TwitchCatalogVideo(redacted)"
}
internal class TwitchHelixPage<T>(items: List<T>, val nextCursor: String? = null) {
    val items = items.toList()
    init { require(items.size <= 100 && (nextCursor == null || validTwitchCatalogCursor(nextCursor))) }
    override fun toString() = "TwitchHelixPage(items=${items.size}, cursor redacted)"
}

private fun validTwitchCatalogText(value: String) = value == value.trim() && value.length in 1..160 &&
    value.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
private fun invalidMetadata(): Nothing = throw TwitchHelixException(TwitchHelixFailure.INVALID_RESPONSE)
private fun Map<String, Any?>.text(key: String): String = (this[key] as? String)?.takeIf(::validTwitchCatalogText) ?: invalidMetadata()
private fun Map<String, Any?>.id(key: String): String = (this[key] as? String)?.takeIf(::validTwitchCatalogId) ?: invalidMetadata()
private fun Map<String, Any?>.login(key: String): String = (this[key] as? String)?.takeIf(::validTwitchCatalogLogin) ?: invalidMetadata()

private fun <T> metadataPage(response: TwitchHelixResponse, item: (Map<String, Any?>) -> T): TwitchHelixPage<T> {
    if (response.status != 200) invalidMetadata()
    val rows = response.fields["data"] as? List<*> ?: invalidMetadata()
    if (rows.size > 100) invalidMetadata()
    val pagination = response.fields["pagination"]
    if (response.fields.containsKey("pagination") && pagination !is Map<*, *>) invalidMetadata()
    val cursor = (pagination as? Map<*, *>)?.let { page ->
        if (!page.containsKey("cursor")) null else (page["cursor"] as? String)?.takeIf(::validTwitchCatalogCursor) ?: invalidMetadata()
    }
    return TwitchHelixPage(rows.map { row ->
        if (row !is Map<*, *> || row.keys.any { it !is String }) invalidMetadata()
        @Suppress("UNCHECKED_CAST")
        item(row as Map<String, Any?>)
    }, cursor)
}

internal fun parseTwitchFollowing(response: TwitchHelixResponse) = metadataPage(response) {
    TwitchCatalogBroadcaster(it.id("broadcaster_id"), it.login("broadcaster_login"), it.text("broadcaster_name"))
}
internal fun parseTwitchSearch(response: TwitchHelixResponse) = metadataPage(response) {
    TwitchCatalogBroadcaster(it.id("id"), it.login("broadcaster_login"), it.text("display_name"),
        it["is_live"] as? Boolean ?: invalidMetadata())
}
internal fun parseTwitchUsers(response: TwitchHelixResponse) = metadataPage(response) {
    TwitchCatalogBroadcaster(it.id("id"), it.login("login"), it.text("display_name"))
}
internal fun parseTwitchStreams(response: TwitchHelixResponse) = metadataPage(response) {
    if (it["type"] != "live") invalidMetadata()
    TwitchCatalogLiveStream(it.id("id"), TwitchCatalogBroadcaster(it.id("user_id"), it.login("user_login"), it.text("user_name"), true))
}
internal fun parseTwitchVideos(response: TwitchHelixResponse) = metadataPage(response) {
    if (it["type"] !in setOf("archive", "highlight", "upload")) invalidMetadata()
    TwitchCatalogVideo(it.id("id"), it.id("user_id"), it.text("title"))
}

private val scheduleTimestamp = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-5][0-9](?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})")
private const val MAX_SCHEDULE_EPOCH_MS = 253402300799999L
private fun scheduleTime(value: Any?): Long {
    val text = value as? String ?: invalidMetadata()
    if (!scheduleTimestamp.matches(text)) invalidMetadata()
    return try {
        OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
            .takeIf { it in 0..MAX_SCHEDULE_EPOCH_MS } ?: invalidMetadata()
    } catch (_: Exception) { invalidMetadata() }
}
private fun scheduleObject(value: Any?): Map<String, Any?> {
    val fields = value as? Map<*, *> ?: invalidMetadata()
    if (fields.keys.any { it !is String }) invalidMetadata()
    @Suppress("UNCHECKED_CAST")
    return fields as Map<String, Any?>
}

// Context from one page only. Cancellation and vacation filtering never
// changes channel availability or turns a scheduled occurrence into a video.
internal fun parseTwitchScheduleStart(response: TwitchHelixResponse, broadcasterId: String, nowEpochMs: Long): Long? {
    if (!validTwitchCatalogId(broadcasterId) || nowEpochMs !in 0..MAX_SCHEDULE_EPOCH_MS) invalidMetadata()
    if (response.status == 404) return null
    if (response.status != 200) invalidMetadata()
    val data = scheduleObject(response.fields["data"])
    if (data.id("broadcaster_id") != broadcasterId || !data.containsKey("vacation")) invalidMetadata()
    val vacation = data["vacation"]?.let {
        val bounds = scheduleObject(it)
        val start = scheduleTime(bounds["start_time"])
        val end = scheduleTime(bounds["end_time"])
        if (end <= start) invalidMetadata()
        start to end
    }
    if (response.fields.containsKey("pagination")) {
        val page = scheduleObject(response.fields["pagination"])
        if (page.containsKey("cursor") && (page["cursor"] as? String)?.takeIf(::validTwitchCatalogCursor) == null)
            invalidMetadata()
    }
    val segments = data["segments"] as? List<*> ?: invalidMetadata()
    if (segments.size > 25) invalidMetadata()
    var earliest: Long? = null
    segments.forEach { value ->
        val segment = scheduleObject(value)
        val start = scheduleTime(segment["start_time"])
        val end = scheduleTime(segment["end_time"])
        if (end <= start || !segment.containsKey("canceled_until")) invalidMetadata()
        val canceled = segment["canceled_until"]?.let { scheduleTime(it) }
        val overlapsVacation = vacation != null && start < vacation.second && end > vacation.first
        if (start > nowEpochMs && canceled == null && !overlapsVacation)
            earliest = minOf(earliest ?: start, start)
    }
    return earliest
}

private fun projectSchedule(data: JSONObject): Map<String, Any?> {
    fun text(fields: JSONObject, key: String): String = (fields.opt(key) as? String)
        ?.takeIf { it.length <= 64 } ?: invalidMetadata()
    val segments = data.opt("segments") as? JSONArray ?: invalidMetadata()
    if (segments.length() > 25 || !data.has("vacation")) invalidMetadata()
    val rows = (0 until segments.length()).map { index ->
        val row = segments.opt(index) as? JSONObject ?: invalidMetadata()
        if (!row.has("canceled_until")) invalidMetadata()
        mapOf("start_time" to text(row, "start_time"), "end_time" to text(row, "end_time"),
            "canceled_until" to if (row.isNull("canceled_until")) null else text(row, "canceled_until"))
    }
    val vacation = if (data.isNull("vacation")) null else {
        val bounds = data.opt("vacation") as? JSONObject ?: invalidMetadata()
        mapOf("start_time" to text(bounds, "start_time"), "end_time" to text(bounds, "end_time"))
    }
    return mapOf("broadcaster_id" to text(data, "broadcaster_id"), "segments" to rows, "vacation" to vacation)
}

// Production decoding projects only needed public fields. No error text,
// account email, returned URLs, thumbnails or arbitrary nested objects escape.
internal fun twitchHelixResponseFields(body: String): Map<String, Any?> {
    if (body.length > TWITCH_HELIX_RESPONSE_LIMIT || body.toByteArray(Charsets.UTF_8).size > TWITCH_HELIX_RESPONSE_LIMIT) invalidMetadata()
    try {
        val json = JSONObject(body)
        val projectedData = when (val data = json.opt("data")) {
            is JSONObject -> projectSchedule(data)
            is JSONArray -> {
                if (data.length() > 100) invalidMetadata()
                val allowed = setOf("id", "login", "display_name", "broadcaster_id", "broadcaster_login", "broadcaster_name",
                    "user_id", "user_login", "user_name", "is_live", "title", "type")
                (0 until data.length()).map { index ->
                    val row = data.opt(index) as? JSONObject ?: invalidMetadata()
                    allowed.filter(row::has).associateWith { key -> when (val value = row.opt(key)) {
                        is String -> value.takeIf { it.length <= 2048 } ?: invalidMetadata()
                        is Boolean -> value
                        is Number -> value
                        JSONObject.NULL -> null
                        else -> invalidMetadata()
                    } }
                }
            }
            else -> invalidMetadata()
        }
        val result = mutableMapOf<String, Any?>("data" to projectedData)
        if (json.has("pagination")) {
            val page = json.opt("pagination") as? JSONObject ?: invalidMetadata()
            result["pagination"] = if (page.has("cursor")) mapOf("cursor" to
                ((page.opt("cursor") as? String)?.takeIf(::validTwitchCatalogCursor) ?: invalidMetadata())) else emptyMap<String, Any?>()
        }
        return result
    } catch (error: TwitchHelixException) { throw error }
    catch (_: Exception) { invalidMetadata() }
}
