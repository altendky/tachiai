package net.fstab.tachiai.provider.twitch

import java.io.IOException
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.net.AccessProbeEndpoint
import net.fstab.tachiai.platform.net.AccessProbeHttp
import net.fstab.tachiai.platform.net.boundedHttpsGet
import net.fstab.tachiai.presentation.ScrubPreviewSource
import net.fstab.tachiai.presentation.ScrubSpriteSheets
import org.json.JSONArray
import org.json.JSONObject

// Twitch VOD seek previews (#673). GQL `video(id) { seekPreviewsURL }` names a
// storyboard JSON beside the VOD's storage on a CloudFront distribution. It
// lists sprite sheets per quality: `count` tiles of `width`×`height`, `cols`
// × `rows` per sheet, one tile per `interval` seconds, and sheet file names in
// the same directory. Verified on midnightsumo VODs on 2026-11-24 (anonymous
// GQL; no tokens in either URL).

internal fun twitchSeekPreviewsBody(videoId: String): ByteArray {
    require(validTwitchPlaybackResource(TwitchAccessCase.REPLAY, videoId))
    // A validated numeric ID and fixed query need no JSON escaping.
    return ("{\"operationName\":\"TachiaiSeekPreviews\",\"variables\":{\"id\":\"$videoId\"},\"query\":\"" +
        "query TachiaiSeekPreviews(\$id: ID!) { video(id: \$id) { seekPreviewsURL } }\"}").toByteArray(Charsets.UTF_8)
}

private val storyboardHost = Regex("d[a-z0-9]{8,32}\\.cloudfront\\.net")
private val storyboardPath = Regex("/[A-Za-z0-9_-]{1,160}/storyboards/")

// The storyboard address for exactly this video, or null.
internal fun parseTwitchSeekPreviewsUrl(status: Int, body: String, videoId: String): URI? = runCatching {
    if (status != 200) return null
    val json = JSONObject(body)
    val errors = json.opt("errors")
    if (errors != null && errors != JSONObject.NULL && (errors !is JSONArray || errors.length() != 0)) return null
    val raw = json.getJSONObject("data").getJSONObject("video").opt("seekPreviewsURL") as? String ?: return null
    if (raw.length > 512) return null
    val uri = URI(raw)
    val path = uri.rawPath ?: return null
    if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1 || uri.rawQuery != null ||
        uri.rawFragment != null || uri.host?.let(storyboardHost::matches) != true) return null
    val suffix = "$videoId-info.json"
    if (!path.endsWith(suffix) || !storyboardPath.matches(path.removeSuffix(suffix))) return null
    uri
}.getOrNull()

// Prefers the larger "high" sheets. Sheet names must be this video's files in
// the storyboard's directory; anything unexpected means no previews.
internal fun parseTwitchStoryboard(info: URI, body: String, videoId: String): ScrubSpriteSheets? = runCatching {
    val entries = JSONArray(body)
    if (entries.length() !in 1..8) return null
    val directory = info.toString().substringBeforeLast('/') + "/"
    val names = Regex("${Regex.escape(videoId)}-(high|low)-(0|[1-9][0-9]{0,3})\\.jpg")
    val candidates = (0 until entries.length()).mapNotNull { index ->
        val entry = entries.getJSONObject(index)
        val quality = entry.optString("quality")
        val images = entry.getJSONArray("images")
        val files = (0 until images.length()).map { images.getString(it) }
        val count = entry.getInt("count"); val interval = entry.getInt("interval")
        val cols = entry.getInt("cols"); val rows = entry.getInt("rows")
        val width = entry.getInt("width"); val height = entry.getInt("height")
        if (quality !in setOf("high", "low") || files.size !in 1..1000 || files.any { names.matchEntire(it)?.groupValues?.get(1) != quality } ||
            count !in 1..100_000 || interval !in 1..86_400 || cols !in 1..64 || rows !in 1..64 ||
            width !in 16..1024 || height !in 9..1024) return@mapNotNull null
        quality to ScrubSpriteSheets(interval * 1_000L, cols, rows, width, height, count) { sheet ->
            files.getOrNull(sheet)?.let { directory + it }
        }
    }
    (candidates.firstOrNull { it.first == "high" } ?: candidates.firstOrNull())?.second
}.getOrNull()

internal fun allowedTwitchStoryboardSprite(info: URI, videoId: String, url: String): Boolean {
    val directory = info.toString().substringBeforeLast('/') + "/"
    return url.startsWith(directory) &&
        Regex("${Regex.escape(videoId)}-(high|low)-(0|[1-9][0-9]{0,3})\\.jpg").matches(url.substring(directory.length))
}

// An anonymous metadata request (no account token) and the storyboard and
// sheet GETs all use the feed's route. Sub-only VODs may have no previews.
internal fun twitchScrubPreviewSource(videoId: String, open: (URL) -> HttpsURLConnection): ScrubPreviewSource? {
    if (!validTwitchPlaybackResource(TwitchAccessCase.REPLAY, videoId)) return null
    var info: URI? = null
    return ScrubPreviewSource("twitch:$videoId", resolve = {
        val located = AccessProbeHttp(open = open).use { http ->
            http.exchange(AccessProbeEndpoint.TWITCH_ACCESS, URI("https://gql.twitch.tv/gql"), twitchSeekPreviewsBody(videoId),
                clientId = SMART_TV_TWITCH_CLIENT_ID) { status, body -> parseTwitchSeekPreviewsUrl(status, body, videoId) }
        }
        located?.let { uri ->
            val body = boundedHttpsGet(open, uri, 64 * 1024, "application/json") ?: throw IOException("Storyboard unavailable")
            parseTwitchStoryboard(uri, body.toString(Charsets.UTF_8), videoId)?.also { info = uri }
        }
    }) { url ->
        require(info?.let { allowedTwitchStoryboardSprite(it, videoId, url) } == true)
        boundedHttpsGet(open, URI(url), 2 * 1024 * 1024, "image/*")
    }
}
