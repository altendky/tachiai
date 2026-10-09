package net.fstab.tachiai.feature.presentation

import android.content.res.Configuration
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
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

@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePairViewerDismissTest {
    private class Member(private val playing: Boolean) : NativePairMember {
        var gain = 0f
        var volumeCalls = 0
        override fun timingSnapshot() = NativeTimingSnapshot(20_000, 60_000, 30_000, null, null, null,
            false, false, true, true, false, playing, playing, 3, 1f)
        override fun seekToMs(targetMs: Long) = NativeSeekPlan(NativeSeekOutcome.REQUESTED, targetMs)
        override fun setTimingPlaying(playing: Boolean) = true
        override fun setVolume(volume: Float): Boolean { gain = volume; volumeCalls++; return true }
        override fun close() {}
    }

    private class Fixture(activity: ComponentActivity, playing: Boolean, landscape: Boolean) {
        val a = Member(playing)
        val b = Member(playing)
        val pair = NativeMixedPair(a, b, { true }, {})
        var commands = 0
        val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
            orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        })
        val playerA = PlayerView(context)
        val playerB = PlayerView(context)
        val viewer = NativePairViewer(context, playerA, playerB, "A", "B", { pair },
            { commands++ }, { commands++ }, { commands++ }, { commands++ }, { commands++ }, { commands++ }, { commands++ })
        val stage get() = viewer.getChildAt(0) as ViewGroup
        val dock get() = viewer.getChildAt(1) as ViewGroup
        val scroller get() = dock.getChildAt(0)
        init { activity.setContentView(viewer); viewer.refresh() }
        fun layout() {
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            viewer.measure(View.MeasureSpec.makeMeasureSpec(if (landscape) 2400 else 1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(if (landscape) 1080 else 2400, View.MeasureSpec.EXACTLY))
            viewer.layout(0, 0, viewer.measuredWidth, viewer.measuredHeight)
        }
        fun close() { viewer.endSession(); pair.close() }
    }

    @Test fun primaryDismissesEitherPanelPausedOrPlayingAndRevealsOnlyTray() {
        for (playing in listOf(false, true)) for (panel in listOf("Audio", "Timing")) {
            withFixture(playing) { scenario, fixture ->
                scenario.onActivity {
                    assertEquals(if (playing) "Pause" else "Play", button(fixture.viewer,
                        if (playing) "Pause" else "Play").text.toString())
                    button(fixture.viewer, panel).performClick(); fixture.layout()
                    assertEquals(View.VISIBLE, fixture.scroller.visibility)
                    val calls = fixture.a.volumeCalls + fixture.b.volumeCalls
                    fixture.stage.getChildAt(0).performClick()
                    assertEquals(View.GONE, fixture.dock.visibility)
                    assertEquals(View.GONE, fixture.scroller.visibility)
                    assertEquals(0, fixture.commands)
                    assertEquals(calls, fixture.a.volumeCalls + fixture.b.volumeCalls)
                    fixture.stage.getChildAt(0).performClick()
                    assertEquals(View.VISIBLE, fixture.dock.visibility)
                    assertEquals(View.GONE, fixture.scroller.visibility)
                }
            }
        }
    }

    @Test fun explicitHideAndBackgroundDismissPreserveMuteAndChildActions() {
        withFixture(false) { scenario, fixture ->
            scenario.onActivity {
                button(fixture.viewer, "Audio").performClick()
                button(fixture.viewer, "Mute A").performClick()
                assertEquals(View.VISIBLE, fixture.dock.visibility)
                assertEquals(0f, fixture.a.gain, 0f)
                button(fixture.viewer, "Hide").performClick()
                assertEquals(View.GONE, fixture.dock.visibility)
                fixture.viewer.performClick()
                button(fixture.viewer, "Audio").performClick()
                assertEquals("Unmute A", button(fixture.viewer, "Unmute A").text.toString())
                fixture.stage.performClick()
                assertEquals(View.GONE, fixture.dock.visibility)
                assertEquals(0, fixture.commands)
            }
        }
    }

    @Test fun dismissalCancelsHeldMixArrowAndReopeningCanNudgeAgain() {
        withFixture(false) { scenario, fixture ->
            var calls = 0
            val settled = CountDownLatch(1)
            scenario.onActivity {
                button(fixture.viewer, "Audio").performClick()
                assertTrue(button(fixture.viewer, "◀").performLongClick())
                assertTrue(fixture.b.gain < 0.5f)
                button(fixture.viewer, "Hide").performClick()
                calls = fixture.a.volumeCalls + fixture.b.volumeCalls
                fixture.viewer.postDelayed({ settled.countDown() }, 400)
            }
            assertTrue("Delayed main-thread check ran", settled.await(3, TimeUnit.SECONDS))
            scenario.onActivity {
                assertEquals(calls, fixture.a.volumeCalls + fixture.b.volumeCalls)
                fixture.viewer.performClick()
                button(fixture.viewer, "Audio").performClick()
                button(fixture.viewer, "◀").performClick()
                assertTrue(fixture.a.volumeCalls + fixture.b.volumeCalls > calls)
                assertEquals(View.VISIBLE, fixture.dock.visibility)
            }
        }
    }

    @Test fun floatingTapAndDragRetainTheirOwnGesturesWithPanelOpen() {
        withFixture(false, true) { scenario, fixture ->
            scenario.onActivity {
                button(fixture.viewer, "Timing").performClick(); fixture.layout()
                val paneA = fixture.stage.getChildAt(0)
                val paneB = fixture.stage.getChildAt(1)
                paneB.performClick(); fixture.layout()
                assertTrue(paneB.width > paneA.width)
                assertEquals(View.VISIBLE, fixture.scroller.visibility)
                val oldLeft = paneA.left
                val now = SystemClock.uptimeMillis()
                for ((action, x) in listOf(MotionEvent.ACTION_DOWN to 50f,
                    MotionEvent.ACTION_MOVE to -250f, MotionEvent.ACTION_UP to -250f)) {
                    val event = MotionEvent.obtain(now, now, action, x, 50f, 0)
                    try { assertTrue(paneA.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                fixture.layout()
                assertTrue(paneA.left < oldLeft)
                assertTrue(paneB.width > paneA.width)
                assertSame(fixture.playerA, (paneA as ViewGroup).getChildAt(0))
                assertSame(fixture.playerB, (paneB as ViewGroup).getChildAt(0))
                assertEquals(View.VISIBLE, fixture.dock.visibility)
                assertEquals(0, fixture.commands)
            }
        }
    }

    @Test fun cancellingPopupPreservesPanelWithoutActionsOrTapThrough() {
        for (playing in listOf(false, true)) for (panel in listOf("Audio", "Timing")) {
            for (dismissal in listOf("Back", "outside", "anchor")) {
                withFixture(playing) { scenario, fixture ->
                    var calls = 0
                    var anchor = floatArrayOf()
                    var outside = floatArrayOf()
                    scenario.onActivity {
                        button(fixture.viewer, panel).performClick()
                        calls = fixture.a.volumeCalls + fixture.b.volumeCalls
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity {
                        val location = IntArray(2)
                        val more = button(fixture.viewer, "More")
                        more.getLocationOnScreen(location)
                        anchor = floatArrayOf(location[0] + more.width / 2f, location[1] + more.height / 2f)
                        fixture.stage.getLocationOnScreen(location)
                        outside = floatArrayOf(location[0] + 8f, location[1] + 8f)
                        // The popup occupies the control area; the video's top-left
                        // is outside it and would hide the tray if the tap leaked.
                    }
                    onView(withText("More")).perform(click())
                    if (dismissal == "Back") pressBack() else {
                        val target = if (dismissal == "anchor") anchor else outside
                        onView(isRoot()).perform(GeneralClickAction(Tap.SINGLE, { target }, Press.FINGER))
                    }
                    onView(withText("Playback status")).check(doesNotExist())
                    scenario.onActivity {
                        assertEquals(View.VISIBLE, fixture.dock.visibility)
                        assertEquals(View.VISIBLE, fixture.scroller.visibility)
                        assertEquals(0, fixture.commands)
                        assertEquals(calls, fixture.a.volumeCalls + fixture.b.volumeCalls)
                        button(fixture.viewer, "Hide").performClick()
                        assertEquals(View.GONE, fixture.dock.visibility)
                    }
                }
            }
        }
    }

    @Test fun cancellingStepPreservesTimingPanelAndSelectedValue() {
        withFixture(false) { scenario, fixture ->
            onView(withText("Timing")).perform(click())
            onView(withText("Step: 1 s · choose")).perform(click())
            pressBack()
            onView(withText("0.25 s")).check(doesNotExist())
            scenario.onActivity {
                assertEquals(View.VISIBLE, fixture.dock.visibility)
                assertEquals(View.VISIBLE, fixture.scroller.visibility)
                assertEquals("Step: 1 s · choose", button(fixture.viewer, "Step:").text.toString())
                assertEquals(0, fixture.commands)
            }
        }
    }

    @Test fun popupActionStillOpensStatusAndTeardownDoesNotRevealControls() {
        withFixture(false) { scenario, fixture ->
            onView(withText("More")).perform(click())
            onView(withText("Playback status")).perform(click())
            scenario.onActivity {
                assertEquals(View.VISIBLE, fixture.dock.visibility)
                assertEquals(View.VISIBLE, fixture.scroller.visibility)
                assertEquals(0, fixture.commands)
            }
            onView(withText("More")).perform(click())
            scenario.onActivity { fixture.viewer.endSession() }
            onView(withText("Playback status")).check(doesNotExist())
            scenario.onActivity { assertEquals(View.GONE, fixture.viewer.visibility) }
        }
    }

    private fun withFixture(playing: Boolean, landscape: Boolean = false,
        test: (ActivityScenario<ComponentActivity>, Fixture) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, playing, landscape) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            try { test(scenario, fixture) } finally { scenario.onActivity { fixture.close() } }
        }
    }

    private fun button(view: View, prefix: String): Button {
        if (view is Button && view.text.startsWith(prefix)) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            runCatching { button(view.getChildAt(index), prefix) }.getOrNull()?.let { return it }
        }
        error("Fixture control not found: $prefix")
    }
}
