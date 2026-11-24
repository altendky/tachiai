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
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.provider.abema.account.*
import org.json.JSONObject

internal class AndroidAbemaMetadataMain(private val handler: Handler = Handler(Looper.getMainLooper())) : AbemaMetadataRuntimeMain {
    override fun checkThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
    override fun post(token: Any, action: () -> Unit) { handler.postAtTime(action, token, SystemClock.uptimeMillis()) }
    override fun delay(token: Any, millis: Long, action: () -> Unit) {
        handler.postAtTime(action, token, SystemClock.uptimeMillis() + millis.coerceAtLeast(0))
    }
    override fun remove(token: Any) { handler.removeCallbacksAndMessages(token) }
}

// The activity and service MUST use this same process-owned instance. Retained
// uncertain views/routes and cleanup workers outlive either component.
internal object AbemaMetadataRuntimeProcess {
    private var coordinator: AbemaMetadataRuntimeCoordinator? = null
    fun get(context: Context): AbemaMetadataRuntimeCoordinator {
        check(Looper.myLooper() == Looper.getMainLooper())
        configureAbemaMetadataRuntimeProfile(context)
        return coordinator ?: AbemaMetadataRuntimeCoordinator(AndroidAbemaMetadataEnvironment(context.applicationContext),
            AndroidAbemaMetadataMain(), Executors.newSingleThreadExecutor(), Executors.newFixedThreadPool(2),
            SystemClock::elapsedRealtime).also { coordinator = it }
    }
}

// One Preparation owns one route. The close claim is distinct from a successful
// receipt because RouteSession's own seal is not proof that cleanup completed.
internal class AbemaMetadataRouteReceipt(private val closeRoute: (RouteSession) -> Unit = { it.close() }) {
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

internal class AndroidAbemaMetadataEnvironment(private val context: Context) : AbemaMetadataRuntimeEnvironment {
    private val diagnostics = FailureDiagnostics.create(context)
    private val webRoute = AbemaWebViewRoute()
    private class Owner(val saved: AbemaCatalogRouteOwner) : AbemaMetadataRuntimeOwner {
        override val proof get() = saved.proof()
        override fun toString() = "AbemaMetadataRuntimeOwner(redacted)"
    }
    private class Preparation(val preparing: RoutePreparation) : AbemaMetadataRuntimePreparation {
        val timing = AbemaPreparationTiming(SystemClock::elapsedRealtime) { android.util.Log.i(AbemaPreparationTiming.TAG, it) }
        private val routeReceipt = AbemaMetadataRouteReceipt()
        val route get() = routeReceipt.route
        val download = AtomicReference<AbemaPublicBundleHttp?>()
        val account = AtomicReference<AndroidAbemaAccountAccessClient?>()
        val accountBound = AtomicBoolean()
        private val accountClosed = AtomicBoolean(true)
        private val initializeLock = Any()
        private var initialize: ByteArray? = null
        private var initializeSealed = false
        val projectionStarted = AtomicBoolean()
        val projectionDone = CountDownLatch(1)
        fun ownAccount(client: AndroidAbemaAccountAccessClient) {
            accountClosed.set(false); check(account.compareAndSet(null, client))
            if (preparing.isCancelled) client.close()
        }
        private fun sealInitialize() {
            val bytes = synchronized(initializeLock) {
                initializeSealed = true
                initialize.also { initialize = null }
            }
            bytes?.fill(0)
        }
        fun ownInitialize(bytes: ByteArray) {
            val admitted = synchronized(initializeLock) {
                if (initializeSealed || initialize != null || preparing.isCancelled) false
                else { initialize = bytes; true }
            }
            if (!admitted) { bytes.fill(0); throw AbemaAccountException(AbemaAccountFailure.CANCELLED) }
            // Cancellation after ownership seals/wipes the same buffer; a late
            // prepare cannot repopulate it or hand private bytes to a new view.
            if (synchronized(initializeLock) { initializeSealed || initialize !== bytes } || preparing.isCancelled) {
                sealInitialize(); throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            }
        }
        override fun cancel() { sealInitialize(); preparing.cancel(); account.get()?.close() }
        // The preparation thread's own close confirms the download receipt.
        override fun cancelTransport() { try { download.get()?.cancel() } finally { account.get()?.close() } }
        override fun checkAccess() = account.get()?.checkWorker() != false
        override val cleanupConfirmed get() = preparing.cleanupConfirmed && routeReceipt.cleanupConfirmed &&
            download.get()?.cleanupConfirmed != false && accountClosed.get()
        // Late preparation after the cleanup seal closes its route through the
        // same receipt. Busy/failed close keeps the actual route retained.
        fun own(route: RouteSession) = routeReceipt.own(route)
        override fun close() {
            check(Looper.myLooper() != Looper.getMainLooper())
            sealInitialize()
            try {
                if (projectionStarted.get() && !projectionDone.await(5, TimeUnit.SECONDS))
                    throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
                if (account.get()?.awaitCleanup() == false) throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
                accountClosed.set(true)
                routeReceipt.close()
            } finally { sealInitialize() }
        }
    }
    private fun savedInstances() = providerInstanceStore(context).read { legacyProviderSettings(context) }
    private fun readSavedOwner(id: String, expectedProof: String?) = captureAbemaCatalogRouteOwner(id, ::savedInstances,
        { connectionProfileStore(context).snapshot(it) }, expectedProof)
    override fun readOwner(instanceId: String): AbemaMetadataRuntimeOwner = readOwner(instanceId, null)
    override fun readOwner(instanceId: String, expectedProof: String?): AbemaMetadataRuntimeOwner = Owner(readSavedOwner(instanceId, expectedProof))
    override fun sameOwner(owner: AbemaMetadataRuntimeOwner): Boolean {
        val captured = (owner as Owner).saved
        return captured.sameOwnership(readSavedOwner(captured.instance.id, captured.proof()))
    }
    override fun newPreparation(): AbemaMetadataRuntimePreparation = Preparation(RoutePreparation(diagnostics))
    override fun prepare(owner: AbemaMetadataRuntimeOwner, request: AbemaMetadataRuntimeRequest,
        preparation: AbemaMetadataRuntimePreparation, canRequest: () -> Boolean): Map<String, ByteArray> {
        check(Looper.myLooper() != Looper.getMainLooper())
        val prepared = preparation as Preparation
        val saved = (owner as Owner).saved
        check(canRequest())
        prepared.timing.mark(AbemaPreparationPhase.OWNER_READ)
        val discovery = request.operation in AbemaMetadataRuntimePolicy.DISCOVERY
        val accountContext = if (discovery) {
            val resource = when (request.operation) {
                AbemaMetadataRuntimeOperation.FETCH_EPISODE -> AbemaMetadataRuntimePolicy.episodeUrl(checkNotNull(request.episodeId))
                AbemaMetadataRuntimeOperation.FETCH_SERIES -> checkNotNull(request.seriesRequest).key()
                AbemaMetadataRuntimeOperation.FETCH_SLOT -> checkNotNull(request.slotRequest).let { "${it.channelId}|${it.url()}" }
                AbemaMetadataRuntimeOperation.FETCH_SCHEDULE -> AbemaMetadataRuntimePolicy.SCHEDULE + "|" + request.scheduleRequest.channelId.orEmpty()
                AbemaMetadataRuntimeOperation.FETCH_SEARCH -> checkNotNull(request.searchRequest).url()
                AbemaMetadataRuntimeOperation.FETCH_REPLAY_LENGTHS -> checkNotNull(request.replayRequest).key()
                else -> error("Discovery operation refused")
            }
            val source = MessageDigest.getInstance("SHA-256").digest(
                "${request.instanceId}|${owner.proof}|${request.operation.name}|$resource".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val client = AndroidAbemaAccountAccessClient(context, request.instanceId, owner.proof, source,
                request.deadlineElapsedMs, false) { !prepared.preparing.isCancelled && request.canRemainActive() }
            prepared.ownAccount(client)
            val access = client.acquire()
            if (access.status == AbemaAccountAccessIpc.Status.FAILURE)
                throw AbemaAccountException(access.failure ?: AbemaAccountFailure.REFUSED)
            check(canRequest() && client.current())
            prepared.timing.mark(AbemaPreparationPhase.ACCOUNT_ACQUIRED)
            when (access.status) {
                AbemaAccountAccessIpc.Status.ACCOUNT -> {
                    val grant = checkNotNull(access.grant)
                    prepared.accountBound.set(true)
                    JSONObject().put("userId", grant.userId).put("bearer", grant.bearer).put("expiresAtMs", grant.expiresAtMs).toString()
                }
                AbemaAccountAccessIpc.Status.GUEST -> null
                AbemaAccountAccessIpc.Status.FAILURE -> throw AbemaAccountException(access.failure ?: AbemaAccountFailure.REFUSED)
            }
        } else null
        val route = when (request.operation) {
            AbemaMetadataRuntimeOperation.GUEST_ONLY -> RouteSession.createAbemaGuest(saved.profile, prepared.preparing)
            AbemaMetadataRuntimeOperation.VERIFY_PROFILE, AbemaMetadataRuntimeOperation.PREPARE_ACCOUNT_VALIDATION ->
                RouteSession.createAbemaGuestProfile(saved.profile, prepared.preparing)
            AbemaMetadataRuntimeOperation.VERIFY_DEVICE_AUTHORIZATION -> RouteSession.createAbemaGuestDeviceAuthorization(saved.profile, prepared.preparing)
            AbemaMetadataRuntimeOperation.OBSERVE_DEVICE_TOKEN -> RouteSession.createAbemaGuestDeviceTokenObservation(saved.profile, prepared.preparing)
            AbemaMetadataRuntimeOperation.PREPARE_ACCOUNT_LINK -> RouteSession.createAbemaAccountBootstrap(saved.profile, prepared.preparing)
            AbemaMetadataRuntimeOperation.VERIFY_EPISODE, AbemaMetadataRuntimeOperation.FETCH_EPISODE ->
                RouteSession.createAbemaGuestEpisode(saved.profile, prepared.preparing)
            AbemaMetadataRuntimeOperation.FETCH_SERIES -> RouteSession.createAbemaGuestSeries(saved.profile, prepared.preparing)
            AbemaMetadataRuntimeOperation.FETCH_SLOT -> RouteSession.createAbemaGuestSlot(saved.profile, prepared.preparing)
            AbemaMetadataRuntimeOperation.FETCH_SCHEDULE -> RouteSession.createAbemaGuestSchedule(saved.profile, prepared.preparing)
            AbemaMetadataRuntimeOperation.FETCH_SEARCH -> RouteSession.createAbemaGuestSearch(saved.profile, prepared.preparing)
            AbemaMetadataRuntimeOperation.FETCH_REPLAY_LENGTHS -> RouteSession.createAbemaGuestReplayLength(saved.profile, prepared.preparing)
        }
        prepared.own(route)
        check(canRequest())
        prepared.timing.mark(AbemaPreparationPhase.ROUTE_LEASED)
        val files = linkedMapOf<String, ByteArray>()
        // Chunked public-code reads/downloads check this between 8 KiB blocks;
        // a full saved-owner read per block dominated preparation (#629).
        val bundleAdmission = AbemaBundleAdmission(SystemClock::elapsedRealtime, live = {
            !prepared.preparing.isCancelled && request.canRemainActive() &&
                SystemClock.elapsedRealtime() < request.deadlineElapsedMs && prepared.account.get()?.current() != false
        }, owner = canRequest)
        for ((name, identity) in listOf("bundle" to AbemaPublicBundleIdentity(),
            "support" to AbemaPublicBundleIdentity.bootstrapSupport(), "legacy" to AbemaPublicBundleIdentity.legacySource(),
            "utilities" to AbemaPublicBundleIdentity.sourceUtilities())) {
            check(bundleAdmission.checkOwner())
            val http = AbemaPublicBundleHttp(canRun = bundleAdmission::current,
                identity = identity, open = { url -> check(bundleAdmission.checkOwner() && prepared.checkAccess()); route.open(url) },
                onFailure = { diagnostics.report(FailureStage.BUNDLE_DISCONNECT, it) })
            prepared.download.set(http)
            try { files["/_tachiai/guest/$name.js"] = AbemaPublicBundleCache(File(context.cacheDir, "abema-helper-bundle"),
                bundleAdmission::current, identity) { prepared.timing.mark(it, identity.asset) }.prepare(http::fetch).bytes }
            finally { http.close(); prepared.download.compareAndSet(http, null) }
            prepared.timing.mark(AbemaPreparationPhase.BUNDLE_READY, identity.asset)
        }
        prepared.timing.mark(AbemaPreparationPhase.BUNDLES_READY)
        files["/_tachiai/guest/index.html"] = context.assets.open("abema/metadata-runtime.html").use { it.readBytes() }
        files["/_tachiai/guest/control.js"] = context.assets.open("abema/metadata-runtime.js").use { it.readBytes() }
        val resourceArgument = if (request.operation == AbemaMetadataRuntimeOperation.FETCH_EPISODE)
                ", " + JSONObject.quote(request.episodeId) else if (request.operation == AbemaMetadataRuntimeOperation.FETCH_SERIES)
                ", " + checkNotNull(request.seriesRequest).let { JSONObject().put("seriesId", it.seriesId).put("offset", it.offset)
                    .apply { it.seasonId?.let { season -> put("seasonId", season) }; it.groupId?.let { group -> put("groupId", group) }
                        it.viewingTypes?.let { types -> put("viewingTypes", org.json.JSONArray(types)) }
                        if (it.newest) put("order", "desc") }.toString() }
                else if (request.operation == AbemaMetadataRuntimeOperation.FETCH_SLOT)
                    ", " + checkNotNull(request.slotRequest).let { JSONObject().put("channelId", it.channelId).put("slotId", it.slotId).toString() }
                else if (request.operation == AbemaMetadataRuntimeOperation.FETCH_SEARCH)
                    ", " + checkNotNull(request.searchRequest).let { JSONObject().put("query", it.query).put("moduleId", it.moduleId ?: JSONObject.NULL)
                        .put("offset", it.offset).toString() }
                else if (request.operation == AbemaMetadataRuntimeOperation.FETCH_REPLAY_LENGTHS)
                    ", " + JSONObject().put("slots", org.json.JSONArray().apply { checkNotNull(request.replayRequest).slots.forEach {
                        put(JSONObject().put("channelId", it.channelId).put("slotId", it.slotId)) } }).toString()
                else if (request.operation == AbemaMetadataRuntimeOperation.FETCH_SCHEDULE && request.scheduleRequest.channelId != null)
                    ", " + JSONObject().put("channelId", request.scheduleRequest.channelId).toString()
                else if (accountContext != null) ", undefined" else ""
        val initialization = context.assets.open("abema/metadata-runtime-initialize.js")
            .bufferedReader().use { it.readText() }.replace("__NONCE__", request.nonce)
            .replace("__OPERATION__", request.operation.name)
            .replace("__RESOURCE_ARGUMENT__", resourceArgument + if (accountContext != null) ", $accountContext" else "")
            .toByteArray(Charsets.UTF_8)
        if (accountContext != null) prepared.ownInitialize(initialization)
        files["/_tachiai/guest/initialize.js"] = initialization
        check(canRequest())
        prepared.timing.mark(AbemaPreparationPhase.FILES_PREPARED)
        return files
    }
    override fun install(preparation: AbemaMetadataRuntimePreparation, callback: (Boolean) -> Unit) {
        val prepared = preparation as Preparation
        webRoute.install(checkNotNull(prepared.route.get()), { applied ->
            if (applied) prepared.timing.mark(AbemaPreparationPhase.ROUTE_INSTALLED)
            callback(applied)
        }, diagnostics)
    }
    override fun clear(callback: () -> Unit) = webRoute.clear(diagnostics, callback)
    override fun report(error: Throwable) {
        // The coordinator's PREPARATION_REFUSED hides why; an account refusal is a
        // closed category (EXPIRED, REFUSED, …) and safe to name in logcat.
        if (error is AbemaAccountException) android.util.Log.i("TachiaiAbemaGuestRuntime", "ACCOUNT_${error.category.name}")
        diagnostics.report(FailureStage.CATALOG_LOAD, error)
    }
    override fun newView(request: AbemaMetadataRuntimeRequest, preparation: AbemaMetadataRuntimePreparation,
        files: Map<String, ByteArray>, canRequest: () -> Boolean, currentView: (AbemaMetadataRuntimeView) -> Boolean,
        failed: (AbemaMetadataRuntimeView, String) -> Unit): AbemaMetadataRuntimeView =
        OwnedMetadataView(context, request, preparation as Preparation, files, canRequest, currentView, failed, diagnostics)

    @SuppressLint("MissingOnRenderProcessGone")
    private class OwnedMetadataView(context: Context, private val request: AbemaMetadataRuntimeRequest,
        private val preparation: Preparation, private val files: Map<String, ByteArray>,
        private val canRequest: () -> Boolean, private val current: (AbemaMetadataRuntimeView) -> Boolean,
        private val failed: (AbemaMetadataRuntimeView, String) -> Unit, private val diagnostics: FailureReporter) : AbemaMetadataRuntimeView {
        companion object { private val projectionWorker = Executors.newSingleThreadExecutor() }
        private val main = Handler(Looper.getMainLooper())
        private val view = WebView(context).also { preparation.timing.mark(AbemaPreparationPhase.WEBVIEW_CREATED) }
        private val admission = AbemaMetadataRequestAdmission(request.operation, request.policyEpisodeId, request.seriesRequest,
            request.slotRequest, request.searchRequest, preparation.accountBound.get(), request.replayRequest)
        private var documentStarted = false
        private var cleanupFailed = false
        override val cleanupConfirmed get() = !cleanupFailed
        private fun configure() {
            configureAbemaMetadataWebView(view)
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
                    if (!current(this@OwnedMetadataView)) return true
                    if (resource.isForMainFrame && !documentStarted && resource.url.toString() == AbemaMetadataRuntimePolicy.PAGE) return false
                    failed(this@OwnedMetadataView, "NAVIGATION_REFUSED"); return true
                }
                override fun shouldInterceptRequest(view: WebView, resource: WebResourceRequest): WebResourceResponse? {
                    val uri = runCatching { URI(resource.url.toString()) }.getOrNull()
                    val allowed = current(this@OwnedMetadataView) && uri != null && runCatching(canRequest).getOrDefault(false) &&
                        runCatching { preparation.checkAccess() }.getOrDefault(false) && current(this@OwnedMetadataView) &&
                        admission.admit(uri, resource.isForMainFrame, resource.method,
                            AbemaMetadataRuntimePolicy.corsHeaders(resource.method) { resource.requestHeaders })
                    if (!allowed) { failed(this@OwnedMetadataView, "RESOURCE_REFUSED"); return refused() }
                    if (uri.toString() == AbemaMetadataRuntimePolicy.GUEST || uri.toString() == AbemaMetadataRuntimePolicy.CHALLENGE ||
                        uri.toString() == AbemaMetadataRuntimePolicy.DEVICE_TOKEN || AbemaMetadataRuntimePolicy.isProfile(uri) ||
                        AbemaMetadataRuntimePolicy.isEpisode(uri, request.policyEpisodeId) ||
                        request.seriesRequest?.let { AbemaMetadataRuntimePolicy.seriesStep(uri, it) } != null ||
                        uri.toString() == request.slotRequest?.url() ||
                        request.operation == AbemaMetadataRuntimeOperation.FETCH_SCHEDULE && uri.toString() == AbemaMetadataRuntimePolicy.SCHEDULE ||
                        request.operation == AbemaMetadataRuntimeOperation.FETCH_SEARCH && uri.toString() == request.searchRequest?.url() ||
                        request.replayRequest?.let { AbemaMetadataRuntimePolicy.replayStep(uri, it) } != null) {
                        preparation.timing.providerRequest(); return null
                    }
                    val bytes = files[uri.path] ?: return refused()
                    return WebResourceResponse(if (resource.isForMainFrame) "text/html" else "application/javascript", "UTF-8",
                        200, "OK", mapOf("Content-Security-Policy" to AbemaMetadataRuntimePolicy.contentSecurityPolicy(request.operation,
                            request.policyEpisodeId, request.seriesRequest, request.slotRequest, request.searchRequest,
                            preparation.accountBound.get(), request.replayRequest), "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), ByteArrayInputStream(bytes))
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (!current(this@OwnedMetadataView)) return
                    if (url != AbemaMetadataRuntimePolicy.PAGE || documentStarted) failed(this@OwnedMetadataView, "NAVIGATION_REFUSED")
                    else { documentStarted = true; preparation.timing.mark(AbemaPreparationPhase.PAGE_STARTED) }
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    if (current(this@OwnedMetadataView) && documentStarted && url == AbemaMetadataRuntimePolicy.PAGE)
                        preparation.timing.mark(AbemaPreparationPhase.PAGE_LOADED)
                }
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    if (current(this@OwnedMetadataView) && url != AbemaMetadataRuntimePolicy.PAGE) failed(this@OwnedMetadataView, "NAVIGATION_REFUSED")
                }
                override fun onReceivedHttpAuthRequest(view: WebView, auth: HttpAuthHandler, host: String, realm: String) {
                    val route = preparation.route.get()
                    if (current(this@OwnedMetadataView) && route?.matchesProxyChallenge(host, realm) == true)
                        auth.proceed(route.proxyUsername, route.proxyPassword) else auth.cancel()
                }
                override fun onReceivedSslError(view: WebView, auth: SslErrorHandler, error: SslError) {
                    auth.cancel(); if (current(this@OwnedMetadataView)) failed(this@OwnedMetadataView, "TLS_REFUSED")
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (current(this@OwnedMetadataView)) failed(this@OwnedMetadataView, "RENDERER_GONE"); return true
                }
            }
        }
        private fun refused() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        override fun open() { configure(); view.loadUrl(AbemaMetadataRuntimePolicy.PAGE) }
        override fun status(callback: (String?) -> Unit) {
            if (view.url != AbemaMetadataRuntimePolicy.PAGE || preparation.account.get()?.current() == false) { callback(null); return }
            view.evaluateJavascript("window['__tachiaiGuest_${request.nonce}']?.status() || null", callback)
        }
        override fun metadata(callback: (String?) -> Unit) {
            check(request.operation in AbemaMetadataRuntimePolicy.DISCOVERY)
            if (preparation.account.get() == null) {
                view.evaluateJavascript("window['__tachiaiGuest_${request.nonce}']?.metadata() || null", callback); return
            }
            if (!preparation.projectionStarted.compareAndSet(false, true)) { callback(null); return }
            try { projectionWorker.execute {
                try {
                    val admitted = runCatching { preparation.checkAccess() && canRequest() &&
                        preparation.account.get()?.current() == true }.getOrDefault(false)
                    main.post {
                        if (!admitted || !current(this) || preparation.account.get()?.current() != true) { callback(null); return@post }
                        view.evaluateJavascript("window['__tachiaiGuest_${request.nonce}']?.metadata() || null", callback)
                    }
                } finally { preparation.projectionDone.countDown() }
            } } catch (_: Throwable) { preparation.projectionDone.countDown(); callback(null) }
        }
        override fun accountLink(callback: (String?) -> Unit) {
            check(request.operation == AbemaMetadataRuntimeOperation.PREPARE_ACCOUNT_LINK)
            if (view.url != AbemaMetadataRuntimePolicy.PAGE) { callback(null); return }
            view.evaluateJavascript("window['__tachiaiGuest_${request.nonce}']?.accountLink() || null", callback)
        }
        override fun accountValidation(callback: (String?) -> Unit) {
            check(request.operation == AbemaMetadataRuntimeOperation.PREPARE_ACCOUNT_VALIDATION)
            if (view.url != AbemaMetadataRuntimePolicy.PAGE) { callback(null); return }
            view.evaluateJavascript("window['__tachiaiGuest_${request.nonce}']?.accountValidation() || null", callback)
        }
        override fun destroy(): Boolean {
            var failed = false
            if (!diagnostics.cleanup(FailureStage.HELPER_STOP) {
                if (view.url == AbemaMetadataRuntimePolicy.PAGE) view.evaluateJavascript("window['__tachiaiGuest_${request.nonce}']?.stop()", null)
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
