package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.presentation.defaultProviderInstances
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// Synthetic chrome only: no controller, account storage or provider requests.
class SettingsHeadingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longPageAndSectionTitlesWrapBetweenBackAndTrailingHelpAtLargeFont() {
        val page = "A long settings page title that wraps"
        val section = "A long settings section heading that also wraps"
        var backs = 0
        compose.setContent { TachiaiPrototypeTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Column(Modifier.width(320.dp)) {
                    SettingsPageHeader(page, { backs++ }, "Back to sources", "Page help fixture")
                    SettingsSection(section, "Section help fixture")
                }
            }
        } }
        val back = compose.onNodeWithContentDescription("Back to sources").assertIsDisplayed()
        val pageHelp = compose.onNodeWithContentDescription("Info about $page").assertIsDisplayed()
        val title = compose.onNodeWithText(page).fetchSemanticsNode().boundsInRoot
        val backBounds = back.fetchSemanticsNode().boundsInRoot
        val helpBounds = pageHelp.fetchSemanticsNode().boundsInRoot
        assertTrue(backBounds.right <= title.left)
        assertTrue(title.right <= helpBounds.left)
        assertEquals(backBounds.center.y, helpBounds.center.y, 1f)
        val minimum = with(compose.density) { 48.dp.toPx() }
        for (bounds in listOf(backBounds, helpBounds)) {
            assertTrue(bounds.width >= minimum); assertTrue(bounds.height >= minimum)
        }
        val sectionTitle = compose.onNodeWithText(section).fetchSemanticsNode().boundsInRoot
        val sectionHelp = compose.onNodeWithContentDescription("Info about $section").fetchSemanticsNode().boundsInRoot
        assertTrue(sectionTitle.right <= sectionHelp.left)
        assertEquals(sectionTitle.center.y, sectionHelp.center.y, 1f)
        back.performClick()
        compose.runOnIdle { assertEquals(1, backs) }
        pageHelp.performClick()
        compose.onNodeWithText("Page help fixture").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("Info about $section").performClick()
        compose.onNodeWithText("Section help fixture").assertIsDisplayed()
    }

    @Test fun providersReproductionKeepsHelpWithTitleAndBackUsesOriginalDestination() {
        var backs = 0
        compose.setContent { TachiaiPrototypeTheme {
            ProviderInstancesScreen(defaultProviderInstances(), emptyList(), false, null, {}, { _, _, _ -> }, { backs++ })
        } }
        val title = compose.onNodeWithText("Providers").fetchSemanticsNode().boundsInRoot
        val info = compose.onNodeWithContentDescription("Info about Providers").fetchSemanticsNode().boundsInRoot
        assertEquals(title.center.y, info.center.y, 1f)
        assertTrue(title.right <= info.left)
        compose.onNodeWithText("Back to streams").assertDoesNotExist()
        compose.onNodeWithText("ABEMA · ABEMA", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to streams").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }
}
