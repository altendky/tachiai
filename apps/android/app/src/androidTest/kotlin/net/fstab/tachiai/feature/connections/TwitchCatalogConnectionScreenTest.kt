package net.fstab.tachiai.feature.connections

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
}
