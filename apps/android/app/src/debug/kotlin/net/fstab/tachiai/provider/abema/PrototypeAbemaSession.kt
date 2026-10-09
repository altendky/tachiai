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
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
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
import net.fstab.tachiai.platform.media.DashManifestPolicyEvent
import net.fstab.tachiai.platform.media.NativeDashEvent
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.NativeMediaEvent
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.platform.media.PrototypeFeedSession
import net.fstab.tachiai.platform.media.awaitOpaqueResponse
import net.fstab.tachiai.platform.net.AccessProbeHttp
import net.fstab.tachiai.platform.web.MediaPermissionPolicy
import net.fstab.tachiai.platform.web.configureSecureSettings
import net.fstab.tachiai.platform.web.userAgentFor
import net.fstab.tachiai.provider.BrowserIdentity
import net.fstab.tachiai.presentation.PrototypeFeedFailure
import net.fstab.tachiai.presentation.PrototypeFeedFailureLatch
import net.fstab.tachiai.presentation.PrototypeFailureReason
import org.json.JSONObject
import org.json.JSONTokener
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage
import net.fstab.tachiai.platform.diagnostics.nativeObserver

// Additive prototype adapter, not a replacement for historical experiments.
// Each slot owns an original page/helper and a fresh, one-exchange native CDM.
// Source/challenge/response values never leave this adapter or enter logs.
@UnstableApi
internal class PrototypeAbemaSession(
    context: Context,
    private val replay: Boolean,
    private val active: () -> Boolean,
    private val onEvent: (PrototypeFeedEvent) -> Unit,
    private val diagnostics: FailureReporter = FailureReporter.NONE,
) : PrototypeFeedSession {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private val revision = AtomicLong()
    private val routeCurrent = AtomicBoolean(false)
    private val transport = AtomicReference<AccessProbeHttp?>()
    private val sourceRequests = AtomicReference<BoundedMediaRequests?>()
    private val sourcePending = AtomicReference<CompletableFuture<URI?>?>()
    private val pending = AtomicReference<CompletableFuture<ByteArray?>?>()
    private val armPending = AtomicReference<CompletableFuture<Boolean>?>()
    private val nonce = UUID.randomUUID().toString().replace("-", "")
    private val name = "__tachiaiOpaque_$nonce"
    // Same two exact bindings as the preserved historical cases; no free-form
    // locator, account session transfer or shared opaque exchange is admitted.
    private val route = if (replay) "https://abema.tv/video/episode/394-72_s10_p8529"
        else "https://abema.tv/now-on-air/abema-news"
    private var registration: ScriptHandler? = null
    @Volatile private var nativeHost: BoundedNativeDashPlayer? = null
    @Volatile private var budget: NativePlaybackBudget? = null
    @Volatile private var deadline = 0L
    private var started = false
    private var documentStarted = false
    private var preparingNative = false
    private var lastEvent: PrototypeFeedEvent? = null
    private var lastHelperState: String? = null
    private val firstFailure = PrototypeFeedFailureLatch()
    override val failure: PrototypeFeedFailure? get() = firstFailure.failure
    override var cleanupFailed: Boolean = false
        private set

    override val providerView: View = WebView(context)
    private val view get() = providerView as WebView
    override val member: NativePairMember? get() = nativeHost
    override val player: Player? get() = nativeHost?.player

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        configureBrowser(context)
    }

    private fun allows(value: String?) = value == route
    private fun current(run: Long) = canContinue() && revision.get() == run
    override fun canContinue(): Boolean = !closed.get() && active() && routeCurrent.get() &&
        budget?.active == true && SystemClock.elapsedRealtime() < deadline
    override fun checkAuthorization(): Boolean = canContinue()

    private fun emit(event: PrototypeFeedEvent) {
        if (lastEvent == event) return
        lastEvent = event
        onEvent(event)
    }

    @SuppressLint("MissingOnRenderProcessGone")
    private fun configureBrowser(context: Context) {
        // The Activity selects its unique process/profile before construction.
        // No cookie/profile transfer, generic JS bridge or DevTools is enabled.
        WebView.setWebContentsDebuggingEnabled(false)
        view.configureSecureSettings()
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = true
        // The desktop provider page otherwise clips its onboarding/play controls
        // in narrow setup panes. This is presentation scale, not media capture.
        view.setInitialScale(35)
        view.settings.userAgentString = userAgentFor(BrowserIdentity.DESKTOP_CHROME, view.settings.userAgentString)
        view.settings.setSupportMultipleWindows(true)
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean = true
            override fun onPermissionRequest(request: PermissionRequest) {
                val granted = runCatching {
                    MediaPermissionPolicy.grantProtectedMediaOnly(AbemaAdapter, URI(request.origin.toString()),
                        request.resources, PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
                }.getOrDefault(emptyArray())
                if (granted.isEmpty()) request.deny() else request.grant(granted)
            }
            override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, result: android.os.Message): Boolean = false
            override fun onJsAlert(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean { result.cancel(); return true }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean { result.cancel(); return true }
            override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean { result.cancel(); return true }
            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: android.webkit.JsPromptResult): Boolean { result.cancel(); return true }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame || allows(request.url.toString())) return false
                fail(PrototypeFeedFailure(PrototypeFailureReason.MEDIA_BLOCKED)); return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!request.isForMainFrame || allows(request.url.toString())) return null
                handler.post { fail(PrototypeFeedFailure(PrototypeFailureReason.MEDIA_BLOCKED)) }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!documentStarted && (url == null || url == "about:blank")) return
                if (closed.get()) return
                // A second document must not inherit the first helper/session.
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
                fail(); return true
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val script = context.assets.open("abema/opaque-broker.js").bufferedReader().use { it.readText() }
                .replace("__NONCE__", nonce)
                .replace("__SHARED_DASH_CAPTURE__", "true")
                .replace("__REPLAY_SOURCE_PROBE__", "false")
                .replace("__NATIVE_REPLAY__", replay.toString())
                .replace("__ALIGNED_INITIAL_LIFETIME__", "true")
            registration = WebViewCompat.addDocumentStartJavaScript(view, script, setOf("https://abema.tv"))
        }
    }

    override fun prepare(budget: NativePlaybackBudget) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (started || closed.get()) return
        started = true
        this.budget = budget
        deadline = SystemClock.elapsedRealtime() + budget.remainingMs
        if (!BuildConfig.DEBUG || registration == null || !active() || !budget.active) { fail(); return }
        emit(PrototypeFeedEvent.PREPARING)
        val run = revision.incrementAndGet()
        handler.postDelayed({ if (!closed.get() && revision.get() == run)
            fail(PrototypeFeedFailure(PrototypeFailureReason.PLAYBACK_LIMIT)) }, budget.remainingMs)
        val readinessDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 90_000)
        handler.postDelayed({
            if (!closed.get() && revision.get() == run && !preparingNative)
                fail(PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_TIMEOUT))
        }, maxOf(0L, readinessDeadline - SystemClock.elapsedRealtime()))
        view.loadUrl(route)
        waitForHelper(run, readinessDeadline)
    }

    private fun waitForHelper(run: Long, readinessDeadline: Long) {
        if (closed.get() || revision.get() != run) return
        if (!active() || budget?.active != true || SystemClock.elapsedRealtime() >= readinessDeadline) {
            fail(PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_TIMEOUT)); return
        }
        if (!allows(view.url) || !routeCurrent.get()) {
            handler.postDelayed({ waitForHelper(run, readinessDeadline) }, 500)
            return
        }
        view.evaluateJavascript("window['$name']?.status() || 'UNAVAILABLE'") { raw ->
            if (!current(run)) return@evaluateJavascript
            val state = quoted(raw, 100)?.takeIf { it in setOf("READY", "CAPTURING", "WAITING_PLAYER",
                "FACTORY_DASH", "FACTORY_NON_DASH", "DASH_CREATE", "DASH_LOAD_PENDING", "WAITING_CONFIG",
                "WAITING_TRANSPORT", "TRANSPORT_REFUSED", "UNAVAILABLE", "STOPPED", "STALE", "FAILED") } ?: "UNAVAILABLE"
            if (lastHelperState != state) {
                lastHelperState = state
                Log.d("TachiaiPrototypeAbema", "kind=${if (replay) "REPLAY" else "LIVE"} helper=$state")
            }
            when (state) {
                "READY" -> if (!preparingNative) { preparingNative = true; armAndPrepare(run) }
                "TRANSPORT_REFUSED" -> fail(PrototypeFeedFailure(PrototypeFailureReason.UNSUPPORTED_MEDIA))
                "FAILED", "STALE", "STOPPED" -> fail()
                else -> {
                    emit(PrototypeFeedEvent.WAITING_PROVIDER)
                    handler.postDelayed({ waitForHelper(run, readinessDeadline) }, 500)
                }
            }
        }
    }

    private fun armAndPrepare(run: Long) {
        if (!current(run) || !allows(view.url)) { fail(); return }
        emit(PrototypeFeedEvent.PREPARING)
        val arm = CompletableFuture<Boolean>()
        if (!armPending.compareAndSet(null, arm)) { fail(); return }
        val armDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 3_000)
        val remaining = minOf(checkNotNull(budget).remainingMs, maxOf(0L, deadline - SystemClock.elapsedRealtime()))
        view.evaluateJavascript("window['$name']?.armInitialExchange($remaining) === true") { raw ->
            arm.complete(current(run) && allows(view.url) && SystemClock.elapsedRealtime() < armDeadline && raw == "true")
        }
        handler.postDelayed({ arm.complete(false) }, maxOf(0L, armDeadline - SystemClock.elapsedRealtime()))
        worker.execute {
            try {
                check(current(run) && CookieHandler.getDefault() == null)
                check(arm.get(maxOf(1L, armDeadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS))
                Log.d("TachiaiPrototypeAbema", "phase=INITIAL_ARM_ACCEPTED")
                val source = resolveSource(run)
                Log.d("TachiaiPrototypeAbema", "phase=SOURCE_RESOLVED")
                check(current(run))
                if (replay) check(selectedReplaySource(run, minOf(deadline, SystemClock.elapsedRealtime() + 10_000)) == source.uri)
                handler.post { if (current(run) && allows(view.url)) createNative(source, run) }
            } catch (error: Exception) {
                if (current(run)) diagnostics.report(FailureStage.ABEMA_SOURCE_PREPARE, error)
                handler.post { if (!closed.get() && revision.get() == run) fail() }
            } finally {
                armPending.compareAndSet(arm, null)
                try { transport.getAndSet(null)?.close() }
                catch (error: Exception) {
                    diagnostics.report(FailureStage.BUNDLE_TRANSPORT_CLOSE, error)
                    throw error
                }
            }
        }
    }

    private fun resolveSource(run: Long): AbemaNativeDashSource {
        if (!replay) {
            val http = AccessProbeHttp(canRequest = { current(run) })
            transport.set(http)
            return http.use { resolveAbemaNewsNativeSource(it) }
        }
        val uri = selectedReplaySource(run, minOf(deadline, SystemClock.elapsedRealtime() + 10_000))
            ?: throw IllegalStateException()
        val requests = BoundedMediaRequests(checkNotNull(budget).child(), { it == uri }, { event, code ->
            Log.d("TachiaiPrototypeAbema", "phase=REPLAY_MANIFEST event=${event.name} code=$code")
            rememberMedia(event, code)
        })
        sourceRequests.set(requests)
        return try {
            val source = resolveAbemaReplayNativeSource(uri, requests)
            check(current(run) && selectedReplaySource(run, minOf(deadline, SystemClock.elapsedRealtime() + 10_000)) == uri)
            source
        } finally { sourceRequests.compareAndSet(requests, null); requests.close() }
    }

    private fun createNative(source: AbemaNativeDashSource, run: Long) {
        val selectedBudget = checkNotNull(budget)
        val policy = DeclaredDashMediaPolicy(source.uri) { uri ->
            if (replay) abemaReplayCdnUriPolicy(uri) == AbemaReplayUriPolicy.ALLOWED
            else abemaNewsCdnUriPolicy(uri) == AbemaNewsMediaUriPolicy.ALLOWED
        }
        val transitions = if (replay) null else AbemaManifestTransitionPolicy(source.kids)
        val parser = BoundedDashManifestParser(source.uri, policy,
            preflight = { body ->
                if (transitions != null) transitions.accepts(body)
                else parseAbemaRequestInitialization(body)?.kids?.toSet() == source.kids.toSet()
            }, canRun = { current(run) }, onEvent = { event, _ ->
                if (event == DashManifestPolicyEvent.REFUSED) remember(PrototypeFeedFailure(PrototypeFailureReason.UNSUPPORTED_MEDIA))
            },
            validateModel = { parsed ->
                transitions?.requiresDeclarationFreeModel != true ||
                    (0 until parsed.periodCount).all { period ->
                        parsed.getPeriod(period).adaptationSets.all { adaptation ->
                            adaptation.representations.all { it.format.drmInitData == null }
                        }
                    }
            })
        try {
            val created = BoundedNativeDashPlayer(view.context, selectedBudget, source.kids,
                allowedUri = policy::allows, manifestUri = source.uri, manifestParser = parser,
                exchange = { challenge -> broker(challenge, run, minOf(deadline, SystemClock.elapsedRealtime() + 30_000)) },
                onEvent = { event, code ->
                    rememberNative(event, code)
                    handler.post {
                        if (closed.get() || revision.get() != run || !active()) return@post
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
                }, onMediaEvent = ::rememberMedia,
                beforeRelease = { policy.close(); pending.getAndSet(null)?.complete(null) },
                keepDrmSessionForClearTransitions = !replay,
                initialDrmFormat = if (replay) null else Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setDrmInitData(DrmInitData(C.CENC_TYPE_cenc, DrmInitData.SchemeData(
                        C.COMMON_PSSH_UUID, MimeTypes.VIDEO_MP4, commonPssh(source.kids))))
                    .build(), onFailure = diagnostics.nativeObserver())
            nativeHost = created
            created.start(source.uri, playWhenReady = false)
        } catch (error: Exception) {
            if (current(run)) diagnostics.report(FailureStage.ABEMA_PLAYER_CREATE, error)
            policy.close()
            fail()
        }
    }

    private fun selectedReplaySource(run: Long, sourceDeadline: Long): URI? {
        val completion = CompletableFuture<URI?>()
        check(sourcePending.compareAndSet(null, completion))
        handler.post {
            if (!current(run) || SystemClock.elapsedRealtime() >= sourceDeadline || !allows(view.url)) completion.complete(null)
            else view.evaluateJavascript("window['$name']?.selectedSource() || null") { raw ->
                if (!current(run) || SystemClock.elapsedRealtime() >= sourceDeadline || !allows(view.url)) completion.complete(null)
                else {
                    val uri = try { URI(quoted(raw, 12_000) ?: "") } catch (_: Exception) { null }
                    val policy = uri?.let(::abemaReplaySelectedSourcePolicy)
                    Log.d("TachiaiPrototypeAbema", "phase=REPLAY_SOURCE policy=${policy?.name ?: "UNAVAILABLE"}")
                    if (uri != null && policy != AbemaReplayUriPolicy.ALLOWED)
                        remember(PrototypeFeedFailure(PrototypeFailureReason.MEDIA_BLOCKED))
                    completion.complete(uri?.takeIf { policy == AbemaReplayUriPolicy.ALLOWED })
                }
            }
        }
        return try { completion.get(maxOf(1, sourceDeadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS) }
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
                else pollResponse(completion, run)
            }
        }
        return try { awaitOpaqueResponse(completion, exchangeDeadline - SystemClock.elapsedRealtime()) }
        finally { pending.compareAndSet(completion, null) }
    }

    private fun pollResponse(completion: CompletableFuture<ByteArray?>, run: Long) {
        if (completion.isDone) return
        if (!current(run) || !allows(view.url)) { completion.complete(null); return }
        view.evaluateJavascript("window['$name']?.status() || 'UNAVAILABLE'") { raw ->
            if (!current(run) || !allows(view.url)) completion.complete(null)
            else when (quoted(raw, 100)) {
                "WORKING" -> handler.postDelayed({ pollResponse(completion, run) }, 100)
                "RESPONSE" -> view.evaluateJavascript("window['$name']?.take()") { opaque ->
                    val encoded = quoted(opaque, 90_000)
                    val response = try {
                        if (encoded == null || !encoded.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) null
                        else Base64.decode(encoded, Base64.NO_WRAP).let { bytes ->
                            if (bytes.size in 1..65536 && Base64.encodeToString(bytes, Base64.NO_WRAP) == encoded) bytes
                            else { bytes.fill(0); null }
                        }
                    } catch (_: Exception) { null }
                    if (!current(run) || !allows(view.url) || !completion.complete(response)) response?.fill(0)
                    if (!current(run)) completion.complete(null)
                }
                else -> completion.complete(null)
            }
        }
    }

    override fun pauseOriginal(onResult: (Boolean) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val run = revision.get()
        if (!current(run) || !allows(view.url) || nativeHost?.timingSnapshot()?.state != Player.STATE_READY) {
            onResult(false); return
        }
        val completed = AtomicBoolean(false)
        fun complete(value: Boolean) { if (completed.compareAndSet(false, true)) onResult(value) }
        handler.postDelayed({ complete(false) }, 3_000)
        // Only exact playback pages; no credentials, login DOM or media capture.
        if (!replay) view.evaluateJavascript(abemaNewsPauseOriginalVideoScript()) { raw ->
            complete(current(run) && allows(view.url) && raw == "true")
        } else view.evaluateJavascript(abemaReplayMuteOriginalVideoScript()) {
            if (!current(run) || !allows(view.url)) { complete(false); return@evaluateJavascript }
            view.evaluateJavascript(abemaReplayOriginalTimingScript(true)) { raw ->
                val state = quoted(raw, 256)?.let { runCatching { JSONObject(it) }.getOrNull() }
                complete(current(run) && allows(view.url) && state?.length() == 3 &&
                    state.opt("paused") == true && state.opt("muted") == true)
            }
        }
    }

    private fun quoted(raw: String?, limit: Int): String? = try {
        if (raw == null || raw.length > limit) null else JSONTokener(raw).nextValue() as? String
    } catch (_: Exception) { null }

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
        remember(value)
        finish(PrototypeFeedEvent.FAILED)
    }
    override fun close() = finish(PrototypeFeedEvent.STOPPED)

    private fun finish(event: PrototypeFeedEvent) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { finish(event) }; return }
        if (!closed.compareAndSet(false, true)) return
        revision.incrementAndGet()
        routeCurrent.set(false)
        handler.removeCallbacksAndMessages(null)
        armPending.getAndSet(null)?.complete(false)
        sourcePending.getAndSet(null)?.complete(null)
        pending.getAndSet(null)?.complete(null)
        // Seal the helper before release can wait for its native DRM worker.
        for ((stage, cleanup) in listOf<Pair<FailureStage, () -> Unit>>(
            FailureStage.BUNDLE_TRANSPORT_CLOSE to { transport.getAndSet(null)?.close() },
            FailureStage.SOURCE_REQUESTS_CLOSE to { sourceRequests.getAndSet(null)?.close() },
            FailureStage.HELPER_STOP to { if (allows(view.url)) view.evaluateJavascript("window['$name']?.stop()", null) },
            FailureStage.NATIVE_HOST_CLOSE to { nativeHost?.close() },
            FailureStage.SCRIPT_REGISTRATION_REMOVE to { registration?.remove() },
            FailureStage.WEBVIEW_STOP to { view.stopLoading() }, FailureStage.WEBVIEW_PAUSE to { view.onPause() },
            FailureStage.WEBVIEW_DETACH to { (view.parent as? ViewGroup)?.removeView(view) },
            FailureStage.WEBVIEW_DESTROY to { view.webChromeClient = null; view.destroy() },
            FailureStage.WORKER_SHUTDOWN to { worker.shutdownNow() },
        )) {
            if (!diagnostics.cleanup(stage, cleanup)) cleanupFailed = true
        }
        nativeHost = null; registration = null
        emit(event)
    }
}
