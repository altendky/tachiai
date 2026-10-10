package net.fstab.tachiai.provider.twitch

import net.fstab.tachiai.platform.net.AccessProbeOutcome

internal enum class TwitchAccessCase { LIVE, REPLAY }

// Public-source protocol observation, not a supported Helix playback API.
// Historical access-only decoders remain available to synthetic tests.
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
