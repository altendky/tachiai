package net.fstab.tachiai.feature.presentation

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeSeekPlan
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePairViewerFailureTest {
    private class Member : NativePairMember {
        override fun timingSnapshot() = NativeTimingSnapshot(20_000, 60_000, 30_000, null, null, null,
            false, false, true, true, false, false, false, 3, 1f)
        override fun seekToMs(targetMs: Long) = NativeSeekPlan(NativeSeekOutcome.REQUESTED, targetMs)
        override fun setTimingPlaying(playing: Boolean) = true
        override fun setVolume(volume: Float) = true
        override fun close() {}
    }

    @Test fun errorsRemainInTheirSlotsAndSourcesWorksWithNoPlayableFeeds() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val a = PlayerView(activity)
            val b = PlayerView(activity)
            val errors = listOf("No saved Twitch login is available.", "The stream could not be found. HTTP 404.")
            var returns = 0
            val viewer = NativePairViewer(activity, a, b, "A · Mei", "B · Virtual Japan", { null },
                {}, {}, {}, {}, {}, {}, {}, feedMessage = { errors[it.ordinal] }, onSources = { returns++ })
            activity.setContentView(viewer)
            viewer.open()
            val texts = descendants(viewer).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.containsAll(errors + listOf("A · Mei", "B · Virtual Japan")))
            assertEquals(View.GONE, a.visibility)
            assertEquals(View.GONE, b.visibility)
            assertFalse(button(viewer, "Play").isEnabled)
            button(viewer, "Sources").performClick()
            assertEquals(1, returns)
            viewer.endSession()
        } }
    }

    @Test fun eitherHealthySlotEnablesTransportAndKeepsSixToolbarTargetsAtCompactSideWidth() {
        for (healthy in NativeMixedSide.entries) {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario -> scenario.onActivity { activity ->
                val configuration = Configuration(activity.resources.configuration).apply {
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                }
                val context = activity.createConfigurationContext(configuration)
                val a = PlayerView(context)
                val b = PlayerView(context)
                val member = Member()
                val volumes = mutableSetOf<NativeMixedSide>()
                var plays = 0
                val viewer = NativePairViewer(context, a, b, "A · Mei", "B · Chillhop Radio", { null },
                    { plays++ }, {}, {}, {}, {}, {}, {},
                    members = { NativeMixedSide.entries.map { if (it == healthy) member else null } },
                    volumeRequest = { side, _ -> volumes.add(side); true },
                    feedMessage = { if (it == healthy) null else "The stream could not be found. HTTP 404." },
                    onSources = {})
                activity.setContentView(viewer)
                viewer.open()
                assertEquals(View.VISIBLE, if (healthy == NativeMixedSide.A) a.visibility else b.visibility)
                assertEquals(View.GONE, if (healthy == NativeMixedSide.A) b.visibility else a.visibility)
                assertTrue(button(viewer, "Play").isEnabled)
                button(viewer, "Play").performClick()
                assertEquals(1, plays)
                assertEquals(NativeMixedSide.entries.toSet(), volumes)
                button(viewer, "Timing").performClick()
                assertFalse(button(viewer, "Advance A").isEnabled)
                assertFalse(button(viewer, "Advance B").isEnabled)
                fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
                viewer.measure(View.MeasureSpec.makeMeasureSpec(dp(640), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(420), View.MeasureSpec.EXACTLY))
                viewer.layout(0, 0, viewer.measuredWidth, viewer.measuredHeight)
                val dock = viewer.getChildAt(1) as ViewGroup
                val toolbar = dock.getChildAt(1) as ViewGroup
                assertEquals(6, toolbar.childCount)
                for (index in 0 until toolbar.childCount) assertTrue(toolbar.getChildAt(index).width >= dp(48))
                viewer.endSession()
            } }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun button(view: View, label: String) = descendants(view).filterIsInstance<Button>().first { it.text == label }
}
