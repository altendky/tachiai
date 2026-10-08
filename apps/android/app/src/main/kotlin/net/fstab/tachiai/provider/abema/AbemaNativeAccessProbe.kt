package net.fstab.tachiai.provider.abema

import java.net.URI
import net.fstab.tachiai.platform.net.AccessProbeEndpoint
import net.fstab.tachiai.platform.net.AccessProbeHttp
import net.fstab.tachiai.platform.net.AccessProbeOutcome
import net.fstab.tachiai.platform.net.AccessProbeResult
import net.fstab.tachiai.platform.net.allowedAccessProbeUri
import org.json.JSONObject

internal fun abemaDashClassification(status: Int, body: String): AccessProbeOutcome = when {
    status != 200 -> AccessProbeOutcome.HTTP_REJECTED
    !Regex("<(?:[A-Za-z0-9_]+:)?MPD(?:\\s|>)").containsMatchIn(body) -> AccessProbeOutcome.INVALID_RESPONSE
    Regex("<(?:[A-Za-z0-9_]+:)?ContentProtection(?:\\s|>)").containsMatchIn(body) -> AccessProbeOutcome.DASH_PROTECTION_MARKERS_PRESENT
    else -> AccessProbeOutcome.DASH_MARKERS_PRESENT
}

// Anonymous metadata and exactly one advertised MPD only. No user/application
// secret, guest-token minting, synthesized manifest, segments, keys or licenses.
internal fun probeAbemaNativeAccess(
    http: AccessProbeHttp,
    classify: (Int, String) -> AccessProbeOutcome = ::abemaDashClassification,
): AccessProbeResult {
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
                    uri = URI(channel.getJSONObject("playback").getString("dash"))
                    break
                }
            }
            if (!found) failure = AccessProbeResult(AccessProbeEndpoint.ABEMA_CHANNELS, AccessProbeOutcome.CHANNEL_MISSING, status)
            else if (uri == null || !allowedAccessProbeUri(AccessProbeEndpoint.ABEMA_DASH, uri)) {
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
    return http.exchange(AccessProbeEndpoint.ABEMA_DASH, advertised) { status, body ->
        AccessProbeResult(AccessProbeEndpoint.ABEMA_DASH, classify(status, body), status)
    }
}
