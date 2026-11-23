package net.fstab.tachiai.feature.presentation

import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.platform.media.NativeMixedPair
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativeSeekPlan
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.platform.media.nativeSeekPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Replay navigation (#655 part 1) with in-memory members: the transport row,
// scrub focus, TV keys and phone double taps. Not provider/device playback.
@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePairViewerTransportTest {
    private class Member(position: Long, live: Boolean = false) : NativePairMember {
        var sample = NativeTimingSnapshot(position, 600_000, position + 10_000, null,
            if (live) 1_000_000 else null, null, live, live, true, true, false, false, false, 3, 1f)
        val seeks = mutableListOf<Long>()
        override fun timingSnapshot() = sample
        override fun seekToMs(targetMs: Long): NativeSeekPlan {
            seeks += targetMs
            val plan = nativeSeekPlan(sample, targetMs)
            plan.targetMs?.let { sample = sample.copy(positionMs = it) }
            return plan
        }
        override fun setTimingPlaying(playing: Boolean): Boolean {
            sample = sample.copy(playing = playing, playWhenReady = playing); return true
        }
        override fun setVolume(volume: Float) = true
        override fun close() {}
    }

    private class Fixture(private val activity: ComponentActivity, television: Boolean, live: Boolean) {
        val a = Member(130_000, live)
        val b = Member(70_000, live)
        val pair = NativeMixedPair(a, b, { true }, {})
        val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
            if (television) uiMode = uiMode and Configuration.UI_MODE_TYPE_MASK.inv() or Configuration.UI_MODE_TYPE_TELEVISION
            // Phone portrait stacks the feeds, so a tap never lands on a swappable floating feed.
            orientation = if (television) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        })
        var plays = 0
        var pauses = 0
        var exits = 0
        val viewer = NativePairViewer(context, PlayerView(context), PlayerView(context), "Sumo", "Commentary", { pair },
            { plays++; pair.play() }, { pauses++; pair.pause() }, {}, {}, {}, {}, {})
        val overlay get() = viewer.getChildAt(1) as ViewGroup
        val notice get() = viewer.getChildAt(2) as TextView
        init {
            activity.setContentView(viewer)
            activity.onBackPressedDispatcher.addCallback(activity) { if (!viewer.handleBack()) exits++ }
            viewer.open()
        }
        // Settle a held skip, as the owner's playback poll does.
        fun settle() { pair.poll(); viewer.refresh() }
        fun close() { viewer.endSession(); pair.close() }
    }

    @Test fun replayShowsPositionRowAndSkipsAroundPlayPause() = withFixture { scenario, fixture ->
        scenario.onActivity {
            val row = descendants(fixture.viewer).filterIsInstance<Button>()
                .filter { it.visibility == View.VISIBLE && it.parent === button(fixture.viewer, "Play").parent }
            assertEquals(listOf("Back 1 minute", "Back 10 seconds", "Play", "Forward 10 seconds", "Forward 1 minute"),
                row.map { if (it.text == "Play") "Play" else it.contentDescription.toString() })
            assertTrue(text(fixture.viewer, "A · Sumo").isShown)
            assertTrue(text(fixture.viewer, "2:10 / 10:00").isShown)
            assertTrue(scrub(fixture.viewer).isShown)
            assertTrue(button(fixture.viewer, "Play").isFocused)
        }
    }

    // The viewer opens before its feeds are ready; focus still lands on
    // Play/Pause once it is enabled, never on Stop or the top bar (#656).
    @Test fun startFocusMovesToPlayPauseOnceFeedsAreReady() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var viewer: NativePairViewer
            var ready = false
            val a = Member(130_000); val b = Member(70_000)
            val pair = NativeMixedPair(a, b, { true }, {})
            scenario.onActivity { activity ->
                val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                    uiMode = uiMode and Configuration.UI_MODE_TYPE_MASK.inv() or Configuration.UI_MODE_TYPE_TELEVISION
                })
                viewer = NativePairViewer(context, PlayerView(context), PlayerView(context), "Sumo", "Commentary",
                    { pair.takeIf { ready } }, {}, {}, {}, {}, {}, {}, {}, onSources = {})
                activity.setContentView(viewer)
                viewer.open()
            }
            idle()
            scenario.onActivity {
                assertFalse(button(viewer, "Play").isEnabled)
                assertFalse(button(viewer, "Stop playback").isFocused)
                ready = true
                viewer.refresh()
            }
            idle()
            scenario.onActivity {
                assertTrue(button(viewer, "Play").isFocused)
                viewer.endSession(); pair.close()
            }
        }
    }

    @Test fun liveFeedsHideTheTransportAndLeftRightOnlyReveal() = withFixture(live = true) { scenario, fixture ->
        scenario.onActivity {
            assertFalse(button(fixture.viewer, "Forward 10 seconds").isShown)
            assertFalse(scrub(fixture.viewer).isShown)
        }
        hide(scenario, fixture)
        key(scenario, fixture, KeyEvent.KEYCODE_DPAD_RIGHT)
        scenario.onActivity {
            assertEquals(View.VISIBLE, fixture.overlay.visibility)
            assertTrue(fixture.a.seeks.isEmpty() && fixture.b.seeks.isEmpty())
        }
    }

    @Test fun onScreenSkipMovesBothFeedsKeepingTheirOffset() = withFixture { scenario, fixture ->
        scenario.onActivity { button(fixture.viewer, "Back 1 minute").performClick() }
        idle()
        scenario.onActivity {
            assertEquals(listOf(70_000L), fixture.a.seeks)
            assertEquals(listOf(10_000L), fixture.b.seeks)
            fixture.settle()
            assertTrue(text(fixture.viewer, "1:10 / 10:00").isShown)
            assertEquals(0, fixture.plays); assertEquals(0, fixture.pauses)
        }
    }

    @Test fun hiddenLeftRightSkipWithAFlashAndKeepTheOverlayHidden() = withFixture { scenario, fixture ->
        hide(scenario, fixture)
        held(scenario, fixture, KeyEvent.KEYCODE_DPAD_RIGHT)
        scenario.onActivity {
            assertEquals(View.GONE, fixture.overlay.visibility)
            assertEquals(listOf(140_000L), fixture.a.seeks)
            assertEquals(listOf(80_000L), fixture.b.seeks)
            assertEquals(View.VISIBLE, fixture.notice.visibility)
            assertTrue(fixture.notice.text.startsWith("+10 s · 2:20 / 10:00"))
            fixture.settle()
        }
        key(scenario, fixture, KeyEvent.KEYCODE_DPAD_LEFT)
        scenario.onActivity {
            assertEquals(View.GONE, fixture.overlay.visibility)
            assertEquals(listOf(140_000L, 130_000L), fixture.a.seeks)
            fixture.settle()
        }
        // Select reveals on Play/Pause without acting.
        key(scenario, fixture, KeyEvent.KEYCODE_DPAD_CENTER)
        scenario.onActivity {
            assertEquals(View.VISIBLE, fixture.overlay.visibility)
            assertTrue(button(fixture.viewer, "Play").isFocused)
            assertEquals(0, fixture.plays); assertEquals(0, fixture.pauses)
        }
    }

    @Test fun mediaFastForwardAndRewindSkip() = withFixture { scenario, fixture ->
        key(scenario, fixture, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        scenario.onActivity { assertEquals(listOf(140_000L), fixture.a.seeks); fixture.settle() }
        key(scenario, fixture, KeyEvent.KEYCODE_MEDIA_REWIND)
        scenario.onActivity { assertEquals(listOf(140_000L, 130_000L), fixture.a.seeks) }
    }

    @Test fun scrubBarLeftRightPreviewsAndSelectCommits() = withFixture { scenario, fixture ->
        scenario.onActivity { assertTrue(scrub(fixture.viewer).requestFocus()) }
        key(scenario, fixture, KeyEvent.KEYCODE_DPAD_RIGHT)
        key(scenario, fixture, KeyEvent.KEYCODE_DPAD_RIGHT)
        scenario.onActivity {
            assertTrue(scrub(fixture.viewer).isFocused)
            assertTrue(fixture.a.seeks.isEmpty())
            assertTrue(text(fixture.viewer, "→ 2:30 / 10:00").isShown)
        }
        key(scenario, fixture, KeyEvent.KEYCODE_DPAD_CENTER)
        scenario.onActivity {
            assertEquals(listOf(150_000L), fixture.a.seeks)
            assertEquals(listOf(90_000L), fixture.b.seeks)
            assertTrue(scrub(fixture.viewer).isFocused)
            assertEquals(0, fixture.plays); assertEquals(0, fixture.pauses)
        }
    }

    @Test fun scrubCommitsAfterIdleAndBackAbandonsIt() = withFixture { scenario, fixture ->
        scenario.onActivity { assertTrue(scrub(fixture.viewer).requestFocus()) }
        key(scenario, fixture, KeyEvent.KEYCODE_DPAD_LEFT)
        after(scenario, fixture, 1_300)
        scenario.onActivity { assertEquals(listOf(120_000L), fixture.a.seeks); fixture.settle() }
        key(scenario, fixture, KeyEvent.KEYCODE_DPAD_LEFT)
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        after(scenario, fixture, 1_300)
        scenario.onActivity {
            assertEquals(listOf(120_000L), fixture.a.seeks)
            assertEquals(View.VISIBLE, fixture.overlay.visibility)
            assertEquals(0, fixture.exits)
        }
    }

    @Test fun phoneDoubleTapSkipsByHalfAndSingleTapStillToggles() = withFixture(television = false) { scenario, fixture ->
        scenario.onActivity {
            val width = fixture.viewer.width
            tap(fixture.viewer, width * 0.75f); tap(fixture.viewer, width * 0.75f)
        }
        idle()
        after(scenario, fixture, ViewConfiguration.getDoubleTapTimeout() + 200L)
        scenario.onActivity {
            assertEquals(listOf(140_000L), fixture.a.seeks)
            assertEquals(View.VISIBLE, fixture.overlay.visibility) // The double tap did not toggle.
            fixture.settle()
            tap(fixture.viewer, fixture.viewer.width * 0.25f)
        }
        after(scenario, fixture, ViewConfiguration.getDoubleTapTimeout() + 200L)
        scenario.onActivity {
            assertEquals(View.GONE, fixture.overlay.visibility)
            assertEquals(1, fixture.a.seeks.size)
        }
    }

    private fun tap(viewer: View, x: Float) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, x, viewer.height * 0.3f, 0)
            try { viewer.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }
    private fun withFixture(television: Boolean = true, live: Boolean = false,
        test: (ActivityScenario<ComponentActivity>, Fixture) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, television, live); fixture.viewer.refresh() }
            idle()
            try { test(scenario, fixture) } finally { scenario.onActivity { fixture.close() } }
        }
    }
    private fun hide(scenario: ActivityScenario<ComponentActivity>, fixture: Fixture) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        idle()
        scenario.onActivity { assertEquals(View.GONE, fixture.overlay.visibility); assertEquals(0, fixture.exits) }
    }
    private fun key(scenario: ActivityScenario<ComponentActivity>, fixture: Fixture, code: Int) {
        scenario.onActivity {
            val now = SystemClock.uptimeMillis()
            fixture.viewer.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            fixture.viewer.dispatchKeyEvent(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, code, 0))
        }
        idle()
    }
    // One press with repeats skips once.
    private fun held(scenario: ActivityScenario<ComponentActivity>, fixture: Fixture, code: Int) {
        scenario.onActivity {
            val down = SystemClock.uptimeMillis()
            fixture.viewer.dispatchKeyEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0))
            fixture.viewer.dispatchKeyEvent(KeyEvent(down, down + 10, KeyEvent.ACTION_DOWN, code, 1))
            fixture.viewer.dispatchKeyEvent(KeyEvent(down, down + 20, KeyEvent.ACTION_UP, code, 0))
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
    private fun button(view: View, label: String): Button = descendants(view).filterIsInstance<Button>()
        .single { it.text == label || it.contentDescription == label }
    private fun text(view: View, value: String): TextView = descendants(view).filterIsInstance<TextView>()
        .filter { it !is Button }.single { it.text.toString() == value }
    private fun scrub(view: View): SeekBar = descendants(view).filterIsInstance<SeekBar>()
        .single { it.contentDescription?.startsWith("Position in") == true }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
