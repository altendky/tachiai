package net.fstab.tachiai.provider.twitch

import java.net.URI
import net.fstab.tachiai.platform.net.AccessProbeEndpoint
import net.fstab.tachiai.platform.net.AccessProbeHttp
import net.fstab.tachiai.platform.net.AccessProbeOutcome
import net.fstab.tachiai.platform.net.AccessProbeResult
import org.json.JSONObject
import org.json.JSONArray

internal enum class TwitchAccessCase { LIVE, REPLAY }

// Public-source protocol observation, not a supported Helix playback API.
// Use only Tachiai's identity; no borrowed TV client, integrity spoof or proxy.
internal fun twitchAccessProbeBody(kind: TwitchAccessCase, resource: String): ByteArray {
    require(if (kind == TwitchAccessCase.LIVE) Regex("[A-Za-z0-9_]{3,25}").matches(resource)
        else Regex("[0-9]{1,20}").matches(resource))
    val operation = if (kind == TwitchAccessCase.LIVE) "StreamPlaybackAccessToken" else "VideoPlaybackAccessToken"
    val field = if (kind == TwitchAccessCase.LIVE) "streamPlaybackAccessToken" else "videoPlaybackAccessToken"
    val variable = if (kind == TwitchAccessCase.LIVE) "login" else "id"
    val argument = if (kind == TwitchAccessCase.LIVE) "channelName" else "id"
    val resourceType = if (kind == TwitchAccessCase.LIVE) "String" else "ID"
    val query = "query $operation(\$$variable: $resourceType!, \$platform: String!, \$playerType: String!) { " +
        "$field($argument: \$$variable, params: { platform: \$platform, playerType: \$playerType }) { signature value } }"
    // Validated ASCII resources and fixed protocol strings need no JSON escaping
    // except the fixed query's dollar signs (which are ordinary JSON characters).
    return """{"operationName":"$operation","variables":{"$variable":"$resource","platform":"web","playerType":"site"},"query":"$query"}""".toByteArray()
}

internal fun twitchAccessClassification(status: Int, errors: List<String>, fieldsPresent: Boolean): AccessProbeOutcome = when {
    errors.any { it.contains("invalid client", ignoreCase = true) } -> AccessProbeOutcome.CLIENT_REJECTED
    status != 200 -> AccessProbeOutcome.HTTP_REJECTED
    errors.isNotEmpty() -> AccessProbeOutcome.PROVIDER_ERROR
    fieldsPresent -> AccessProbeOutcome.AUTHORIZATION_FIELDS_PRESENT
    else -> AccessProbeOutcome.INVALID_RESPONSE
}

internal fun classifyTwitchAccessFields(status: Int, fields: Map<String, Any?>): AccessProbeOutcome {
    val errors = mutableListOf<String>()
    for (key in listOf("message", "error")) {
        val value = fields[key] ?: continue
        if (value !is String) return AccessProbeOutcome.INVALID_RESPONSE
        errors += value
    }
    fields["errors"]?.let { value ->
        if (value !is List<*> || value.any { it !is String }) return AccessProbeOutcome.INVALID_RESPONSE
        errors += value.filterIsInstance<String>()
    }
    val present = listOf("signature", "value").all { key ->
        val value = fields[key]
        value is String && value.length in 1..32768
    }
    return twitchAccessClassification(status, errors, present)
}

internal fun probeTwitchNativeAccess(
    http: AccessProbeHttp, kind: TwitchAccessCase, resource: String, token: String? = null,
): AccessProbeResult = http.exchange(AccessProbeEndpoint.TWITCH_ACCESS, URI("https://gql.twitch.tv/gql"),
    twitchAccessProbeBody(kind, resource), token, TACHIAI_TWITCH_CLIENT_ID) { status, body ->
    val outcome = try {
        val json = JSONObject(body)
        fun nullable(value: Any?): Any? = if (value == JSONObject.NULL) null else value
        val fields = mutableMapOf<String, Any?>()
        for (key in listOf("message", "error")) fields[key] = nullable(json.opt(key))
        val array = nullable(json.opt("errors"))
        fields["errors"] = if (array is JSONArray) (0 until array.length()).map { index ->
            nullable(array.optJSONObject(index)?.opt("message"))
        } else array // Preserve malformed non-null shapes for fail-closed checks.
        val field = if (kind == TwitchAccessCase.LIVE) "streamPlaybackAccessToken" else "videoPlaybackAccessToken"
        val access = json.optJSONObject("data")?.optJSONObject(field)
        for (key in listOf("signature", "value")) fields[key] = nullable(access?.opt(key))
        // Do not retain or return these session-bearing fields, even on success.
        classifyTwitchAccessFields(status, fields)
    } catch (_: Exception) {
        if (status == 200) AccessProbeOutcome.INVALID_RESPONSE else AccessProbeOutcome.HTTP_REJECTED
    }
    AccessProbeResult(AccessProbeEndpoint.TWITCH_ACCESS, outcome, status)
}
