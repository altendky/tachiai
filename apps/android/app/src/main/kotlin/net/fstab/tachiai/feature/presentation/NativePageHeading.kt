package net.fstab.tachiai.feature.presentation

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import net.fstab.tachiai.R

internal fun nativeBackButton(context: Context, description: String, onBack: () -> Unit): ImageButton = ImageButton(context).apply {
    val size = (48 * resources.displayMetrics.density).toInt()
    minimumWidth = size; minimumHeight = size
    setImageResource(R.drawable.navigation_back)
    contentDescription = description
    val color = TypedValue()
    if (context.theme.resolveAttribute(android.R.attr.textColorPrimary, color, true)) {
        val colors = if (color.resourceId != 0) context.getColorStateList(color.resourceId) else ColorStateList.valueOf(color.data)
        imageTintList = colors
    }
    val background = TypedValue()
    if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true))
        setBackgroundResource(background.resourceId)
    setOnClickListener { onBack() }
}

internal fun nativePageHeading(context: Context, title: String, description: String, onBack: () -> Unit): LinearLayout =
    LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        val size = (48 * resources.displayMetrics.density).toInt()
        addView(nativeBackButton(context, description, onBack), LinearLayout.LayoutParams(size, size))
        addView(TextView(context).apply { text = title; textSize = 20f; ViewCompat.setAccessibilityHeading(this, true) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
