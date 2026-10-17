package net.fstab.tachiai.feature.presentation

import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.fstab.tachiai.platform.media.NativeMixedSide
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePairViewerRecoveryTest {
    @Test fun recoveryTargetsEitherFloatingFeedWithoutSwappingAndDisablesWhilePending() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (side in NativeMixedSide.entries) {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                lateinit var viewer: NativePairViewer
                lateinit var pane: View
                lateinit var action: Button
                lateinit var player: PlayerView
                var pending = false
                var failed = true
                val recoveries = mutableListOf<NativeMixedSide>()
                var otherCommands = 0
                scenario.onActivity { activity ->
                    val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                        orientation = Configuration.ORIENTATION_LANDSCAPE
                    })
                    val a = PlayerView(context)
                    val b = PlayerView(context)
                    player = if (side == NativeMixedSide.A) a else b
                    viewer = NativePairViewer(context, a, b, "A · News", "B · Travel", { null },
                        { otherCommands++ }, { otherCommands++ }, {}, {}, {}, {}, {},
                        feedMessage = { if (it == side && failed) {
                            if (pending) "Retrying…" else "Playback fell behind live."
                        } else null },
                        feedRecovery = { if (it == side) ViewerFeedRecovery("Go live", !pending) else null },
                        onRecoverFeed = { recoveries.add(it); pending = true })
                    activity.setContentView(viewer)
                    viewer.open()
                    if (side == NativeMixedSide.A) (b.parent as View).performClick()
                    layout(viewer)
                    pane = player.parent as View
                    action = descendants(viewer).filterIsInstance<Button>().single { it.text == "Go live" }
                    assertTrue(action.isShown)
                    assertTrue(action.isEnabled)
                    assertTrue(action.isFocusable)
                    assertTrue(action.contentDescription.toString().startsWith("${side.name} · "))
                    assertTrue("Action must fit the floating pane",
                        Rect(0, 0, pane.width, pane.height).contains(actionBounds(pane as ViewGroup, action)))
                    assertFloating(viewer, pane)
                    val bounds = actionBounds(viewer, action)
                    gesture(viewer, bounds, listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP))
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertTrue(recoveries.isEmpty())
                    assertFloating(viewer, pane)
                    gesture(viewer, actionBounds(viewer, action), listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP))
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertEquals(listOf(side), recoveries)
                    assertFalse(action.isEnabled)
                    assertFloating(viewer, pane)
                    // Repeated events and tapping the disabled action must never
                    // escape into the floating pane's swap handler.
                    gesture(viewer, actionBounds(viewer, action), listOf(MotionEvent.ACTION_UP))
                    gesture(viewer, actionBounds(viewer, action), listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP))
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertEquals(listOf(side), recoveries)
                    assertFloating(viewer, pane)
                    assertEquals(0, otherCommands)
                    failed = false
                    viewer.refresh()
                    assertEquals(View.VISIBLE, player.visibility)
                    assertEquals(View.GONE, action.visibility)
                    viewer.endSession()
                }
            }
        }
    }

    @Test fun retryRechecksCurrentAdmissionBeforeInvokingTheHost() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            var enabled = true
            var recoveries = 0
            val viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity), "A", "B", { null },
                {}, {}, {}, {}, {}, {}, {}, feedMessage = { "Connection interrupted." },
                feedRecovery = { if (it == NativeMixedSide.B) ViewerFeedRecovery("Retry", enabled) else null },
                onRecoverFeed = { recoveries++ })
            activity.setContentView(viewer)
            viewer.open()
            val action = descendants(viewer).filterIsInstance<Button>().single { it.text == "Retry" }
            enabled = false
            action.performClick()
            assertEquals(0, recoveries)
            viewer.refresh()
            assertFalse(action.isEnabled)
            viewer.endSession()
        } }
    }

    private fun assertFloating(viewer: NativePairViewer, pane: View) {
        layout(viewer)
        assertTrue("Recovery must retain the floating feed", pane.width < viewer.getChildAt(0).width)
    }
    private fun layout(viewer: View) {
        fun dp(value: Int) = (value * viewer.resources.displayMetrics.density).toInt()
        viewer.measure(View.MeasureSpec.makeMeasureSpec(dp(850), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(420), View.MeasureSpec.EXACTLY))
        viewer.layout(0, 0, viewer.measuredWidth, viewer.measuredHeight)
    }
    private fun actionBounds(viewer: ViewGroup, action: View) = Rect().also {
        action.getDrawingRect(it)
        viewer.offsetDescendantRectToMyCoords(action, it)
    }
    private fun gesture(viewer: View, bounds: Rect, actions: List<Int>) {
        val now = SystemClock.uptimeMillis()
        actions.forEachIndexed { index, action ->
            val event = MotionEvent.obtain(now, now + index * 16L, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { assertTrue(viewer.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
