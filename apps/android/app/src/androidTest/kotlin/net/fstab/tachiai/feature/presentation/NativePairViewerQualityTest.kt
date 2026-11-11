package net.fstab.tachiai.feature.presentation

import android.view.View
import android.view.ViewGroup
import android.view.MenuItem
import android.widget.AdapterView
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.startsWith
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher

@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePairViewerQualityTest {
    private val manual = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC, 2_000_000, 1280, 720))
    private val actual = NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC, 500_000, 640, 360)
    private inner class Member : NativePairMember {
        var preferences = NativeQualityPreferences()
        override fun timingSnapshot() = NativeTimingSnapshot(20_000, 60_000, 30_000, null, null, null,
            false, false, true, true, false, false, false, 3, 1f)
        override fun setQualityPreferences(preferences: NativeQualityPreferences): Boolean { this.preferences = preferences; return true }
        override fun qualitySnapshot() = NativeQualitySnapshot(actual, null, 1_000_000, null, emptyList(), 0,
            NativeQualityKind.entries.associateWith { kind ->
                val request = preferences.get(kind)
                NativeQualityControl(request, if (request.track == null) NativeQualityOutcome.AUTO else NativeQualityOutcome.REQUESTED,
                    if (kind == NativeQualityKind.VIDEO) listOf(manual) else emptyList())
            })
        override fun seekToMs(targetMs: Long) = NativeSeekPlan(NativeSeekOutcome.REQUESTED, targetMs)
        override fun setTimingPlaying(playing: Boolean) = true
        override fun setVolume(volume: Float) = true
        override fun close() {}
    }

    @Test fun independentFeedChoicesNeedExplicitSaveAndResetsHaveSeparateScopes() {
        val source = PrototypeSource.ABEMA_REPLAY
        val state = ViewerQualityState(listOf(source, source), emptyMap())
        val a = Member(); val b = Member()
        val pair = NativeMixedPair(a, b, { true }, {})
        var writes = 0
        var saved = emptyMap<PrototypeSource, NativeQualityPreferences>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var viewer: NativePairViewer
            fun apply() { a.setQualityPreferences(state.effective(NativeMixedSide.A)); b.setQualityPreferences(state.effective(NativeMixedSide.B)) }
            scenario.onActivity { activity ->
                viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity), "A · Sumo", "B · Sumo",
                    { pair }, {}, {}, {}, {}, {}, {}, {},
                    qualityState = { side, kind -> state.read(side, kind) },
                    onQualityOverride = { side, kind, request -> state.setOverride(side, kind, request); apply() },
                    onSaveQuality = { side, kind ->
                        writes++; saved = saved + (source to (saved[source] ?: NativeQualityPreferences()).with(kind, state.read(side, kind).effective))
                        state.replaceDefaults(saved); apply()
                    }, onResetQuality = { _, kind ->
                        writes++; saved = saved + (source to (saved[source] ?: NativeQualityPreferences()).with(kind, NativeQualityRequest.auto))
                        state.replaceDefaults(saved); apply()
                    })
                activity.setContentView(viewer)
            }
            scenario.onActivity { assertTrue(button(viewer, "More").performClick()) }
            selectMenu("Quality")
            onView(withContentDescription(startsWith("A · Sumo Video quality"))).perform(scrollTo(), invokeButton())
            selectMenu(manual.title)
            scenario.onActivity {
                assertEquals(manual, a.preferences.video); assertEquals(NativeQualityRequest.auto, b.preferences.video)
                assertEquals(0, writes)
                val text = descendants(viewer).filterIsInstance<TextView>().joinToString("\n") { it.text }
                assertTrue(text.contains("requested: ${manual.title}")); assertTrue(text.contains("Actual Video: ${actual.summary()}"))
                control(viewer, "Save A · Sumo video preference").performClick()
                assertEquals(1, writes); assertEquals(manual, b.preferences.video)
            }
            onView(withContentDescription(startsWith("B · Sumo Video quality"))).perform(scrollTo(), invokeButton())
            selectMenu("Auto for this feed")
            scenario.onActivity {
                assertEquals(manual, a.preferences.video); assertEquals(NativeQualityRequest.auto, b.preferences.video)
                control(viewer, "Reset A · Sumo stream video").performClick()
                assertEquals(2, writes)
                assertEquals(manual, a.preferences.video) // Explicit feed override survives saved-default reset.
            }
            onView(withContentDescription(startsWith("A · Sumo Video quality"))).perform(scrollTo(), invokeButton())
            selectMenu("Use stream default (Auto)")
            scenario.onActivity {
                assertEquals(NativeQualityRequest.auto, a.preferences.video)
                assertEquals(NativeQualityRequest.auto, b.preferences.video)
                viewer.endSession(); state.clearOverrides()
            }
        }
        pair.close()
        assertEquals(NativeQualityPreferences(), ViewerQualityState(listOf(source, source), saved).effective(NativeMixedSide.A))
    }

    @Test fun qualityControlsDisableDuringSaveAndPreserveUnavailableRequestedFeedback() {
        val state = ViewerQualityReadback(manual, null, saving = true, message = "Fixture save failure")
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var viewer: NativePairViewer
            scenario.onActivity { activity ->
                viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity), "A", "B",
                    { null }, {}, {}, {}, {}, {}, {}, {}, qualityState = { _, kind ->
                        if (kind == NativeQualityKind.VIDEO) state else state.copy(streamDefault = NativeQualityRequest.auto)
                    })
                activity.setContentView(viewer)
            }
            scenario.onActivity { assertTrue(button(viewer, "More").performClick()) }
            selectMenu("Quality")
            scenario.onActivity {
                assertFalse(control(viewer, "A Video quality").isEnabled)
                assertFalse(control(viewer, "Save A video preference").isEnabled)
                assertFalse(control(viewer, "Reset A stream video").isEnabled)
                val text = descendants(viewer).filterIsInstance<TextView>().joinToString("\n") { it.text }
                assertTrue(text.contains("requested: ${manual.title}")); assertTrue(text.contains("Actual Video: unavailable"))
                assertTrue(text.contains("Fixture save failure"))
                viewer.endSession()
            }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun control(view: View, prefix: String) = descendants(view).filterIsInstance<Button>()
        .first { it.contentDescription?.startsWith(prefix) == true }
    private fun button(view: View, title: String) = descendants(view).filterIsInstance<Button>()
        .single { it.text.toString() == title }.also { assertTrue(it.isShown); assertTrue(it.isEnabled) }

    private fun invokeButton() = object : ViewAction {
        override fun getConstraints() = isDisplayed()
        override fun getDescription() = "Activate the visible quality control without changing input mode"
        override fun perform(uiController: UiController, view: View) {
            assertTrue(view is Button); assertTrue(view.isEnabled)
            assertTrue(view.performClick())
            uiController.loopMainThreadUntilIdle()
        }
    }

    private fun selectMenu(title: String) {
        val item = object : TypeSafeMatcher<Any>() {
            override fun describeTo(description: Description) { description.appendText("MenuItem title: $title") }
            override fun matchesSafely(value: Any) = value is MenuItem && value.title.toString() == title
        }
        onData(item).inRoot(isPlatformPopup()).check(matches(isDisplayed())).perform(object : ViewAction {
            override fun getConstraints() = isDisplayed()
            override fun getDescription() = "Select the visible menu row without changing input mode"
            override fun perform(uiController: UiController, view: View) {
                var row = view
                while (row.parent is View && row.parent !is AdapterView<*>) row = row.parent as View
                val list = row.parent as AdapterView<*>
                val position = list.getPositionForView(row)
                assertTrue(position != AdapterView.INVALID_POSITION)
                assertTrue(list.performItemClick(row, position, list.getItemIdAtPosition(position)))
                uiController.loopMainThreadUntilIdle()
            }
        })
    }
}
