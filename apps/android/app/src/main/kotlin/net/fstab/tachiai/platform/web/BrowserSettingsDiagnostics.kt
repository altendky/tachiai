package net.fstab.tachiai.platform.web

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import net.fstab.tachiai.BuildConfig

internal const val SETTINGS_LOG_TAG = "TachiaiSettings"

internal enum class BrowserSettingsRole { MAIN, POPUP }

// Native configuration only: never accept page data, URLs, UA strings or cookies.
internal data class BrowserSettingsSnapshot(
    val role: BrowserSettingsRole,
    val defaultIdentity: Boolean,
    val identityMatchesParent: Boolean?,
    val javaScript: Boolean,
    val domStorage: Boolean,
    val firstPartyCookies: Boolean,
    val thirdPartyCookies: Boolean,
    val wideViewport: Boolean,
    val overviewMode: Boolean,
    val multipleWindows: Boolean,
    val automaticWindows: Boolean,
    val mixedContentBlocked: Boolean,
) {
    fun fixedMessage(): String = "role=${role.name} defaultIdentity=$defaultIdentity" +
        (identityMatchesParent?.let { " identityMatchesParent=$it" } ?: "") +
        " javaScript=$javaScript domStorage=$domStorage" +
        " firstPartyCookies=$firstPartyCookies thirdPartyCookies=$thirdPartyCookies" +
        " wideViewport=$wideViewport overviewMode=$overviewMode" +
        " multipleWindows=$multipleWindows automaticWindows=$automaticWindows" +
        " mixedContentBlocked=$mixedContentBlocked"
}

internal fun logBrowserSettings(
    snapshot: BrowserSettingsSnapshot,
    enabled: Boolean,
    debug: Boolean = BuildConfig.DEBUG,
    sink: (String) -> Unit = { Log.d(SETTINGS_LOG_TAG, it) },
) {
    if (enabled && debug) sink(snapshot.fixedMessage())
}

internal fun WebView.recordNativeSettings(
    role: BrowserSettingsRole,
    enabled: Boolean,
    parent: WebView? = null,
) {
    if (!enabled || !BuildConfig.DEBUG) return
    val cookies = CookieManager.getInstance()
    logBrowserSettings(BrowserSettingsSnapshot(
        role = role,
        defaultIdentity = settings.userAgentString == WebSettings.getDefaultUserAgent(context),
        identityMatchesParent = parent?.let { settings.userAgentString == it.settings.userAgentString },
        javaScript = settings.javaScriptEnabled,
        domStorage = settings.domStorageEnabled,
        firstPartyCookies = cookies.acceptCookie(),
        thirdPartyCookies = cookies.acceptThirdPartyCookies(this),
        wideViewport = settings.useWideViewPort,
        overviewMode = settings.loadWithOverviewMode,
        multipleWindows = settings.supportMultipleWindows(),
        automaticWindows = settings.javaScriptCanOpenWindowsAutomatically,
        mixedContentBlocked = settings.mixedContentMode == WebSettings.MIXED_CONTENT_NEVER_ALLOW,
    ), enabled)
}
