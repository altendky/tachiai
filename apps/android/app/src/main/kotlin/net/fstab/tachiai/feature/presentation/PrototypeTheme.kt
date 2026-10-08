package net.fstab.tachiai.feature.presentation

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import net.fstab.tachiai.R

// Shared resource roles keep Compose and native controls in the same palette.
@Composable
internal fun TachiaiPrototypeTheme(content: @Composable () -> Unit) {
    val base = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    val colors = base.copy(
        primary = colorResource(R.color.prototype_primary),
        onPrimary = colorResource(R.color.prototype_on_primary),
        secondary = colorResource(R.color.prototype_secondary),
        background = colorResource(R.color.prototype_background),
        onBackground = colorResource(R.color.prototype_text),
        surface = colorResource(R.color.prototype_background),
        onSurface = colorResource(R.color.prototype_text),
        surfaceVariant = colorResource(R.color.prototype_surface),
        onSurfaceVariant = colorResource(R.color.prototype_muted),
        outline = colorResource(R.color.prototype_outline),
    )
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}
