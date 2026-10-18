package net.fstab.tachiai.feature.presentation

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import net.fstab.tachiai.presentation.ConfiguredFeedAssignments
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.PrototypeSource
import net.fstab.tachiai.presentation.defaultProviderInstances
import net.fstab.tachiai.presentation.defaultSourceSetups
import net.fstab.tachiai.presentation.legacyConfiguredSources
import net.fstab.tachiai.presentation.prototypeCatalogResource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

// Run the phone presentation explicitly even when the test host is a TV.
class PhonePickerPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneKeepsCheckboxAssignmentsAndDuplicateFeedSelection() {
        val provider = defaultProviderInstances().single { it.service == PrototypeService.TWITCH }
        val source = legacyConfiguredSources(provider, defaultSourceSetups(), emptyMap()).single {
            it.entry.resource == prototypeCatalogResource(PrototypeSource.TWITCH_LIVE)
        }
        var watched: ConfiguredFeedAssignments? = null
        compose.setContent { TachiaiPrototypeTheme(television = false) {
            PrototypeSourcePicker(null, providerInstances = listOf(provider), configuredSources = listOf(source),
                initialAssignments = ConfiguredFeedAssignments(null, null), onWatch = { watched = it })
        } }
        compose.onNodeWithText("Feed A").assertDoesNotExist()
        for (slot in listOf("A", "B")) {
            val checkbox = compose.onNodeWithContentDescription("Assign ${source.entry.title} to feed $slot")
            checkbox.assertIsOff().performClick()
            checkbox.assertIsOn()
        }
        compose.onNodeWithText("Watch").performClick()
        compose.runOnIdle { assertEquals(ConfiguredFeedAssignments(source.choice, source.choice), watched) }
    }
}
