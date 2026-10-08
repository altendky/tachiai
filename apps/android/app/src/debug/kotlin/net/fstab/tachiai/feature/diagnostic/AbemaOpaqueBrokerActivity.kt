package net.fstab.tachiai.feature.diagnostic

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
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
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.ui.PlayerView
import java.io.ByteArrayInputStream
import java.net.CookieHandler
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.R
import net.fstab.tachiai.platform.media.AndroidOpaqueClearKeyCdm
import net.fstab.tachiai.platform.media.ExchangeOutcome
import net.fstab.tachiai.platform.media.awaitOpaqueResponse
import net.fstab.tachiai.platform.media.BoundedNativeDashPlayer
import net.fstab.tachiai.platform.media.BoundedMediaRequests
import net.fstab.tachiai.platform.media.BoundedDashManifestParser
import net.fstab.tachiai.platform.media.DeclaredDashMediaPolicy
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.NativeDashEvent
import net.fstab.tachiai.platform.media.BoundedNativePlayer
import net.fstab.tachiai.platform.media.NativeMediaEvent
import net.fstab.tachiai.platform.media.NativeMixedPair
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.feature.presentation.NativePairViewer
import net.fstab.tachiai.platform.media.NativePlaybackAudioGroup
import net.fstab.tachiai.platform.media.NativePairPlayGate
import net.fstab.tachiai.platform.media.exchangeClearKeyOnce
import net.fstab.tachiai.platform.net.AccessProbeHttp
import net.fstab.tachiai.platform.net.AccessProbeOutcome
import net.fstab.tachiai.platform.web.MediaPermissionPolicy
import net.fstab.tachiai.platform.web.configureSecureSettings
import net.fstab.tachiai.platform.web.userAgentFor
import net.fstab.tachiai.provider.BrowserIdentity
import net.fstab.tachiai.provider.abema.AbemaAdapter
import net.fstab.tachiai.provider.abema.abemaDashClassification
import net.fstab.tachiai.provider.abema.commonPssh
import net.fstab.tachiai.provider.abema.parseAbemaRequestInitialization
import net.fstab.tachiai.provider.abema.probeAbemaNativeAccess
import net.fstab.tachiai.provider.abema.resolveAbemaNewsNativeSource
import net.fstab.tachiai.provider.abema.AbemaNativeDashSource
import net.fstab.tachiai.provider.abema.AbemaManifestTransitionPolicy
import net.fstab.tachiai.provider.abema.AbemaReplayUriPolicy
import net.fstab.tachiai.provider.abema.abemaReplayCdnUriPolicy
import net.fstab.tachiai.provider.abema.abemaReplaySelectedSourcePolicy
import net.fstab.tachiai.provider.abema.resolveAbemaReplayNativeSource
import net.fstab.tachiai.provider.abema.abemaReplayMuteOriginalVideoScript
import net.fstab.tachiai.provider.abema.abemaReplayOriginalTimingScript
import net.fstab.tachiai.provider.abema.abemaNewsCdnUriPolicy
import net.fstab.tachiai.provider.abema.AbemaNewsMediaUriPolicy
import net.fstab.tachiai.provider.abema.abemaNewsMuteOriginalVideoScript
import net.fstab.tachiai.provider.abema.abemaNewsPauseOriginalVideoScript
import net.fstab.tachiai.provider.twitch.NativePairTwitchPreparation
import net.fstab.tachiai.provider.twitch.allowedTwitchMediaUri
import org.json.JSONTokener
import org.json.JSONObject

// Debug-only isolated process/profile. No DevTools, addJavascriptInterface or opaque logging.
@UnstableApi
open class AbemaOpaqueBrokerActivity : ComponentActivity() {
    protected open val preliminaryViewer = false
    companion object { private var profileConfigured = false }
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val revision = AtomicLong()
    private val resumed = AtomicBoolean()
    private val running = AtomicBoolean()
    private val destroyed = AtomicBoolean()
    private val transport = AtomicReference<AccessProbeHttp?>()
    private val pending = AtomicReference<CompletableFuture<ByteArray?>?>()
    private val sourcePending = AtomicReference<CompletableFuture<URI?>?>()
    private val sourceRequests = AtomicReference<BoundedMediaRequests?>()
    private var browser: WebView? = null
    private var registration: ScriptHandler? = null
    private var name = ""
    private var lastMarker = ""
    private var lastMetadata = ""
    private var nativeResult: String? = null
    private var newsStarted = false
    private var originalOnly = false
    private var sharedDashCapture = false
    private var nativePlayback = false
    private var replaySourceProbe = false
    private var nativeReplay = false
    private var nativePair = false
    private var nativeTransitions = false
    private var nativeAlignedHelper = false
    private var nativePrewarm = false
    private var nativeRelativeControls = false
    private var nativeTwitchReplay = false
    private var nativePreparationDelayMs = 0L
    private var delayedNativePreparation: Runnable? = null
    private val pairAuthorization = AtomicReference<NativePairTwitchPreparation?>()
    private var twitchHost: BoundedNativePlayer? = null
    private var twitchSurface: PlayerView? = null
    private var mixedPair: NativeMixedPair? = null
    private var pairPanel: NativeMixedPairPanel? = null
    private var pairViewer: NativePairViewer? = null
    private var pairFocus: NativePlaybackAudioGroup? = null
    private var pairSurfaces: LinearLayout? = null
    private var pairLastSampleMs = 0L
    private var pairLastStorageCheckMs = 0L
    private val pairPlayGate = NativePairPlayGate()
    private val pairStorageChecking = AtomicBoolean(false)
    private val pairTicker = object : Runnable {
        override fun run() {
            val selected = mixedPair ?: return
            selected.poll()
            pairPanel?.refresh()
            pairViewer?.refresh()
            val now = SystemClock.elapsedRealtime()
            if (now - pairLastSampleMs >= 5_000) {
                pairLastSampleMs = now
                Log.d("TachiaiMixedPair", "side=A ${nativeTimingSummary(selected.a.timingSnapshot())}")
                Log.d("TachiaiMixedPair", "side=B ${nativeTimingSummary(selected.b.timingSnapshot())}")
            }
            if (now - pairLastStorageCheckMs >= 5_000 && pairStorageChecking.compareAndSet(false, true)) {
                pairLastStorageCheckMs = now
                val run = revision.get()
                val authorization = pairAuthorization.get()
                worker.execute {
                    try {
                        if (authorization?.checkStored(force = true) != true) handler.post {
                            if (revision.get() == run && mixedPair === selected) {
                                cancel(); nativeResult = "PAIR_AUTHORIZATION_ENDED"; marker(checkNotNull(nativeResult))
                            }
                        }
                    } finally { pairStorageChecking.set(false) }
                }
            }
            handler.postDelayed(this, 200)
        }
    }
    private var lastSourceMetadata = ""
    private var nativeHost: BoundedNativeDashPlayer? = null
    private var nativeSurface: PlayerView? = null
    private var nativeStatus: TextView? = null
    private var timingActionId = 0L
    private var webTimingActionId = 0L
    private val timingTicker = object : Runnable {
        override fun run() {
            val selected = nativeHost ?: return
            Log.d("TachiaiAbemaTiming", "sample ${nativeTimingSummary(selected.timingSnapshot())}")
            handler.postDelayed(this, 5_000)
        }
    }
    private lateinit var host: LinearLayout
    private lateinit var status: TextView
    private lateinit var start: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG || android.os.Build.VERSION.SDK_INT < 28) { finish(); return }
        originalOnly = intent.getBooleanExtra("net.fstab.tachiai.extra.ABEMA_BROKER_ORIGINAL_ONLY", false)
        replaySourceProbe = !originalOnly && intent.getBooleanExtra("net.fstab.tachiai.extra.ABEMA_BROKER_REPLAY_SOURCE_PROBE", false)
        nativeReplay = !originalOnly && !replaySourceProbe && intent.getBooleanExtra("net.fstab.tachiai.extra.ABEMA_BROKER_NATIVE_REPLAY", false)
        nativePlayback = !originalOnly && (nativeReplay || intent.getBooleanExtra("net.fstab.tachiai.extra.ABEMA_BROKER_NATIVE_PLAYBACK", false))
        val pairMode = intent.getStringExtra("net.fstab.tachiai.extra.ABEMA_NATIVE_PAIR")
        if (pairMode != null) {
            val selected = if (preliminaryViewer) abemaNativeViewerCase(pairMode) else abemaNativePairCase(pairMode)
            if (selected == null || originalOnly || replaySourceProbe) { finish(); return }
            nativePair = true; nativePlayback = true; nativeReplay = selected.replay
            nativeTransitions = selected.transitions
            nativeAlignedHelper = selected.alignedHelper
            nativePrewarm = selected.prewarm
            nativeRelativeControls = selected.relativeControls
            nativeTwitchReplay = selected.twitchReplay
            nativePreparationDelayMs = selected.preparationDelayMs
        }
        if (replaySourceProbe) nativePlayback = false
        // The separate viewer entry accepts only already bounded relative cases.
        if (preliminaryViewer && (!nativePair || !nativeRelativeControls)) { finish(); return }
        sharedDashCapture = nativePlayback || replaySourceProbe || intent.getBooleanExtra("net.fstab.tachiai.extra.ABEMA_BROKER_SHARED_DASH_CAPTURE", false)
        Log.d("TachiaiAbemaBroker", "capturePath=${if (originalOnly) "NONE" else if (sharedDashCapture) "SHARED_DASH" else "LEGACY_LOAD"}")
        if (!profileConfigured) { WebView.setDataDirectorySuffix("abema-opaque-broker"); profileConfigured = true }
        if (profileConfigured) WebView.setWebContentsDebuggingEnabled(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        host.addView(TextView(this).apply { setText(if (nativePair) R.string.native_mixed_warning else if (replaySourceProbe) R.string.abema_replay_source_warning else if (nativeReplay) R.string.abema_native_replay_warning else if (nativePlayback) R.string.abema_native_playback_warning else R.string.abema_broker_warning) })
        status = TextView(this).apply { setText(R.string.abema_request_ready) }
        host.addView(status)
        val controls = LinearLayout(this)
        start = Button(this).apply { setText(if (replaySourceProbe) R.string.abema_replay_source_check else if (nativePlayback) R.string.abema_native_playback_start else R.string.abema_broker_start); isEnabled = false; setOnClickListener { begin() } }
        controls.addView(start, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply { setText(R.string.abema_request_stop); setOnClickListener { cancel(); nativeResult = "STOPPED"; marker("STOPPED") } }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply {
            setText(R.string.abema_inspection_reload); setOnClickListener { cancel(); browser?.reload() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(Button(this).apply { setText(R.string.abema_inspection_close); setOnClickListener { finish() } }, LinearLayout.LayoutParams(0, -2, 1f))
        host.addView(controls)
        if (preliminaryViewer) host.addView(Button(this).apply {
            setText(R.string.native_viewer_return); setOnClickListener { if (mixedPair != null) pairViewer?.open() }
        })
        if (nativePlayback) {
            nativeStatus = TextView(this).also { host.addView(it) }
            if (nativePair) {
                pairPanel = NativeMixedPairPanel(this, { mixedPair }, onPlay = ::playPair,
                    onPause = ::pausePair,
                    onShift = { side, delta ->
                        pairPlayGate.invalidate()
                        val plan = mixedPair?.shift(side, delta)
                        Log.d("TachiaiMixedPair", "side=${side.name} shiftMs=$delta outcome=${plan?.outcome?.name} targetMs=${plan?.targetMs}")
                    }, onRelativeShift = if (nativeRelativeControls) ::shiftPairRelative else null,
                    onLiveRecovery = if (nativeRelativeControls) ::catchUpPair else null).also {
                        if (!preliminaryViewer) host.addView(it)
                    }
            } else {
            host.addView(LinearLayout(this).apply {
                addView(Button(this@AbemaOpaqueBrokerActivity).apply {
                    setText(R.string.abema_native_playback_mute_web)
                    setOnClickListener { browser?.takeIf { resumed.get() && allows(it.url) }
                        ?.evaluateJavascript(if (nativeReplay) abemaReplayMuteOriginalVideoScript() else abemaNewsMuteOriginalVideoScript(), null) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (nativeReplay) addView(Button(this@AbemaOpaqueBrokerActivity).apply {
                    setText(R.string.abema_native_playback_pause_web)
                    setOnClickListener { pauseOriginalReplay() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Button(this@AbemaOpaqueBrokerActivity).apply {
                    setText(R.string.abema_native_playback_mute)
                    setOnClickListener { nativeHost?.player?.volume = 0f }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Button(this@AbemaOpaqueBrokerActivity).apply {
                    setText(R.string.abema_native_playback_sound)
                    setOnClickListener { nativeHost?.player?.volume = 0.5f }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            host.addView(LinearLayout(this).apply {
                fun control(label: Int, action: String, operation: (BoundedNativeDashPlayer) -> String) {
                    addView(Button(this@AbemaOpaqueBrokerActivity).apply {
                        setText(label); setOnClickListener { timingAction(action, operation) }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
                control(R.string.abema_native_pause, "PAUSE") { "requested=${it.setTimingPlaying(false)}" }
                control(R.string.abema_native_play, "PLAY") { "requested=${it.setTimingPlaying(true)}" }
                control(R.string.abema_native_back, "BACK_5") { it.shiftByMs(-5_000).toString() }
                control(R.string.abema_native_forward, "FORWARD_5") { it.shiftByMs(5_000).toString() }
                control(R.string.abema_native_live, "LIVE_DEFAULT") { it.seekLiveDefault().toString() }
            })
            }
            nativeSurface = (if (preliminaryViewer) layoutInflater.inflate(R.layout.native_viewer_player, host, false) as PlayerView
                else PlayerView(this)).apply {
                useController = !nativePair; keepScreenOn = true
                visibility = View.GONE // Preserve the original page's startup viewport.
            }
            if (nativePair) {
                if (preliminaryViewer) {
                    twitchSurface = (layoutInflater.inflate(R.layout.native_viewer_player, host, false) as PlayerView).apply { keepScreenOn = true }
                    pairViewer = NativePairViewer(this, checkNotNull(nativeSurface), checkNotNull(twitchSurface),
                        "ABEMA", "Twitch", { mixedPair }, ::playPair, ::pausePair, ::shiftPairRelative, ::catchUpPair,
                        onDiagnostics = { pairViewer?.visibility = View.GONE },
                        onStop = { cancel(); nativeResult = "STOPPED"; marker("STOPPED") },
                        onLandscape = { landscape ->
                            requestedOrientation = if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }).apply { visibility = View.GONE }
                } else {
                pairSurfaces = LinearLayout(this).apply {
                    orientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                    visibility = View.GONE
                    val paneParams = if (orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -1, 1f)
                        else LinearLayout.LayoutParams(-1, 0, 1f)
                    addView(nativeSurface, paneParams)
                    twitchSurface = PlayerView(this@AbemaOpaqueBrokerActivity).apply { useController = false; keepScreenOn = true }
                    addView(twitchSurface, LinearLayout.LayoutParams(paneParams))
                }.also { host.addView(it, LinearLayout.LayoutParams(-1, 0, 3f)) }
                }
            } else host.addView(nativeSurface, LinearLayout.LayoutParams(-1, 0, 3f))
        }
        if (preliminaryViewer) setContentView(FrameLayout(this).apply {
            addView(host, FrameLayout.LayoutParams(-1, -1))
            addView(pairViewer, FrameLayout.LayoutParams(-1, -1))
        }) else setContentView(host)
    }

    // Shared by old diagnostics and viewer. Never bypass original web pause/mute
    // confirmation or the revision/source/lifecycle-bound asynchronous Play gate.
    private fun playPair() {
        val view = browser
        val selected = mixedPair
        if (view != null && selected != null && resumed.get() && allows(view.url)) {
            view.evaluateJavascript(if (nativeReplay) abemaReplayMuteOriginalVideoScript() else abemaNewsMuteOriginalVideoScript(), null)
            val run = revision.get()
            val completePlay = pairPlayGate.begin()
            fun play() {
                if (resumed.get() && revision.get() == run && browser === view && mixedPair === selected &&
                    allows(view.url) && completePlay()) {
                    Log.d("TachiaiMixedPair", "playRequested=${selected.play()}")
                    pairViewer?.refresh()
                }
            }
            if (nativeReplay) pauseOriginalReplay { play() }
            else view.evaluateJavascript(abemaNewsPauseOriginalVideoScript()) { paused ->
                if (paused == "true") play() else Log.d("TachiaiMixedPair", "originalPause=UNAVAILABLE")
            }
        }
    }
    private fun pausePair() { pairPlayGate.invalidate(); Log.d("TachiaiMixedPair", "pauseRequested=${mixedPair?.pause()}") }
    private fun shiftPairRelative(delta: Long) {
        pairPlayGate.invalidate()
        val plan = mixedPair?.shiftRelative(delta)
        val choice = mixedPair?.lastRelativePlan
        Log.d("TachiaiMixedPair", "relativeMs=$delta selection=${choice?.outcome?.name} side=${choice?.side?.name} movementMs=${choice?.movementMs} outcome=${plan?.outcome?.name} targetMs=${plan?.targetMs}")
    }
    private fun catchUpPair(side: NativeMixedSide) {
        pairPlayGate.invalidate()
        val plan = mixedPair?.catchUp(side)
        Log.d("TachiaiMixedPair", "catchUpSide=${side.name} outcome=${plan?.outcome?.name} targetMs=${plan?.targetMs}")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (preliminaryViewer) {
            pairViewer?.requestLayout()
            Log.d("TachiaiMixedPair", "viewerOrientation=${if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "LANDSCAPE" else "PORTRAIT"}")
        }
    }

    private fun marker(value: String) {
        if (destroyed.get()) return
        status.text = getString(R.string.abema_broker_status, value)
        if (lastMarker != value) { lastMarker = value; Log.d("TachiaiAbemaBroker", "broker=$value") }
    }

    private fun allows(value: String?): Boolean = value == (if (replaySourceProbe || nativeReplay) AbemaInspectionSource.REPLAY else AbemaInspectionSource.NEWS).url

    private fun timingAction(action: String, operation: (BoundedNativeDashPlayer) -> String) {
        val selected = nativeHost ?: return
        val run = revision.get()
        val id = ++timingActionId
        Log.d("TachiaiAbemaTiming", "action=$id before=$action ${nativeTimingSummary(selected.timingSnapshot())}")
        Log.d("TachiaiAbemaTiming", "action=$id request=$action ${operation(selected)}")
        handler.postDelayed({
            if (resumed.get() && revision.get() == run && nativeHost === selected && timingActionId == id)
                Log.d("TachiaiAbemaTiming", "action=$id after=$action ${nativeTimingSummary(selected.timingSnapshot())}")
        }, 1_500)
    }

    private fun initialBlank(value: String?): Boolean = !newsStarted && (value == null || value == "about:blank")

    private fun pauseOriginalReplay(onPaused: () -> Unit = {}) {
        val view = browser ?: return
        val selected = nativeHost ?: return
        if (!nativeReplay || !resumed.get() || !allows(view.url) ||
            selected.timingSnapshot()?.state != androidx.media3.common.Player.STATE_READY) return
        val run = revision.get()
        val id = ++webTimingActionId
        fun current() = resumed.get() && revision.get() == run && browser === view &&
            nativeHost === selected && webTimingActionId == id && allows(view.url)
        fun read(pause: Boolean) {
            if (!current()) return
            view.evaluateJavascript(abemaReplayOriginalTimingScript(pause)) { raw ->
                if (!current()) return@evaluateJavascript
                val value = quoted(raw, 256)?.let { runCatching { JSONObject(it) }.getOrNull() }
                val paused = value?.opt("paused") as? Boolean
                val muted = value?.opt("muted") as? Boolean
                val position = (value?.opt("positionMs") as? Number)?.toLong()?.takeIf { it in 0..86_400_000 }
                if (value?.length() == 3 && paused != null && muted != null) {
                    Log.d("TachiaiAbemaWeb", "action=$id pauseRequested=$pause paused=$paused muted=$muted positionMs=$position")
                    if (pause && paused && muted) onPaused()
                } else Log.d("TachiaiAbemaWeb", "action=$id readback=UNAVAILABLE")
            }
        }
        read(true)
        handler.postDelayed({ read(false) }, 5_000)
    }

    private fun refuseNavigation(phase: String, value: String?) {
        val kind = when {
            value == null || value == "about:blank" -> "BLANK"
            value.startsWith("https://abema.tv/") -> "OTHER_ABEMA_ROUTE"
            else -> "OTHER"
        }
        marker("NAVIGATION_REFUSED_${phase}_$kind")
        cancel(); finish()
    }

    @SuppressLint("MissingOnRenderProcessGone")
    private fun createBrowser() {
        if (!originalOnly && !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            marker("DOCUMENT_START_UNAVAILABLE"); return
        }
        val nonce = UUID.randomUUID().toString().replace("-", "")
        name = "__tachiaiOpaque_$nonce"
        lastMetadata = ""
        lastSourceMetadata = ""
        val script = assets.open("abema/opaque-broker.js").bufferedReader().use { it.readText() }
            .replace("__NONCE__", nonce).replace("__SHARED_DASH_CAPTURE__", sharedDashCapture.toString())
            .replace("__REPLAY_SOURCE_PROBE__", replaySourceProbe.toString())
            .replace("__NATIVE_REPLAY__", nativeReplay.toString())
            .replace("__ALIGNED_INITIAL_LIFETIME__", nativeAlignedHelper.toString())
        val view = WebView(this)
        browser = view
        newsStarted = false
        view.configureSecureSettings()
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = true
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
                refuseNavigation("OVERRIDE", request.url.toString()); return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!request.isForMainFrame || allows(request.url.toString())) return null
                handler.post { refuseNavigation("REQUEST", request.url.toString()) }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (initialBlank(url)) return
                cancel(stopHelper = false); nativeResult = null; marker("LOADING")
                if (!allows(url)) refuseNavigation("START", url) else newsStarted = true
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (!allows(url) && !initialBlank(url)) refuseNavigation("FINISH", url)
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (!allows(url) && !initialBlank(url)) refuseNavigation("HISTORY", url)
            }
            override fun onReceivedSslError(view: WebView, ssl: SslErrorHandler, error: SslError) { ssl.cancel(); cancel(); marker("TLS_REFUSED") }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                marker(if (detail.didCrash()) "RENDERER_CRASHED" else "RENDERER_LOST")
                cancel(); finish(); return true
            }
        }
        if (!originalOnly && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            registration = WebViewCompat.addDocumentStartJavaScript(view, script, setOf("https://abema.tv"))
        }
        host.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        view.loadUrl((if (replaySourceProbe || nativeReplay) AbemaInspectionSource.REPLAY else AbemaInspectionSource.NEWS).url)
        availability(view)
    }

    private fun quoted(raw: String?, limit: Int): String? = try {
        if (raw == null || raw.length > limit) null else JSONTokener(raw).nextValue() as? String
    } catch (_: Exception) { null }

    private fun availability(view: WebView) {
        if (!resumed.get() || browser !== view) return
        if (originalOnly) { marker("ORIGINAL_PAGE_ONLY"); return }
        if (!running.get() && nativeResult == null && allows(view.url)) {
            val observedRevision = revision.get()
            view.evaluateJavascript("window['$name']?.status() || 'UNAVAILABLE'") { raw ->
                if (resumed.get() && browser === view && revision.get() == observedRevision && !running.get() && nativeResult == null && allows(view.url)) {
                    val value = quoted(raw, 100)?.takeIf { it in setOf("READY", "CAPTURING", "WAITING_PLAYER", "FACTORY_DASH", "FACTORY_NON_DASH", "DASH_CREATE", "DASH_LOAD_PENDING", "WAITING_CONFIG", "WAITING_TRANSPORT", "TRANSPORT_REFUSED", "UNAVAILABLE", "STOPPED", "STALE", "FAILED") } ?: "UNAVAILABLE"
                    start.isEnabled = value == "READY"
                    marker(value)
                    view.evaluateJavascript("JSON.stringify(window['$name']?.metadata() || null)") { metadata ->
                        if (resumed.get() && browser === view && revision.get() == observedRevision && !running.get() && nativeResult == null && allows(view.url)) {
                            recordMetadata(metadata)
                            if (replaySourceProbe || nativeReplay) recordSourceMetadata(view, observedRevision)
                        }
                    }
                }
            }
        }
        handler.postDelayed({ availability(view) }, 500)
    }

    private fun recordMetadata(raw: String?) {
        val value = quoted(raw, 1024) ?: return
        val objectValue = try { JSONObject(value) } catch (_: Exception) { return }
        val names = listOf("factoryPresent", "hooksOwned", "topVideoPlayed", "childFrame", "priorCallbackPresent", "factoryCalled", "dashCreateCalled", "loadLibCalled", "loadResolved",
            "managerHooksPresent", "managerFactoryInstalled", "managerFactoryCalled", "managerSessionCalled", "dashNamespaceHookPresent", "dashNamespaceCalled", "rawDashCreated", "autoDashVideoPlayed",
            "requestFilterCalled", "requestBeforeConfiguration", "configuredRequestMatched", "capturedVideoPlayed", "encryptedEventSeen")
        if (objectValue.length() != names.size + 1 || names.any { objectValue.opt(it) !is Boolean }) return
        val installs = objectValue.opt("installCount") as? Int ?: return
        if (installs !in 0..2) return
        val closed = names.joinToString(" ") { "$it=${objectValue.getBoolean(it)}" } + " installCount=$installs"
        if (lastMetadata != closed) { lastMetadata = closed; Log.d("TachiaiAbemaBroker", "probe=$closed") }
    }

    private fun begin() {
        val view = browser ?: return
        if (originalOnly || !resumed.get() || !allows(view.url) || !start.isEnabled || !running.compareAndSet(false, true)) return
        if (replaySourceProbe) { running.set(false); recordSourceMetadata(view, revision.get()); return }
        if (nativePlayback) { beginNativePlayback(view); return }
        val run = revision.incrementAndGet()
        val deadline = SystemClock.elapsedRealtime() + 30_000
        fun active() = resumed.get() && !destroyed.get() && revision.get() == run && SystemClock.elapsedRealtime() < deadline
        start.isEnabled = false
        marker("NATIVE_PREPARING")
        worker.execute {
            var result = ExchangeOutcome.CANCELLED
            try {
                if (active() && CookieHandler.getDefault() == null) {
                    val http = AccessProbeHttp(canRequest = ::active)
                    transport.set(http)
                    http.use {
                        probeAbemaNativeAccess(it) { code, manifest ->
                            val initialization = if (code == 200) parseAbemaRequestInitialization(manifest) else null
                            if (initialization != null && active()) {
                                result = exchangeClearKeyOnce(commonPssh(initialization.kids), initialization.kids,
                                    ::active, ::AndroidOpaqueClearKeyCdm) { challenge -> broker(view, challenge, ::active, deadline) }
                            } else if (active()) result = ExchangeOutcome.REQUEST_REFUSED
                            if (code == 200) abemaDashClassification(code, manifest) else AccessProbeOutcome.HTTP_REJECTED
                        }
                    }
                } else if (active()) result = ExchangeOutcome.REQUEST_REFUSED
            } catch (_: Exception) { result = if (active()) ExchangeOutcome.HELPER_FAILED else ExchangeOutcome.CANCELLED }
            finally { transport.getAndSet(null)?.close(); running.set(false) }
            val closed = result
            handler.post {
                if (resumed.get() && !destroyed.get() && revision.get() == run && browser === view) {
                    nativeResult = if (active()) closed.name else "TIME_LIMIT"
                    marker(checkNotNull(nativeResult))
                }
            }
        }
        handler.postDelayed({ if (revision.get() == run && running.get()) { cancel(); nativeResult = "TIME_LIMIT"; marker("TIME_LIMIT") } }, 30_000)
    }

    private fun recordSourceMetadata(view: WebView, run: Long) {
        view.evaluateJavascript("JSON.stringify(window['$name']?.sourceMetadata() || null)") { raw ->
            if (!resumed.get() || browser !== view || revision.get() != run || !allows(view.url)) return@evaluateJavascript
            val parsed = try { JSONObject(quoted(raw, 256) ?: return@evaluateJavascript) } catch (_: Exception) { return@evaluateJavascript }
            if (parsed.length() != 2) return@evaluateJavascript
            val shape = parsed.optString("shape").takeIf { it in setOf("UNSEEN", "TYPE_OR_SIZE", "AUTHORITY", "USER_INFO", "QUERY", "FRAGMENT", "NOT_MPD", "PATH", "MPD", "UNRECOGNIZED") } ?: return@evaluateJavascript
            val cdn = parsed.optString("cdn").takeIf { it in setOf("NONE", "VOD_AKAMAI", "DS_VOD_AKAMAI", "LINEAR_AKAMAI", "OTHER") } ?: return@evaluateJavascript
            val closed = "shape=$shape cdn=$cdn"
            if (closed != lastSourceMetadata) { lastSourceMetadata = closed; Log.d("TachiaiAbemaSource", closed) }
        }
    }

    private fun beginNativePlayback(view: WebView) {
        val run = revision.incrementAndGet()
        val durationMs = if (nativePair) 300_000L else 120_000L
        val deadline = SystemClock.elapsedRealtime() + durationMs
        fun active() = resumed.get() && !destroyed.get() && revision.get() == run &&
            SystemClock.elapsedRealtime() < deadline
        start.isEnabled = false
        nativeResult = "NATIVE_PREPARING"
        marker(checkNotNull(nativeResult))
        val helperArm = if (nativeAlignedHelper) CompletableFuture<Boolean>() else null
        if (helperArm != null) {
            val armDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 3_000)
            fun finishArm(accepted: Boolean, verdict: String) {
                if (!helperArm.complete(accepted)) return
                if (active() && browser === view && allows(view.url)) {
                    Log.d("TachiaiAbemaPlayback", "initialHelperArm=$verdict")
                    if (!accepted) {
                        cancel(); nativeResult = "INITIAL_HELPER_ARM_FAILED"; marker(checkNotNull(nativeResult))
                    }
                }
            }
            val remainingMs = maxOf(0L, deadline - SystemClock.elapsedRealtime())
            view.evaluateJavascript("window['$name']?.armInitialExchange($remainingMs) === true") { raw ->
                val accepted = active() && browser === view && allows(view.url) &&
                    SystemClock.elapsedRealtime() < armDeadline && raw == "true"
                finishArm(accepted, if (accepted) "ACCEPTED" else "REFUSED")
            }
            handler.postDelayed({ finishArm(false, "TIMEOUT") }, maxOf(0L, armDeadline - SystemClock.elapsedRealtime()))
        }
        lateinit var prepare: Runnable
        prepare = Runnable {
            if (delayedNativePreparation === prepare) delayedNativePreparation = null
            if (!active() || browser !== view || !allows(view.url)) {
                if (revision.get() == run) running.set(false)
                return@Runnable
            }
        worker.execute {
            try {
                check(active() && CookieHandler.getDefault() == null)
                if (helperArm != null) check(helperArm.get(3, java.util.concurrent.TimeUnit.SECONDS))
                val source: AbemaNativeDashSource = if (nativeReplay) {
                    val uri = selectedReplaySource(view, ::active, minOf(deadline, SystemClock.elapsedRealtime() + 10_000))
                        ?: throw IllegalStateException()
                    val fetchBudget = NativePlaybackBudget(deadline - SystemClock.elapsedRealtime(), ::active)
                    val requests = BoundedMediaRequests(fetchBudget, { it == uri },
                        { event, code -> Log.d("TachiaiAbemaPlayback", "source=${event.name} code=$code") })
                    sourceRequests.set(requests)
                    try {
                        val resolved = resolveAbemaReplayNativeSource(uri, requests)
                        check(active() && selectedReplaySource(view, ::active,
                            minOf(deadline, SystemClock.elapsedRealtime() + 10_000)) == uri)
                        resolved
                    }
                    finally { sourceRequests.compareAndSet(requests, null); requests.close() }
                } else {
                    val http = AccessProbeHttp(canRequest = ::active)
                    transport.set(http)
                    http.use { resolveAbemaNewsNativeSource(it) }
                }
                val twitchPreparation = if (nativePair) NativePairTwitchPreparation(this, ::active).also {
                    pairAuthorization.set(it)
                    if (!active()) { it.close(); throw IllegalStateException() }
                } else null
                val twitchSource = twitchPreparation?.resolve(nativeTwitchReplay) { endpoint, code ->
                    Log.d("TachiaiMixedPair", "endpoint=$endpoint http=$code")
                }
                if (nativePair && nativeReplay) check(active() && selectedReplaySource(view, ::active,
                    minOf(deadline, SystemClock.elapsedRealtime() + 10_000)) == source.uri)
                handler.post {
                    if (!active() || browser !== view || !allows(view.url)) return@post
                    val budget = NativePlaybackBudget(deadline - SystemClock.elapsedRealtime(), canContinue = {
                        active() && (twitchPreparation == null || twitchPreparation.canContinue())
                    }, maximumDurationMs = durationMs)
                    val mediaPolicy = DeclaredDashMediaPolicy(source.uri) {
                        if (nativeReplay) {
                            val verdict = abemaReplayCdnUriPolicy(it)
                            if (verdict != AbemaReplayUriPolicy.ALLOWED) Log.d("TachiaiAbemaPlayback", "replayDeclaredPathPolicy=${verdict.name}")
                            return@DeclaredDashMediaPolicy verdict == AbemaReplayUriPolicy.ALLOWED
                        }
                        val verdict = abemaNewsCdnUriPolicy(it)
                        if (verdict != AbemaNewsMediaUriPolicy.ALLOWED)
                            Log.d("TachiaiAbemaPlayback", "declaredPathPolicy=${verdict.name}")
                        verdict == AbemaNewsMediaUriPolicy.ALLOWED
                    }
                    val lastInitializationShape = AtomicReference<String>()
                    val transitionPolicy = if (nativeTransitions) AbemaManifestTransitionPolicy(source.kids) else null
                    val lastTransitionVerdict = AtomicReference<String>()
                    val manifestParser = BoundedDashManifestParser(source.uri, mediaPolicy,
                        preflight = { body ->
                            if (transitionPolicy != null) {
                                val accepted = transitionPolicy.accepts(body)
                                val verdict = transitionPolicy.verdict.name
                                if (lastTransitionVerdict.getAndSet(verdict) != verdict)
                                    Log.d("TachiaiAbemaPlayback", "transition=$verdict")
                                return@BoundedDashManifestParser accepted
                            }
                            val initialization = parseAbemaRequestInitialization(body) { diagnostic ->
                                val shape = diagnostic.closedSummary()
                                if (lastInitializationShape.getAndSet(shape) != shape)
                                    Log.d("TachiaiAbemaPlayback", "initialization $shape")
                            }
                            val matches = initialization?.kids?.toSet() == source.kids.toSet()
                            if (!matches) Log.d("TachiaiAbemaPlayback", "manifestProtection=${
                                if (initialization == null) "UNRECOGNIZED"
                                else if (source.kids.containsAll(initialization.kids)) "KID_SET_REMOVED_ONLY"
                                else "KID_SET_ADDED"}")
                            matches
                        },
                        canRun = { active() && budget.active },
                        onEvent = { event, count -> Log.d("TachiaiAbemaPlayback", "manifest=${event.name} count=$count") },
                        onRefusal = { stage -> Log.d("TachiaiAbemaPlayback", "manifestRefusalStage=${stage.name}") },
                        validateModel = { parsed ->
                            transitionPolicy?.requiresDeclarationFreeModel != true ||
                                (0 until parsed.periodCount).all { period ->
                                    parsed.getPeriod(period).adaptationSets.all { adaptation ->
                                        adaptation.representations.all { it.format.drmInitData == null }
                                    }
                                }
                        })
                    try {
                        val created = BoundedNativeDashPlayer(this, budget, source.kids, allowedUri = { uri ->
                            val allowed = mediaPolicy.allows(uri)
                            if (!allowed) {
                                if (nativeReplay) {
                                    Log.d("TachiaiAbemaPlayback", "replaySourcePolicy=UNDECLARED")
                                    return@BoundedNativeDashPlayer false
                                }
                                val reason = abemaNewsCdnUriPolicy(uri).takeIf { it != AbemaNewsMediaUriPolicy.ALLOWED }
                                    ?: AbemaNewsMediaUriPolicy.UNDECLARED
                                Log.d("TachiaiAbemaPlayback", "sourcePolicy=${reason.name}")
                            }
                            allowed
                        }, manifestUri = source.uri, manifestParser = manifestParser,
                            exchange = { challenge ->
                                broker(view, challenge, { active() && budget.active },
                                    minOf(deadline, SystemClock.elapsedRealtime() + 30_000))
                            }, onEvent = { event, code ->
                                handler.post {
                                    if (resumed.get() && revision.get() == run && browser === view) {
                                        nativeStatus?.text = getString(R.string.abema_native_playback_status, event.name, code)
                                        Log.d("TachiaiAbemaPlayback", "event=${event.name} code=$code")
                                        if (event == NativeDashEvent.STOPPED) {
                                            if (nativePair) {
                                                cancel(); nativeResult = "PAIR_STOPPED"; marker(checkNotNull(nativeResult))
                                                return@post
                                            }
                                            nativeSurface?.player = null
                                            nativeSurface?.visibility = View.GONE
                                            nativeHost = null
                                            handler.removeCallbacks(timingTicker)
                                        }
                                    }
                                }
                            }, onMediaEvent = { event, code ->
                                Log.d("TachiaiAbemaPlayback", "media=${event.name} code=$code")
                            }, beforeRelease = { mediaPolicy.close(); pending.getAndSet(null)?.complete(null) },
                            keepDrmSessionForClearTransitions = nativeTransitions,
                            initialDrmFormat = if (nativePrewarm) Format.Builder()
                                .setSampleMimeType(MimeTypes.VIDEO_H264)
                                .setDrmInitData(DrmInitData(C.CENC_TYPE_cenc, DrmInitData.SchemeData(
                                    C.COMMON_PSSH_UUID, MimeTypes.VIDEO_MP4, commonPssh(source.kids))))
                                .build() else null)
                        nativeHost = created
                        nativeSurface?.visibility = View.VISIBLE
                        nativeSurface?.player = created.player
                        if (nativePair) {
                            check(twitchSource != null && twitchPreparation.canContinue())
                            val focus = NativePlaybackAudioGroup(this) {
                                pairPlayGate.invalidate()
                                mixedPair?.focusLost()
                                if (nativeHost?.timingSnapshot()?.playWhenReady == true || twitchHost?.timingSnapshot()?.playWhenReady == true) cancel()
                            }
                            pairFocus = focus
                            val twitch = BoundedNativePlayer(this, budget, twitchPreparation.acceptanceDeadlineMs,
                                allowedUri = { allowedTwitchMediaUri(it, observedReplayCdn = nativeTwitchReplay) },
                                handleAudioFocus = false, canRequest = { twitchPreparation.checkStored() },
                                onEvent = { event, code -> handler.post {
                                    if (active() && browser === view) {
                                        Log.d("TachiaiMixedPair", "side=B event=${event.name} code=$code")
                                        if (event == NativeMediaEvent.STOPPED) {
                                            cancel(); nativeResult = "PAIR_STOPPED"; marker(checkNotNull(nativeResult))
                                        }
                                    }
                                } })
                            twitchHost = twitch
                            mixedPair = NativeMixedPair(created, twitch, focus::acquire, releaseFocus = { budget.stop(); focus.close() },
                                requireCurrentLiveWindowOnPlay = nativeRelativeControls)
                            pairSurfaces?.visibility = View.VISIBLE
                            twitchSurface?.player = twitch.player
                            created.start(source.uri, playWhenReady = false)
                            twitch.start(twitchSource.uri, playWhenReady = false)
                            if (nativeTwitchReplay) twitch.player.seekTo(70 * 60 * 1_000L)
                            pairViewer?.open()
                            handler.post(pairTicker)
                        } else {
                            created.start(source.uri)
                            handler.postDelayed(timingTicker, 5_000)
                        }
                        nativeResult = "NATIVE_PLAYBACK"
                        marker(checkNotNull(nativeResult))
                    } catch (_: Exception) {
                        mediaPolicy.close(); budget.stop()
                        if (nativePair) cancel()
                        else { nativeHost?.close(); nativeHost = null; nativeSurface?.player = null }
                        nativeResult = "NATIVE_START_FAILED"; marker(checkNotNull(nativeResult))
                    }
                }
            } catch (_: Exception) {
                handler.post { if (active() && browser === view) {
                    if (nativePair) cancel()
                    nativeResult = "NATIVE_SOURCE_FAILED"; marker(checkNotNull(nativeResult))
                } }
            } finally { transport.getAndSet(null)?.close(); running.set(false) }
        }
        }
        if (nativePreparationDelayMs == 0L) prepare.run()
        else {
            delayedNativePreparation = prepare
            Log.d("TachiaiAbemaPlayback", "preparation=FIXED_LATE_START")
            handler.postDelayed(prepare, nativePreparationDelayMs)
        }
        handler.postDelayed({ if (revision.get() == run && resumed.get()) {
            cancel(); nativeResult = "TIME_LIMIT"; marker("TIME_LIMIT")
        } }, durationMs)
    }

    private fun selectedReplaySource(view: WebView, active: () -> Boolean, deadline: Long): URI? {
        val completion = CompletableFuture<URI?>()
        check(sourcePending.compareAndSet(null, completion))
        handler.post {
            if (!active() || SystemClock.elapsedRealtime() >= deadline || browser !== view || !allows(view.url)) completion.complete(null)
            else view.evaluateJavascript("window['$name']?.selectedSource() || null") { raw ->
                if (!active() || SystemClock.elapsedRealtime() >= deadline || browser !== view || !allows(view.url)) completion.complete(null)
                else {
                    val uri = try { URI(quoted(raw, 12_000) ?: "") } catch (_: Exception) { null }
                    val verdict = uri?.let(::abemaReplaySelectedSourcePolicy)
                    Log.d("TachiaiAbemaSource", "nativeSelection=${verdict?.name ?: "UNAVAILABLE"}")
                    completion.complete(uri?.takeIf { verdict == AbemaReplayUriPolicy.ALLOWED })
                }
            }
        }
        return try { completion.get(maxOf(1, deadline - SystemClock.elapsedRealtime()), java.util.concurrent.TimeUnit.MILLISECONDS) }
        catch (_: Exception) { null }
        finally { sourcePending.compareAndSet(completion, null) }
    }

    private fun broker(view: WebView, challenge: ByteArray, active: () -> Boolean, deadline: Long): ByteArray? {
        val completion = CompletableFuture<ByteArray?>()
        check(pending.compareAndSet(null, completion))
        val encoded = Base64.encodeToString(challenge, Base64.NO_WRAP)
        handler.post {
            if (!active() || browser !== view || !allows(view.url)) completion.complete(null)
            else view.evaluateJavascript("window['$name']?.begin('$encoded') || 'REFUSED'") { raw ->
                if (!active() || quoted(raw, 100) != "WORKING") completion.complete(null)
                else pollResponse(view, completion, active)
            }
        }
        return try { awaitOpaqueResponse(completion, deadline - SystemClock.elapsedRealtime()) }
        finally { pending.compareAndSet(completion, null) }
    }

    private fun pollResponse(view: WebView, completion: CompletableFuture<ByteArray?>, active: () -> Boolean) {
        if (completion.isDone) return
        if (!active() || browser !== view || !allows(view.url)) { completion.complete(null); return }
        view.evaluateJavascript("window['$name']?.status() || 'UNAVAILABLE'") { raw ->
            if (!active() || browser !== view || !allows(view.url)) completion.complete(null)
            else when (quoted(raw, 100)) {
                "WORKING" -> handler.postDelayed({ pollResponse(view, completion, active) }, 100)
                "RESPONSE" -> view.evaluateJavascript("window['$name']?.take()") { opaque ->
                    val encoded = quoted(opaque, 90_000)
                    val response = try {
                        if (encoded == null || !encoded.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) null
                        else Base64.decode(encoded, Base64.NO_WRAP).let { bytes ->
                            if (bytes.size in 1..65536 && Base64.encodeToString(bytes, Base64.NO_WRAP) == encoded) bytes
                            else { bytes.fill(0); null }
                        }
                    } catch (_: Exception) { null }
                    if (!active() || browser !== view || !allows(view.url) || !completion.complete(response)) response?.fill(0)
                    if (!active()) completion.complete(null)
                }
                else -> completion.complete(null)
            }
        }
    }

    private fun cancel(stopHelper: Boolean = true) {
        revision.incrementAndGet()
        delayedNativePreparation?.let { handler.removeCallbacks(it); running.set(false) }
        delayedNativePreparation = null
        pairPlayGate.invalidate()
        pairViewer?.endSession()
        transport.getAndSet(null)?.close()
        sourceRequests.getAndSet(null)?.close()
        sourcePending.getAndSet(null)?.complete(null)
        pending.getAndSet(null)?.complete(null)
        pairAuthorization.getAndSet(null)?.close()
        val stoppedPair = mixedPair
        mixedPair = null
        stoppedPair?.close()
        twitchHost?.close(); twitchHost = null
        pairFocus?.close(); pairFocus = null
        twitchSurface?.player = null
        pairSurfaces?.visibility = View.GONE
        handler.removeCallbacks(pairTicker)
        pairPanel?.refresh()
        nativeHost?.close(); nativeHost = null
        nativeSurface?.player = null
        nativeSurface?.visibility = View.GONE
        nativeStatus?.text = getString(R.string.abema_native_playback_status, NativeDashEvent.STOPPED.name, 0)
        handler.removeCallbacks(timingTicker)
        val view = browser
        if (stopHelper && view != null && allows(view.url)) view.evaluateJavascript("window['$name']?.stop()", null)
        if (::start.isInitialized) start.isEnabled = false
    }

    private fun disposeBrowser() {
        cancel()
        registration?.remove(); registration = null
        browser?.let { view ->
            view.stopLoading(); view.onPause(); host.removeView(view); view.webChromeClient = null; view.destroy()
        }
        browser = null
        if (profileConfigured) WebView.setWebContentsDebuggingEnabled(false)
    }

    override fun onResume() { super.onResume(); Log.d("TachiaiAbemaBroker", "lifecycle=RESUME"); resumed.set(true); if (profileConfigured && browser == null) createBrowser() }
    override fun onPause() { Log.d("TachiaiAbemaBroker", "lifecycle=PAUSE finishing=$isFinishing"); resumed.set(false); disposeBrowser(); super.onPause() }
    override fun onDestroy() { Log.d("TachiaiAbemaBroker", "lifecycle=DESTROY"); destroyed.set(true); disposeBrowser(); worker.shutdownNow(); super.onDestroy() }
}
