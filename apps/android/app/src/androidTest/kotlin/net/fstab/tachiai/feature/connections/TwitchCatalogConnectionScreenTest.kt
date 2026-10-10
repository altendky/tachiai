package net.fstab.tachiai.feature.connections

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.net.URI
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.provider.twitch.*
import net.fstab.tachiai.provider.twitch.catalog.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TwitchCatalogConnectionScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun catalogActionsAreDistinctAndPendingConsentCanBeCancelled() {
        val state = mutableStateOf(TwitchCatalogConnectionState(name = "Twitch 2", routeTitle = "Saved fixture route",
            ready = true, status = "No saved catalog account."))
        var connections = 0; var validations = 0; var cancellations = 0; var forgotten = 0; var browser: String? = null
        compose.setContent { TachiaiPrototypeTheme {
            TwitchCatalogConnectionScreen(state.value, { connections++ }, { validations++ }, { forgotten++ },
                { cancellations++ }, { browser = it }, {})
        } }
        compose.onNodeWithText("Connect Twitch").performScrollTo().performClick()
        compose.onAllNodesWithText("Connect Twitch").assertCountEquals(1)
        compose.onNodeWithText("Following, stream discovery and playback", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("Other provider instances keep their own accounts", substring = true).assertExists()
        compose.onNodeWithText("Validate Twitch account").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Forget Twitch account on this device").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("external consent browser uses its own network", substring = true).performScrollTo().assertExists()
        compose.runOnIdle {
            assertEquals(1, connections)
            state.value = state.value.copy(hasSavedGrant = true, canForget = true,
                operation = TwitchCatalogConnectionOperation.CONNECT, phase = DeviceAuthPhase.PAUSED,
                activation = DeviceActivation("FIXTURE123", URI("https://www.twitch.tv/activate")))
        }
        compose.onNodeWithText("Reconnect Twitch").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Your activation code: FIXTURE123").performScrollTo().assertExists()
        compose.onNodeWithText("Open Twitch activation in Chrome").performScrollTo().performClick()
        compose.onNodeWithText("Cancel Twitch action").performScrollTo().performClick()
        compose.onNodeWithText("Forget Twitch account on this device").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("com.android.chrome", browser); assertEquals(1, cancellations); assertEquals(1, forgotten)
            assertEquals(0, validations)
            state.value = state.value.copy(operation = null, activation = null)
        }
        compose.onNodeWithText("Your activation code: FIXTURE123").assertDoesNotExist()
        compose.onNodeWithText("Validate Twitch account").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, validations) }
    }

    @Test fun failedClearOffersForgetWhileAccountAccessRemainsBlocked() {
        var forgotten = 0
        compose.setContent { TachiaiPrototypeTheme {
            TwitchCatalogConnectionScreen(TwitchCatalogConnectionState(canForget = true,
                status = "Catalog account could not be cleared. Access remains blocked. Retry Forget."),
                { fail("Connect remains blocked") }, { fail("Validate remains blocked") }, { forgotten++ }, {}, {}, {})
        } }
        compose.onNodeWithText("Connect Twitch").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Validate Twitch account").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Forget Twitch account on this device").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, forgotten) }
    }

    // Persistent-device safe: only a Compose test host, synthetic public codes,
    // in-memory state and callbacks. No controller, stores or provider requests.
    @Test fun syntheticActivationQrRendersExactUrlAndTracksChallengeLifecycle() {
        val firstUri = "https://www.twitch.tv/activate?public=true&device-code=FIXTURE123"
        val state = mutableStateOf(TwitchCatalogConnectionState(operation = TwitchCatalogConnectionOperation.CONNECT,
            phase = DeviceAuthPhase.WAITING, activation = DeviceActivation("FIXTURE123", URI(firstUri))))
        var browser: String? = null
        compose.setContent { TachiaiPrototypeTheme {
            TwitchCatalogConnectionScreen(state.value, { fail("No real Connect") }, { fail("No real Validate") },
                { fail("No real Forget") }, { state.value = state.value.copy(operation = null, activation = null) },
                { browser = it }, {})
        } }
        assertEquals(firstUri, decodedRenderedQr())
        compose.onNodeWithText("Your activation code: FIXTURE123").performScrollTo().assertExists()
        listOf(DeviceAuthPhase.PAUSED, DeviceAuthPhase.BROWSER_UNAVAILABLE).forEach { phase ->
            compose.runOnIdle { state.value = state.value.copy(phase = phase) }
            compose.onNodeWithContentDescription("Twitch activation QR code").performScrollTo().assertExists()
            compose.onNodeWithText("Your activation code: FIXTURE123").performScrollTo().assertExists()
        }
        compose.onNodeWithText("Open Twitch activation in Chrome").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("com.android.chrome", browser) }

        val replacementUri = "https://www.twitch.tv/activate?device-code=SECOND123&public=true"
        compose.runOnIdle { state.value = state.value.copy(phase = DeviceAuthPhase.WAITING,
            activation = DeviceActivation("SECOND123", URI(replacementUri))) }
        assertEquals(replacementUri, decodedRenderedQr())
        compose.onNodeWithText("Your activation code: FIXTURE123").assertDoesNotExist()
        compose.onNodeWithText("Your activation code: SECOND123").performScrollTo().assertExists()

        listOf(DeviceAuthPhase.VALIDATING, DeviceAuthPhase.SUCCEEDED, DeviceAuthPhase.CANCELLED,
            DeviceAuthPhase.EXPIRED, DeviceAuthPhase.DENIED, DeviceAuthPhase.INVALID_CODE,
            DeviceAuthPhase.INVALID_RESPONSE).forEach { phase ->
            // A residual challenge must not make terminal instructions visible.
            compose.runOnIdle { state.value = state.value.copy(phase = phase) }
            compose.onNodeWithContentDescription("Twitch activation QR code").assertDoesNotExist()
            compose.onNodeWithText("Your activation code: SECOND123").assertDoesNotExist()
        }
        compose.runOnIdle { state.value = state.value.copy(phase = DeviceAuthPhase.WAITING,
            operation = TwitchCatalogConnectionOperation.VALIDATE) }
        compose.onNodeWithContentDescription("Twitch activation QR code").assertDoesNotExist()
        compose.onNodeWithText("Your activation code: SECOND123").assertDoesNotExist()

        val bareUri = "https://www.twitch.tv/activate"
        compose.runOnIdle { state.value = state.value.copy(operation = TwitchCatalogConnectionOperation.CONNECT,
            activation = DeviceActivation("MANUAL123", URI(bareUri))) }
        assertEquals(bareUri, decodedRenderedQr())
        compose.onNodeWithText("Your activation code: MANUAL123").performScrollTo().assertExists()
        compose.onNodeWithText("Cancel Twitch action").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Twitch activation QR code").assertDoesNotExist()
        compose.onNodeWithText("Your activation code: MANUAL123").assertDoesNotExist()
    }

    @Test fun syntheticEncoderFailureShowsFixedFallbackWithoutQr() {
        compose.setContent { TachiaiPrototypeTheme {
            TwitchActivationQr(DeviceActivation("FIXTURE123", URI("https://www.twitch.tv/activate"))) {
                throw IllegalStateException("Synthetic encoder failure")
            }
        } }
        compose.onNodeWithText("QR code unavailable. Use the activation code or open a browser.").assertExists()
        compose.onNodeWithContentDescription("Twitch activation QR code").assertDoesNotExist()
    }

    @Test fun syntheticQrFitsShortWideViewportAndRenderedPixelsDecode() {
        val uri = "https://www.twitch.tv/activate?public=true&device-code=FIXTURE123"
        var browser: String? = null; var cancellations = 0
        compose.setContent { TachiaiPrototypeTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    Box(Modifier.fillMaxWidth().height(200.dp).testTag("Synthetic short viewport")) {
                        TwitchCatalogConnectionScreen(TwitchCatalogConnectionState(
                            operation = TwitchCatalogConnectionOperation.CONNECT, phase = DeviceAuthPhase.WAITING,
                            activation = DeviceActivation("FIXTURE123", URI(uri))),
                            { fail("No real Connect") }, { fail("No real Validate") }, { fail("No real Forget") },
                            { cancellations++ }, { browser = it }, {})
                    }
                }
            }
        } }
        assertEquals(uri, decodedRenderedQr())
        val qr = compose.onNodeWithContentDescription("Twitch activation QR code").fetchSemanticsNode()
        val host = compose.onNodeWithTag("Synthetic short viewport").fetchSemanticsNode()
        assertEquals(qr.size.width, qr.size.height)
        assertTrue("Short viewport must constrain QR height", qr.size.width < host.size.width)
        assertFitsSyntheticViewport(compose.onNodeWithContentDescription("Twitch activation QR code"))
        assertFitsSyntheticViewport(compose.onNodeWithText("Your activation code: FIXTURE123"))
        val chrome = compose.onNodeWithText("Open Twitch activation in Chrome")
        assertFitsSyntheticViewport(chrome); chrome.performClick()
        val cancel = compose.onNodeWithText("Cancel Twitch action")
        assertFitsSyntheticViewport(cancel); cancel.performClick()
        compose.runOnIdle { assertEquals("com.android.chrome", browser); assertEquals(1, cancellations) }
    }

    private fun assertFitsSyntheticViewport(node: SemanticsNodeInteraction) {
        node.performScrollTo().assertIsDisplayed()
        val child = node.fetchSemanticsNode()
        val host = compose.onNodeWithTag("Synthetic short viewport").fetchSemanticsNode()
        assertTrue(child.size.width <= host.size.width); assertTrue(child.size.height <= host.size.height)
        assertTrue(child.positionInRoot.x >= host.boundsInRoot.left)
        assertTrue(child.positionInRoot.y >= host.boundsInRoot.top)
        assertTrue(child.positionInRoot.x + child.size.width <= host.boundsInRoot.right + 1f)
        assertTrue(child.positionInRoot.y + child.size.height <= host.boundsInRoot.bottom + 1f)
    }

    private fun decodedRenderedQr(): String {
        val bitmap = compose.onNodeWithContentDescription("Twitch activation QR code")
            .performScrollTo().captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(
            RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
    }
}
