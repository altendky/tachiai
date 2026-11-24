package net.fstab.tachiai.provider.abema

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import net.fstab.tachiai.platform.web.configureSecureSettings

private object AbemaMetadataRuntimeProfile { var configured = false }

internal fun configureAbemaMetadataRuntimeProfile(context: Context) {
    check(Looper.myLooper() == Looper.getMainLooper())
    if (Build.VERSION.SDK_INT < 28) error("Metadata runtime profile requires API 28")
    check(Application.getProcessName() == "${context.packageName}:abema_guest_bootstrap")
    if (!AbemaMetadataRuntimeProfile.configured) {
        WebView.setDataDirectorySuffix(AbemaMetadataRuntimePolicy.PROFILE_SUFFIX)
        AbemaMetadataRuntimeProfile.configured = true
    }
    net.fstab.tachiai.platform.web.DeveloperAccess.configureWebInspection()
}

internal fun configureAbemaMetadataWebView(view: WebView) {
    view.configureSecureSettings()
    view.settings.domStorageEnabled = false
    view.settings.blockNetworkImage = true
    view.settings.cacheMode = WebSettings.LOAD_NO_CACHE
    view.settings.setSupportMultipleWindows(false)
    CookieManager.getInstance().setAcceptCookie(false)
    CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
}
