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
                { connects++ }, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithTag("abema-account-identity").assertDoesNotExist()
        compose.onNodeWithText("I confirm this is the existing account I intend to connect.").assertDoesNotExist()
        compose.onNodeWithText("Connect your existing account through ABEMA’s pages. Sign-in and account selection happen in ABEMA. Tachiai never asks for your email or password.").performScrollTo().assertExists()
        compose.onNodeWithText("Connect ABEMA").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Connect ABEMA").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, connects) }
    }
    @Test fun savedAccountStatusDoesNotReintroduceIdentifierEntry() {
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(AbemaAccountConnectionState(status = AbemaAccountStatus.UNVERIFIED, progress = AbemaAccountProgress.IDLE),
                {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Saved ABEMA account needs fresh validation.").performScrollTo().assertExists()
        compose.onNodeWithText("Connect ABEMA").performScrollTo().assertIsEnabled()
    }
    @Test fun controllerReadinessReadingAndBusyStatePreventAnEnabledNoOp() {
        var state by androidx.compose.runtime.mutableStateOf(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE))
        var ready by androidx.compose.runtime.mutableStateOf(true); var connects = 0; var backs = 0
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(state, { connects++ }, {}, {}, {}, {}, {}, {}, { backs++ }, ready = ready)
        } }
        compose.onNodeWithText("Connect ABEMA").performScrollTo().assertIsEnabled()
        compose.runOnIdle { ready = false }
        compose.onNodeWithText("Connect ABEMA").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Validate saved ABEMA account").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Check ABEMA authorization").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("ABEMA account setup is unavailable. Return to providers and reopen this account.").performScrollTo().assertExists()
        compose.onNodeWithText("Back to providers").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, backs); ready = true; state = state.copy(progress = AbemaAccountProgress.READING) }
        compose.onNodeWithText("Connect ABEMA").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Reading protected account setup…").performScrollTo().assertExists()
        compose.runOnIdle { state = state.copy(progress = AbemaAccountProgress.IDLE, busy = true) }
        compose.onNodeWithText("Connect ABEMA").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Wait for the current ABEMA action to finish.").performScrollTo().assertExists()
        compose.runOnIdle { assertEquals(0, connects); state = state.copy(busy = false) }
        compose.onNodeWithText("Connect ABEMA").performScrollTo().assertIsEnabled()
    }
    @Test fun forgetInProgressBlocksAccountActionsAndNavigation() {
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(AbemaAccountConnectionState(progress = AbemaAccountProgress.FORGETTING, busy = true),
                {}, {}, {}, {}, {}, {}, {}, {})
        } }
        for (text in listOf("Connect ABEMA", "Validate saved ABEMA account", "Check ABEMA authorization",
            "Forget ABEMA account on this device", "Cancel ABEMA action", "Back to providers"))
            compose.onNodeWithText(text).performScrollTo().assertIsNotEnabled()
    }
    @Test fun approvalAndContinueAreDistinctExplicitActions() {
        var approvals = 0; var continuation = 0
        var state by androidx.compose.runtime.mutableStateOf(AbemaAccountConnectionState(progress = AbemaAccountProgress.APPROVAL_READY, canApprove = true))
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(state,
                {}, { approvals++ }, { continuation++ }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Open ABEMA approval").performScrollTo().performClick()
        compose.onNodeWithText("Continue after approval").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, approvals); assertEquals(0, continuation) }
        compose.runOnIdle { state = state.copy(progress = AbemaAccountProgress.WAITING_APPROVAL, canApprove = false, canContinue = true) }
        compose.onNodeWithText("Open ABEMA approval").assertDoesNotExist()
        compose.onNodeWithText("Continue after approval").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, approvals); assertEquals(1, continuation) }
    }
    @Test fun authorizationCheckUsesPlainActionAndOutcomeTextWithoutDiagnosticCategories() {
        var checks = 0; var state by androidx.compose.runtime.mutableStateOf(AbemaAccountConnectionState(progress = AbemaAccountProgress.ENFORCEMENT_PROVEN))
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(state, {}, {}, {}, {}, { checks++ }, {}, {}, {})
        } }
        compose.onNodeWithText("ABEMA authorization check passed.").performScrollTo().assertExists()
        compose.onNodeWithText("Check ABEMA authorization").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, checks); state = state.copy(progress = AbemaAccountProgress.ENFORCEMENT_NOT_PROVEN) }
        compose.onNodeWithText("ABEMA authorization could not be confirmed.").performScrollTo().assertExists()
        compose.onNodeWithText("Check profile authorization enforcement").assertDoesNotExist()
        compose.onNodeWithText("PROFILE_AUTH_ENFORCEMENT_ESTABLISHED").assertDoesNotExist()
        compose.onNodeWithText("PROFILE_AUTH_ENFORCEMENT_NOT_ESTABLISHED").assertDoesNotExist()
    }
}
