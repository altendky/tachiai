package net.fstab.tachiai.feature.presentation

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import net.fstab.tachiai.presentation.ConfiguredFeedAssignments
import net.fstab.tachiai.presentation.ConfiguredSource
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.PrototypeSource
import net.fstab.tachiai.presentation.ProviderSetup
import net.fstab.tachiai.presentation.SourceRouteChoice
import net.fstab.tachiai.presentation.SourceRouteMode
import net.fstab.tachiai.presentation.SourceSetup
import net.fstab.tachiai.presentation.defaultSourceSetups
import net.fstab.tachiai.presentation.defaultProviderInstances
import net.fstab.tachiai.presentation.legacyConfiguredSources
import net.fstab.tachiai.provider.catalog.CatalogAvailability
import net.fstab.tachiai.provider.catalog.CatalogIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrototypeSourcePickerTest {
    @get:Rule val compose = createComposeRule()
    private val providers = defaultProviderInstances()
    private val configured = providers.flatMap { legacyConfiguredSources(it, defaultSourceSetups(), emptyMap()) }
    private fun item(source: PrototypeSource): ConfiguredSource = configured.single { it.entry.resource ==
        net.fstab.tachiai.presentation.prototypeCatalogResource(source) }
    private fun expected(a: PrototypeSource, b: PrototypeSource) = ConfiguredFeedAssignments(item(a).choice, item(b).choice)

    @Test fun savedOfflineBroadcasterCanRequestFreshAssessmentWithoutChangingItsIdentity() {
        val twitch = providers.single { it.service == PrototypeService.TWITCH }
        val channel = ConfiguredSource("12345678-1234-1234-1234-123456789abc", twitch.id,
            net.fstab.tachiai.provider.catalog.CatalogEntry(net.fstab.tachiai.provider.catalog.CatalogResource(
                net.fstab.tachiai.presentation.ProviderId("twitch"), "broadcaster", "789", CatalogIntent.CHANNEL),
                "Saved channel", availability = CatalogAvailability.OFFLINE))
        var opened: ConfiguredFeedAssignments? = null
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(null, providerInstances = listOf(twitch), configuredSources = listOf(channel),
                initialAssignments = ConfiguredFeedAssignments(channel.choice, channel.choice), onWatch = { opened = it })
        } }
        compose.onNodeWithText("Saved channel").assertExists()
        compose.onNodeWithText("Watch").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(ConfiguredFeedAssignments(channel.choice, channel.choice), opened) }
    }

    @Test fun configuredExactVideosEnableWatchAndKeepItemAndOwnerForBothSlots() {
        val twitch = providers.single { it.service == PrototypeService.TWITCH }
        val first = ConfiguredSource("12345678-1234-1234-1234-123456789abc", twitch.id,
            net.fstab.tachiai.provider.catalog.CatalogEntry(net.fstab.tachiai.provider.catalog.CatalogResource(
                net.fstab.tachiai.presentation.ProviderId("twitch"), "video", "789", CatalogIntent.VIDEO), "Saved replay"))
        val second = first.copy(id = "12345678-1234-1234-1234-123456789abd", entry = first.entry.copy(
            resource = first.entry.resource.copy(identity = "98765432101234567890"), title = "Other replay"))
        var opened: ConfiguredFeedAssignments? = null
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(null, providerInstances = listOf(twitch), configuredSources = listOf(first, second),
                initialAssignments = ConfiguredFeedAssignments(first.choice, second.choice), onWatch = { opened = it })
        } }
        compose.onNodeWithText("Watch").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(ConfiguredFeedAssignments(first.choice, second.choice), opened) }
        compose.onNodeWithContentDescription("Assign Saved replay to feed B").performScrollTo().performClick()
        compose.onNodeWithText("Watch").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(ConfiguredFeedAssignments(first.choice, first.choice), opened) }
    }

    @Test fun configuredVideoBeyondNativeBoundStaysVisibleAndWatchExplainsUnsupported() {
        val twitch = providers.single { it.service == PrototypeService.TWITCH }
        val long = ConfiguredSource("12345678-1234-1234-1234-123456789abc", twitch.id,
            net.fstab.tachiai.provider.catalog.CatalogEntry(net.fstab.tachiai.provider.catalog.CatalogResource(
                net.fstab.tachiai.presentation.ProviderId("twitch"), "video", "1".repeat(21), CatalogIntent.VIDEO), "Long video"))
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(null, providerInstances = listOf(twitch), configuredSources = listOf(long),
                initialAssignments = ConfiguredFeedAssignments(long.choice, long.choice), onWatch = { error("Unsupported choice opened") })
        } }
        compose.onNodeWithText("Long video").assertExists()
        compose.onNodeWithText("Watch").assertIsNotEnabled()
        compose.onNodeWithText("Playback for a selected item is not supported yet. It stays configured.").assertExists()
    }

    @Test fun cleanupBlockKeepsSetupAndRecoveryAvailable() {
        var routes = false
        var providers = false
        var recovery = false
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(null, onConnections = { routes = true },
                onProviders = { providers = true }, playbackAvailable = false,
                recoveryMessage = "Playback cleanup could not be confirmed.", onRecovery = { recovery = true }, onWatch = {})
        } }
        compose.onNodeWithText("Watch").assertIsNotEnabled()
        compose.onNodeWithText("Playback cleanup could not be confirmed.").assertExists()
        compose.onNodeWithText("Recovery options").performClick()
        compose.onNodeWithText("Routes").assertIsEnabled().performClick()
        compose.onNodeWithText("Providers").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(routes); assertTrue(providers); assertTrue(recovery) }
    }

    @Test fun compactRecoveryKeepsAssignmentsBlockedViewerAndRecoveryReachable() {
        var recovery = false
        var assignments: ConfiguredFeedAssignments? = null
        compose.setContent { TachiaiPrototypeTheme {
            Box(Modifier.height(240.dp)) {
                PrototypeSourcePicker(null, playbackAvailable = false,
                    recoveryMessage = "Playback blocked. Recovery category: PLAYBACK_CLEANUP_UNCONFIRMED.",
                    onAssignmentsChanged = { assignments = it }, onRecovery = { recovery = true }, onWatch = {})
            }
        } }
        assignment(PrototypeSource.ABEMA_REPLAY, "A").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(item(PrototypeSource.ABEMA_REPLAY).choice, assignments?.a) }
        compose.onNodeWithText("Watch").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Recovery options").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(recovery) }
    }

    @Test fun duplicateChoiceReachesOpenViewerWithoutFiltering() {
        var opened: ConfiguredFeedAssignments? = null
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(null) { opened = it } } }
        compose.waitForIdle()
        PrototypeSource.entries.forEach { compose.onAllNodesWithText(it.optionTitle).assertCountEquals(1) }
        assignment(PrototypeSource.ABEMA_LIVE, "B").performScrollTo().performClick()
        assignment(PrototypeSource.TWITCH_LIVE, "B").assertIsOff()
        compose.onNodeWithText("Watch").performClick()
        compose.runOnIdle {
            assertEquals(expected(PrototypeSource.ABEMA_LIVE, PrototypeSource.ABEMA_LIVE), opened)
        }
    }

    @Test fun reassignmentAndClearingPreserveTheOtherSlot() {
        var opened: ConfiguredFeedAssignments? = null
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(null) { opened = it } } }
        compose.waitForIdle()
        assignment(PrototypeSource.ABEMA_REPLAY, "A").performScrollTo().performClick()
        assignment(PrototypeSource.ABEMA_LIVE, "A").assertIsOff()
        assignment(PrototypeSource.TWITCH_LIVE, "B").assertIsOn()
        assignment(PrototypeSource.ABEMA_REPLAY, "A").performClick()
        compose.onNodeWithText("Watch").assertIsNotEnabled()
        assignment(PrototypeSource.TWITCH_REPLAY, "A").performScrollTo().performClick()
        compose.onNodeWithText("Watch").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(expected(PrototypeSource.TWITCH_REPLAY, PrototypeSource.TWITCH_LIVE), opened)
        }
    }

    private fun assignment(source: PrototypeSource, slot: String) =
        compose.onNodeWithContentDescription("Assign ${source.title} to feed $slot")

    @Test fun providerTreeKeepsOptionsBelowTheirHeadings() {
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(null) {} } }
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
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(null) {} } }
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
        compose.onNodeWithText("Watch").assertIsNotEnabled()
    }

    @Test fun additionalLiveChannelsReachTheirAssignedViewerSlots() {
        var opened: ConfiguredFeedAssignments? = null
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(null) { opened = it } } }
        compose.waitForIdle()
        assignment(PrototypeSource.TWITCH_CHILLHOP_LIVE, "A").performScrollTo().performClick()
        assignment(PrototypeSource.TWITCH_VIRTUAL_JAPAN_LIVE, "B").performScrollTo().performClick()
        compose.onNodeWithText("Watch").performClick()
        compose.runOnIdle {
            assertEquals(expected(PrototypeSource.TWITCH_CHILLHOP_LIVE, PrototypeSource.TWITCH_VIRTUAL_JAPAN_LIVE), opened)
        }
    }

    @Test fun rapidSlotChangesBeforeRecompositionDoNotOverwriteEachOther() {
        var opened: ConfiguredFeedAssignments? = null
        compose.setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(null) { opened = it } } }
        compose.waitForIdle()
        val source = PrototypeSource.ABEMA_REPLAY
        val assignA = assignment(source, "A").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val assignB = assignment(source, "B").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { assignA(); assignB() }
        compose.onNodeWithText("Watch").performClick()
        compose.runOnIdle { assertEquals(expected(source, source), opened) }
    }

    @Test fun routesAndProvidersHaveSeparateEntrypointsWithoutPerStreamSetupButtons() {
        var routes = false
        var providers = false
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(null, onConnections = { routes = true },
                onProviders = { providers = true }, onWatch = {})
        } }
        compose.waitForIdle()
        PrototypeSource.entries.forEach { compose.onAllNodesWithText(it.optionTitle).assertCountEquals(1) }
        compose.onNodeWithText("Stream").assertDoesNotExist()
        compose.onNodeWithText("Routes").performClick()
        compose.onNodeWithText("Providers").performClick()
        compose.onNodeWithText("Source setup").assertDoesNotExist()
        compose.onNodeWithText("Reset stream settings").assertDoesNotExist()
        compose.runOnIdle { assertTrue(routes); assertTrue(providers) }
    }

    @Test fun groupedCustomNamesRemainExactWhileAssignmentsUseCanonicalIdentities() {
        var opened: ConfiguredFeedAssignments? = null
        val source = PrototypeSource.ABEMA_REPLAY
        val customName = "ABEMA · My replay"
        val setups = defaultSourceSetups() + (source to SourceSetup(customName))
        val renamed = providers.flatMap { legacyConfiguredSources(it, setups, emptyMap()) }
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(null, configuredSources = renamed, onWatch = { opened = it })
        } }
        compose.waitForIdle()
        compose.onNodeWithText(customName).assertExists()
        compose.onNodeWithText("My replay").assertDoesNotExist()
        compose.onNodeWithText(source.optionTitle).assertDoesNotExist()
        val heading = compose.onNodeWithText(PrototypeService.ABEMA.title).fetchSemanticsNode()
        assertTrue(heading.positionInRoot.y < compose.onNodeWithText(customName).fetchSemanticsNode().positionInRoot.y)
        compose.onNodeWithContentDescription("Assign $customName to feed A").performScrollTo().performClick()
        compose.onNodeWithText("Watch").performClick()
        compose.runOnIdle { assertEquals(expected(source, PrototypeSource.TWITCH_LIVE), opened) }
    }

    @Test fun unreadSetupKeepsRoutesAvailableButDisablesProvidersAndPlayback() {
        var routes = false
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker("Setup could not be read.",
                onConnections = { routes = true }, setupReady = false, onProviders = {}, onWatch = {})
        } }
        compose.waitForIdle()
        compose.onNodeWithText("Routes").assertIsEnabled().performClick()
        compose.onNodeWithText("Providers").assertIsNotEnabled()
        compose.onNodeWithText("Watch").assertIsNotEnabled()
        compose.onNodeWithText("Setup could not be read.").assertExists()
        compose.onNodeWithText("Provider setup must be read successfully before playback.").assertExists()
        compose.onAllNodesWithText("System network").assertCountEquals(0)
        compose.onNodeWithText("Reset stream settings").assertDoesNotExist()
        compose.runOnIdle { assertTrue(routes) }
    }

    @Test fun providerSummariesDistinguishSavedRoutesFromRequiredReview() {
        val saved = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION,
            "00000000-0000-0000-0000-000000000001", "Japan route")
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(null,
                providerInstances = defaultProviderInstances(mapOf(PrototypeService.ABEMA to ProviderSetup(saved),
                    PrototypeService.TWITCH to ProviderSetup(null))), onProviders = {}, onWatch = {})
        } }
        compose.waitForIdle()
        compose.onNodeWithText("Japan route").assertExists()
        compose.onNodeWithText("Route choice required").assertExists()
        // Configured selection validates route review before offering playback.
        compose.onNodeWithText("Watch").assertIsNotEnabled()
    }

    @Test fun obsoleteSettingsOfferOnlyAnExplicitResetWithoutOpeningPlayback() {
        var resets = 0
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker("Stream settings are obsolete.", setupReady = false,
                onProviders = {}, obsoleteSetup = true, onResetStreamSettings = { resets++ }, onWatch = {})
        } }
        compose.waitForIdle()
        compose.onNodeWithText("Providers").assertIsNotEnabled()
        compose.onNodeWithText("Watch").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, resets) }
        compose.onNodeWithText("Reset stream settings").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, resets) }
    }

    @Test fun explicitEmptyConfiguredListDoesNotResurrectPrototypeChoices() {
        var providersOpened = false
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(null, configuredSources = emptyList(),
                onProviders = { providersOpened = true }, onWatch = {})
        } }
        PrototypeSource.entries.forEach { compose.onAllNodesWithText(it.optionTitle).assertCountEquals(0) }
        compose.onNodeWithText("Watch").assertIsNotEnabled()
        compose.onNodeWithText("Providers").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(providersOpened) }
    }

    @Test fun removedSavedItemKeepsTheOtherFeedAndRequiresExplicitReassignment() {
        val removed = item(PrototypeSource.ABEMA_LIVE)
        var opened: ConfiguredFeedAssignments? = null
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(null, configuredSources = configured.filterNot { it.id == removed.id },
                initialAssignments = expected(PrototypeSource.ABEMA_LIVE, PrototypeSource.TWITCH_LIVE),
                onWatch = { opened = it })
        } }
        compose.onNodeWithText(PrototypeSource.ABEMA_LIVE.optionTitle).assertDoesNotExist()
        assignment(PrototypeSource.TWITCH_LIVE, "B").assertIsOn()
        assignment(PrototypeSource.ABEMA_REPLAY, "A").assertIsOff()
        compose.onNodeWithText("Watch").assertIsNotEnabled()
        assignment(PrototypeSource.ABEMA_REPLAY, "A").performScrollTo().performClick()
        compose.onNodeWithText("Watch").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(expected(PrototypeSource.ABEMA_REPLAY, PrototypeSource.TWITCH_LIVE), opened) }
    }

    @Test fun configuredCollectionIsVisibleAndSelectedWithoutBecomingPlayableChild() {
        val original = item(PrototypeSource.ABEMA_REPLAY)
        val collection = original.copy(entry = original.entry.copy(title = "Saved sumo series",
            resource = original.entry.resource.copy(kind = "series", identity = "394-72", intent = CatalogIntent.COLLECTION)))
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(null, configuredSources = configured.map { if (it.id == original.id) collection else it },
                initialAssignments = ConfiguredFeedAssignments(collection.choice, item(PrototypeSource.TWITCH_LIVE).choice), onWatch = {})
        } }
        compose.onNodeWithText("Saved sumo series").assertExists()
        compose.onNodeWithContentDescription("Assign Saved sumo series to feed A").assertIsOn()
        assignment(PrototypeSource.TWITCH_LIVE, "B").assertIsOn()
        compose.onNodeWithText("Watch").assertIsNotEnabled()
    }

    @Test fun availabilityUpdatesRetainSelectionAndBlockOnlyKnownUnavailableStates() {
        val original = item(PrototypeSource.ABEMA_LIVE)
        val availability = mutableStateOf(CatalogAvailability.UNKNOWN)
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(null, configuredSources = configured.map {
                if (it.id == original.id) it.copy(entry = it.entry.copy(availability = availability.value)) else it
            }, onWatch = {})
        } }
        compose.onNodeWithText("Watch").assertIsEnabled()
        for (state in listOf(CatalogAvailability.UPCOMING, CatalogAvailability.OFFLINE,
                CatalogAvailability.EXPIRED, CatalogAvailability.UNAVAILABLE)) {
            compose.runOnIdle { availability.value = state }
            assignment(PrototypeSource.ABEMA_LIVE, "A").assertIsOn()
            assignment(PrototypeSource.TWITCH_LIVE, "B").assertIsOn()
            compose.onNodeWithText("Watch").assertIsNotEnabled()
        }
        for (state in listOf(CatalogAvailability.LIVE, CatalogAvailability.AVAILABLE, CatalogAvailability.UNKNOWN)) {
            compose.runOnIdle { availability.value = state }
            compose.onNodeWithText("Watch").assertIsEnabled()
            assignment(PrototypeSource.ABEMA_LIVE, "A").assertIsOn()
        }
    }

    @Test fun unsupportedConfiguredResourceCannotOpenTheSampleViewer() {
        val original = item(PrototypeSource.ABEMA_LIVE)
        val unsupported = original.copy(entry = original.entry.copy(title = "Another saved channel",
            resource = original.entry.resource.copy(identity = "another-channel")))
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(null, configuredSources = configured.map { if (it.id == original.id) unsupported else it },
                onWatch = {})
        } }
        compose.onNodeWithText("Another saved channel").assertExists()
        compose.onNodeWithContentDescription("Assign Another saved channel to feed A").assertIsOn()
        compose.onNodeWithText("Watch").assertIsNotEnabled()
    }

    @Test fun unsupportedResourceWithLegacyLookingTitleKeepsItsExactTitle() {
        val original = item(PrototypeSource.ABEMA_LIVE)
        val unsupported = original.copy(entry = original.entry.copy(resource = original.entry.resource.copy(identity = "another-channel")))
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(null, configuredSources = configured.map { if (it.id == original.id) unsupported else it },
                onWatch = {})
        } }
        compose.onNodeWithText(original.entry.title).assertExists()
        compose.onNodeWithText(PrototypeSource.ABEMA_LIVE.optionTitle).assertDoesNotExist()
        assignment(PrototypeSource.ABEMA_LIVE, "A").assertIsOn()
        compose.onNodeWithText("Watch").assertIsNotEnabled()
    }
}
