package net.fstab.tachiai.provider

import java.net.URI
import net.fstab.tachiai.presentation.PaneId
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

// Closed native diagnostic labels, never URLs or provider page text.
enum class BrowserProbeRole { GENERAL, LOGIN, WEB, DESKTOP_WEB, EMBED }

// Diagnostic classifications only, not network permissions or successful loads.
enum class BrowserResourceCategory { CONFIG_SCRIPT, BOOTSTRAP_SCRIPT, AUTH_UI_SCRIPT, PROTECTION_SCRIPT }

data class BrowserRequest(
    val url: String,
    val isPackagedAsset: Boolean = false,
    val acceptsThirdPartyCookies: Boolean = false,
    val browserIdentity: BrowserIdentity = BrowserIdentity.DEFAULT,
    val packagedChildPaths: Set<String> = emptySet(),
    val allowsExternalNavigation: Boolean = true,
    // Explicit opt-in for a user-triggered, unscripted authentication window.
    // These are exact HTTPS hosts, not suffixes or wildcard origins.
    val popupHttpsHosts: Set<String> = emptySet(),
    // Diagnostic classification only: these hosts are still denied navigation.
    val popupDiagnosticAlternateHttpsHosts: Set<String> = emptySet(),
    val suppressScriptDialogsAndConsole: Boolean = false,
    // Debug-only native lifecycle markers; never provider strings or page inspection.
    val logNativePageEvents: Boolean = false,
    val nativeProbeRole: BrowserProbeRole = BrowserProbeRole.GENERAL,
    // Rendering-only debug comparison. Console suppression remains independent.
    val debugUseDefaultScriptDialogs: Boolean = false,
    // Native viewport configuration, independent of provider DOM knowledge.
    val useWideViewport: Boolean = false,
    val loadWithOverviewMode: Boolean = false,
)

data class BrowserCommandScript(
    val send: String,
    val poll: String? = null,
)

interface ProviderAdapter {
    val id: ProviderId
    val displayName: String

    fun browserRequest(resource: ResourceLocator): BrowserRequest

    fun isTopLevelNavigationAllowed(uri: URI): Boolean

    fun isProtectedMediaOriginAllowed(uri: URI): Boolean

    fun isDiagnosticRouteAllowed(uri: URI): Boolean = false

    // Pure, thread-safe classification of public resources; never return URLs.
    fun diagnosticResourceCategory(uri: URI): BrowserResourceCategory? = null

    fun scriptFor(command: BrowserCommand, requestedUri: URI, currentUri: URI): String?

    fun scriptForPane(
        pane: PaneId,
        command: BrowserCommand,
        requestId: Long,
        requestedUri: URI,
        currentUri: URI,
    ): BrowserCommandScript? = null

    fun focusScriptFor(requestedUri: URI, currentUri: URI): String? = null
}

internal fun URI.isExactHttpsOrigin(hostname: String): Boolean =
    scheme.equals("https", ignoreCase = true) &&
        host.equals(hostname, ignoreCase = true) &&
        userInfo == null &&
        (port == -1 || port == 443)
