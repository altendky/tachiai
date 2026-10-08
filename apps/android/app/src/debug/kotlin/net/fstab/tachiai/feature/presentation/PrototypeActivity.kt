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
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativePlaybackAudioGroup
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.PrototypePlaybackController
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.platform.media.PrototypeFeedSession
import net.fstab.tachiai.presentation.PrototypePlayBarrier
import net.fstab.tachiai.presentation.PrototypeFeedFailure
import net.fstab.tachiai.presentation.PrototypeFailureReason
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
    private var sessions = listOf<PrototypeFeedSession?>(null, null)
    private val failures = arrayOfNulls<PrototypeFeedFailure>(2)
    private val prepared = BooleanArray(2)
    private val initialCatchUp = BooleanArray(2)
    private val catchUpDeadline = LongArray(2)
    private var surfaces = emptyList<PlayerView>()
    private var budget: NativePlaybackBudget? = null
    private var playback: PrototypePlaybackController? = null
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
    private var desiredPlaying = false
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
            if (budget != null) stopToPicker("Playback stopped.") else finish()
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
        failures.fill(null); prepared.fill(false); initialCatchUp.fill(false); catchUpDeadline.fill(0)
        desiredPlaying = true; lastAuthorizationPoll = 0; lastSample = 0
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
        createViewer()
        selected.sources.forEachIndexed { index, source ->
            try {
                val replay = source.kind == PrototypePlaybackKind.REPLAY
                val events: (PrototypeFeedEvent) -> Unit = { event ->
                    if (disposing && event == PrototypeFeedEvent.FAILED) cleanupFailed = true
                    else if (active() && failures[index] == null) {
                        Log.d("TachiaiPrototype", "slot=${if (index == 0) "A" else "B"} source=${source.name} event=${event.name}")
                        if (event == PrototypeFeedEvent.NETWORK_APPROVAL_REQUIRED)
                            failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.MEDIA_APPROVAL_REQUIRED))
                        else if (event == PrototypeFeedEvent.FAILED || event == PrototypeFeedEvent.STOPPED)
                            failFeed(index, sessions[index]?.failure ?: PrototypeFeedFailure(
                                if (event == PrototypeFeedEvent.STOPPED) PrototypeFailureReason.STOPPED else PrototypeFailureReason.PREPARATION_FAILED))
                        else updateProgress()
                    }
                }
                val feedActive = { active() && failures[index] == null }
                val session = when (source.service) {
                    PrototypeService.ABEMA -> if (useCachedAbema) CachedPrototypeAbemaSession(this, replay, feedActive, events)
                        else PrototypeAbemaSession(this, replay, feedActive, events)
                    PrototypeService.TWITCH -> PrototypeTwitchSession(this, replay, checkNotNull(source.resourceId), feedActive, events)
                }
                sessions = sessions.toMutableList().also { it[index] = session }
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
            } catch (_: Exception) { failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_FAILED)) }
        }
        sessions.forEachIndexed { index, host ->
            try { host?.prepare(sharedBudget.child()) }
            catch (_: Exception) { failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_FAILED)) }
        }
        updateProgress()
        handler.post(ticker)
    }

    private fun updateProgress() {
        progress?.text = selection.sources.mapIndexed { index, source ->
            val state = failures[index]?.message ?: if (prepared[index]) "Ready" else "Preparing"
            "${source.slotLabel(if (index == 0) "A" else "B")}: $state"
        }.joinToString("\n")
    }

    private val ticker = object : Runnable {
        override fun run() {
            val currentBudget = budget ?: return
            if (!currentBudget.active) {
                failures.indices.forEach { failFeed(it, PrototypeFeedFailure(PrototypeFailureReason.PLAYBACK_LIMIT)) }
                focus?.release()
                viewer?.refresh()
                return
            }
            sessions.forEachIndexed { index, host ->
                try {
                    if (host?.member != null && !host.canContinue()) failFeed(index, authorizationFailure(index, host))
                    else if (host != null && failures[index] == null && !prepared[index]) prepareViewerSlot(index, host)
                } catch (_: Exception) { failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED)) }
            }
            updateProgress()
            playback?.poll()
            val available = NativeMixedSide.entries.mapNotNull { playback?.member(it) }
            if (desiredPlaying && playBarrier == null && playback?.busy != true && available.isNotEmpty() &&
                available.all { it.timingSnapshot()?.let { value -> value.state == Player.STATE_READY && !value.playingAd } == true } &&
                available.any { it.timingSnapshot()?.playWhenReady != true }) {
                playReadyFeeds()
            }
            viewer?.refresh()
            val now = SystemClock.elapsedRealtime()
            if (now - lastSample >= 5_000) {
                lastSample = now
                NativeMixedSide.entries.forEach { side ->
                    playback?.member(side)?.timingSnapshot()?.let { value ->
                        Log.d("TachiaiPrototype", "slot=${side.name} state=${value.state} playing=${value.playing} positionMs=${value.positionMs} contentTimeMs=${value.contentTimeMs}")
                    }
                }
            }
            if (now - lastAuthorizationPoll >= 5_000 && sessions.any { it?.member != null } &&
                authorizationPolling.compareAndSet(false, true)) {
                lastAuthorizationPoll = now
                val run = epoch.get()
                val hosts = sessions
                worker.execute {
                    try {
                        hosts.forEachIndexed { index, host ->
                            if (host?.member != null && !runCatching { host.checkAuthorization() }.getOrDefault(false)) handler.post {
                                if (epoch.get() == run && sessions[index] === host)
                                    failFeed(index, authorizationFailure(index, host))
                            }
                        }
                    } finally { authorizationPolling.set(false) }
                }
            }
            if (budget === currentBudget) handler.postDelayed(this, 200)
        }
    }

    private fun createViewer() {
        val group = NativePlaybackAudioGroup(this) {
            desiredPlaying = false; invalidatePlay(); playback?.focusLost()
            sessions.forEachIndexed { index, host ->
                if (host?.member?.timingSnapshot()?.playWhenReady == true)
                    failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED))
            }
            viewer?.refresh()
        }
        focus = group
        playback = PrototypePlaybackController(group::acquire)
        fun surface() = (layoutInflater.inflate(R.layout.native_viewer_player, root, false) as PlayerView).apply {
            useController = false; keepScreenOn = true
        }
        surfaces = listOf(surface(), surface())
        val pane = NativePairViewer(this, surfaces[0], surfaces[1], selection.a.slotLabel("A"), selection.b.slotLabel("B"),
            { playback?.pair }, ::playPair, ::pausePair,
            onRelative = { delta -> desiredPlaying = false; invalidatePlay(); playback?.shiftRelative(delta) },
            onCatchUp = { side -> desiredPlaying = false; invalidatePlay(); playback?.catchUp(side) },
            onDiagnostics = { pausePair(); viewer?.visibility = View.GONE; styleSystemBars(false) },
            onStop = { stopToPicker("Playback stopped.") },
            onLandscape = { landscape -> requestedOrientation = if (landscape)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT },
            setupLabel = "Preparation / provider pages", statusOverride = { playMessage ?: playback?.status },
            members = { NativeMixedSide.entries.map { playback?.member(it) } },
            volumeRequest = { side, volume -> playback?.setVolume(side, volume) == true },
            feedMessage = { side ->
                val index = side.ordinal
                failures[index]?.let { "Could not load this feed\n\n${it.message}\n\nUse Sources to choose another feed." }
                    ?: if (!prepared[index]) "Preparing this feed…" else null
            }, onSources = { stopToPicker("Playback stopped.") })
        viewer = pane
        root?.addView(pane, FrameLayout.LayoutParams(-1, -1))
        returnButton?.isEnabled = true
        styleSystemBars(true)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        pane.open()
    }

    private fun prepareViewerSlot(index: Int, host: PrototypeFeedSession) {
        if (initialCatchUp[index] && SystemClock.elapsedRealtime() >= catchUpDeadline[index]) {
            failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.LIVE_POSITION_FAILED)); return
        }
        val member = host.member ?: return
        val snapshot = member.timingSnapshot() ?: return
        if (snapshot.state != Player.STATE_READY || snapshot.playWhenReady || snapshot.playingAd) return
        if (!initialCatchUp[index] && (snapshot.live || snapshot.dynamic)) {
            initialCatchUp[index] = true
            catchUpDeadline[index] = SystemClock.elapsedRealtime() + 8_000
            // Initial live position is chosen once, before establishing a timing
            // anchor. No reload, retry, future seek or license exchange is added.
            if (member.seekLiveDefault().outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE))
                failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.LIVE_POSITION_FAILED))
            return
        }
        if ((snapshot.live || snapshot.dynamic) && (snapshot.positionMs == null || snapshot.durationMs == null ||
                snapshot.durationMs <= 0 || snapshot.positionMs !in 0..snapshot.durationMs)) return
        prepared[index] = true
        invalidatePlay()
        playback?.setMember(NativeMixedSide.entries[index], member)
        surfaces[index].player = host.player
    }

    private fun invalidatePlay() { playBarrier?.cancel(); playBarrier = null; playMessage = null }
    private fun pausePair() { desiredPlaying = false; invalidatePlay(); playback?.pause(); viewer?.refresh() }

    private fun playPair() {
        desiredPlaying = true
        playReadyFeeds()
    }

    private fun playReadyFeeds() {
        invalidatePlay()
        val current = playback ?: return
        // The comparison's original ABEMA page may autoplay while preparing.
        // Its adapter can only confirm isolation once its native host is ready.
        // The default cached flow has no original page and remains independent.
        if (!useCachedAbema && sessions.indices.any { sessions[it]?.providerView != null && !prepared[it] }) {
            playMessage = "Waiting to safely pause the original provider player."
            return
        }
        val hosts = sessions.mapIndexedNotNull { index, host ->
            if (host != null && prepared[index] && failures[index] == null) index to host else null
        }
        if (hosts.isEmpty()) return
        val run = epoch.get()
        val waiting = hosts.indices.toMutableSet()
        lateinit var barrier: PrototypePlayBarrier
        barrier = PrototypePlayBarrier(hosts.size) { accepted ->
            if (playBarrier !== barrier || !resumed.get() || epoch.get() != run || playback !== current) return@PrototypePlayBarrier
            playBarrier = null
            if (accepted) {
                if (!current.play()) desiredPlaying = false
            } else {
                current.pause()
                desiredPlaying = false
            }
            viewer?.refresh()
        }
        playBarrier = barrier
        hosts.forEachIndexed { slot, (index, host) ->
            try {
                host.pauseOriginal { accepted -> handler.post {
                    if (playBarrier !== barrier || epoch.get() != run || sessions[index] !== host) return@post
                    if (accepted) { waiting.remove(slot); barrier.result(slot, true) }
                    else failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.ORIGINAL_PLAYER_NOT_PAUSED))
                } }
            } catch (_: Exception) { failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.ORIGINAL_PLAYER_NOT_PAUSED)) }
        }
        handler.postDelayed({
            if (playBarrier === barrier) waiting.map { hosts[it].first }.forEach {
                failFeed(it, PrototypeFeedFailure(PrototypeFailureReason.ORIGINAL_PLAYER_NOT_PAUSED))
            }
        }, 3_000)
    }

    private fun authorizationFailure(index: Int, host: PrototypeFeedSession) = host.failure ?: PrototypeFeedFailure(
        if (selection.sources[index].service == PrototypeService.TWITCH) PrototypeFailureReason.LOGIN_EXPIRED
        else PrototypeFailureReason.PREPARATION_FAILED)

    private fun failFeed(index: Int, failure: PrototypeFeedFailure) {
        if (failures[index] != null) return
        failures[index] = failure
        Log.d("TachiaiPrototype", "slot=${NativeMixedSide.entries[index].name} failure=${failure.reason.name} http=${failure.httpStatus ?: 0}")
        invalidatePlay()
        playback?.setMember(NativeMixedSide.entries[index], null)
        prepared[index] = false
        surfaces.getOrNull(index)?.player = null
        val host = sessions[index]
        sessions = sessions.toMutableList().also { it[index] = null }
        val closed = runCatching { host?.close() }.isSuccess && host?.cleanupFailed != true
        if (!closed) {
            cleanupFailed = true
            budget?.stop()
            failures.indices.filter { failures[it] == null }.forEach {
                failFeed(it, PrototypeFeedFailure(PrototypeFailureReason.CLEANUP_FAILED))
            }
        }
        if (NativeMixedSide.entries.all { playback?.member(it) == null }) focus?.release()
        updateProgress()
        viewer?.refresh()
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
        playback?.close(); playback = null
        surfaces.forEach { it.player = null }; surfaces = emptyList()
        sessions.forEach { if (runCatching { it?.close() }.isFailure || it?.cleanupFailed == true) cleanupFailed = true }
        sessions = listOf(null, null)
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
        if (budget != null) { dispose(); resumeMessage = "Playback stopped while the app was in the background." }
        super.onPause()
    }
    override fun onDestroy() { resumed.set(false); dispose(); worker.shutdownNow(); super.onDestroy() }
}
