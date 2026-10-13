package net.fstab.tachiai.feature.connections

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
        compose.onNodeWithText("Connect your existing account using ABEMA’s email sign-in. Enter your email address and emailed code only on ABEMA’s pages.").performScrollTo().assertExists()
        compose.onNodeWithText("Connect ABEMA by email").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Connect ABEMA by email").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, connects) }
    }
    @Test fun savedAccountStatusDoesNotReintroduceIdentifierEntry() {
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(AbemaAccountConnectionState(status = AbemaAccountStatus.UNVERIFIED, progress = AbemaAccountProgress.IDLE),
                {}, {}, {}, {}, {}, {})
        } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Saved ABEMA account needs fresh validation.").performScrollTo().assertExists()
        compose.onNodeWithText("Connect ABEMA by email").performScrollTo().assertIsEnabled()
    }
    @Test fun controllerReadinessReadingAndBusyStatePreventAnEnabledNoOp() {
        var state by androidx.compose.runtime.mutableStateOf(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE))
        var ready by androidx.compose.runtime.mutableStateOf(true); var connects = 0; var backs = 0
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(state, { connects++ }, {}, {}, {}, {}, { backs++ }, ready = ready)
        } }
        compose.onNodeWithText("Connect ABEMA by email").performScrollTo().assertIsEnabled()
        compose.runOnIdle { ready = false }
        compose.onNodeWithText("Connect ABEMA by email").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Validate saved ABEMA account").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Check ABEMA authorization").assertDoesNotExist()
        compose.onNodeWithText("ABEMA account setup is unavailable. Return to providers and reopen this account.").performScrollTo().assertExists()
        compose.onNodeWithText("Back to providers").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, backs); ready = true; state = state.copy(progress = AbemaAccountProgress.READING) }
        compose.onNodeWithText("Connect ABEMA by email").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Reading protected account setup…").performScrollTo().assertExists()
        compose.runOnIdle { state = state.copy(progress = AbemaAccountProgress.IDLE, busy = true) }
        compose.onNodeWithText("Connect ABEMA by email").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Wait for the current ABEMA action to finish.").performScrollTo().assertExists()
        compose.runOnIdle { assertEquals(0, connects); state = state.copy(busy = false) }
        compose.onNodeWithText("Connect ABEMA by email").performScrollTo().assertIsEnabled()
    }
    @Test fun forgetInProgressBlocksAccountActionsAndNavigation() {
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(AbemaAccountConnectionState(progress = AbemaAccountProgress.FORGETTING, busy = true),
                {}, {}, {}, {}, {}, {})
        } }
        for (text in listOf("Connect ABEMA by email", "Validate saved ABEMA account",
            "Forget ABEMA account on this device", "Cancel ABEMA action", "Back to providers"))
            compose.onNodeWithText(text).performScrollTo().assertIsNotEnabled()
    }
    @Test fun emailCompletionIsOfferedAfterTheProtectedBrowserHandoff() {
        var completions = 0
        var state by androidx.compose.runtime.mutableStateOf(AbemaAccountConnectionState(progress = AbemaAccountProgress.WAITING_APPROVAL))
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(state, {}, { completions++ }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Open ABEMA approval").assertDoesNotExist()
        compose.onNodeWithText("Finish email connection").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(canContinue = true) }
        compose.onNodeWithText("Finish email connection").performScrollTo().performClick()
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

    @Test fun rememberedLoginAndFreshSignInAreSeparateExplicitActionsWithBusyGuards() {
        var connects = 0; var resets = 0
        var ready by androidx.compose.runtime.mutableStateOf(true)
        var state by androidx.compose.runtime.mutableStateOf(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE))
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(state, { connects++ }, {}, {}, {}, {}, {}, ready = ready,
                onSignInAgain = { resets++ })
        } }
        compose.onNodeWithText("Connect ABEMA by email").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, connects); assertEquals(0, resets); ready = false }
        compose.onNodeWithText("Sign in again").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { ready = true; state = state.copy(busy = true) }
        compose.onNodeWithText("Sign in again").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(busy = false) }
        compose.onNodeWithText("Sign in again").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, connects); assertEquals(1, resets) }
    }
}
