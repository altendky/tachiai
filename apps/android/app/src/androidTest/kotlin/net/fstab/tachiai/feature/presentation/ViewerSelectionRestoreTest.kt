package net.fstab.tachiai.feature.presentation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// ComponentActivity-hosted picker only: no product Activity, storage, routes,
// provider sessions, account state or network operations are used.
class ViewerSelectionRestoreTest {
    @get:Rule val compose = createComposeRule()
    private val providers = defaultProviderInstances()
    private val configured = providers.flatMap { legacyConfiguredSources(it, defaultSourceSetups(), emptyMap()) }
    private fun source(kind: PrototypeSource) = configured.single { it.entry.resource == prototypeCatalogResource(kind) }
    private fun assignment(kind: PrototypeSource, slot: String) =
        compose.onNodeWithContentDescription("Assign ${source(kind).entry.title} to feed $slot")

    @Test fun asynchronousGenerationRestoresDuplicateChoicesWithoutClosingPickerNavigation() {
        val initial = mutableStateOf(restoreConfiguredFeedAssignments(false, null, null))
        val generation = mutableStateOf(0L)
        var watched: ConfiguredFeedAssignments? = null
        var developerOpens = 0
        compose.setContent { TachiaiPrototypeTheme(television = false) {
            PrototypeSourcePicker(null, providerInstances = providers, configuredSources = configured,
                initialAssignments = initial.value, assignmentRestoreGeneration = generation.value,
                onDeveloperTools = { developerOpens++ }, onWatch = { watched = it })
        } }
        assignment(PrototypeSource.ABEMA_LIVE, "A").performScrollTo().assertIsOn()
        assignment(PrototypeSource.TWITCH_LIVE, "B").performScrollTo().assertIsOn()
        val restored = ConfiguredFeedAssignments(source(PrototypeSource.TWITCH_LIVE).choice,
            source(PrototypeSource.TWITCH_LIVE).choice)
        compose.runOnIdle { initial.value = restored }
        // A normal setup recomposition must not reset the user's local state.
        assignment(PrototypeSource.ABEMA_LIVE, "A").performScrollTo().assertIsOn()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Developer tools").assertExists()
        compose.runOnIdle { generation.value++ }
        compose.onNodeWithText("Developer tools").assertExists().performClick()
        assignment(PrototypeSource.TWITCH_LIVE, "A").performScrollTo().assertIsOn()
        assignment(PrototypeSource.TWITCH_LIVE, "B").performScrollTo().assertIsOn()
        assignment(PrototypeSource.ABEMA_LIVE, "A").performScrollTo().assertIsOff()
        compose.onNodeWithText("Watch").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(restored, watched); assertEquals(1, developerOpens) }
    }

    @Test fun asynchronousPartialAssignmentsBecomePlayableAndExplicitEmptyStillClearsBothAndBlocksWatch() {
        val initial = mutableStateOf(restoreConfiguredFeedAssignments(false, null, null))
        val generation = mutableStateOf(0L)
        var watched: ConfiguredFeedAssignments? = null
        compose.setContent { TachiaiPrototypeTheme(television = false) {
            PrototypeSourcePicker(null, providerInstances = providers, configuredSources = configured,
                initialAssignments = initial.value, assignmentRestoreGeneration = generation.value,
                onWatch = { watched = it })
        } }
        compose.runOnIdle {
            initial.value = ConfiguredFeedAssignments(source(PrototypeSource.TWITCH_LIVE).choice, null)
            generation.value++
        }
        assignment(PrototypeSource.TWITCH_LIVE, "A").performScrollTo().assertIsOn()
        assignment(PrototypeSource.TWITCH_LIVE, "B").performScrollTo().assertIsOff()
        assignment(PrototypeSource.ABEMA_LIVE, "A").performScrollTo().assertIsOff()
        compose.onNodeWithText("Watch").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(initial.value, watched) }
        compose.runOnIdle {
            initial.value = ConfiguredFeedAssignments(null, source(PrototypeSource.TWITCH_LIVE).choice)
            generation.value++
        }
        assignment(PrototypeSource.TWITCH_LIVE, "A").performScrollTo().assertIsOff()
        assignment(PrototypeSource.TWITCH_LIVE, "B").performScrollTo().assertIsOn()
        compose.onNodeWithText("Watch").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(initial.value, watched); watched = null }
        compose.runOnIdle { initial.value = ConfiguredFeedAssignments(null, null); generation.value++ }
        assignment(PrototypeSource.TWITCH_LIVE, "A").performScrollTo().assertIsOff()
        assignment(PrototypeSource.TWITCH_LIVE, "B").performScrollTo().assertIsOff()
        compose.onNodeWithText("Watch").assertIsNotEnabled()
        compose.runOnIdle { assertNull(watched) }
    }

    @Test fun rememberedRemovedItemNeverSelectsAReplacementOrEnablesWatch() {
        val initial = mutableStateOf(restoreConfiguredFeedAssignments(false, null, null))
        val generation = mutableStateOf(0L)
        val visible = configured.filterNot { it.choice == source(PrototypeSource.ABEMA_REPLAY).choice }
        compose.setContent { TachiaiPrototypeTheme(television = false) {
            PrototypeSourcePicker(null, providerInstances = providers, configuredSources = visible,
                initialAssignments = initial.value, assignmentRestoreGeneration = generation.value,
                onWatch = { error("Removed item opened playback") })
        } }
        compose.runOnIdle {
            initial.value = ConfiguredFeedAssignments(source(PrototypeSource.ABEMA_REPLAY).choice,
                source(PrototypeSource.TWITCH_LIVE).choice)
            generation.value++
        }
        assignment(PrototypeSource.ABEMA_REPLAY, "A").assertDoesNotExist()
        assignment(PrototypeSource.ABEMA_LIVE, "A").performScrollTo().assertIsOff()
        assignment(PrototypeSource.TWITCH_LIVE, "A").performScrollTo().assertIsOff()
        assignment(PrototypeSource.TWITCH_LIVE, "B").performScrollTo().assertIsOn()
        compose.onNodeWithText("Watch").assertIsNotEnabled()
        compose.onNodeWithText("A selected item was removed. Choose another configured stream.").assertExists()
    }
}
