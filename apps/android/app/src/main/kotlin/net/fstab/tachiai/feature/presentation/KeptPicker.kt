package net.fstab.tachiai.feature.presentation

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView

// The picker's composition stays in the window while the viewer is in front
// (#790), so Stop does not compose a new picker. The picker stays the content's
// first child, where setContent finds it and updates it in place.

/**
 * Hides the picker before the viewer is added. Anything else is removed, as
 * setContentView did. Returns whether a picker was kept.
 */
internal fun hideKeptPicker(content: ViewGroup): Boolean {
    val picker = content.getChildAt(0) as? ComposeView
    val kept = if (picker != null) 1 else 0
    content.removeViews(kept, content.childCount - kept)
    picker?.visibility = View.GONE
    return picker != null
}

/**
 * Shows the kept picker again without handing it view focus. Outside touch mode
 * a view that becomes visible gets focus at once, and Compose then enters at its
 * first control. A new picker was never given focus that way: focus waits for
 * the next key, or on TV goes to the picker's own entry.
 */
internal fun revealKeptPicker(content: ViewGroup) {
    val picker = content.getChildAt(0) as? ComposeView ?: return
    if (picker.visibility == View.VISIBLE) return
    val focusability = picker.descendantFocusability
    picker.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    try { picker.visibility = View.VISIBLE } finally { picker.descendantFocusability = focusability }
}
