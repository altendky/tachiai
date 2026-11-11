package net.fstab.tachiai.platform.web

import android.view.Window
import android.view.WindowManager
import android.webkit.WebView
import net.fstab.tachiai.BuildConfig

/** Shared capture policy and compile-time WebView inspection policy. */
internal object DeveloperAccess {
    fun allowScreenCapture(window: Window) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    // This is process-global. Teardown must also respect the inspection build.
    // Call after any required WebView data-directory suffix has been set.
    fun configureWebInspection(enabledNormally: Boolean = false) {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.LOCAL_INSPECTION || enabledNormally)
    }
}
