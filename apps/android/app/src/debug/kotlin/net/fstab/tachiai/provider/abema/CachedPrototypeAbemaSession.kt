package net.fstab.tachiai.provider.abema

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.CookieHandler
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.platform.media.BoundedDashManifestParser
import net.fstab.tachiai.platform.media.BoundedMediaRequests
import net.fstab.tachiai.platform.media.BoundedNativeDashPlayer
import net.fstab.tachiai.platform.media.DeclaredDashMediaPolicy
import net.fstab.tachiai.platform.media.DashManifestPolicyEvent
import net.fstab.tachiai.platform.media.NativeDashEvent
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.NativeMediaEvent
import net.fstab.tachiai.platform.media.NativeFailureStage
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.platform.media.PrototypeFeedSession
import net.fstab.tachiai.platform.media.awaitOpaqueResponse
import net.fstab.tachiai.platform.network.RouteSession
import net.fstab.tachiai.platform.web.configureSecureSettings
import net.fstab.tachiai.presentation.PrototypeFeedFailure
import net.fstab.tachiai.presentation.PrototypeFeedFailureLatch
import net.fstab.tachiai.presentation.PrototypeFailureReason
import net.fstab.tachiai.provider.catalog.CatalogResource
import org.json.JSONObject
import org.json.JSONTokener
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureCategory
import net.fstab.tachiai.platform.diagnostics.FailureStage
import net.fstab.tachiai.platform.diagnostics.nativeObserver

// Minimal app-owned bootstrap, not a provider player page. Each slot owns its
// unchanged cached helper, anonymous bootstrap state and fresh one-exchange CDM.
// Historical page-backed PrototypeAbemaSession remains a separate example.
@UnstableApi
internal class CachedPrototypeAbemaSession(
    context: Context,
    private val replay: Boolean,
    private val active: () -> Boolean,
    private val onEvent: (PrototypeFeedEvent) -> Unit,
    private val route: RouteSession? = null,
    private val diagnostics: FailureReporter = FailureReporter.NONE,
    private val onHelperCreated: (CachedAbemaHelperCleanup) -> Unit = {},
    private val onCleanupChanged: () -> Unit = {},
    private val episodeResource: CatalogResource? = null,
    private val ownership: AbemaEpisodePlaybackOwnership? = null,
    private val channelResource: CatalogResource? = null,
    private val slotResource: CatalogResource? = null,
) : PrototypeFeedSession {
    init {
        require(episodeResource == null || replay && supportedConfiguredAbemaEpisode(episodeResource) && route != null && ownership != null)
        require(channelResource == null || !replay && episodeResource == null && supportedConfiguredAbemaChannel(channelResource) && route != null && ownership != null)
        require(slotResource == null || replay && episodeResource == null && channelResource == null &&
            supportedConfiguredAbemaSlot(slotResource) && route != null && ownership != null)
        require(episodeResource != null || channelResource != null || slotResource != null || ownership == null)
    }
    private val episodeId get() = episodeResource?.identity ?: "394-72_s10_p8529"
    private val channelId get() = channelResource?.identity ?: "abema-news"
    private val slotChannelId get() = checkNotNull(slotResource).identity.substringBefore('/')
    private val slotId get() = checkNotNull(slotResource).identity.substringAfter('/')
    private val handler = Handler(Looper.getMainLooper())
    private val worker: CachedCleanupWorker = CachedCleanupWorker(Executors.newSingleThreadExecutor()) {
        finishTransportCleanup(); onCleanupChanged()
    }
    private val transportFinalizing = AtomicBoolean()
    private val transportFinalized = AtomicBoolean()
    val cleanupPending get() = worker.pending || (closed.get() && !transportFinalized.get())
    private val transportCleanup = net.fstab.tachiai.platform.media.NativeCleanupReceipt { cleanupFailed = true; onCleanupChanged() }
    @Volatile private var nativeCleanup: net.fstab.tachiai.platform.media.NativeCleanupReceipt? = null
    private val closed = AtomicBoolean(false)
    private val networkReviewNeeded = AtomicBoolean(false)
    private val cdnJournal = AbemaCdnReviewJournal(File(context.noBackupFilesDir, "abema-cdn-review.tsv"))
    private val revision = AtomicLong()
    private val routeCurrent = AtomicBoolean(false)
    private val bundleTransport = AtomicReference<AbemaPublicBundleHttp?>()
    private val sourceRequests = AtomicReference<BoundedMediaRequests?>()
    private val sourcePending = AtomicReference<CompletableFuture<URI?>?>()
    private val pending = AtomicReference<CompletableFuture<ByteArray?>?>()
    private val nonce = UUID.randomUUID().toString().replace("-", "")
    private val name = "__tachiaiNative_$nonce"
    @Volatile private var ownedResources: Map<String, ByteArray> = emptyMap()
    @Volatile private var nativeHost: BoundedNativeDashPlayer? = null
    @Volatile private var budget: NativePlaybackBudget? = null
    @Volatile private var deadline = 0L
    private var started = false
    private var documentStarted = false
    private var preparingNative = false
    private var lastEvent: PrototypeFeedEvent? = null
    private var lastHelperState: String? = null
    private var lastMetadata: String? = null
    private val helperEvidence = AbemaCachedHelperEvidence()
    private val firstFailure = PrototypeFeedFailureLatch()
    private var helperFailure: PrototypeFeedFailure? = null
    override val failure: PrototypeFeedFailure? get() = firstFailure.failure
    @Volatile override var cleanupFailed: Boolean = false
        private set

    val cleanupConfirmed get() = closed.get() && !cleanupFailed && !cleanupPending &&
        transportCleanup.confirmed && (nativeCleanup?.confirmed != false)

    override val providerView: View = WebView(context)
    private val view get() = providerView as WebView
    private val helperCleanup = CachedAbemaHelperCleanup.forView(view, diagnostics)
    override val member: NativePairMember? get() = nativeHost
    override val player: Player? get() = nativeHost?.player

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        onHelperCreated(helperCleanup)
        configureBrowser()
    }

    private fun allows(value: String?) = value == AbemaNativeBootstrapPolicy.PAGE
    private fun preparationCurrent(run: Long) = !closed.get() && revision.get() == run && active() &&
        budget?.active == true && SystemClock.elapsedRealtime() < deadline && ownership?.canContinue() != false
    private fun current(run: Long) = preparationCurrent(run) && routeCurrent.get()
    private fun workerOwned(): Boolean = ownership?.checkWorker() != false
    private fun openConnection(url: URL): HttpsURLConnection {
        if (!workerOwned()) throw java.io.IOException("Selected playback owner unavailable")
        return route?.open(url) ?: (url.openConnection() as HttpsURLConnection)
    }
    override fun canContinue(): Boolean = current(revision.get())
    override fun checkAuthorization(): Boolean = canContinue()

    private fun emit(event: PrototypeFeedEvent) {
        if (lastEvent == event) return
        lastEvent = event
        onEvent(event)
    }

    private fun record(category: String) {
        Log.d("TachiaiCachedAbema", "kind=${if (replay) "REPLAY" else "LIVE"} $category")
    }

    @SuppressLint("MissingOnRenderProcessGone")
    private fun configureBrowser() {
        // Activity chooses its dedicated process/profile before construction.
        WebView.setWebContentsDebuggingEnabled(false)
        view.configureSecureSettings()
        view.settings.domStorageEnabled = false
        view.settings.blockNetworkImage = true
        view.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        view.settings.setSupportMultipleWindows(false)
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
            override fun onReceivedHttpAuthRequest(view: WebView, auth: HttpAuthHandler, host: String, realm: String) {
                // A generated local-proxy credential, never a provider login or
                // persistent browser credential. Reject every other challenger.
                if (current(revision.get()) && route?.matchesProxyChallenge(host, realm) == true)
                    auth.proceed(route.proxyUsername, route.proxyPassword)
                else auth.cancel()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && allows(request.url.toString()) && !documentStarted) return false
                fail(PrototypeFeedFailure(PrototypeFailureReason.MEDIA_BLOCKED)); return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = runCatching { URI(request.url.toString()) }.getOrNull()
                val live = preparationCurrent(revision.get()) && workerOwned()
                val allowed = uri != null && live &&
                    AbemaNativeBootstrapPolicy.allows(uri, request.isForMainFrame, request.method, episodeResource?.identity, channelResource?.identity, slotResource?.identity)
                if (!allowed) {
                    val category = if (!live) "LIFECYCLE" else
                        AbemaNativeBootstrapPolicy.refusalCategory(uri, request.isForMainFrame, request.method)
                    handler.post { if (!closed.get()) {
                        record("resource=REFUSED category=$category")
                        fail(PrototypeFeedFailure(if (live) PrototypeFailureReason.MEDIA_BLOCKED else PrototypeFailureReason.PLAYBACK_LIMIT))
                    } }
                    return refusedResource()
                }
                val local = ownedResources[uri.path]
                if (uri.host == "abema.tv" && uri.path.startsWith("/_tachiai/native/")) {
                    if (local == null) {
                        handler.post { if (!closed.get()) fail() }
                        return refusedResource()
                    }
                    return WebResourceResponse(if (request.isForMainFrame) "text/html" else "application/javascript", "UTF-8",
                        200, "OK", mapOf("Content-Security-Policy" to AbemaNativeBootstrapPolicy.contentSecurityPolicy(episodeResource?.identity, channelResource?.identity, slotResource?.identity),
                            "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), ByteArrayInputStream(local))
                }
                return null // Only exact policy-approved provider API endpoints.
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!documentStarted && (url == null || url == "about:blank")) return
                if (closed.get()) return
                if (!allows(url) || documentStarted) { fail(PrototypeFeedFailure(PrototypeFailureReason.MEDIA_BLOCKED)); return }
                documentStarted = true
                routeCurrent.set(true)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (!allows(url) && documentStarted) fail(PrototypeFeedFailure(PrototypeFailureReason.MEDIA_BLOCKED))
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (!allows(url) && documentStarted) fail(PrototypeFeedFailure(PrototypeFailureReason.MEDIA_BLOCKED))
            }
            override fun onReceivedSslError(view: WebView, ssl: SslErrorHandler, error: SslError) {
                ssl.cancel(); fail(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail(PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_FAILED)); return true
            }
        }
    }

    private fun refusedResource() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
        emptyMap(), ByteArrayInputStream(byteArrayOf()))

    override fun prepare(budget: NativePlaybackBudget) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (started || closed.get()) return
        started = true
        this.budget = budget
        deadline = SystemClock.elapsedRealtime() + budget.remainingMs
        if (!BuildConfig.DEBUG || !active() || !budget.active) { fail(); return }
        emit(PrototypeFeedEvent.PREPARING)
        val run = revision.incrementAndGet()
        val readinessDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 45_000)
        handler.postDelayed({ if (!closed.get() && revision.get() == run)
            fail(PrototypeFeedFailure(PrototypeFailureReason.PLAYBACK_LIMIT)) }, budget.remainingMs)
        handler.postDelayed({ if (!closed.get() && revision.get() == run && !preparingNative)
            fail(PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_TIMEOUT)) },
            maxOf(0L, readinessDeadline - SystemClock.elapsedRealtime()))
        worker.execute {
            fun cached(identity: AbemaPublicBundleIdentity): AbemaBundlePrepared {
                val http = AbemaPublicBundleHttp(
                    canRun = { preparationCurrent(run) && SystemClock.elapsedRealtime() < readinessDeadline },
                    identity = identity,
                    open = { url -> transportCleanup.allocate(10, { openConnection(url) }) { it.disconnect() } },
                    onFailure = { transportCleanup.failed(); cleanupFailed = true; diagnostics.report(FailureStage.BUNDLE_DISCONNECT, it) },
                    openInput = { request -> transportCleanup.input { request.inputStream } },
                )
                bundleTransport.set(http)
                return try {
                    AbemaPublicBundleCache(File(view.context.cacheDir, "abema-helper-bundle"),
                        { preparationCurrent(run) && SystemClock.elapsedRealtime() < readinessDeadline }, identity)
                        .prepare(http::fetch)
                } finally {
                    try { http.close(); bundleTransport.compareAndSet(http, null) }
                    catch (error: Throwable) { cleanupFailed = true; throw error }
                }
            }
            try {
                check(preparationCurrent(run) && workerOwned() && CookieHandler.getDefault() == null)
                val prepared = cached(AbemaPublicBundleIdentity())
                val support = cached(AbemaPublicBundleIdentity.bootstrapSupport())
                val legacy = cached(AbemaPublicBundleIdentity.legacySource())
                val utilities = cached(AbemaPublicBundleIdentity.sourceUtilities())
                val resources = mapOf(
                    "/_tachiai/native/index.html" to view.context.assets.open("abema/native-bootstrap.html").use { it.readBytes() },
                    "/_tachiai/native/control.js" to view.context.assets.open("abema/native-bootstrap.js").use { it.readBytes() },
                    "/_tachiai/native/bundle.js" to prepared.bytes,
                    "/_tachiai/native/support.js" to support.bytes,
                    "/_tachiai/native/legacy.js" to legacy.bytes,
                    "/_tachiai/native/utilities.js" to utilities.bytes,
                    "/_tachiai/native/initialize.js" to view.context.assets.open("abema/native-bootstrap-initialize.js")
                        .bufferedReader().use { it.readText() }.replace("__NONCE__", nonce)
                        .replace("__NATIVE_REPLAY__", replay.toString())
                        .replace("__EPISODE_ARGUMENT__", episodeResource?.let { ", " + JSONObject.quote(it.identity) } ?: "")
                        .replace("__CHANNEL_ARGUMENT__", channelResource?.let { ", undefined, " + JSONObject.quote(it.identity) } ?: "")
                        .replace("__SLOT_ARGUMENT__", slotResource?.let { ", undefined, undefined, " + JSONObject.quote(it.identity) } ?: "").toByteArray(Charsets.UTF_8),
                )
                handler.post {
                    if (preparationCurrent(run) && SystemClock.elapsedRealtime() < readinessDeadline) {
                        record("cache=${prepared.origin.name} support=${support.origin.name} legacy=${legacy.origin.name} utilities=${utilities.origin.name}")
                        ownedResources = resources
                        view.loadUrl(AbemaNativeBootstrapPolicy.PAGE)
                        waitForHelper(run, readinessDeadline)
                    }
                }
            } catch (error: AbemaBundleException) {
                if (preparationCurrent(run) && error.category != AbemaBundleFailure.CANCELLED)
                    diagnostics.report(FailureStage.CACHED_ABEMA_BUNDLE_PREPARE, error)
                val reason = when (error.category) {
                    AbemaBundleFailure.NETWORK_FAILED -> PrototypeFailureReason.NETWORK_FAILED
                    AbemaBundleFailure.HTTP_REFUSED -> PrototypeFailureReason.HTTP_REJECTED
                    AbemaBundleFailure.TIME_LIMIT_REACHED -> PrototypeFailureReason.PREPARATION_TIMEOUT
                    AbemaBundleFailure.POLICY_REFUSED, AbemaBundleFailure.COOKIE_HANDLER_REFUSED -> PrototypeFailureReason.MEDIA_BLOCKED
                    AbemaBundleFailure.PIN_REFUSED, AbemaBundleFailure.SIZE_REFUSED, AbemaBundleFailure.ASSET_TYPE_REFUSED -> PrototypeFailureReason.UNSUPPORTED_MEDIA
                    else -> PrototypeFailureReason.PREPARATION_FAILED
                }
                handler.post { if (!closed.get() && revision.get() == run) fail(PrototypeFeedFailure(reason)) }
            } catch (error: Throwable) {
                // Cleanup Errors must remain a safe retained failure, never
                // escape this owned worker to Android's uncaught handler.
                if (preparationCurrent(run)) diagnostics.report(FailureStage.CACHED_ABEMA_BUNDLE_PREPARE, error)
                handler.post { if (!closed.get() && revision.get() == run) fail() }
            }
        }
    }

    private fun waitForHelper(run: Long, readinessDeadline: Long) {
        if (closed.get() || revision.get() != run) return
        if (!preparationCurrent(run) || SystemClock.elapsedRealtime() >= readinessDeadline) {
            fail(PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_TIMEOUT)); return
        }
        if (!allows(view.url) || !routeCurrent.get()) {
            handler.postDelayed({ waitForHelper(run, readinessDeadline) }, 100); return
        }
        view.evaluateJavascript("window['$name']?.status() || 'UNAVAILABLE'") { raw ->
            if (!current(run) || SystemClock.elapsedRealtime() >= readinessDeadline || !allows(view.url)) return@evaluateJavascript
            val state = quoted(raw, 100)?.takeIf { it in setOf("WAITING_SESSION", "WAITING_SOURCE", "READY",
                "WORKING", "RESPONSE", "FAILED", "STOPPED", "UNAVAILABLE") } ?: "FAILED"
            if (lastHelperState != state) { lastHelperState = state; record("helper=$state") }
            pollMetadata(run, readinessDeadline) { metadata ->
                if (helperEvidence.firstFailure != AbemaCachedHelperFailure.NONE) {
                    fail(helperFailure ?: PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_FAILED)); return@pollMetadata
                }
                when (state) {
                    "READY" -> if (!helperEvidence.permitsPreparation(state, metadata)) fail()
                        else if (!preparingNative) { preparingNative = true; prepareNative(run) }
                    "WAITING_SESSION", "WAITING_SOURCE", "UNAVAILABLE" -> {
                        emit(PrototypeFeedEvent.WAITING_PROVIDER)
                        handler.postDelayed({ waitForHelper(run, readinessDeadline) }, 100)
                    }
                    else -> fail(helperFailure ?: PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_FAILED))
                }
            }
        }
    }

    private fun pollMetadata(run: Long, readDeadline: Long, onRead: (AbemaCachedHelperMetadata?) -> Unit) {
        if (!current(run) || SystemClock.elapsedRealtime() >= readDeadline || !allows(view.url)) return
        view.evaluateJavascript("window['$name']?.metadata() || null") { raw ->
            if (!current(run) || SystemClock.elapsedRealtime() >= readDeadline || !allows(view.url)) return@evaluateJavascript
            if (raw == "null") { onRead(null); return@evaluateJavascript }
            val value = parseAbemaCachedHelperMetadataCallback(raw)
            if (value == null) {
                record("metadata=REFUSED"); fail(); return@evaluateJavascript
            }
            val first = helperEvidence.observe(value)
            if (first) diagnostics.reportCategory(FailureStage.CACHED_ABEMA_SOURCE_PREPARE,
                value.failure.diagnosticCategory())
            val phase = value.phase
            val http = value.http
            val diagnostic = value.closedSummary()
            if (lastMetadata != diagnostic) { lastMetadata = diagnostic; record(diagnostic) }
            if (first || helperEvidence.firstFailure == AbemaCachedHelperFailure.NONE) helperFailure = when {
                value.failure == AbemaCachedHelperFailure.TIMER_EXPIRED -> PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_TIMEOUT)
                value.failure == AbemaCachedHelperFailure.OWNED_CANCELLED -> PrototypeFeedFailure(PrototypeFailureReason.STOPPED)
                value.failure == AbemaCachedHelperFailure.FETCH_REJECTED_BEFORE_HTTP -> PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED)
                value.failure == AbemaCachedHelperFailure.HTTP_REFUSED -> PrototypeFeedFailure(if (http == 404) PrototypeFailureReason.MEDIA_NOT_FOUND
                    else PrototypeFailureReason.HTTP_REJECTED, httpStatus = http)
                http >= 400 -> PrototypeFeedFailure(if (http == 404) PrototypeFailureReason.MEDIA_NOT_FOUND
                    else PrototypeFailureReason.HTTP_REJECTED, httpStatus = http)
                phase == "SOURCE_SESSION_REQUIRED" || phase == "SOURCE_MODE_UNSUPPORTED" ||
                    phase.startsWith("REPLAY_") || phase.startsWith("SLOT_") ->
                    PrototypeFeedFailure(PrototypeFailureReason.UNSUPPORTED_MEDIA)
                phase == "LICENSE" -> PrototypeFeedFailure(PrototypeFailureReason.LICENSE_FAILED)
                else -> null
            }
            onRead(value)
        }
    }

    private fun prepareNative(run: Long) {
        if (!current(run) || !allows(view.url)) { fail(); return }
        emit(PrototypeFeedEvent.PREPARING)
        worker.execute {
            try {
                check(current(run) && workerOwned() && CookieHandler.getDefault() == null)
                val uri = selectedSource(run, minOf(deadline, SystemClock.elapsedRealtime() + 10_000))
                    ?: throw IllegalStateException()
                val requests = BoundedMediaRequests(checkNotNull(budget).child(), { current(run) && it == uri }, { event, code ->
                    record("manifest=${event.name} code=$code")
                    rememberMedia(event, code)
                }, canRequest = { current(run) },
                    openConnection = { url -> transportCleanup.allocate(10, { openConnection(url) }) { it.disconnect() } },
                    openInput = { request -> transportCleanup.input { request.inputStream } },
                    openErrorInput = { request -> transportCleanup.optionalInput { request.errorStream } },
                    onFailure = { stage, error ->
                        if (stage == NativeFailureStage.MEDIA_DISCONNECT) { transportCleanup.failed(); cleanupFailed = true }
                        diagnostics.nativeObserver()(stage, error)
                    })
                sourceRequests.set(requests)
                val source = try { readSource(uri, requests) }
                finally {
                    try { requests.close(); sourceRequests.compareAndSet(requests, null) }
                    catch (error: Throwable) { cleanupFailed = true; throw error }
                }
                check(current(run) && workerOwned() && selectedSource(run, minOf(deadline, SystemClock.elapsedRealtime() + 10_000)) == uri)
                handler.post { if (current(run) && allows(view.url)) createNative(source, run) }
            } catch (error: Throwable) {
                if (current(run)) diagnostics.report(FailureStage.CACHED_ABEMA_SOURCE_PREPARE, error)
                handler.post { if (!closed.get() && revision.get() == run) fail() }
            }
        }
    }

    private fun readSource(uri: URI, requests: BoundedMediaRequests): AbemaNativeDashSource {
        if (slotResource != null) return resolveAbemaSlotNativeSource(uri, requests, slotChannelId, slotId)
        if (replay) return resolveAbemaReplayNativeSource(uri, requests, AbemaReplayCdnScope.APPROVED_PROTOTYPE, episodeId)
        if (channelResource != null) return resolveAbemaChannelNativeSource(uri, requests, channelId)
        check(AbemaNativeBootstrapPolicy.allowsNewsSource(uri, approvedPrototype = true, channelId = channelId))
        val source = requests.create(C.DATA_TYPE_MANIFEST)
        return try {
            source.openUri(uri)
            val body = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count < 0) break
                check(body.size() + count <= 256 * 1024)
                body.write(buffer, 0, count)
            }
            val initialization = checkNotNull(parseAbemaRequestInitialization(body.toString("UTF-8")))
            AbemaNewsNativeSource(uri, initialization.kids)
        } finally { source.close() }
    }

    private fun createNative(source: AbemaNativeDashSource, run: Long) {
        if (slotResource != null && (source !is AbemaSlotNativeSource || source.channelId != slotChannelId || source.slotId != slotId)) { fail(); return }
        if (replay && slotResource == null && (source !is AbemaReplayNativeSource || source.episodeId != episodeId)) { fail(); return }
        if (channelResource != null && (source !is AbemaChannelNativeSource || source.channelId != channelId)) { fail(); return }
        val policy = DeclaredDashMediaPolicy(source.uri) { uri ->
            reviewCdn(uri, AbemaCdnStage.DECLARED_MEDIA)
            if (slotResource != null) {
                allowsAbemaSlotDeclaredMedia(uri)
            } else if (replay) {
                abemaReplayCdnUriPolicy(uri, AbemaReplayCdnScope.APPROVED_PROTOTYPE) == AbemaReplayUriPolicy.ALLOWED
            }
            else AbemaNativeBootstrapPolicy.allowsNewsCdn(uri, approvedPrototype = true, channelId = channelId)
        }
        val transitions = if (replay) null else AbemaManifestTransitionPolicy(source.kids)
        val parser = BoundedDashManifestParser(source.uri, policy,
            preflight = { body -> transitions?.accepts(body) ?: (parseAbemaRequestInitialization(body)?.kids?.toSet() == source.kids.toSet()) },
            canRun = { current(run) }, onEvent = { event, _ ->
                if (event == DashManifestPolicyEvent.REFUSED) remember(PrototypeFeedFailure(PrototypeFailureReason.UNSUPPORTED_MEDIA))
            },
            validateModel = { parsed -> transitions?.requiresDeclarationFreeModel != true ||
                (0 until parsed.periodCount).all { period -> parsed.getPeriod(period).adaptationSets.all { adaptation ->
                    adaptation.representations.all { it.format.drmInitData == null }
                } } })
        try {
            val created = BoundedNativeDashPlayer(view.context, checkNotNull(budget), source.kids,
                allowedUri = policy::allows, manifestUri = source.uri, manifestParser = parser,
                exchange = { challenge -> broker(challenge, run, minOf(deadline, SystemClock.elapsedRealtime() + 30_000)) },
                onEvent = { event, code ->
                    rememberNative(event, code)
                    handler.post {
                        if (closed.get() || revision.get() != run || !active()) return@post
                        record("native=${event.name}")
                        when (event) {
                            NativeDashEvent.READY -> emit(PrototypeFeedEvent.READY)
                            NativeDashEvent.DRM_REQUESTED -> emit(PrototypeFeedEvent.LICENSE_REQUESTED)
                            NativeDashEvent.DRM_KEYS_LOADED -> emit(PrototypeFeedEvent.LICENSE_READY)
                            NativeDashEvent.VIDEO_FRAME -> emit(PrototypeFeedEvent.VIDEO_FRAME)
                            NativeDashEvent.PLAYER_FAILED, NativeDashEvent.DRM_REFUSED, NativeDashEvent.DRM_FAILED,
                            NativeDashEvent.DRM_PREWARM_FAILED, NativeDashEvent.LIMIT_REACHED, NativeDashEvent.STOPPED -> fail()
                            else -> Unit
                        }
                    }
                }, onMediaEvent = { event, code ->
                    rememberMedia(event, code)
                    if (current(run)) record("media=${event.name} code=$code")
                },
                beforeRelease = { policy.close(); pending.getAndSet(null)?.complete(null) },
                keepDrmSessionForClearTransitions = !replay,
                initialDrmFormat = if (replay) null else Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setDrmInitData(DrmInitData(C.CENC_TYPE_cenc, DrmInitData.SchemeData(
                        C.COMMON_PSSH_UUID, MimeTypes.VIDEO_MP4, commonPssh(source.kids)))).build(),
                openConnection = ::openConnection, onFailure = diagnostics.nativeObserver(),
                onCleanupOwner = { nativeCleanup = it },
                onCleanupUnconfirmed = { cleanupFailed = true; onCleanupChanged() })
            nativeHost = created
            created.start(source.uri, playWhenReady = false)
        } catch (error: Exception) {
            if (current(run)) diagnostics.report(FailureStage.ABEMA_PLAYER_CREATE, error)
            policy.close(); fail()
        }
    }

    private fun selectedSource(run: Long, sourceDeadline: Long): URI? {
        val completion = CompletableFuture<URI?>()
        check(sourcePending.compareAndSet(null, completion))
        handler.post {
            if (!current(run) || SystemClock.elapsedRealtime() >= sourceDeadline || !allows(view.url)) completion.complete(null)
            else view.evaluateJavascript("window['$name']?.${when { slotResource != null -> "slotBinding"; episodeResource != null -> "sourceBinding"; channelResource != null -> "channelBinding"; else -> "selectedSource" }}() || null") { raw ->
                if (!current(run) || SystemClock.elapsedRealtime() >= sourceDeadline || !allows(view.url)) completion.complete(null)
                else {
                    val selected = quoted(raw, 12_000)
                    val uri = when {
                        slotResource != null -> parseAbemaSlotSourceBinding(selected, slotChannelId, slotId)
                        episodeResource != null -> parseAbemaEpisodeSourceBinding(selected, episodeId)
                        channelResource != null -> parseAbemaChannelSourceBinding(selected, channelId)
                        else -> runCatching { URI(selected ?: "") }.getOrNull()
                    }
                    if (uri != null) {
                        try { reviewCdn(uri, AbemaCdnStage.SELECTED_MANIFEST) }
                        catch (_: Exception) {
                            record("cdnReview=UNAVAILABLE"); completion.complete(null); return@evaluateJavascript
                        }
                    }
                    val allowed = uri != null && if (slotResource != null) allowsAbemaSlotSource(uri, slotChannelId, slotId)
                    else if (replay)
                        abemaReplaySelectedSourcePolicy(uri, AbemaReplayCdnScope.APPROVED_PROTOTYPE, episodeId) == AbemaReplayUriPolicy.ALLOWED
                    else AbemaNativeBootstrapPolicy.allowsNewsSource(uri, approvedPrototype = true, channelId = channelId)
                    record("source=${if (allowed) "ALLOWED" else "REFUSED"}")
                    if (!allowed && uri != null) remember(PrototypeFeedFailure(PrototypeFailureReason.MEDIA_BLOCKED))
                    if (!allowed && replay && slotResource == null) {
                        val category = uri?.let { abemaReplaySelectedSourcePolicy(it, AbemaReplayCdnScope.APPROVED_PROTOTYPE).name } ?: "INVALID_URI"
                        val shape = when {
                            uri == null -> "INVALID_URI"
                            uri.toString().length > 2048 -> "TOO_LONG"
                            uri.scheme != "https" -> "SCHEME"
                            uri.port !in listOf(-1, 443) -> "PORT"
                            uri.host == "ds-vod-abematv.akamaized.net" -> "DS_VOD_AKAMAI"
                            uri.host == "vod-abematv.akamaized.net" -> "VOD_AKAMAI"
                            uri.host == "vod-cf.p-c3-e.abema-tv.com" -> "VOD_CLOUDFRONT"
                            else -> "OTHER_HOST"
                        }
                        record("sourcePolicy=$category shape=$shape")
                    }
                    completion.complete(uri?.takeIf { allowed })
                }
            }
        }
        return try { completion.get(maxOf(1L, sourceDeadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS) }
        catch (_: Exception) { null }
        finally { sourcePending.compareAndSet(completion, null) }
    }

    private fun broker(challenge: ByteArray, run: Long, exchangeDeadline: Long): ByteArray? {
        if (!workerOwned()) return null
        val completion = CompletableFuture<ByteArray?>()
        check(pending.compareAndSet(null, completion))
        val encoded = Base64.encodeToString(challenge, Base64.NO_WRAP)
        handler.post {
            if (!current(run) || SystemClock.elapsedRealtime() >= exchangeDeadline || !allows(view.url)) completion.complete(null)
            else view.evaluateJavascript("window['$name']?.begin('$encoded') || 'REFUSED'") { raw ->
                if (!current(run) || SystemClock.elapsedRealtime() >= exchangeDeadline || !allows(view.url) || quoted(raw, 100) != "WORKING") completion.complete(null)
                else pollResponse(completion, run, exchangeDeadline)
            }
        }
        return try {
            awaitOpaqueResponse(completion, exchangeDeadline - SystemClock.elapsedRealtime())?.let { bytes ->
                if (workerOwned() && current(run)) bytes else { bytes.fill(0); null }
            }
        }
        finally { pending.compareAndSet(completion, null) }
    }

    private fun pollResponse(completion: CompletableFuture<ByteArray?>, run: Long, exchangeDeadline: Long) {
        if (completion.isDone) return
        if (!current(run) || !allows(view.url) || SystemClock.elapsedRealtime() >= exchangeDeadline) { completion.complete(null); return }
        view.evaluateJavascript("window['$name']?.status() || 'UNAVAILABLE'") { raw ->
            if (!current(run) || !allows(view.url) || SystemClock.elapsedRealtime() >= exchangeDeadline) completion.complete(null)
            else when (quoted(raw, 100)) {
                "WORKING" -> handler.postDelayed({ pollResponse(completion, run, exchangeDeadline) }, 100)
                "RESPONSE" -> view.evaluateJavascript("window['$name']?.take()") { opaque ->
                    val encoded = quoted(opaque, 90_000)
                    val response = try {
                        if (encoded == null || !encoded.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) null
                        else Base64.decode(encoded, Base64.NO_WRAP).let { bytes ->
                            if (bytes.size in 1..65536 && Base64.encodeToString(bytes, Base64.NO_WRAP) == encoded) bytes
                            else { bytes.fill(0); null }
                        }
                    } catch (_: Exception) { null }
                    if (!current(run) || !allows(view.url) || SystemClock.elapsedRealtime() >= exchangeDeadline ||
                        !completion.complete(response)) response?.fill(0)
                    if (!current(run) || SystemClock.elapsedRealtime() >= exchangeDeadline) completion.complete(null)
                }
                // Collect the same bounded closed evidence before handing a
                // terminal helper failure back to the CDM/cleanup path. Never
                // read after close or extend the original exchange deadline.
                else -> pollMetadata(run, exchangeDeadline) {
                    remember(helperFailure ?: PrototypeFeedFailure(PrototypeFailureReason.LICENSE_FAILED))
                    completion.complete(null)
                }
            }
        }
    }

    override fun pauseOriginal(onResult: (Boolean) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        // No provider media element was created by this runtime.
        onResult(current(revision.get()) && allows(view.url) && nativeHost?.timingSnapshot()?.state == Player.STATE_READY)
    }

    private fun quoted(raw: String?, limit: Int): String? = try {
        if (raw == null || raw.length > limit) null else JSONTokener(raw).nextValue() as? String
    } catch (_: Exception) { null }

    private fun reviewCdn(uri: URI, stage: AbemaCdnStage) {
        val value = AbemaCdnApprovalPolicy.review(uri, stage, System.currentTimeMillis()) ?: return
        try { cdnJournal.record(value) }
        catch (_: Exception) { record("cdnReview=UNAVAILABLE"); throw IllegalStateException("CDN review unavailable") }
        if (value.decision == AbemaCdnDecision.PENDING) {
            networkReviewNeeded.set(true)
            remember(PrototypeFeedFailure(PrototypeFailureReason.MEDIA_APPROVAL_REQUIRED))
            record("cdnReview=PENDING stage=${stage.name}")
        }
    }

    private fun remember(value: PrototypeFeedFailure) {
        if (!closed.get() && active()) firstFailure.remember(value)
    }

    private fun rememberMedia(event: NativeMediaEvent, code: Int) {
        val reason = when (event) {
            NativeMediaEvent.HTTP_REJECTED -> if (code == 404) PrototypeFailureReason.MEDIA_NOT_FOUND else PrototypeFailureReason.HTTP_REJECTED
            NativeMediaEvent.SOURCE_NOT_ALLOWLISTED -> PrototypeFailureReason.MEDIA_BLOCKED
            NativeMediaEvent.REQUEST_FAILED -> PrototypeFailureReason.NETWORK_FAILED
            NativeMediaEvent.ENCRYPTION_UNSUPPORTED, NativeMediaEvent.PLAYLIST_UNSUPPORTED -> PrototypeFailureReason.UNSUPPORTED_MEDIA
            NativeMediaEvent.LIMIT_REACHED -> PrototypeFailureReason.PLAYBACK_LIMIT
            else -> return
        }
        remember(PrototypeFeedFailure(reason, httpStatus = if (event == NativeMediaEvent.HTTP_REJECTED)
            code.takeIf { it in 100..599 } else null))
    }

    private fun rememberNative(event: NativeDashEvent, code: Int) {
        val reason = when (event) {
            NativeDashEvent.DRM_REFUSED, NativeDashEvent.DRM_FAILED, NativeDashEvent.DRM_PREWARM_FAILED -> PrototypeFailureReason.LICENSE_FAILED
            NativeDashEvent.PLAYER_FAILED -> PrototypeFailureReason.PLAYER_FAILED
            NativeDashEvent.LIMIT_REACHED -> PrototypeFailureReason.PLAYBACK_LIMIT
            NativeDashEvent.STOPPED -> PrototypeFailureReason.STOPPED
            else -> return
        }
        remember(PrototypeFeedFailure(reason, playerCode = if (event == NativeDashEvent.PLAYER_FAILED)
            code.takeIf { it in 1000..9999 } else null))
    }

    private fun fail(value: PrototypeFeedFailure = PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_FAILED)) {
        remember(if (networkReviewNeeded.get()) PrototypeFeedFailure(PrototypeFailureReason.MEDIA_APPROVAL_REQUIRED) else value)
        finish(if (failure?.reason == PrototypeFailureReason.MEDIA_APPROVAL_REQUIRED)
            PrototypeFeedEvent.NETWORK_APPROVAL_REQUIRED else PrototypeFeedEvent.FAILED)
    }
    override fun close() {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { close() }; return }
        // This owner explicitly cancels an outstanding helper operation. Do
        // not read after close, delay cleanup, or classify ordinary successful
        // playback/take cleanup as cancellation.
        if (!closed.get() && documentStarted && (!preparingNative || pending.get()?.isDone == false) &&
            helperEvidence.remember(AbemaCachedHelperFailure.OWNED_CANCELLED))
            diagnostics.reportCategory(FailureStage.CACHED_ABEMA_SOURCE_PREPARE, FailureCategory.ABEMA_CACHED_OWNED_CANCELLED)
        finish(PrototypeFeedEvent.STOPPED)
    }

    private fun finishTransportCleanup() {
        if (!closed.get() || worker.pending || !transportFinalizing.compareAndSet(false, true)) return
        try {
            if (!diagnostics.cleanupAll(FailureStage.SOURCE_REQUESTS_CLOSE) { transportCleanup.close() }) cleanupFailed = true
        } finally { transportFinalized.set(true); onCleanupChanged() }
    }

    private fun finish(event: PrototypeFeedEvent) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { finish(event) }; return }
        if (!closed.compareAndSet(false, true)) return
        transportCleanup.seal()
        revision.incrementAndGet()
        routeCurrent.set(false)
        handler.removeCallbacksAndMessages(null)
        sourcePending.getAndSet(null)?.complete(null)
        pending.getAndSet(null)?.complete(null)
        var nativeClosed = nativeHost == null
        for ((stage, cleanup) in listOf<Pair<FailureStage, () -> Unit>>(
            FailureStage.BUNDLE_TRANSPORT_CLOSE to {
                val owned = bundleTransport.get(); owned?.close(); bundleTransport.compareAndSet(owned, null)
            },
            FailureStage.SOURCE_REQUESTS_CLOSE to {
                val owned = sourceRequests.get(); owned?.close(); sourceRequests.compareAndSet(owned, null)
            },
            FailureStage.HELPER_STOP to { if (allows(view.url)) view.evaluateJavascript("window['$name']?.stop()", null) },
            FailureStage.NATIVE_HOST_CLOSE to { nativeHost?.close(); nativeCleanup?.close(); nativeClosed = true },
            FailureStage.WEBVIEW_DESTROY to {
                if (!helperCleanup.destroy() || helperCleanup.cleanupFailed) cleanupFailed = true
            },
            FailureStage.RESOURCES_CLEAR to { ownedResources = emptyMap() },
            FailureStage.WORKER_SHUTDOWN to { worker.shutdownNow() },
        )) {
            if (!diagnostics.cleanupAll(stage, cleanup)) cleanupFailed = true
        }
        if (nativeClosed) nativeHost = null
        finishTransportCleanup()
        emit(event)
    }
}
