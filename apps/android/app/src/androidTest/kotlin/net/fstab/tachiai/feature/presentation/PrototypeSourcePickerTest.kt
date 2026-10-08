package net.fstab.tachiai.feature.presentation

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import net.fstab.tachiai.presentation.PrototypeSelection
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.PrototypeSource
import net.fstab.tachiai.presentation.ProviderSetup
import net.fstab.tachiai.presentation.SourceRouteChoice
import net.fstab.tachiai.presentation.SourceRouteMode
import net.fstab.tachiai.presentation.SourceSetup
import net.fstab.tachiai.presentation.defaultSourceSetups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrototypeSourcePickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun duplicateChoiceReachesOpenViewerWithoutFiltering() {
        var opened: PrototypeSelection? = null
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(PrototypeSelection(), null) { opened = it } } }
        compose.waitForIdle()
        PrototypeSource.entries.forEach { compose.onAllNodesWithText(it.optionTitle).assertCountEquals(1) }
        assignment(PrototypeSource.ABEMA_LIVE, "B").performScrollTo().performClick()
        assignment(PrototypeSource.TWITCH_LIVE, "B").assertIsOff()
        compose.onNodeWithText("Open viewer").performClick()
        compose.runOnIdle {
            assertEquals(PrototypeSelection(PrototypeSource.ABEMA_LIVE, PrototypeSource.ABEMA_LIVE), opened)
        }
    }

    @Test fun reassignmentAndClearingPreserveTheOtherSlot() {
        var opened: PrototypeSelection? = null
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(PrototypeSelection(), null) { opened = it } } }
        compose.waitForIdle()
        assignment(PrototypeSource.ABEMA_REPLAY, "A").performScrollTo().performClick()
        assignment(PrototypeSource.ABEMA_LIVE, "A").assertIsOff()
        assignment(PrototypeSource.TWITCH_LIVE, "B").assertIsOn()
        assignment(PrototypeSource.ABEMA_REPLAY, "A").performClick()
        compose.onNodeWithText("Open viewer").assertIsNotEnabled()
        assignment(PrototypeSource.TWITCH_REPLAY, "A").performScrollTo().performClick()
        compose.onNodeWithText("Open viewer").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(PrototypeSelection(PrototypeSource.TWITCH_REPLAY, PrototypeSource.TWITCH_LIVE), opened)
        }
    }

    private fun assignment(source: PrototypeSource, slot: String) =
        compose.onNodeWithContentDescription("Assign ${source.title} to feed $slot")

    @Test fun providerTreeKeepsOptionsBelowTheirHeadings() {
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(PrototypeSelection(), null) {} } }
        compose.waitForIdle()
        for (service in PrototypeService.entries) {
            val heading = compose.onNodeWithText(service.title).fetchSemanticsNode()
            PrototypeSource.entries.filter { it.service == service }.forEach { source ->
                assertTrue(heading.positionInRoot.y < compose.onNodeWithText(source.optionTitle).fetchSemanticsNode().positionInRoot.y)
            }
        }
        assertTrue(compose.onNodeWithText(PrototypeSource.ABEMA_REPLAY.optionTitle).fetchSemanticsNode().positionInRoot.y <
            compose.onNodeWithText(PrototypeService.TWITCH.title).fetchSemanticsNode().positionInRoot.y)
    }

    @Test fun providerIndicatorsFollowEachColumnWithoutOfferingAssignmentActions() {
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(PrototypeSelection(), null) {} } }
        compose.waitForIdle()
        fun indicator(service: PrototypeService, slot: String, selected: Boolean) {
            val description = if (selected) "${service.title}: a stream is selected for feed $slot"
                else "${service.title}: no stream selected for feed $slot"
            val node = compose.onNodeWithContentDescription(description).fetchSemanticsNode()
            assertFalse(node.config.contains(SemanticsActions.OnClick))
        }
        indicator(PrototypeService.ABEMA, "A", true)
        indicator(PrototypeService.TWITCH, "A", false)
        indicator(PrototypeService.ABEMA, "B", false)
        indicator(PrototypeService.TWITCH, "B", true)
        compose.onAllNodesWithText("−").assertCountEquals(0)
        assignment(PrototypeSource.TWITCH_CHILLHOP_LIVE, "A").performScrollTo().performClick()
        indicator(PrototypeService.ABEMA, "A", false)
        indicator(PrototypeService.TWITCH, "A", true)
        indicator(PrototypeService.TWITCH, "B", true)
        assignment(PrototypeSource.TWITCH_LIVE, "B").performScrollTo().performClick()
        indicator(PrototypeService.TWITCH, "B", false)
        indicator(PrototypeService.TWITCH, "A", true)
        compose.onNodeWithText("Open viewer").assertIsNotEnabled()
    }

    @Test fun additionalLiveChannelsReachTheirAssignedViewerSlots() {
        var opened: PrototypeSelection? = null
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(PrototypeSelection(), null) { opened = it } } }
        compose.waitForIdle()
        assignment(PrototypeSource.TWITCH_CHILLHOP_LIVE, "A").performScrollTo().performClick()
        assignment(PrototypeSource.TWITCH_VIRTUAL_JAPAN_LIVE, "B").performScrollTo().performClick()
        compose.onNodeWithText("Open viewer").performClick()
        compose.runOnIdle {
            assertEquals(PrototypeSelection(PrototypeSource.TWITCH_CHILLHOP_LIVE, PrototypeSource.TWITCH_VIRTUAL_JAPAN_LIVE), opened)
        }
    }

    @Test fun rapidSlotChangesBeforeRecompositionDoNotOverwriteEachOther() {
        var opened: PrototypeSelection? = null
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(PrototypeSelection(), null) { opened = it } } }
        compose.waitForIdle()
        val source = PrototypeSource.ABEMA_REPLAY
        val assignA = assignment(source, "A").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val assignB = assignment(source, "B").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { assignA(); assignB() }
        compose.onNodeWithText("Open viewer").performClick()
        compose.runOnIdle { assertEquals(PrototypeSelection(source, source), opened) }
    }

    @Test fun routesAndProvidersHaveSeparateEntrypointsWithoutPerStreamSetupButtons() {
        var routes = false
        var providers = false
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(PrototypeSelection(), null, onConnections = { routes = true },
                onProviders = { providers = true }, onWatch = {})
        } }
        compose.waitForIdle()
        PrototypeSource.entries.forEach { compose.onAllNodesWithText(it.optionTitle).assertCountEquals(1) }
        compose.onNodeWithText("Stream").assertExists()
        compose.onNodeWithText("Routes").performClick()
        compose.onNodeWithText("Providers").performClick()
        compose.onNodeWithText("Source setup").assertDoesNotExist()
        compose.onNodeWithText("Reset stream settings").assertDoesNotExist()
        compose.runOnIdle { assertTrue(routes); assertTrue(providers) }
    }

    @Test fun groupedCustomNamesRemainExactWhileAssignmentsUseCanonicalIdentities() {
        var opened: PrototypeSelection? = null
        val source = PrototypeSource.ABEMA_REPLAY
        val customName = "ABEMA · My replay"
        val setups = defaultSourceSetups() + (source to SourceSetup(customName))
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(PrototypeSelection(), null, sourceSetups = setups, onWatch = { opened = it })
        } }
        compose.waitForIdle()
        compose.onNodeWithText(customName).assertExists()
        compose.onNodeWithText("My replay").assertDoesNotExist()
        compose.onNodeWithText(source.optionTitle).assertDoesNotExist()
        val heading = compose.onNodeWithText(PrototypeService.ABEMA.title).fetchSemanticsNode()
        assertTrue(heading.positionInRoot.y < compose.onNodeWithText(customName).fetchSemanticsNode().positionInRoot.y)
        assignment(source, "A").performScrollTo().performClick()
        compose.onNodeWithText("Open viewer").performClick()
        compose.runOnIdle { assertEquals(PrototypeSelection(source, PrototypeSource.TWITCH_LIVE), opened) }
    }

    @Test fun unreadSetupKeepsRoutesAvailableButDisablesProvidersAndPlayback() {
        var routes = false
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(PrototypeSelection(), "Setup could not be read.",
                onConnections = { routes = true }, setupReady = false, onProviders = {}, onWatch = {})
        } }
        compose.waitForIdle()
        compose.onNodeWithText("Routes").assertIsEnabled().performClick()
        compose.onNodeWithText("Providers").assertIsNotEnabled()
        compose.onNodeWithText("Open viewer").assertIsNotEnabled()
        compose.onNodeWithText("Setup could not be read.").assertExists()
        compose.onNodeWithText("Provider setup must be read successfully before playback.").assertExists()
        compose.onNodeWithText("A · ABEMA: System network").assertDoesNotExist()
        compose.onNodeWithText("B · Twitch: System network").assertDoesNotExist()
        compose.onNodeWithText("Reset stream settings").assertDoesNotExist()
        compose.runOnIdle { assertTrue(routes) }
    }

    @Test fun providerSummariesDistinguishSavedRoutesFromRequiredReview() {
        val saved = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION,
            "00000000-0000-0000-0000-000000000001", "Japan route")
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(PrototypeSelection(), null,
                providerSetups = mapOf(PrototypeService.ABEMA to ProviderSetup(saved),
                    PrototypeService.TWITCH to ProviderSetup(null)), onProviders = {}, onWatch = {})
        } }
        compose.waitForIdle()
        compose.onNodeWithText("A · ABEMA: Japan route").assertExists()
        compose.onNodeWithText("B · Twitch: Route choice required").assertExists()
        // Open viewer is an explicit request; the Activity owns route admission.
        compose.onNodeWithText("Open viewer").assertIsEnabled()
    }

    @Test fun obsoleteSettingsOfferOnlyAnExplicitResetWithoutOpeningPlayback() {
        var resets = 0
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(PrototypeSelection(), "Stream settings are obsolete.", setupReady = false,
                onProviders = {}, obsoleteSetup = true, onResetStreamSettings = { resets++ }, onWatch = {})
        } }
        compose.waitForIdle()
        compose.onNodeWithText("Providers").assertIsNotEnabled()
        compose.onNodeWithText("Open viewer").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, resets) }
        compose.onNodeWithText("Reset stream settings").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, resets) }
    }
}
