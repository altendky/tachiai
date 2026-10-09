package net.fstab.tachiai.feature.connections

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.platform.network.ConnectionProfile
import net.fstab.tachiai.platform.network.parseConnectionProfile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConnectionProfilesScreenTest {
    @get:Rule val compose = createComposeRule()
    private var saved: String? = null
    private var discarded = false
    private var openedProton = false
    private var openedWindscribe = false
    private var openedPicker = false
    private fun screen(draft: ConnectionProfile? = null) {
        compose.setContent { TachiaiPrototypeTheme {
            ConnectionProfilesScreen(emptyList(), draft, false, null,
                { openedProton = true }, { openedWindscribe = true }, { openedPicker = true }, {}, { saved = it }, { discarded = true }, {}, {})
        } }
        compose.waitForIdle()
    }

    @Test fun guidanceAndFallbackAreExplicitAndNeverSaveAutomatically() {
        screen()
        compose.onNodeWithText("Set up Proton").performScrollTo().performClick()
        compose.onNodeWithText("Paid Windscribe account required: Pro includes all locations; Build-A-Plan includes your paid locations.")
            .performScrollTo().assertExists()
        compose.onNodeWithText("Choose WireGuard, not OpenVPN or IKEv2.", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("Set up Windscribe").performScrollTo().performClick()
        compose.onNodeWithText("Import file (fallback)").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(openedProton); assertTrue(openedWindscribe); assertTrue(openedPicker); assertNull(saved) }
    }

    @Test fun previewHidesCredentialsAndNeedsNameAndExplicitSave() {
        screen(parseConnectionProfile("http://fixture:private-password@proxy.example.test:3128".toByteArray()))
        compose.onNodeWithText("private-password", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Save route").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Route name").performScrollTo().performTextInput("Japan proxy")
        compose.onNodeWithText("Save route").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Japan proxy", saved) }
    }

    @Test fun discardDoesNotSave() {
        screen(parseConnectionProfile("http://proxy.example.test:3128".toByteArray()))
        compose.onNodeWithText("Discard import").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(discarded); assertNull(saved) }
    }
}
