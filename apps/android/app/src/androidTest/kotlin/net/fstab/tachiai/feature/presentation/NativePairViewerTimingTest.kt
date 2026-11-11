package net.fstab.tachiai.feature.presentation

import android.view.View
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.AdapterView
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.fstab.tachiai.platform.media.NativeMixedPair
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.PrototypePlaybackController
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeSeekPlan
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.platform.media.nativeLiveDefaultPlan
import java.io.File
import net.fstab.tachiai.presentation.ViewerTimingStep
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.hasToString
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed

@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePairViewerTimingTest {
    private class EventMember(position: Long) : NativePairMember {
        var snapshot = NativeTimingSnapshot(position, 10_000_000, position, null, null, null,
            false, false, true, true, false, true, true, 3, 1f)
        var seeks = 0
        var transportRequests = 0
        var onRead: (() -> Unit)? = null
        override fun timingSnapshot(): NativeTimingSnapshot { onRead?.invoke(); return snapshot }
        override fun seekToMs(targetMs: Long): NativeSeekPlan {
            seeks++; snapshot = snapshot.copy(positionMs = targetMs)
            return NativeSeekPlan(NativeSeekOutcome.REQUESTED, targetMs)
        }
        override fun setTimingPlaying(playing: Boolean): Boolean {
            transportRequests++; snapshot = snapshot.copy(playing = playing, playWhenReady = playing)
            return true
        }
        override fun setVolume(volume: Float) = true
        override fun close() {}
    }

    private class LiveMember : NativePairMember {
        var snapshot = NativeTimingSnapshot(20_000, 60_000, 30_000, null, null, 55_000,
            true, true, true, true, false, false, false, 3, 1f)
        var seeks = 0
        override fun timingSnapshot() = snapshot
        override fun seekToMs(targetMs: Long) = NativeSeekPlan(NativeSeekOutcome.REQUESTED, targetMs)
        override fun seekLiveDefault(): NativeSeekPlan {
            seeks++
            return nativeLiveDefaultPlan(snapshot).also {
                if (it.outcome == NativeSeekOutcome.REQUESTED) snapshot = snapshot.copy(positionMs = it.targetMs)
            }
        }
        override fun setTimingPlaying(playing: Boolean): Boolean {
            snapshot = snapshot.copy(playing = playing, playWhenReady = playing); return true
        }
        override fun setVolume(volume: Float) = true
        override fun close() {}
    }
    private class Member : NativePairMember {
        override fun timingSnapshot() = NativeTimingSnapshot(20_000, 60_000, 30_000, null, null, null,
            false, false, true, true, false, false, false, 3, 1f)
        override fun seekToMs(targetMs: Long) = NativeSeekPlan(NativeSeekOutcome.REQUESTED, targetMs)
        override fun setTimingPlaying(playing: Boolean) = true
        override fun setVolume(volume: Float) = true
        override fun close() {}
    }

    @Test fun rowsOfferFiveDirectStepsAndDispatchBothRelativeDirections() {
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
            scenario.onActivity { button(viewer, "Timing").performClick() }
            instrumentation.waitForIdleSync()
            for (choice in ViewerTimingStep.entries) {
                scenario.onActivity {
                    timingButtons(viewer, "A").first { it.text == choice.label }.performClick()
                    timingButtons(viewer, "B").first { it.text == choice.label }.performClick()
                }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity {
                for (feed in listOf("A", "B")) {
                    assertEquals(ViewerTimingStep.entries.map { it.label }, timingButtons(viewer, feed).map { it.text.toString() })
                }
                assertFalse(descendants(viewer).filterIsInstance<Button>().any {
                    it.text.startsWith("Step:") || it.text == "Advance A" || it.text == "Advance B"
                })
                val rowA = timingButtons(viewer, "A")
                val rowB = timingButtons(viewer, "B")
                assertTrue(rowA[0].requestFocus())
                assertSame(rowA[1], rowA[0].focusSearch(View.FOCUS_RIGHT))
                assertSame(rowB[0], rowA[0].focusSearch(View.FOCUS_DOWN))
                assertSame(rowA[0], rowB[0].focusSearch(View.FOCUS_UP))
                button(viewer, "Audio").performClick()
                button(viewer, "Timing").performClick()
                assertEquals(10, timingButtons(viewer, "A").size + timingButtons(viewer, "B").size)
                viewer.endSession()
            }
        }
        pair.close()
        assertEquals(ViewerTimingStep.entries.flatMap { listOf(it.milliseconds, -it.milliseconds) }, requested)
    }

    @Test fun allStepsDisableWhileBusyOrMissingAndFocusTracksRows() {
        val pair = NativeMixedPair(Member(), Member(), { true }, {})
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var available: NativeMixedPair? = pair
                val viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity),
                    "A", "B", { available }, {}, {}, {}, {}, {}, {}, {})
                activity.setContentView(viewer)
                button(viewer, "Timing").performClick()
                val rowA = timingButtons(viewer, "A")
                val rowB = timingButtons(viewer, "B")
                assertTrue((rowA + rowB).all { it.isEnabled && it.isFocusable })
                for (index in rowA.indices) {
                    assertEquals(rowB[index].id, rowA[index].nextFocusDownId)
                    assertEquals(rowA[index].id, rowB[index].nextFocusUpId)
                    if (index < rowA.lastIndex) assertEquals(rowA[index + 1].id, rowA[index].nextFocusRightId)
                    if (index > 0) assertEquals(rowB[index - 1].id, rowB[index].nextFocusLeftId)
                }
                pair.shiftRelative(100)
                assertTrue(pair.busy)
                viewer.refresh()
                assertTrue((rowA + rowB).none { it.isEnabled })
                pair.pause(); viewer.refresh()
                assertTrue((rowA + rowB).all { it.isEnabled })
                available = null; viewer.refresh()
                assertTrue((rowA + rowB).none { it.isEnabled })
                viewer.endSession()
            }
        }
        pair.close()
    }

    @Test fun secondEventMarkAlignsByMeasuredAmountAndRestartingMarksDoesNotSeek() {
        val a = EventMember(500_000); val b = EventMember(10_000)
        val pair = NativeMixedPair(a, b, { true }, {})
        val advances = mutableListOf<Long>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity),
                    "Alpha", "Beta", { pair }, {}, {}, {}, {}, {}, {}, {},
                    onMarkedAlignment = { advances += it; pair.alignRelative(it) })
                activity.setContentView(viewer)
                button(viewer, "Timing").performClick()
                val markA = markButton(viewer, "A"); val markB = markButton(viewer, "B")
                assertTrue(markA.isEnabled && markB.isEnabled)
                markA.performClick()
                assertEquals("Marked", markA.text.toString())
                assertTrue(descendants(viewer).filterIsInstance<android.widget.TextView>().any {
                    it.text.toString().startsWith("A marked.")
                })
                assertEquals(0, a.seeks + b.seeks)
                assertEquals(0, a.transportRequests + b.transportRequests)
                assertTrue(advances.isEmpty())
                a.snapshot = a.snapshot.copy(positionMs = 525_000)
                b.snapshot = b.snapshot.copy(positionMs = 35_000)
                markB.performClick()
                assertEquals(listOf(-25_000L), advances)
                assertEquals(-25_000L, pair.requestedAdjustmentMs)
                assertEquals(1, a.seeks); assertEquals(0, b.seeks)
                assertEquals(500_000L, a.snapshot.positionMs)
                assertTrue(pair.busy)
                assertEquals("Marked", markA.text.toString()); assertEquals("Marked", markB.text.toString())
                pair.poll(); viewer.refresh()
                assertFalse(pair.busy)
                assertTrue(a.snapshot.playing && b.snapshot.playing)
                assertTrue(descendants(viewer).filterIsInstance<android.widget.TextView>().any {
                    it.text.toString().contains("Alignment applied. Marks cleared;")
                })
                viewer.refresh(); assertEquals(listOf(-25_000L), advances)
                button(viewer, "Audio").performClick(); button(viewer, "Timing").performClick()
                assertEquals("Mark", markA.text.toString()); assertEquals("Mark", markB.text.toString())
                assertEquals(markA.id, timingButtons(viewer, "Alpha").last().nextFocusRightId)
                assertEquals(markB.id, markA.nextFocusDownId)
                assertEquals(markA.id, markB.nextFocusUpId)
                assertEquals(listOf(-25_000L), advances)
                assertEquals(-25_000L, pair.requestedAdjustmentMs)
                assertEquals(1, a.seeks + b.seeks)
                assertFalse(pair.busy)
                markA.performClick()
                assertEquals("Marked", markA.text.toString()); assertEquals("Mark", markB.text.toString())
                assertEquals(listOf(-25_000L), advances)
                viewer.endSession()
            }
        }
        pair.close()
    }

    @Test fun aFreshMarkPairAddsOrSubtractsAFinerCorrectionFromTheAppliedFirstPair() {
        for (secondFirst in NativeMixedSide.entries) {
            val a = EventMember(500_000); val b = EventMember(9_000_000)
            val pair = NativeMixedPair(a, b, { true }, {})
            val adjustments = mutableListOf<Long>()
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity), "Alpha", "Beta", { pair },
                        {}, {}, {}, {}, {}, {}, {}, onMarkedAlignment = { delta -> adjustments += delta; pair.alignRelative(delta) })
                    activity.setContentView(viewer); button(viewer, "Timing").performClick()
                    markButton(viewer, "A").performClick()
                    a.snapshot = a.snapshot.copy(positionMs = 525_000)
                    b.snapshot = b.snapshot.copy(positionMs = 9_025_000)
                    markButton(viewer, "B").performClick()
                    pair.poll(); viewer.refresh()
                    assertEquals(-25_000L, pair.requestedAdjustmentMs)
                    assertEquals("Mark", markButton(viewer, "A").text.toString())
                    assertEquals("Mark", markButton(viewer, "B").text.toString())
                    assertEquals(listOf(-25_000L), adjustments)
                    assertEquals(-25_000L, pair.requestedAdjustmentMs)
                    assertFalse(pair.busy)
                    markButton(viewer, secondFirst.name).performClick()
                    assertEquals("Mark", markButton(viewer, if (secondFirst == NativeMixedSide.A) "B" else "A").text.toString())
                    a.snapshot = a.snapshot.copy(positionMs = checkNotNull(a.snapshot.positionMs) + 1_000)
                    b.snapshot = b.snapshot.copy(positionMs = checkNotNull(b.snapshot.positionMs) + 1_000)
                    markButton(viewer, if (secondFirst == NativeMixedSide.A) "B" else "A").performClick()
                    pair.poll(); viewer.refresh()
                    val fine = if (secondFirst == NativeMixedSide.A) -1_000L else 1_000L
                    assertEquals(listOf(-25_000L, fine), adjustments)
                    assertEquals(-25_000L + fine, pair.requestedAdjustmentMs)
                    assertEquals(fine, pair.lastTimingSettlement?.deltaMs)
                    assertTrue(a.snapshot.playing && b.snapshot.playing)
                    viewer.endSession()
                }
            }
            pair.close()
        }
    }

    @Test fun unknownHistoricalTotalDoesNotLabelASuccessfulFreshAlignmentAsInvalid() {
        val a = EventMember(500_000); val b = EventMember(9_000_000)
        val pair = NativeMixedPair(a, b, { true }, {}, initialAdjustmentValid = false)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity), "Alpha", "Beta", { pair },
                    {}, {}, {}, {}, {}, {}, {}, onMarkedAlignment = { pair.alignRelative(it) })
                activity.setContentView(viewer); button(viewer, "Timing").performClick()
                markButton(viewer, "A").performClick()
                a.snapshot = a.snapshot.copy(positionMs = 501_000)
                b.snapshot = b.snapshot.copy(positionMs = 9_001_000)
                markButton(viewer, "B").performClick(); pair.poll(); viewer.refresh()
                assertFalse(pair.requestedAdjustmentValid)
                assertEquals(1L, pair.lastTimingSettlement?.revision)
                val text = descendants(viewer).filterIsInstance<android.widget.TextView>().joinToString("\n") { it.text }
                assertFalse(text.contains("Timing anchor invalid"))
                assertTrue(text.contains("Earlier adjustment total is unknown"))
                assertTrue(text.contains("Marked alignment playback requested"))
                viewer.endSession()
            }
        }
        pair.close()
    }

    @Test fun longMarkedCorrectionSplitsAcrossBothLiveWindowsAndResumes() {
        val a = EventMember(20_500); val b = EventMember(10_000)
        a.snapshot = a.snapshot.copy(live = true, dynamic = true, windowStartMs = 1_000_000, durationMs = 30_000)
        b.snapshot = b.snapshot.copy(live = true, dynamic = true, windowStartMs = 9_000_000, durationMs = 20_000)
        val pair = NativeMixedPair(a, b, { true }, {}, requireCurrentLiveWindowOnPlay = true)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity),
                    "ABEMA", "Twitch", { pair }, {}, {}, {}, {}, {}, {}, {}, onMarkedAlignment = pair::alignRelative)
                activity.setContentView(viewer); button(viewer, "Timing").performClick()
                markButton(viewer, "A").performClick()
                a.snapshot = a.snapshot.copy(windowStartMs = 1_026_400)
                b.snapshot = b.snapshot.copy(windowStartMs = 9_026_400)
                markButton(viewer, "B").performClick()
                assertEquals(-26_400L, pair.requestedAdjustmentMs)
                assertEquals(1, a.seeks); assertEquals(1, b.seeks)
                assertEquals(2_050L, a.snapshot.positionMs); assertEquals(17_950L, b.snapshot.positionMs)
                assertTrue(pair.busy)
                pair.poll(); viewer.refresh()
                assertFalse(pair.busy)
                assertTrue(a.snapshot.playing && b.snapshot.playing)
                assertTrue(descendants(viewer).filterIsInstance<android.widget.TextView>().any {
                    it.text.toString().contains("Alignment applied. Marks cleared;")
                })
                viewer.endSession()
            }
        }
        pair.close()
    }

    @Test fun oppositeMarkOrderRequestsOppositeAlignmentAndShowsARefusalWithoutRetrying() {
        val a = EventMember(500_000); val b = EventMember(10_000)
        val pair = NativeMixedPair(a, b, { true }, {})
        val requests = mutableListOf<Long>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity),
                    "A", "B", { pair }, {}, {}, {}, {}, {}, {}, {},
                    onMarkedAlignment = { requests += it; NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) })
                activity.setContentView(viewer); button(viewer, "Timing").performClick()
                markButton(viewer, "B").performClick()
                assertTrue(requests.isEmpty())
                a.snapshot = a.snapshot.copy(positionMs = 525_000)
                b.snapshot = b.snapshot.copy(positionMs = 35_000)
                markButton(viewer, "A").performClick()
                assertEquals(listOf(25_000L), requests)
                assertTrue(descendants(viewer).filterIsInstance<android.widget.TextView>().any {
                    it.text.toString().contains("Full alignment is unavailable")
                })
                viewer.refresh(); assertEquals(listOf(25_000L), requests)
                assertEquals("Marked", markButton(viewer, "A").text.toString())
                assertEquals("Marked", markButton(viewer, "B").text.toString())
                assertEquals(0, a.seeks + b.seeks)
                assertEquals(0L, pair.requestedAdjustmentMs)
                viewer.endSession()
            }
        }
        pair.close()
    }

    @Test fun simultaneousMarksNeedNoSeekOrTransportChange() {
        val a = EventMember(500_000); val b = EventMember(10_000)
        val pair = NativeMixedPair(a, b, { true }, {})
        var requests = 0
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity),
                    "A", "B", { pair }, {}, {}, {}, {}, {}, {}, {},
                    onMarkedAlignment = { requests++; NativeSeekPlan(NativeSeekOutcome.REQUESTED) })
                activity.setContentView(viewer); button(viewer, "Timing").performClick()
                markButton(viewer, "A").performClick(); markButton(viewer, "B").performClick()
                assertEquals(0, requests)
                assertEquals(0, a.seeks + b.seeks)
                assertEquals(0, a.transportRequests + b.transportRequests)
                assertEquals("Mark", markButton(viewer, "A").text.toString())
                assertEquals("Mark", markButton(viewer, "B").text.toString())
                assertTrue(descendants(viewer).filterIsInstance<android.widget.TextView>().any {
                    it.text.toString().startsWith("No adjustment needed")
                })
                viewer.endSession()
            }
        }
        pair.close()
    }

    @Test fun eventMarksRefuseNewAdMarksButAllowClearingAndClearOnMemberReplacement() {
        val a = EventMember(500_000); val b = EventMember(10_000)
        val first = NativeMixedPair(a, b, { true }, {})
        val replacement = NativeMixedPair(EventMember(100_000), EventMember(200_000), { true }, {})
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var current: NativeMixedPair? = first
                val viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity),
                    "A", "B", { current }, {}, {}, {}, {}, {}, {}, {})
                activity.setContentView(viewer)
                button(viewer, "Timing").performClick()
                val markA = markButton(viewer, "A"); val markB = markButton(viewer, "B")
                markA.performClick()
                b.snapshot = b.snapshot.copy(playingAd = true); viewer.refresh()
                assertTrue(markA.isEnabled); assertFalse(markB.isEnabled)
                markA.performClick()
                assertEquals("Mark", markA.text.toString()); assertEquals("Mark", markB.text.toString())
                assertFalse(markA.isEnabled || markB.isEnabled)
                b.snapshot = b.snapshot.copy(playingAd = false); viewer.refresh()
                markA.performClick()
                first.shiftRelative(100); viewer.refresh(); assertTrue(first.busy)
                assertFalse(markA.isEnabled || markB.isEnabled)
                markB.performClick(); assertEquals("Mark", markB.text.toString())
                first.pause(); viewer.refresh(); assertTrue(markB.isEnabled)
                current = replacement; viewer.refresh()
                assertEquals("Mark", markA.text.toString()); assertEquals("Mark", markB.text.toString())
                current = null; viewer.refresh(); assertFalse(markA.isEnabled || markB.isEnabled)
                viewer.endSession()
            }
        }
        first.close(); replacement.close()
    }

    @Test fun changingPairDuringSecondMarkOrUsingRetiredControlsCannotAlignAnotherPair() {
        val a = EventMember(500_000); val b = EventMember(10_000)
        val original = NativeMixedPair(a, b, { true }, {})
        val replacement = NativeMixedPair(EventMember(100_000), EventMember(200_000), { true }, {})
        var requests = 0
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var current = original
                val viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity),
                    "A", "B", { current }, {}, {}, {}, {}, {}, {}, {},
                    onMarkedAlignment = { requests++; NativeSeekPlan(NativeSeekOutcome.REQUESTED) })
                activity.setContentView(viewer); button(viewer, "Timing").performClick()
                val markA = markButton(viewer, "A"); val markB = markButton(viewer, "B")
                markA.performClick()
                a.onRead = { a.onRead = null; current = replacement }
                markB.performClick()
                assertEquals(0, requests)
                assertEquals("Mark", markA.text.toString()); assertEquals("Mark", markB.text.toString())
                viewer.endSession()
                markA.performClick(); markB.performClick()
                assertEquals(0, requests)
            }
        }
        original.close(); replacement.close()
    }

    @Test fun markedRowsKeepMinimumTargetsInCompactPortraitAndLandscape() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for ((widthDp, heightDp) in listOf(320 to 640, 640 to 360)) {
                    val configuration = android.content.res.Configuration(activity.resources.configuration).apply {
                        orientation = if (widthDp > heightDp) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                            else android.content.res.Configuration.ORIENTATION_PORTRAIT
                    }
                    val context = activity.createConfigurationContext(configuration).apply { theme.setTo(activity.theme) }
                    val pair = NativeMixedPair(EventMember(100_000), EventMember(200_000), { true }, {})
                    val viewer = NativePairViewer(context, PlayerView(context), PlayerView(context),
                        "A", "B", { pair }, {}, {}, {}, {}, {}, {}, {})
                    activity.setContentView(viewer)
                    button(viewer, "Timing").performClick()
                    val density = context.resources.displayMetrics.density
                    val width = (widthDp * density).toInt(); val height = (heightDp * density).toInt()
                    viewer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    viewer.layout(0, 0, width, height)
                    for (feed in listOf("A", "B")) {
                        val controls = timingButtons(viewer, feed) + markButton(viewer, feed)
                        assertEquals(6, controls.size)
                        assertTrue("All timing targets must be at least 48 dp wide", controls.all { it.measuredWidth >= (48 * density).toInt() })
                        assertTrue("All timing targets must be at least 48 dp tall", controls.all { it.measuredHeight >= (48 * density).toInt() })
                    }
                    viewer.endSession(); pair.close()
                }
            }
        }
    }

    @Test fun backToLiveWhilePlayingResumesBothAfterHeldSettlement() {
        for (side in NativeMixedSide.entries) {
            val a = LiveMember(); val b = LiveMember()
            a.snapshot = a.snapshot.copy(playing = true, playWhenReady = true)
            b.snapshot = b.snapshot.copy(playing = true, playWhenReady = true)
            val pair = NativeMixedPair(a, b, { true }, {})
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                lateinit var viewer: NativePairViewer
                scenario.onActivity { activity ->
                    viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity), "Alpha", "Beta", { pair },
                        {}, {}, {}, { pair.catchUp(it) }, {}, {}, {})
                    activity.setContentView(viewer); viewer.open()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity {
                    button(viewer, "Back to live ${side.name}").performClick()
                    assertTrue(pair.busy)
                    assertFalse(a.snapshot.playWhenReady || b.snapshot.playWhenReady)
                    pair.poll(); viewer.refresh()
                    assertFalse(pair.busy)
                    assertTrue(a.snapshot.playWhenReady && b.snapshot.playWhenReady)
                    assertEquals(1, a.seeks + b.seeks)
                    assertFalse(pair.requestedAdjustmentValid)
                    viewer.endSession()
                }
            }
            pair.close()
        }
    }

    @Test fun visibleBackToLiveTargetsBothSidesAndPreservesHeldSettlement() {
        val a = LiveMember(); val b = LiveMember()
        val requested = mutableListOf<NativeMixedSide>()
        val pair = NativeMixedPair(a, b, { true }, {})
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var viewer: NativePairViewer
            scenario.onActivity { activity ->
                viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity), "Alpha", "Beta", { pair },
                    {}, {}, {}, { side -> requested.add(side); pair.catchUp(side) }, {}, {}, {})
                activity.setContentView(viewer); viewer.open()
                assertTrue(button(viewer, "Back to live A").isShown)
                assertTrue(button(viewer, "Back to live B").isShown)
                assertTrue(button(viewer, "Back to live A").isEnabled)
                assertTrue(button(viewer, "Back to live B").isEnabled)
                assertTrue(button(viewer, "Back to live A").contentDescription.contains("Alpha"))
                assertTrue(button(viewer, "Back to live B").contentDescription.contains("Beta"))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            captureOwnedFixtureIfRequested()
            scenario.onActivity {
                button(viewer, "Back to live A").performClick()
                assertEquals(1, a.seeks); assertEquals(0, b.seeks)
                assertTrue(pair.busy); assertFalse(pair.requestedAdjustmentValid)
                assertFalse(a.snapshot.playWhenReady); assertFalse(b.snapshot.playWhenReady)
                assertFalse(button(viewer, "Back to live B").isEnabled)
                button(viewer, "Back to live B").performClick()
                assertEquals(0, b.seeks)
                pair.poll(); viewer.refresh()
                assertFalse(pair.busy)
                button(viewer, "Back to live B").performClick()
                assertEquals(1, b.seeks); pair.poll(); viewer.refresh()
                assertTrue(descendants(viewer).filterIsInstance<android.widget.TextView>().any {
                    it.text.toString().contains("resumes both feeds if both were playing")
                })
                val left = button(viewer, "Back to live A"); val right = button(viewer, "Back to live B")
                assertEquals(right.id, left.nextFocusRightId); assertEquals(left.id, right.nextFocusLeftId)
                viewer.endSession()
            }
        }
        pair.close()
        assertEquals(listOf(NativeMixedSide.A, NativeMixedSide.B), requested)
    }

    @Test fun liveCapabilityChangesDisableActionsAndReplayOrMissingFeedsDoNotAdvertiseThem() {
        val a = LiveMember(); val b = LiveMember()
        val pair = NativeMixedPair(a, b, { true }, {})
        var requested = 0
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity), "A", "B", { pair },
                    {}, {}, {}, { requested++ }, {}, {}, {})
                activity.setContentView(viewer); viewer.open()
                val action = button(viewer, "Back to live A")
                for (changed in listOf(a.snapshot.copy(canSeekDefault = false), a.snapshot.copy(playingAd = true),
                    a.snapshot.copy(state = 2), a.snapshot.copy(defaultPositionMs = null),
                    a.snapshot.copy(defaultPositionMs = 70_000), a.snapshot.copy(positionMs = 55_000))) {
                    val before = a.snapshot; a.snapshot = changed; viewer.refresh()
                    assertFalse(action.isEnabled); action.performClick(); assertEquals(0, requested)
                    a.snapshot = before
                }
                viewer.refresh(); assertTrue(action.isEnabled)
                b.snapshot = b.snapshot.copy(playingAd = true); viewer.refresh()
                assertFalse(action.isEnabled); b.snapshot = b.snapshot.copy(playingAd = false)
                a.snapshot = a.snapshot.copy(live = false, dynamic = false); viewer.refresh()
                assertEquals(View.GONE, action.visibility)
                a.snapshot = a.snapshot.copy(dynamic = true); viewer.refresh()
                assertEquals(View.GONE, action.visibility) // Backend cannot recover a dynamic-only clock.
                b.snapshot = b.snapshot.copy(live = false, dynamic = false); viewer.refresh()
                assertEquals(View.GONE, button(viewer, "Back to live B").visibility)
                viewer.endSession()
            }
        }
        pair.close()
    }

    @Test fun staleDisplayedMemberAndChangesDuringCapabilityReadNeverDispatch() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val first = LiveMember(); val replacement = LiveMember()
                var selected: NativePairMember? = first
                var changeDuringRead = false
                var admitted = true
                var requested = 0
                val viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity), "A", "B", { null },
                    {}, {}, {}, { requested++ }, {}, {}, {}, members = { listOf(selected, null) },
                    catchUpPlan = {
                        val plan = if (admitted) nativeLiveDefaultPlan(selected?.timingSnapshot())
                            else NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
                        if (changeDuringRead) { changeDuringRead = false; selected = replacement }
                        plan
                    })
                activity.setContentView(viewer); viewer.open()
                val action = button(viewer, "Back to live A")
                assertTrue(action.isEnabled)
                selected = replacement; action.performClick(); assertEquals(0, requested)
                selected = first; viewer.refresh()
                changeDuringRead = true; action.performClick(); assertEquals(0, requested)
                selected = first; viewer.refresh(); admitted = false
                action.performClick(); assertEquals(0, requested); assertFalse(action.isEnabled)
                admitted = true; viewer.refresh()
                first.snapshot = first.snapshot.copy(playingAd = true)
                action.performClick(); assertEquals(0, requested)
                selected = null; viewer.refresh(); assertEquals(View.GONE, action.visibility)
                viewer.endSession(); action.performClick(); assertEquals(0, requested)
            }
        }
    }

    @Test fun catchUpAliasesRejectChangedMembersAndDisableReplayWhileFreshLiveDispatches() {
        val originalA = LiveMember(); val originalB = LiveMember()
        val replacementA = LiveMember(); val replacementB = LiveMember()
        val original = NativeMixedPair(originalA, originalB, { true }, {})
        val replacement = NativeMixedPair(replacementA, replacementB, { true }, {})
        var current = original
        val requested = mutableListOf<NativeMixedSide>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var viewer: NativePairViewer
            scenario.onActivity { activity ->
                viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity), "Alpha", "Beta", { current },
                    {}, {}, {}, { requested.add(it) }, {}, {}, {})
                activity.setContentView(viewer); viewer.open()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { button(viewer, "More").performClick() }
            scenario.onActivity { current = replacement; viewer.refresh() }
            selectMenuItem("Catch up Alpha (holds both)")
            scenario.onActivity { assertTrue("An alias must retain its originally displayed member", requested.isEmpty()) }
            scenario.onActivity { button(viewer, "More").performClick() }
            selectMenuItem("Catch up Beta (holds both)")
            scenario.onActivity {
                assertEquals(listOf(NativeMixedSide.B), requested)
                replacementB.snapshot = replacementB.snapshot.copy(live = false, dynamic = false)
                viewer.refresh()
            }
            scenario.onActivity { button(viewer, "More").performClick() }
            onData(hasToString<Any>("Catch up Beta (holds both)"))
                .inRoot(isPlatformPopup())
                .check(matches(not(isEnabled())))
            selectMenuItem("Catch up Beta (holds both)")
            scenario.onActivity { assertEquals(listOf(NativeMixedSide.B), requested) }
            pressBack()
            scenario.onActivity {
                assertEquals(listOf(NativeMixedSide.B), requested)
                assertEquals(0, originalA.seeks); assertEquals(0, originalB.seeks)
                viewer.endSession()
            }
        }
        original.close(); replacement.close()
    }

    @Test fun aSingleSurvivingLiveFeedUsesTheControllerCapabilityWithoutAPair() {
        for (side in NativeMixedSide.entries) {
            val member = LiveMember()
            val controller = PrototypePlaybackController({ true })
            controller.setMember(side, member)
            var requested: NativeMixedSide? = null
            var playRequests = 0
            var pauseRequests = 0
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val viewer = NativePairViewer(activity, PlayerView(activity), PlayerView(activity), "A", "B", { controller.pair },
                        { playRequests++; controller.play() }, { pauseRequests++; controller.pause() }, {},
                        { requested = it; controller.catchUp(it) }, {}, {}, {},
                        members = { listOf(controller.member(NativeMixedSide.A), controller.member(NativeMixedSide.B)) },
                        catchUpPlan = controller::catchUpPlan, playbackBusy = { controller.busy })
                    activity.setContentView(viewer); viewer.open()
                    val action = button(viewer, "Back to live ${side.name}")
                    assertTrue(action.isEnabled); action.performClick()
                    assertEquals(side, requested); assertEquals(1, member.seeks)
                    assertFalse(member.snapshot.playWhenReady)
                    assertTrue(descendants(viewer).filterIsInstance<android.widget.TextView>().any {
                        it.text.toString().contains("Returns to live and resumes if playing")
                    })
                    assertTrue(controller.busy)
                    button(viewer, "Pause").performClick()
                    assertFalse(controller.busy); assertEquals(1, pauseRequests); assertEquals(0, playRequests)
                    assertFalse(member.snapshot.playWhenReady)
                    member.snapshot = member.snapshot.copy(positionMs = 20_000); viewer.refresh()
                    assertTrue(action.isEnabled); action.performClick(); assertEquals(2, member.seeks)
                    controller.poll(); viewer.refresh(); assertFalse(controller.busy)
                    assertEquals("Play", button(viewer, "Play").text.toString())
                    assertEquals(0, playRequests); assertFalse(member.snapshot.playWhenReady)
                    assertEquals(View.GONE, button(viewer, "Back to live ${if (side == NativeMixedSide.A) "B" else "A"}").visibility)
                    viewer.endSession()
                }
            }
            controller.close()
        }
    }

    private fun captureOwnedFixtureIfRequested() {
        if (InstrumentationRegistry.getArguments().getString("backToLiveScreenshot") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.cacheDir, "back-to-live-owned-fixture.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun selectMenuItem(title: String) {
        onData(hasToString<Any>(title)).inRoot(isPlatformPopup()).perform(object : ViewAction {
            override fun getConstraints() = isDisplayed()
            override fun getDescription() = "Activate the visible provider-free menu row"
            override fun perform(uiController: UiController, view: View) {
                var row = view
                while (row.parent is View && row.parent !is AdapterView<*>) row = row.parent as View
                val list = row.parent as AdapterView<*>
                val position = list.getPositionForView(row)
                if (row.isEnabled) assertTrue(list.performItemClick(row, position, list.getItemIdAtPosition(position)))
                uiController.loopMainThreadUntilIdle()
            }
        })
    }

    private fun timingButtons(view: View, feed: String) = descendants(view).filterIsInstance<Button>()
        .filter { it.contentDescription?.startsWith("Advance $feed by ") == true }

    private fun markButton(view: View, feed: String) = descendants(view).filterIsInstance<Button>()
        .single { it.contentDescription?.startsWith("Mark matching event in feed $feed ·") == true }

    private fun button(view: View, prefix: String): Button {
        if (view is Button && view.text.startsWith(prefix)) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            val found = runCatching { button(view.getChildAt(index), prefix) }.getOrNull()
            if (found != null) return found
        }
        error("Fixture control not found: $prefix")
    }
}
