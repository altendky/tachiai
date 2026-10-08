package net.fstab.tachiai.feature.diagnostic

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayInputStream
import java.net.URI
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.R
import net.fstab.tachiai.platform.web.MediaPermissionPolicy
import net.fstab.tachiai.platform.web.configureSecureSettings
import net.fstab.tachiai.platform.web.userAgentFor
import net.fstab.tachiai.provider.BrowserIdentity
import net.fstab.tachiai.provider.abema.AbemaAdapter

// Debug sources only; isolated process/profile so DevTools cannot expose ordinary panes.
// No JavaScript injection, native bridge, request interception/proxying or body capture.
class AbemaInspectionActivity : ComponentActivity() {
    companion object {
        private var profileConfigured = false
    }

    private var browser: WebView? = null
    private lateinit var host: LinearLayout
    private lateinit var status: TextView
    private var source = AbemaInspectionSource.NEWS
    private var supported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = AbemaInspectionPolicy.source(intent.getStringExtra(AbemaInspectionPolicy.SOURCE_EXTRA))
            ?: run { finish(); return }
        supported = BuildConfig.DEBUG && AbemaInspectionPolicy.supportsInspection(Build.VERSION.SDK_INT)
        if (supported && Build.VERSION.SDK_INT >= 28) {
            // Before any WebView API in this manifest-isolated process; never import cookies.
            if (!profileConfigured) {
                WebView.setDataDirectorySuffix(AbemaInspectionPolicy.PROFILE_SUFFIX)
                profileConfigured = true
            }
            WebView.setWebContentsDebuggingEnabled(false)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        host.addView(TextView(this).apply { setText(R.string.abema_inspection_warning) })
        status = TextView(this).apply { setText(R.string.abema_inspection_stopped) }
        host.addView(status)
        host.addView(LinearLayout(this).apply {
            addControl(R.string.abema_inspection_news) { select(AbemaInspectionSource.NEWS) }
            addControl(R.string.abema_inspection_replay) { select(AbemaInspectionSource.REPLAY) }
            addControl(R.string.abema_inspection_reload) { browser?.reload() }
            addControl(R.string.abema_inspection_close) { finish() }
        })
        host.addView(Button(this).apply {
            setText(R.string.abema_request_open)
            setOnClickListener { startActivity(Intent(this@AbemaInspectionActivity, AbemaNativeRequestActivity::class.java)) }
        })
        setContentView(host)
        if (!supported) status.setText(R.string.abema_inspection_unsupported)
    }

    private fun LinearLayout.addControl(label: Int, action: () -> Unit) {
        addView(Button(this@AbemaInspectionActivity).apply {
            setText(label)
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun select(selected: AbemaInspectionSource) {
        source = selected
        browser?.loadUrl(source.url)
    }

    @SuppressLint("MissingOnRenderProcessGone")
    override fun onResume() {
        super.onResume()
        if (!supported || browser != null) return
        WebView.setWebContentsDebuggingEnabled(true)
        Log.d("TachiaiAbemaInspect", "inspection=ENABLED profile=ISOLATED")
        browser = WebView(this).apply {
            configureSecureSettings()
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // Unsupported desktop identity retained as a comparison, not a support claim.
            settings.userAgentString = userAgentFor(BrowserIdentity.DESKTOP_CHROME, settings.userAgentString)
            settings.setSupportMultipleWindows(true)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean = true
                override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    result.cancel(); return true
                }
                override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    result.cancel(); return true
                }
                override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    result.cancel(); return true
                }
                override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean {
                    result.cancel(); return true
                }
                override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, result: Message): Boolean = false
                override fun onPermissionRequest(request: PermissionRequest) {
                    val granted = runCatching {
                        MediaPermissionPolicy.grantProtectedMediaOnly(AbemaAdapter, URI(request.origin.toString()),
                            request.resources, PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
                    }.getOrDefault(emptyArray())
                    if (granted.isEmpty()) request.deny() else request.grant(granted)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame || allows(request.url.toString())) return false
                    endInspection()
                    finish()
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (!request.isForMainFrame || allows(request.url.toString())) return null
                    view.post { endInspection(); finish() }
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
                        ByteArrayInputStream(byteArrayOf()))
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (!allows(url)) { endInspection(); finish(); return }
                    status.setText(R.string.abema_inspection_loading)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (allows(url)) status.setText(R.string.abema_inspection_ready)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    // SPA history changes do not necessarily trigger page/navigation callbacks.
                    if (!allows(url)) { endInspection(); finish() }
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    status.setText(R.string.abema_inspection_tls_failure)
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    endInspection()
                    finish()
                    return true
                }
            }
            host.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            loadUrl(source.url)
        }
    }

    private fun allows(url: String?): Boolean = url != null && runCatching {
        AbemaInspectionPolicy.allowsNavigation(URI(url))
    }.getOrDefault(false)

    private fun endInspection() {
        if (!supported) return
        // Disable the process-global debugger before disposing any page state.
        WebView.setWebContentsDebuggingEnabled(false)
        browser?.let {
            it.stopLoading()
            it.onPause()
            host.removeView(it)
            it.webChromeClient = null
            it.destroy()
        }
        browser = null
        if (::status.isInitialized) status.setText(R.string.abema_inspection_stopped)
        Log.d("TachiaiAbemaInspect", "inspection=DISABLED")
    }

    override fun onPause() { endInspection(); super.onPause() }
    override fun onDestroy() { endInspection(); super.onDestroy() }
}
