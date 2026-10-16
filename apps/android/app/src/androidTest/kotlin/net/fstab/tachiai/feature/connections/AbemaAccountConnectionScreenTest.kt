package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.provider.abema.account.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// Synthetic UI only; no provider binding, authorization storage or WebView.
class AbemaAccountConnectionScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun connectNeedsNoLocalIdentifierAndDelegatesSignInToAbema() {
        var connects = 0
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE),
                { connects++ }, {}, {}, {}, {}, {})
        } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithTag("abema-account-identity").assertDoesNotExist()
        compose.onNodeWithText("I confirm this is the existing account I intend to connect.").assertDoesNotExist()
        compose.onNodeWithText("Enter your email address and emailed code", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Info about ABEMA account").performScrollTo().performClick()
        compose.onNodeWithText("Enter your email address and emailed code", substring = true).assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Connect by email").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Connect by email").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, connects) }
    }
    @Test fun savedAccountStatusDoesNotReintroduceIdentifierEntry() {
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(AbemaAccountConnectionState(status = AbemaAccountStatus.UNVERIFIED, progress = AbemaAccountProgress.IDLE),
                {}, {}, {}, {}, {}, {})
        } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Saved ABEMA account needs fresh validation.").performScrollTo().assertExists()
        compose.onNodeWithText("Connect by email").performScrollTo().assertIsEnabled()
    }
    @Test fun embeddedAccountHasNoNestedBackNavigationAndKeepsStatusVisible() {
        compose.setContent { TachiaiPrototypeTheme {
            androidx.compose.foundation.lazy.LazyColumn {
                item { AbemaAccountConnectionScreen(AbemaAccountConnectionState(status = AbemaAccountStatus.RECONNECT_REQUIRED,
                    progress = AbemaAccountProgress.IDLE), {}, {}, {}, {}, {}) }
                item { androidx.compose.material3.Text("Configured streams fixture") }
            }
        } }
        compose.onNodeWithText("ABEMA account needs reconnection.").assertExists()
        compose.onNodeWithText("Back to providers").assertDoesNotExist()
        compose.onNodeWithText("Configured streams fixture").performScrollTo().assertExists()
    }
    @Test fun pendingProviderSaveDisablesAccountActionsWithoutChangingAccountStatus() {
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(AbemaAccountConnectionState(status = AbemaAccountStatus.CONNECTED,
                progress = AbemaAccountProgress.IDLE, canContinue = true), {}, {}, {}, {}, {}, {},
                onSignInAgain = {}, enabled = false)
        } }
        compose.onNodeWithText("ABEMA account validated for this instance.").assertExists()
        compose.onNodeWithText("ABEMA account setup is unavailable.", substring = true).assertDoesNotExist()
        for (label in listOf("Connect by email", "Finish connection", "Sign in again",
            "Validate account", "Forget account", "Cancel", "Back to providers"))
            compose.onNodeWithText(label).performScrollTo().assertIsNotEnabled()
    }
    @Test fun controllerReadinessReadingAndBusyStatePreventAnEnabledNoOp() {
        var state by androidx.compose.runtime.mutableStateOf(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE))
        var ready by androidx.compose.runtime.mutableStateOf(true); var connects = 0; var backs = 0
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(state, { connects++ }, {}, {}, {}, {}, { backs++ }, ready = ready)
        } }
        compose.onNodeWithText("Connect by email").performScrollTo().assertIsEnabled()
        compose.runOnIdle { ready = false }
        compose.onNodeWithText("Connect by email").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Validate account").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Check ABEMA authorization").assertDoesNotExist()
        compose.onNodeWithText("ABEMA account setup is unavailable. Return to providers and reopen this account.").performScrollTo().assertExists()
        compose.onNodeWithText("Back to providers").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, backs); ready = true; state = state.copy(progress = AbemaAccountProgress.READING) }
        compose.onNodeWithText("Connect by email").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Reading protected account setup…").performScrollTo().assertExists()
        compose.runOnIdle { state = state.copy(progress = AbemaAccountProgress.IDLE, busy = true) }
        compose.onNodeWithText("Connect by email").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Wait for the current ABEMA action to finish.").performScrollTo().assertExists()
        compose.runOnIdle { assertEquals(0, connects); state = state.copy(busy = false) }
        compose.onNodeWithText("Connect by email").performScrollTo().assertIsEnabled()
    }
    @Test fun forgetInProgressBlocksAccountActionsAndNavigation() {
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(AbemaAccountConnectionState(progress = AbemaAccountProgress.FORGETTING, busy = true),
                {}, {}, {}, {}, {}, {})
        } }
        for (text in listOf("Connect by email", "Validate account",
            "Forget account", "Cancel", "Back to providers"))
            compose.onNodeWithText(text).performScrollTo().assertIsNotEnabled()
    }
    @Test fun emailCompletionIsOfferedAfterTheProtectedBrowserHandoff() {
        var completions = 0
        var state by androidx.compose.runtime.mutableStateOf(AbemaAccountConnectionState(progress = AbemaAccountProgress.WAITING_APPROVAL))
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(state, {}, { completions++ }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Open ABEMA approval").assertDoesNotExist()
        compose.onNodeWithText("Finish connection").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(canContinue = true) }
        compose.onNodeWithText("Finish connection").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, completions) }
    }
    @Test fun accountSetupDoesNotOfferDiagnosticAuthorizationProbes() {
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE),
                {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Check ABEMA authorization").assertDoesNotExist()
        compose.onNodeWithText("Check profile authorization enforcement").assertDoesNotExist()
    }

    @Test fun accountActionsStayPresentAcrossConnectionStatesAndWrapWithLargeText() {
        var state by androidx.compose.runtime.mutableStateOf(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE))
        compose.setContent { TachiaiPrototypeTheme {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, 1.5f)) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier
                    .width(320.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    AbemaAccountConnectionScreen(state, {}, {}, {}, {}, {}, onSignInAgain = {})
                }
            }
        } }
        for (status in listOf(AbemaAccountStatus.MISSING, AbemaAccountStatus.CONNECTED, AbemaAccountStatus.RECONNECT_REQUIRED)) {
            compose.runOnIdle { state = state.copy(status = status) }
            for (label in listOf("Connect by email", "Sign in again", "Validate account", "Finish connection", "Forget account", "Cancel"))
                compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Finish connection").assertIsNotEnabled()
        }
    }

    @Test fun rememberedLoginAndFreshSignInAreSeparateExplicitActionsWithBusyGuards() {
        var connects = 0; var resets = 0
        var ready by androidx.compose.runtime.mutableStateOf(true)
        var state by androidx.compose.runtime.mutableStateOf(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE))
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(state, { connects++ }, {}, {}, {}, {}, {}, ready = ready,
                onSignInAgain = { resets++ })
        } }
        compose.onNodeWithText("Connect by email").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, connects); assertEquals(0, resets); ready = false }
        compose.onNodeWithText("Sign in again").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { ready = true; state = state.copy(busy = true) }
        compose.onNodeWithText("Sign in again").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(busy = false) }
        compose.onNodeWithText("Sign in again").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, connects); assertEquals(1, resets) }
    }
}
