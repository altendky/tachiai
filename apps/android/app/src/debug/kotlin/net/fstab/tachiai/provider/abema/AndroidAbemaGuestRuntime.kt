package net.fstab.tachiai.provider.abema

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.*
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.platform.network.*
import org.json.JSONObject

internal class AndroidAbemaGuestMain(private val handler: Handler = Handler(Looper.getMainLooper())) : AbemaGuestRuntimeMain {
    override fun checkThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
    override fun post(token: Any, action: () -> Unit) { handler.postAtTime(action, token, SystemClock.uptimeMillis()) }
    override fun delay(token: Any, millis: Long, action: () -> Unit) {
        handler.postAtTime(action, token, SystemClock.uptimeMillis() + millis.coerceAtLeast(0))
    }
    override fun remove(token: Any) { handler.removeCallbacksAndMessages(token) }
}

// The activity and service MUST use this same process-owned instance. Retained
// uncertain views/routes and cleanup workers outlive either component.
internal object AbemaGuestRuntimeProcess {
    private var coordinator: AbemaGuestRuntimeCoordinator? = null
    fun get(context: Context): AbemaGuestRuntimeCoordinator {
        check(Looper.myLooper() == Looper.getMainLooper())
        configureAbemaGuestBootstrapProfile(context)
        return coordinator ?: AbemaGuestRuntimeCoordinator(AndroidAbemaGuestEnvironment(context.applicationContext),
            AndroidAbemaGuestMain(), Executors.newSingleThreadExecutor(), Executors.newFixedThreadPool(2),
            SystemClock::elapsedRealtime).also { coordinator = it }
    }
}

// One Preparation owns one route. The close claim is distinct from a successful
// receipt because RouteSession's own seal is not proof that cleanup completed.
internal class AbemaGuestRouteReceipt(private val closeRoute: (RouteSession) -> Unit = { it.close() }) {
    val route = AtomicReference<RouteSession?>()
    private val closed = AtomicBoolean()
    private val closeClaimed = AtomicBoolean()
    private val closeConfirmed = AtomicBoolean()
    val cleanupConfirmed get() = !closed.get() ||
        route.get() == null && (!closeClaimed.get() || closeConfirmed.get())
    fun own(owned: RouteSession) {
        route.set(owned)
        if (closed.get()) closeOwned(owned)
    }
    private fun closeOwned(owned: RouteSession) {
        if (!closeClaimed.compareAndSet(false, true)) return
        closeRoute(owned)
        closeConfirmed.set(true)
        route.compareAndSet(owned, null)
    }
    fun close() { closed.set(true); route.get()?.let(::closeOwned) }
}

internal class AndroidAbemaGuestEnvironment(private val context: Context) : AbemaGuestRuntimeEnvironment {
    private val diagnostics = FailureDiagnostics.create(context)
    private val webRoute = AbemaWebViewRoute()
    private class Owner(val saved: AbemaCatalogRouteOwner) : AbemaGuestRuntimeOwner {
        override val proof get() = saved.proof()
        override fun toString() = "AbemaGuestRuntimeOwner(redacted)"
    }
    private class Preparation(val preparing: RoutePreparation) : AbemaGuestRuntimePreparation {
        private val routeReceipt = AbemaGuestRouteReceipt()
        val route get() = routeReceipt.route
        val download = AtomicReference<AbemaPublicBundleHttp?>()
        override fun cancel() { preparing.cancel() }
        override fun cancelTransport() { download.get()?.close() }
        override val cleanupConfirmed get() = preparing.cleanupConfirmed && routeReceipt.cleanupConfirmed &&
            download.get()?.cleanupConfirmed != false
        // Late preparation after the cleanup seal closes its route through the
        // same receipt. Busy/failed close keeps the actual route retained.
        fun own(route: RouteSession) = routeReceipt.own(route)
        override fun close() = routeReceipt.close()
    }
    private fun savedInstances() = providerInstanceStore(context).read { legacyProviderSettings(context) }
    private fun readSavedOwner(id: String) = readAbemaCatalogRouteOwner(id, ::savedInstances,
        { connectionProfileStore(context).selected(it) })
    override fun readOwner(instanceId: String): AbemaGuestRuntimeOwner = Owner(readSavedOwner(instanceId))
    override fun sameOwner(owner: AbemaGuestRuntimeOwner): Boolean {
        val captured = (owner as Owner).saved
        return captured.sameOwnership(readSavedOwner(captured.instance.id))
    }
    override fun newPreparation(): AbemaGuestRuntimePreparation = Preparation(RoutePreparation(diagnostics))
    override fun prepare(owner: AbemaGuestRuntimeOwner, request: AbemaGuestRuntimeRequest,
        preparation: AbemaGuestRuntimePreparation, canRequest: () -> Boolean): Map<String, ByteArray> {
        check(Looper.myLooper() != Looper.getMainLooper())
        val prepared = preparation as Preparation
        val saved = (owner as Owner).saved
        check(canRequest())
        val route = when (request.operation) {
            AbemaGuestBootstrapOperation.GUEST_ONLY -> RouteSession.createAbemaGuest(saved.profile, prepared.preparing)
            AbemaGuestBootstrapOperation.VERIFY_PROFILE, AbemaGuestBootstrapOperation.PREPARE_ACCOUNT_VALIDATION ->
                RouteSession.createAbemaGuestProfile(saved.profile, prepared.preparing)
            AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION -> RouteSession.createAbemaGuestDeviceAuthorization(saved.profile, prepared.preparing)
            AbemaGuestBootstrapOperation.OBSERVE_DEVICE_TOKEN -> RouteSession.createAbemaGuestDeviceTokenObservation(saved.profile, prepared.preparing)
            AbemaGuestBootstrapOperation.PREPARE_ACCOUNT_LINK -> RouteSession.createAbemaAccountBootstrap(saved.profile, prepared.preparing)
            AbemaGuestBootstrapOperation.VERIFY_EPISODE, AbemaGuestBootstrapOperation.FETCH_EPISODE ->
                RouteSession.createAbemaGuestEpisode(saved.profile, prepared.preparing)
            AbemaGuestBootstrapOperation.FETCH_SERIES -> RouteSession.createAbemaGuestSeries(saved.profile, prepared.preparing)
            AbemaGuestBootstrapOperation.FETCH_SLOT -> RouteSession.createAbemaGuestSlot(saved.profile, prepared.preparing)
            AbemaGuestBootstrapOperation.FETCH_SCHEDULE -> RouteSession.createAbemaGuestSchedule(saved.profile, prepared.preparing)
        }
        prepared.own(route)
        check(canRequest())
        val files = linkedMapOf<String, ByteArray>()
        for ((name, identity) in listOf("bundle" to AbemaPublicBundleIdentity(),
            "support" to AbemaPublicBundleIdentity.bootstrapSupport(), "legacy" to AbemaPublicBundleIdentity.legacySource(),
            "utilities" to AbemaPublicBundleIdentity.sourceUtilities())) {
            val http = AbemaPublicBundleHttp(canRun = canRequest, identity = identity, open = route::open,
                onFailure = { diagnostics.report(FailureStage.BUNDLE_DISCONNECT, it) })
            prepared.download.set(http)
            try { files["/_tachiai/guest/$name.js"] = AbemaPublicBundleCache(File(context.cacheDir, "abema-helper-bundle"),
                canRequest, identity).prepare(http::fetch).bytes }
            finally { http.close(); prepared.download.compareAndSet(http, null) }
        }
        files["/_tachiai/guest/index.html"] = context.assets.open("abema/guest-bootstrap.html").use { it.readBytes() }
        files["/_tachiai/guest/control.js"] = context.assets.open("abema/guest-bootstrap.js").use { it.readBytes() }
        files["/_tachiai/guest/initialize.js"] = context.assets.open("abema/guest-bootstrap-initialize.js")
            .bufferedReader().use { it.readText() }.replace("__NONCE__", request.nonce)
            .replace("__OPERATION__", request.operation.name)
            .replace("__RESOURCE_ARGUMENT__", if (request.operation == AbemaGuestBootstrapOperation.FETCH_EPISODE)
                ", " + JSONObject.quote(request.episodeId) else if (request.operation == AbemaGuestBootstrapOperation.FETCH_SERIES)
                ", " + checkNotNull(request.seriesRequest).let { JSONObject().put("seriesId", it.seriesId).put("offset", it.offset)
                    .put("version", it.version ?: JSONObject.NULL).toString() }
                else if (request.operation == AbemaGuestBootstrapOperation.FETCH_SLOT)
                    ", " + checkNotNull(request.slotRequest).let { JSONObject().put("channelId", it.channelId).put("slotId", it.slotId).toString() }
                else "").toByteArray(Charsets.UTF_8)
        check(canRequest())
        return files
    }
    override fun install(preparation: AbemaGuestRuntimePreparation, callback: (Boolean) -> Unit) =
        webRoute.install(checkNotNull((preparation as Preparation).route.get()), callback, diagnostics)
    override fun clear(callback: () -> Unit) = webRoute.clear(diagnostics, callback)
    override fun report(error: Throwable) = diagnostics.report(FailureStage.CATALOG_LOAD, error)
    override fun newView(request: AbemaGuestRuntimeRequest, preparation: AbemaGuestRuntimePreparation,
        files: Map<String, ByteArray>, canRequest: () -> Boolean, currentView: (AbemaGuestRuntimeView) -> Boolean,
        failed: (AbemaGuestRuntimeView, String) -> Unit): AbemaGuestRuntimeView =
        OwnedGuestView(context, request, preparation as Preparation, files, canRequest, currentView, failed, diagnostics)

    @SuppressLint("MissingOnRenderProcessGone")
    private class OwnedGuestView(context: Context, private val request: AbemaGuestRuntimeRequest,
        private val preparation: Preparation, private val files: Map<String, ByteArray>,
        private val canRequest: () -> Boolean, private val current: (AbemaGuestRuntimeView) -> Boolean,
        private val failed: (AbemaGuestRuntimeView, String) -> Unit, private val diagnostics: FailureReporter) : AbemaGuestRuntimeView {
        private val view = WebView(context)
        private val admission = AbemaGuestRequestAdmission(request.operation, request.policyEpisodeId, request.seriesRequest, request.slotRequest)
        private var documentStarted = false
        private var cleanupFailed = false
        override val cleanupConfirmed get() = !cleanupFailed
        private fun configure() {
            configureAbemaGuestWebView(view)
            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage) = true
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, result: android.os.Message) = false
                override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
                override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
                override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
                override fun onJsPrompt(view: WebView, url: String, message: String, value: String?, result: JsPromptResult): Boolean { result.cancel(); return true }
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, resource: WebResourceRequest): Boolean {
                    if (!current(this@OwnedGuestView)) return true
                    if (resource.isForMainFrame && !documentStarted && resource.url.toString() == AbemaGuestBootstrapPolicy.PAGE) return false
                    failed(this@OwnedGuestView, "NAVIGATION_REFUSED"); return true
                }
                override fun shouldInterceptRequest(view: WebView, resource: WebResourceRequest): WebResourceResponse? {
                    val uri = runCatching { URI(resource.url.toString()) }.getOrNull()
                    val allowed = current(this@OwnedGuestView) && uri != null && runCatching(canRequest).getOrDefault(false) &&
                        admission.admit(uri, resource.isForMainFrame, resource.method,
                            AbemaGuestBootstrapPolicy.corsHeaders(resource.method) { resource.requestHeaders })
                    if (!allowed) { failed(this@OwnedGuestView, "RESOURCE_REFUSED"); return refused() }
                    if (uri.toString() == AbemaGuestBootstrapPolicy.GUEST || uri.toString() == AbemaGuestBootstrapPolicy.CHALLENGE ||
                        uri.toString() == AbemaGuestBootstrapPolicy.DEVICE_TOKEN || AbemaGuestBootstrapPolicy.isProfile(uri) ||
                        AbemaGuestBootstrapPolicy.isEpisode(uri, request.policyEpisodeId) || uri.toString() == request.seriesRequest?.url() ||
                        uri.toString() == request.slotRequest?.url() ||
                        request.operation == AbemaGuestBootstrapOperation.FETCH_SCHEDULE && uri.toString() == AbemaGuestBootstrapPolicy.SCHEDULE) return null
                    val bytes = files[uri.path] ?: return refused()
                    return WebResourceResponse(if (resource.isForMainFrame) "text/html" else "application/javascript", "UTF-8",
                        200, "OK", mapOf("Content-Security-Policy" to AbemaGuestBootstrapPolicy.contentSecurityPolicy(request.operation,
                            request.policyEpisodeId, request.seriesRequest, request.slotRequest), "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), ByteArrayInputStream(bytes))
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (!current(this@OwnedGuestView)) return
                    if (url != AbemaGuestBootstrapPolicy.PAGE || documentStarted) failed(this@OwnedGuestView, "NAVIGATION_REFUSED")
                    else documentStarted = true
                }
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    if (current(this@OwnedGuestView) && url != AbemaGuestBootstrapPolicy.PAGE) failed(this@OwnedGuestView, "NAVIGATION_REFUSED")
                }
                override fun onReceivedHttpAuthRequest(view: WebView, auth: HttpAuthHandler, host: String, realm: String) {
                    val route = preparation.route.get()
                    if (current(this@OwnedGuestView) && route?.matchesProxyChallenge(host, realm) == true)
                        auth.proceed(route.proxyUsername, route.proxyPassword) else auth.cancel()
                }
                override fun onReceivedSslError(view: WebView, auth: SslErrorHandler, error: SslError) {
                    auth.cancel(); if (current(this@OwnedGuestView)) failed(this@OwnedGuestView, "TLS_REFUSED")
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (current(this@OwnedGuestView)) failed(this@OwnedGuestView, "RENDERER_GONE"); return true
                }
            }
        }
        private fun refused() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        override fun open() { configure(); view.loadUrl(AbemaGuestBootstrapPolicy.PAGE) }
        override fun status(callback: (String?) -> Unit) {
            if (view.url != AbemaGuestBootstrapPolicy.PAGE) { callback(null); return }
            view.evaluateJavascript("window['__tachiaiGuest_${request.nonce}']?.status() || null", callback)
        }
        override fun metadata(callback: (String?) -> Unit) {
            check(request.operation in setOf(AbemaGuestBootstrapOperation.FETCH_EPISODE, AbemaGuestBootstrapOperation.FETCH_SERIES,
                AbemaGuestBootstrapOperation.FETCH_SLOT, AbemaGuestBootstrapOperation.FETCH_SCHEDULE))
            view.evaluateJavascript("window['__tachiaiGuest_${request.nonce}']?.metadata() || null", callback)
        }
        override fun accountLink(callback: (String?) -> Unit) {
            check(request.operation == AbemaGuestBootstrapOperation.PREPARE_ACCOUNT_LINK)
            if (view.url != AbemaGuestBootstrapPolicy.PAGE) { callback(null); return }
            view.evaluateJavascript("window['__tachiaiGuest_${request.nonce}']?.accountLink() || null", callback)
        }
        override fun accountValidation(callback: (String?) -> Unit) {
            check(request.operation == AbemaGuestBootstrapOperation.PREPARE_ACCOUNT_VALIDATION)
            if (view.url != AbemaGuestBootstrapPolicy.PAGE) { callback(null); return }
            view.evaluateJavascript("window['__tachiaiGuest_${request.nonce}']?.accountValidation() || null", callback)
        }
        override fun destroy(): Boolean {
            var failed = false
            if (!diagnostics.cleanup(FailureStage.HELPER_STOP) {
                if (view.url == AbemaGuestBootstrapPolicy.PAGE) view.evaluateJavascript("window['__tachiaiGuest_${request.nonce}']?.stop()", null)
            }) failed = true
            for ((stage, stop) in listOf<Pair<FailureStage, () -> Unit>>(
                FailureStage.WEBVIEW_STOP to { view.stopLoading() }, FailureStage.WEBVIEW_PAUSE to { view.onPause() },
            )) if (!diagnostics.cleanup(stage, stop)) failed = true
            val destroyed = diagnostics.cleanup(FailureStage.WEBVIEW_DESTROY) { view.webChromeClient = null; view.destroy() }
            cleanupFailed = failed || !destroyed
            return destroyed
        }
    }
}
