package net.fstab.tachiai.feature.diagnostic

import android.content.Context
import android.annotation.SuppressLint
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import net.fstab.tachiai.R
import net.fstab.tachiai.platform.media.NativeMixedPair
import net.fstab.tachiai.platform.media.NativeMixedSide

// Presentation only: no source, network, DOM or provider timestamp arithmetic.
// Programmatic-only panel with required callbacks, never XML/tool inflated.
@SuppressLint("ViewConstructor")
internal class NativeMixedPairPanel(
    context: Context,
    private val pair: () -> NativeMixedPair?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onShift: (NativeMixedSide, Long) -> Unit,
    onRelativeShift: ((Long) -> Unit)? = null,
    onLiveRecovery: ((NativeMixedSide) -> Unit)? = null,
) : LinearLayout(context) {
    private val readback = TextView(context)
    private val relativeControls = onRelativeShift != null
    init {
        orientation = VERTICAL
        fun row(vararg controls: Pair<Int, () -> Unit>) {
            addView(LinearLayout(context).apply {
                for ((label, action) in controls) addView(Button(context).apply {
                    setText(label); setOnClickListener { action() }
                }, LayoutParams(0, -2, 1f))
            })
        }
        row(R.string.native_mixed_play to onPlay, R.string.native_mixed_pause to onPause)
        row(R.string.native_mixed_a_earlier to { onShift(NativeMixedSide.A, -5_000) },
            R.string.native_mixed_a_later to { onShift(NativeMixedSide.A, 5_000) },
            R.string.native_mixed_b_earlier to { onShift(NativeMixedSide.B, -5_000) },
            R.string.native_mixed_b_later to { onShift(NativeMixedSide.B, 5_000) })
        row(R.string.native_mixed_a_mute to { pair()?.setVolume(NativeMixedSide.A, 0f) },
            R.string.native_mixed_a_sound to { pair()?.setVolume(NativeMixedSide.A, 0.5f) },
            R.string.native_mixed_b_mute to { pair()?.setVolume(NativeMixedSide.B, 0f) },
            R.string.native_mixed_b_sound to { pair()?.setVolume(NativeMixedSide.B, 0.5f) })
        if (onRelativeShift != null) {
            row(R.string.native_mixed_a_advance_relative to { onRelativeShift(5_000) },
                R.string.native_mixed_b_advance_relative to { onRelativeShift(-5_000) })
            if (onLiveRecovery != null) row(
                R.string.native_mixed_a_catch_up to { onLiveRecovery(NativeMixedSide.A) },
                R.string.native_mixed_b_catch_up to { onLiveRecovery(NativeMixedSide.B) })
            addView(TextView(context).apply { setText(R.string.native_mixed_relative_explanation) })
        }
        addView(readback)
        refresh()
    }

    fun refresh() {
        val current = pair()
        readback.text = if (current?.requestedAdjustmentValid == false)
            context.getString(R.string.native_mixed_invalid_anchor, current.status)
        else context.getString(R.string.native_mixed_status,
            current?.status ?: context.getString(R.string.native_mixed_not_prepared),
            current?.requestedAdjustmentMs ?: 0L)
        val choice = current?.lastRelativePlan
        if (relativeControls && choice?.side != null && choice.movementMs != null)
            readback.append(context.getString(R.string.native_mixed_relative_choice, choice.side.name, choice.movementMs))
    }
}
