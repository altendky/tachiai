package net.fstab.tachiai.feature.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.view.KeyEvent
import net.fstab.tachiai.feature.connections.ProviderInstancesScreen
import net.fstab.tachiai.feature.connections.SettingsPageHeader
import net.fstab.tachiai.presentation.ConfiguredFeedAssignments
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.PrototypeSource
import net.fstab.tachiai.presentation.defaultProviderInstances
import net.fstab.tachiai.presentation.defaultSourceSetups
import net.fstab.tachiai.presentation.legacyConfiguredSources
import net.fstab.tachiai.presentation.prototypeCatalogResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

// Synthetic presentation only: callbacks retain their fixture state in memory.
// These tests never open stores, accounts, provider pages or media sessions.
class TvNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Composable
    private fun Television(content: @Composable () -> Unit) {
        val input = LocalInputModeManager.current
        // Keep the test independent of a previous test's last input device.
        SideEffect { input.requestInputMode(InputMode.Keyboard) }
        TachiaiPrototypeTheme(television = true, content = content)
    }

    private fun SemanticsNodeInteraction.focused(): SemanticsNodeInteraction =
        assertIsFocused()

    private fun SemanticsNodeInteraction.key(key: Key) = performKeyInput { pressKey(key) }

    // Deliver a real remote Back to the focused window. Espresso's base-view
    // root picker can select the unfocused Activity while a Compose dialog owns it.
    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    @Test fun remoteAssignsOneSourceToBothFeedsAndOpensOnlyOnWatch() {
        val provider = defaultProviderInstances().single { it.service == PrototypeService.TWITCH }
        val source = legacyConfiguredSources(provider, defaultSourceSetups(), emptyMap()).single {
            it.entry.resource == prototypeCatalogResource(PrototypeSource.TWITCH_LIVE)
        }
        var assignments = ConfiguredFeedAssignments(null, null)
        var opened: ConfiguredFeedAssignments? = null
        compose.setContent { Television {
            PrototypeSourcePicker(null, providerInstances = listOf(provider), configuredSources = listOf(source),
                initialAssignments = assignments, onAssignmentsChanged = { assignments = it },
                onWatch = { opened = it })
        } }

        val a = compose.onNodeWithContentDescription("Assign ${source.entry.title} to feed A")
        val b = compose.onNodeWithContentDescription("Assign ${source.entry.title} to feed B")
        val watch = compose.onNodeWithText("Watch")
        watch.assertIsNotEnabled()
        a.focused().key(Key.DirectionCenter)
        a.assertIsSelected().focused().key(Key.DirectionRight)
        b.focused().key(Key.DirectionCenter)
        b.assertIsSelected()
        compose.runOnIdle {
            assertEquals(ConfiguredFeedAssignments(source.choice, source.choice), assignments)
            assertNull(opened)
        }
        watch.assertIsEnabled()
        b.focused().key(Key.DirectionUp)
        watch.focused().key(Key.DirectionCenter)
        compose.runOnIdle { assertEquals(ConfiguredFeedAssignments(source.choice, source.choice), opened) }
    }

    @Test fun emptyPickerStartsOnProvidersWithoutOpeningPlayback() {
        var providers = 0
        var watches = 0
        compose.setContent { Television {
            PrototypeSourcePicker(null, providerInstances = emptyList(), configuredSources = emptyList(),
                initialAssignments = ConfiguredFeedAssignments(null, null),
                onProviders = { providers++ }, onWatch = { watches++ })
        } }
        compose.onNodeWithText("Watch").assertIsNotEnabled()
        compose.onNodeWithText("Providers").focused().key(Key.DirectionCenter)
        compose.runOnIdle { assertEquals(1, providers); assertEquals(0, watches) }
    }

    @Test fun settingsHeaderTraversesActionsAndHelpAndRestoresFocusAfterBack() {
        var backs = 0
        var actions = 0
        compose.setContent { Television {
            Column(Modifier.tvFocusGroup()) {
                SettingsPageHeader("TV fixture settings", onBack = { backs++ },
                    backDescription = "Back from TV fixture settings", help = "Fixture help stays in this dialog.",
                    actions = { RemoteTextButton(onClick = { actions++ }) { Text("Fixture action") } })
            }
        } }
        val back = compose.onNodeWithContentDescription("Back from TV fixture settings")
        val action = compose.onNodeWithText("Fixture action")
        val help = compose.onNodeWithContentDescription("Info about TV fixture settings")
        back.focused().key(Key.DirectionRight)
        action.focused().key(Key.DirectionRight)
        help.focused().key(Key.DirectionCenter)
        compose.onNodeWithText("Fixture help stays in this dialog.").assertIsDisplayed()
        pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Fixture help stays in this dialog.").assertDoesNotExist()
        help.focused().key(Key.DirectionLeft)
        action.focused().key(Key.DirectionCenter)
        action.focused().key(Key.DirectionLeft)
        back.focused().key(Key.DirectionCenter)
        compose.runOnIdle { assertEquals(1, actions); assertEquals(1, backs) }
    }

    @Test fun providersHelpClosesWithBackWithoutCreatingRemovingOrOpeningAnInstance() {
        var backs = 0
        var mutations = 0
        var opens = 0
        compose.setContent { Television {
            ProviderInstancesScreen(defaultProviderInstances(), emptyList(), false, null,
                onRoutes = { mutations++ }, onSave = { _, _, _ -> mutations++ }, onBack = { backs++ },
                onCreate = { mutations++ }, onReorder = { mutations++; null }, onRemove = { mutations++ },
                onOpenProvider = { opens++ })
        } }
        val back = compose.onNodeWithContentDescription("Back to streams")
        val help = compose.onNodeWithContentDescription("Info about Providers")
        back.focused().key(Key.DirectionRight)
        help.focused().key(Key.DirectionCenter)
        compose.onNodeWithText("Close").assertIsDisplayed()
        pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Close").assertDoesNotExist()
        help.focused().key(Key.DirectionLeft)
        back.focused().key(Key.DirectionCenter)
        compose.runOnIdle { assertEquals(1, backs); assertEquals(0, mutations); assertEquals(0, opens) }
    }

    @Test fun providerReadinessKeepsNavigationAvailableAndDoesNotStealSurvivingHelpFocus() {
        var busy by mutableStateOf(true)
        compose.setContent { Television {
            ProviderInstancesScreen(defaultProviderInstances(), emptyList(), busy, null,
                onRoutes = {}, onSave = { _, _, _ -> }, onBack = {})
        } }
        val back = compose.onNodeWithContentDescription("Back to streams")
        val help = compose.onNodeWithContentDescription("Info about Providers")
        back.assertIsNotEnabled()
        help.focused()
        compose.runOnIdle { busy = false }
        help.focused()
        compose.runOnIdle { busy = true }
        help.focused()
        compose.runOnIdle { busy = false }
        help.focused().key(Key.DirectionLeft)
        back.focused()
    }

    @Test fun unavailablePickerEntersRoutesBeforeDeveloperOverflowAndKeepsPlaybackDisabled() {
        var routes = 0
        var providers = 0
        var developers = 0
        var watches = 0
        compose.setContent { Television {
            PrototypeSourcePicker("Provider setup could not be read.", setupReady = false,
                onConnections = { routes++ }, onProviders = { providers++ },
                onDeveloperTools = { developers++ }, onWatch = { watches++ })
        } }
        compose.onNodeWithContentDescription("More options").assertExists()
        compose.onNodeWithText("Providers").assertIsNotEnabled()
        compose.onNodeWithText("Watch").assertIsNotEnabled()
        compose.onNodeWithText("Routes").focused().key(Key.DirectionCenter)
        compose.runOnIdle {
            assertEquals(1, routes); assertEquals(0, providers)
            assertEquals(0, developers); assertEquals(0, watches)
        }
    }

    @Test fun readyPickerEntersRoutesBeforeOverflowAndMovesRightToProviders() {
        var routes = 0
        var providers = 0
        var developers = 0
        var watches = 0
        compose.setContent { Television {
            PrototypeSourcePicker(null, onConnections = { routes++ }, onProviders = { providers++ },
                onDeveloperTools = { developers++ }, onWatch = { watches++ })
        } }
        compose.onNodeWithContentDescription("More options").assertExists()
        compose.onNodeWithText("Routes").focused().key(Key.DirectionRight)
        compose.onNodeWithText("Providers").focused().key(Key.DirectionCenter)
        compose.runOnIdle {
            assertEquals(0, routes); assertEquals(1, providers)
            assertEquals(0, developers); assertEquals(0, watches)
        }
    }

    @Test fun deletingFocusedProviderLeavesAnEnabledFallbackWithoutOpeningAnotherProvider() {
        val provider = defaultProviderInstances().single { it.service == PrototypeService.TWITCH }
            .copy(customName = "TV account")
        var instances by mutableStateOf(listOf(provider))
        var removals = 0
        var opens = 0
        compose.setContent { Television {
            ProviderInstancesScreen(instances, emptyList(), false, null,
                onRoutes = {}, onSave = { _, _, _ -> }, onBack = {},
                onRemove = { id -> removals++; instances = instances.filterNot { it.id == id } },
                onOpenProvider = { opens++ })
        } }
        // Seed the exact row action: this test isolates removal/restoration,
        // rather than depending on the list's geometric down-navigation.
        val actions = compose.onNodeWithContentDescription("Provider actions: TV account")
        actions.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        actions.focused().key(Key.DirectionCenter)
        val deleteItem = compose.onNodeWithText("Delete…")
        deleteItem.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        deleteItem.focused().key(Key.DirectionCenter)
        compose.onNodeWithText("Delete TV account?").assertIsDisplayed()
        val cancel = compose.onNodeWithText("Cancel")
        cancel.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        cancel.focused().key(Key.DirectionRight)
        compose.onNodeWithText("Delete", substring = false).focused().key(Key.DirectionCenter)
        compose.onNodeWithContentDescription("Provider actions: TV account").assertDoesNotExist()
        compose.onNodeWithText("No providers configured.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to streams").assertIsEnabled().focused()
        compose.runOnIdle { assertEquals(1, removals); assertEquals(0, opens) }
    }
}
