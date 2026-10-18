package net.fstab.tachiai.feature.presentation

import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.platform.media.NativeMixedPair
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeSeekPlan
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

// Native remote UI with in-memory member snapshots, not provider/device support.
@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePairViewerRemoteTest {
    private class Member(playing: Boolean) : NativePairMember {
        var sample = NativeTimingSnapshot(20_000, 60_000, 30_000, null, null, null,
            false, false, true, true, false, playing, playing, 3, 1f)
        var volumeCalls = 0
        override fun timingSnapshot() = sample
        override fun seekToMs(targetMs: Long) = NativeSeekPlan(NativeSeekOutcome.REQUESTED, targetMs)
        override fun setTimingPlaying(playing: Boolean): Boolean {
            sample = sample.copy(playing = playing, playWhenReady = playing)
            return true
        }
        override fun setVolume(volume: Float): Boolean { volumeCalls++; return true }
        override fun close() {}
    }

    private class Fixture(activity: ComponentActivity, playing: Boolean) {
        val a = Member(playing)
        val b = Member(playing)
        val pair = NativeMixedPair(a, b, { true }, {})
        val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
            uiMode = uiMode and Configuration.UI_MODE_TYPE_MASK.inv() or Configuration.UI_MODE_TYPE_TELEVISION
            orientation = Configuration.ORIENTATION_LANDSCAPE
        })
        val playerA = PlayerView(context)
        val playerB = PlayerView(context)
        var plays = 0
        var pauses = 0
        var orientations = 0
        var exits = 0
        val viewer = NativePairViewer(context, playerA, playerB, "A", "B", { pair },
            { plays++; pair.play() }, { pauses++; pair.pause() }, {}, {}, {}, {}, { orientations++ })
        val stage get() = viewer.getChildAt(0) as ViewGroup
        val dock get() = viewer.getChildAt(1) as ViewGroup
        val scroller get() = dock.getChildAt(0)
        val volumeCalls get() = a.volumeCalls + b.volumeCalls
        init {
            activity.setContentView(viewer)
            activity.onBackPressedDispatcher.addCallback(activity) {
                if (!viewer.handleBack()) exits++
            }
            viewer.open()
        }
        fun layout() {
            fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
            viewer.measure(View.MeasureSpec.makeMeasureSpec(dp(960), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(540), View.MeasureSpec.EXACTLY))
            viewer.layout(0, 0, viewer.measuredWidth, viewer.measuredHeight)
        }
        fun close() { viewer.endSession(); pair.close() }
    }

    @Test fun hiddenControlsConsumeRevealPressAndItsRepeatsBeforeAcceptingSelect() = withFixture { scenario, fixture ->
        scenario.onActivity { assertTrue(button(fixture.viewer, "Play").isFocused) }
        focus(scenario, fixture, "Hide")
        key(scenario, fixture, KeyEvent.KEYCODE_DPAD_CENTER)
        scenario.onActivity { assertEquals(View.GONE, fixture.dock.visibility) }
        scenario.onActivity {
            val down = SystemClock.uptimeMillis()
            fixture.viewer.dispatchKeyEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            fixture.viewer.dispatchKeyEvent(KeyEvent(down, down + 10, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
            fixture.viewer.dispatchKeyEvent(KeyEvent(down, down + 20, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        }
        idle()
        scenario.onActivity {
            assertEquals(View.VISIBLE, fixture.dock.visibility)
            assertTrue(button(fixture.viewer, "Play").isFocused)
            assertEquals(0, fixture.plays); assertEquals(0, fixture.pauses)
        }
        key(scenario, fixture, KeyEvent.KEYCODE_DPAD_CENTER)
        scenario.onActivity { assertEquals(1, fixture.plays); assertEquals(0, fixture.pauses) }
    }

    @Test fun focusedPlayingControlRemainsVisiblePastTheAutoHideDeadline() = withFixture(true) { scenario, fixture ->
        scenario.onActivity { assertTrue(button(fixture.viewer, "Pause").isFocused) }
        after(scenario, fixture, 4_300)
        scenario.onActivity {
            assertEquals(View.VISIBLE, fixture.dock.visibility)
            assertTrue(button(fixture.viewer, "Pause").isFocused)
            assertNotNull(button(fixture.viewer, "Pause").foreground)
            assertEquals(0, fixture.plays); assertEquals(0, fixture.pauses)
        }
    }

    @Test fun panelBackRestoresTheOpeningToolbarControlWithoutPlaybackCommands() {
        for (panel in listOf("Audio", "Timing")) withFixture { scenario, fixture ->
            focus(scenario, fixture, panel)
            key(scenario, fixture, KeyEvent.KEYCODE_DPAD_CENTER)
            scenario.onActivity {
                assertEquals(View.VISIBLE, fixture.scroller.visibility)
                assertEquals("Back to playback controls", fixture.viewer.findFocus()?.contentDescription)
                it.onBackPressedDispatcher.onBackPressed()
            }
            idle()
            scenario.onActivity {
                assertEquals(View.GONE, fixture.scroller.visibility)
                assertTrue(button(fixture.viewer, panel).isFocused)
                assertEquals(0, fixture.plays); assertEquals(0, fixture.pauses); assertEquals(0, fixture.exits)
            }
        }
    }

    @Test fun systemRemoteBackDismissesOnlyTheMenuThenThePanelAndRestoresTheirAnchors() = withFixture { scenario, fixture ->
        focus(scenario, fixture, "Audio"); key(scenario, fixture, KeyEvent.KEYCODE_DPAD_CENTER)
        focus(scenario, fixture, "More"); key(scenario, fixture, KeyEvent.KEYCODE_DPAD_CENTER)
        onView(withText("Playback status")).check(androidx.test.espresso.assertion.ViewAssertions.matches(
            androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        idle()
        scenario.onActivity {
            assertEquals(View.VISIBLE, fixture.scroller.visibility)
            assertTrue(button(fixture.viewer, "More").isFocused)
            assertEquals(0, fixture.exits)
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        idle()
        scenario.onActivity {
            assertEquals(View.GONE, fixture.scroller.visibility)
            assertTrue(button(fixture.viewer, "Audio").isFocused)
            assertEquals(0, fixture.exits); assertEquals(0, fixture.plays); assertEquals(0, fixture.pauses)
        }
    }

    @Test fun nativeNudgeLongPressStopsAtKeyReleaseAndFocusLoss() = withFixture { scenario, fixture ->
        focus(scenario, fixture, "Audio"); key(scenario, fixture, KeyEvent.KEYCODE_DPAD_CENTER)
        var down = 0L
        var before = 0
        scenario.onActivity {
            assertTrue(button(fixture.viewer, "◀").requestFocus())
            before = fixture.volumeCalls
            down = SystemClock.uptimeMillis()
            fixture.viewer.dispatchKeyEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        }
        after(scenario, fixture, ViewConfiguration.getLongPressTimeout().toLong() + 350)
        scenario.onActivity {
            assertTrue("Native key long press starts repeat", fixture.volumeCalls > before + 2)
            fixture.viewer.dispatchKeyEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_DPAD_CENTER, 0))
            before = fixture.volumeCalls
        }
        after(scenario, fixture, 400)
        scenario.onActivity {
            assertEquals(before, fixture.volumeCalls)
            assertTrue(button(fixture.viewer, "◀").performLongClick())
            assertTrue(button(fixture.viewer, "Mute A").requestFocus())
            before = fixture.volumeCalls
        }
        after(scenario, fixture, 400)
        scenario.onActivity { assertEquals(before, fixture.volumeCalls) }
    }

    @Test fun nudgeRepeatStopsOnCanceledReleaseSuspensionAndSessionEnd() {
        for (ending in listOf("canceled", "suspended", "ended")) withFixture { scenario, fixture ->
            focus(scenario, fixture, "Audio"); key(scenario, fixture, KeyEvent.KEYCODE_DPAD_CENTER)
            var calls = 0
            scenario.onActivity {
                val arrow = button(fixture.viewer, "◀")
                assertTrue(arrow.requestFocus()); assertTrue(arrow.performLongClick())
                when (ending) {
                    "canceled" -> {
                        val now = SystemClock.uptimeMillis()
                        fixture.viewer.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP,
                            KeyEvent.KEYCODE_DPAD_CENTER, 0, 0, 0, 0, KeyEvent.FLAG_CANCELED))
                    }
                    "suspended" -> fixture.viewer.suspendControls()
                    "ended" -> fixture.viewer.endSession()
                }
                calls = fixture.volumeCalls
            }
            after(scenario, fixture, 400)
            scenario.onActivity { assertEquals(calls, fixture.volumeCalls) }
        }
    }

    @Test fun floatingPresetsRetainBothSurfacesAndNeverRequestTvRotation() = withFixture { scenario, fixture ->
        var originalWidth = 0
        scenario.onActivity { fixture.layout(); originalWidth = fixture.stage.getChildAt(1).width }
        focus(scenario, fixture, "More"); key(scenario, fixture, KeyEvent.KEYCODE_DPAD_CENTER)
        onView(withText("Portrait / stacked layout")).check(doesNotExist())
        onView(withText("Floating feed position")).perform(click())
        onView(withText("Bottom left")).perform(click())
        idle()
        scenario.onActivity {
            fixture.layout()
            val floating = fixture.stage.getChildAt(1)
            assertEquals(0, floating.left)
            assertTrue(floating.top > 0)
            assertTrue(button(fixture.viewer, "More").isFocused)
        }
        key(scenario, fixture, KeyEvent.KEYCODE_DPAD_CENTER)
        onView(withText("Floating feed size")).perform(click())
        onView(withText("Large")).perform(click())
        idle()
        scenario.onActivity {
            fixture.layout()
            val floating = fixture.stage.getChildAt(1) as ViewGroup
            assertTrue(floating.width > originalWidth)
            assertTrue(floating.top >= 0 && floating.bottom <= fixture.stage.height)
            assertSame(fixture.playerA, (fixture.stage.getChildAt(0) as ViewGroup).getChildAt(0))
            assertSame(fixture.playerB, floating.getChildAt(0))
            assertEquals(0, fixture.orientations); assertEquals(0, fixture.plays); assertEquals(0, fixture.pauses)
        }
    }

    private fun withFixture(playing: Boolean = false, test: (ActivityScenario<ComponentActivity>, Fixture) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, playing); fixture.layout() }
            idle()
            try { test(scenario, fixture) } finally { scenario.onActivity { fixture.close() } }
        }
    }
    private fun focus(scenario: ActivityScenario<ComponentActivity>, fixture: Fixture, label: String) {
        scenario.onActivity { assertTrue(button(fixture.viewer, label).requestFocus()) }
    }
    private fun key(scenario: ActivityScenario<ComponentActivity>, fixture: Fixture, code: Int) {
        scenario.onActivity {
            val now = SystemClock.uptimeMillis()
            fixture.viewer.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            fixture.viewer.dispatchKeyEvent(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, code, 0))
        }
        idle()
    }
    private fun after(scenario: ActivityScenario<ComponentActivity>, fixture: Fixture, delayMs: Long) {
        val settled = CountDownLatch(1)
        scenario.onActivity { fixture.viewer.postDelayed({ settled.countDown() }, delayMs) }
        assertTrue("Bounded main-thread interval completed", settled.await(delayMs + 3_000, TimeUnit.MILLISECONDS))
        idle()
    }
    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    private fun button(view: View, label: String): Button = descendants(view).filterIsInstance<Button>().single { it.text == label }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
