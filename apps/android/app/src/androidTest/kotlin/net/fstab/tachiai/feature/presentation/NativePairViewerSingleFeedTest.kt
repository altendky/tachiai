package net.fstab.tachiai.feature.presentation

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativeQualityRequest
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeSeekPlan
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.presentation.ViewerQualityReadback
import org.hamcrest.Matchers.hasToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

// App-owned blank players and synthetic clocks only. Configuration contexts
// exercise the shared responsive rules without changing device settings.
@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePairViewerSingleFeedTest {
    private class Member : NativePairMember {
        override fun timingSnapshot() = NativeTimingSnapshot(20_000, 60_000, 30_000, null, null, null,
            false, false, true, true, false, false, false, 3, 1f)
        override fun seekToMs(targetMs: Long) = NativeSeekPlan(NativeSeekOutcome.REQUESTED, targetMs)
        override fun setTimingPlaying(playing: Boolean) = true
        override fun setVolume(volume: Float) = true
        override fun close() {}
    }
    private data class Shape(val orientation: Int, val television: Boolean, val width: Int, val height: Int)
    private class Fixture(activity: ComponentActivity, val shape: Shape, selected: Set<NativeMixedSide>) {
        private val window = activity.window
        val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
            orientation = shape.orientation
            uiMode = uiMode and Configuration.UI_MODE_TYPE_MASK.inv() or
                if (shape.television) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
        })
        val a = PlayerView(context)
        val b = PlayerView(context)
        val messages = mutableMapOf<NativeMixedSide, String>()
        val recoveries = mutableListOf<NativeMixedSide>()
        val volumes = mutableListOf<Pair<NativeMixedSide, Float>>()
        val qualityReads = mutableListOf<NativeMixedSide>()
        val suppliedMembers = listOf(Member(), Member())
        val viewer = NativePairViewer(context, a, b, "Alpha", "Beta", { null }, {}, {}, {}, {}, {}, {}, {},
            members = { suppliedMembers },
            volumeRequest = { side, gain -> volumes += side to gain; true },
            feedMessage = { messages[it] },
            feedRecovery = { side -> messages[side]?.let { ViewerFeedRecovery("Retry ${side.name}") } },
            onRecoverFeed = { recoveries += it },
            qualityState = { side, _ -> qualityReads += side; ViewerQualityReadback(NativeQualityRequest.auto, null) },
            selectedSlots = selected)
        init { activity.setContentView(viewer); viewer.open(); layout() }
        fun layout() {
            fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
            viewer.measure(View.MeasureSpec.makeMeasureSpec(dp(shape.width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(shape.height), View.MeasureSpec.EXACTLY))
            viewer.layout(0, 0, viewer.measuredWidth, viewer.measuredHeight)
        }
        fun layoutOnScreen() {
            // Popup anchors must use the actual app viewport, rather than the
            // synthetic landscape dimensions used for geometry assertions.
            viewer.measure(View.MeasureSpec.makeMeasureSpec(window.decorView.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(window.decorView.height, View.MeasureSpec.EXACTLY))
            viewer.layout(0, 0, viewer.measuredWidth, viewer.measuredHeight)
        }
        fun button(label: String) = descendants(viewer).filterIsInstance<Button>().single { it.text == label }
        fun player(side: NativeMixedSide) = if (side == NativeMixedSide.A) a else b
        fun assertFullStage(side: NativeMixedSide) {
            val pane = player(side).parent as ViewGroup
            val stage = pane.parent as ViewGroup
            assertEquals(1, stage.childCount)
            assertEquals(0, pane.left); assertEquals(0, pane.top)
            assertTrue(stage.width > 0 && stage.height > 0)
            assertEquals(stage.width, pane.width); assertEquals(stage.height, pane.height)
            val unused = player(NativeMixedSide.entries.single { it != side }).parent as ViewGroup
            assertNull(unused.parent)
            assertFalse(unused.isShown)
        }
        fun close() { viewer.endSession() }
    }

    @Test fun eitherSingleSelectionFillsTheStageInPortraitLandscapeAndTelevision() = singleConfigurations { scenario, fixture, side ->
        scenario.onActivity {
            fixture.assertFullStage(side)
            assertFalse(fixture.button("Timing").isShown)
            assertFalse(fixture.button("Timing").isEnabled)
            val pane = fixture.player(side).parent as View
            assertFalse(pane.contentDescription.toString().contains("floating"))
            pane.performClick(); fixture.layout(); fixture.assertFullStage(side)
            pane.performClick(); fixture.layout(); fixture.assertFullStage(side)
        }
    }

    @Test fun selectedPreparingAndFailedFeedKeepsFullStageAndItsOwnRecovery() = singleConfigurations { scenario, fixture, side ->
        scenario.onActivity {
            for (message in listOf("Preparing selected feed", "Selected feed failed")) {
                fixture.messages[side] = message
                fixture.viewer.refresh(); fixture.layout(); fixture.assertFullStage(side)
                assertEquals(View.GONE, fixture.player(side).visibility)
                assertTrue(descendants(fixture.viewer).filterIsInstance<TextView>().any { text -> text.isShown && text.text == message })
                fixture.button("Retry ${side.name}").performClick()
            }
            assertEquals(listOf(side, side), fixture.recoveries)
            fixture.messages.clear(); fixture.viewer.refresh(); fixture.layout()
            fixture.assertFullStage(side); assertEquals(View.VISIBLE, fixture.player(side).visibility)
        }
    }

    @Test fun singleFeedAudioAndMenusNeverOfferActionsForTheUnselectedSlot() = singleConfigurations { scenario, fixture, side ->
        scenario.onActivity {
            fixture.button("Audio").performClick(); fixture.layout()
            assertEquals(1, descendants(fixture.viewer).filterIsInstance<SeekBar>().count { it.isShown })
            assertFalse(descendants(fixture.viewer).filterIsInstance<Button>().any { it.text == "Centre mix" })
            assertEquals(setOf(side), fixture.volumes.map { value -> value.first }.toSet())
            assertEquals(0.5f, fixture.volumes.last().second, 0f)
            fixture.button("Mute ${side.name}").performClick()
            assertEquals(side to 0f, fixture.volumes.last())
            val unused = NativeMixedSide.entries.single { it != side }
            assertFalse(descendants(fixture.viewer).filterIsInstance<Button>().any { it.text == "Mute ${unused.name}" })
            fixture.layoutOnScreen(); fixture.button("More").performClick()
        }
        onView(withText("Swap primary feed")).check(doesNotExist())
        onView(withText("Floating feed position")).check(doesNotExist())
        onView(withText("Floating feed size")).check(doesNotExist())
        onData(hasToString("Quality")).inRoot(isPlatformPopup()).perform(selectMenuItem())
        scenario.onActivity {
            assertEquals(setOf(side), fixture.qualityReads.toSet())
            val labels = descendants(fixture.viewer).filterIsInstance<TextView>().map { text -> text.text.toString() }
            assertTrue(labels.contains(if (side == NativeMixedSide.A) "Alpha" else "Beta"))
            assertFalse(labels.contains(if (side == NativeMixedSide.A) "Beta" else "Alpha"))
        }
    }

    @Test fun twoSelectedSlotsKeepBothPanesWhenOneIsPreparingOrFailed() {
        for (shape in shapes) ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val fixture = Fixture(activity, shape, NativeMixedSide.entries.toSet())
                try {
                    val paneA = fixture.a.parent as ViewGroup; val paneB = fixture.b.parent as ViewGroup
                    val stage = paneA.parent as ViewGroup
                    assertSame(stage, paneB.parent); assertEquals(2, stage.childCount)
                    for (side in NativeMixedSide.entries) {
                        fixture.messages[side] = "Selected feed unavailable"
                        fixture.viewer.refresh(); fixture.layout()
                        assertEquals(2, stage.childCount)
                        assertTrue(paneA.width > 0 && paneA.height > 0 && paneB.width > 0 && paneB.height > 0)
                        assertTrue(paneA.width < stage.width || paneA.height < stage.height ||
                            paneB.width < stage.width || paneB.height < stage.height)
                        assertTrue(fixture.button("Timing").isShown)
                        fixture.messages.clear()
                    }
                } finally { fixture.close() }
            }
        }
    }

    private fun singleConfigurations(test: (ActivityScenario<ComponentActivity>, Fixture, NativeMixedSide) -> Unit) {
        for (shape in shapes) for (side in NativeMixedSide.entries)
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                lateinit var fixture: Fixture
                scenario.onActivity { fixture = Fixture(it, shape, setOf(side)) }
                try { test(scenario, fixture, side) } finally { scenario.onActivity { fixture.close() } }
            }
    }
    private fun selectMenuItem() = object : ViewAction {
        override fun getConstraints() = isDisplayed()
        override fun getDescription() = "Select the visible app-owned popup row"
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
        val shapes = listOf(Shape(Configuration.ORIENTATION_PORTRAIT, false, 420, 850),
            Shape(Configuration.ORIENTATION_LANDSCAPE, false, 850, 420),
            Shape(Configuration.ORIENTATION_LANDSCAPE, true, 960, 540))
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    }
}
