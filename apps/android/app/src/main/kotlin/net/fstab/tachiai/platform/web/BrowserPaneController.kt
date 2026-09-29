package net.fstab.tachiai.platform.web

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.URI
import net.fstab.tachiai.provider.BrowserCommand
import net.fstab.tachiai.provider.ProviderAdapter

class BrowserPaneController internal constructor(
    private val adapter: ProviderAdapter,
) {
    private var webView: WebView? = null
    private var requestedUri: URI? = null
    private var recreate: (() -> Unit)? = null

    var status by mutableStateOf("Loading provider page")
        private set

    internal fun attach(webView: WebView, requestedUri: URI) {
        this.webView = webView
        this.requestedUri = requestedUri
    }

    internal fun detach(webView: WebView) {
        if (this.webView === webView) {
            this.webView = null
            requestedUri = null
        }
    }

    internal fun setRecreate(block: (() -> Unit)?) {
        recreate = block
    }

    internal fun pageReady() {
        status = "Ready for user interaction"
    }

    internal fun pageFailed(detail: String) {
        status = "Provider page failed to load ($detail)"
    }

    internal fun rendererGone() {
        status = "Browser renderer stopped; recreating the pane"
    }

    internal fun pause() = webView?.onPause()

    internal fun resume() = webView?.onResume()

    fun execute(command: BrowserCommand) {
        val view = webView ?: run {
            status = "Browser surface is unavailable"
            return
        }
        val uri = view.url?.let(::parseUri) ?: run {
            status = "Provider controls are unavailable on this page"
            return
        }
        val requested = requestedUri ?: run {
            status = "Provider controls are unavailable on this page"
            return
        }
        val script = adapter.scriptFor(command, requested, uri) ?: run {
            status = "Provider controls are unavailable on this page"
            return
        }
        view.evaluateJavascript(script) { result ->
            val outcome = result?.trim('"')
            status = when {
                outcome == null || outcome == "null" -> "Command sent; outcome unknown"
                outcome.contains("unavailable") -> "The provider player is not currently visible"
                outcome.contains("blocked") -> "Playback requires interaction with the provider player"
                outcome == "paused" -> "Paused"
                outcome == "muted" -> "Muted"
                outcome == "unmuted" -> "Unmuted"
                outcome.startsWith("volume ") -> "Provider ${outcome.replaceFirstChar(Char::uppercase)}"
                outcome == "play requested" || outcome == "playing" -> "Play requested"
                else -> "Command sent"
            }
        }
    }

    fun recover() {
        webView?.reload() ?: recreate?.invoke()
        status = "Reloading provider page"
    }

    private fun parseUri(value: String): URI? = runCatching { URI(value) }.getOrNull()
}
