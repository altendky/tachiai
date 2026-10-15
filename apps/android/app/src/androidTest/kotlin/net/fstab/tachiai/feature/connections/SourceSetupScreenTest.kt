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
    @Test fun defaultsAreNotWrittenUntilEditedAndRouteChangesSaveImmediately() {
        var saved: SourceSetup? = null
        var imported = false
        val connection = ConnectionSummary("12345678-1234-1234-1234-123456789abc", "Proton Japan", ConnectionKind.WIREGUARD, "fixture.example.test:51820")
        compose.setContent { TachiaiPrototypeTheme {
            SourceSetupScreen(PrototypeSource.ABEMA_REPLAY, SourceSetup("Sumo"), listOf(connection), false, null,
                { imported = true }, { saved = it }, {})
        } }
        compose.waitForIdle()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Add connection · setup / import").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Source default connection: Proton Japan").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(connection.id, checkNotNull(saved).defaultRoute.connectionId) }
        compose.onNodeWithContentDescription("Feed A connection: System network").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(imported)
            val value = checkNotNull(saved)
            assertEquals(SourceRouteChoice.system, value.route(PrototypeSlot.A))
            assertEquals(connection.id, value.route(PrototypeSlot.B).connectionId)
        }
        compose.onNodeWithText("Save source setup").assertDoesNotExist()
    }

    @Test fun recreationRetainsNonsecretSetupMetadataWithoutWritingItAgain() {
        val restoration = StateRestorationTester(compose)
        lateinit var focusManager: FocusManager
        var saved: SourceSetup? = null
        var writes = 0
        val connection = ConnectionSummary("12345678-1234-1234-1234-123456789abc", "Proton Japan", ConnectionKind.WIREGUARD, "fixture.example.test:51820")
        restoration.setContent { TachiaiPrototypeTheme {
            focusManager = LocalFocusManager.current
            SourceSetupScreen(PrototypeSource.ABEMA_REPLAY, SourceSetup("Sumo"), listOf(connection), false, null,
                {}, { saved = it; writes++ }, {})
        } }
        compose.onNodeWithText("Source name · max 64 characters").performTextReplacement("My sumo")
        // Settle IME/inset changes before scrolling and tapping the route radio button.
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Source default connection: Proton Japan").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Source default connection: Proton Japan").assertIsSelected()
        var previousWrites = 0
        compose.runOnIdle { previousWrites = writes }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Source default connection: Proton Japan").assertIsSelected()
        compose.runOnIdle {
            assertEquals(previousWrites, writes)
            val value = checkNotNull(saved)
            assertEquals("My sumo", value.name)
            assertEquals(connection.id, value.route(PrototypeSlot.A).connectionId)
        }
    }

    @Test fun invalidNameNeverReplacesTheValidNameAndDoesNotBlockRouteChanges() {
        val saved = mutableListOf<SourceSetup>()
        lateinit var focusManager: FocusManager
        compose.setContent { TachiaiPrototypeTheme {
            focusManager = LocalFocusManager.current
            SourceSetupScreen(PrototypeSource.ABEMA_REPLAY, SourceSetup("Sumo"), emptyList(), false, null,
                {}, { saved += it }, {})
        } }
        compose.onNodeWithText("Source name · max 64 characters").performTextReplacement("")
        compose.runOnIdle { assertTrue(saved.isEmpty()); focusManager.clearFocus(force = true) }
        closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Feed A connection: System network").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("Sumo", saved.single().name)
            assertEquals(SourceRouteChoice.system, saved.single().feedA)
        }
    }
}
