package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.provider.twitch.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TwitchProviderLoginScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingAvailableAndExpiredStatusRequireExplicitLoginActions() {
        val state = mutableStateOf(TwitchProviderLoginState(storage = SavedAuthorizationState.MISSING))
        var connections = 0; var validations = 0; var forgotten = 0
        compose.setContent { TachiaiPrototypeTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TwitchProviderLoginSection(state.value, onConnect = { connections++ },
                    onRevalidate = { validations++ }, onForget = { forgotten++ }, onCancel = {}, onCopy = {}, onBrowser = {})
            }
        } }
        compose.onNodeWithText("No saved Twitch login.").assertExists()
        compose.onNodeWithText("Revalidate saved Twitch login").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Connect Twitch").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, connections); assertEquals(0, validations); assertEquals(0, forgotten)
            state.value = TwitchProviderLoginState(storage = SavedAuthorizationState.AVAILABLE)
        }
        compose.onNodeWithText("Reconnect Twitch").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Revalidate saved Twitch login").performScrollTo().performClick()
        compose.onNodeWithText("Forget Twitch login on this device").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, validations); assertEquals(1, forgotten)
            state.value = TwitchProviderLoginState(storage = SavedAuthorizationState.EXPIRED)
        }
        compose.onNodeWithText("Saved Twitch login expired. Reconnect to continue.").performScrollTo().assertExists()
        compose.onNodeWithText("Revalidate saved Twitch login").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Reconnect Twitch").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Forget clears only this saved grant", substring = true).performScrollTo().assertExists()
    }

    @Test fun pendingLoginCanBeCancelledOrForgottenButNotStartedTwice() {
        var cancellations = 0; var forgotten = 0
        compose.setContent { TachiaiPrototypeTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TwitchProviderLoginSection(TwitchProviderLoginState(storage = SavedAuthorizationState.AVAILABLE,
                    operation = ProviderLoginOperation.CONNECT, phase = DeviceAuthPhase.WAITING),
                    onConnect = { fail("Concurrent connect") }, onRevalidate = { fail("Concurrent revalidation") },
                    onForget = { forgotten++ }, onCancel = { cancellations++ }, onCopy = {}, onBrowser = {})
            }
        } }
        compose.onNodeWithText("Reconnect Twitch").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Revalidate saved Twitch login").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Cancel login action").performScrollTo().performClick()
        compose.onNodeWithText("Forget Twitch login on this device").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, cancellations); assertEquals(1, forgotten) }
    }
}
