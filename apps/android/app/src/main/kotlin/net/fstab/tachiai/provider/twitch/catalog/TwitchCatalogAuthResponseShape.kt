package net.fstab.tachiai.provider.twitch.catalog

import net.fstab.tachiai.provider.twitch.DeviceAuthEndpoint
import net.fstab.tachiai.provider.twitch.DeviceLifetimeShape
import net.fstab.tachiai.provider.twitch.deviceLifetimeShape

internal enum class TwitchCatalogScopeShape { EXACT, EMPTY, OTHER, INVALID }
internal enum class TwitchCatalogRefreshShape { PRESENT, OMITTED, INVALID, NOT_APPLICABLE }

// Closed shapes only: no response values, credentials, account identifiers,
// activation codes or arbitrary provider scope names enter this result.
internal data class TwitchCatalogAuthResponseShape(
    val endpoint: DeviceAuthEndpoint,
    val lifetime: DeviceLifetimeShape,
    val scopes: TwitchCatalogScopeShape,
    val refresh: TwitchCatalogRefreshShape,
)

internal fun twitchCatalogAuthResponseShape(endpoint: DeviceAuthEndpoint,
    fields: Map<String, Any?>): TwitchCatalogAuthResponseShape {
    require(endpoint == DeviceAuthEndpoint.TOKEN || endpoint == DeviceAuthEndpoint.VALIDATE)
    val scope = fields[if (endpoint == DeviceAuthEndpoint.TOKEN) "scope" else "scopes"]
    val scopes = when {
        scope !is List<*> || scope.any { it !is String } -> TwitchCatalogScopeShape.INVALID
        scope.isEmpty() -> TwitchCatalogScopeShape.EMPTY
        scope.size == 1 && scope.single() == TWITCH_CATALOG_SCOPE -> TwitchCatalogScopeShape.EXACT
        else -> TwitchCatalogScopeShape.OTHER
    }
    val refresh = when {
        endpoint != DeviceAuthEndpoint.TOKEN -> TwitchCatalogRefreshShape.NOT_APPLICABLE
        !fields.containsKey("refresh_token") -> TwitchCatalogRefreshShape.OMITTED
        (fields["refresh_token"] as? String)?.let(::validCatalogRefreshToken) == true -> TwitchCatalogRefreshShape.PRESENT
        else -> TwitchCatalogRefreshShape.INVALID
    }
    return TwitchCatalogAuthResponseShape(endpoint, deviceLifetimeShape(fields), scopes, refresh)
}
