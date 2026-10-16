package net.fstab.tachiai.feature.connections

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.platform.network.ConnectionKind
import net.fstab.tachiai.platform.network.ConnectionSummary
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProvidersScreenTest {
    @get:Rule val compose = createComposeRule()
    private val profile = ConnectionSummary("12345678-1234-1234-1234-123456789abc", "Proton Japan", ConnectionKind.WIREGUARD, "fixture.example.test:51820")
    @Test fun catalogAccountKeepsExactTwitchOwnerWhileSettingsSaveAutomatically() {
        val second = ProviderInstance("12345678-1234-1234-1234-123456789abd", PrototypeService.TWITCH, "Twitch 2")
        var connected: String? = null
        var saved = false
        compose.setContent { TachiaiPrototypeTheme {
            ProviderInstancesScreen(defaultProviderInstances() + second, listOf(profile), false, null,
                {}, { _, _, _ -> saved = true }, {}, onCatalogConnection = { connected = it })
        } }
        compose.onNodeWithText("Configure ABEMA").performScrollTo().performClick()
        compose.onNodeWithText("Twitch account").assertDoesNotExist()
        compose.onNodeWithText("ABEMA account").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Back to providers").performScrollTo().performClick()
        compose.onNodeWithText("Configure Twitch 2").performScrollTo().performClick()
        compose.onNodeWithText("Provider instance name").performScrollTo().performTextInput("Draft")
        compose.onNodeWithContentDescription("Twitch route: Proton Japan").performScrollTo().performClick()
        compose.onNodeWithText("Connect Twitch").assertDoesNotExist()
        compose.onAllNodesWithText("Twitch account").assertCountEquals(1)
        compose.onNodeWithText("Twitch account").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(second.id, connected); assertTrue(saved) }
    }
    @Test fun abemaAccountKeepsExactOwnerWhileNameSavesAutomatically() {
        val second = ProviderInstance("12345678-1234-1234-1234-123456789abe", PrototypeService.ABEMA, "ABEMA 2")
        var connected: String? = null; var saved = false
        compose.setContent { TachiaiPrototypeTheme {
            ProviderInstancesScreen(defaultProviderInstances() + second, emptyList(), false, null,
                {}, { _, _, _ -> saved = true }, {}, onCatalogConnection = { connected = it })
        } }
        compose.onNodeWithText("Configure ABEMA 2").performScrollTo().performClick()
        compose.onNodeWithText("Provider instance name").performScrollTo().performTextInput("Draft")
        compose.onNodeWithText("ABEMA account").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(second.id, connected); assertTrue(saved) }
    }
    @Test fun manageStreamsKeepsStableInstanceWhileNameSavesAutomatically() {
        val second = ProviderInstance("12345678-1234-1234-1234-123456789abd", PrototypeService.TWITCH, "Twitch 2")
        var managed: String? = null
        var saved = false
        compose.setContent { TachiaiPrototypeTheme {
            ProviderInstancesScreen(defaultProviderInstances() + second, emptyList(), false, null,
                {}, { _, _, _ -> saved = true }, {}, onManageStreams = { managed = it })
        } }
        compose.onNodeWithText("Configure Twitch 2").performScrollTo().performClick()
        compose.onNodeWithText("Provider instance name").performScrollTo().performTextInput("Unsaved draft")
        compose.onNodeWithText("Manage streams").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(second.id, managed); assertTrue(saved) }
    }
    @Test fun loginSectionBelongsOnlyToTwitchBesideItsRouteEditor() {
        compose.setContent { TachiaiPrototypeTheme {
            ProvidersScreen(legacyProviderSetups(defaultSourceSetups()), emptyList(), false, null, {}, { _, _ -> }, {},
                twitchLogin = { Text("Twitch login fixture") })
        } }
        compose.onNodeWithText("Configure ABEMA").performScrollTo().performClick()
        compose.onNodeWithText("Twitch login fixture").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to providers").performScrollTo().performClick()
        compose.onNodeWithText("Configure Twitch").performScrollTo().performClick()
        compose.onNodeWithText("Twitch login fixture").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Twitch route: System network").performScrollTo().assertIsSelected()
    }
    @Test fun providerDefaultSavesOnSelectionAndSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        var imported = false
        var saved: Pair<PrototypeService, SourceRouteChoice>? = null
        restoration.setContent { TachiaiPrototypeTheme {
            ProvidersScreen(legacyProviderSetups(defaultSourceSetups()), listOf(profile), false, null,
                { imported = true }, { p, r -> saved = p to r }, {})
        } }
        compose.onNodeWithText("Configure ABEMA").performScrollTo().performClick()
        compose.onNodeWithContentDescription("ABEMA route: Proton Japan").performScrollTo().performClick()
        compose.onNodeWithText("Add route · setup / import").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertTrue(imported); assertEquals(profile.id, saved?.second?.connectionId) }
        compose.onNodeWithContentDescription("ABEMA route: Proton Japan").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Save ABEMA setup").assertDoesNotExist()
        compose.runOnIdle {
            val value = checkNotNull(saved)
            assertEquals(PrototypeService.ABEMA, value.first); assertEquals(profile.id, value.second.connectionId)
        }
        compose.onNodeWithContentDescription("Back to providers").performScrollTo().performClick()
        compose.onNodeWithText("Configure Twitch").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Twitch route: System network").performScrollTo().assertIsSelected()
    }
    @Test fun legacyConflictRequiresAnExplicitReplacement() {
        val settings = legacyProviderSetups(defaultSourceSetups()) + (PrototypeService.ABEMA to ProviderSetup(null))
        compose.setContent { TachiaiPrototypeTheme { ProvidersScreen(settings, emptyList(), false, null, {}, { _, _ -> }, {}) } }
        compose.onNodeWithText("Configure ABEMA").performScrollTo().performClick()
        compose.onNodeWithText("Choose a route to resolve earlier settings.").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("ABEMA route: System network").performScrollTo().performClick()
        compose.onNodeWithContentDescription("ABEMA route: System network").assertIsSelected()
    }
    @Test fun deletedRouteDoesNotSelectSystemOrAllowSave() {
        val route = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, profile.id, profile.name)
        val settings = legacyProviderSetups(defaultSourceSetups()) + (PrototypeService.ABEMA to ProviderSetup(route))
        compose.setContent { TachiaiPrototypeTheme { ProvidersScreen(settings, emptyList(), false, null, {}, { _, _ -> }, {}) } }
        compose.onNodeWithText("Configure ABEMA").performScrollTo().performClick()
        compose.onNodeWithContentDescription("ABEMA route: System network").performScrollTo().assertIsNotSelected()
        compose.onNodeWithText("Save ABEMA setup").assertDoesNotExist()
    }
    @Test fun routeDeletedDuringImportReturnInvalidatesDraftWithoutFallback() {
        val profiles = mutableStateOf(listOf(profile))
        compose.setContent { TachiaiPrototypeTheme {
            ProvidersScreen(legacyProviderSetups(defaultSourceSetups()), profiles.value, false, null,
                { profiles.value = emptyList() }, { _, _ -> }, {})
        } }
        compose.onNodeWithText("Configure ABEMA").performScrollTo().performClick()
        compose.onNodeWithContentDescription("ABEMA route: Proton Japan").performScrollTo().performClick()
        compose.onNodeWithText("Add route · setup / import").performScrollTo().performClick()
        compose.onNodeWithText("Selected saved route is unavailable. Choose a replacement; no system fallback occurs.").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("ABEMA route: System network").performScrollTo().assertIsNotSelected()
        compose.onNodeWithText("Save ABEMA setup").assertDoesNotExist()
    }
}
