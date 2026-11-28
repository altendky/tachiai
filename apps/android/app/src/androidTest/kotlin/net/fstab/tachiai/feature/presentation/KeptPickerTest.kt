package net.fstab.tachiai.feature.presentation

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

// #790: the picker stays composed behind the viewer. Shown again, it takes focus
// as a new picker did, not where focus was before the viewer opened.
// Synthetic presentation only: no stores, accounts or provider requests.
class KeptPickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // Remote keys leave touch mode, where a view shown again could take focus.
    @Before fun leaveTouchMode() = InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    @After fun restoreTouchMode() = InstrumentationRegistry.getInstrumentation().setInTouchMode(true)

    private fun content(): ViewGroup = compose.activity.findViewById(android.R.id.content)

    @Composable private fun Picker(television: Boolean, covered: Boolean) {
        val input = LocalInputModeManager.current
        if (television) SideEffect { input.requestInputMode(InputMode.Keyboard) }
        TachiaiPrototypeTheme(television = television) {
            PrototypeSourcePicker(null, onConnections = {}, onProviders = {}, covered = covered, onWatch = {})
        }
    }

    // What the Activity does around a viewer: hide the picker, cover it, then
    // show it again and uncover it.
    private fun viewerRoundTrip(cover: (Boolean) -> Unit) {
        compose.runOnUiThread { assertTrue(hideKeptPicker(content())); cover(true) }
        compose.waitForIdle()
        compose.runOnUiThread { revealKeptPicker(content()); cover(false) }
        compose.waitForIdle()
    }

    @Test fun televisionPickerEntersRoutesAgainInsteadOfRestoringItsLastFocus() {
        var covered by mutableStateOf(false)
        compose.setContent { Picker(television = true, covered = covered) }
        compose.onNodeWithText("Routes").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Providers").assertIsFocused()
        viewerRoundTrip { covered = it }
        compose.onNodeWithText("Routes").assertIsFocused()
        compose.onNodeWithText("Providers").assertIsNotFocused()
    }

    @Test fun phonePickerIsNotHandedFocusWhenShownAgain() {
        var covered by mutableStateOf(false)
        compose.setContent { Picker(television = false, covered = covered) }
        compose.onNodeWithText("Providers").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Providers").assertIsFocused()
        viewerRoundTrip { covered = it }
        compose.onAllNodes(isFocused()).assertCountEquals(0)
    }
}
