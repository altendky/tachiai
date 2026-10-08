package net.fstab.tachiai.feature.presentation

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePairViewerLayoutTest {
    private class Fixture(activity: ComponentActivity, landscape: Boolean, fontScale: Float = 1f) {
        val configuration = Configuration(activity.resources.configuration).apply {
            orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            this.fontScale = fontScale
        }
        val context = activity.createConfigurationContext(configuration)
        val a = PlayerView(context)
        val b = PlayerView(context)
        var commands = 0
        val viewer = NativePairViewer(context, a, b, "A · full source title", "B · full source title", { null },
            { commands++ }, { commands++ }, { commands++ }, { commands++ }, {}, {}, {})
        val stage get() = viewer.getChildAt(0) as ViewGroup
        val dock get() = viewer.getChildAt(1) as ViewGroup
        val scroller get() = dock.getChildAt(0) as ScrollView
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        init { activity.setContentView(viewer) }
        fun layout(width: Int, height: Int) {
            viewer.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(height), View.MeasureSpec.EXACTLY))
            viewer.layout(0, 0, viewer.measuredWidth, viewer.measuredHeight)
        }
    }

    @Test fun sidePanelFitsContentAndAvoidsFloatingPaneWithoutReplacingPlayers() {
        withFixture(true) { scenario, f -> scenario.onActivity {
            f.layout(850, 420)
            val fullWidth = f.stage.width
            val originalFloatingRight = f.stage.left + f.stage.getChildAt(1).right
            button(f.viewer, "Timing").performClick(); f.layout(850, 420)
            assertEquals(fullWidth, f.stage.width)
            assertTrue(f.dock.width <= f.dp(360))
            assertTrue(f.dock.width < f.viewer.width / 2)
            assertTrue("Short content has no fixed 200dp blank area", f.scroller.height < f.dp(200))
            val stageWidth = f.stage.width
            val paneA = f.stage.getChildAt(0) as ViewGroup
            val paneB = f.stage.getChildAt(1) as ViewGroup
            assertTrue(f.stage.left + paneB.right <= f.dock.left)
            button(f.viewer, "Hide").performClick(); f.layout(850, 420)
            assertEquals(fullWidth, f.stage.width)
            assertEquals(originalFloatingRight, f.stage.left + paneB.right)
            f.viewer.performClick(); button(f.viewer, "Timing").performClick(); f.layout(850, 420)
            assertTrue(f.stage.left + paneB.right <= f.dock.left)
            button(f.viewer, "Fit video: off").performClick(); f.layout(850, 420)
            assertEquals(f.dock.left, f.stage.right)
            assertTrue(f.stage.width < stageWidth)
            assertSame(f.a, paneA.getChildAt(0)); assertSame(f.b, paneB.getChildAt(0))
            button(f.viewer, "Hide").performClick(); f.layout(850, 420)
            assertEquals(stageWidth, f.stage.width)
            f.viewer.performClick(); button(f.viewer, "Audio").performClick(); f.layout(850, 420)
            assertEquals("Fit video: on", button(f.viewer, "Fit video:").text.toString())
            button(f.viewer, "Fit video: on").performClick(); f.layout(850, 420)
            assertEquals(stageWidth, f.stage.width)
            assertEquals(0, f.commands)
        } }
    }

    @Test fun portraitAndNarrowLandscapeKeepTargetsAndCapScrollableContent() {
        for (landscape in listOf(false, true)) withFixture(landscape, 1.8f) { scenario, f -> scenario.onActivity {
            button(f.viewer, "Audio").performClick()
            f.layout(if (landscape) 560 else 400, if (landscape) 320 else 800)
            assertEquals(f.viewer.width - f.viewer.paddingLeft - f.viewer.paddingRight, f.dock.width)
            assertTrue(f.dock.top >= f.viewer.paddingTop)
            assertTrue(f.scroller.height <= (f.viewer.height * 0.6f).toInt())
            val toolbar = f.dock.getChildAt(1) as ViewGroup
            for (index in 0 until toolbar.childCount) {
                assertTrue(toolbar.getChildAt(index).width >= f.dp(48))
                assertTrue(toolbar.getChildAt(index).height >= f.dp(48))
            }
            val body = f.scroller.getChildAt(0)
            assertTrue(body.height > f.scroller.height || !landscape)
            f.scroller.scrollTo(0, body.height)
            if (body.height > f.scroller.height) assertTrue(f.scroller.scrollY > 0)
            assertEquals(0, f.commands)
        } }
    }

    @Test fun fullStatusRemainsAvailableWithoutDuplicatingItInAudio() {
        withFixture(false) { scenario, f ->
            onView(withText("More")).perform(click())
            onView(withText("Playback status")).perform(click())
            scenario.onActivity {
                f.layout(400, 800)
                assertEquals(View.VISIBLE, f.scroller.visibility)
                assertTrue(descendants(f.scroller).any { it.contentDescription?.startsWith("Playback status:") == true })
                button(f.viewer, "Audio").performClick(); f.layout(400, 800)
                assertFalse(descendants(f.scroller).any { it.contentDescription?.startsWith("Playback status:") == true })
            }
        }
    }

    private fun withFixture(landscape: Boolean, fontScale: Float = 1f,
        test: (ActivityScenario<ComponentActivity>, Fixture) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, landscape, fontScale) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            try { test(scenario, fixture) } finally { scenario.onActivity { fixture.viewer.endSession() } }
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun button(view: View, prefix: String) = descendants(view).filterIsInstance<Button>()
        .first { it.text.startsWith(prefix) }
}
