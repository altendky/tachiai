package net.fstab.tachiai.provider

import java.net.URI
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.ResourceLocator

enum class BrowserCommand {
    PLAY,
    PAUSE,
    MUTE,
    UNMUTE,
    VOLUME_DOWN,
    VOLUME_UP,
}

enum class BrowserIdentity {
    DEFAULT,
    MOBILE_CHROME,
    DESKTOP_CHROME,
}

data class BrowserRequest(
    val url: String,
    val isPackagedAsset: Boolean = false,
    val acceptsThirdPartyCookies: Boolean = false,
    val browserIdentity: BrowserIdentity = BrowserIdentity.DEFAULT,
)

interface ProviderAdapter {
    val id: ProviderId
    val displayName: String

    fun browserRequest(resource: ResourceLocator): BrowserRequest

    fun isTopLevelNavigationAllowed(uri: URI): Boolean

    fun isProtectedMediaOriginAllowed(uri: URI): Boolean

    fun isDiagnosticRouteAllowed(uri: URI): Boolean = false

    fun scriptFor(command: BrowserCommand, requestedUri: URI, currentUri: URI): String?

    fun focusScriptFor(requestedUri: URI, currentUri: URI): String? = null
}

internal fun URI.isExactHttpsOrigin(hostname: String): Boolean =
    scheme.equals("https", ignoreCase = true) &&
        host.equals(hostname, ignoreCase = true) &&
        userInfo == null &&
        (port == -1 || port == 443)
