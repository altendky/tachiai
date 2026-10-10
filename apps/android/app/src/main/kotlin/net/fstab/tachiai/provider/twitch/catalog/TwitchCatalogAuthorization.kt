package net.fstab.tachiai.provider.twitch.catalog

import net.fstab.tachiai.provider.twitch.DeviceAuthResponse
import net.fstab.tachiai.provider.twitch.SMART_TV_TWITCH_CLIENT_ID

internal const val TWITCH_CATALOG_SCOPE = "user:read:follows"
internal const val TWITCH_CATALOG_TOKEN_LIMIT = 2048
internal const val TWITCH_CATALOG_LIFETIME_LIMIT_MS = Int.MAX_VALUE * 1000L

internal enum class TwitchCatalogAuthFailure {
    INVALID_RESPONSE, REJECTED, CLIENT_MISMATCH, USER_MISMATCH, SCOPE_MISMATCH, EXPIRED, NETWORK,
}

// No response text, cause, tokens or account values enter errors or diagnostics.
internal class TwitchCatalogAuthException(val failure: TwitchCatalogAuthFailure) : Exception(failure.name)

internal fun validCatalogAccessToken(value: String) =
    value.length in 1..TWITCH_CATALOG_TOKEN_LIMIT && Regex("[A-Za-z0-9._~+/=-]+").matches(value)

internal fun validCatalogRefreshToken(value: String) =
    value.length in 1..TWITCH_CATALOG_TOKEN_LIMIT && value.all { it.code in 33..126 }

internal fun validCatalogUserId(value: String) = Regex("[A-Za-z0-9_-]{1,128}").matches(value)
internal fun validCatalogLogin(value: String) = Regex("[a-z0-9_]{1,64}").matches(value)

internal class TwitchCatalogCredentials(val accessToken: String, val refreshToken: String, val expiresInMs: Long) {
    init {
        require(validCatalogAccessToken(accessToken) && validCatalogRefreshToken(refreshToken))
        require(expiresInMs in 1..TWITCH_CATALOG_LIFETIME_LIMIT_MS)
    }
    override fun toString() = "TwitchCatalogCredentials(redacted)"
}

internal class TwitchCatalogValidation(val userId: String, scopes: Set<String>, val expiresInMs: Long, val login: String? = null) {
    val scopes: Set<String> = scopes.toSet()
    init {
        require(validCatalogUserId(userId) && this.scopes == setOf(TWITCH_CATALOG_SCOPE))
        require(expiresInMs in 1..TWITCH_CATALOG_LIFETIME_LIMIT_MS && (login == null || validCatalogLogin(login)))
    }
    override fun toString() = "TwitchCatalogValidation(redacted)"
}

private fun fail(failure: TwitchCatalogAuthFailure = TwitchCatalogAuthFailure.INVALID_RESPONSE): Nothing =
    throw TwitchCatalogAuthException(failure)

private fun accepted(response: DeviceAuthResponse): Map<String, Any?> {
    if (response.status != 200) fail(TwitchCatalogAuthFailure.REJECTED)
    if (response.fields["error"] != null || response.fields["message"] != null) fail()
    return response.fields
}

private fun Map<String, Any?>.string(key: String, max: Int): String =
    (this[key] as? String)?.takeIf { it.length in 1..max } ?: fail()

private fun Map<String, Any?>.lifetime(): Long {
    val seconds = when (val value = this["expires_in"]) {
        is Int -> value.toLong()
        is Long -> value
        else -> fail()
    }
    if (seconds == 0L) fail(TwitchCatalogAuthFailure.EXPIRED)
    if (seconds !in 1..Int.MAX_VALUE.toLong()) fail()
    return seconds * 1000
}

private fun Map<String, Any?>.scopes(key: String): Set<String> {
    val value = this[key] as? List<*> ?: fail()
    if (value.any { it !is String }) fail()
    if (value.size != 1 || value.single() != TWITCH_CATALOG_SCOPE) fail(TwitchCatalogAuthFailure.SCOPE_MISMATCH)
    return setOf(TWITCH_CATALOG_SCOPE)
}

internal fun parseTwitchCatalogToken(response: DeviceAuthResponse): TwitchCatalogCredentials {
    val fields = accepted(response)
    if (!(fields["token_type"] as? String).equals("bearer", ignoreCase = true)) fail()
    fields.scopes("scope")
    val access = fields.string("access_token", TWITCH_CATALOG_TOKEN_LIMIT)
    val refresh = fields.string("refresh_token", TWITCH_CATALOG_TOKEN_LIMIT)
    if (!validCatalogAccessToken(access) || !validCatalogRefreshToken(refresh)) fail()
    return TwitchCatalogCredentials(access, refresh, fields.lifetime())
}

internal fun parseTwitchCatalogValidation(response: DeviceAuthResponse, expectedUserId: String? = null): TwitchCatalogValidation {
    val fields = accepted(response)
    if (fields.string("client_id", 64) != SMART_TV_TWITCH_CLIENT_ID) fail(TwitchCatalogAuthFailure.CLIENT_MISMATCH)
    val user = fields.string("user_id", 128)
    if (!validCatalogUserId(user)) fail()
    if (expectedUserId != null && user != expectedUserId) fail(TwitchCatalogAuthFailure.USER_MISMATCH)
    val scopes = fields.scopes("scopes")
    val login = fields["login"]?.let { value ->
        (value as? String)?.takeIf(::validCatalogLogin) ?: fail()
    }
    return TwitchCatalogValidation(user, scopes, fields.lifetime(), login)
}
