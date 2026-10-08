package net.fstab.tachiai.feature.presentation

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.R
import net.fstab.tachiai.platform.media.NativeMixedPair
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativePlaybackAudioGroup
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.platform.media.PrototypeFeedSession
import net.fstab.tachiai.presentation.PrototypePlayBarrier
import net.fstab.tachiai.presentation.PrototypePlaybackKind
import net.fstab.tachiai.presentation.PrototypeSelection
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.provider.abema.PrototypeAbemaSession
import net.fstab.tachiai.provider.abema.CachedPrototypeAbemaSession
import net.fstab.tachiai.provider.twitch.PrototypeTwitchSession

// Product-flow prototype, separate from historical experiment screens. The
// unsupported playback adapters deliberately remain confined to the debug APK.
@UnstableApi
@SuppressLint("SetTextI18n") // Preliminary English UX; localization is deferred.
open class PrototypeActivity : ComponentActivity() {
    companion object { private var profileConfigured = false }
    protected open val useCachedAbema: Boolean = false
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val resumed = AtomicBoolean()
    private val epoch = AtomicLong()
    private var selection = PrototypeSelection()
    private var sessions = listOf<PrototypeFeedSession>()
    private var budget: NativePlaybackBudget? = null
    private var pair: NativeMixedPair? = null
    private var focus: NativePlaybackAudioGroup? = null
    private var viewer: NativePairViewer? = null
    private var root: FrameLayout? = null
    private var progress: TextView? = null
    private var returnButton: Button? = null
    private var playBarrier: PrototypePlayBarrier? = null
    private var playMessage: String? = null
    private var cleanupFailed = false
    private var disposing = false
    private var resumeMessage: String? = null
    private var startedViewer = false
    private var initialCatchUp = false
    private var catchUpDeadline = 0L
    private var lastAuthorizationPoll = 0L
    private var lastSample = 0L
    private val authorizationPolling = AtomicBoolean()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG || android.os.Build.VERSION.SDK_INT < 28) { finish(); return }
        if (!profileConfigured) {
            WebView.setDataDirectorySuffix(if (useCachedAbema) "prototype-cached-player" else "prototype-player")
            profileConfigured = true
        }
        WebView.setWebContentsDebuggingEnabled(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this) {
            if (sessions.isNotEmpty()) stopToPicker("Playback stopped.") else finish()
        }
        showPicker(null)
    }

    private fun showPicker(message: String?) {
        root = null; progress = null; returnButton = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        styleSystemBars(false)
        setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(selection, message, ::watch) } }
    }

    @Suppress("DEPRECATION") // Pre-enforced-edge-to-edge Android still uses explicit bar colours.
    private fun styleSystemBars(video: Boolean) {
        val light = !video && resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        val color = if (video) android.graphics.Color.BLACK else getColor(R.color.prototype_background)
        window.statusBarColor = color
        window.navigationBarColor = color
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun watch(selected: PrototypeSelection) {
        dispose()
        if (cleanupFailed) { showPicker("Previous playback cleanup reported a failure. Close and reopen Tachiai before retrying."); return }
        selection = selected
        val run = epoch.incrementAndGet()
        fun active() = resumed.get() && epoch.get() == run && !isFinishing
        val sharedBudget = NativePlaybackBudget(300_000, ::active, maximumDurationMs = 300_000)
        budget = sharedBudget
        startedViewer = false; initialCatchUp = false; lastAuthorizationPoll = 0; lastSample = 0
        styleSystemBars(false)
        val frame = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.prototype_background)) }
        val setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(setup) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val margin = (16 * resources.displayMetrics.density).toInt()
            view.setPadding(bars.left + margin, bars.top + margin, bars.right + margin, bars.bottom + margin); insets
        }
        progress = TextView(this).apply { textSize = 18f; setTextColor(getColor(R.color.prototype_text)) }.also { setup.addView(it) }
        setup.addView(TextView(this).apply {
            text = if (useCachedAbema) "Preparing your feeds… ABEMA uses cached provider modules, without opening its player page. No account entry is needed."
                else "Preparing your feeds… ABEMA may ask you to keep using the web player. No account entry is needed for these fixed sources."
        })
        setup.addView(Button(this).apply { text = "Back to sources"; stylePrototypeControl(); setOnClickListener { stopToPicker("Playback stopped.") } })
        returnButton = Button(this).apply {
            text = "Return to viewer"; isEnabled = false
            stylePrototypeControl()
            setOnClickListener { styleSystemBars(true); viewer?.open() }
        }.also { setup.addView(it) }
        frame.addView(setup, FrameLayout.LayoutParams(-1, -1))
        root = frame
        setContentView(frame)
        try {
            selected.sources.forEachIndexed { index, source ->
                val replay = source.kind == PrototypePlaybackKind.REPLAY
                val events: (PrototypeFeedEvent) -> Unit = { event ->
                    if (disposing && event == PrototypeFeedEvent.FAILED) cleanupFailed = true
                    else if (active()) {
                        Log.d("TachiaiPrototype", "slot=${if (index == 0) "A" else "B"} source=${source.name} event=${event.name}")
                        if (event == PrototypeFeedEvent.NETWORK_APPROVAL_REQUIRED)
                            stopToPicker("A new media destination is blocked pending approval. Ask the agent to check the saved CDN review log.")
                        else if (event == PrototypeFeedEvent.FAILED || event == PrototypeFeedEvent.STOPPED)
                            stopToPicker("Could not continue ${source.title}. Check your connection and saved Twitch login, then try again.")
                        else updateProgress()
                    }
                }
                val session = when (source.service) {
                    PrototypeService.ABEMA -> if (useCachedAbema) CachedPrototypeAbemaSession(this, replay, ::active, events)
                        else PrototypeAbemaSession(this, replay, ::active, events)
                    PrototypeService.TWITCH -> PrototypeTwitchSession(this, replay, ::active, events)
                }
                sessions = sessions + session
                session.providerView?.let { page ->
                    if (useCachedAbema) {
                        // Runtime only: no provider page/video or interactive consent UI.
                        page.visibility = View.INVISIBLE
                        setup.addView(page, LinearLayout.LayoutParams(1, 1))
                    } else {
                        setup.addView(TextView(this).apply { text = source.slotLabel(if (index == 0) "A" else "B") })
                        setup.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
                    }
                }
            }
            updateProgress()
            sessions.forEach { it.prepare(sharedBudget) }
            handler.post(ticker)
        } catch (_: Exception) { stopToPicker("Unable to prepare these feeds. Please try again.") }
    }

    private fun updateProgress() {
        progress?.text = selection.sources.mapIndexed { index, source ->
            val state = if (sessions.getOrNull(index)?.member?.timingSnapshot()?.state == Player.STATE_READY) "Ready" else "Preparing"
            "${source.slotLabel(if (index == 0) "A" else "B")}: $state"
        }.joinToString("\n")
    }

    private val ticker = object : Runnable {
        override fun run() {
            val currentBudget = budget ?: return
            if (!currentBudget.active) { stopToPicker("The five-minute prototype session ended."); return }
            if (sessions.any { it.member != null && !it.canContinue() }) {
                stopToPicker("Playback authorization ended. Reconnect through the existing login screen."); return
            }
            updateProgress()
            if (pair == null && sessions.size == 2 && sessions.all { it.member != null && it.player != null }) createViewer()
            val current = pair
            if (current != null) {
                current.poll()
                if (!startedViewer) startWhenReady(current)
                viewer?.refresh()
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastSample >= 5_000 && current != null) {
                lastSample = now
                listOf(current.a, current.b).forEachIndexed { index, member ->
                    member.timingSnapshot()?.let { value ->
                        Log.d("TachiaiPrototype", "slot=${if (index == 0) "A" else "B"} state=${value.state} playing=${value.playing} positionMs=${value.positionMs} contentTimeMs=${value.contentTimeMs}")
                    }
                }
            }
            if (now - lastAuthorizationPoll >= 5_000 && sessions.all { it.member != null } &&
                authorizationPolling.compareAndSet(false, true)) {
                lastAuthorizationPoll = now
                val run = epoch.get()
                val hosts = sessions
                worker.execute {
                    try {
                        if (hosts.any { !it.checkAuthorization() }) handler.post {
                            if (epoch.get() == run) stopToPicker("Saved login changed or expired. Reconnect before trying again.")
                        }
                    } finally { authorizationPolling.set(false) }
                }
            }
            if (budget === currentBudget) handler.postDelayed(this, 200)
        }
    }

    private fun createViewer() {
        val group = NativePlaybackAudioGroup(this) {
            invalidatePlay(); pair?.focusLost()
            if (sessions.any { it.member?.timingSnapshot()?.playWhenReady == true })
                stopToPicker("Could not pause after audio focus changed.")
        }
        focus = group
        pair = NativeMixedPair(checkNotNull(sessions[0].member), checkNotNull(sessions[1].member),
            group::acquire, releaseFocus = { budget?.stop(); group.close() }, requireCurrentLiveWindowOnPlay = true)
        fun surface(index: Int) = (layoutInflater.inflate(R.layout.native_viewer_player, root, false) as PlayerView).apply {
            player = sessions[index].player; useController = false; keepScreenOn = true
        }
        val pane = NativePairViewer(this, surface(0), surface(1), selection.a.slotLabel("A"), selection.b.slotLabel("B"),
            { pair }, ::playPair, ::pausePair,
            onRelative = { delta -> invalidatePlay(); pair?.shiftRelative(delta) },
            onCatchUp = { side -> invalidatePlay(); pair?.catchUp(side) },
            onDiagnostics = { pausePair(); viewer?.visibility = View.GONE; styleSystemBars(false) },
            onStop = { stopToPicker("Playback stopped.") },
            onLandscape = { landscape -> requestedOrientation = if (landscape)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT },
            setupLabel = "Sources / provider pages", statusOverride = { playMessage })
        viewer = pane
        pane.visibility = View.GONE
        root?.addView(pane, FrameLayout.LayoutParams(-1, -1))
        returnButton?.isEnabled = true
    }

    private fun startWhenReady(current: NativeMixedPair) {
        if (initialCatchUp && SystemClock.elapsedRealtime() >= catchUpDeadline) {
            stopToPicker("The initial live position did not settle. Please try again."); return
        }
        val members = listOf(current.a, current.b)
        val snapshots = members.map { it.timingSnapshot() }
        if (snapshots.any { it?.state != Player.STATE_READY }) return
        if (!initialCatchUp) {
            initialCatchUp = true
            catchUpDeadline = SystemClock.elapsedRealtime() + 8_000
            // Initial live position is chosen once, before establishing a timing
            // anchor. No reload, retry, future seek or license exchange is added.
            members.zip(snapshots).forEach { (member, snapshot) ->
                if (snapshot?.live == true || snapshot?.dynamic == true) {
                    if (member.seekLiveDefault().outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE)) {
                        stopToPicker("Cannot start in the current live window."); return
                    }
                }
            }
            return
        }
        if (snapshots.any { value -> value == null || value.playWhenReady || value.playingAd ||
                ((value.live || value.dynamic) && (value.positionMs == null || value.durationMs == null ||
                    value.durationMs <= 0 || value.positionMs !in 0..value.durationMs)) }) return
        startedViewer = true
        styleSystemBars(true)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        viewer?.open()
        playPair()
    }

    private fun invalidatePlay() { playBarrier?.cancel(); playBarrier = null; playMessage = null }
    private fun pausePair() { invalidatePlay(); pair?.pause(); viewer?.refresh() }

    private fun playPair() {
        invalidatePlay()
        val current = pair ?: return
        val run = epoch.get()
        lateinit var barrier: PrototypePlayBarrier
        barrier = PrototypePlayBarrier(sessions.size) { accepted ->
            if (playBarrier !== barrier || !resumed.get() || epoch.get() != run || pair !== current) return@PrototypePlayBarrier
            playBarrier = null
            if (accepted) current.play() else {
                current.pause()
                playMessage = "Could not pause the original provider player. Open provider pages, then retry Play."
            }
            viewer?.refresh()
        }
        playBarrier = barrier
        sessions.forEachIndexed { index, host -> host.pauseOriginal { barrier.result(index, it) } }
        handler.postDelayed({ if (playBarrier === barrier) barrier.fail() }, 3_000)
    }

    private fun stopToPicker(message: String) {
        dispose()
        val result = message + if (cleanupFailed) " Playback cleanup reported a failure." else ""
        if (resumed.get() && !isFinishing) showPicker(result) else resumeMessage = result
    }

    private fun dispose() {
        disposing = true
        epoch.incrementAndGet(); invalidatePlay(); handler.removeCallbacks(ticker)
        budget?.stop()
        if (runCatching { viewer?.endSession() }.isFailure) cleanupFailed = true
        viewer = null
        sessions.forEach { if (runCatching { it.close() }.isFailure) cleanupFailed = true }; sessions = emptyList()
        pair?.close()
        if (pair?.cleanupFailed == true) cleanupFailed = true
        pair = null
        if (runCatching { focus?.close() }.isFailure) cleanupFailed = true
        focus = null
        budget = null
        disposing = false
        if (cleanupFailed) Log.d("TachiaiPrototype", "cleanup=FAILED")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        viewer?.requestLayout()
    }
    override fun onResume() {
        super.onResume(); resumed.set(true)
        resumeMessage?.let { resumeMessage = null; showPicker(it) }
    }
    override fun onPause() {
        resumed.set(false)
        if (sessions.isNotEmpty()) { dispose(); resumeMessage = "Playback stopped while the app was in the background." }
        super.onPause()
    }
    override fun onDestroy() { resumed.set(false); dispose(); worker.shutdownNow(); super.onDestroy() }
}
