package net.fstab.tachiai.feature.presentation

import android.content.res.Configuration
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.unit.dp

internal fun isTelevision(configuration: Configuration): Boolean =
    configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

internal val LocalTelevision = staticCompositionLocalOf { false }

// Observe the existing control's focus target; an extra focusable wrapper would
// create a second, non-actionable stop in remote navigation.
internal fun Modifier.tvFocusIndicator(): Modifier = composed {
    if (!LocalTelevision.current) return@composed this
    var focused by remember { mutableStateOf(false) }
    // A selected assignment already uses primary as its fill. The contrasting
    // text color keeps focused and selected states visibly distinct.
    val color = MaterialTheme.colorScheme.onSurface
    onFocusChanged { focused = it.isFocused }.drawWithContent {
            drawContent()
            if (focused) {
                val stroke = 3.dp.toPx()
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size((size.width - stroke).coerceAtLeast(0f),
                        (size.height - stroke).coerceAtLeast(0f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()), style = Stroke(stroke))
            }
        }
}

// Entry focus belongs to a screen host, never a lazily remounted heading. The
// restorer retains the last surviving child. Re-enter only after readiness
// changes, so opening a popup or keyboard does not trigger a focus-stealing loop.
internal fun Modifier.tvFocusGroup(ready: Boolean = true, contentKey: Any? = Unit,
    preferredEntry: FocusRequester? = null): Modifier = composed {
    if (!LocalTelevision.current) return@composed this
    val requester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(ready, contentKey) {
        withFrameNanos { }
        if (!hasFocus) {
            if (preferredEntry?.requestFocus() != true) requester.requestFocus()
        }
    }
    focusRequester(requester).onFocusChanged { hasFocus = it.hasFocus }.focusRestorer().focusGroup()
}

@Composable
internal fun RemoteButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, content: @Composable RowScope.() -> Unit) {
    androidx.compose.material3.Button(onClick, modifier.tvFocusIndicator(), enabled = enabled,
        contentPadding = contentPadding, content = content)
}

@Composable
internal fun RemoteTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding, content: @Composable RowScope.() -> Unit) {
    androidx.compose.material3.TextButton(onClick, modifier.tvFocusIndicator(), enabled = enabled,
        contentPadding = contentPadding, content = content)
}

@Composable
internal fun RemoteOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, shape: Shape = ButtonDefaults.outlinedShape,
    content: @Composable RowScope.() -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick, modifier.tvFocusIndicator(), enabled = enabled,
        contentPadding = contentPadding, shape = shape, content = content)
}

@Composable
internal fun RemoteIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    content: @Composable () -> Unit) {
    androidx.compose.material3.IconButton(onClick, modifier.tvFocusIndicator(), enabled = enabled, colors = colors, content = content)
}

@Composable
internal fun RemoteDropdownMenuItem(text: @Composable () -> Unit, onClick: () -> Unit,
    modifier: Modifier = Modifier, leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null, enabled: Boolean = true) {
    androidx.compose.material3.DropdownMenuItem(text, onClick, modifier.tvFocusIndicator(),
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, enabled = enabled)
}

@Composable
internal fun RemoteTab(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, text: (@Composable () -> Unit)? = null, icon: (@Composable () -> Unit)? = null) {
    androidx.compose.material3.Tab(selected, onClick, modifier.tvFocusIndicator(), enabled = enabled, text = text, icon = icon)
}

@Composable
internal fun RemoteRadioButton(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true) {
    androidx.compose.material3.RadioButton(selected, onClick, modifier.tvFocusIndicator(), enabled = enabled)
}
