package net.fstab.tachiai.feature.presentation

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.startsWith

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
            onView(withText("More")).perform(click()); onView(withText("Quality")).perform(click())
            onView(withContentDescription(startsWith("A · Sumo Video quality"))).perform(scrollTo(), click())
            onView(withText(manual.title)).perform(click())
            scenario.onActivity {
                assertEquals(manual, a.preferences.video); assertEquals(NativeQualityRequest.auto, b.preferences.video)
                assertEquals(0, writes)
                val text = descendants(viewer).filterIsInstance<TextView>().joinToString("\n") { it.text }
                assertTrue(text.contains("requested: ${manual.title}")); assertTrue(text.contains("Actual Video: ${actual.summary()}"))
                control(viewer, "Save A · Sumo video preference").performClick()
                assertEquals(1, writes); assertEquals(manual, b.preferences.video)
            }
            onView(withContentDescription(startsWith("B · Sumo Video quality"))).perform(scrollTo(), click())
            onView(withText("Auto for this feed")).perform(click())
            scenario.onActivity {
                assertEquals(manual, a.preferences.video); assertEquals(NativeQualityRequest.auto, b.preferences.video)
                control(viewer, "Reset A · Sumo stream video").performClick()
                assertEquals(2, writes)
                assertEquals(manual, a.preferences.video) // Explicit feed override survives saved-default reset.
            }
            onView(withContentDescription(startsWith("A · Sumo Video quality"))).perform(scrollTo(), click())
            onView(withText("Use stream default (Auto)")).perform(click())
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
            onView(withText("More")).perform(click()); onView(withText("Quality")).perform(click())
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
}
