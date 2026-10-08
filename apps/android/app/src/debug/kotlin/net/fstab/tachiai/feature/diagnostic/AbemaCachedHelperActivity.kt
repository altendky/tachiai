package net.fstab.tachiai.feature.diagnostic

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.platform.web.configureSecureSettings
import net.fstab.tachiai.provider.abema.AbemaBundleException
import net.fstab.tachiai.provider.abema.AbemaCachedHelperPolicy
import net.fstab.tachiai.provider.abema.AbemaPublicBundleCache
import net.fstab.tachiai.provider.abema.AbemaPublicBundleHttp
import org.json.JSONObject
import org.json.JSONTokener

// Additive debug case only. Downloaded provider code is never an APK asset.
// No provider identity, source, challenge, response, media or CDM enters this host.
@SuppressLint("SetTextI18n") // Preliminary diagnostic English, not product localization.
class AbemaCachedHelperActivity : ComponentActivity() {
    companion object { private var profileConfigured = false }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val resumed = AtomicBoolean()
    private val epoch = AtomicLong()
    private val transport = AtomicReference<AbemaPublicBundleHttp?>()
    private lateinit var host: LinearLayout
    private lateinit var status: TextView
    private lateinit var start: Button
    private var browser: WebView? = null
    @Volatile private var deadline = 0L
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < 28) { finish(); return }
        // Must precede WebView/CookieManager APIs in this manifest-isolated process.
        if (!profileConfigured) {
            WebView.setDataDirectorySuffix("abema-cached-helper")
            profileConfigured = true
        }
        WebView.setWebContentsDebuggingEnabled(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        host.addView(TextView(this).apply {
            text = "ABEMA cached helper · initialization only\nDownloads reviewed public code into app cache. No login, license or playback. Existing players are unchanged."
        })
        status = TextView(this).apply { text = "Ready. First run downloads; later runs verify and reuse the cache." }
        host.addView(status)
        start = Button(this).apply { text = "Download/cache + initialize"; setOnClickListener { begin() } }
        host.addView(start)
        host.addView(Button(this).apply { text = "Stop"; setOnClickListener { stop("STOPPED") } })
        host.addView(Button(this).apply { text = "Back"; setOnClickListener { finish() } })
        setContentView(host)
    }

    private fun current(run: Long): Boolean = resumed.get() && epoch.get() == run && !isFinishing &&
        SystemClock.elapsedRealtime() < deadline

    private fun record(value: String) {
        status.text = value
        Log.d("TachiaiAbemaBundle", value)
    }

    private fun begin() {
        stop(null)
        val run = epoch.incrementAndGet()
        val started = SystemClock.elapsedRealtime()
        deadline = started + 45_000
        running = true
        start.isEnabled = false
        record("phase=PREPARING")
        handler.postDelayed({ if (running && epoch.get() == run) stop("TIMEOUT") }, 45_000)
        worker.execute {
            val http = AbemaPublicBundleHttp(canRun = { current(run) })
            transport.set(http)
            try {
                val prepared = AbemaPublicBundleCache(File(cacheDir, "abema-helper-bundle"), { current(run) })
                    .prepare(http::fetch)
                if (!current(run)) return@execute
                // Only app-owned control files are packaged. Bundle bytes are external.
                val owned = mapOf(
                    "/runtime/index.html" to assets.open("abema/cached-helper.html").use { it.readBytes() },
                    "/runtime/control.js" to assets.open("abema/cached-helper.js").use { it.readBytes() },
                    "/runtime/initialize.js" to assets.open("abema/cached-helper-initialize.js").use { it.readBytes() },
                    "/runtime/bundle.js" to prepared.bytes,
                )
                handler.post {
                    if (current(run)) {
                        record("cache=${prepared.origin.name} bytes=${prepared.bytes.size} elapsedMs=${SystemClock.elapsedRealtime() - started}")
                        openRuntime(owned, run, started)
                    }
                }
            } catch (failure: AbemaBundleException) {
                handler.post { if (current(run)) stop(failure.category.name) }
            } catch (_: Exception) {
                handler.post { if (current(run)) stop("UNAVAILABLE") }
            } finally {
                transport.compareAndSet(http, null)
                http.close()
            }
        }
    }

    @SuppressLint("MissingOnRenderProcessGone")
    private fun openRuntime(owned: Map<String, ByteArray>, run: Long, started: Long) {
        if (!current(run)) return
        val runtimeDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 10_000)
        var documentStarted = false
        val view = WebView(this)
        browser = view
        view.configureSecureSettings()
        view.settings.domStorageEnabled = false
        view.settings.blockNetworkLoads = true
        view.settings.blockNetworkImage = true
        view.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean = true
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, result: android.os.Message): Boolean = false
            override fun onJsAlert(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean { result.cancel(); return true }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean { result.cancel(); return true }
            override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean { result.cancel(); return true }
            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: android.webkit.JsPromptResult): Boolean { result.cancel(); return true }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && request.url.toString() == AbemaCachedHelperPolicy.PAGE) return false
                stop("NAVIGATION_REFUSED"); return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = runCatching { URI(request.url.toString()) }.getOrNull()
                val bytes = uri?.takeIf { current(run) && AbemaCachedHelperPolicy.allows(it, request.isForMainFrame, request.method) }
                    ?.let { owned[it.path] }
                if (bytes == null) {
                    handler.post { if (current(run)) stop("RESOURCE_REFUSED") }
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                }
                val headers = mapOf("Content-Security-Policy" to AbemaCachedHelperPolicy.CONTENT_SECURITY_POLICY,
                    "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")
                return WebResourceResponse(if (request.isForMainFrame) "text/html" else "application/javascript", "UTF-8",
                    200, "OK", headers, ByteArrayInputStream(bytes))
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != AbemaCachedHelperPolicy.PAGE || documentStarted) { stop("NAVIGATION_REFUSED"); return }
                documentStarted = true
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (url != AbemaCachedHelperPolicy.PAGE) stop("NAVIGATION_REFUSED")
            }
            override fun onReceivedSslError(view: WebView, ssl: SslErrorHandler, error: SslError) { ssl.cancel(); stop("TLS_REFUSED") }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { stop("RENDERER_GONE"); return true }
        }
        host.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        view.loadUrl(AbemaCachedHelperPolicy.PAGE)
        poll(view, run, runtimeDeadline, started)
    }

    private fun poll(view: WebView, run: Long, runtimeDeadline: Long, started: Long, readyAt: Long? = null) {
        if (!current(run) || browser !== view) return
        if (SystemClock.elapsedRealtime() >= runtimeDeadline) { stop("RUNTIME_TIMEOUT"); return }
        if (view.url != AbemaCachedHelperPolicy.PAGE) {
            handler.postDelayed({ poll(view, run, runtimeDeadline, started) }, 100); return
        }
        view.evaluateJavascript("window.__tachiaiCachedHelperResult || null") { raw ->
            if (!current(run) || browser !== view) return@evaluateJavascript
            val parsed = try {
                val text = if (raw != null && raw.length <= 1024) JSONTokener(raw).nextValue() as? String else null
                text?.let(::JSONObject)?.takeIf { it.length() == 4 }
            } catch (_: Exception) { null }
            val state = parsed?.opt("state") as? String
            val modules = parsed?.opt("modules") as? Int
            val characters = parsed?.opt("sourceCharacters") as? Int
            val callable = parsed?.opt("callable") as? Boolean
            when {
                state == "READY" && modules == 37 && characters != null && characters in 1..(2 * 1024 * 1024) && callable == true -> {
                    val now = SystemClock.elapsedRealtime()
                    if (readyAt == null || now - readyAt < 100) {
                        handler.postDelayed({ poll(view, run, runtimeDeadline, started, readyAt ?: now) }, 100)
                        return@evaluateJavascript
                    }
                    running = false
                    handler.removeCallbacksAndMessages(null)
                    start.isEnabled = true
                    record("runtime=INTERFACE_READY modules=$modules sourceCharacters=$characters elapsedMs=${SystemClock.elapsedRealtime() - started}")
                    // End execution after observing the interface, retaining only public cache.
                    destroyBrowser()
                }
                state == "REFUSED" -> stop("RUNTIME_REFUSED")
                readyAt != null -> stop("STATE_REFUSED")
                state != null && state !in setOf("PENDING", "SETTLING") -> stop("STATE_REFUSED")
                else -> handler.postDelayed({ poll(view, run, runtimeDeadline, started) }, 100)
            }
        }
    }

    private fun destroyBrowser() {
        browser?.let { view ->
            browser = null
            view.stopLoading(); view.onPause(); host.removeView(view)
            view.webChromeClient = null; view.destroy()
        }
    }

    private fun stop(category: String?) {
        epoch.incrementAndGet()
        running = false
        handler.removeCallbacksAndMessages(null)
        transport.getAndSet(null)?.close()
        destroyBrowser()
        if (::start.isInitialized) start.isEnabled = true
        if (category != null && ::status.isInitialized) record("outcome=$category")
    }

    override fun onResume() { super.onResume(); resumed.set(true) }
    override fun onPause() { resumed.set(false); stop(if (running) "CANCELLED" else null); super.onPause() }
    override fun onDestroy() { stop(null); worker.shutdownNow(); super.onDestroy() }
}
