package net.fstab.tachiai.feature.presentation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PrototypeProviderInstancePickerTest {
    @get:Rule val compose = createComposeRule()
    private val extra = ProviderInstance("12345678-1234-1234-1234-123456789abc", PrototypeService.TWITCH, "Twitch 2")
    @Test fun sameStreamUnderDifferentInstancesRestoresBothBindingsAndCanOpenDuplicateFeeds() {
        val restoration = StateRestorationTester(compose)
        var opened: PrototypeSelection? = null
        restoration.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(PrototypeSelection(), null, providerInstances = defaultProviderInstances() + extra,
                onWatch = { opened = it })
        } }
        val stream = PrototypeSource.TWITCH_LIVE
        compose.onNodeWithContentDescription("Assign ${stream.title} using Twitch 2 to feed A").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Assign ${stream.title} to feed B").performScrollTo().assertIsOn()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Assign ${stream.title} using Twitch 2 to feed A").performScrollTo().assertIsOn()
        compose.onNodeWithContentDescription("Assign ${stream.title} to feed B").performScrollTo().assertIsOn()
        compose.onNodeWithText("Watch").performClick()
        compose.runOnIdle {
            assertEquals(PrototypeSelection(stream, stream, extra.id, defaultProviderInstanceId(PrototypeService.TWITCH)), opened)
        }
    }
    @Test fun staleOrWrongTypeInstanceDisablesViewerUntilExplicitReassignment() {
        val instances = mutableStateOf(defaultProviderInstances() + extra)
        val stream = PrototypeSource.TWITCH_LIVE
        var opened: PrototypeSelection? = null
        compose.setContent { TachiaiPrototypeTheme {
            PrototypeSourcePicker(PrototypeSelection(stream, stream, extra.id, defaultProviderInstanceId(PrototypeService.ABEMA)),
                null, providerInstances = instances.value, onWatch = { opened = it })
        } }
        compose.onNodeWithText("Watch").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Assign ${stream.title} to feed B").performScrollTo().performClick()
        compose.onNodeWithText("Watch").assertIsEnabled()
        compose.runOnIdle { instances.value = defaultProviderInstances() }
        compose.onNodeWithText("Watch").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Assign ${stream.title} to feed A").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("Watch").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(PrototypeSelection(stream, stream), opened) }
    }
}
