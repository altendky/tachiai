package net.fstab.tachiai.feature.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import net.fstab.tachiai.platform.media.NativeMixedPair
import net.fstab.tachiai.platform.media.NativeEventMarks
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativePairSkipPlan
import net.fstab.tachiai.platform.media.SKIP_END_MARGIN_MS
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeSeekPlan
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.R
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativeQualityKind
import net.fstab.tachiai.platform.media.NativeQualityRequest
import net.fstab.tachiai.presentation.ViewerQualityReadback
import net.fstab.tachiai.presentation.ViewerPairOffsetReadback
import net.fstab.tachiai.presentation.PairOffsetCorrespondence
import net.fstab.tachiai.presentation.FloatingPosition
import net.fstab.tachiai.presentation.FloatingFeedSize
import net.fstab.tachiai.presentation.TwoFeedMix
import net.fstab.tachiai.presentation.ViewerRect
import net.fstab.tachiai.presentation.ViewerTimingStep
import net.fstab.tachiai.presentation.ViewerOverlayPlan
import net.fstab.tachiai.presentation.ViewerPanelSpace
import net.fstab.tachiai.presentation.ViewerStatusNotices
import net.fstab.tachiai.presentation.ViewerHiddenKey
import net.fstab.tachiai.presentation.ViewerTap
import net.fstab.tachiai.presentation.ViewerTapGesture
import net.fstab.tachiai.presentation.ViewerTransportSteps
import net.fstab.tachiai.presentation.formatPlaybackPosition
import net.fstab.tachiai.presentation.formatPlaybackTime
import net.fstab.tachiai.presentation.formatScrubTarget
import net.fstab.tachiai.presentation.formatSkipAmount
import net.fstab.tachiai.presentation.startOverNotice
import net.fstab.tachiai.presentation.twoFeedBounds
import net.fstab.tachiai.presentation.viewerHiddenKey
import net.fstab.tachiai.presentation.viewerMediaSkipMs
import net.fstab.tachiai.presentation.viewerScrubStepMs
import net.fstab.tachiai.presentation.viewerSkipRefusal
import net.fstab.tachiai.presentation.viewerOverlayPlan
import net.fstab.tachiai.presentation.viewerPaneInsets
import net.fstab.tachiai.presentation.viewerSheetCap
import net.fstab.tachiai.presentation.viewerSheetWidth
import net.fstab.tachiai.presentation.viewerSideSheet
import kotlin.math.abs

internal data class ViewerFeedRecovery(val label: String, val enabled: Boolean = true)

// Generic two-feed presentation. The caller owns lifecycle, sources and guarded playback.
@UnstableApi
// English-only debug presentation, including existing controller diagnostic
// statuses. Localization is intentionally deferred with the production viewer.
@SuppressLint("ViewConstructor", "SetTextI18n")
internal class NativePairViewer(
    context: Context,
    private val a: PlayerView,
    private val b: PlayerView,
    private val labelA: String,
    private val labelB: String,
    private val pair: () -> NativeMixedPair?,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onRelative: (Long) -> Unit,
    private val onCatchUp: (NativeMixedSide) -> Unit,
    private val onDiagnostics: () -> Unit,
    private val onStop: () -> Unit,
    private val onLandscape: (Boolean) -> Unit,
    private val setupLabel: String = "Setup / diagnostics",
    private val statusOverride: () -> String? = { null },
    private val members: () -> List<NativePairMember?> = { listOf(pair()?.a, pair()?.b) },
    private val volumeRequest: (NativeMixedSide, Float) -> Boolean = { side, value -> pair()?.setVolume(side, value) == true },
    private val feedMessage: (NativeMixedSide) -> String? = { null },
    private val feedRecovery: (NativeMixedSide) -> ViewerFeedRecovery? = { null },
    private val onRecoverFeed: (NativeMixedSide) -> Unit = {},
    private val onSources: (() -> Unit)? = null,
    private val qualityState: (NativeMixedSide, NativeQualityKind) -> ViewerQualityReadback? = { _, _ -> null },
    private val onQualityOverride: (NativeMixedSide, NativeQualityKind, NativeQualityRequest?) -> Unit = { _, _, _ -> },
    private val onSaveQuality: (NativeMixedSide, NativeQualityKind) -> Unit = { _, _ -> },
    private val onResetQuality: (NativeMixedSide, NativeQualityKind) -> Unit = { _, _ -> },
    private val catchUpPlan: (NativeMixedSide) -> NativeSeekPlan = { side ->
        pair()?.catchUpPlan(side) ?: NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
    },
    private val playbackBusy: () -> Boolean = { pair()?.busy == true },
    private val onMarkedAlignment: (Long) -> NativeSeekPlan? = { delta -> onRelative(delta); null },
    private val pairOffsetState: () -> ViewerPairOffsetReadback? = { null },
    private val onPairOffsetEnabled: (Boolean) -> Unit = {},
    private val onMarkedCorrespondence: ((Long, PairOffsetCorrespondence) -> NativeSeekPlan?)? = null,
    // Replay skip/scrub by a relative amount; a pair moves both feeds (#655).
    private val onSkip: (Long) -> NativePairSkipPlan = { delta ->
        pair()?.seekTogether(delta) ?: NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE)
    },
    // Start over moves back to the earliest feed's start, keeping a pair's
    // offset, and forgets remembered positions (#655 part 2).
    private val onStartOver: (() -> NativePairSkipPlan)? = null,
    selectedSlots: Set<NativeMixedSide> = NativeMixedSide.entries.toSet(),
) : FrameLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    // Selection is independent of readiness: a selected preparing/failed feed
    // keeps its pane and recovery controls instead of promoting its sibling.
    private val selectedSides = selectedSlots.toSet()
    private val hasPairSlots = selectedSides.size == 2
    private fun selectedMembers(): List<NativePairMember?> {
        val available = members()
        return NativeMixedSide.entries.map { side -> available.getOrNull(side.ordinal).takeIf { side in selectedSides } }
    }
    private val television = isTelevision(resources.configuration)
    private var mix = TwoFeedMix()
    private var panel: String? = null
    private var fitVideo = true
    private var activeMenu: PopupMenu? = null
    private var activeMenuAnchor: View? = null
    private var panelReturnFocus: View? = null
    private var revealPress: Pair<Int, Long>? = null
    private var focusGeneration = 0L
    private val controlKeys = java.util.WeakHashMap<View, String>()
    private val panelFocus = mutableMapOf<String, String>()
    private var overlayFocus: String? = null
    private data class FocusIntent(val key: String, val panel: String?, val generation: Long)
    private var pendingFocus: FocusIntent? = null
    private var focusMutationDepth = 0
    private var restoringFocus = false
    private var focusRestoreTicket = 0L
    private var suppressedFocusPress: Pair<Int, Long>? = null
    private var requestedPlaying = false
    private var observedPair: NativeMixedPair? = null
    private var volumeApplied = false
    private var volumeFailed = false
    private val repeats = mutableListOf<Runnable>()
    private val liveTargets = arrayOfNulls<NativePairMember>(2)
    private data class QualityField(val side: NativeMixedSide, val kind: NativeQualityKind,
        val choose: Button, val readback: TextView, val capability: TextView, val save: Button, val reset: Button)
    private val qualityFields = mutableListOf<QualityField>()
    // Replay navigation (#655): the position, skips and scrub refer to the
    // primary feed of a pair (or the single feed); a skip moves every feed.
    private data class TransportTarget(val side: NativeMixedSide, val positionMs: Long, val durationMs: Long)
    private var transport: TransportTarget? = null
    private var skipInFlight = false
    private var queuedSkipMs = 0L
    private var expectedPositionMs: Long? = null
    private var scrubbingMs: Long? = null
    private var scrubTouch = false
    private val commitScrubTask = Runnable { commitScrub() }
    private val tapGesture = ViewerTapGesture(ViewConfiguration.getDoubleTapTimeout().toLong())
    private val deferredToggle = Runnable { tapGesture.deferredDone(); toggleControls() }
    private var lastTouchX: Float? = null
    private var startFocusPending = false
    private val hideControls = Runnable {
        if (panel == null && requestedPlaying && !overlay.hasFocus() && pendingFocus == null && scrubbingMs == null) {
            overlay.visibility = GONE; requestLayout()
        }
    }
    // Layers, bottom to top: the video stage fills the viewer; the controls
    // overlay floats over it (top bar, bottom transport and an open panel);
    // brief status notices sit above both (#656).
    private val stage = FeedStage()
    private val overlay = ControlsLayer()
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroller = CappedScrollView().apply {
        addView(content)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = context.prototypeSurface(R.color.prototype_panel)
    }
    private val notices = ViewerStatusNotices()
    private val statusNotice = text("").apply {
        visibility = GONE; maxLines = 4; gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = context.prototypeSurface(R.color.prototype_panel)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val fadeNotice = Runnable {
        statusNotice.animate().alpha(0f).setDuration(ViewerStatusNotices.FADE_MS)
            .withEndAction { statusNotice.visibility = GONE }
    }
    private var plan: ViewerOverlayPlan? = null
    private val play = button("Play") {
        pendingMarkAlignment = null
        queuedSkipMs = 0L
        if (requestedPlaying || playbackBusy()) onPause() else onPlay()
        showControls(); refresh()
    }
    private val audioControl = button("Audio") { togglePanel("Audio", it) }
    private val timingControl = button("Timing") { togglePanel("Timing", it) }
    private val moreControl = button("⋯", "More") { showMore(it) }.apply { contentDescription = "More" }
    private val stopControl = onSources?.let { action ->
        button("Stop", STOP_KEY) { action() }.apply {
            contentDescription = "Stop playback"
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.navigation_back, 0, 0, 0)
            compoundDrawablePadding = dp(4)
            compoundDrawableTintList = ColorStateList.valueOf(context.getColor(R.color.prototype_text))
        }
    }
    private val details = text("")
    private val adjustmentLabel = text("").apply { textSize = 12f }
    private val eventMarks = NativeEventMarks()
    private var markAlignmentMessage: String? = null
    private var markGeneration = 0L
    private class MarkAlignmentReceipt(val owner: NativeMixedPair, val generation: Long,
        val revision: Long, val deltaMs: Long) {
        var accepted = false
        var attempt: Any? = null
    }
    private var pendingMarkAlignment: MarkAlignmentReceipt? = null
    private val markResult = text("").apply { textSize = 12f }
    private var shownPairOffset: ViewerPairOffsetReadback? = null
    private var shownPairOffsetOwner: NativeMixedPair? = null
    private val pairOffsetToggle = button("Offset: Off", "pair-offset") { requestPairOffset() }.apply {
        id = View.generateViewId()
        contentDescription = "Pair offset for $labelA and $labelB: Offset: Off"
    }
    private val pairOffsetReadback = text("").apply { textSize = 12f }
    private val pairOffsetControls = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(pairOffsetToggle)
        addView(pairOffsetReadback)
        visibility = GONE
    }
    private val markButtons = NativeMixedSide.entries.map { side ->
        button("Mark", "mark:${side.name}") { markEvent(side) }.apply {
            id = View.generateViewId()
            contentDescription = "Mark matching event in feed ${side.name} · ${if (side == NativeMixedSide.A) labelA else labelB}"
        }
    }
    private val volume = SeekBar(context).apply { max = 100; progress = mix.overall; minimumHeight = dp(48) }
    private val balance = SeekBar(context).apply { max = 100; progress = mix.balance }
    private val volumeLabel = text("")
    private val mixLabel = text("")
    private val muteA = button("", "mute:A") { changeMix(mix.copy(muteA = !mix.muteA)) }
    private val muteB = button("", "mute:B") { changeMix(mix.copy(muteB = !mix.muteB)) }
    private val advanceA = timingButtons(true)
    private val advanceB = timingButtons(false)
    private val fit = button("", "fit") { fitVideo = !fitVideo; updateLabels(); showControls() }.apply {
        contentDescription = "Fit video beside visible controls; toggle without restarting playback"
    }
    private val backToLive = NativeMixedSide.entries.map { side ->
        button("Back to live ${side.name}") { control -> requestBackToLive(side, control) }.apply {
            id = View.generateViewId()
            contentDescription = "Back to live ${side.name} · ${if (side == NativeMixedSide.A) labelA else labelB}"
        }
    }
    private val liveHint = text("").apply { textSize = 12f; setTextColor(OVERLAY_TEXT) }
    private val liveControls = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(row(*backToLive.toTypedArray()))
        addView(liveHint)
        visibility = GONE
    }
    // The top bar holds Stop and the secondary controls; the bottom area the
    // live affordances, the replay position row (#655) and the transport.
    private val topBar = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
        background = overlayScrim()
        stopControl?.let { addView(it, LinearLayout.LayoutParams(-2, -2)) }
        addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        listOf(audioControl, timingControl, moreControl).forEach { control ->
            addView(control, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
        }
    }
    // Position row (#655): the target feed's title, elapsed / total and a
    // scrub bar. Shown only while every playing feed is a replay.
    private val positionTitle = text("").apply {
        textSize = 12f; setTextColor(OVERLAY_TEXT); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private val positionTime = text("").apply { textSize = 12f; setTextColor(OVERLAY_TEXT) }
    private val scrub = SeekBar(context).apply {
        id = View.generateViewId(); max = 1; minimumHeight = dp(48)
        contentDescription = "Playback position"
        progressTintList = ColorStateList.valueOf(context.getColor(R.color.prototype_primary))
        thumbTintList = progressTintList
    }
    private val positionRow = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0)
            addView(positionTitle, LinearLayout.LayoutParams(0, -2, 1f))
            addView(positionTime, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(-1, -2))
        addView(scrub, LinearLayout.LayoutParams(-1, dp(48)))
        visibility = GONE
    }
    // ⏪ 1 min, ⏪ 10 s, Play/Pause, 10 s ⏩, 1 min ⏩.
    private val skipButtons = listOf(-ViewerTransportSteps.LONG_MS, -ViewerTransportSteps.SHORT_MS,
        ViewerTransportSteps.SHORT_MS, ViewerTransportSteps.LONG_MS).map { delta ->
        val amount = if (kotlin.math.abs(delta) >= 60_000) "1 min" else "10 s"
        button(amount, "skip:$delta") { requestSkip(delta, flash = false); showControls() }.apply {
            id = View.generateViewId(); maxLines = 1
            contentDescription = (if (delta < 0) "Back " else "Forward ") +
                if (kotlin.math.abs(delta) >= 60_000) "1 minute" else "10 seconds"
            val icon = context.getDrawable(if (delta < 0) R.drawable.transport_rewind else R.drawable.transport_fast_forward)
            icon?.setBounds(0, 0, dp(18), dp(18))
            setCompoundDrawablesRelative(if (delta < 0) icon else null, null, if (delta < 0) null else icon, null)
            compoundDrawablePadding = dp(2)
            compoundDrawableTintList = ColorStateList.valueOf(context.getColor(R.color.prototype_text))
            visibility = GONE
        }
    }
    private val transportRow = TransportRow().apply {
        gravity = Gravity.CENTER
        listOf(skipButtons[0], skipButtons[1], play, skipButtons[2], skipButtons[3]).forEachIndexed { index, control ->
            addView(control, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(4) })
        }
    }
    private val bottomBar = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
        background = overlayScrim()
        addView(liveControls, LinearLayout.LayoutParams(-1, -2))
        addView(positionRow, LinearLayout.LayoutParams(-1, -2))
        addView(transportRow, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

    init {
        timingControl.visibility = if (hasPairSlots) VISIBLE else GONE
        timingControl.isEnabled = hasPairSlots
        if (hasPairSlots) {
            backToLive[0].nextFocusRightId = backToLive[1].id
            backToLive[1].nextFocusLeftId = backToLive[0].id
        }
        for (index in advanceA.indices) {
            advanceA[index].nextFocusDownId = advanceB[index].id
            advanceB[index].nextFocusUpId = advanceA[index].id
        }
        advanceA.last().nextFocusRightId = markButtons[0].id
        advanceB.last().nextFocusRightId = markButtons[1].id
        markButtons[0].nextFocusLeftId = advanceA.last().id
        markButtons[1].nextFocusLeftId = advanceB.last().id
        markButtons[0].nextFocusDownId = markButtons[1].id
        markButtons[1].nextFocusUpId = markButtons[0].id
        markButtons[1].nextFocusDownId = pairOffsetToggle.id
        pairOffsetToggle.nextFocusUpId = markButtons[1].id
        setBackgroundColor(Color.BLACK)
        if (television) { isFocusable = true; isFocusableInTouchMode = true }
        // Consume empty-area taps here rather than passing them to the hidden
        // provider page. Child controls and pane gestures keep their handlers.
        setOnClickListener { videoTap() }
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        play.minWidth = dp(96)
        addView(stage)
        addView(overlay)
        addView(statusNotice)
        overlay.addView(scroller)
        overlay.addView(topBar)
        overlay.addView(bottomBar)
        scroller.visibility = GONE
        volume.contentDescription = "Overall mix volume"
        balance.contentDescription = "Mix: $labelA to $labelB; centre is equal"
        volume.progressTintList = ColorStateList.valueOf(context.getColor(R.color.prototype_primary))
        volume.thumbTintList = volume.progressTintList
        balance.progressTintList = ColorStateList.valueOf(context.getColor(R.color.prototype_secondary))
        balance.thumbTintList = balance.progressTintList
        remoteControl(volume, "volume"); remoteControl(balance, "balance")
        fun listener(action: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) { if (user) action(value) }
            override fun onStartTrackingTouch(bar: SeekBar) { removeCallbacks(hideControls) }
            override fun onStopTrackingTouch(bar: SeekBar) { showControls() }
        }
        volume.setOnSeekBarChangeListener(listener { changeMix(mix.copy(overall = it)) })
        balance.setOnSeekBarChangeListener(listener { changeMix(mix.copy(balance = it)) })
        setUpScrub()
        updateLabels()
    }

    // Touch drags and commits on release; Left/Right (hold to speed up) and
    // accessibility adjustments commit on Select or after a short idle.
    private fun setUpScrub() {
        remoteControl(scrub, "scrub")
        val focusBase = scrub.onFocusChangeListener
        scrub.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            focusBase?.onFocusChange(view, focused)
            if (!focused && scrubbingMs != null && !scrubTouch) commitScrub()
        }
        scrub.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) {
                if (!user || transport == null) return
                if (scrubTouch) showScrub(value * 1_000L) else updateScrub(value * 1_000L)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {
                scrubTouch = true; removeCallbacks(hideControls); removeCallbacks(commitScrubTask)
                showScrub(bar.progress * 1_000L)
            }
            override fun onStopTrackingTouch(bar: SeekBar) { scrubTouch = false; commitScrub() }
        })
        scrub.setOnKeyListener { _, code, event ->
            val target = transport ?: return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val step = viewerScrubStepMs((event.eventTime - event.downTime).coerceAtLeast(0), target.durationMs)
                        val base = scrubbingMs ?: target.positionMs
                        updateScrub((base + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) step else -step)
                            .coerceIn(0, target.durationMs))
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (scrubbingMs == null) return@setOnKeyListener false
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) commitScrub()
                    true
                }
                else -> false
            }
        }
    }
    private fun showScrub(ms: Long) {
        val target = transport ?: return
        scrubbingMs = ms.coerceIn(0, target.durationMs)
        positionTime.text = formatScrubTarget(checkNotNull(scrubbingMs), target.durationMs)
    }
    private fun updateScrub(ms: Long) {
        val target = transport ?: return
        showScrub(ms)
        scrub.progress = (checkNotNull(scrubbingMs) / 1_000).toInt()
        ViewCompat.setStateDescription(scrub, positionDescription(checkNotNull(scrubbingMs), target.durationMs))
        removeCallbacks(hideControls); removeCallbacks(commitScrubTask)
        postDelayed(commitScrubTask, ViewerTransportSteps.SCRUB_COMMIT_IDLE_MS)
    }
    private fun cancelScrub() {
        removeCallbacks(commitScrubTask)
        scrubbingMs = null; scrubTouch = false
    }
    // A scrub is a skip by the distance from the target feed's current position.
    private fun commitScrub() {
        val chosen = scrubbingMs ?: return
        cancelScrub()
        val target = transportTarget()
        if (target != null && isAttachedToWindow && isShown) requestSkip(chosen - target.positionMs, flash = false)
        if (!overlay.isGone) showControls()
    }
    private fun positionDescription(positionMs: Long, durationMs: Long) =
        "${formatPlaybackTime(positionMs, durationMs)} of ${formatPlaybackTime(durationMs, durationMs)}"
    // The target feed: the primary feed of a pair, otherwise the other feed if
    // the primary is unavailable. Absent while any playing feed is live, so live
    // views keep LIVE / Back to live (#660).
    private fun transportTarget(): TransportTarget? {
        val available = selectedMembers()
        val replays = mutableMapOf<NativeMixedSide, NativeTimingSnapshot>()
        for (side in selectedSides) {
            if (feedMessage(side) != null) continue
            val snapshot = available.getOrNull(side.ordinal)?.let { runCatching { it.timingSnapshot() }.getOrNull() } ?: continue
            if (snapshot.live || snapshot.dynamic) return null
            val duration = snapshot.durationMs
            if (snapshot.positionMs != null && duration != null && duration > 0) replays[side] = snapshot
        }
        val primary = if (hasPairSlots) stage.primarySide else selectedSides.firstOrNull() ?: return null
        val side = (listOf(primary) + NativeMixedSide.entries).firstOrNull { it in replays } ?: return null
        val snapshot = replays.getValue(side)
        val duration = checkNotNull(snapshot.durationMs)
        return TransportTarget(side, checkNotNull(snapshot.positionMs).coerceIn(0, duration), duration)
    }
    // Keys, gestures and buttons all skip here. While this viewer's own pair
    // skip is settling, further presses add up and run next.
    private fun requestSkip(deltaMs: Long, flash: Boolean) {
        if (!isAttachedToWindow || visibility != VISIBLE) return
        val target = transportTarget() ?: return
        if (skipInFlight && playbackBusy()) {
            queuedSkipMs = runCatching { Math.addExact(queuedSkipMs, deltaMs) }.getOrDefault(queuedSkipMs)
            val predicted = ((expectedPositionMs ?: target.positionMs) + queuedSkipMs)
                .coerceIn(0, (target.durationMs - SKIP_END_MARGIN_MS).coerceAtLeast(0))
            if (flash) flashPosition(deltaMs, predicted, target.durationMs)
            return
        }
        if (playbackBusy()) { showNotice("Wait for the current adjustment to finish."); return }
        dispatchSkip(deltaMs, flash, target)
    }
    private fun dispatchSkip(deltaMs: Long, flash: Boolean, target: TransportTarget) {
        val plan = runCatching { onSkip(deltaMs) }.getOrElse { NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE) }
        if (plan.outcome == NativeSeekOutcome.REQUESTED || plan.outcome == NativeSeekOutcome.CLAMPED) {
            val moved = plan.targets.firstOrNull { it.side == target.side }?.targetMs ?: (target.positionMs + plan.movementMs)
            skipInFlight = playbackBusy()
            expectedPositionMs = moved.takeIf { skipInFlight }
            if (flash) flashPosition(plan.movementMs, moved, target.durationMs)
        } else viewerSkipRefusal(plan, deltaMs > 0, hasPairSlots)?.let(::showNotice)
        refresh()
    }
    private fun startOver() {
        if (!isAttachedToWindow || visibility != VISIBLE) return
        val action = onStartOver ?: return
        val target = transportTarget() ?: return
        if (playbackBusy()) { showNotice("Wait for the current adjustment to finish."); return }
        cancelScrub(); queuedSkipMs = 0L
        val plan = runCatching { action() }.getOrElse { NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE) }
        val moved = plan.targets.firstOrNull { it.side == target.side }?.targetMs
        if (plan.outcome == NativeSeekOutcome.REQUESTED || plan.outcome == NativeSeekOutcome.CLAMPED) {
            skipInFlight = playbackBusy()
            expectedPositionMs = moved.takeIf { skipInFlight }
        }
        startOverNotice(plan, moved, target.durationMs, hasPairSlots)?.let(::showNotice)
        refresh()
    }
    private fun flashPosition(deltaMs: Long, positionMs: Long, durationMs: Long) =
        showNotice("${formatSkipAmount(deltaMs)} · ${formatPlaybackPosition(positionMs, durationMs)}")
    // A remote fast-forward/rewind from the media session; false when nothing can skip.
    fun remoteSkip(deltaMs: Long): Boolean {
        if (transportTarget() == null) return false
        requestSkip(deltaMs, flash = overlay.isGone)
        return true
    }
    // Phone video taps: a single tap shows or hides the overlay; a double tap
    // on either half skips while skipping is available (#655).
    private fun videoTap() {
        val x = lastTouchX
        lastTouchX = null
        val tap = if (x == null || television) ViewerTap.TOGGLE_NOW
            else tapGesture.tap(SystemClock.uptimeMillis(), x, width, transportTarget() != null)
        removeCallbacks(deferredToggle)
        when (tap) {
            ViewerTap.TOGGLE_NOW -> toggleControls()
            ViewerTap.DEFER_TOGGLE -> postDelayed(deferredToggle, ViewConfiguration.getDoubleTapTimeout().toLong())
            ViewerTap.SKIP_BACK -> requestSkip(-ViewerTransportSteps.SHORT_MS, flash = overlay.isGone)
            ViewerTap.SKIP_FORWARD -> requestSkip(ViewerTransportSteps.SHORT_MS, flash = overlay.isGone)
        }
    }

    // A dark translucent band, with light text, keeps the bars legible over any
    // video in both themes. Buttons keep their own surfaces and focus outlines.
    private fun overlayScrim() = ColorDrawable(OVERLAY_SCRIM)
    private fun panelSpace(value: String?) = when (value) {
        // Marking matching events needs both feeds in view; quality choices are
        // long and judged against the picture. Both keep their own space.
        "Timing", "Quality" -> ViewerPanelSpace.RESERVE
        null -> null
        // Audio is short and adjusted by ear; Status is read-only text.
        else -> ViewerPanelSpace.OVERLAY
    }
    private fun text(value: String) = TextView(context).apply {
        text = value; setTextColor(context.getColor(R.color.prototype_text)); textSize = 14f
    }
    private fun button(value: String, key: String = value, action: (Button) -> Unit) = Button(context).apply {
        text = value; textSize = 12f; isAllCaps = false; minHeight = dp(48); minWidth = dp(48)
        setPadding(dp(4), 0, dp(4), 0); setOnClickListener {
            if (!isEnabled || !isShown || !isAttachedToWindow) return@setOnClickListener
            rememberControlFocus()
            invalidateFocusIntent()
            action(this)
        }
        stylePrototypeControl()
        remoteControl(this, key)
    }
    private fun remoteControl(control: View, key: String = control.contentDescription?.toString().orEmpty()) {
        controlKeys[control] = key
        control.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            if (focused) {
                removeCallbacks(hideControls)
                // Android can relocate focus after the disabling refresh has
                // returned. Navigation/touch/click clear intent before focus
                // moves; an unsolicited gain must not authorize fallback Select.
                if (focusMutationDepth == 0 && !restoringFocus && pendingFocus == null) {
                    invalidateFocusIntent()
                    rememberControlFocus()
                }
            } else cancelRepeats()
        }
        if (!television) return
        control.isFocusableInTouchMode = true
        control.foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(3).coerceAtLeast(1), context.getColor(R.color.prototype_text))
                cornerRadius = dp(8).toFloat()
            })
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
    }
    private fun rememberControlFocus() {
        val focused = findFocus() ?: return
        val key = controlKeys[focused] ?: return
        // Revealing never lands on Stop: a reflexive Select must not ask to stop.
        if (key == STOP_KEY) return
        var ancestor = focused.parent
        while (ancestor is View && ancestor !== content) ancestor = ancestor.parent
        if (ancestor === content && panel != null) panelFocus[checkNotNull(panel)] = key
        else overlayFocus = key
    }
    private fun invalidateFocusIntent() {
        focusGeneration++
        focusRestoreTicket++
        pendingFocus = null
    }
    private fun findControl(key: String): View? = controlKeys.entries.firstOrNull {
        it.value == key && it.key.isAttachedToWindow && it.key.isShown
    }?.key
    private fun restoreRemoteFocus(preferred: View? = play, key: String? = null, pending: FocusIntent? = null) {
        // Preserve real keyboard/remote focus on every form factor. Initial TV
        // focus retains its existing behavior; touch alone never creates intent.
        if (!television && isInTouchMode && key == null && !overlay.hasFocus()) return
        val generation = focusGeneration
        val scope = panel
        val ticket = ++focusRestoreTicket
        post {
            if (generation != focusGeneration || ticket != focusRestoreTicket || scope != panel ||
                !isAttachedToWindow || !isShown || overlay.isGone || activeMenu != null) return@post
            val target = if (key != null && key != STOP_KEY) findControl(key) else preferred
            if (pending != null && (pendingFocus != pending || target?.isEnabled != true)) return@post
            restoringFocus = true
            try {
                fun focus(view: View?): Boolean = view?.isShown == true && view.isEnabled && view.isFocusable && view.requestFocus()
                if (focus(target)) {
                    // Keep the semantic owner until a real click, navigation,
                    // touch or scope change supersedes it. Android may deliver
                    // another fallback focus gain after this restore task.
                    rememberControlFocus()
                    return@post
                }
                if (pending != null) return@post
                if (focus(play)) return@post
                // Never fall back to Stop: a reflexive Select must not ask to stop.
                for (bar in listOf(transportRow, topBar))
                    for (index in 0 until bar.childCount) bar.getChildAt(index).takeIf { controlKeys[it] != STOP_KEY }
                        ?.let { if (focus(it)) return@post }
            } finally {
                restoringFocus = false
            }
        }
    }
    private fun focusedPaneRecovery(): Boolean {
        val focused = findFocus() as? Button ?: return false
        return controlKeys[focused]?.startsWith("recovery:") == true && focused.isEnabled && focused.isShown
    }
    private fun remoteKey(code: Int) = code in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val press = event.keyCode to event.downTime
        if (suppressedFocusPress == press) {
            if (event.action == KeyEvent.ACTION_UP || event.isCanceled) suppressedFocusPress = null
            return true
        }
        // Fast-forward/rewind skip a replay 10 s on every form factor (#655).
        // Unhandled (live) presses continue to the foreground media session.
        viewerMediaSkipMs(event.keyCode)?.let { delta ->
            if (!isShown || transportTarget() == null) return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) requestSkip(delta, flash = overlay.isGone)
            return true
        }
        val activation = event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_SPACE)
        if (activation && pendingFocus != null) {
            val intent = checkNotNull(pendingFocus)
            val target = findControl(intent.key)
            if (target?.isEnabled != true || !target.isFocused) {
                if (event.action == KeyEvent.ACTION_DOWN) suppressedFocusPress = press
                if (target?.isEnabled == true) restoreRemoteFocus(key = intent.key, pending = intent)
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_TAB)) {
            invalidateFocusIntent()
            if (!overlay.isGone) startFocusPending = false
        }
        if (event.action == KeyEvent.ACTION_DOWN && !overlay.isGone && (activation || remoteKey(event.keyCode)))
            startFocusPending = false
        if (television && isShown && remoteKey(event.keyCode)) {
            if (revealPress == press) {
                if (event.action == KeyEvent.ACTION_UP) revealPress = null
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && overlay.isGone) return hiddenOverlayKey(event, press, activation)
            if (event.action == KeyEvent.ACTION_UP || event.isCanceled) cancelRepeats()
            if (event.action == KeyEvent.ACTION_DOWN) removeCallbacks(hideControls)
        }
        return super.dispatchKeyEvent(event)
    }
    // A remote key while the basic overlay is hidden. On a replay, Left/Right
    // skip 10 s with a brief position flash and keep the overlay hidden (#655);
    // Select reveals it on Play/Pause and other keys on the last control.
    // The press's repeats and release are consumed either way.
    private fun hiddenOverlayKey(event: KeyEvent, press: Pair<Int, Long>, activation: Boolean): Boolean {
        // A feed pane's Retry/Go live is already shown while the overlay is
        // hidden; Select on it acts, as one tap does (#559).
        if (activation && focusedPaneRecovery()) return super.dispatchKeyEvent(event)
        revealPress = press
        when (viewerHiddenKey(event.keyCode, transportTarget() != null)) {
            ViewerHiddenKey.SKIP_BACK -> requestSkip(-ViewerTransportSteps.SHORT_MS, flash = true)
            ViewerHiddenKey.SKIP_FORWARD -> requestSkip(ViewerTransportSteps.SHORT_MS, flash = true)
            ViewerHiddenKey.REVEAL_ON_PLAY -> { showControls(); restoreRemoteFocus(play) }
            ViewerHiddenKey.REVEAL -> { showControls(); restoreRemoteFocus(key = overlayFocus) }
        }
        return true
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { invalidateFocusIntent(); startFocusPending = false }
        // Taps are handled during the release; their side picks a skip direction.
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) lastTouchX = event.x
        return super.dispatchTouchEvent(event)
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) { invalidateFocusIntent(); cancelRepeats(); revealPress = null }
    }
    private fun row(vararg views: View) = LinearLayout(context).apply {
        views.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }
    private fun timingButtons(forA: Boolean) = ViewerTimingStep.entries.map { choice ->
        button(choice.label, "advance:${if (forA) "A" else "B"}:${choice.milliseconds}") {
            if (!hasPairSlots || pair()?.busy != false || playbackBusy() || panel != "Timing") return@button
            pendingMarkAlignment = null
            markAlignmentMessage = null
            onRelative(if (forA) choice.milliseconds else -choice.milliseconds); refresh()
        }.apply {
            id = View.generateViewId()
            contentDescription = "Advance ${if (forA) labelA else labelB} by ${choice.label} relative to ${if (forA) labelB else labelA}"
        }
    }.also { buttons ->
        buttons.forEachIndexed { index, button ->
            if (index > 0) button.nextFocusLeftId = buttons[index - 1].id
            if (index < buttons.lastIndex) button.nextFocusRightId = buttons[index + 1].id
        }
    }
    private fun markEvent(side: NativeMixedSide) {
        if (!hasPairSlots || !isAttachedToWindow || !isShown || !overlay.isShown || panel != "Timing") return
        val current = pair() ?: return
        if (current !== observedPair || current.busy || playbackBusy()) { refresh(); return }
        val sa = runCatching { current.a.timingSnapshot() }.getOrNull()
        val sb = runCatching { current.b.timingSnapshot() }.getOrNull()
        val ta = runCatching { current.a.timelineEvidence() }.getOrNull()
        val tb = runCatching { current.b.timelineEvidence() }.getOrNull()
        if (pair() !== current || current.busy || playbackBusy()) { refresh(); return }
        if (!eventMarks.mark(side, sa, sb, ta, tb)) { refresh(); return }
        markGeneration++
        pendingMarkAlignment = null
        markAlignmentMessage = null
        if (eventMarks.complete) {
            val offset = eventMarks.offsetMs(sa, sb, ta, tb)
            val adjustment = offset?.let { runCatching { Math.negateExact(it) }.getOrNull() }
            val correspondence = eventMarks.correspondence()
            // Install before calling out: the callback may synchronously poll
            // playback or refresh this viewer. Saving uses the captured immutable
            // correspondence, independently of the local button selection.
            val receipt = adjustment?.let { delta ->
                runCatching { Math.addExact(current.lastTimingSettlement?.revision ?: 0L, 1L) }.getOrNull()
                    ?.let { MarkAlignmentReceipt(current, markGeneration, it, delta) }
            }
            pendingMarkAlignment = receipt
            val generation = markGeneration
            val beforeAttempt = current.lastTimingAttempt
            val result = if (adjustment != null && correspondence != null && onMarkedCorrespondence != null)
                onMarkedCorrespondence.invoke(adjustment, correspondence)
                else if (adjustment != null && adjustment != 0L) onMarkedAlignment(adjustment) else null
            if (markGeneration != generation || pair() !== current || observedPair !== current) {
                refresh(); return
            }
            markAlignmentMessage = when {
                offset == null || adjustment == null -> "The marked estimate is unavailable or too large to align."
                adjustment == 0L && (result == null || result.outcome == NativeSeekOutcome.NO_CHANGE) -> "No adjustment needed for these marks."
                else -> when (result?.outcome) {
                    NativeSeekOutcome.AD_BLOCKED -> "Alignment refused during an advertisement. Mark again after it finishes."
                    NativeSeekOutcome.UNAVAILABLE, NativeSeekOutcome.UNSUPPORTED,
                    NativeSeekOutcome.OUTSIDE_WINDOW, NativeSeekOutcome.CLAMPED ->
                        "Full alignment is unavailable in the current playback window."
                    else -> "Alignment requested from the marked event."
                }
            }
            if (receipt != null && pendingMarkAlignment === receipt) {
                if (adjustment == 0L && (result == null || result.outcome == NativeSeekOutcome.NO_CHANGE)) {
                    completeMarkAlignment(receipt, "No adjustment needed for these marks.")
                } else if (result?.outcome == NativeSeekOutcome.REQUESTED ||
                    result == null && current.lastTimingAttempt !== beforeAttempt) {
                    receipt.attempt = current.lastTimingAttempt
                    receipt.accepted = true
                    reconcileMarkAlignment(current)
                } else pendingMarkAlignment = null
            }
        }
        showControls(); refresh()
    }
    private fun completeMarkAlignment(receipt: MarkAlignmentReceipt, message: String) {
        if (pendingMarkAlignment !== receipt || markGeneration != receipt.generation ||
            pair() !== receipt.owner || observedPair !== receipt.owner || !eventMarks.complete) return
        eventMarks.clear()
        markGeneration++
        pendingMarkAlignment = null
        markAlignmentMessage = "$message Marks cleared; mark another matching event."
    }
    private fun reconcileMarkAlignment(current: NativeMixedPair?) {
        val receipt = pendingMarkAlignment ?: return
        if (!receipt.accepted) return
        if (current == null || current !== receipt.owner || markGeneration != receipt.generation || !eventMarks.complete ||
            current.lastTimingAttempt !== receipt.attempt) {
            pendingMarkAlignment = null
            return
        }
        val settlement = current.lastTimingSettlement
        if (!current.busy && settlement?.revision == receipt.revision && settlement.deltaMs == receipt.deltaMs) {
            completeMarkAlignment(receipt, "Alignment applied.")
        } else if (!current.busy || settlement != null && settlement.revision >= receipt.revision) {
            // A cancelled/refused transaction must not acquire a later receipt
            // from an unrelated timing action, even one with the same delta.
            pendingMarkAlignment = null
        }
    }
    private fun pairOffsetAction(value: ViewerPairOffsetReadback): Boolean? {
        if (!value.available || value.busy || value.appliedOffsetMs == null) return null
        if (value.offsetActive ?: (value.appliedOffsetMs != 0L)) return false
        return true.takeIf { value.hasCorrespondence || value.savedOffsetMs != null && value.savedOffsetMs != 0L }
    }
    private fun requestPairOffset() {
        if (panel != "Timing" || !isAttachedToWindow || !isShown || !overlay.isShown) return
        val current = pair()
        val value = pairOffsetState()
        if (current == null || current !== shownPairOffsetOwner || value == null || value != shownPairOffset ||
            current.busy || playbackBusy()) { refresh(); return }
        val enabled = pairOffsetAction(value) ?: run { refresh(); return }
        if (pair() !== current || current.busy || playbackBusy()) { refresh(); return }
        pendingMarkAlignment = null
        onPairOffsetEnabled(enabled)
        showControls(); refresh()
    }
    private fun refreshPairOffset(current: NativeMixedPair?) {
        val value = pairOffsetState()
        shownPairOffset = value
        shownPairOffsetOwner = current
        pairOffsetControls.visibility = if (value == null) GONE else VISIBLE
        if (value == null) {
            pairOffsetToggle.isEnabled = false
            pairOffsetReadback.text = ""
            return
        }
        val on = value.offsetActive ?: (value.appliedOffsetMs != null && value.appliedOffsetMs != 0L)
        pairOffsetToggle.text = when {
            on -> "Offset: On"
            !value.hasCorrespondence && (value.savedOffsetMs == null || value.savedOffsetMs == 0L) ||
                !value.enabled && value.appliedOffsetMs == 0L -> "Offset: Off"
            else -> "Apply offset"
        }
        pairOffsetToggle.isSelected = on
        pairOffsetToggle.isEnabled = current != null && !current.busy && !playbackBusy() && pairOffsetAction(value) != null
        ViewCompat.setStateDescription(pairOffsetToggle, when {
            on -> "Offset applied"
            value.appliedOffsetMs == null -> "Current offset unknown"
            value.appliedOffsetMs == 0L -> "Offset off"
            else -> "Offset not applied"
        })
        pairOffsetToggle.contentDescription = "Pair offset for $labelA and $labelB: ${pairOffsetToggle.text}"
        fun describe(offset: Long): String {
            if (offset == 0L) return "no relative advance"
            val seconds = java.math.BigDecimal.valueOf(offset).movePointLeft(3).abs().stripTrailingZeros().toPlainString()
            return if (offset > 0) "advance A by $seconds s relative to B"
                else "advance B by $seconds s relative to A"
        }
        pairOffsetReadback.text = buildList {
            add(value.savedOffsetMs?.let { "Offset value: ${describe(it)}." } ?: "No offset value to apply.")
            add(when (val applied = value.appliedOffsetMs) {
                null -> "Current offset is unknown."
                0L -> "No offset applied."
                else -> "Applied offset: ${describe(applied)}."
            })
            if (value.savedOffsetMs != null && !on) add("Offset value is retained.")
            value.timelineStatus?.let(::add)
            if (value.busy) add("Updating offset…")
            else if (!value.available) add("Offset changes are unavailable right now.")
            value.message?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString("\n")
        pairOffsetReadback.contentDescription = "Offset for $labelA and $labelB: ${pairOffsetReadback.text}"
    }
    private fun liveMember(side: NativeMixedSide): NativePairMember? {
        if (side !in selectedSides) return null
        if (feedMessage(side) != null) return null
        val member = selectedMembers().getOrNull(side.ordinal) ?: return null
        val snapshot = runCatching { member.timingSnapshot() }.getOrNull() ?: return null
        return member.takeIf { snapshot.live }
    }
    private fun currentCatchUpPlan(side: NativeMixedSide, member: NativePairMember): NativeSeekPlan {
        val currentPair = pair()
        if (currentPair != null && (if (side == NativeMixedSide.A) currentPair.a else currentPair.b) !== member)
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        val snapshot = runCatching { member.timingSnapshot() }.getOrNull()
        if (snapshot == null || snapshot.state != Player.STATE_READY || playbackBusy())
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        if (snapshot.playingAd) return NativeSeekPlan(NativeSeekOutcome.AD_BLOCKED)
        return runCatching { catchUpPlan(side) }.getOrElse { NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) }
    }
    private fun requestBackToLive(side: NativeMixedSide, control: Button,
        shown: NativePairMember? = liveTargets[side.ordinal]) {
        val selectedPair = pair()
        if (!control.isEnabled || !isAttachedToWindow || visibility != VISIBLE || overlay.visibility != VISIBLE ||
            shown == null || liveTargets[side.ordinal] !== shown || liveMember(side) !== shown ||
            currentCatchUpPlan(side, shown).outcome != NativeSeekOutcome.REQUESTED ||
            pair() !== selectedPair || liveMember(side) !== shown) {
            refresh(); return
        }
        val snapshot = runCatching { shown.timingSnapshot() }.getOrNull()
        if (snapshot == null || snapshot.state != Player.STATE_READY || snapshot.playingAd) { refresh(); return }
        pendingMarkAlignment = null
        onCatchUp(side)
        showControls(); refresh()
    }
    private fun showControls() {
        overlay.visibility = VISIBLE; removeCallbacks(hideControls)
        if (panel == null && requestedPlaying) postDelayed(hideControls, 4_000)
        requestLayout()
    }
    private fun cancelRepeats() { repeats.forEach { removeCallbacks(it) }; repeats.clear() }
    private fun toggleControls() { if (overlay.isGone) showControls() else dismissControls() }
    private fun dismissControls() {
        rememberControlFocus()
        suspendControls(); panel = null; content.removeAllViews()
        panelReturnFocus = null
        qualityFields.clear()
        scroller.visibility = GONE; overlay.visibility = GONE
        if (television) requestFocus()
        requestLayout()
    }
    private fun togglePanel(value: String, opener: View? = null) {
        if (value == "Timing" && !hasPairSlots) return
        rememberControlFocus()
        invalidateFocusIntent()
        val preserveFocus = television || !isInTouchMode || overlay.hasFocus()
        focusMutationDepth++
        try {
        if (panel != value && preserveFocus) panelReturnFocus = opener ?: activeMenuAnchor ?: moreControl
        dismissMenu(false); cancelRepeats(); panel = if (panel == value) null else value
        content.removeAllViews()
        qualityFields.clear()
        // Reused controls may still belong to a removed row container.
        (listOf(volumeLabel, volume, mixLabel, balance, muteA, muteB, fit, adjustmentLabel, markResult, pairOffsetControls) + advanceA + advanceB + markButtons).forEach {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
        }
        panel?.let { current ->
            content.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(nativeBackButton(context, "Back to playback controls") { togglePanel(current) }.apply {
                    imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.prototype_text))
                    remoteControl(this)
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(text("$current controls").apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, -2, 1f))
                // Fit applies only to a panel that takes space from the video.
                if (panelSpace(current) == ViewerPanelSpace.RESERVE) addView(fit, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        if (panel == "Audio") {
            content.addView(row(volumeLabel.apply { gravity = Gravity.CENTER_VERTICAL }, volume))
            if (hasPairSlots) {
                content.addView(mixLabel)
                val left = nudgeButton("◀", -1, labelA)
                val right = nudgeButton("▶", 1, labelB)
                content.addView(LinearLayout(context).apply {
                    addView(left, LinearLayout.LayoutParams(dp(48), dp(48)))
                    addView(balance, LinearLayout.LayoutParams(0, dp(48), 1f))
                    addView(right, LinearLayout.LayoutParams(dp(48), dp(48)))
                })
                content.addView(row(muteA, button("Centre mix") { changeMix(mix.copy(balance = 50)) }, muteB))
            } else selectedSides.singleOrNull()?.let { side ->
                content.addView(if (side == NativeMixedSide.A) muteA else muteB)
            }
        } else if (panel == "Timing") {
            content.addView(text("Advance A · $labelA"))
            content.addView(row(*(advanceA + markButtons[0]).toTypedArray()))
            content.addView(text("Advance B · $labelB"))
            content.addView(row(*(advanceB + markButtons[1]).toTypedArray()))
            content.addView(markResult)
            content.addView(adjustmentLabel)
            content.addView(pairOffsetControls)
        } else if (panel == "Status") {
            content.addView(details)
            content.addView(text("Requested offsets are not measured synchronization. Live movement is limited to the available window."))
        } else if (panel == "Quality") {
            content.addView(text("Feed overrides last for this session. Save stream defaults explicitly. Auto retains audio-first selection and device constraints."))
            NativeMixedSide.entries.filter { it in selectedSides }.forEach { side ->
                val label = if (side == NativeMixedSide.A) labelA else labelB
                content.addView(text(label))
                NativeQualityKind.entries.forEach { kind ->
                    val choose = button("", "quality:$side:$kind:choose") { showQualityChoices(it, side, kind) }
                    val readback = text("")
                    val capability = text("").apply { textSize = 12f }
                    val save = button("Save stream default", "quality:$side:$kind:save") {
                        if (qualityState(side, kind)?.saving != false || playbackBusy()) return@button
                        onSaveQuality(side, kind); refresh()
                    }
                    val reset = button("Reset stream default", "quality:$side:$kind:reset") {
                        if (qualityState(side, kind)?.saving != false || playbackBusy()) return@button
                        onResetQuality(side, kind); refresh()
                    }
                    save.contentDescription = "Save $label ${kind.name.lowercase()} preference as stream default"
                    reset.contentDescription = "Reset $label stream ${kind.name.lowercase()} default to Auto; retain feed override"
                    qualityFields += QualityField(side, kind, choose, readback, capability, save, reset)
                    content.addView(choose); content.addView(readback); content.addView(capability); content.addView(row(save, reset))
                }
            }
        }
        scroller.visibility = if (panel == null) GONE else VISIBLE
        scroller.scrollTo(0, 0)
        updateLabels(); showControls(); refresh()
        if (preserveFocus) {
            if (panel == null) {
                val target = panelReturnFocus; panelReturnFocus = null
                restoreRemoteFocus(target)
            } else restoreRemoteFocus((content.getChildAt(0) as? LinearLayout)?.getChildAt(0), key = panel?.let(panelFocus::get))
        }
        } finally { focusMutationDepth-- }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun nudgeButton(value: String, delta: Int, label: String): Button {
        val button = button(value) { changeMix(mix.nudge(delta)) }
        button.contentDescription = "Mix one percent toward $label; hold to repeat"
        val repeat = object : Runnable {
            override fun run() {
                if (panel != "Audio" || visibility != VISIBLE || selectedMembers().all { it == null }) return
                changeMix(mix.nudge(delta)); postDelayed(this, 150)
            }
        }
        button.setOnLongClickListener {
            cancelRepeats(); repeats.add(repeat); repeat.run(); true
        }
        button.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) cancelRepeats()
            false // Native button click/accessibility semantics remain intact.
        }
        if (television) button.setOnKeyListener { _, _, event ->
            if (event.action == KeyEvent.ACTION_UP || event.isCanceled) cancelRepeats()
            false
        }
        return button
    }

    private fun changeMix(value: TwoFeedMix) {
        mix = value; volumeApplied = false; updateLabels(); applyVolume(); showControls()
    }
    private fun applyVolume() {
        if (selectedMembers().all { it == null }) return
        // Evaluate both: a refusal must not short-circuit the second mute/gain.
        val appliedA = NativeMixedSide.A !in selectedSides || volumeRequest(NativeMixedSide.A,
            if (hasPairSlots) mix.gainA else if (mix.muteA) 0f else mix.overall / 100f)
        val appliedB = NativeMixedSide.B !in selectedSides || volumeRequest(NativeMixedSide.B,
            if (hasPairSlots) mix.gainB else if (mix.muteB) 0f else mix.overall / 100f)
        volumeApplied = appliedA && appliedB; volumeFailed = !volumeApplied
    }
    private fun updateLabels() {
        volumeLabel.text = "Overall · ${mix.overall}%"
        mixLabel.text = "Mix A ↔ B · ${mix.balance}% toward B (50% = equal)"
        volume.progress = mix.overall; balance.progress = mix.balance
        muteA.text = if (mix.muteA) "Unmute A" else "Mute A"
        muteB.text = if (mix.muteB) "Unmute B" else "Mute B"
        muteA.contentDescription = if (mix.muteA) "Unmute $labelA" else "Mute $labelA"
        muteB.contentDescription = if (mix.muteB) "Unmute $labelB" else "Mute $labelB"
        muteA.isSelected = mix.muteA; muteB.isSelected = mix.muteB
        fit.text = "Fit video: ${if (fitVideo) "on" else "off"}"
        fit.isSelected = fitVideo
    }

    private fun dismissMenu(restoreFocus: Boolean = true) {
        val previous = activeMenu
        val anchor = activeMenuAnchor
        activeMenu = null
        activeMenuAnchor = null
        previous?.setOnDismissListener(null)
        fun clear(menu: android.view.Menu) {
            for (index in 0 until menu.size()) menu.getItem(index).let { item ->
                item.setOnMenuItemClickListener(null)
                item.subMenu?.let(::clear)
            }
        }
        previous?.menu?.let(::clear)
        previous?.dismiss()
        if (previous != null && restoreFocus) restoreRemoteFocus(anchor)
    }

    private fun showMenu(anchor: View, populate: PopupMenu.() -> Unit) {
        rememberControlFocus()
        invalidateFocusIntent()
        dismissMenu(false); cancelRepeats(); removeCallbacks(hideControls)
        if (!isAttachedToWindow || visibility != VISIBLE) return
        val popup = PopupMenu(context, anchor).apply(populate)
        popup.setOnDismissListener {
            if (activeMenu === popup) {
                activeMenu = null
                activeMenuAnchor = null
                if (isAttachedToWindow && visibility == VISIBLE) {
                    showControls()
                    restoreRemoteFocus(anchor)
                }
            }
        }
        activeMenu = popup
        activeMenuAnchor = anchor
        popup.show()
    }

    private fun showMore(anchor: View) {
        val shownPair = pair()
        val holdLabel = if (selectedMembers().count { it != null } == 1) "holds this feed" else "holds both"
        showMenu(anchor) {
            if (onStartOver != null && transportTarget() != null) menu.add("Start over").apply {
                isEnabled = !playbackBusy()
                setOnMenuItemClickListener { startOver(); true }
            }
            menu.add("Playback status").setOnMenuItemClickListener { togglePanel("Status"); true }
            menu.add("Quality").apply {
                isEnabled = selectedSides.any { qualityState(it, NativeQualityKind.VIDEO) != null }
                setOnMenuItemClickListener { togglePanel("Quality"); true }
            }
            if (!television) menu.add(if (hasPairSlots) {
                if (landscape()) "Portrait / stacked layout" else "Landscape / PiP layout"
            } else if (landscape()) "Portrait layout" else "Landscape layout")
                .setOnMenuItemClickListener { onLandscape(!landscape()); true }
            if (hasPairSlots) menu.add("Swap primary feed").setOnMenuItemClickListener { stage.swap(); true }
            if (television && hasPairSlots) {
                menu.addSubMenu("Floating feed position").apply {
                    listOf("Top left" to FloatingPosition(0f, 0f), "Top right" to FloatingPosition(1f, 0f),
                        "Bottom left" to FloatingPosition(0f, 1f), "Bottom right" to FloatingPosition(1f, 1f)).forEach { (label, position) ->
                        add(label).apply {
                            isCheckable = true; isChecked = stage.floatingPosition == position
                            setOnMenuItemClickListener { stage.placeFloating(position); true }
                        }
                    }
                }
                menu.addSubMenu("Floating feed size").apply {
                    FloatingFeedSize.entries.forEach { size -> add(size.title).apply {
                        isCheckable = true; isChecked = stage.floatingSize == size
                        setOnMenuItemClickListener { stage.sizeFloating(size); true }
                    } }
                }
            }
            NativeMixedSide.entries.filter { it in selectedSides }.forEach { side ->
                val control = backToLive[side.ordinal]
                val shown = liveTargets[side.ordinal]
                menu.add("Catch up ${if (side == NativeMixedSide.A) labelA else labelB} ($holdLabel)").apply {
                    isEnabled = control.isEnabled && shown != null && liveMember(side) === shown &&
                        currentCatchUpPlan(side, shown).outcome == NativeSeekOutcome.REQUESTED
                    setOnMenuItemClickListener {
                        if (pair() === shownPair) requestBackToLive(side, control, shown) else refresh()
                        true
                    }
                }
            }
            menu.add(setupLabel).setOnMenuItemClickListener { suspendControls(); onDiagnostics(); true }
            menu.add(if (hasPairSlots) "Stop both" else "Stop playback").setOnMenuItemClickListener { suspendControls(); onStop(); true }
        }
    }

    private fun showQualityChoices(anchor: View, side: NativeMixedSide, kind: NativeQualityKind) {
        val state = qualityState(side, kind) ?: return
        if (state.saving || playbackBusy()) return
        if (side !in selectedSides) return
        val control = selectedMembers().getOrNull(side.ordinal)?.qualitySnapshot()?.controls?.get(kind)
        showMenu(anchor) {
            fun choice(title: String, request: NativeQualityRequest?, checked: Boolean) {
                menu.add(title).apply {
                    isCheckable = true; isChecked = checked
                    setOnMenuItemClickListener {
                        if (qualityState(side, kind)?.saving == false && !playbackBusy()) {
                            onQualityOverride(side, kind, request); refresh()
                        }
                        true
                    }
                }
            }
            choice("Use stream default (${state.streamDefault.title})", null, state.feedOverride == null)
            choice("Auto for this feed", NativeQualityRequest.auto, state.feedOverride == NativeQualityRequest.auto)
            control?.options.orEmpty().forEach { option -> choice(option.title, option, state.feedOverride == option) }
        }
    }

    fun refresh() {
        val focused = findFocus()
        val key = focused?.let(controlKeys::get)
        val generation = focusGeneration
        val scope = panel
        if (pendingFocus == null) rememberControlFocus()
        focusMutationDepth++
        try { refreshControls() } finally { focusMutationDepth-- }
        // A focused step that just became busy is protected like a disabled one
        // (#617): if anything moves focus away, the next Select restores it.
        val busyStep = focused != null && focused in advanceA + advanceB &&
            ViewCompat.getStateDescription(focused)?.toString() == TIMING_BUSY
        if (generation == focusGeneration && scope == panel && key != null && focused?.isShown == true &&
            (!focused.isEnabled || busyStep) && pendingFocus == null) {
            pendingFocus = FocusIntent(key, scope, generation)
        }
        pendingFocus?.let { intent ->
            if (intent.generation != focusGeneration || intent.panel != panel) pendingFocus = null
            else if (findControl(intent.key)?.let { it.isEnabled && !it.isFocused } == true)
                restoreRemoteFocus(key = intent.key, pending = intent)
        }
    }
    private fun refreshControls() {
        val current = pair()
        if (observedPair !== current) {
            invalidateFocusIntent(); panelFocus.clear(); overlayFocus = null
            observedPair = current; volumeApplied = false
            eventMarks.clear()
            markGeneration++
            pendingMarkAlignment = null
            markAlignmentMessage = null
        }
        reconcileMarkAlignment(current)
        if (stage.bindPlayers()) volumeApplied = false
        stage.updateMessages()
        val available = selectedMembers()
        backToLive.forEachIndexed { index, control ->
            val side = NativeMixedSide.entries[index]
            val member = liveMember(side)
            liveTargets[index] = member
            control.visibility = if (member == null) GONE else VISIBLE
            control.isEnabled = member != null && currentCatchUpPlan(side, member).outcome == NativeSeekOutcome.REQUESTED &&
                liveMember(side) === member
        }
        liveControls.visibility = if (liveTargets.any { it != null }) VISIBLE else GONE
        liveHint.text = if (available.count { it != null } > 1)
            "Returns to live and resumes both feeds if both were playing. Realign afterward."
            else "Returns to live and resumes if playing. Timing alignment is not restored."
        if (available.any { it != null } && !volumeApplied) applyVolume()
        val wasPlaying = requestedPlaying
        // A pair skip holds both feeds briefly; keep the requested state shown
        // so the overlay neither flickers nor appears for a hidden skip.
        val holdingSkip = skipInFlight && playbackBusy()
        if (!holdingSkip) requestedPlaying = available.any { it?.timingSnapshot()?.playWhenReady == true }
        play.text = if (requestedPlaying || playbackBusy() && !holdingSkip) "Pause" else "Play"
        play.isEnabled = available.any { it != null }
        refreshTransport(holdingSkip)
        // A busy timing step stays enabled and keeps focus (#617). Disabling the
        // focused step let Android move focus to another control, where a quick
        // next Select acted on it (for example Back to sources). The step's
        // handler ignores presses until the adjustment completes.
        val timingReady = hasPairSlots && current != null
        val timingBusy = current?.busy != false || playbackBusy()
        (advanceA + advanceB).forEach { control ->
            control.isEnabled = timingReady
            control.alpha = if (timingReady && timingBusy) 0.5f else 1f
            ViewCompat.setStateDescription(control, if (timingReady && timingBusy) TIMING_BUSY else null)
        }
        val sa = runCatching { current?.a?.timingSnapshot() }.getOrNull()
        val sb = runCatching { current?.b?.timingSnapshot() }.getOrNull()
        val ta = runCatching { current?.a?.timelineEvidence() }.getOrNull()
        val tb = runCatching { current?.b?.timelineEvidence() }.getOrNull()
        val markedOffset = eventMarks.offsetMs(sa, sb, ta, tb)
        markButtons.forEachIndexed { index, control ->
            val marked = if (index == 0) eventMarks.markedA else eventMarks.markedB
            control.isEnabled = hasPairSlots && current != null && !current.busy && !playbackBusy() &&
                (marked || eventMarks.canMark(sa, sb, ta, tb))
            control.text = if (marked) "Marked" else "Mark"
            control.isSelected = marked
            ViewCompat.setStateDescription(control, if (marked) "Event marked; activate to clear both marks" else "Event not marked")
        }
        markResult.text = when {
            eventMarks.complete && playbackBusy() -> "Updating marked estimate after adjustment…"
            markedOffset != null -> {
                val seconds = String.format(java.util.Locale.getDefault(), "%.1f", kotlin.math.abs(markedOffset / 1_000.0))
                val result = when {
                    markedOffset > 0 -> "A is about $seconds s ahead of B. Advance B to close the gap."
                    markedOffset < 0 -> "B is about $seconds s ahead of A. Advance A to close the gap."
                    else -> "About $seconds s apart."
                }
                "${markAlignmentMessage?.let { "$it " }.orEmpty()}Marked estimate: $result Press a Marked button to clear both marks."
            }
            eventMarks.complete -> markAlignmentMessage ?: "Waiting for both playback positions to update the marked estimate."
            eventMarks.markedA -> "A marked. Mark B at the same event to align automatically."
            eventMarks.markedB -> "B marked. Mark A at the same event to align automatically."
            else -> markAlignmentMessage ?: "Press Mark in each row when you see or hear the same event. The second mark aligns the feeds."
        }
        markResult.contentDescription = "Event marks for $labelA and $labelB: ${markResult.text}"
        val adjustment = when {
            current == null -> "Relative timing requires two playable feeds."
            !current.requestedAdjustmentValid -> "Earlier adjustment total is unknown. Fresh marks can still align the feeds."
            else -> "Requested A relative to B: ${current.requestedAdjustmentMs / 1_000.0} s (not measured)."
        }
        val playbackStatus = statusOverride() ?: current?.status ?: "Use setup to prepare playback."
        val audioWarning = if (volumeFailed) "Volume request failed; shown mix is requested, not confirmed.\n" else ""
        // Full status is in the Status panel; its changes show as brief notices.
        notices.observe(audioWarning + playbackStatus, selectedSides.associate { side ->
            side.name to (feedMessage(side) == null)
        })?.let(::showNotice)
        val quality = available.mapIndexedNotNull { index, member ->
            member?.qualitySnapshot()?.let { "${if (index == 0) "A" else "B"} quality:\n${it.details()}" }
        }.joinToString("\n")
        details.text = "$audioWarning$playbackStatus\n$adjustment" + if (quality.isEmpty()) "" else "\n$quality"
        details.contentDescription = "Playback status: ${details.text}"
        adjustmentLabel.text = adjustment
        adjustmentLabel.contentDescription = "$labelA relative to $labelB: $adjustment"
        refreshPairOffset(current)
        qualityFields.forEach { field ->
            val state = qualityState(field.side, field.kind)
            val snapshot = available.getOrNull(field.side.ordinal)?.qualitySnapshot()
            val control = snapshot?.controls?.get(field.kind)
            val label = if (field.side == NativeMixedSide.A) labelA else labelB
            val kind = field.kind.name.lowercase().replaceFirstChar { it.uppercase() }
            field.choose.text = "$kind · ${state?.effective?.title ?: "unavailable"} · choose"
            field.choose.contentDescription = "$label $kind quality; ${state?.scope ?: "unavailable"}; requested ${state?.effective?.title ?: "unavailable"}; choose"
            val actual = if (field.kind == NativeQualityKind.VIDEO) snapshot?.video else snapshot?.audio
            field.readback.text = "${state?.scope ?: "Unavailable"} · requested: ${state?.effective?.title ?: "unavailable"}\nActual $kind: ${actual?.summary() ?: "unavailable"}"
            field.capability.text = (control?.let { "${it.explanation}. ${it.capability}." +
                if (it.omitted > 0) " ${it.omitted} additional options omitted." else "" }
                ?: "Waiting for this feed's supported tracks; no quality change confirmed.") +
                (state?.message?.let { "\n$it" } ?: "")
            val enabled = state != null && !state.saving && !playbackBusy()
            field.choose.isEnabled = enabled; field.save.isEnabled = enabled; field.reset.isEnabled = enabled
        }
        if (wasPlaying != requestedPlaying) showControls()
        // The viewer opens before its feeds are ready, while Play/Pause is
        // still disabled and focus had to fall back. Until the person moves
        // focus, put it on Play/Pause as soon as that can take it (#656).
        if (startFocusPending && play.isEnabled && play.isShown && !play.isFocused && panel == null &&
            activeMenu == null && pendingFocus == null) {
            startFocusPending = false
            restoreRemoteFocus(play)
        } else if (play.isFocused) startFocusPending = false
        if (available.all { it == null }) suspendControls()
    }
    private fun refreshTransport(holdingSkip: Boolean) {
        val target = transportTarget()
        transport = target
        positionRow.visibility = if (target == null) GONE else VISIBLE
        skipButtons.forEach { it.visibility = if (target == null) GONE else VISIBLE }
        if (target == null) {
            if (scrubbingMs != null) cancelScrub()
        } else {
            val label = if (target.side == NativeMixedSide.A) labelA else labelB
            positionTitle.text = if (hasPairSlots) "${target.side.name} · $label" else label
            val shown = scrubbingMs ?: expectedPositionMs?.takeIf { holdingSkip } ?: target.positionMs
            positionTime.text = scrubbingMs?.let { formatScrubTarget(it, target.durationMs) }
                ?: formatPlaybackPosition(shown, target.durationMs)
            positionTime.contentDescription = positionDescription(shown, target.durationMs)
            scrub.max = (target.durationMs / 1_000).toInt().coerceAtLeast(1)
            if (scrubbingMs == null) scrub.progress = (shown / 1_000).toInt()
            scrub.contentDescription = "Position in ${positionTitle.text}"
            ViewCompat.setStateDescription(scrub, positionDescription(shown, target.durationMs))
        }
        if (skipInFlight && !holdingSkip) {
            skipInFlight = false; expectedPositionMs = null
            val queued = queuedSkipMs
            queuedSkipMs = 0L
            // Further presses made while the last skip settled run as one skip.
            if (queued != 0L) post {
                val next = transportTarget()
                if (next != null && !playbackBusy() && isAttachedToWindow && visibility == VISIBLE) dispatchSkip(queued, false, next)
            }
        }
    }

    // System Back unwinds menus, panels and the toolbar before stopping playback.
    // Popup windows normally consume Back themselves; also handle dispatcher
    // delivery here without invoking any menu action or playback command.
    fun handleBack(): Boolean {
        if (!isAttachedToWindow || visibility != VISIBLE) return false
        if (activeMenu != null) {
            dismissMenu(); showControls()
            return true
        }
        // Back abandons an uncommitted scrub without moving the feeds.
        if (scrubbingMs != null) { cancelScrub(); showControls(); refresh(); return true }
        panel?.let { togglePanel(it); return true }
        if (!overlay.isGone) {
            dismissControls()
            return true
        }
        return false
    }
    fun open() { visibility = VISIBLE; startFocusPending = panel == null && overlayFocus == null; showControls(); refresh(); restoreRemoteFocus(key = panel?.let(panelFocus::get) ?: overlayFocus) }
    fun endSession() { suspendControls(); clearNotice(); notices.reset(); panelFocus.clear(); overlayFocus = null; suppressedFocusPress = null; stage.unbindPlayers(); observedPair = null; eventMarks.clear(); markGeneration++; pendingMarkAlignment = null; markAlignmentMessage = null; visibility = GONE }
    fun suspendControls() {
        invalidateFocusIntent()
        dismissMenu(false); cancelRepeats(); revealPress = null; removeCallbacks(hideControls)
        cancelScrub(); removeCallbacks(deferredToggle); tapGesture.reset(); queuedSkipMs = 0L
    }
    override fun onDetachedFromWindow() { suspendControls(); clearNotice(); stage.unbindPlayers(); super.onDetachedFromWindow() }
    // A brief notice from the owner, such as where remembered positions resumed.
    fun announce(message: String) { if (isAttachedToWindow && visibility == VISIBLE) showNotice(message) }
    private fun showNotice(message: String) {
        if (panel == "Status") return // The open panel already shows it.
        removeCallbacks(fadeNotice); statusNotice.animate().cancel()
        statusNotice.text = message; statusNotice.alpha = 1f; statusNotice.visibility = VISIBLE
        postDelayed(fadeNotice, ViewerStatusNotices.SHOWN_MS)
    }
    private fun clearNotice() {
        removeCallbacks(fadeNotice); statusNotice.animate().cancel(); statusNotice.visibility = GONE
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec); val h = MeasureSpec.getSize(heightSpec)
        val contentW = (w - paddingLeft - paddingRight).coerceAtLeast(0)
        val contentH = (h - paddingTop - paddingBottom).coerceAtLeast(0)
        val density = resources.displayMetrics.density
        val open = panel != null
        val side = viewerSideSheet(contentW, landscape(), open, density)
        val sheetW = viewerSheetWidth(contentW, landscape(), open, density, scroller.paddingLeft + scroller.paddingRight)
        val barWidth = MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY)
        val atMost = MeasureSpec.makeMeasureSpec(contentH, MeasureSpec.AT_MOST)
        topBar.measure(barWidth, atMost); bottomBar.measure(barWidth, atMost)
        scroller.maximumHeight = viewerSheetCap(contentH, topBar.measuredHeight, bottomBar.measuredHeight)
        if (scroller.visibility != GONE) scroller.measure(MeasureSpec.makeMeasureSpec(sheetW, MeasureSpec.EXACTLY), atMost)
        val visible = !overlay.isGone
        val next = viewerOverlayPlan(contentW, contentH, landscape(), visible, panelSpace(panel), fitVideo,
            topBar.measuredHeight, bottomBar.measuredHeight, sheetW,
            if (scroller.visibility != GONE) scroller.measuredHeight else 0, side)
        plan = next
        next.sheet?.let { sheet ->
            scroller.measure(MeasureSpec.makeMeasureSpec(sheet.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(sheet.height, MeasureSpec.EXACTLY))
        }
        overlay.measure(barWidth, MeasureSpec.makeMeasureSpec(contentH, MeasureSpec.EXACTLY))
        stage.floatingTop = next.floatingTop
        stage.floatingRight = next.floatingRight
        stage.floatingBottom = next.floatingBottom
        stage.measure(MeasureSpec.makeMeasureSpec(next.stage.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(next.stage.height, MeasureSpec.EXACTLY))
        statusNotice.measure(MeasureSpec.makeMeasureSpec(minOf(dp(480), (next.stage.width - dp(32)).coerceAtLeast(0)),
            MeasureSpec.AT_MOST), atMost)
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        stage.layout(paddingLeft, paddingTop, paddingLeft + stage.measuredWidth, paddingTop + stage.measuredHeight)
        if (overlay.visibility != GONE) overlay.layout(paddingLeft, paddingTop,
            paddingLeft + overlay.measuredWidth, paddingTop + overlay.measuredHeight)
        if (statusNotice.visibility != GONE) {
            // Centered over the video, below where the top bar appears.
            val x = paddingLeft + (stage.measuredWidth - statusNotice.measuredWidth) / 2
            val y = paddingTop + topBar.measuredHeight + dp(8)
            statusNotice.layout(x, y, x + statusNotice.measuredWidth, y + statusNotice.measuredHeight)
        }
    }
    private fun landscape() = television || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Children are measured by the viewer and placed at its overlay plan.
    private inner class ControlsLayer : FrameLayout(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val current = plan ?: return
            fun place(view: View, rect: ViewerRect?) {
                if (view.visibility == GONE || rect == null) return
                view.layout(rect.left, rect.top, rect.left + rect.width, rect.top + rect.height)
            }
            place(topBar, current.topBar); place(bottomBar, current.bottomBar); place(scroller, current.sheet)
        }
    }

    // With skips shown, the five transport controls share up to 560 dp
    // equally; otherwise Play/Pause keeps its own width.
    private inner class TransportRow : LinearLayout(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val available = MeasureSpec.getSize(widthSpec)
            val width = if (skipButtons.any { it.visibility != GONE }) minOf(available, dp(560)) else {
                play.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightSpec)
                minOf(available, play.measuredWidth)
            }
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec)
        }
    }

    private inner class CappedScrollView : ScrollView(context) {
        var maximumHeight = 0
            set(value) { if (field != value) { field = value; requestLayout() } }
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(minOf(maximumHeight, MeasureSpec.getSize(heightSpec)), MeasureSpec.AT_MOST))
        }
    }

    private inner class FeedStage : FrameLayout(context) {
        var floatingTop = 0
            set(value) { if (field != value) { field = value; requestLayout() } }
        var floatingBottom = 0
            set(value) { if (field != value) { field = value; requestLayout() } }
        var floatingRight = 0
            set(value) { if (field != value) { field = value; requestLayout() } }
        private var primaryA = true
        val primarySide get() = if (primaryA) NativeMixedSide.A else NativeMixedSide.B
        private var floating = FloatingPosition()
        val floatingPosition get() = floating
        var floatingSize = FloatingFeedSize.MEDIUM
            private set
        fun placeFloating(position: FloatingPosition) { floating = position; showControls(); requestLayout() }
        fun sizeFloating(size: FloatingFeedSize) { floatingSize = size; showControls(); requestLayout() }
        private var ratioA = 16f / 9f
        private var ratioB = 16f / 9f
        private var playerA: Player? = null
        private var playerB: Player? = null
        private val listenerA = object : Player.Listener { override fun onVideoSizeChanged(size: VideoSize) { ratioA = aspect(size); requestLayout() } }
        private val listenerB = object : Player.Listener { override fun onVideoSizeChanged(size: VideoSize) { ratioB = aspect(size); requestLayout() } }
        private fun aspect(size: VideoSize) = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 16f / 9f
        private val paneA = pane(a, labelA, true)
        private val paneB = pane(b, labelB, false)
        private val noticeA = notice(paneA, NativeMixedSide.A)
        private val noticeB = notice(paneB, NativeMixedSide.B)
        private inner class FeedNotice(val scroll: ScrollView, val message: TextView, val recovery: Button)
        init {
            setOnClickListener { videoTap() }
            if (television) isFocusable = false
            if (NativeMixedSide.A in selectedSides) addView(paneA)
            if (NativeMixedSide.B in selectedSides) addView(paneB)
        }
        fun bindPlayers(): Boolean {
            val nextA = a.player.takeIf { NativeMixedSide.A in selectedSides }
            val nextB = b.player.takeIf { NativeMixedSide.B in selectedSides }
            if (playerA === nextA && playerB === nextB) return false
            unbindPlayers(); playerA = nextA; playerB = nextB
            playerA?.addListener(listenerA); playerB?.addListener(listenerB)
            ratioA = playerA?.videoSize?.let(::aspect) ?: ratioA
            ratioB = playerB?.videoSize?.let(::aspect) ?: ratioB
            requestLayout()
            return true
        }
        private fun notice(pane: FrameLayout, side: NativeMixedSide): FeedNotice {
            val notice = text("")
            val recover = button("", "recovery:$side") { control ->
                if (side in selectedSides && control.isEnabled && feedMessage(side) != null && feedRecovery(side)?.enabled == true) {
                    control.isEnabled = false
                    onRecoverFeed(side)
                    refresh()
                }
            }
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(36), dp(12), dp(8))
                addView(notice, LinearLayout.LayoutParams(-1, -2))
                addView(recover, LinearLayout.LayoutParams(-2, -2))
            }
            val scroll = object : ScrollView(context) {
                private val slop = ViewConfiguration.get(context).scaledTouchSlop
                private var downX = 0f
                private var downY = 0f
                private var tap = false
                private var recoveryGesture = false
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x; downY = event.y; tap = true
                            val bounds = Rect()
                            recover.getDrawingRect(bounds)
                            offsetDescendantRectToMyCoords(recover, bounds)
                            // Reserve disabled actions too: tapping Retry during cleanup
                            // must not unexpectedly swap the floating feed.
                            recoveryGesture = recover.visibility == VISIBLE && bounds.contains(event.x.toInt(), event.y.toInt())
                        }
                        MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) tap = false
                        MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tap = false
                        MotionEvent.ACTION_UP -> if (!recoveryGesture && tap && abs(event.x - downX) <= slop && abs(event.y - downY) <= slop) {
                            tap = false
                            // Finish native touch handling before forwarding one pane click.
                            // Scrolling keeps its normal event stream and never swaps feeds.
                            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
                            performClick()
                            return true
                        }
                    }
                    return super.dispatchTouchEvent(event)
                }
            }.apply {
                setOnClickListener { pane.performClick() }
                setBackgroundColor(context.getColor(R.color.prototype_background))
                addView(body, LayoutParams(-1, -2))
                visibility = GONE
            }
            // Preparation and recovery notices sit above the video surface.
            pane.addView(scroll, 1, LayoutParams(-1, -1))
            return FeedNotice(scroll, notice, recover)
        }
        fun updateMessages() {
            fun update(side: NativeMixedSide, player: PlayerView, label: String, notice: FeedNotice) {
                if (side !in selectedSides) {
                    player.visibility = GONE
                    notice.scroll.visibility = GONE
                    notice.recovery.isEnabled = false
                    return
                }
                val message = feedMessage(side)
                val recovery = if (message == null) null else feedRecovery(side)
                notice.message.text = message.orEmpty()
                notice.scroll.visibility = if (message == null) GONE else VISIBLE
                player.visibility = if (message == null) VISIBLE else GONE
                notice.message.contentDescription = "$label: ${message.orEmpty()}"
                notice.recovery.text = recovery?.label.orEmpty()
                notice.recovery.contentDescription = "${side.name} · $label · ${recovery?.label.orEmpty()}"
                notice.recovery.visibility = if (recovery == null) GONE else VISIBLE
                notice.recovery.isEnabled = recovery?.enabled == true
            }
            update(NativeMixedSide.A, a, labelA, noticeA)
            update(NativeMixedSide.B, b, labelB, noticeB)
        }
        fun unbindPlayers() { playerA?.removeListener(listenerA); playerB?.removeListener(listenerB); playerA = null; playerB = null }
        fun swap() { if (hasPairSlots) { primaryA = !primaryA; cancelScrub(); showControls(); requestLayout(); refresh() } }
        @SuppressLint("ClickableViewAccessibility")
        private fun pane(player: PlayerView, label: String, sideA: Boolean) = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(player, LayoutParams(-1, -1))
            // Parent handles viewing gestures, never the PlayerView's own transport.
            player.isClickable = false; player.isFocusable = false
            contentDescription = if (hasPairSlots) "$label feed; tap floating feed to swap, drag to move" else "$label feed"
            // The floating feed's tap swaps at once; it has no double tap.
            setOnClickListener { if (hasPairSlots && landscape() && sideA != primaryA) swap() else videoTap() }
            var downX = 0f; var downY = 0f; var originLeft = 0; var originTop = 0; var dragged = false
            var gestureActive = false; var gestureDownTime = 0L
            val slop = ViewConfiguration.get(context).scaledTouchSlop
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        gestureActive = true; gestureDownTime = event.downTime
                        downX = event.rawX; downY = event.rawY; originLeft = view.left; originTop = view.top; dragged = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (hasPairSlots && gestureActive && event.downTime == gestureDownTime && landscape() && sideA != primaryA) {
                            val dx = event.rawX - downX; val dy = event.rawY - downY
                            if (abs(dx) > slop || abs(dy) > slop) dragged = true
                            if (dragged) {
                                floating = FloatingPosition.fromPixels(originLeft + dx, originTop + dy - floatingTop,
                                    floatingRight - view.width, floatingBottom - floatingTop - view.height)
                                requestLayout()
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val click = gestureActive && event.downTime == gestureDownTime && !dragged
                        gestureActive = false
                        if (click) view.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> { gestureActive = false; dragged = false; true }
                    else -> true
                }
            }
            if (television) isFocusable = false
        }
        // Notice content avoids the visible bars and panels without moving the video.
        private fun insetNotice(notice: FeedNotice, rect: ViewerRect) {
            val insets = viewerPaneInsets(rect, floatingTop, floatingRight, floatingBottom)
            notice.scroll.setPadding(0, insets.top, insets.right, insets.bottom)
        }
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
            if (!hasPairSlots) {
                val full = ViewerRect(0, 0, measuredWidth, measuredHeight)
                insetNotice(noticeA, full); insetNotice(noticeB, full)
                for (index in 0 until childCount) getChildAt(index).measure(
                    MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY))
                return
            }
            val bounds = twoFeedBounds(measuredWidth, measuredHeight, landscape(), primaryA, ratioA, ratioB, floating,
                floatingBottom, floatingRight, floatingSize, floatingTop)
            fun measure(view: View, rect: ViewerRect) { view.measure(MeasureSpec.makeMeasureSpec(rect.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rect.height, MeasureSpec.EXACTLY)) }
            insetNotice(noticeA, bounds.a); insetNotice(noticeB, bounds.b)
            measure(paneA, bounds.a); measure(paneB, bounds.b)
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            if (!hasPairSlots) {
                for (index in 0 until childCount) getChildAt(index).layout(0, 0, width, height)
                return
            }
            val bounds = twoFeedBounds(width, height, landscape(), primaryA, ratioA, ratioB, floating,
                floatingBottom, floatingRight, floatingSize, floatingTop)
            fun place(view: View, rect: ViewerRect) {
                view.measure(MeasureSpec.makeMeasureSpec(rect.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rect.height, MeasureSpec.EXACTLY))
                view.layout(rect.left, rect.top, rect.left + rect.width, rect.top + rect.height)
            }
            place(paneA, bounds.a); place(paneB, bounds.b)
            if (landscape()) (if (primaryA) paneB else paneA).bringToFront()
        }
    }
}

private const val STOP_KEY = "Stop"
private const val OVERLAY_SCRIM = 0x99000000.toInt() // Black at 60%.
private const val OVERLAY_TEXT = 0xFFE8ECEE.toInt()

// State description of a timing step while an adjustment is in progress.
internal const val TIMING_BUSY = "Adjustment in progress"
