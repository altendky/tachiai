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
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeSeekPlan
import net.fstab.tachiai.R
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativeQualityKind
import net.fstab.tachiai.platform.media.NativeQualityRequest
import net.fstab.tachiai.presentation.ViewerQualityReadback
import net.fstab.tachiai.presentation.FloatingPosition
import net.fstab.tachiai.presentation.FloatingFeedSize
import net.fstab.tachiai.presentation.TwoFeedMix
import net.fstab.tachiai.presentation.ViewerRect
import net.fstab.tachiai.presentation.ViewerTimingStep
import net.fstab.tachiai.presentation.twoFeedBounds
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
) : FrameLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val television = isTelevision(resources.configuration)
    private var mix = TwoFeedMix()
    private var panel: String? = null
    private var fitVideo = true
    private var sideDock = false
    private var activeMenu: PopupMenu? = null
    private var activeMenuAnchor: View? = null
    private var panelReturnFocus: View? = null
    private var revealPress: Pair<Int, Long>? = null
    private var focusGeneration = 0L
    private var requestedPlaying = false
    private var observedPair: NativeMixedPair? = null
    private var volumeApplied = false
    private var volumeFailed = false
    private val repeats = mutableListOf<Runnable>()
    private val liveTargets = arrayOfNulls<NativePairMember>(2)
    private data class QualityField(val side: NativeMixedSide, val kind: NativeQualityKind,
        val choose: Button, val readback: TextView, val capability: TextView, val save: Button, val reset: Button)
    private val qualityFields = mutableListOf<QualityField>()
    private val hideControls = Runnable {
        if (panel == null && requestedPlaying && !(television && dock.hasFocus())) {
            dock.visibility = GONE; requestLayout()
        }
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
        if (requestedPlaying || playbackBusy()) onPause() else onPlay()
        showControls(); refresh()
    }
    private val audioControl = button("Audio") { togglePanel("Audio", it) }
    private val timingControl = button("Timing") { togglePanel("Timing", it) }
    private val moreControl = button("More") { showMore(it) }
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
    private val backToLive = NativeMixedSide.entries.map { side ->
        button("Back to live ${side.name}") { control -> requestBackToLive(side, control) }.apply {
            id = View.generateViewId()
            contentDescription = "Back to live ${side.name} · ${if (side == NativeMixedSide.A) labelA else labelB}"
        }
    }
    private val liveHint = text("").apply { textSize = 12f }
    private val liveControls = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(row(*backToLive.toTypedArray()))
        addView(liveHint)
        visibility = GONE
    }
    private val toolbar = row(*listOfNotNull(onSources?.let { action -> nativeBackButton(context, "Back to sources", action).apply {
            imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.prototype_text))
            remoteControl(this)
        } }, play, audioControl, timingControl, moreControl,
        button("Hide") { dismissControls() }.apply { contentDescription = "Hide all playback controls" }).toTypedArray())

    init {
        backToLive[0].nextFocusRightId = backToLive[1].id
        backToLive[1].nextFocusLeftId = backToLive[0].id
        for (index in advanceA.indices) {
            advanceA[index].nextFocusDownId = advanceB[index].id
            advanceB[index].nextFocusUpId = advanceA[index].id
        }
        setBackgroundColor(Color.BLACK)
        if (television) { isFocusable = true; isFocusableInTouchMode = true }
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
        dock.addView(liveControls)
        dock.addView(readback)
        scroller.visibility = GONE
        volume.contentDescription = "Overall mix volume"
        balance.contentDescription = "Mix: $labelA to $labelB; centre is equal"
        volume.progressTintList = ColorStateList.valueOf(context.getColor(R.color.prototype_primary))
        volume.thumbTintList = volume.progressTintList
        balance.progressTintList = ColorStateList.valueOf(context.getColor(R.color.prototype_secondary))
        balance.thumbTintList = balance.progressTintList
        remoteControl(volume); remoteControl(balance)
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
        remoteControl(this)
    }
    private fun remoteControl(control: View) {
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
        control.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            if (focused) removeCallbacks(hideControls) else cancelRepeats()
        }
    }
    private fun restoreRemoteFocus(preferred: View? = play) {
        if (!television) return
        val generation = focusGeneration
        post {
            if (generation != focusGeneration || !isAttachedToWindow || !isShown || dock.isGone || activeMenu != null) return@post
            if (preferred?.isShown == true && preferred.isEnabled && preferred.isFocusable && preferred.requestFocus()) return@post
            if (play.isShown && play.isEnabled && play.requestFocus()) return@post
            for (index in 0 until toolbar.childCount) {
                val child = toolbar.getChildAt(index)
                if (child.isShown && child.isEnabled && child.isFocusable && child.requestFocus()) return@post
            }
        }
    }
    private fun remoteKey(code: Int) = code in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (television && isShown && remoteKey(event.keyCode)) {
            val press = event.keyCode to event.downTime
            if (revealPress == press) {
                if (event.action == KeyEvent.ACTION_UP) revealPress = null
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && dock.isGone) {
                revealPress = press
                showControls(); restoreRemoteFocus()
                return true
            }
            if (event.action == KeyEvent.ACTION_UP || event.isCanceled) cancelRepeats()
            if (event.action == KeyEvent.ACTION_DOWN) removeCallbacks(hideControls)
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (television && !hasWindowFocus) { cancelRepeats(); revealPress = null }
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
    private fun liveMember(side: NativeMixedSide): NativePairMember? {
        if (feedMessage(side) != null) return null
        val member = members().getOrNull(side.ordinal) ?: return null
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
        if (!control.isEnabled || !isAttachedToWindow || visibility != VISIBLE || dock.visibility != VISIBLE ||
            shown == null || liveTargets[side.ordinal] !== shown || liveMember(side) !== shown ||
            currentCatchUpPlan(side, shown).outcome != NativeSeekOutcome.REQUESTED ||
            pair() !== selectedPair || liveMember(side) !== shown) {
            refresh(); return
        }
        val snapshot = runCatching { shown.timingSnapshot() }.getOrNull()
        if (snapshot == null || snapshot.state != Player.STATE_READY || snapshot.playingAd) { refresh(); return }
        onCatchUp(side)
        showControls(); refresh()
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
        panelReturnFocus = null
        qualityFields.clear()
        scroller.visibility = GONE; dock.visibility = GONE
        if (television) requestFocus()
        requestLayout()
    }
    private fun togglePanel(value: String, opener: View? = null) {
        if (panel != value && television) panelReturnFocus = opener ?: activeMenuAnchor ?: moreControl
        dismissMenu(false); cancelRepeats(); panel = if (panel == value) null else value
        content.removeAllViews()
        qualityFields.clear()
        // Reused controls may still belong to a removed row container.
        (listOf(volumeLabel, volume, mixLabel, balance, muteA, muteB, fit, adjustmentLabel) + advanceA + advanceB).forEach {
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
                addView(fit, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
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
        } else if (panel == "Quality") {
            content.addView(text("Feed overrides last for this session. Save stream defaults explicitly. Auto retains audio-first selection and device constraints."))
            NativeMixedSide.entries.forEach { side ->
                val label = if (side == NativeMixedSide.A) labelA else labelB
                content.addView(text(label))
                NativeQualityKind.entries.forEach { kind ->
                    val choose = button("") { showQualityChoices(it, side, kind) }
                    val readback = text("")
                    val capability = text("").apply { textSize = 12f }
                    val save = button("Save stream default") { onSaveQuality(side, kind); refresh() }
                    val reset = button("Reset stream default") { onResetQuality(side, kind); refresh() }
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
        if (television) {
            if (panel == null) {
                val target = panelReturnFocus; panelReturnFocus = null
                restoreRemoteFocus(target)
            } else restoreRemoteFocus((content.getChildAt(0) as? LinearLayout)?.getChildAt(0))
        }
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
        val holdLabel = if (members().count { it != null } == 1) "holds this feed" else "holds both"
        showMenu(anchor) {
            menu.add("Playback status").setOnMenuItemClickListener { togglePanel("Status"); true }
            menu.add("Quality").apply {
                isEnabled = NativeMixedSide.entries.any { qualityState(it, NativeQualityKind.VIDEO) != null }
                setOnMenuItemClickListener { togglePanel("Quality"); true }
            }
            if (!television) menu.add(if (landscape()) "Portrait / stacked layout" else "Landscape / PiP layout")
                .setOnMenuItemClickListener { onLandscape(!landscape()); true }
            menu.add("Swap primary feed").setOnMenuItemClickListener { stage.swap(); true }
            if (television) {
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
            NativeMixedSide.entries.forEach { side ->
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
            menu.add("Stop both").setOnMenuItemClickListener { suspendControls(); onStop(); true }
        }
    }

    private fun showQualityChoices(anchor: View, side: NativeMixedSide, kind: NativeQualityKind) {
        val state = qualityState(side, kind) ?: return
        if (state.saving || playbackBusy()) return
        val control = members().getOrNull(side.ordinal)?.qualitySnapshot()?.controls?.get(kind)
        showMenu(anchor) {
            fun choice(title: String, request: NativeQualityRequest?, checked: Boolean) {
                menu.add(title).apply {
                    isCheckable = true; isChecked = checked
                    setOnMenuItemClickListener { onQualityOverride(side, kind, request); refresh(); true }
                }
            }
            choice("Use stream default (${state.streamDefault.title})", null, state.feedOverride == null)
            choice("Auto for this feed", NativeQualityRequest.auto, state.feedOverride == NativeQualityRequest.auto)
            control?.options.orEmpty().forEach { option -> choice(option.title, option, state.feedOverride == option) }
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
            "Pauses both feeds. Press Play to resume; adjust timing alignment again."
            else "Pauses this feed. Press Play to resume; timing alignment is not restored."
        if (available.any { it != null } && !volumeApplied) applyVolume()
        val wasPlaying = requestedPlaying
        requestedPlaying = available.any { it?.timingSnapshot()?.playWhenReady == true }
        play.text = if (requestedPlaying || playbackBusy()) "Pause" else "Play"
        play.isEnabled = available.any { it != null }
        (advanceA + advanceB).forEach { it.isEnabled = current != null && !current.busy }
        val adjustment = when {
            current == null -> "Relative timing requires two playable feeds."
            !current.requestedAdjustmentValid -> "Timing anchor invalid; realign after recovery."
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
        if (available.all { it == null }) suspendControls()
    }
    // System Back unwinds the visible controls before the Activity stops playback.
    // Popup windows normally consume Back themselves; also handle dispatcher
    // delivery here without invoking any menu action or playback command.
    fun handleBack(): Boolean {
        if (!isAttachedToWindow || visibility != VISIBLE) return false
        if (activeMenu != null) {
            dismissMenu(); showControls()
            return true
        }
        val current = panel ?: return false
        togglePanel(current)
        return true
    }
    fun open() { visibility = VISIBLE; showControls(); refresh(); restoreRemoteFocus() }
    fun endSession() { suspendControls(); stage.unbindPlayers(); observedPair = null; visibility = GONE }
    fun suspendControls() {
        focusGeneration++
        dismissMenu(false); cancelRepeats(); revealPress = null; removeCallbacks(hideControls)
    }
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
        if (liveControls.visibility != GONE) liveControls.measure(childWidth, childHeight)
        val liveHeight = if (liveControls.visibility == GONE) 0 else liveControls.measuredHeight
        scroller.maximumHeight = minOf((contentH * 0.6f).toInt(),
            (contentH - toolbar.measuredHeight - readback.measuredHeight - liveHeight - dock.paddingTop - dock.paddingBottom).coerceAtLeast(0))
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
    private fun landscape() = television || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

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
            setOnClickListener { toggleControls() }
            if (television) isFocusable = false
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
        private fun notice(pane: FrameLayout, side: NativeMixedSide): FeedNotice {
            val notice = text("")
            val recover = button("") { control ->
                if (control.isEnabled && feedMessage(side) != null && feedRecovery(side)?.enabled == true) {
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
            // Keep the source label above the scrollable preparation/error panel.
            pane.addView(scroll, 1, LayoutParams(-1, -1))
            return FeedNotice(scroll, notice, recover)
        }
        fun updateMessages() {
            fun update(side: NativeMixedSide, player: PlayerView, label: String, notice: FeedNotice) {
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
                        if (gestureActive && event.downTime == gestureDownTime && landscape() && sideA != primaryA) {
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
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
            val bounds = twoFeedBounds(measuredWidth, measuredHeight, landscape(), primaryA, ratioA, ratioB, floating,
                floatingBottom, floatingRight, floatingSize)
            fun measure(view: View, rect: ViewerRect) { view.measure(MeasureSpec.makeMeasureSpec(rect.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rect.height, MeasureSpec.EXACTLY)) }
            measure(paneA, bounds.a); measure(paneB, bounds.b)
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val bounds = twoFeedBounds(width, height, landscape(), primaryA, ratioA, ratioB, floating,
                floatingBottom, floatingRight, floatingSize)
            fun place(view: View, rect: ViewerRect) {
                view.measure(MeasureSpec.makeMeasureSpec(rect.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rect.height, MeasureSpec.EXACTLY))
                view.layout(rect.left, rect.top, rect.left + rect.width, rect.top + rect.height)
            }
            place(paneA, bounds.a); place(paneB, bounds.b)
            if (landscape()) (if (primaryA) paneB else paneA).bringToFront()
        }
    }
}
