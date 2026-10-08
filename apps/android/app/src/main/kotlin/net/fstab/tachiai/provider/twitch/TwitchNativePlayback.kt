package net.fstab.tachiai.provider.twitch

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import net.fstab.tachiai.platform.net.AccessProbeEndpoint
import net.fstab.tachiai.platform.net.AccessProbeHttp
import org.json.JSONArray
import org.json.JSONObject

// Never a data class: generated toString/copy would expose signed authorization.
internal class TwitchPlaybackSource(val uri: URI) {
    override fun toString() = "TwitchPlaybackSource(redacted)"
}

internal fun validTwitchPlaybackResource(kind: TwitchAccessCase, resource: String): Boolean =
    if (kind == TwitchAccessCase.LIVE) Regex("[A-Za-z0-9_]{3,25}").matches(resource)
    else Regex("[0-9]{1,20}").matches(resource)

internal fun twitchPlaybackSource(kind: TwitchAccessCase, resource: String,
    signature: Any?, value: Any?): TwitchPlaybackSource {
    require(validTwitchPlaybackResource(kind, resource))
    require(signature is String && Regex("[A-Za-z0-9_-]{1,256}").matches(signature))
    require(value is String && value.length in 1..12_000 && value.none { it.code < 32 })
    fun encoded(text: String) = URLEncoder.encode(text, "UTF-8")
    val path = if (kind == TwitchAccessCase.LIVE) "/api/v2/channel/hls/$resource.m3u8"
        else "/vod/v2/$resource.m3u8"
    // Independently implemented from observed protocol, not a public playback API.
    // No ad suppression, integrity identity, proxy, or low-latency special flags.
    return TwitchPlaybackSource(URI("https://usher.ttvnw.net$path?sig=${encoded(signature)}" +
        "&token=${encoded(value)}&platform=web&allow_source=true&allow_audio_only=true"))
}

internal fun resolveTwitchPlayback(http: AccessProbeHttp, kind: TwitchAccessCase,
    resource: String, token: String, onHttpStatus: (Int) -> Unit = {}): TwitchPlaybackSource = http.exchange(
    AccessProbeEndpoint.TWITCH_ACCESS, URI("https://gql.twitch.tv/gql"),
    twitchAccessProbeBody(kind, resource), token, TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL.clientId,
    inspectTwitchErrors = true, blankTwitchClientHeader = true,
) { status, body ->
    onHttpStatus(status)
    try {
        if (status != 200) throw IOException()
        val json = JSONObject(body)
        for (key in listOf("message", "error"))
            if (json.has(key) && !json.isNull(key)) throw IOException()
        val errors = json.opt("errors")
        if (errors != null && errors != JSONObject.NULL &&
            (errors !is JSONArray || errors.length() != 0)) throw IOException()
        val field = if (kind == TwitchAccessCase.LIVE) "streamPlaybackAccessToken" else "videoPlaybackAccessToken"
        val access = json.getJSONObject("data").getJSONObject(field)
        twitchPlaybackSource(kind, resource, access.opt("signature"), access.opt("value"))
    } catch (_: Exception) { throw IOException("Playback access rejected") }
}

// Observed in the accepted replay master on Pixel 6, 2026-10-06. This one
// distribution is an explicit comparison mode, not a CloudFront-family rule.
internal const val OBSERVED_TWITCH_REPLAY_CDN = "dgeft87wbj63p.cloudfront.net"
internal fun allowedTwitchMediaUri(uri: URI, observedReplayCdn: Boolean = false): Boolean {
    if (uri.scheme != "https" || uri.rawUserInfo != null || uri.rawFragment != null ||
        uri.port !in listOf(-1, 443)) return false
    val host = uri.host ?: return false
    // Experimental provider-owned families; no broad multi-tenant CloudFront rule.
    return listOf("ttvnw.net", "twitchcdn.net").any { host == it || host.endsWith(".$it") } ||
        (observedReplayCdn && host == OBSERVED_TWITCH_REPLAY_CDN)
}

// Only a recognizable public CloudFront distribution hostname, never a URL,
// arbitrary hostname, account identifier, signed query, path or fragment.
internal fun twitchPublicCdnDiagnosticHost(uri: URI): String? {
    if (uri.scheme != "https" || uri.rawUserInfo != null || uri.rawFragment != null ||
        uri.port !in listOf(-1, 443)) return null
    return uri.host?.takeIf { Regex("d[a-z0-9]{8,32}\\.cloudfront\\.net").matches(it) }
}

internal enum class TwitchManifestRejection { TRANSCODE_MISSING_REPORTED, UNCLASSIFIED }
internal fun classifyTwitchManifestRejection(status: Int, errorCode: Any?): TwitchManifestRejection =
    if (status == 404 && errorCode == "transcode_does_not_exist") TwitchManifestRejection.TRANSCODE_MISSING_REPORTED
    else TwitchManifestRejection.UNCLASSIFIED

internal fun parseTwitchManifestRejection(status: Int, body: String): TwitchManifestRejection {
    if (status != 404 || body.length !in 1..4096) return TwitchManifestRejection.UNCLASSIFIED
    return try {
        val entry = if (body.trimStart().startsWith("[")) {
            val array = JSONArray(body)
            if (array.length() != 1) return TwitchManifestRejection.UNCLASSIFIED
            array.getJSONObject(0)
        } else JSONObject(body)
        classifyTwitchManifestRejection(status, entry.opt("error_code"))
    } catch (_: Exception) { TwitchManifestRejection.UNCLASSIFIED }
}
