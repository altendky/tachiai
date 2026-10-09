package net.fstab.tachiai.feature.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
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
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.R
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.presentation.FloatingPosition
import net.fstab.tachiai.presentation.TwoFeedMix
import net.fstab.tachiai.presentation.ViewerRect
import net.fstab.tachiai.presentation.ViewerTimingStep
import net.fstab.tachiai.presentation.twoFeedBounds
import kotlin.math.abs

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
    private val onSources: (() -> Unit)? = null,
) : FrameLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private var mix = TwoFeedMix()
    private var panel: String? = null
    private var fitVideo = true
    private var sideDock = false
    private var activeMenu: PopupMenu? = null
    private var requestedPlaying = false
    private var observedPair: NativeMixedPair? = null
    private var volumeApplied = false
    private var volumeFailed = false
    private val repeats = mutableListOf<Runnable>()
    private val hideControls = Runnable {
        if (panel == null && requestedPlaying) { dock.visibility = GONE; requestLayout() }
    }
    private val stage = FeedStage()
    private val dock = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4))
        background = context.prototypeSurface(R.color.prototype_panel)
    }
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroller = CappedScrollView().apply { addView(content) }
    private val scrollerParams = LinearLayout.LayoutParams(-1, -2)
    private val play = button("Play") {
        if (requestedPlaying || pair()?.busy == true) onPause() else onPlay()
        showControls(); refresh()
    }
    private val readback = text("").apply { textSize = 12f; maxLines = 2 }
    private val details = text("")
    private val adjustmentLabel = text("").apply { textSize = 12f }
    private val volume = SeekBar(context).apply { max = 100; progress = mix.overall; minimumHeight = dp(48) }
    private val balance = SeekBar(context).apply { max = 100; progress = mix.balance }
    private val volumeLabel = text("")
    private val mixLabel = text("")
    private val muteA = button("") { changeMix(mix.copy(muteA = !mix.muteA)) }
    private val muteB = button("") { changeMix(mix.copy(muteB = !mix.muteB)) }
    private val advanceA = timingButtons(true)
    private val advanceB = timingButtons(false)
    private val fit = button("") { fitVideo = !fitVideo; updateLabels(); showControls() }.apply {
        contentDescription = "Fit video beside visible controls; toggle without restarting playback"
    }
    private val toolbar = row(*listOfNotNull(play, button("Audio") { togglePanel("Audio") },
        button("Timing") { togglePanel("Timing") }, onSources?.let { action -> button("Sources") { action() } },
        button("More") { showMore(it) },
        button("Hide") { dismissControls() }.apply { contentDescription = "Hide all playback controls" }).toTypedArray())

    init {
        for (index in advanceA.indices) {
            advanceA[index].nextFocusDownId = advanceB[index].id
            advanceB[index].nextFocusUpId = advanceA[index].id
        }
        setBackgroundColor(Color.BLACK)
        // Consume empty-area taps here rather than passing them to the hidden
        // provider page. Child controls and pane gestures keep their handlers.
        setOnClickListener { toggleControls() }
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        addView(stage)
        addView(dock)
        dock.addView(scroller, scrollerParams)
        dock.addView(toolbar)
        dock.addView(readback)
        scroller.visibility = GONE
        volume.contentDescription = "Overall mix volume"
        balance.contentDescription = "Mix: $labelA to $labelB; centre is equal"
        volume.progressTintList = ColorStateList.valueOf(context.getColor(R.color.prototype_primary))
        volume.thumbTintList = volume.progressTintList
        balance.progressTintList = ColorStateList.valueOf(context.getColor(R.color.prototype_secondary))
        balance.thumbTintList = balance.progressTintList
        fun listener(action: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) { if (user) action(value) }
            override fun onStartTrackingTouch(bar: SeekBar) { removeCallbacks(hideControls) }
            override fun onStopTrackingTouch(bar: SeekBar) { showControls() }
        }
        volume.setOnSeekBarChangeListener(listener { changeMix(mix.copy(overall = it)) })
        balance.setOnSeekBarChangeListener(listener { changeMix(mix.copy(balance = it)) })
        updateLabels()
    }

    private fun text(value: String) = TextView(context).apply {
        text = value; setTextColor(context.getColor(R.color.prototype_text)); textSize = 14f
    }
    private fun button(value: String, action: (Button) -> Unit) = Button(context).apply {
        text = value; textSize = 12f; isAllCaps = false; minHeight = dp(48); minWidth = dp(48)
        setPadding(dp(4), 0, dp(4), 0); setOnClickListener { action(this) }
        stylePrototypeControl()
    }
    private fun row(vararg views: View) = LinearLayout(context).apply {
        views.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }
    private fun timingButtons(forA: Boolean) = ViewerTimingStep.entries.map { choice ->
        button(choice.label) {
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
    private fun showControls() {
        dock.visibility = VISIBLE; removeCallbacks(hideControls)
        if (panel == null && requestedPlaying) postDelayed(hideControls, 4_000)
        requestLayout()
    }
    private fun cancelRepeats() { repeats.forEach { removeCallbacks(it) }; repeats.clear() }
    private fun toggleControls() { if (dock.isGone) showControls() else dismissControls() }
    private fun dismissControls() {
        suspendControls(); panel = null; content.removeAllViews()
        scroller.visibility = GONE; dock.visibility = GONE; requestLayout()
    }
    private fun togglePanel(value: String) {
        dismissMenu(); cancelRepeats(); panel = if (panel == value) null else value
        content.removeAllViews()
        // Reused controls may still belong to a removed row container.
        (listOf(volumeLabel, volume, mixLabel, balance, muteA, muteB, fit, adjustmentLabel) + advanceA + advanceB).forEach {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
        }
        if (panel != null) content.addView(row(text("${checkNotNull(panel)} controls").apply { gravity = Gravity.CENTER_VERTICAL }, fit))
        if (panel == "Audio") {
            content.addView(row(volumeLabel.apply { gravity = Gravity.CENTER_VERTICAL }, volume))
            content.addView(mixLabel)
            val left = nudgeButton("◀", -1, labelA)
            val right = nudgeButton("▶", 1, labelB)
            content.addView(LinearLayout(context).apply {
                addView(left, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(balance, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(right, LinearLayout.LayoutParams(dp(48), dp(48)))
            })
            content.addView(row(muteA, button("Centre mix") { changeMix(mix.copy(balance = 50)) }, muteB))
        } else if (panel == "Timing") {
            content.addView(text("Advance A · $labelA"))
            content.addView(row(*advanceA.toTypedArray()))
            content.addView(text("Advance B · $labelB"))
            content.addView(row(*advanceB.toTypedArray()))
            content.addView(adjustmentLabel)
        } else if (panel == "Status") {
            content.addView(details)
            content.addView(text("Requested offsets are not measured synchronization. Live movement is limited to the available window."))
        }
        scroller.visibility = if (panel == null) GONE else VISIBLE
        scroller.scrollTo(0, 0)
        updateLabels(); showControls(); refresh()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun nudgeButton(value: String, delta: Int, label: String): Button {
        val button = button(value) { changeMix(mix.nudge(delta)) }
        button.contentDescription = "Mix one percent toward $label; hold to repeat"
        val repeat = object : Runnable {
            override fun run() {
                if (panel != "Audio" || visibility != VISIBLE || members().all { it == null }) return
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
        return button
    }

    private fun changeMix(value: TwoFeedMix) {
        mix = value; volumeApplied = false; updateLabels(); applyVolume(); showControls()
    }
    private fun applyVolume() {
        if (members().all { it == null }) return
        // Evaluate both: a refusal must not short-circuit the second mute/gain.
        val appliedA = volumeRequest(NativeMixedSide.A, mix.gainA)
        val appliedB = volumeRequest(NativeMixedSide.B, mix.gainB)
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

    private fun dismissMenu() {
        val previous = activeMenu
        activeMenu = null
        previous?.setOnDismissListener(null)
        previous?.menu?.let { menu ->
            for (index in 0 until menu.size()) menu.getItem(index).setOnMenuItemClickListener(null)
        }
        previous?.dismiss()
    }

    private fun showMenu(anchor: View, populate: PopupMenu.() -> Unit) {
        dismissMenu(); cancelRepeats(); removeCallbacks(hideControls)
        if (!isAttachedToWindow || visibility != VISIBLE) return
        val popup = PopupMenu(context, anchor).apply(populate)
        popup.setOnDismissListener {
            if (activeMenu === popup) {
                activeMenu = null
                if (isAttachedToWindow && visibility == VISIBLE) {
                    showControls()
                }
            }
        }
        activeMenu = popup
        popup.show()
    }

    private fun showMore(anchor: View) {
        showMenu(anchor) {
            menu.add("Playback status").setOnMenuItemClickListener { togglePanel("Status"); true }
            menu.add(if (landscape()) "Portrait / stacked layout" else "Landscape / PiP layout")
                .setOnMenuItemClickListener { onLandscape(!landscape()); true }
            menu.add("Swap primary feed").setOnMenuItemClickListener { stage.swap(); true }
            menu.add("Catch up $labelA (holds both)").apply {
                isEnabled = pair() != null
                setOnMenuItemClickListener { onCatchUp(NativeMixedSide.A); refresh(); true }
            }
            menu.add("Catch up $labelB (holds both)").apply {
                isEnabled = pair() != null
                setOnMenuItemClickListener { onCatchUp(NativeMixedSide.B); refresh(); true }
            }
            menu.add(setupLabel).setOnMenuItemClickListener { suspendControls(); onDiagnostics(); true }
            menu.add("Stop both").setOnMenuItemClickListener { suspendControls(); onStop(); true }
        }
    }

    fun refresh() {
        val current = pair()
        if (observedPair !== current) {
            observedPair = current; volumeApplied = false
        }
        if (stage.bindPlayers()) volumeApplied = false
        stage.updateMessages()
        val available = members()
        if (available.any { it != null } && !volumeApplied) applyVolume()
        val wasPlaying = requestedPlaying
        requestedPlaying = available.any { it?.timingSnapshot()?.playWhenReady == true }
        play.text = if (requestedPlaying || current?.busy == true) "Pause" else "Play"
        play.isEnabled = available.any { it != null }
        (advanceA + advanceB).forEach { it.isEnabled = current != null && !current.busy }
        val adjustment = when {
            current == null -> "Relative timing requires two playable feeds."
            !current.requestedAdjustmentValid -> "Timing anchor invalid; realign after catch-up."
            else -> "Requested A relative to B: ${current.requestedAdjustmentMs / 1_000.0} s (not measured)."
        }
        val playbackStatus = statusOverride() ?: current?.status ?: "Use setup to prepare playback."
        val audioWarning = if (volumeFailed) "Volume request failed; shown mix is requested, not confirmed.\n" else ""
        readback.text = audioWarning + playbackStatus + if (current?.requestedAdjustmentValid == false) "\nTiming anchor invalid." else ""
        // Full status is scrollable under More; the compact tray prioritizes
        // refusal/failure over the informational requested-adjustment ledger.
        val quality = available.mapIndexedNotNull { index, member ->
            member?.qualitySnapshot()?.let { "${if (index == 0) "A" else "B"} quality:\n${it.details()}" }
        }.joinToString("\n")
        details.text = "$audioWarning$playbackStatus\n$adjustment" + if (quality.isEmpty()) "" else "\n$quality"
        details.contentDescription = "Playback status: ${details.text}"
        adjustmentLabel.text = adjustment
        adjustmentLabel.contentDescription = "$labelA relative to $labelB: $adjustment"
        if (wasPlaying != requestedPlaying) showControls()
        if (available.all { it == null }) suspendControls()
    }
    fun open() { visibility = VISIBLE; showControls(); refresh() }
    fun endSession() { suspendControls(); stage.unbindPlayers(); observedPair = null; visibility = GONE }
    fun suspendControls() { dismissMenu(); cancelRepeats(); removeCallbacks(hideControls) }
    override fun onDetachedFromWindow() { suspendControls(); stage.unbindPlayers(); super.onDetachedFromWindow() }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec); val h = MeasureSpec.getSize(heightSpec)
        val contentW = (w - paddingLeft - paddingRight).coerceAtLeast(0)
        val contentH = (h - paddingTop - paddingBottom).coerceAtLeast(0)
        // A side strip keeps more video clear. Below 640 dp use the full-width
        // bottom tray so the toolbar keeps at least 48 dp touch widths.
        sideDock = landscape() && panel != null && contentW >= dp(640)
        val minimumDockWidth = toolbar.childCount * dp(48) + dock.paddingLeft + dock.paddingRight
        val dockW = if (sideDock) minOf(dp(360), (contentW * 0.45f).toInt().coerceAtLeast(minimumDockWidth)) else contentW
        val innerW = (dockW - dock.paddingLeft - dock.paddingRight).coerceAtLeast(0)
        val childWidth = MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.EXACTLY)
        val childHeight = MeasureSpec.makeMeasureSpec(contentH, MeasureSpec.AT_MOST)
        toolbar.measure(childWidth, childHeight); readback.measure(childWidth, childHeight)
        scroller.maximumHeight = minOf((contentH * 0.6f).toInt(),
            (contentH - toolbar.measuredHeight - readback.measuredHeight - dock.paddingTop - dock.paddingBottom).coerceAtLeast(0))
        dock.measure(MeasureSpec.makeMeasureSpec(dockW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(contentH, MeasureSpec.AT_MOST))
        val visible = !dock.isGone
        val reserveW = if (visible && sideDock && fitVideo) dock.measuredWidth else 0
        val reserveH = if (!landscape()) { if (visible) dock.measuredHeight else dp(56) }
            else if (visible && !sideDock && fitVideo) dock.measuredHeight else 0
        val stageW = (contentW - reserveW).coerceAtLeast(0)
        val stageH = (contentH - reserveH).coerceAtLeast(0)
        stage.floatingRight = if (visible && sideDock) (contentW - dock.measuredWidth).coerceIn(0, stageW) else stageW
        stage.floatingBottom = if (visible && !sideDock && landscape()) (contentH - dock.measuredHeight).coerceIn(0, stageH) else stageH
        stage.measure(MeasureSpec.makeMeasureSpec(stageW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(stageH, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val dockTop = height - paddingBottom - dock.measuredHeight
        stage.layout(paddingLeft, paddingTop, paddingLeft + stage.measuredWidth, paddingTop + stage.measuredHeight)
        if (dock.visibility != GONE) dock.layout(width - paddingRight - dock.measuredWidth, dockTop,
            width - paddingRight, height - paddingBottom)
    }
    private fun landscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private inner class CappedScrollView : ScrollView(context) {
        var maximumHeight = 0
            set(value) { if (field != value) { field = value; requestLayout() } }
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(minOf(maximumHeight, MeasureSpec.getSize(heightSpec)), MeasureSpec.AT_MOST))
        }
    }

    private inner class FeedStage : FrameLayout(context) {
        var floatingBottom = 0
            set(value) { if (field != value) { field = value; requestLayout() } }
        var floatingRight = 0
            set(value) { if (field != value) { field = value; requestLayout() } }
        private var primaryA = true
        private var floating = FloatingPosition()
        private var ratioA = 16f / 9f
        private var ratioB = 16f / 9f
        private var playerA: Player? = null
        private var playerB: Player? = null
        private val listenerA = object : Player.Listener { override fun onVideoSizeChanged(size: VideoSize) { ratioA = aspect(size); requestLayout() } }
        private val listenerB = object : Player.Listener { override fun onVideoSizeChanged(size: VideoSize) { ratioB = aspect(size); requestLayout() } }
        private fun aspect(size: VideoSize) = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 16f / 9f
        private val paneA = pane(a, labelA, true)
        private val paneB = pane(b, labelB, false)
        private val noticeA = notice(paneA)
        private val noticeB = notice(paneB)
        init {
            setOnClickListener { toggleControls() }
            addView(paneA); addView(paneB)
        }
        fun bindPlayers(): Boolean {
            if (playerA === a.player && playerB === b.player) return false
            unbindPlayers(); playerA = a.player; playerB = b.player
            playerA?.addListener(listenerA); playerB?.addListener(listenerB)
            ratioA = playerA?.videoSize?.let(::aspect) ?: ratioA
            ratioB = playerB?.videoSize?.let(::aspect) ?: ratioB
            requestLayout()
            return true
        }
        private fun notice(pane: FrameLayout): TextView {
            val notice = text("").apply {
                setPadding(dp(12), dp(36), dp(12), dp(12))
            }
            val scroll = object : ScrollView(context) {
                private val slop = ViewConfiguration.get(context).scaledTouchSlop
                private var downX = 0f
                private var downY = 0f
                private var tap = false
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; tap = true }
                        MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) tap = false
                        MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tap = false
                        MotionEvent.ACTION_UP -> if (tap && abs(event.x - downX) <= slop && abs(event.y - downY) <= slop) {
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
                addView(notice, LayoutParams(-1, -2))
                visibility = GONE
            }
            // Keep the source label above the scrollable preparation/error panel.
            pane.addView(scroll, 1, LayoutParams(-1, -1))
            return notice
        }
        fun updateMessages() {
            fun update(side: NativeMixedSide, player: PlayerView, label: String, notice: TextView) {
                val message = feedMessage(side)
                notice.text = message.orEmpty()
                (notice.parent as View).visibility = if (message == null) GONE else VISIBLE
                player.visibility = if (message == null) VISIBLE else GONE
                notice.contentDescription = "$label: ${message.orEmpty()}"
            }
            update(NativeMixedSide.A, a, labelA, noticeA)
            update(NativeMixedSide.B, b, labelB, noticeB)
        }
        fun unbindPlayers() { playerA?.removeListener(listenerA); playerB?.removeListener(listenerB); playerA = null; playerB = null }
        fun swap() { primaryA = !primaryA; showControls(); requestLayout() }
        @SuppressLint("ClickableViewAccessibility")
        private fun pane(player: PlayerView, label: String, sideA: Boolean) = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(player, LayoutParams(-1, -1))
            // Parent handles viewing gestures, never the PlayerView's own transport.
            player.isClickable = false; player.isFocusable = false
            addView(text(label).apply { setTextColor(Color.WHITE); setBackgroundColor(0x99000000.toInt()); setPadding(dp(8), dp(2), dp(8), dp(2)) }, LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            contentDescription = "$label feed; tap floating feed to swap, drag to move"
            setOnClickListener { if (landscape() && sideA != primaryA) swap() else toggleControls() }
            var downX = 0f; var downY = 0f; var originLeft = 0; var originTop = 0; var dragged = false
            val slop = ViewConfiguration.get(context).scaledTouchSlop
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY; originLeft = view.left; originTop = view.top; dragged = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (landscape() && sideA != primaryA) {
                            val dx = event.rawX - downX; val dy = event.rawY - downY
                            if (abs(dx) > slop || abs(dy) > slop) dragged = true
                            if (dragged) {
                                floating = FloatingPosition.fromPixels(originLeft + dx, originTop + dy,
                                    floatingRight - view.width, floatingBottom - view.height)
                                requestLayout()
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> { if (!dragged) view.performClick(); true }
                    MotionEvent.ACTION_CANCEL -> { dragged = false; true }
                    else -> true
                }
            }
        }
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
            val bounds = twoFeedBounds(measuredWidth, measuredHeight, landscape(), primaryA, ratioA, ratioB, floating,
                floatingBottom, floatingRight)
            fun measure(view: View, rect: ViewerRect) { view.measure(MeasureSpec.makeMeasureSpec(rect.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rect.height, MeasureSpec.EXACTLY)) }
            measure(paneA, bounds.a); measure(paneB, bounds.b)
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val bounds = twoFeedBounds(width, height, landscape(), primaryA, ratioA, ratioB, floating, floatingBottom, floatingRight)
            fun place(view: View, rect: ViewerRect) {
                view.measure(MeasureSpec.makeMeasureSpec(rect.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rect.height, MeasureSpec.EXACTLY))
                view.layout(rect.left, rect.top, rect.left + rect.width, rect.top + rect.height)
            }
            place(paneA, bounds.a); place(paneB, bounds.b)
            if (landscape()) (if (primaryA) paneB else paneA).bringToFront()
        }
    }
}
