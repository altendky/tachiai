package net.fstab.tachiai.feature.presentation

import android.content.res.Configuration
import android.graphics.Color
import android.widget.Button
import androidx.core.graphics.ColorUtils
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.fstab.tachiai.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrototypeThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun composeUsesCurrentSystemResourceRoles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var primary = 0
        var surface = 0
        compose.setContent { TachiaiPrototypeTheme {
            primary = MaterialTheme.colorScheme.primary.toArgb()
            surface = MaterialTheme.colorScheme.surface.toArgb()
        } }
        compose.runOnIdle {
            assertEquals(context.getColor(R.color.prototype_primary), primary)
            assertEquals(context.getColor(R.color.prototype_background), surface)
        }
    }

    @Test fun dayAndNightRolesHaveLegibleTextIncludingTranslucentVideoPanels() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        var dayBackground = 0
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or night
            })
            fun color(id: Int) = context.getColor(id)
            val background = color(R.color.prototype_background)
            if (night == Configuration.UI_MODE_NIGHT_NO) dayBackground = background else assertNotEquals(dayBackground, background)
            for (surface in listOf(background, color(R.color.prototype_surface))) {
                assertTrue(ColorUtils.calculateContrast(color(R.color.prototype_text), surface) >= 4.5)
                assertTrue(ColorUtils.calculateContrast(color(R.color.prototype_muted), surface) >= 4.5)
            }
            for (video in listOf(Color.BLACK, Color.WHITE)) {
                val panel = ColorUtils.compositeColors(color(R.color.prototype_panel), video)
                assertTrue(ColorUtils.calculateContrast(color(R.color.prototype_text), panel) >= 4.5)
            }
            assertTrue(ColorUtils.calculateContrast(color(R.color.prototype_on_primary), color(R.color.prototype_primary)) >= 4.5)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val button = Button(context).apply { stylePrototypeControl() }
                assertEquals(color(R.color.prototype_text), button.currentTextColor)
                button.isSelected = true
                assertEquals(color(R.color.prototype_on_primary), button.currentTextColor)
                button.isEnabled = false
                assertEquals(color(R.color.prototype_muted), button.currentTextColor)
                assertNotNull(button.background)
            }
        }
    }
}
