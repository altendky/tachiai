package net.fstab.tachiai.feature.connections

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.test.espresso.Espresso.closeSoftKeyboard
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
    private lateinit var focusManager: FocusManager
    private fun screen(draft: ConnectionProfile? = null) {
        compose.setContent { TachiaiPrototypeTheme {
            focusManager = LocalFocusManager.current
            ConnectionProfilesScreen(emptyList(), draft, false, null,
                { openedProton = true }, { openedWindscribe = true }, { openedPicker = true }, {}, { saved = it }, { discarded = true }, {}, {})
        } }
        compose.waitForIdle()
    }

    private fun settleNamedPreview(name: String) {
        compose.onNodeWithText(name).assertExists()
        // Settle IME/inset changes before scrolling and tapping Save.
        compose.runOnIdle { focusManager.clearFocus(force = true); assertNull(saved) }
        closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithText("Route name").assertIsNotFocused()
        compose.onNodeWithText("Save route").performScrollTo().assertIsEnabled()
    }

    @Test fun guidanceAndFallbackAreExplicitAndNeverSaveAutomatically() {
        screen()
        compose.onNodeWithText("Set up Proton").performScrollTo().performClick()
        compose.onNodeWithText("Paid Windscribe account required: Pro includes all locations; Build-A-Plan includes your paid locations.")
            .performScrollTo().assertExists()
        compose.onNodeWithText("For this Windscribe export, choose WireGuard.", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("Set up Windscribe").performScrollTo().performClick()
        compose.onNodeWithText("Import file (fallback)").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(openedProton); assertTrue(openedWindscribe); assertTrue(openedPicker); assertNull(saved) }
    }

    @Test fun previewHidesCredentialsAndNeedsNameAndExplicitSave() {
        screen(parseConnectionProfile("http://fixture:private-password@proxy.example.test:3128".toByteArray()))
        compose.onNodeWithText("private-password", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Save route").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Route name").performScrollTo().performTextInput("Japan proxy")
        settleNamedPreview("Japan proxy")
        compose.onNodeWithText("Save route").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Japan proxy", saved) }
    }

    @Test fun discardDoesNotSave() {
        screen(parseConnectionProfile("http://proxy.example.test:3128".toByteArray()))
        compose.onNodeWithText("Discard import").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(discarded); assertNull(saved) }
    }

    @Test fun socksPreviewHidesEncodedAndDecodedCredentialsAndRequiresExplicitSave() {
        screen(parseConnectionProfile("socks5://fixture:private%2Dpassword@proxy.example.test:1080".toByteArray()))
        compose.onNodeWithText("SOCKS5 proxy\nEndpoint: proxy.example.test:1080\nPrivate keys and credentials: hidden")
            .performScrollTo().assertExists()
        compose.onNodeWithText("fixture", substring = true).assertDoesNotExist()
        compose.onNodeWithText("private", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Save route").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Route name").performScrollTo().performTextInput("SOCKS route")
        settleNamedPreview("SOCKS route")
        compose.onNodeWithText("Save route").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("SOCKS route", saved) }
    }

    @Test fun openVpnPreviewHidesInlineMaterialAndRequiresExplicitSave() {
        // Grammar-only fixture; Save must not initiate native cryptographic
        // validation, authenticate to a server or expose inline key material.
        val configuration = """
            client
            dev tun
            proto udp
            remote 192.0.2.10 1194
            remote-cert-tls server
            verify-x509-name owned-route.test name
            tls-version-min 1.2
            <ca>
            synthetic-ca-sentinel
            </ca>
            <cert>
            synthetic-cert-sentinel
            </cert>
            <key>
            synthetic-key-sentinel
            </key>
        """.trimIndent()
        screen(parseConnectionProfile(configuration.toByteArray()))
        compose.onNodeWithText("OpenVPN\nEndpoint: 192.0.2.10:1194\nPrivate keys and credentials: hidden")
            .performScrollTo().assertExists()
        listOf("synthetic-ca-sentinel", "synthetic-cert-sentinel", "synthetic-key-sentinel", "owned-route.test")
            .forEach { compose.onNodeWithText(it, substring = true).assertDoesNotExist() }
        compose.onNodeWithText("Save route").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Route name").performScrollTo().performTextInput("Owned VPN")
        settleNamedPreview("Owned VPN")
        compose.onNodeWithText("Save route").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Owned VPN", saved) }
    }
}
