package net.fstab.tachiai.feature.presentation

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.fstab.tachiai.platform.media.NativeMixedPair
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeSeekPlan
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.presentation.ViewerTimingStep
import org.junit.Assert.assertEquals
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

    @Test fun selectorOffersFiveExactStepsAndDispatchesBothRelativeDirections() {
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
            var selected = ViewerTimingStep.ONE_SECOND
            for (choice in ViewerTimingStep.entries) {
                onView(withText("Step: ${selected.label} · choose")).perform(scrollTo(), click())
                onView(withText(choice.label)).perform(click())
                selected = choice
                scenario.onActivity {
                    assertEquals("Step: ${choice.label} · choose", button(viewer, "Step:").text.toString())
                    button(viewer, "Advance A").performClick()
                    button(viewer, "Advance B").performClick()
                }
            }
            scenario.onActivity { viewer.endSession() }
        }
        pair.close()
        assertEquals(ViewerTimingStep.entries.flatMap { listOf(it.milliseconds, -it.milliseconds) }, requested)
    }

    @Test fun teardownDismissesTheOwnedStepMenu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var viewer: NativePairViewer
            scenario.onActivity { activity ->
                viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity),
                    "A", "B", { null }, {}, {}, {}, {}, {}, {}, {})
                activity.setContentView(viewer)
            }
            instrumentation.waitForIdleSync()
            onView(withText("Timing")).perform(click())
            onView(withText("Step: 1 s · choose")).perform(scrollTo(), click())
            onView(withText("0.25 s")).check(matches(isDisplayed()))
            scenario.onActivity { viewer.endSession() }
            onView(withText("0.25 s")).check(doesNotExist())
            scenario.onActivity { assertEquals(View.GONE, viewer.visibility) }
        }
    }

    private fun button(view: View, prefix: String): Button {
        if (view is Button && view.text.startsWith(prefix)) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            val found = runCatching { button(view.getChildAt(index), prefix) }.getOrNull()
            if (found != null) return found
        }
        error("Fixture control not found: $prefix")
    }
}
