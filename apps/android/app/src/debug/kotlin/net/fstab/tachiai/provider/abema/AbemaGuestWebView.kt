package net.fstab.tachiai.provider.abema

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import net.fstab.tachiai.platform.web.configureSecureSettings

private object AbemaGuestProfile { var configured = false }

internal fun configureAbemaGuestBootstrapProfile(context: Context) {
    check(Looper.myLooper() == Looper.getMainLooper())
    if (Build.VERSION.SDK_INT < 28) error("Guest profile requires API 28")
    check(Application.getProcessName() == "${context.packageName}:abema_guest_bootstrap")
    if (!AbemaGuestProfile.configured) {
        WebView.setDataDirectorySuffix(AbemaGuestBootstrapPolicy.PROFILE_SUFFIX)
        AbemaGuestProfile.configured = true
    }
    WebView.setWebContentsDebuggingEnabled(false)
}

internal fun configureAbemaGuestWebView(view: WebView) {
    view.configureSecureSettings()
    view.settings.domStorageEnabled = false
    view.settings.blockNetworkImage = true
    view.settings.cacheMode = WebSettings.LOAD_NO_CACHE
    view.settings.setSupportMultipleWindows(false)
    CookieManager.getInstance().setAcceptCookie(false)
    CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
}
