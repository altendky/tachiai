package net.fstab.tachiai.feature.presentation

import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.fstab.tachiai.platform.media.NativeMixedPair
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeSeekPlan
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.presentation.ViewerPairOffsetReadback
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

// UI contract only: no provider, storage, network, or actual seek operation.
@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePairViewerOffsetTest {
    private class Member : NativePairMember {
        override fun timingSnapshot() = NativeTimingSnapshot(20_000, 60_000, 30_000, null, null, null,
            false, false, true, false, false, false, false, 3, 1f)
        override fun seekToMs(targetMs: Long) = NativeSeekPlan(NativeSeekOutcome.REQUESTED, targetMs)
        override fun setTimingPlaying(playing: Boolean) = true
        override fun setVolume(volume: Float) = true
        override fun close() {}
    }
    private class Fixture(activity: ComponentActivity, television: Boolean) {
        val pair = NativeMixedPair(Member(), Member(), { true }, {})
        var state: ViewerPairOffsetReadback? = ViewerPairOffsetReadback(2_500, false, 0)
        var transportBusy = false
        var plays = 0
        val requests = mutableListOf<Boolean>()
        var onRequest: (Boolean) -> Unit = {}
        val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
            uiMode = uiMode and Configuration.UI_MODE_TYPE_MASK.inv() or
                if (television) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
        })
        val viewer = NativePairViewer(context, PlayerView(context), PlayerView(context), "Alpha", "Beta", { pair },
            { plays++ }, {}, {}, {}, {}, {}, {},
            playbackBusy = { transportBusy }, pairOffsetState = { state },
            onPairOffsetEnabled = { requests += it; onRequest(it) })
        init { activity.setContentView(viewer); viewer.open() }
        fun button(label: String) = descendants(viewer).filterIsInstance<Button>().single { it.text == label }
        fun toggle() = descendants(viewer).filterIsInstance<Button>().single {
            it.contentDescription?.toString()?.startsWith("Pair offset for Alpha and Beta:") == true
        }
        fun readback() = descendants(viewer).filterIsInstance<TextView>().single {
            it.contentDescription?.toString()?.startsWith("Offset for Alpha and Beta:") == true
        }.text.toString()
        fun openTiming() { button("Timing").performClick() }
        fun close() { viewer.endSession(); pair.close() }
    }

    @Test fun toggleUsesConfirmedAppliedStateAndDispatchesOnlyRequestedEnabledValue() = configurations { scenario, fixture ->
        scenario.onActivity {
            fixture.openTiming()
            assertEquals("Offset: Off", fixture.toggle().text.toString())
            assertTrue(fixture.toggle().isEnabled)
            fixture.toggle().performClick()
            assertEquals(listOf(true), fixture.requests)

            fixture.state = ViewerPairOffsetReadback(2_500, true, 2_500)
            fixture.viewer.refresh()
            assertEquals("Offset: On", fixture.toggle().text.toString())
            assertTrue(fixture.toggle().isSelected)
            fixture.toggle().performClick()
            assertEquals(listOf(true, false), fixture.requests)

            fixture.state = ViewerPairOffsetReadback(2_500, true, 500)
            fixture.viewer.refresh()
            assertEquals("Offset: On", fixture.toggle().text.toString())
            fixture.toggle().performClick()
            assertEquals(listOf(true, false, false), fixture.requests)

            fixture.state = ViewerPairOffsetReadback(-250, false, 500)
            fixture.viewer.refresh()
            assertEquals("Offset: On", fixture.toggle().text.toString())
            assertTrue(fixture.toggle().isSelected)
            val text = fixture.readback()
            assertTrue(text.contains("Offset value: advance B by 0.25 s relative to A."))
            assertTrue(text.contains("Applied offset: advance A by 0.5 s relative to B."))
            fixture.toggle().performClick()
            assertEquals(listOf(true, false, false, false), fixture.requests)

            fixture.state = ViewerPairOffsetReadback(2_500, true, 0)
            fixture.viewer.refresh()
            assertEquals("Apply offset", fixture.toggle().text.toString())
            fixture.toggle().performClick()
            assertEquals(listOf(true, false, false, false, true), fixture.requests)

            fixture.state = ViewerPairOffsetReadback(null, false, 500, message = "Offset could not be saved.")
            fixture.viewer.refresh()
            assertEquals("Offset: On", fixture.toggle().text.toString())
            assertTrue(fixture.toggle().isEnabled)
            assertTrue(fixture.readback().contains("Offset could not be saved."))
            fixture.toggle().performClick()
            assertEquals(listOf(true, false, false, false, true, false), fixture.requests)
        }
    }

    @Test fun unavailableUnknownAndMissingOffsetsStayTruthfulAndCannotDispatch() = configurations { scenario, fixture ->
        scenario.onActivity {
            fixture.openTiming()
            fixture.state = ViewerPairOffsetReadback(2_500, true, null, available = false,
                message = "Realign this pair before applying a saved offset.")
            fixture.viewer.refresh()
            assertEquals("Apply offset", fixture.toggle().text.toString())
            assertFalse(fixture.toggle().isEnabled)
            assertFalse(fixture.toggle().isSelected)
            assertTrue(fixture.readback().contains("Current offset is unknown."))
            assertTrue(fixture.readback().contains("Offset value is retained."))
            assertTrue(fixture.readback().contains("Realign this pair"))
            fixture.toggle().performClick()

            fixture.state = ViewerPairOffsetReadback(null, false, 0)
            fixture.viewer.refresh()
            assertEquals("Offset: Off", fixture.toggle().text.toString())
            assertFalse(fixture.toggle().isEnabled)
            assertTrue(fixture.readback().contains("No offset value to apply."))
            assertTrue(fixture.readback().contains("No offset applied."))
            fixture.toggle().performClick()

            fixture.state = ViewerPairOffsetReadback(0, false, 0)
            fixture.viewer.refresh()
            assertEquals("Offset: Off", fixture.toggle().text.toString())
            assertFalse(fixture.toggle().isEnabled)
            fixture.toggle().performClick()
            assertTrue(fixture.requests.isEmpty())

            fixture.state = null
            fixture.viewer.refresh()
            assertFalse("Historical callers do not show saved-offset controls", fixture.toggle().isShown)
        }
    }

    @Test fun staleStateAndTransportBusyAreRecheckedBeforeDispatch() = configurations { scenario, fixture ->
        scenario.onActivity {
            fixture.openTiming()
            assertTrue(fixture.toggle().isEnabled)
            // Change the owner's state without rendering it first.
            fixture.state = fixture.state!!.copy(available = false)
            fixture.toggle().performClick()
            assertFalse(fixture.toggle().isEnabled)
            assertTrue(fixture.requests.isEmpty())

            fixture.state = ViewerPairOffsetReadback(2_500, false, 0)
            fixture.viewer.refresh()
            fixture.transportBusy = true
            fixture.toggle().performClick()
            assertFalse(fixture.toggle().isEnabled)
            assertTrue(fixture.requests.isEmpty())

            fixture.transportBusy = false
            fixture.state = fixture.state!!.copy(busy = true)
            fixture.viewer.refresh()
            assertFalse(fixture.toggle().isEnabled)
            assertTrue(fixture.readback().contains("Updating offset…"))
            fixture.toggle().performClick()
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun busyOffsetRetainsFocusAndDoesNotActivateFallbackTransport() = configurations { scenario, fixture ->
        scenario.onActivity {
            fixture.openTiming()
            assertTrue(fixture.toggle().requestFocus())
            fixture.onRequest = { fixture.state = fixture.state!!.copy(busy = true) }
            fixture.toggle().performClick()
            assertFalse(fixture.toggle().isEnabled)
            fixture.viewer.post { fixture.button("Play").requestFocus() }
        }
        idle()
        scenario.onActivity {
            select(fixture.viewer)
            assertEquals(listOf(true), fixture.requests)
            assertEquals(0, fixture.plays)
            fixture.state = ViewerPairOffsetReadback(2_500, true, 2_500)
            fixture.viewer.refresh()
        }
        idle()
        scenario.onActivity {
            assertTrue(fixture.toggle().isFocused)
            assertEquals("Offset: On", fixture.toggle().text.toString())
            select(fixture.viewer)
            assertEquals(listOf(true, false), fixture.requests)
            assertEquals(0, fixture.plays)
        }
    }

    private fun configurations(test: (ActivityScenario<ComponentActivity>, Fixture) -> Unit) {
        for (television in listOf(false, true)) ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, television) }
            idle()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            idle()
            try { test(scenario, fixture) } finally { scenario.onActivity { fixture.close() } }
        }
    }
    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    private companion object {
        fun select(viewer: NativePairViewer) {
            val now = SystemClock.uptimeMillis()
            viewer.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            viewer.dispatchKeyEvent(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        }
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    }
}
