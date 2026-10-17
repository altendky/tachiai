package net.fstab.tachiai.feature.presentation

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button
import net.fstab.tachiai.R

internal fun Context.prototypeSurface(color: Int, radius: Int = 12) = GradientDrawable().apply {
    setColor(getColor(color))
    cornerRadius = radius * resources.displayMetrics.density
}

internal fun Button.stylePrototypeControl() {
    val enabledSelected = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_selected)
    val disabled = intArrayOf(-android.R.attr.state_enabled)
    val normal = intArrayOf()
    val states = arrayOf(disabled, enabledSelected, normal)
    setTextColor(ColorStateList(states, intArrayOf(context.getColor(R.color.prototype_muted),
        context.getColor(R.color.prototype_on_primary), context.getColor(R.color.prototype_text))))
    val surfaces = StateListDrawable().apply {
        addState(enabledSelected, context.prototypeSurface(R.color.prototype_primary, 8))
        addState(normal, context.prototypeSurface(R.color.prototype_surface, 8).apply {
            setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), context.getColor(R.color.prototype_outline))
        })
    }
    backgroundTintList = null
    background = RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.prototype_primary) and 0x00ffffff or 0x33000000),
        surfaces, context.prototypeSurface(R.color.prototype_surface, 8))
    isAllCaps = false
}
