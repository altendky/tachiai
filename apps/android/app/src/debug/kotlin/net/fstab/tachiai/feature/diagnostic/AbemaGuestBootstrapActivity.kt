package net.fstab.tachiai.feature.diagnostic

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.web.configureSecureSettings
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.abema.*
import org.json.JSONObject
import org.json.JSONTokener

private object AbemaGuestProfile {
    var configured = false
}

internal fun configureAbemaGuestBootstrapProfile(context: Context) {
    check(Looper.myLooper() == Looper.getMainLooper())
    if (Build.VERSION.SDK_INT < 28) error("Guest profile requires API 28")
    check(Application.getProcessName() == "${context.packageName}:abema_guest_bootstrap")
    if (!AbemaGuestProfile.configured) {
        WebView.setDataDirectorySuffix(AbemaGuestBootstrapPolicy.PROFILE_SUFFIX)
        AbemaGuestProfile.configured = true
    }
    WebView.setWebContentsDebuggingEnabled(false)
}

internal fun configureAbemaGuestWebView(view: WebView) {
    view.configureSecureSettings()
    view.settings.domStorageEnabled = false
    view.settings.blockNetworkImage = true
    view.settings.cacheMode = WebSettings.LOAD_NO_CACHE
    view.settings.setSupportMultipleWindows(false)
    CookieManager.getInstance().setAcceptCookie(false)
    CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
}

// Explicit debug observation only. No automatic request on creation, no account
// connection. Retained session binding remains JS-owned. Platform request objects
// transiently carry bearer headers/private URIs; admission never copies bearer
// headers and no private request data enters diagnostics/UI/storage.
@SuppressLint("SetTextI18n")
class AbemaGuestBootstrapActivity : ComponentActivity() {
    companion object {
        private val admission = AbemaGuestBootstrapLifecycle(SystemClock::elapsedRealtime)
        private val webRoute = AbemaWebViewRoute()
        private val cleanupWorkers = Executors.newFixedThreadPool(2)
        // One poisoned run maximum. Retain its uncertain JS and selected route;
        // no automatic backend close or process kill can expose System access.
        private var retainedAttempt: Attempt? = null
    }
    private class Attempt(val run: Long, val operation: AbemaGuestBootstrapOperation, diagnostics: FailureReporter) {
        val preparation = RoutePreparation(diagnostics)
        val prepared = CountDownLatch(1)
        val cancellationFinished = CountDownLatch(1)
        val route = AtomicReference<RouteSession?>()
        val download = AtomicReference<AbemaPublicBundleHttp?>()
        val requests = AbemaGuestRequestAdmission(operation)
        val cleanupFailed = AtomicBoolean()
        val retainRoute = AtomicBoolean()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        @Volatile var owner: AbemaCatalogRouteOwner? = null
        var view: WebView? = null
    }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val foreground = AtomicBoolean()
    private lateinit var diagnostics: FailureReporter
    private lateinit var host: LinearLayout
    private lateinit var status: TextView
    private lateinit var choose: Spinner
    private lateinit var start: Button
    private lateinit var verifyProfile: Button
    private lateinit var verifyEpisode: Button
    private var instances: List<ProviderInstance> = emptyList()
    @Volatile private var attempt: Attempt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < 28) { finish(); return }
        // This manifest-isolated process must never initialize an ordinary
        // provider/browser profile. No cookie inspection, deletion or import.
        configureAbemaGuestBootstrapProfile(this)
        intent = android.content.Intent() // No external extras influence a request.
        diagnostics = FailureDiagnostics.create(applicationContext)
        host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        host.addView(TextView(this).apply {
            text = "ABEMA guest preparation · debug only\nSelect a saved ABEMA provider and explicitly prepare or verify a fresh guest using its saved route. No account login or playback. Cookies are disabled; episode acceptance is unverified."
        })
        status = TextView(this).apply { text = "Loading saved providers. No provider request has started." }
        host.addView(status)
        choose = Spinner(this).apply { isEnabled = false }; host.addView(choose)
        start = Button(this).apply { text = "Prepare guest"; isEnabled = false; setOnClickListener { begin(AbemaGuestBootstrapOperation.GUEST_ONLY) } }
        host.addView(start)
        verifyProfile = Button(this).apply {
            text = "Verify guest profile"; isEnabled = false
            setOnClickListener { begin(AbemaGuestBootstrapOperation.VERIFY_PROFILE) }
        }
        host.addView(verifyProfile)
        verifyEpisode = Button(this).apply {
            text = "Verify guest episode metadata"; isEnabled = false
            setOnClickListener { begin(AbemaGuestBootstrapOperation.VERIFY_EPISODE) }
        }
        host.addView(verifyEpisode)
        host.addView(Button(this).apply { text = "Stop"; setOnClickListener { stop("STOPPED") } })
        host.addView(Button(this).apply { text = "Back"; setOnClickListener { finish() } })
        setContentView(host)
        worker.execute {
            try {
                val saved = savedInstances().filter { it.service == PrototypeService.ABEMA }
                handler.post {
                    if (!isFinishing && !isDestroyed) {
                        instances = saved
                        choose.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                            saved.map { "${it.name} · ${it.setup.route?.title ?: "Route needs review"}" })
                        controls()
                        status.text = if (saved.isEmpty()) "No saved ABEMA provider available." else
                            "Ready. Preparing a guest does not connect an existing account."
                    }
                }
            } catch (error: Exception) {
                diagnostics.report(FailureStage.CATALOG_LOAD, error)
                handler.post { if (!isDestroyed) status.text = "Saved providers could not be read. Close and reopen." }
            }
        }
    }

    private fun savedInstances() = providerInstanceStore(applicationContext).read { legacyProviderSettings(applicationContext) }
    private fun readOwner(id: String) = readAbemaCatalogRouteOwner(id, ::savedInstances,
        { connectionProfileStore(applicationContext).selected(it) })
    private fun current(active: Attempt) = attempt === active && admission.current(active.run, foreground.get())
    private fun runtimeCurrent(active: Attempt) = attempt === active && admission.canRunRuntime(active.run, foreground.get())
    private fun sameOwner(active: Attempt): Boolean {
        // Worker-only protected store reads; queued main callbacks use current().
        if (!current(active)) return false
        val owner = active.owner ?: return false
        return owner.sameOwnership(readOwner(owner.instance.id)) && current(active)
    }
    private fun controls() {
        if (!::start.isInitialized) return
        start.isEnabled = foreground.get() && instances.isNotEmpty() && !admission.unavailable
        verifyProfile.isEnabled = start.isEnabled
        verifyEpisode.isEnabled = start.isEnabled
        choose.isEnabled = start.isEnabled
    }
    private fun outcome(category: String) {
        Log.d("TachiaiAbemaGuest", "outcome=$category")
        status.text = when (category) {
            "PREPARED" -> "Guest prepared. No existing account is connected; the transient guest has been discarded."
            "GUEST_PROFILE_BOUND" -> "Guest profile identity verified. No existing account is connected; the transient guest and profile have been discarded."
            "VERIFYING_PROFILE" -> "Preparing a fresh guest and verifying its server profile through the selected provider route."
            "EXACT_EPISODE_METADATA" -> "Exact episode metadata verified. The transient guest and metadata have been discarded; no account or playback is connected."
            "VERIFYING_EPISODE" -> "Preparing a fresh guest and verifying one fixed episode through the selected provider route."
            "PREPARING" -> "Preparing guest through the selected provider route."
            "CLEANUP_FAILED" -> "Cleanup could not be confirmed. Further attempts are blocked in this process."
            else -> "Guest preparation ended: $category. No account is connected."
        }
    }

    private fun begin(operation: AbemaGuestBootstrapOperation) {
        val selected = instances.getOrNull(choose.selectedItemPosition) ?: return
        val run = admission.begin(foreground.get()) ?: return
        val active = Attempt(run, operation, diagnostics)
        attempt = active
        Log.d("TachiaiAbemaGuest", "routeMode=${selected.setup.route?.mode?.name ?: "UNSET"}")
        controls(); outcome(when (operation) {
            AbemaGuestBootstrapOperation.GUEST_ONLY -> "PREPARING"
            AbemaGuestBootstrapOperation.VERIFY_PROFILE -> "VERIFYING_PROFILE"
            AbemaGuestBootstrapOperation.VERIFY_EPISODE -> "VERIFYING_EPISODE"
        })
        handler.postDelayed({ if (attempt === active) stop("TIMEOUT") }, 45_000)
        worker.execute {
            try {
                active.owner = readOwner(selected.id)
                check(sameOwner(active))
                val route = when (operation) {
                    AbemaGuestBootstrapOperation.GUEST_ONLY -> RouteSession.createAbemaGuest(active.owner!!.profile, active.preparation)
                    AbemaGuestBootstrapOperation.VERIFY_PROFILE -> RouteSession.createAbemaGuestProfile(active.owner!!.profile, active.preparation)
                    AbemaGuestBootstrapOperation.VERIFY_EPISODE -> RouteSession.createAbemaGuestEpisode(active.owner!!.profile, active.preparation)
                }
                active.route.set(route)
                check(sameOwner(active))
                val files = linkedMapOf<String, ByteArray>()
                for ((name, identity) in listOf("bundle" to AbemaPublicBundleIdentity(),
                    "support" to AbemaPublicBundleIdentity.bootstrapSupport(),
                    "legacy" to AbemaPublicBundleIdentity.legacySource(),
                    "utilities" to AbemaPublicBundleIdentity.sourceUtilities())) {
                    val http = AbemaPublicBundleHttp(canRun = { sameOwner(active) }, identity = identity,
                        open = route::open, onFailure = { diagnostics.report(FailureStage.BUNDLE_DISCONNECT, it) })
                    active.download.set(http)
                    try {
                        files["/_tachiai/guest/$name.js"] = AbemaPublicBundleCache(File(cacheDir, "abema-helper-bundle"),
                            { sameOwner(active) }, identity).prepare(http::fetch).bytes
                    } finally { active.download.compareAndSet(http, null); http.close() }
                }
                files["/_tachiai/guest/index.html"] = assets.open("abema/guest-bootstrap.html").use { it.readBytes() }
                files["/_tachiai/guest/control.js"] = assets.open("abema/guest-bootstrap.js").use { it.readBytes() }
                files["/_tachiai/guest/initialize.js"] = assets.open("abema/guest-bootstrap-initialize.js")
                    .bufferedReader().use { it.readText() }.replace("__NONCE__", active.nonce)
                    .replace("__OPERATION__", operation.name).toByteArray(Charsets.UTF_8)
                check(sameOwner(active))
                handler.post {
                    if (current(active)) webRoute.install(route, { applied ->
                        if (!applied || !admission.routeApplied(active.run, foreground.get()) || attempt !== active) {
                            if (attempt === active) stop("ROUTE_REFUSED")
                        } else verifyThen(active) { openRuntime(active, files) }
                    }, diagnostics)
                }
            } catch (error: Exception) {
                if (current(active)) diagnostics.report(FailureStage.CATALOG_LOAD, error)
                handler.post { if (attempt === active) stop("PREPARATION_REFUSED") }
            } finally {
                // A cancelled route creation can finish after the cleanup wait.
                // Its producer still owns any late resource and must close it.
                if (!current(active) && !active.retainRoute.get()) {
                    try { active.route.getAndSet(null)?.close() } catch (error: Exception) {
                        active.cleanupFailed.set(true); diagnostics.report(FailureStage.ROUTE_BACKEND_CLOSE, error)
                    }
                }
                active.prepared.countDown()
            }
        }
    }

    private fun verifyThen(active: Attempt, next: () -> Unit) {
        worker.execute {
            val valid = runCatching { sameOwner(active) }.onFailure {
                diagnostics.report(FailureStage.CATALOG_LOAD, it)
            }.getOrDefault(false)
            handler.post { if (current(active)) { if (valid) next() else stop("OWNER_CHANGED") } }
        }
    }

    @SuppressLint("MissingOnRenderProcessGone")
    private fun openRuntime(active: Attempt, files: Map<String, ByteArray>) {
        if (!runtimeCurrent(active)) return
        var documentStarted = false
        val view = WebView(this)
        active.view = view
        configureAbemaGuestWebView(view)
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean = true
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, result: android.os.Message) = false
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsPrompt(view: WebView, url: String, message: String, value: String?, result: JsPromptResult): Boolean { result.cancel(); return true }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!runtimeCallback(active, view) {}) return true
                if (request.isForMainFrame && !documentStarted && request.url.toString() == AbemaGuestBootstrapPolicy.PAGE) return false
                runtimeCallback(active, view) { stop(active, "NAVIGATION_REFUSED") }; return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = runCatching { URI(request.url.toString()) }.getOrNull()
                val allowed = runtimeCurrent(active) && uri != null &&
                    runCatching { sameOwner(active) }.getOrDefault(false) &&
                    active.requests.admit(uri, request.isForMainFrame, request.method,
                        AbemaGuestBootstrapPolicy.corsHeaders(request.method) { request.requestHeaders })
                if (!allowed) {
                    handler.post { runtimeCallback(active, view) { stop(active, "RESOURCE_REFUSED") } }
                    return refused()
                }
                if (uri.toString() == AbemaGuestBootstrapPolicy.GUEST) return null
                if (AbemaGuestBootstrapPolicy.isProfile(uri)) return null
                if (AbemaGuestBootstrapPolicy.isEpisode(uri)) return null
                val bytes = files[uri.path] ?: return refused()
                return WebResourceResponse(if (request.isForMainFrame) "text/html" else "application/javascript", "UTF-8",
                    200, "OK", mapOf("Content-Security-Policy" to AbemaGuestBootstrapPolicy.contentSecurityPolicy(active.operation),
                        "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), ByteArrayInputStream(bytes))
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                runtimeCallback(active, view) {
                    if (url != AbemaGuestBootstrapPolicy.PAGE || documentStarted) stop(active, "NAVIGATION_REFUSED")
                    else documentStarted = true
                }
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                runtimeCallback(active, view) { if (url != AbemaGuestBootstrapPolicy.PAGE) stop(active, "NAVIGATION_REFUSED") }
            }
            override fun onReceivedHttpAuthRequest(view: WebView, auth: HttpAuthHandler, host: String, realm: String) {
                val route = active.route.get()
                if (runtimeCallback(active, view) {} && route?.matchesProxyChallenge(host, realm) == true)
                    auth.proceed(route.proxyUsername, route.proxyPassword) else auth.cancel()
            }
            override fun onReceivedSslError(view: WebView, auth: SslErrorHandler, error: SslError) {
                auth.cancel(); runtimeCallback(active, view) { stop(active, "TLS_REFUSED") }
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                runtimeCallback(active, view) { stop(active, "RENDERER_GONE") }; return true
            }
        }
        // Attached owned context only; no provider document or media element.
        host.addView(view, LinearLayout.LayoutParams(1, 1))
        view.loadUrl(AbemaGuestBootstrapPolicy.PAGE)
        poll(active)
    }
    private fun refused() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))

    private fun runtimeCallback(active: Attempt, view: WebView, action: () -> Unit): Boolean =
        admission.runtimeCallback(active.run, foreground.get(), attempt === active && active.view === view, action)

    private fun stop(active: Attempt, category: String) {
        if (attempt === active) stop(category)
    }

    private fun poll(active: Attempt) {
        if (!runtimeCurrent(active)) return
        val view = active.view ?: return
        if (view.url != AbemaGuestBootstrapPolicy.PAGE) { handler.postDelayed({ poll(active) }, 100); return }
        view.evaluateJavascript("window['__tachiaiGuest_${active.nonce}']?.status() || null") { raw ->
            if (!runtimeCurrent(active) || active.view !== view) return@evaluateJavascript
            val value = runCatching {
                val text = raw?.takeIf { it.length <= 256 }?.let { JSONTokener(it).nextValue() as? String }
                text?.let(::JSONObject)?.takeIf { it.length() == 2 }
            }.getOrNull()
            val state = value?.opt("state") as? String
            val failure = value?.opt("failure") as? String
            when {
                state == "PREPARED" && failure == "NONE" && active.operation == AbemaGuestBootstrapOperation.GUEST_ONLY ->
                    verifyThen(active) { stop("PREPARED") }
                state == "GUEST_PROFILE_BOUND" && failure == "NONE" && active.operation == AbemaGuestBootstrapOperation.VERIFY_PROFILE ->
                    verifyThen(active) { stop("GUEST_PROFILE_BOUND") }
                state == "EXACT_EPISODE_METADATA" && failure == "NONE" && active.operation == AbemaGuestBootstrapOperation.VERIFY_EPISODE ->
                    verifyThen(active) { stop("EXACT_EPISODE_METADATA") }
                state == "REFUSED" && failure in setOf("HTTP", "RESPONSE", "POLICY", "RUNTIME", "TIMEOUT",
                    "PROFILE_HTTP", "PROFILE_RESPONSE", "PROFILE_TIMEOUT", "EPISODE_RESPONSE", "EPISODE_TIMEOUT",
                    "EPISODE_TRANSPORT", "EPISODE_REDIRECT", "EPISODE_HTTP_401", "EPISODE_HTTP_403", "EPISODE_HTTP_404",
                    "EPISODE_HTTP_410", "EPISODE_HTTP_429", "EPISODE_HTTP_3XX", "EPISODE_HTTP_4XX", "EPISODE_HTTP_5XX",
                    "EPISODE_HTTP_OTHER") -> stop("GUEST_$failure")
                raw == null || raw == "null" || (state in setOf("WAITING", "MODULES", "GUEST", "PROFILE", "EPISODE") && failure == "NONE") ->
                    handler.postDelayed({ poll(active) }, 100)
                else -> stop("STATUS_REFUSED")
            }
        }
    }

    private fun stop(category: String) {
        val active = attempt ?: return
        if (!admission.beginCleanup(active.run)) return
        attempt = null // Synchronously deny all requests and queued callbacks.
        handler.removeCallbacksAndMessages(null)
        active.preparation.cancel()
        cleanupWorkers.execute {
            try { active.download.get()?.close() } catch (error: Exception) {
                active.cleanupFailed.set(true); diagnostics.report(FailureStage.BUNDLE_TRANSPORT_CLOSE, error)
            } finally { active.cancellationFinished.countDown() }
        }
        val clearDispatched = admission.destroyBeforeRouteClear(active.run, destroy = {
            var destroyed = true
            active.view?.let { view ->
                if (!diagnostics.cleanup(FailureStage.HELPER_STOP) {
                    if (view.url == AbemaGuestBootstrapPolicy.PAGE)
                        view.evaluateJavascript("window['__tachiaiGuest_${active.nonce}']?.stop()", null)
                }) active.cleanupFailed.set(true)
                for ((stage, close) in listOf<Pair<FailureStage, () -> Unit>>(
                    FailureStage.WEBVIEW_STOP to { view.stopLoading() }, FailureStage.WEBVIEW_PAUSE to { view.onPause() },
                    FailureStage.WEBVIEW_DETACH to { host.removeView(view) },
                )) if (!diagnostics.cleanup(stage, close)) active.cleanupFailed.set(true)
                destroyed = diagnostics.cleanup(FailureStage.WEBVIEW_DESTROY) { view.webChromeClient = null; view.destroy() }
                if (destroyed) active.view = null
            }
            if (!destroyed) { active.retainRoute.set(true); retainedAttempt = active }
            destroyed
        }, clear = {
            controls()
            status.text = "Ending guest preparation and confirming route cleanup."
            // Timeout and completion are main-thread scoped. Admission cannot
            // unlock B before A's handler callbacks and status are finished.
            handler.postDelayed({
                admission.finishCleanup(active.run, false, { handler.removeCallbacksAndMessages(null) }) {
                    if (!isDestroyed) { outcome("CLEANUP_FAILED"); controls() }
                }
            }, 20_000)
            webRoute.clear(diagnostics) {
                cleanupWorkers.execute {
                    try {
                        if (!active.prepared.await(15, TimeUnit.SECONDS) ||
                            !active.cancellationFinished.await(5, TimeUnit.SECONDS)) active.cleanupFailed.set(true)
                    } catch (error: Exception) {
                        active.cleanupFailed.set(true); diagnostics.report(FailureStage.ROUTE_CANCEL, error)
                    }
                    try { active.route.getAndSet(null)?.close() } catch (error: Exception) {
                        active.cleanupFailed.set(true); diagnostics.report(FailureStage.ROUTE_BACKEND_CLOSE, error)
                    }
                    if (!active.preparation.cleanupConfirmed) active.cleanupFailed.set(true)
                    val confirmed = !active.cleanupFailed.get()
                    handler.post {
                        admission.finishCleanup(active.run, confirmed, { handler.removeCallbacksAndMessages(null) }) { completed ->
                            if (!isDestroyed) { outcome(if (completed) category else "CLEANUP_FAILED"); controls() }
                        }
                    }
                }
            }
        })
        if (!clearDispatched) {
            active.retainRoute.set(true); retainedAttempt = active
            active.cleanupFailed.set(true)
            outcome("CLEANUP_FAILED"); controls()
        }
    }

    private fun refreshAdmission() {
        controls()
        if (foreground.get() && admission.cleanupPending && !isDestroyed)
            handler.postDelayed(::refreshAdmission, 500)
    }
    override fun onResume() { super.onResume(); foreground.set(true); refreshAdmission() }
    override fun onPause() { foreground.set(false); stop("CANCELLED"); controls(); super.onPause() }
    override fun onDestroy() { stop("CANCELLED"); worker.shutdown(); super.onDestroy() }
}
