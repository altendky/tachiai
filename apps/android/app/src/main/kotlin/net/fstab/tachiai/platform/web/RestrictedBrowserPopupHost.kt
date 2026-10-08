package net.fstab.tachiai.platform.web

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.R
import net.fstab.tachiai.provider.BrowserResourceCategory

// No adapter scripts, bridge, asset loader, provider inspection, URL logging, or cookie
// readback. Default WebViews share only the app's normal persistent profile.
internal class RestrictedBrowserPopupHost {
    private companion object { val nextPopupId = AtomicInteger() }
    private var dialog: Dialog? = null
    private var popup: WebView? = null

    fun pause() { popup?.onPause() }
    fun resume() { popup?.onResume() }
    fun close() { dialog?.dismiss() }

    // Lint does not recognize the anonymous client's implemented renderer callback.
    @SuppressLint("MissingOnRenderProcessGone")
    fun open(
        parent: WebView,
        hosts: Set<String>,
        alternateHosts: Set<String>,
        userGesture: Boolean,
        resultMsg: Message,
        logNativeSettings: Boolean,
        resourceCategory: (URI) -> BrowserResourceCategory?,
        onEvent: (PopupDiagnosticEvent) -> Unit,
    ): Boolean {
        if (hosts.isEmpty()) return false // Ordinary panes produce no auth diagnostics.
        val popupId = nextPopupId.incrementAndGet()
        fun report(kind: PopupEventKind, destination: PopupDestinationClass? = null, code: Int? = null) {
            onEvent(PopupDiagnosticEvent(popupId, kind, destination, code))
        }
        if (!BrowserPopupPolicy.canOpen(hosts, userGesture, popup != null)) {
            report(if (!userGesture) PopupEventKind.REQUEST_NO_GESTURE else PopupEventKind.REQUEST_ALREADY_OPEN)
            return false
        }
        val transport = resultMsg.obj as? WebView.WebViewTransport
        if (transport == null || resultMsg.target == null) {
            report(PopupEventKind.REQUEST_INVALID_TRANSPORT)
            return false
        }
        val resources = BrowserResourceDiagnostics(popupId, logNativeSettings)
        fun classifyResource(url: String): BrowserResourceCategory? =
            if (BuildConfig.DEBUG && logNativeSettings) runCatching { resourceCategory(URI(url)) }.getOrNull() else null
        val window = Dialog(parent.context)
        val label = TextView(parent.context).apply { setText(R.string.popup_waiting) }
        val browser = WebView(parent.context).apply {
            configureSecureSettings()
            // With this disabled Android converts window.open/target=_blank
            // into same-window navigation instead of consulting onCreateWindow.
            // Enable dispatch, not nested windows: the callback below rejects them.
            settings.setSupportMultipleWindows(true)
            settings.userAgentString = parent.settings.userAgentString
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            recordNativeSettings(BrowserSettingsRole.POPUP, logNativeSettings, parent)
            visibility = View.INVISIBLE
        }
        val layout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(Button(parent.context).apply {
                setText(R.string.popup_close)
                setOnClickListener { window.dismiss() }
            })
            addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        window.setContentView(layout)
        window.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        var terminal = false
        val navigation = BrowserPopupNavigation(hosts)
        fun fail(kind: PopupEventKind, destination: PopupDestinationClass? = null, code: Int? = null) {
            if (terminal) return
            terminal = true
            resources.close()
            navigation.fail()
            browser.visibility = View.INVISIBLE
            val event = PopupDiagnosticEvent(popupId, kind, destination, code)
            label.text = parent.context.getString(R.string.popup_failure, event.fixedMessage())
            browser.stopLoading()
            onEvent(event)
        }
        fun permitted(url: String): Boolean = runCatching { BrowserPopupPolicy.allows(URI(url), hosts) }.getOrDefault(false)
        fun classification(url: String) = BrowserPopupPolicy.classify(
            runCatching { URI(url) }.getOrNull(), hosts, alternateHosts,
        )
        browser.webViewClient = object : WebViewClient() {
            private val reportedSubresourceFailures = NativeDiagnosticBudget<Pair<PopupEventKind, Int>>(8)

            private fun reportSubresourceFailure(kind: PopupEventKind, code: Int) {
                if (terminal) return
                if (reportedSubresourceFailures.admit(kind to code)) {
                    report(kind, code = code)
                }
                if (reportedSubresourceFailures.takeLimitMarker()) report(PopupEventKind.SUBRESOURCE_LIMIT_REACHED)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (!terminal && permitted(request.url.toString())) return false
                fail(PopupEventKind.NAVIGATION_BLOCKED, classification(request.url.toString()))
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Also guard the first window load (shouldOverrideUrlLoading is
                // not called for every navigation). This is defense in depth,
                // not a guarantee that every server redirect is intercepted.
                if (request.isForMainFrame && !permitted(request.url.toString())) {
                    val destination = classification(request.url.toString())
                    // The callback is off the UI thread. A closed window's
                    // terminal guard prevents this post mutating a newer attempt.
                    view.post { fail(PopupEventKind.NAVIGATION_BLOCKED, destination) }
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
                        ByteArrayInputStream(byteArrayOf()))
                }
                if (!request.isForMainFrame && logNativeSettings) {
                    // This callback observes dispatch, not successful download or
                    // script execution. Do not inspect headers, bodies or tokens.
                    resources.record(classifyResource(request.url.toString()), ResourceEventKind.REQUEST_OBSERVED)
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (terminal) { view.stopLoading(); return }
                view.visibility = View.INVISIBLE
                label.setText(R.string.popup_waiting)
                val uri = runCatching { URI(url) }.getOrNull()
                val destination = BrowserPopupPolicy.classify(uri, hosts, alternateHosts)
                if (!navigation.start(uri)) fail(PopupEventKind.NAVIGATION_BLOCKED, destination)
                else report(if (destination == PopupDestinationClass.INITIAL_BLANK)
                    PopupEventKind.INITIAL_BLANK else PopupEventKind.NAVIGATION_ALLOWED, destination)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (terminal) return
                val uri = runCatching { URI(url) }.getOrNull()
                val current = runCatching { URI(view.url ?: "") }.getOrNull()
                // The old about:blank document could be opener-written. Only
                // show content once the allowlisted HTTPS document has committed.
                if (!terminal && navigation.canDisplayCommitted(uri) && uri == current) {
                    label.text = parent.context.getString(R.string.popup_origin, uri!!.host)
                    view.visibility = View.VISIBLE
                    report(PopupEventKind.COMMIT_VISIBLE, classification(url))
                } else report(PopupEventKind.COMMIT_IGNORED, classification(url))
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!terminal) report(PopupEventKind.PAGE_FINISHED, classification(url))
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail(PopupEventKind.NETWORK_ERROR, code = error.errorCode)
                else {
                    resources.record(classifyResource(request.url.toString()), ResourceEventKind.NETWORK_ERROR, error.errorCode)
                    reportSubresourceFailure(PopupEventKind.SUBRESOURCE_NETWORK_ERROR, error.errorCode)
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) fail(PopupEventKind.HTTP_ERROR, code = response.statusCode)
                else if (!request.isForMainFrame && response.statusCode >= 400) {
                    resources.record(classifyResource(request.url.toString()), ResourceEventKind.HTTP_ERROR, response.statusCode)
                    reportSubresourceFailure(PopupEventKind.SUBRESOURCE_HTTP_ERROR, response.statusCode)
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                fail(PopupEventKind.TLS_ERROR)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail(PopupEventKind.RENDERER_GONE)
                window.dismiss()
                return true
            }
        }
        browser.webChromeClient = object : WebChromeClient() {
            private val reportedConsoleLevels = NativeDiagnosticBudget<android.webkit.ConsoleMessage.MessageLevel>(2)
            override fun onCloseWindow(view: WebView) { window.dismiss() }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!terminal) report(PopupEventKind.NESTED_WINDOW_DENIED)
                return false
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
                if (!terminal) report(PopupEventKind.PERMISSION_DENIED)
            }
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                val level = message.messageLevel()
                val event = when (level) {
                    android.webkit.ConsoleMessage.MessageLevel.ERROR -> PopupEventKind.CONSOLE_ERROR_SUPPRESSED
                    android.webkit.ConsoleMessage.MessageLevel.WARNING -> PopupEventKind.CONSOLE_WARNING_SUPPRESSED
                    else -> null
                }
                if (!terminal && event != null && reportedConsoleLevels.admit(level)) report(event)
                return true
            }
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                if (!terminal) report(PopupEventKind.SCRIPT_ALERT_CANCELLED)
                result.cancel(); return true
            }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                if (!terminal) report(PopupEventKind.SCRIPT_CONFIRM_CANCELLED)
                result.cancel(); return true
            }
            override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean {
                if (!terminal) report(PopupEventKind.SCRIPT_BEFORE_UNLOAD_CANCELLED)
                result.cancel(); return true
            }
            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean {
                if (!terminal) report(PopupEventKind.SCRIPT_PROMPT_CANCELLED)
                result.cancel(); return true
            }
        }
        window.setOnDismissListener {
            terminal = true
            resources.close()
            navigation.fail()
            CookieManager.getInstance().flush()
            layout.removeView(browser)
            browser.stopLoading()
            browser.webChromeClient = null
            browser.destroy()
            if (popup === browser) { popup = null; dialog = null }
            report(PopupEventKind.CLOSED)
        }
        popup = browser
        dialog = window
        window.show()
        window.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        report(PopupEventKind.OPENED)
        transport.webView = browser
        resultMsg.sendToTarget()
        return true
    }
}
