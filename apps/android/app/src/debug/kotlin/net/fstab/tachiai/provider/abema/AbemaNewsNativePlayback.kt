package net.fstab.tachiai.provider.abema

import java.io.IOException
import java.net.URI
import java.util.UUID
import net.fstab.tachiai.platform.net.AccessProbeEndpoint
import net.fstab.tachiai.platform.net.AccessProbeHttp
import org.json.JSONObject

// Transient source/standard initialization, never a loggable data class.
internal interface AbemaNativeDashSource { val uri: URI; val kids: List<UUID> }
internal class AbemaNewsNativeSource(override val uri: URI, override val kids: List<UUID>) : AbemaNativeDashSource

internal enum class AbemaNewsMediaUriPolicy { ALLOWED, AUTHORITY, USER_INFO, FRAGMENT, QUERY, CHANNEL_ROOT, PATH, UNDECLARED }

// Closed diagnostic reason only: no host/path/query values leave this adapter.
internal fun abemaNewsCdnUriPolicy(uri: URI, approvedPrototype: Boolean = false): AbemaNewsMediaUriPolicy = when {
    uri.scheme != "https" || !(if (approvedPrototype) AbemaCdnApprovalPolicy.approvedHost(uri.host)
        else uri.host == "linear-abematv.akamaized.net") ||
        uri.port !in listOf(-1, 443) -> AbemaNewsMediaUriPolicy.AUTHORITY
    uri.rawUserInfo != null -> AbemaNewsMediaUriPolicy.USER_INFO
    uri.rawFragment != null -> AbemaNewsMediaUriPolicy.FRAGMENT
    uri.rawQuery != null -> AbemaNewsMediaUriPolicy.QUERY
    uri.rawPath == null || !Regex("[A-Za-z0-9_./~$-]+").matches(uri.rawPath) ||
        uri.rawPath.split('/').any { it == "." || it == ".." } ||
        uri.normalize().rawPath != uri.rawPath -> AbemaNewsMediaUriPolicy.PATH
    else -> AbemaNewsMediaUriPolicy.ALLOWED
}

// Preserve the original guessed-root predicate for the metadata URI and its
// comparison fixtures. Native media instead needs exact MPD-declared files.
internal fun abemaNewsMediaUriPolicy(uri: URI, approvedPrototype: Boolean = false): AbemaNewsMediaUriPolicy {
    val common = abemaNewsCdnUriPolicy(uri, approvedPrototype)
    return if (common in listOf(AbemaNewsMediaUriPolicy.ALLOWED, AbemaNewsMediaUriPolicy.PATH) &&
        uri.rawPath?.startsWith("/channel/abema-news/") != true) AbemaNewsMediaUriPolicy.CHANNEL_ROOT else common
}

internal fun allowedAbemaNewsMediaUri(uri: URI): Boolean =
    abemaNewsMediaUriPolicy(uri) == AbemaNewsMediaUriPolicy.ALLOWED

internal fun parseAbemaNewsNativeUri(body: String): URI? = try {
    val channels = JSONObject(body).getJSONArray("channels")
    var result: URI? = null
    var matched = false
    for (index in 0 until channels.length()) {
        val channel = channels.optJSONObject(index) ?: continue
        if (channel.optString("id") != "abema-news") continue
        if (matched) throw IOException("Ambiguous News source")
        matched = true
        val candidate = URI(channel.getJSONObject("playback").getString("dash"))
        if (!allowedAbemaNewsMediaUri(candidate) || !candidate.path.endsWith(".mpd")) throw IOException("News source refused")
        result = candidate
    }
    result
} catch (_: Exception) { null }

// Separate from the older metadata/request-only probes; no URL synthesis or
// guest authorization. Media3 later requests this exact advertised MPD.
internal fun resolveAbemaNewsNativeSource(http: AccessProbeHttp): AbemaNewsNativeSource {
    val uri = http.exchange(AccessProbeEndpoint.ABEMA_CHANNELS, URI("https://api.abema.io/v1/channels")) { status, body ->
        if (status == 200) parseAbemaNewsNativeUri(body) else null
    } ?: throw IOException("News metadata unavailable")
    val initialization = http.exchange(AccessProbeEndpoint.ABEMA_DASH, uri) { status, body ->
        if (status == 200) parseAbemaRequestInitialization(body) else null
    } ?: throw IOException("News initialization unavailable")
    return AbemaNewsNativeSource(uri, initialization.kids)
}

// Audio-isolation control, not a DRM/player replacement or ad suppression.
internal fun abemaNewsMuteOriginalVideoScript(): String = """
    (() => {
      if (window !== window.top || location.href !== 'https://abema.tv/now-on-air/abema-news') return;
      const media = Array.from(document.querySelectorAll('video'))
        .find(candidate => candidate.offsetWidth > 0 && candidate.offsetHeight > 0);
      if (media) media.muted = true;
    })()
""".trimIndent()

internal fun abemaNewsPauseOriginalVideoScript(): String = """
    (() => {
      if (window !== window.top || location.href !== 'https://abema.tv/now-on-air/abema-news') return;
      const media = Array.from(document.querySelectorAll('video'))
        .find(candidate => candidate.offsetWidth > 0 && candidate.offsetHeight > 0);
      if (!media) return false;
      media.muted = true;
      media.pause();
      return media.paused && media.muted;
    })()
""".trimIndent()
