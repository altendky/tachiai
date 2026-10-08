package net.fstab.tachiai.feature.presentation

import android.content.res.Configuration
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePairViewerTouchTest {
    @Test fun portraitStageBackgroundRevealsControls() = checkBackgroundTap(false)

    @Test fun portraitFooterBackgroundRevealsControls() = checkBackgroundTap(true)

    private fun checkBackgroundTap(footer: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var viewer: NativePairViewer
            lateinit var dock: View
            scenario.onActivity { activity ->
                val configuration = Configuration(activity.resources.configuration).apply {
                    orientation = Configuration.ORIENTATION_PORTRAIT
                }
                val context = activity.createConfigurationContext(configuration)
                viewer = NativePairViewer(context, PlayerView(context), PlayerView(context),
                    "A", "B", { null }, {}, {}, {}, {}, {}, {}, {})
                activity.setContentView(viewer)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val stage = viewer.getChildAt(0) as ViewGroup
                dock = viewer.getChildAt(1)
                dock.visibility = View.GONE
                viewer.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
                viewer.layout(0, 0, 1080, 2400)
                val y = if (footer) viewer.height - 1f else stage.bottom - 1f
                assertTrue("Tap lies below both video panes", y > stage.getChildAt(1).bottom)
                if (footer) assertTrue("Tap lies outside the stage", y > stage.bottom)
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now, action, 540f, y, 0)
                    try { assertTrue(viewer.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
            }
            // View posts its click callback after ACTION_UP on an attached view.
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(View.VISIBLE, dock.visibility)
                viewer.suspendControls()
            }
        }
    }
}
