package net.fstab.tachiai.feature.connections

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
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
    @Test fun intendedAccountIsVisibleWithoutConfirmationAndNormalizedInputIsClearedAfterDispatch() {
        var expected: String? = null
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE),
                { expected = it }, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Connect ABEMA").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("abema-account-identity").performScrollTo().performTextInput("  fixture-account  ")
        compose.onNodeWithTag("abema-account-identity").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithText("I confirm this is the existing account I intend to connect.").assertDoesNotExist()
        compose.onNodeWithText("Connect ABEMA").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Connect ABEMA").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("fixture-account", expected) }
        compose.onNodeWithTag("abema-account-identity").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }
    @Test fun invalidInputShowsGuidanceAndAnOverlongIdIsNotSilentlyShortened() {
        var connects = 0
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE),
                { connects++ }, {}, {}, {}, {}, {}, {}, {})
        } }
        for (invalid in listOf("invalid account", "  ", "fixture@example.test", "a".repeat(257))) {
            compose.onNodeWithTag("abema-account-identity").performScrollTo().performTextReplacement(invalid)
            compose.onNodeWithText("Connect ABEMA").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("abema-account-identity").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(invalid)))
            compose.onNodeWithText("Use 1–256 letters, numbers, underscores or hyphens. Outer whitespace is ignored.").performScrollTo().assertExists()
        }
        compose.runOnIdle { assertEquals(0, connects) }
        compose.onNodeWithTag("abema-account-identity").performScrollTo().performTextReplacement("a".repeat(256))
        compose.onNodeWithText("Connect ABEMA").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Use 1–256 letters, numbers, underscores or hyphens. Outer whitespace is ignored.").assertDoesNotExist()
    }
    @Test fun controllerReadinessReadingAndBusyStatePreventAnEnabledNoOpAndKeepInput() {
        var state by androidx.compose.runtime.mutableStateOf(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE))
        var ready by androidx.compose.runtime.mutableStateOf(true); var connects = 0; var backs = 0
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(state, { connects++ }, {}, {}, {}, {}, {}, {}, { backs++ }, ready = ready)
        } }
        compose.onNodeWithTag("abema-account-identity").performScrollTo().performTextInput("fixture-account")
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
        compose.onNodeWithTag("abema-account-identity").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("fixture-account")))
        compose.runOnIdle { assertEquals(0, connects); state = state.copy(busy = false) }
        compose.onNodeWithText("Connect ABEMA").performScrollTo().assertIsEnabled()
    }
    @Test fun accountIdentityIsNeverRestoredAndGenericStateContainsOnlyFiniteCategories() {
        val restoration = StateRestorationTester(compose)
        val state = AbemaAccountConnectionState(status = AbemaAccountStatus.UNVERIFIED, progress = AbemaAccountProgress.IDLE)
        restoration.setContent { TachiaiPrototypeTheme { AbemaAccountConnectionScreen(state, {}, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("abema-account-identity").performScrollTo().performTextInput("fixture-private-account")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("abema-account-identity").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertFalse(state.toString().contains("fixture-private-account"))
        compose.onNodeWithText("Saved ABEMA account needs fresh validation.").performScrollTo().assertExists()
    }
    @Test fun approvalAndContinueAreDistinctExplicitActionsAndForgetBlocksReturn() {
        var approvals = 0; var continuation = 0
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(AbemaAccountConnectionState(progress = AbemaAccountProgress.APPROVAL_READY, canApprove = true),
                {}, { approvals++ }, { continuation++ }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Open ABEMA approval").performScrollTo().performClick()
        compose.onNodeWithText("Continue after approval").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, approvals); assertEquals(0, continuation) }
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
