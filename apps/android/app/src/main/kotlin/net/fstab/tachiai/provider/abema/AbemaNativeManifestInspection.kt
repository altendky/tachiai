package net.fstab.tachiai.provider.abema

import java.net.URI
import net.fstab.tachiai.platform.net.AccessProbeEndpoint
import net.fstab.tachiai.platform.net.AccessProbeHttp
import net.fstab.tachiai.platform.net.AccessProbeOutcome
import net.fstab.tachiai.platform.net.AccessProbeResult
import net.fstab.tachiai.platform.net.allowedAccessProbeUri
import org.json.JSONObject

// Lexical hints only, not XML validation, a DRM configuration or a license request.
internal fun abemaDashFormatClassification(status: Int, body: String): AccessProbeOutcome {
    val original = abemaDashClassification(status, body)
    if (original != AccessProbeOutcome.DASH_PROTECTION_MARKERS_PRESENT) return original
    // Public scheme identifiers, not pssh/default_KID/license data. Unknown values
    // never leave this classifier. A recognized marker does not configure MediaDrm.
    val schemes = Regex("<(?:[A-Za-z0-9_]+:)?ContentProtection\\b[^>]*\\bschemeIdUri\\s*=\\s*['\"]([^'\"]*)['\"]", RegexOption.IGNORE_CASE)
        .findAll(body).map { it.groupValues[1].lowercase(java.util.Locale.ROOT) }.toSet()
    val recognized = schemes.mapNotNull { scheme -> when (scheme) {
        "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" -> AccessProbeOutcome.DASH_WIDEVINE_MARKERS_PRESENT
        "urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95" -> AccessProbeOutcome.DASH_PLAYREADY_MARKERS_PRESENT
        "urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e" -> AccessProbeOutcome.DASH_CLEARKEY_MARKERS_PRESENT
        "urn:uuid:5e629af5-38da-4063-8977-97ffbd9902d4" -> AccessProbeOutcome.DASH_MARLIN_MARKERS_PRESENT
        else -> null
    } }.toSet()
    return when {
        recognized.size > 1 -> AccessProbeOutcome.DASH_MULTIPLE_DRM_MARKERS_PRESENT
        recognized.size == 1 -> recognized.first()
        "urn:mpeg:dash:mp4protection:2011" in schemes -> AccessProbeOutcome.DASH_COMMON_ENCRYPTION_MARKERS_PRESENT
        else -> original
    }
}

private fun hlsLines(body: String) = body.removePrefix("\uFEFF").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

internal fun abemaHlsClassification(status: Int, body: String): AccessProbeOutcome {
    if (status != 200) return AccessProbeOutcome.HTTP_REJECTED
    val lines = hlsLines(body)
    if (lines.firstOrNull() != "#EXTM3U") return AccessProbeOutcome.INVALID_RESPONSE
    val keys = lines.filter { it.startsWith("#EXT-X-KEY:") || it.startsWith("#EXT-X-SESSION-KEY:") }
    if (keys.any { "abematv-license://" in it }) return AccessProbeOutcome.HLS_ABEMA_KEY_MARKERS_PRESENT
    if (keys.any { it != "#EXT-X-KEY:METHOD=NONE" }) return AccessProbeOutcome.HLS_PROTECTION_MARKERS_PRESENT
    return when {
        lines.any { it.startsWith("#EXT-X-STREAM-INF:") } -> AccessProbeOutcome.HLS_MASTER_MARKERS_PRESENT
        lines.any { it.startsWith("#EXTINF:") } -> AccessProbeOutcome.HLS_MEDIA_MARKERS_PRESENT
        else -> AccessProbeOutcome.INVALID_RESPONSE
    }
}

// Only the first regular variant is considered. No alternate-host fallback,
// audio rendition, key/license, segment or recursive playlist traversal.
internal fun abemaFirstHlsVariant(body: String, master: URI): URI? {
    if (abemaHlsClassification(200, body) != AccessProbeOutcome.HLS_MASTER_MARKERS_PRESENT) return null
    val lines = hlsLines(body)
    val index = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
    val next = lines.getOrNull(index + 1) ?: return null
    if (next.startsWith('#')) return null
    return try { master.resolve(URI(next)).takeIf { allowedAccessProbeUri(AccessProbeEndpoint.ABEMA_HLS, it) } }
    catch (_: Exception) { null }
}

internal fun probeAbemaNativeHlsAccess(http: AccessProbeHttp, inspectVariant: Boolean = false): AccessProbeResult {
    var failure: AccessProbeResult? = null
    val advertised = http.exchange(AccessProbeEndpoint.ABEMA_CHANNELS, URI("https://api.abema.io/v1/channels")) { status, body ->
        if (status != 200) {
            failure = AccessProbeResult(AccessProbeEndpoint.ABEMA_CHANNELS, AccessProbeOutcome.HTTP_REJECTED, status)
            null
        } else try {
            val channels = JSONObject(body).getJSONArray("channels")
            var uri: URI? = null
            var found = false
            for (index in 0 until channels.length()) {
                val channel = channels.optJSONObject(index) ?: continue
                if (channel.optString("id") == "abema-news") {
                    found = true
                    uri = URI(channel.getJSONObject("playback").getString("hls"))
                    break
                }
            }
            if (!found) failure = AccessProbeResult(AccessProbeEndpoint.ABEMA_CHANNELS, AccessProbeOutcome.CHANNEL_MISSING, status)
            else if (uri == null || !allowedAccessProbeUri(AccessProbeEndpoint.ABEMA_HLS, uri)) {
                failure = AccessProbeResult(AccessProbeEndpoint.ABEMA_CHANNELS, AccessProbeOutcome.SOURCE_NOT_ALLOWLISTED, status)
                uri = null
            }
            uri
        } catch (_: Exception) {
            failure = AccessProbeResult(AccessProbeEndpoint.ABEMA_CHANNELS, AccessProbeOutcome.INVALID_RESPONSE, status)
            null
        }
    }
    if (advertised == null) return failure ?: AccessProbeResult(AccessProbeEndpoint.ABEMA_CHANNELS, AccessProbeOutcome.INVALID_RESPONSE)
    return probeAbemaAdvertisedHlsManifest(http, advertised, inspectVariant)
}

internal fun probeAbemaAdvertisedHlsManifest(http: AccessProbeHttp, advertised: URI, inspectVariant: Boolean): AccessProbeResult {
    require(allowedAccessProbeUri(AccessProbeEndpoint.ABEMA_HLS, advertised))
    var variant: URI? = null
    val result = http.exchange(AccessProbeEndpoint.ABEMA_HLS, advertised) { status, body ->
        val outcome = abemaHlsClassification(status, body)
        if (inspectVariant && outcome == AccessProbeOutcome.HLS_MASTER_MARKERS_PRESENT) variant = abemaFirstHlsVariant(body, advertised)
        AccessProbeResult(AccessProbeEndpoint.ABEMA_HLS, outcome, status)
    }
    if (!inspectVariant || result.outcome != AccessProbeOutcome.HLS_MASTER_MARKERS_PRESENT) return result
    val source = variant ?: return AccessProbeResult(AccessProbeEndpoint.ABEMA_HLS, AccessProbeOutcome.SOURCE_NOT_ALLOWLISTED)
    return http.exchange(AccessProbeEndpoint.ABEMA_HLS, source) { status, body ->
        AccessProbeResult(AccessProbeEndpoint.ABEMA_HLS, abemaHlsClassification(status, body), status)
    }
}
