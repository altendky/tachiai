package net.fstab.tachiai.provider.abema

import android.app.Application
import android.content.Context
import android.os.Build
import android.webkit.WebView

// Shared by the cached viewer and the inert observation surface. Never selects
// a default/login WebView profile and never inspects or clears cookies/storage.
internal fun configureAbemaCachedPlaybackProfile(context: Context) {
    check(Build.VERSION.SDK_INT >= 28)
    check(Application.getProcessName() == "${context.packageName}:prototype_cached_player")
    if (!cachedProfileConfigured) {
        WebView.setDataDirectorySuffix("prototype-cached-player")
        cachedProfileConfigured = true
    }
}
private var cachedProfileConfigured = false
