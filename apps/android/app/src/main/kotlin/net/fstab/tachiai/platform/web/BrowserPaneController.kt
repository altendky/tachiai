package net.fstab.tachiai.platform.web

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.URI
import net.fstab.tachiai.presentation.PaneId
import net.fstab.tachiai.provider.BrowserCommand
import net.fstab.tachiai.provider.ProviderAdapter
import net.fstab.tachiai.BuildConfig

class BrowserPaneController internal constructor(
    private val adapter: ProviderAdapter,
) {
    private var webView: WebView? = null
    private var requestedUri: URI? = null
    private var recreate: (() -> Unit)? = null
    private var nextRequestId = 0L
    private var diagnosticEpoch = 0L
    private var diagnosticRequest = 0L
    private val pending = mutableMapOf<PaneId?, Long>()
    private var paneStatuses by mutableStateOf<Map<PaneId, String>>(emptyMap())

    var status by mutableStateOf("Loading provider page")
        private set

    internal var latestPopupEvent by mutableStateOf<PopupDiagnosticEvent?>(null)
        private set
    internal var lastPopupFailure by mutableStateOf<PopupDiagnosticEvent?>(null)
        private set
    private var latestPopupId = 0

    internal fun recordPopupEvent(event: PopupDiagnosticEvent) {
        logPopupEvent(event)
        if (event.popupId < latestPopupId) return
        latestPopupId = event.popupId
        latestPopupEvent = event
        if (event.kind == PopupEventKind.OPENED) {
            lastPopupFailure = null
        }
        if (event.kind.failure) lastPopupFailure = event
    }

    internal fun attach(webView: WebView, requestedUri: URI) {
        invalidateCommands()
        this.webView = webView
        this.requestedUri = requestedUri
    }

    internal fun detach(webView: WebView) {
        if (this.webView === webView) {
            invalidateCommands()
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

    internal fun pageLoading() {
        invalidateCommands()
        status = "Loading provider page"
    }

    internal fun pageFailed(detail: String) {
        invalidateCommands()
        status = "Provider page failed to load ($detail)"
    }

    internal fun rendererGone() {
        status = "Browser renderer stopped; recreating the pane"
    }

    internal fun pause() = webView?.onPause()

    internal fun resume() = webView?.onResume()

    // Debug feature only. The adapter and caller both validate the playback route;
    // no native bridge or browser-debugging endpoint is exposed to provider pages.
    internal fun evaluatePlaybackDiagnostic(
        scriptFor: (URI, URI) -> String?,
        onResult: (String?) -> Unit,
    ) {
        if (!BuildConfig.DEBUG) return
        val view = webView ?: return
        val requested = requestedUri ?: return
        val current = view.url?.let(::parseUri) ?: return
        if (!adapter.isDiagnosticRouteAllowed(current)) return
        val script = scriptFor(requested, current) ?: return
        val epoch = diagnosticEpoch
        val ticket = ++diagnosticRequest
        view.evaluateJavascript(script) { result ->
            if (webView === view && diagnosticEpoch == epoch && diagnosticRequest == ticket &&
                view.url?.let(::parseUri) == current) {
                onResult(result?.takeIf { it.length <= 8192 })
            }
        }
    }

    fun statusFor(pane: PaneId): String = paneStatuses[pane] ?: status

    fun execute(command: BrowserCommand, pane: PaneId? = null) {
        val requestId = ++nextRequestId
        pending[pane] = requestId
        val view = webView ?: run {
            updateStatus(pane, "Browser surface is unavailable")
            return
        }
        val uri = view.url?.let(::parseUri) ?: run {
            updateStatus(pane, "Provider controls are unavailable on this page")
            return
        }
        val requested = requestedUri ?: run {
            updateStatus(pane, "Provider controls are unavailable on this page")
            return
        }
        val targeted = pane?.let { adapter.scriptForPane(it, command, requestId, requested, uri) }
        val script = if (pane == null) adapter.scriptFor(command, requested, uri) else targeted?.send
        if (script == null) {
            updateStatus(pane, "Provider controls are unavailable on this page")
            return
        }
        view.evaluateJavascript(script) { result ->
            if (this.webView !== view || pending[pane] != requestId) return@evaluateJavascript
            if (result == "\"pending\"" && targeted?.poll != null) {
                updateStatus(pane, "Waiting for player acknowledgment")
                pollResult(view, pane, requestId, targeted.poll, 15)
                return@evaluateJavascript
            }
            val outcome = result?.trim('"')
            updateStatus(pane, describeOutcome(outcome))
        }
    }

    private fun pollResult(view: WebView, pane: PaneId?, requestId: Long, script: String, remaining: Int) {
        view.postDelayed({
            if (this.webView !== view || pending[pane] != requestId) return@postDelayed
            view.evaluateJavascript(script) { result ->
                if (this.webView !== view || pending[pane] != requestId) return@evaluateJavascript
                if (result == null || result == "null") {
                    if (remaining > 1) pollResult(view, pane, requestId, script, remaining - 1)
                    else updateStatus(pane, "Player did not acknowledge the command; use its own controls")
                } else {
                    updateStatus(pane, describeOutcome(result.trim('"')))
                }
            }
        }, 200L)
    }

    private fun updateStatus(pane: PaneId?, value: String) {
        if (pane == null) status = value else paneStatuses = paneStatuses + (pane to value)
    }

    private fun invalidateCommands() {
        diagnosticEpoch += 1
        pending.clear()
        paneStatuses = emptyMap()
    }

    private fun describeOutcome(outcome: String?): String = when {
        outcome == null || outcome == "null" -> "Command sent; outcome unknown"
        outcome == "not ready" -> "Player is not ready; use its Start control"
        outcome == "failed" -> "Player command failed"
        outcome.contains("unavailable") -> "The provider player is not currently visible"
        outcome.contains("blocked") -> "Playback requires interaction with the provider player"
        outcome == "paused" -> "Paused"
        outcome == "muted" -> "Muted"
        outcome == "unmuted" -> "Unmuted"
        outcome.startsWith("volume ") -> "Provider ${outcome.replaceFirstChar(Char::uppercase)}"
        outcome == "play requested" || outcome == "playing" -> "Play requested"
        outcome == "pause requested" -> "Pause requested"
        outcome == "mute requested" -> "Mute requested"
        outcome == "unmute requested" -> "Sound requested"
        else -> "Command sent"
    }

    fun recover() {
        invalidateCommands()
        webView?.reload() ?: recreate?.invoke()
        status = "Reloading provider page"
    }

    private fun parseUri(value: String): URI? = runCatching { URI(value) }.getOrNull()
}
