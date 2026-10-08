package net.fstab.tachiai.feature.presentation

import android.content.res.Configuration
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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

    @Test fun floatingErrorTextAndEmptyPanelSpaceSwapEitherSideWithoutReplacingPlayers() {
        for (failed in listOf(setOf(NativeMixedSide.A), setOf(NativeMixedSide.B), NativeMixedSide.entries.toSet())) {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario -> scenario.onActivity { activity ->
                val configuration = Configuration(activity.resources.configuration).apply {
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                }
                val context = activity.createConfigurationContext(configuration)
                val a = PlayerView(context).apply { useController = false }
                val b = PlayerView(context).apply { useController = false }
                val player = ExoPlayer.Builder(context).build()
                if (NativeMixedSide.A !in failed) a.player = player
                if (NativeMixedSide.B !in failed) b.player = player
                var commands = 0
                val viewer = NativePairViewer(context, a, b, "A · Mei", "B · Chillhop Radio", { null },
                    { commands++ }, { commands++ }, {}, {}, {}, {}, {},
                    feedMessage = { if (it in failed) "Feed failed." else null }, onSources = {})
                try {
                    activity.setContentView(viewer)
                    viewer.open()
                    layout(viewer, 850, 420)
                    val stage = viewer.getChildAt(0) as ViewGroup
                    val panes = listOf(a.parent as ViewGroup, b.parent as ViewGroup)
                    for (blank in listOf(false, true)) {
                        for (side in listOf(NativeMixedSide.B, NativeMixedSide.A)) {
                            val pane = panes[side.ordinal]
                            assertTrue(pane.width < stage.width)
                            val y = if (side in failed) {
                                if (blank) pane.height - 2f else 48f * context.resources.displayMetrics.density
                            } else pane.height / 2f
                            if (blank && side in failed) {
                                val scroll = pane.getChildAt(1) as ScrollView
                                assertTrue("Tap must hit empty panel space", y > scroll.getChildAt(0).height)
                            } else if (side in failed) {
                                val notice = (pane.getChildAt(1) as ScrollView).getChildAt(0)
                                assertTrue("Tap must hit message text", y >= notice.paddingTop && y < notice.height - notice.paddingBottom)
                            }
                            tap(viewer, stage.left + pane.left + pane.width / 2f, stage.top + pane.top + y)
                            layout(viewer, 850, 420)
                            assertEquals(stage.width, pane.width)
                            assertSame(panes[0], a.parent)
                            assertSame(panes[1], b.parent)
                            assertSame(if (NativeMixedSide.A in failed) null else player, a.player)
                            assertSame(if (NativeMixedSide.B in failed) null else player, b.player)
                            assertEquals(0, commands)
                        }
                    }
                } finally {
                    viewer.endSession()
                    a.player = null
                    b.player = null
                    player.release()
                }
            } }
        }
    }

    @Test fun scrollingLongFloatingErrorDoesNotSwapTheViews() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val configuration = Configuration(activity.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
            val context = activity.createConfigurationContext(configuration)
            val a = PlayerView(context).apply { useController = false }
            val b = PlayerView(context).apply { useController = false }
            val viewer = NativePairViewer(context, a, b, "A · Mei", "B · Chillhop Radio", { null },
                {}, {}, {}, {}, {}, {}, {},
                feedMessage = { if (it == NativeMixedSide.B) (1..30).joinToString("\n") { "Error detail $it" } else null },
                onSources = {})
            try {
                activity.setContentView(viewer)
                viewer.open()
                layout(viewer, 850, 420)
                val stage = viewer.getChildAt(0) as ViewGroup
                val pane = b.parent as ViewGroup
                val scroll = pane.getChildAt(1) as ScrollView
                val x = stage.left + pane.left + pane.width / 2f
                val top = stage.top + pane.top
                gesture(viewer, listOf(MotionEvent.ACTION_DOWN to top + pane.height * .8f,
                    MotionEvent.ACTION_MOVE to top + pane.height * .2f,
                    MotionEvent.ACTION_UP to top + pane.height * .2f), x)
                layout(viewer, 850, 420)
                assertEquals(stage.width, (a.parent as View).width)
                assertTrue(pane.width < stage.width)
                assertTrue(scroll.scrollY > 0)
            } finally { viewer.endSession() }
        } }
    }

    @Test fun portraitErrorPanelTapRevealsControlsWithoutChangingFeedOrder() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val configuration = Configuration(activity.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_PORTRAIT
            }
            val context = activity.createConfigurationContext(configuration)
            val a = PlayerView(context)
            val b = PlayerView(context)
            val viewer = NativePairViewer(context, a, b, "A · Mei", "B · Chillhop Radio", { null },
                {}, {}, {}, {}, {}, {}, {}, feedMessage = { "Feed failed." }, onSources = {})
            try {
                activity.setContentView(viewer)
                viewer.open()
                button(viewer, "Hide").performClick()
                layout(viewer, 400, 800)
                val stage = viewer.getChildAt(0) as ViewGroup
                val pane = b.parent as ViewGroup
                val paneA = a.parent as ViewGroup
                val dock = viewer.getChildAt(1)
                assertEquals(View.GONE, dock.visibility)
                tap(viewer, stage.left + pane.left + pane.width / 2f, stage.top + pane.top + pane.height / 2f)
                layout(viewer, 400, 800)
                assertEquals(View.VISIBLE, dock.visibility)
                assertEquals(0, paneA.top)
                assertEquals(paneA.bottom, pane.top)
            } finally { viewer.endSession() }
        } }
    }

    private fun layout(viewer: View, width: Int, height: Int) {
        fun dp(value: Int) = (value * viewer.resources.displayMetrics.density).toInt()
        viewer.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(height), View.MeasureSpec.EXACTLY))
        viewer.layout(0, 0, viewer.measuredWidth, viewer.measuredHeight)
    }

    private fun tap(viewer: View, x: Float, y: Float) =
        gesture(viewer, listOf(MotionEvent.ACTION_DOWN to y, MotionEvent.ACTION_UP to y), x)

    private fun gesture(viewer: View, points: List<Pair<Int, Float>>, x: Float) {
        val now = SystemClock.uptimeMillis()
        points.forEachIndexed { index, (action, y) ->
            val event = MotionEvent.obtain(now, now + index * 16L, action, x, y, 0)
            try { assertTrue(viewer.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun button(view: View, label: String) = descendants(view).filterIsInstance<Button>().first { it.text == label }
}
