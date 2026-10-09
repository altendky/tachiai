package net.fstab.tachiai.feature.presentation

import android.view.View
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.fstab.tachiai.platform.media.NativeMixedPair
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeSeekPlan
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.presentation.ViewerTimingStep
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePairViewerTimingTest {
    private class Member : NativePairMember {
        override fun timingSnapshot() = NativeTimingSnapshot(20_000, 60_000, 30_000, null, null, null,
            false, false, true, true, false, false, false, 3, 1f)
        override fun seekToMs(targetMs: Long) = NativeSeekPlan(NativeSeekOutcome.REQUESTED, targetMs)
        override fun setTimingPlaying(playing: Boolean) = true
        override fun setVolume(volume: Float) = true
        override fun close() {}
    }

    @Test fun rowsOfferFiveDirectStepsAndDispatchBothRelativeDirections() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val requested = mutableListOf<Long>()
        val pair = NativeMixedPair(Member(), Member(), { true }, {})
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var viewer: NativePairViewer
            scenario.onActivity { activity ->
                viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity),
                    "A", "B", { pair }, {}, {}, { requested.add(it) }, {}, {}, {}, {})
                activity.setContentView(viewer)
            }
            instrumentation.waitForIdleSync()
            onView(withText("Timing")).perform(click())
            for (choice in ViewerTimingStep.entries) {
                scenario.onActivity {
                    timingButtons(viewer, "A").first { it.text == choice.label }.performClick()
                    timingButtons(viewer, "B").first { it.text == choice.label }.performClick()
                }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity {
                for (feed in listOf("A", "B")) {
                    assertEquals(ViewerTimingStep.entries.map { it.label }, timingButtons(viewer, feed).map { it.text.toString() })
                }
                assertFalse(descendants(viewer).filterIsInstance<Button>().any {
                    it.text.startsWith("Step:") || it.text == "Advance A" || it.text == "Advance B"
                })
                val rowA = timingButtons(viewer, "A")
                val rowB = timingButtons(viewer, "B")
                assertTrue(rowA[0].requestFocus())
                assertSame(rowA[1], rowA[0].focusSearch(View.FOCUS_RIGHT))
                assertSame(rowB[0], rowA[0].focusSearch(View.FOCUS_DOWN))
                assertSame(rowA[0], rowB[0].focusSearch(View.FOCUS_UP))
                button(viewer, "Audio").performClick()
                button(viewer, "Timing").performClick()
                assertEquals(10, timingButtons(viewer, "A").size + timingButtons(viewer, "B").size)
                viewer.endSession()
            }
        }
        pair.close()
        assertEquals(ViewerTimingStep.entries.flatMap { listOf(it.milliseconds, -it.milliseconds) }, requested)
    }

    @Test fun allStepsDisableWhileBusyOrMissingAndFocusTracksRows() {
        val pair = NativeMixedPair(Member(), Member(), { true }, {})
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var available: NativeMixedPair? = pair
                val viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity),
                    "A", "B", { available }, {}, {}, {}, {}, {}, {}, {})
                activity.setContentView(viewer)
                button(viewer, "Timing").performClick()
                val rowA = timingButtons(viewer, "A")
                val rowB = timingButtons(viewer, "B")
                assertTrue((rowA + rowB).all { it.isEnabled && it.isFocusable })
                for (index in rowA.indices) {
                    assertEquals(rowB[index].id, rowA[index].nextFocusDownId)
                    assertEquals(rowA[index].id, rowB[index].nextFocusUpId)
                    if (index < rowA.lastIndex) assertEquals(rowA[index + 1].id, rowA[index].nextFocusRightId)
                    if (index > 0) assertEquals(rowB[index - 1].id, rowB[index].nextFocusLeftId)
                }
                pair.shiftRelative(100)
                assertTrue(pair.busy)
                viewer.refresh()
                assertTrue((rowA + rowB).none { it.isEnabled })
                pair.pause(); viewer.refresh()
                assertTrue((rowA + rowB).all { it.isEnabled })
                available = null; viewer.refresh()
                assertTrue((rowA + rowB).none { it.isEnabled })
                viewer.endSession()
            }
        }
        pair.close()
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun timingButtons(view: View, feed: String) = descendants(view).filterIsInstance<Button>()
        .filter { it.contentDescription?.startsWith("Advance $feed by ") == true }

    private fun button(view: View, prefix: String): Button {
        if (view is Button && view.text.startsWith(prefix)) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            val found = runCatching { button(view.getChildAt(index), prefix) }.getOrNull()
            if (found != null) return found
        }
        error("Fixture control not found: $prefix")
    }
}
