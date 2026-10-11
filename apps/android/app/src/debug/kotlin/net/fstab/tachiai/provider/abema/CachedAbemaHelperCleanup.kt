package net.fstab.tachiai.provider.abema

import android.view.ViewGroup
import android.webkit.WebView
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage

// Registered with the process owner before browser configuration can throw.
// A general cleanup failure is not a receipt for WebView destruction.
internal class CachedAbemaHelperCleanup(
    private val retained: Any,
    private val actions: List<Pair<FailureStage, () -> Unit>>,
    private val diagnostics: FailureReporter,
) {
    private var attempted = false
    var destructionConfirmed = false
        private set
    var cleanupFailed = false
        private set
    fun destroy(): Boolean {
        if (attempted) return destructionConfirmed
        attempted = true
        for ((stage, action) in actions) {
            val confirmed = diagnostics.cleanupAll(stage, action)
            if (!confirmed) cleanupFailed = true
            if (stage == FailureStage.WEBVIEW_DESTROY) destructionConfirmed = confirmed
        }
        return destructionConfirmed
    }
    override fun toString() = "CachedAbemaHelperCleanup(status only)"
    companion object {
        fun forView(view: WebView, diagnostics: FailureReporter) = CachedAbemaHelperCleanup(view, listOf(
            FailureStage.WEBVIEW_STOP to { view.stopLoading() },
            FailureStage.WEBVIEW_PAUSE to { view.onPause() },
            FailureStage.WEBVIEW_DETACH to { (view.parent as? ViewGroup)?.removeView(view) },
            FailureStage.WEBVIEW_DESTROY to { view.webChromeClient = null; view.destroy() },
        ), diagnostics)
    }
}
