package net.fstab.tachiai.feature.connections

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.test.espresso.Espresso.closeSoftKeyboard
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.platform.network.ConnectionKind
import net.fstab.tachiai.platform.network.ConnectionSummary
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SourceSetupScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun defaultAndIndependentOverridesAreSavedOnlyAfterExplicitSave() {
        var saved: SourceSetup? = null
        var imported = false
        val connection = ConnectionSummary("12345678-1234-1234-1234-123456789abc", "Proton Japan", ConnectionKind.WIREGUARD, "fixture.example.test:51820")
        compose.setContent { TachiaiPrototypeTheme {
            SourceSetupScreen(PrototypeSource.ABEMA_REPLAY, SourceSetup("Sumo"), listOf(connection), false, null,
                { imported = true }, { saved = it }, {})
        } }
        compose.waitForIdle()
        compose.onNodeWithText("Add connection · setup / import").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Source default connection: Proton Japan").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Feed A connection: System network").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(imported); assertNull(saved) }
        compose.onNodeWithText("Save source setup").performScrollTo().performClick()
        compose.runOnIdle {
            val value = checkNotNull(saved)
            assertEquals(SourceRouteChoice.system, value.route(PrototypeSlot.A))
            assertEquals(connection.id, value.route(PrototypeSlot.B).connectionId)
        }
    }

    @Test fun recreationRetainsOnlyNonsecretUnsavedSetupMetadata() {
        val restoration = StateRestorationTester(compose)
        lateinit var focusManager: FocusManager
        var saved: SourceSetup? = null
        val connection = ConnectionSummary("12345678-1234-1234-1234-123456789abc", "Proton Japan", ConnectionKind.WIREGUARD, "fixture.example.test:51820")
        restoration.setContent { TachiaiPrototypeTheme {
            focusManager = LocalFocusManager.current
            SourceSetupScreen(PrototypeSource.ABEMA_REPLAY, SourceSetup("Sumo"), listOf(connection), false, null,
                {}, { saved = it }, {})
        } }
        compose.onNodeWithText("Source name · max 64 characters").performTextReplacement("My sumo")
        // Settle IME/inset changes before scrolling and tapping the route radio button.
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Source default connection: Proton Japan").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Source default connection: Proton Japan").assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Source default connection: Proton Japan").assertIsSelected()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Save source setup").performScrollTo().performClick()
        compose.runOnIdle {
            val value = checkNotNull(saved)
            assertEquals("My sumo", value.name)
            assertEquals(connection.id, value.route(PrototypeSlot.A).connectionId)
        }
    }
}
