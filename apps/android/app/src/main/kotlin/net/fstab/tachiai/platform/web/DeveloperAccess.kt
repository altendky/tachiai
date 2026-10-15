package net.fstab.tachiai.platform.web

import android.view.Window
import android.view.WindowManager
import android.webkit.WebView
import net.fstab.tachiai.BuildConfig

/** Compile-time local inspection policy, shared by every app process/window. */
internal object DeveloperAccess {
    fun protectScreen(window: Window, protectedNormally: Boolean = true) {
        if (protectedNormally && !BuildConfig.LOCAL_INSPECTION) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // This is process-global. Teardown must also respect the inspection build.
    // Call after any required WebView data-directory suffix has been set.
    fun configureWebInspection(enabledNormally: Boolean = false) {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.LOCAL_INSPECTION || enabledNormally)
    }
}
