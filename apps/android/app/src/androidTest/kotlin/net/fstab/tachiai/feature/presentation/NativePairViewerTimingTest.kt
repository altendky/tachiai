package net.fstab.tachiai.feature.presentation

import android.view.View
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
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

@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePairViewerTimingTest {
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
            onView(withText("Timing")).perform(click())
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
                    it.text.toString().contains("Pauses both feeds. Press Play")
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
            onView(withText("More")).perform(click())
            scenario.onActivity { current = replacement; viewer.refresh() }
            onView(withText("Catch up Alpha (holds both)")).perform(click())
            scenario.onActivity { assertTrue("An alias must retain its originally displayed member", requested.isEmpty()) }
            onView(withText("More")).perform(click())
            onView(withText("Catch up Beta (holds both)")).perform(click())
            scenario.onActivity {
                assertEquals(listOf(NativeMixedSide.B), requested)
                replacementB.snapshot = replacementB.snapshot.copy(live = false, dynamic = false)
                viewer.refresh()
            }
            onView(withText("More")).perform(click())
            onView(withText("Catch up Beta (holds both)"))
                .check(matches(isDescendantOfA(not(isEnabled())))).perform(click())
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
                        it.text.toString().contains("Pauses this feed. Press Play")
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

    private fun timingButtons(view: View, feed: String) = descendants(view).filterIsInstance<Button>()
        .filter { it.contentDescription?.startsWith("Advance $feed by ") == true }

    private fun button(view: View, prefix: String): Button {
        if (view is Button && view.text.startsWith(prefix)) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            val found = runCatching { button(view.getChildAt(index), prefix) }.getOrNull()
            if (found != null) return found
        }
        error("Fixture control not found: $prefix")
    }
}
