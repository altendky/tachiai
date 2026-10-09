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
    @Test fun loginSectionBelongsOnlyToTwitchBesideItsRouteEditor() {
        compose.setContent { TachiaiPrototypeTheme {
            ProvidersScreen(legacyProviderSetups(defaultSourceSetups()), emptyList(), false, null, {}, { _, _ -> }, {},
                twitchLogin = { Text("Twitch login fixture") })
        } }
        compose.onNodeWithText("Configure ABEMA").performScrollTo().performClick()
        compose.onNodeWithText("Twitch login fixture").assertDoesNotExist()
        compose.onNodeWithText("Back to providers").performScrollTo().performClick()
        compose.onNodeWithText("Configure Twitch").performScrollTo().performClick()
        compose.onNodeWithText("Twitch login fixture").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Twitch route: System network").performScrollTo().assertIsSelected()
    }
    @Test fun providerDefaultNeedsExplicitSaveAndDraftSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        var imported = false
        var saved: Pair<PrototypeService, SourceRouteChoice>? = null
        restoration.setContent { TachiaiPrototypeTheme {
            ProvidersScreen(legacyProviderSetups(defaultSourceSetups()), listOf(profile), false, null,
                { imported = true }, { p, r -> saved = p to r }, {})
        } }
        compose.onNodeWithText("Configure ABEMA").performScrollTo().performClick()
        compose.onNodeWithContentDescription("ABEMA route: Proton Japan").performScrollTo().performClick()
        compose.onNodeWithText("Add route · Proton / import").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertTrue(imported); assertNull(saved) }
        compose.onNodeWithContentDescription("ABEMA route: Proton Japan").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Save ABEMA setup").performScrollTo().performClick()
        compose.runOnIdle {
            val value = checkNotNull(saved)
            assertEquals(PrototypeService.ABEMA, value.first); assertEquals(profile.id, value.second.connectionId)
        }
        compose.onNodeWithText("Back to providers").performScrollTo().performClick()
        compose.onNodeWithText("Configure Twitch").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Twitch route: System network").performScrollTo().assertIsSelected()
    }
    @Test fun legacyConflictRequiresAnExplicitReplacement() {
        val settings = legacyProviderSetups(defaultSourceSetups()) + (PrototypeService.ABEMA to ProviderSetup(null))
        compose.setContent { TachiaiPrototypeTheme { ProvidersScreen(settings, emptyList(), false, null, {}, { _, _ -> }, {}) } }
        compose.onNodeWithText("Configure ABEMA").performScrollTo().performClick()
        compose.onNodeWithText("Save ABEMA setup").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("ABEMA route: System network").performScrollTo().performClick()
        compose.onNodeWithText("Save ABEMA setup").performScrollTo().assertIsEnabled()
    }
    @Test fun deletedRouteDoesNotSelectSystemOrAllowSave() {
        val route = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, profile.id, profile.name)
        val settings = legacyProviderSetups(defaultSourceSetups()) + (PrototypeService.ABEMA to ProviderSetup(route))
        compose.setContent { TachiaiPrototypeTheme { ProvidersScreen(settings, emptyList(), false, null, {}, { _, _ -> }, {}) } }
        compose.onNodeWithText("Configure ABEMA").performScrollTo().performClick()
        compose.onNodeWithContentDescription("ABEMA route: System network").performScrollTo().assertIsNotSelected()
        compose.onNodeWithText("Save ABEMA setup").performScrollTo().assertIsNotEnabled()
    }
    @Test fun routeDeletedDuringImportReturnInvalidatesDraftWithoutFallback() {
        val profiles = mutableStateOf(listOf(profile))
        compose.setContent { TachiaiPrototypeTheme {
            ProvidersScreen(legacyProviderSetups(defaultSourceSetups()), profiles.value, false, null,
                { profiles.value = emptyList() }, { _, _ -> }, {})
        } }
        compose.onNodeWithText("Configure ABEMA").performScrollTo().performClick()
        compose.onNodeWithContentDescription("ABEMA route: Proton Japan").performScrollTo().performClick()
        compose.onNodeWithText("Add route · Proton / import").performScrollTo().performClick()
        compose.onNodeWithText("Selected saved route is unavailable. Choose a replacement; no system fallback occurs.").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("ABEMA route: System network").performScrollTo().assertIsNotSelected()
        compose.onNodeWithText("Save ABEMA setup").performScrollTo().assertIsNotEnabled()
    }
}
