package net.fstab.tachiai.feature.presentation

import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.AdapterView
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.fstab.tachiai.platform.media.NativeMixedPair
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativeQualityRequest
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeSeekPlan
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.presentation.ViewerQualityReadback
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.hasToString

// Provider-free focus/activation ownership, exercised with keyboard input in
// phone and TV configurations. No saved configuration or provider state changes.
@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePairViewerFocusTest {
    private class Member : NativePairMember {
        var onRead: (() -> Unit)? = null
        override fun timingSnapshot(): NativeTimingSnapshot {
            onRead?.invoke()
            return NativeTimingSnapshot(20_000, 60_000, 30_000, null, null, null,
                false, false, true, false, false, false, false, 3, 1f)
        }
        override fun seekToMs(targetMs: Long) = NativeSeekPlan(NativeSeekOutcome.REQUESTED, targetMs)
        override fun setTimingPlaying(playing: Boolean) = true
        override fun setVolume(volume: Float) = true
        override fun close() {}
    }
    private class Fixture(activity: ComponentActivity, television: Boolean) {
        val a = Member()
        val b = Member()
        val pair = NativeMixedPair(a, b, { true }, {})
        var busy = false
        var saving = false
        var advances = 0
        var pauses = 0
        val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
            uiMode = uiMode and Configuration.UI_MODE_TYPE_MASK.inv() or
                if (television) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
            orientation = Configuration.ORIENTATION_LANDSCAPE
        })
        val viewer = NativePairViewer(context, PlayerView(context), PlayerView(context), "Alpha", "Beta", { pair },
            {}, { pauses++ }, { advances++; busy = true }, {}, {}, {}, {},
            playbackBusy = { busy },
            qualityState = { _, _ -> ViewerQualityReadback(NativeQualityRequest.auto, null, saving = saving) },
            onSaveQuality = { _, _ -> saving = true })
        init { activity.setContentView(viewer); viewer.open() }
        fun button(label: String) = descendants(viewer).filterIsInstance<Button>().single { it.text == label }
        fun advance() = descendants(viewer).filterIsInstance<Button>().single {
            it.contentDescription?.toString() == "Advance Beta by 5 s relative to Alpha"
        }
        fun qualitySave() = descendants(viewer).filterIsInstance<Button>().single {
            it.contentDescription?.toString() == "Save Alpha video preference as stream default"
        }
        fun openTiming() { button("Timing").performClick() }
        fun displaceFocusDuringRefresh() {
            // Model Android relocating focus to the enabled transport control
            // while refresh temporarily disables the selected action.
            a.onRead = { button("Pause").requestFocus() }
            try { viewer.refresh() } finally { a.onRead = null }
        }
        fun close() { viewer.endSession(); pair.close() }
    }

    @Test fun busyAdvanceConsumesSelectAndRestoresSameStepBeforeNextActivation() = forEachInputConfiguration { scenario, fixture ->
        scenario.onActivity {
            fixture.openTiming()
            assertTrue(fixture.advance().requestFocus())
            fixture.advance().performClick()
            fixture.displaceFocusDuringRefresh()
            assertFalse(fixture.advance().isEnabled)
            assertTrue(fixture.button("Pause").isFocused)
            val now = SystemClock.uptimeMillis()
            dispatch(fixture.viewer, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            dispatch(fixture.viewer, KeyEvent(now, now + 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
            fixture.busy = false
            fixture.viewer.refresh()
            dispatch(fixture.viewer, KeyEvent(now, now + 2, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            assertEquals(1, fixture.advances)
            assertEquals(0, fixture.pauses)
        }
        idle()
        scenario.onActivity { assertTrue(fixture.advance().isFocused) }
        select(scenario, fixture)
        scenario.onActivity {
            assertEquals(2, fixture.advances)
            assertEquals(0, fixture.pauses)
        }
    }

    @Test fun explicitNavigationDuringBusyAllowsPauseAndWinsOverCompletion() = forEachInputConfiguration { scenario, fixture ->
        scenario.onActivity {
            fixture.openTiming()
            assertTrue(fixture.advance().requestFocus())
            fixture.advance().performClick()
            fixture.displaceFocusDuringRefresh()
            val now = SystemClock.uptimeMillis()
            dispatch(fixture.viewer, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0))
            dispatch(fixture.viewer, KeyEvent(now, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, 0))
            assertTrue(fixture.button("Pause").requestFocus())
        }
        select(scenario, fixture)
        scenario.onActivity {
            assertEquals(1, fixture.pauses)
            fixture.busy = false
            fixture.viewer.refresh()
        }
        idle()
        scenario.onActivity {
            assertFalse(fixture.advance().isFocused)
            assertTrue(fixture.button("Play").isFocused)
        }
    }

    @Test fun asynchronousFallbackFocusCannotActivatePauseWhileAdvanceIsBusy() = forEachInputConfiguration { scenario, fixture ->
        scenario.onActivity {
            fixture.openTiming()
            assertTrue(fixture.advance().requestFocus())
            fixture.advance().performClick()
            assertFalse(fixture.advance().isEnabled)
            fixture.viewer.post { fixture.button("Pause").requestFocus() }
        }
        idle()
        scenario.onActivity { assertTrue(fixture.button("Pause").isFocused) }
        select(scenario, fixture)
        scenario.onActivity {
            assertEquals(1, fixture.advances)
            assertEquals(0, fixture.pauses)
            fixture.busy = false
            fixture.viewer.refresh()
        }
        idle()
        scenario.onActivity { assertTrue(fixture.advance().isFocused) }
        select(scenario, fixture)
        scenario.onActivity {
            assertEquals(2, fixture.advances)
            assertEquals(0, fixture.pauses)
        }
    }

    @Test fun queuedPanelRestoreCannotStealNewerFocusAndTimingReopensAtSameStep() = forEachInputConfiguration { scenario, fixture ->
        scenario.onActivity {
            fixture.openTiming()
            assertTrue(fixture.advance().requestFocus())
        }
        idle()
        scenario.onActivity {
            assertTrue(fixture.advance().isFocused)
            fixture.viewer.handleBack()
        }
        idle()
        scenario.onActivity { fixture.openTiming() }
        idle()
        scenario.onActivity {
            assertTrue(fixture.advance().isFocused)
            fixture.button("Audio").performClick()
            assertTrue(fixture.button("More").requestFocus())
        }
        idle()
        scenario.onActivity { assertTrue(fixture.button("More").isFocused) }
    }

    @Test fun qualitySaveRetainsFocusAcrossAsyncCompletionAndPanelRecreation() = forEachInputConfiguration { scenario, fixture ->
        scenario.onActivity {
            val more = fixture.button("More")
            assertTrue(more.requestFocus()); more.performClick()
        }
        // PopupMenu owns an AdapterView: render/scroll the matching row before
        // asserting visibility, rather than targeting a clipped title child.
        onData(hasToString("Quality")).inRoot(isPlatformPopup()).perform(selectMenuItem())
        idle()
        lateinit var original: Button
        scenario.onActivity {
            original = fixture.qualitySave()
            assertTrue(original.requestFocus())
            original.performClick()
            assertFalse(original.isEnabled)
            fixture.saving = false
            fixture.viewer.refresh()
        }
        idle()
        scenario.onActivity {
            assertTrue(original.isFocused)
            fixture.viewer.handleBack()
        }
        idle()
        scenario.onActivity {
            val more = fixture.button("More")
            assertTrue(more.requestFocus()); more.performClick()
        }
        onData(hasToString("Quality")).inRoot(isPlatformPopup()).perform(selectMenuItem())
        idle()
        scenario.onActivity {
            assertNotSame(original, fixture.qualitySave())
            assertTrue(fixture.qualitySave().isFocused)
        }
    }

    @Test fun queuedPopupRestoreCannotOverrideNewerControlFocus() = forEachInputConfiguration { scenario, fixture ->
        scenario.onActivity { fixture.button("More").performClick() }
        idle()
        scenario.onActivity {
            assertTrue(fixture.viewer.handleBack())
            assertTrue(fixture.button("Audio").requestFocus())
        }
        idle()
        scenario.onActivity { assertTrue(fixture.button("Audio").isFocused) }
    }

    private fun forEachInputConfiguration(test: (ActivityScenario<ComponentActivity>, Fixture) -> Unit) {
        for (television in listOf(false, true)) ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, television) }
            idle()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            idle()
            try { test(scenario, fixture) } finally { scenario.onActivity { fixture.close() } }
        }
    }
    private fun select(scenario: ActivityScenario<ComponentActivity>, fixture: Fixture) {
        scenario.onActivity {
            val now = SystemClock.uptimeMillis()
            dispatch(fixture.viewer, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            dispatch(fixture.viewer, KeyEvent(now, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        }
        idle()
    }
    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    private fun selectMenuItem() = object : ViewAction {
        override fun getConstraints() = isDisplayed()
        override fun getDescription() = "Select the visible provider-free popup row without changing keyboard input mode"
        override fun perform(uiController: UiController, view: View) {
            var row = view
            while (row.parent is View && row.parent !is AdapterView<*>) row = row.parent as View
            val list = row.parent as AdapterView<*>
            val position = list.getPositionForView(row)
            assertTrue(list.performItemClick(row, position, list.getItemIdAtPosition(position)))
            uiController.loopMainThreadUntilIdle()
        }
    }
    private companion object {
        fun dispatch(viewer: NativePairViewer, event: KeyEvent) { viewer.dispatchKeyEvent(event) }
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    }
}
