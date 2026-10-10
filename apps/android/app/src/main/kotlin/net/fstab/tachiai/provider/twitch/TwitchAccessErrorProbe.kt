package net.fstab.tachiai.provider.twitch

import java.net.URI
import java.util.Locale
import net.fstab.tachiai.platform.net.AccessProbeEndpoint
import net.fstab.tachiai.platform.net.AccessProbeHttp
import org.json.JSONArray
import org.json.JSONObject

internal enum class TwitchErrorShape { NOT_READ, EMPTY, INVALID_JSON, INVALID_FIELDS, NO_ERROR, ERROR_FIELDS }
internal enum class TwitchAccessFields { PRESENT, ABSENT }
internal enum class TwitchErrorCategory {
    UNCLASSIFIED, CLIENT_REJECTION_REPORTED, TOKEN_REJECTION_REPORTED,
    AUTHENTICATION_REQUIRED_REPORTED, PERMISSION_REJECTION_REPORTED,
    INTEGRITY_REJECTION_REPORTED, QUERY_REJECTION_REPORTED, REQUEST_FAILED,
}
// No response text, account identity or session-bearing fields in this result.
internal data class TwitchAccessErrorResult(
    val http: Int, val shape: TwitchErrorShape, val categories: Set<TwitchErrorCategory>,
    val accessFields: TwitchAccessFields? = null,
)
internal fun TwitchAccessErrorResult.safeSummary() =
    "http=$http shape=${shape.name} categories=${categories.joinToString(",") { it.name }}" +
        (accessFields?.let { " accessFields=${it.name}" } ?: "")

internal fun twitchAccessFieldPresence(result: TwitchAccessErrorResult, signature: Any?, value: Any?): TwitchAccessFields =
    if (result.http == 200 && result.shape == TwitchErrorShape.NO_ERROR &&
        listOf(signature, value).all { it is String && it.length in 1..32768 }) TwitchAccessFields.PRESENT
    else TwitchAccessFields.ABSENT

// Exact bounded vocabulary only. These are provider-reported indications, not
// verified causes: in particular HTTP 401 alone never means an invalid token.
internal fun classifyTwitchErrorFields(status: Int, fields: Map<String, Any?>): TwitchAccessErrorResult {
    val messages = mutableListOf<String>()
    fun invalid() = TwitchAccessErrorResult(status, TwitchErrorShape.INVALID_FIELDS, setOf(TwitchErrorCategory.UNCLASSIFIED))
    for (key in listOf("message", "error")) {
        val value = fields[key] ?: continue
        if (value !is String || value.length !in 1..1024) return invalid()
        messages += value
    }
    for (key in listOf("messages", "codes")) {
        val values = fields[key] ?: continue
        if (values !is List<*> || values.size > 16 || values.any { it !is String || it.length !in 1..1024 }) return invalid()
        messages += values.filterIsInstance<String>()
    }
    if (messages.isEmpty()) return TwitchAccessErrorResult(status, TwitchErrorShape.NO_ERROR, setOf(TwitchErrorCategory.UNCLASSIFIED))
    val categories = messages.map { message ->
        when (message.trim().lowercase(Locale.ROOT)) {
            "invalid client", "invalid client id", "invalid client-id",
            "the \"client-id\" header is invalid." -> TwitchErrorCategory.CLIENT_REJECTION_REPORTED
            "invalid oauth token", "invalid authorization token", "invalid access token",
            "the \"authorization\" token is invalid." -> TwitchErrorCategory.TOKEN_REJECTION_REPORTED
            "unauthorized", "unauthenticated", "authentication required" -> TwitchErrorCategory.AUTHENTICATION_REQUIRED_REPORTED
            "forbidden", "permission denied" -> TwitchErrorCategory.PERMISSION_REJECTION_REPORTED
            "failed integrity check", "integrity_check_failed" -> TwitchErrorCategory.INTEGRITY_REJECTION_REPORTED
            "graphql_validation_failed", "graphql_parse_failed" -> TwitchErrorCategory.QUERY_REJECTION_REPORTED
            else -> TwitchErrorCategory.UNCLASSIFIED
        }
    }.toSet()
    return TwitchAccessErrorResult(status, TwitchErrorShape.ERROR_FIELDS, categories)
}

internal fun probeTwitchAccessErrors(
    http: AccessProbeHttp, kind: TwitchAccessCase, resource: String, token: String,
    blankClientHeader: Boolean = true,
    profile: TwitchAuthorizationProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV,
): TwitchAccessErrorResult = http.exchange(
    AccessProbeEndpoint.TWITCH_ACCESS, URI("https://gql.twitch.tv/gql"),
    twitchAccessProbeBody(kind, resource), token, profile.clientId.also {
        require(blankClientHeader)
    },
    inspectTwitchErrors = true,
    blankTwitchClientHeader = blankClientHeader,
) { status, body ->
    if (body.isEmpty()) return@exchange TwitchAccessErrorResult(status,
        if (status in listOf(200, 400, 401, 403)) TwitchErrorShape.EMPTY else TwitchErrorShape.NOT_READ,
        setOf(TwitchErrorCategory.UNCLASSIFIED))
    val json = try { JSONObject(body) } catch (_: Exception) {
        return@exchange TwitchAccessErrorResult(status, TwitchErrorShape.INVALID_JSON, setOf(TwitchErrorCategory.UNCLASSIFIED))
    }
    fun nullable(value: Any?): Any? = if (value == JSONObject.NULL) null else value
    val fields = mutableMapOf<String, Any?>()
    for (key in listOf("message", "error")) fields[key] = nullable(json.opt(key))
    val errors = nullable(json.opt("errors"))
    if (errors != null) {
        // Preserve malformed shapes and never enumerate arbitrary JSON keys.
        if (errors !is JSONArray || errors.length() > 16) fields["messages"] = true
        else {
            val messages = mutableListOf<Any?>()
            val codes = mutableListOf<Any?>()
            for (index in 0 until errors.length()) {
                val error = errors.optJSONObject(index)
                if (error == null) { messages += true; continue }
                messages += nullable(error.opt("message"))
                val extensions = nullable(error.opt("extensions"))
                if (extensions is JSONObject) nullable(extensions.opt("code"))?.let { codes += it }
                else if (extensions != null) codes += true
            }
            fields["messages"] = messages
            fields["codes"] = codes
        }
    }
    val result = classifyTwitchErrorFields(status, fields)
    if (!blankClientHeader) result // Earlier error cases do not inspect access fields.
    else {
        val field = if (kind == TwitchAccessCase.LIVE) "streamPlaybackAccessToken" else "videoPlaybackAccessToken"
        val access = json.optJSONObject("data")?.optJSONObject(field)
        // Values remain parser-local and are discarded; presence is not playback proof.
        result.copy(accessFields = twitchAccessFieldPresence(result,
            nullable(access?.opt("signature")), nullable(access?.opt("value"))))
    }
}
