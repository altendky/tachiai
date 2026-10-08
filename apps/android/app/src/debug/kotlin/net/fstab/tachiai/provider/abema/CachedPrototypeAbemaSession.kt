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
import android.view.ViewGroup
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
import net.fstab.tachiai.platform.media.NativeDashEvent
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.platform.media.PrototypeFeedSession
import net.fstab.tachiai.platform.media.awaitOpaqueResponse
import net.fstab.tachiai.platform.web.configureSecureSettings
import org.json.JSONObject
import org.json.JSONTokener

// Minimal app-owned bootstrap, not a provider player page. Each slot owns its
// unchanged cached helper, anonymous bootstrap state and fresh one-exchange CDM.
// Historical page-backed PrototypeAbemaSession remains a separate example.
@UnstableApi
internal class CachedPrototypeAbemaSession(
    context: Context,
    private val replay: Boolean,
    private val active: () -> Boolean,
    private val onEvent: (PrototypeFeedEvent) -> Unit,
) : PrototypeFeedSession {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
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

    override val providerView: View = WebView(context)
    private val view get() = providerView as WebView
    override val member: NativePairMember? get() = nativeHost
    override val player: Player? get() = nativeHost?.player

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        configureBrowser()
    }

    private fun allows(value: String?) = value == AbemaNativeBootstrapPolicy.PAGE
    private fun preparationCurrent(run: Long) = !closed.get() && revision.get() == run && active() &&
        budget?.active == true && SystemClock.elapsedRealtime() < deadline
    private fun current(run: Long) = preparationCurrent(run) && routeCurrent.get()
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
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && allows(request.url.toString()) && !documentStarted) return false
                fail(); return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = runCatching { URI(request.url.toString()) }.getOrNull()
                val live = preparationCurrent(revision.get())
                val allowed = uri != null && live &&
                    AbemaNativeBootstrapPolicy.allows(uri, request.isForMainFrame, request.method)
                if (!allowed) {
                    val category = if (!live) "LIFECYCLE" else
                        AbemaNativeBootstrapPolicy.refusalCategory(uri, request.isForMainFrame, request.method)
                    handler.post { if (!closed.get()) { record("resource=REFUSED category=$category"); fail() } }
                    return refusedResource()
                }
                val local = ownedResources[uri.path]
                if (uri.host == "abema.tv" && uri.path.startsWith("/_tachiai/native/")) {
                    if (local == null) {
                        handler.post { if (!closed.get()) fail() }
                        return refusedResource()
                    }
                    return WebResourceResponse(if (request.isForMainFrame) "text/html" else "application/javascript", "UTF-8",
                        200, "OK", mapOf("Content-Security-Policy" to AbemaNativeBootstrapPolicy.CONTENT_SECURITY_POLICY,
                            "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), ByteArrayInputStream(local))
                }
                return null // Only exact policy-approved provider API endpoints.
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!documentStarted && (url == null || url == "about:blank")) return
                if (closed.get()) return
                if (!allows(url) || documentStarted) { fail(); return }
                documentStarted = true
                routeCurrent.set(true)
            }
            override fun onPageFinished(view: WebView, url: String?) { if (!allows(url) && documentStarted) fail() }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { if (!allows(url) && documentStarted) fail() }
            override fun onReceivedSslError(view: WebView, ssl: SslErrorHandler, error: SslError) { ssl.cancel(); fail() }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { fail(); return true }
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
        handler.postDelayed({ if (!closed.get() && revision.get() == run) fail() }, budget.remainingMs)
        handler.postDelayed({ if (!closed.get() && revision.get() == run && !preparingNative) fail() },
            maxOf(0L, readinessDeadline - SystemClock.elapsedRealtime()))
        worker.execute {
            fun cached(identity: AbemaPublicBundleIdentity): AbemaBundlePrepared {
                val http = AbemaPublicBundleHttp(
                    canRun = { preparationCurrent(run) && SystemClock.elapsedRealtime() < readinessDeadline },
                    identity = identity,
                )
                bundleTransport.set(http)
                return try {
                    AbemaPublicBundleCache(File(view.context.cacheDir, "abema-helper-bundle"),
                        { preparationCurrent(run) && SystemClock.elapsedRealtime() < readinessDeadline }, identity)
                        .prepare(http::fetch)
                } finally {
                    bundleTransport.compareAndSet(http, null)
                    http.close()
                }
            }
            try {
                check(preparationCurrent(run) && CookieHandler.getDefault() == null)
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
                        .replace("__NATIVE_REPLAY__", replay.toString()).toByteArray(Charsets.UTF_8),
                )
                handler.post {
                    if (preparationCurrent(run) && SystemClock.elapsedRealtime() < readinessDeadline) {
                        record("cache=${prepared.origin.name} support=${support.origin.name} legacy=${legacy.origin.name} utilities=${utilities.origin.name}")
                        ownedResources = resources
                        view.loadUrl(AbemaNativeBootstrapPolicy.PAGE)
                        waitForHelper(run, readinessDeadline)
                    }
                }
            } catch (_: Exception) {
                handler.post { if (!closed.get() && revision.get() == run) fail() }
            }
        }
    }

    private fun waitForHelper(run: Long, readinessDeadline: Long) {
        if (closed.get() || revision.get() != run) return
        if (!preparationCurrent(run) || SystemClock.elapsedRealtime() >= readinessDeadline) { fail(); return }
        if (!allows(view.url) || !routeCurrent.get()) {
            handler.postDelayed({ waitForHelper(run, readinessDeadline) }, 100); return
        }
        view.evaluateJavascript("window['$name']?.status() || 'UNAVAILABLE'") { raw ->
            if (!current(run)) return@evaluateJavascript
            val state = quoted(raw, 100)?.takeIf { it in setOf("WAITING_SESSION", "WAITING_SOURCE", "READY",
                "WORKING", "RESPONSE", "FAILED", "STOPPED", "UNAVAILABLE") } ?: "FAILED"
            if (lastHelperState != state) { lastHelperState = state; record("helper=$state") }
            pollMetadata(run) {
                when (state) {
                    "READY" -> if (!preparingNative) { preparingNative = true; prepareNative(run) }
                    "WAITING_SESSION", "WAITING_SOURCE", "UNAVAILABLE" -> {
                        emit(PrototypeFeedEvent.WAITING_PROVIDER)
                        handler.postDelayed({ waitForHelper(run, readinessDeadline) }, 100)
                    }
                    else -> fail()
                }
            }
        }
    }

    private fun pollMetadata(run: Long, onRead: () -> Unit) {
        if (!current(run) || !allows(view.url)) return
        view.evaluateJavascript("window['$name']?.metadata() || null") { raw ->
            if (!current(run) || !allows(view.url)) return@evaluateJavascript
            if (raw == "null") { onRead(); return@evaluateJavascript }
            val value = quoted(raw, 512)?.let { runCatching { JSONObject(it) }.getOrNull() }
            val phase = value?.opt("phase") as? String
            val modules = value?.opt("modules") as? Int
            val lastModule = value?.opt("lastModule") as? Int
            val http = value?.opt("http") as? Int
            if (value?.length() != 4 || phase !in setOf("MODULES", "GUEST", "MEDIA_TOKEN", "DEVICE",
                "CONTENT", "REPLAY_FREE", "REPLAY_WINDOW", "REPLAY_RESTRICTIONS", "REPLAY_TERMS",
                "REPLAY_RENTAL", "REPLAY_PRECEDENCE", "REPLAY_DEVICE", "REPLAY_TRIAL",
                "REPLAY_PARTNER", "REPLAY_EXTERNAL_CONTENT", "REPLAY_EXTERNAL_PROVIDER", "REPLAY_AUTHORITIES",
                "SOURCE_SESSION_REQUIRED", "SOURCE_MODE_UNSUPPORTED",
                "SOURCE", "CONFIG", "LICENSE", "READY", "FAILED") ||
                modules == null || modules !in 0..4096 || lastModule == null || lastModule !in 0..1_000_000 ||
                http == null || (http != 0 && http !in 100..599)) {
                record("metadata=REFUSED"); fail(); return@evaluateJavascript
            }
            val diagnostic = "phase=$phase modules=$modules lastModule=$lastModule http=$http"
            if (lastMetadata != diagnostic) { lastMetadata = diagnostic; record(diagnostic) }
            onRead()
        }
    }

    private fun prepareNative(run: Long) {
        if (!current(run) || !allows(view.url)) { fail(); return }
        emit(PrototypeFeedEvent.PREPARING)
        worker.execute {
            try {
                check(current(run) && CookieHandler.getDefault() == null)
                val uri = selectedSource(run, minOf(deadline, SystemClock.elapsedRealtime() + 10_000))
                    ?: throw IllegalStateException()
                val requests = BoundedMediaRequests(checkNotNull(budget).child(), { current(run) && it == uri }, { event, code ->
                    record("manifest=${event.name} code=$code")
                }, canRequest = { current(run) })
                sourceRequests.set(requests)
                val source = try { readSource(uri, requests) }
                finally { sourceRequests.compareAndSet(requests, null); requests.close() }
                check(current(run) && selectedSource(run, minOf(deadline, SystemClock.elapsedRealtime() + 10_000)) == uri)
                handler.post { if (current(run) && allows(view.url)) createNative(source, run) }
            } catch (_: Exception) {
                handler.post { if (!closed.get() && revision.get() == run) fail() }
            }
        }
    }

    private fun readSource(uri: URI, requests: BoundedMediaRequests): AbemaNativeDashSource {
        if (replay) return resolveAbemaReplayNativeSource(uri, requests, AbemaReplayCdnScope.APPROVED_PROTOTYPE)
        check(AbemaNativeBootstrapPolicy.allowsNewsSource(uri, approvedPrototype = true))
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
        val policy = DeclaredDashMediaPolicy(source.uri) { uri ->
            reviewCdn(uri, AbemaCdnStage.DECLARED_MEDIA)
            if (replay) {
                abemaReplayCdnUriPolicy(uri, AbemaReplayCdnScope.APPROVED_PROTOTYPE) == AbemaReplayUriPolicy.ALLOWED
            }
            else AbemaNativeBootstrapPolicy.allowsNewsCdn(uri, approvedPrototype = true)
        }
        val transitions = if (replay) null else AbemaManifestTransitionPolicy(source.kids)
        val parser = BoundedDashManifestParser(source.uri, policy,
            preflight = { body -> transitions?.accepts(body) ?: (parseAbemaRequestInitialization(body)?.kids?.toSet() == source.kids.toSet()) },
            canRun = { current(run) }, onEvent = { _, _ -> },
            validateModel = { parsed -> transitions?.requiresDeclarationFreeModel != true ||
                (0 until parsed.periodCount).all { period -> parsed.getPeriod(period).adaptationSets.all { adaptation ->
                    adaptation.representations.all { it.format.drmInitData == null }
                } } })
        try {
            val created = BoundedNativeDashPlayer(view.context, checkNotNull(budget), source.kids,
                allowedUri = policy::allows, manifestUri = source.uri, manifestParser = parser,
                exchange = { challenge -> broker(challenge, run, minOf(deadline, SystemClock.elapsedRealtime() + 30_000)) },
                onEvent = { event, _ -> handler.post {
                    if (!current(run)) return@post
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
                } }, onMediaEvent = { event, code -> if (current(run)) record("media=${event.name} code=$code") },
                beforeRelease = { policy.close(); pending.getAndSet(null)?.complete(null) },
                keepDrmSessionForClearTransitions = !replay,
                initialDrmFormat = if (replay) null else Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setDrmInitData(DrmInitData(C.CENC_TYPE_cenc, DrmInitData.SchemeData(
                        C.COMMON_PSSH_UUID, MimeTypes.VIDEO_MP4, commonPssh(source.kids)))).build())
            nativeHost = created
            created.start(source.uri, playWhenReady = false)
        } catch (_: Exception) { policy.close(); fail() }
    }

    private fun selectedSource(run: Long, sourceDeadline: Long): URI? {
        val completion = CompletableFuture<URI?>()
        check(sourcePending.compareAndSet(null, completion))
        handler.post {
            if (!current(run) || SystemClock.elapsedRealtime() >= sourceDeadline || !allows(view.url)) completion.complete(null)
            else view.evaluateJavascript("window['$name']?.selectedSource() || null") { raw ->
                if (!current(run) || SystemClock.elapsedRealtime() >= sourceDeadline || !allows(view.url)) completion.complete(null)
                else {
                    val uri = runCatching { URI(quoted(raw, 12_000) ?: "") }.getOrNull()
                    if (uri != null) {
                        try { reviewCdn(uri, AbemaCdnStage.SELECTED_MANIFEST) }
                        catch (_: Exception) {
                            record("cdnReview=UNAVAILABLE"); completion.complete(null); return@evaluateJavascript
                        }
                    }
                    val allowed = uri != null && if (replay)
                        abemaReplaySelectedSourcePolicy(uri, AbemaReplayCdnScope.APPROVED_PROTOTYPE) == AbemaReplayUriPolicy.ALLOWED
                    else AbemaNativeBootstrapPolicy.allowsNewsSource(uri, approvedPrototype = true)
                    record("source=${if (allowed) "ALLOWED" else "REFUSED"}")
                    if (!allowed && replay) {
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
        val completion = CompletableFuture<ByteArray?>()
        check(pending.compareAndSet(null, completion))
        val encoded = Base64.encodeToString(challenge, Base64.NO_WRAP)
        handler.post {
            if (!current(run) || !allows(view.url)) completion.complete(null)
            else view.evaluateJavascript("window['$name']?.begin('$encoded') || 'REFUSED'") { raw ->
                if (!current(run) || !allows(view.url) || quoted(raw, 100) != "WORKING") completion.complete(null)
                else pollResponse(completion, run, exchangeDeadline)
            }
        }
        return try { awaitOpaqueResponse(completion, exchangeDeadline - SystemClock.elapsedRealtime()) }
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
                else -> completion.complete(null)
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
            record("cdnReview=PENDING stage=${stage.name}")
        }
    }

    private fun fail() = finish(if (networkReviewNeeded.get()) PrototypeFeedEvent.NETWORK_APPROVAL_REQUIRED else PrototypeFeedEvent.FAILED)
    override fun close() = finish(PrototypeFeedEvent.STOPPED)

    private fun finish(event: PrototypeFeedEvent) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { finish(event) }; return }
        if (!closed.compareAndSet(false, true)) return
        revision.incrementAndGet()
        routeCurrent.set(false)
        handler.removeCallbacksAndMessages(null)
        sourcePending.getAndSet(null)?.complete(null)
        pending.getAndSet(null)?.complete(null)
        var cleanupFailed = false
        for (cleanup in listOf<() -> Unit>(
            { bundleTransport.getAndSet(null)?.close() },
            { sourceRequests.getAndSet(null)?.close() },
            { if (allows(view.url)) view.evaluateJavascript("window['$name']?.stop()", null) },
            { nativeHost?.close() },
            { view.stopLoading() }, { view.onPause() },
            { (view.parent as? ViewGroup)?.removeView(view) },
            { view.webChromeClient = null; view.destroy() },
            { ownedResources = emptyMap() },
            { worker.shutdownNow() },
        )) {
            try { cleanup() } catch (_: Exception) { cleanupFailed = true }
        }
        nativeHost = null
        emit(if (cleanupFailed) PrototypeFeedEvent.FAILED else event)
    }
}
