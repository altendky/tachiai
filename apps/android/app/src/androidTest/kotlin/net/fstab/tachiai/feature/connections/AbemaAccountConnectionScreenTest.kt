package net.fstab.tachiai.feature.connections

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.provider.abema.account.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// Synthetic presentation only; no provider binding, storage, login or WebView.
class AbemaAccountConnectionScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun menu() = compose.onNodeWithContentDescription("ABEMA account actions").performClick()
    private fun dismissMenuWithEscape() {
        compose.onNode(isPopup()).assertExists()
        // Android's popup window handles Escape outside the Compose owner.
        // Send through the focused window instead of bypassing it at the node.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ESCAPE)
        compose.waitForIdle()
        compose.onNode(isPopup()).assertDoesNotExist()
    }

    @Test fun compactPrimaryUsesProviderFlowWithoutLocalCredentialFieldsOrPermanentActionBank() {
        var connects = 0
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE),
                { connects++ }, {}, {}, onSignInAgain = {})
        } }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithText("Not connected").assertExists()
        compose.onNodeWithText("Connect by email").performClick()
        compose.runOnIdle { assertEquals(1, connects) }
        for (label in listOf("Sign in again", "Check connection", "Forget account…", "Finish connection", "Cancel"))
            compose.onNodeWithText(label).assertDoesNotExist()
        compose.onNodeWithText("Enter your email address and emailed code", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Info about ABEMA account").performClick()
        compose.onNodeWithText("Enter your email address and emailed code", substring = true).assertExists()
        compose.onNodeWithText("Close").performClick()
    }

    @Test fun connectedOpenAccountUsesSameCallbackAndEmbeddedSectionHasNoBackRow() {
        var opens = 0
        compose.setContent { TachiaiPrototypeTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                Column(Modifier.width(320.dp)) {
                    AbemaAccountConnectionScreen(AbemaAccountConnectionState(AbemaAccountStatus.CONNECTED, AbemaAccountProgress.IDLE),
                        { opens++ }, {}, {})
                }
            }
        } }
        compose.onNodeWithText("Connected").assertExists()
        val button = compose.onNode(hasClickAction() and hasText("Open account"))
        val bounds = button.fetchSemanticsNode().boundsInRoot
        val section = compose.onNodeWithTag("abema-account-section").fetchSemanticsNode().boundsInRoot
        assertEquals(with(compose.density) { 40.dp.toPx() }, bounds.height, 1f)
        button.assertTouchHeightIsEqualTo(48.dp)
        assertTrue("The button clears the following divider", section.bottom - bounds.bottom >= with(compose.density) { 8.dp.toPx() })
        assertEquals(section.right, bounds.right, 1f)
        assertTrue("The action fits its label instead of filling its column", bounds.width < section.width / 2)
        button.performClick()
        compose.onNodeWithText("Connect by email").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to providers").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun overflowActionsCloseTheMenuAndForgetRequiresExplicitConfirmation() {
        var resets = 0; var checks = 0; var forgets = 0
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE),
                {}, { checks++ }, { forgets++ }, onSignInAgain = { resets++ })
        } }
        menu(); compose.onNodeWithText("Sign in again").performClick()
        compose.onNodeWithText("Check connection").assertDoesNotExist()
        menu(); compose.onNodeWithText("Check connection").performClick()
        menu(); compose.onNodeWithText("Forget account…").performClick()
        compose.onNodeWithText("Forget ABEMA account?").assertExists()
        compose.runOnIdle { assertEquals(1, resets); assertEquals(1, checks); assertEquals(0, forgets) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Forget ABEMA account?").assertDoesNotExist()
        menu(); compose.onNodeWithText("Forget account…").performClick()
        compose.onNodeWithText("Forget account").performClick()
        compose.runOnIdle { assertEquals(1, forgets) }
    }

    @Test fun keyboardCanActivateAndDismissOverflowWithoutRunningAnAction() {
        var callbacks = 0
        lateinit var inputMode: InputModeManager
        compose.setContent { TachiaiPrototypeTheme {
            inputMode = LocalInputModeManager.current
            AbemaAccountConnectionScreen(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE),
                { callbacks++ }, { callbacks++ }, { callbacks++ }, onSignInAgain = { callbacks++ })
        } }
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        val button = compose.onNodeWithContentDescription("ABEMA account actions")
        button.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        button.performKeyInput { pressKey(Key.Enter) }
        val item = compose.onNodeWithText("Check connection").assertExists()
        item.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        item.assertIsFocused()
        dismissMenuWithEscape()
        compose.onNodeWithText("Check connection").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, callbacks) }
    }

    @Test fun readingBusyReadinessAndPendingSaveGuardActionsWithoutAddingRows() {
        var state by mutableStateOf(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE))
        var ready by mutableStateOf(true); var enabled by mutableStateOf(true)
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(state, {}, {}, {}, ready = ready, enabled = enabled, onSignInAgain = {})
        } }
        val initial = compose.onNodeWithTag("abema-account-section").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { ready = false }
        compose.onNodeWithText("Connect by email").assertIsNotEnabled()
        compose.onNodeWithContentDescription("ABEMA account actions").assertIsNotEnabled()
        compose.onNodeWithText("Unavailable").assertExists()
        compose.runOnIdle { ready = true; state = state.copy(progress = AbemaAccountProgress.READING) }
        compose.onNodeWithText("Reading…").assertExists()
        compose.onNodeWithText("Connect by email").assertIsNotEnabled()
        menu(); compose.onNodeWithText("Check connection").assertIsNotEnabled()
        compose.onNodeWithText("Sign in again").assertIsNotEnabled()
        dismissMenuWithEscape()
        compose.runOnIdle { state = state.copy(progress = AbemaAccountProgress.IDLE, busy = true) }
        compose.onNodeWithText("Working…").assertExists()
        compose.onNodeWithText("Connect by email").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(busy = false); enabled = false }
        compose.onNodeWithText("Connect by email").assertIsNotEnabled()
        compose.onNodeWithContentDescription("ABEMA account actions").assertIsNotEnabled()
        assertEquals(initial, compose.onNodeWithTag("abema-account-section").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun narrowLargeFontSectionKeepsHeightAndActionPositionAcrossAllStatusesAndProgress() {
        var state by mutableStateOf(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE))
        compose.setContent { TachiaiPrototypeTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Column(Modifier.width(320.dp)) { AbemaAccountConnectionScreen(state, {}, {}, {}, onSignInAgain = {}) }
            }
        } }
        val before = compose.onNodeWithTag("abema-account-section").fetchSemanticsNode().boundsInRoot
        val button = compose.onNode(hasClickAction() and hasText("Connect by email")).fetchSemanticsNode().boundsInRoot
        for (status in AbemaAccountStatus.entries) for (progress in listOf(AbemaAccountProgress.IDLE,
            AbemaAccountProgress.READING, AbemaAccountProgress.PREPARING, AbemaAccountProgress.CHECKING,
            AbemaAccountProgress.WAITING_APPROVAL, AbemaAccountProgress.FORGETTING)) {
            compose.runOnIdle { state = state.copy(status = status, progress = progress, busy = progress != AbemaAccountProgress.IDLE) }
            assertEquals(before, compose.onNodeWithTag("abema-account-section").fetchSemanticsNode().boundsInRoot)
            val label = if (status == AbemaAccountStatus.CONNECTED) "Open account" else "Connect by email"
            val bounds = compose.onNode(hasClickAction() and hasText(label)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(before.right, bounds.right, 1f)
            assertEquals(button.center.y, bounds.center.y, 1f)
            assertTrue("The label stays inside its stable row", bounds.top >= before.top && bounds.bottom <= before.bottom - with(compose.density) { 8.dp.toPx() })
            compose.onNodeWithContentDescription("Info about ABEMA account").assertIsDisplayed()
        }
    }

    @Test fun flowFinishAndCancelRemainSeparateFromSettingsAndRespectActiveState() {
        var state by mutableStateOf(AbemaAccountConnectionState(progress = AbemaAccountProgress.WAITING_APPROVAL))
        var finishes = 0; var cancels = 0
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionFlow(state, { finishes++ }, { cancels++ })
        } }
        compose.onNodeWithText("Finish connection").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsEnabled()
        compose.runOnIdle { state = state.copy(canContinue = true) }
        compose.onNodeWithText("Finish connection").performClick()
        compose.runOnIdle { assertEquals(1, finishes); state = state.copy(canContinue = false, busy = true, progress = AbemaAccountProgress.CHECKING) }
        compose.onNodeWithText("Finish connection").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
    }

    @Test fun openingStaysInlineAndOnlySlowConnectionsShowProgressAndCancelWithoutMovingSections() {
        var pending by mutableStateOf(false)
        var state by mutableStateOf(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE))
        var cancels = 0; var connects = 0
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionHost(state, pending, true, {}, { pending = false }) {
                AbemaAccountConnectionScreen(state, { connects++ }, {}, {},
                    onCancelConnection = if (pending) {{ cancels++; pending = false }} else null)
            }
        } }
        val before = compose.onNodeWithTag("abema-account-section").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            // Even the preceding IDLE snapshot must not produce a modal flash.
            compose.runOnIdle { pending = true }
            compose.mainClock.advanceTimeByFrame()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNodeWithText("Connect by email").assertIsNotEnabled()
            compose.runOnIdle { state = state.copy(progress = AbemaAccountProgress.PREPARING, busy = true) }
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("abema-connection-progress").assertDoesNotExist()
            compose.onNodeWithText("Cancel").assertDoesNotExist()
            compose.runOnIdle { pending = false }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { pending = true }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("abema-connection-progress").assertDoesNotExist()
            compose.mainClock.advanceTimeBy(150)
            compose.onNodeWithTag("abema-connection-progress").assertIsDisplayed()
            assertEquals(before, compose.onNodeWithTag("abema-account-section").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithText("Cancel").assertIsEnabled().performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("abema-connection-progress").assertDoesNotExist()
            compose.onNodeWithText("Cancel").assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, cancels); assertEquals(0, connects) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun hostDoesNotShowAModalUntilThereIsAnActionableManualFinish() {
        var state by mutableStateOf(AbemaAccountConnectionState(progress = AbemaAccountProgress.PREPARING, busy = true))
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionHost(state, true, true, {}, {}) { Text("Account settings") }
        } }
        for (progress in listOf(AbemaAccountProgress.PREPARING, AbemaAccountProgress.WAITING_APPROVAL,
            AbemaAccountProgress.CHECKING, AbemaAccountProgress.IDLE)) {
            compose.runOnIdle { state = state.copy(progress = progress) }
            compose.onNode(isDialog()).assertDoesNotExist()
        }
        compose.runOnIdle { state = state.copy(progress = AbemaAccountProgress.WAITING_APPROVAL, busy = false, canContinue = true) }
        compose.onNodeWithText("Finish connection").assertIsEnabled()
    }

    @Test fun systemBackCancelsPendingOpeningWithoutMountedAccountContent() {
        var pending by mutableStateOf(true)
        var cancels = 0
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionHost(
                AbemaAccountConnectionState(progress = AbemaAccountProgress.PREPARING, busy = true),
                pending, true, {}, { cancels++; pending = false }) {
                Text("Account section is off-screen")
            }
        } }
        compose.onNode(isDialog()).assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.runOnIdle { assertEquals(1, cancels); assertFalse(pending) }
    }

    @Test fun mountedFlowSurvivesLazyAccountContentDisposalAndProviderPauseReturn() {
        var includeSection by mutableStateOf(true); var foreground by mutableStateOf(true); var flow by mutableStateOf(true)
        var cancels = 0
        val state = AbemaAccountConnectionState(progress = AbemaAccountProgress.WAITING_APPROVAL, canContinue = true)
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionHost(state, flow, foreground, { flow = false }, { cancels++; flow = false }) {
                LazyColumn(Modifier.height(180.dp), state = rememberLazyListState()) {
                    if (includeSection) item { AbemaAccountConnectionScreen(state, {}, {}, {}) }
                    items(20) { Text("Configured stream $it", Modifier.height(64.dp)) }
                }
            }
        } }
        compose.onNodeWithText("Finish connection").assertIsDisplayed()
        compose.runOnIdle { includeSection = false; foreground = false }
        compose.onNodeWithText("Finish connection").assertDoesNotExist()
        compose.runOnIdle { foreground = true }
        compose.onNodeWithText("Finish connection").assertIsDisplayed().performClick()
        compose.onNodeWithText("Finish connection").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, cancels); flow = true }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
    }

    @Test fun standaloneHeadingUsesLeadingBackAndTrailingInfoWithoutTextBackRow() {
        var backs = 0
        compose.setContent { TachiaiPrototypeTheme {
            AbemaAccountConnectionScreen(AbemaAccountConnectionState(progress = AbemaAccountProgress.IDLE),
                {}, {}, {}, onBack = { backs++ })
        } }
        val back = compose.onNodeWithContentDescription("Back to providers").assertIsDisplayed()
        val title = compose.onNodeWithText("ABEMA account").fetchSemanticsNode().boundsInRoot
        val info = compose.onNodeWithContentDescription("Info about ABEMA account").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(back.fetchSemanticsNode().boundsInRoot.right <= title.left)
        assertTrue(info.left >= title.right)
        compose.onNodeWithText("Back to providers").assertDoesNotExist()
        back.performClick(); compose.runOnIdle { assertEquals(1, backs) }
    }
}
