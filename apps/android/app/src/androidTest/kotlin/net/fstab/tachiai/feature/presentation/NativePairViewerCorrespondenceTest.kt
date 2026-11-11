package net.fstab.tachiai.feature.presentation

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.fstab.tachiai.platform.media.NativeMixedPair
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeSeekPlan
import net.fstab.tachiai.platform.media.NativeTimelineEvidence
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.presentation.PairOffsetCorrespondence
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

// Only app-owned blank views and in-memory source clocks. No provider, account,
// saved preferences, media, screenshot or actual SDK operation is involved.
@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePairViewerCorrespondenceTest {
    private class Member(position: Long) : NativePairMember {
        var value = NativeTimingSnapshot(position, 1_000_000, position, null, null, null,
            false, false, true, false, false, true, true, 3, 1f)
        var timeline: NativeTimelineEvidence? = NativeTimelineEvidence.Replay(1_000_000)
        var seeks = 0
        var transportRequests = 0
        override fun timingSnapshot() = value
        override fun timelineEvidence() = timeline
        override fun seekToMs(targetMs: Long): NativeSeekPlan {
            seeks++; value = value.copy(positionMs = targetMs)
            return NativeSeekPlan(NativeSeekOutcome.REQUESTED, targetMs)
        }
        override fun setTimingPlaying(playing: Boolean): Boolean {
            transportRequests++; value = value.copy(playing = playing, playWhenReady = playing); return true
        }
        override fun setVolume(volume: Float) = true
        override fun close() {}
    }
    private class Fixture(activity: ComponentActivity, television: Boolean) {
        val a = Member(500_000)
        val b = Member(10_000)
        var nowMs = 0L
        val pair = NativeMixedPair(a, b, { true }, {}, clockMs = { nowMs })
        var currentPair = pair
        var dispatchAlignment = false
        var dispatchDelta: Long? = null
        var afterAlignment: (() -> Unit)? = null
        val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
            uiMode = uiMode and Configuration.UI_MODE_TYPE_MASK.inv() or
                if (television) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL
            orientation = Configuration.ORIENTATION_LANDSCAPE
        })
        val playerA = PlayerView(context)
        val playerB = PlayerView(context)
        val correspondences = mutableListOf<Pair<Long, PairOffsetCorrespondence>>()
        var legacyMarkedCalls = 0
        var relativeCalls = 0
        val viewer = NativePairViewer(context, playerA, playerB, "Alpha", "Beta", { currentPair },
            {}, {}, { relativeCalls++ }, {}, {}, {}, {},
            onMarkedAlignment = { legacyMarkedCalls++; NativeSeekPlan(NativeSeekOutcome.REQUESTED) },
            onMarkedCorrespondence = { delta, value ->
                correspondences += delta to value
                (if (dispatchAlignment) currentPair.alignRelative(dispatchDelta ?: delta)
                else NativeSeekPlan(if (delta == 0L) NativeSeekOutcome.NO_CHANGE else NativeSeekOutcome.REQUESTED))
                    .also { afterAlignment?.invoke() }
            })
        init {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.setContentView(viewer)
            viewer.open()
        }
        fun openTiming() = descendants(viewer).filterIsInstance<Button>().single { it.text == "Timing" }.performClick()
        fun mark(side: NativeMixedSide) = descendants(viewer).filterIsInstance<Button>().single {
            it.contentDescription?.toString()?.startsWith("Mark matching event in feed ${side.name} ·") == true
        }.also { assertTrue(it.isEnabled) }.performClick()
        fun assertMarked(expected: Boolean) {
            val controls = descendants(viewer).filterIsInstance<Button>().filter {
                it.contentDescription?.toString()?.startsWith("Mark matching event in feed ") == true
            }
            assertEquals(2, controls.size)
            controls.forEach { assertEquals(expected, it.isSelected); assertEquals(if (expected) "Marked" else "Mark", it.text.toString()) }
        }
        fun assertClearedMessage() = assertTrue(descendants(viewer).filterIsInstance<TextView>().any {
            it.text.toString().contains("Marks cleared; mark another matching event.")
        })
        fun progress(delta: Long) {
            a.value = a.value.copy(positionMs = checkNotNull(a.value.positionMs) + delta)
            b.value = b.value.copy(positionMs = checkNotNull(b.value.positionMs) + delta)
        }
        fun assertOnlyCorrespondenceWasForwarded() {
            assertEquals(0, legacyMarkedCalls); assertEquals(0, relativeCalls)
            assertEquals(0, a.seeks + b.seeks); assertEquals(0, a.transportRequests + b.transportRequests)
        }
        fun close() { viewer.endSession(); pair.close(); if (currentPair !== pair) currentPair.close() }
    }

    @Test fun completedLiveMarksForwardTheirExactOwnClocksAndCapturedTimelineEvidence() = configurations { scenario, fixture ->
        scenario.onActivity {
            val first = NativeTimelineEvidence.Dash(1_700_000_000_000, 0)
            val second = NativeTimelineEvidence.Dash(9_000_000_000_000, 0)
            fixture.a.value = fixture.a.value.copy(positionMs = 20_000, durationMs = 60_000,
                windowStartMs = first.availabilityStartTimeMs, live = true, dynamic = true)
            fixture.b.value = fixture.b.value.copy(windowStartMs = second.availabilityStartTimeMs,
                durationMs = 60_000, live = true, dynamic = true)
            fixture.a.timeline = first; fixture.b.timeline = second
            fixture.openTiming()
            fixture.mark(NativeMixedSide.A)
            assertTrue(fixture.correspondences.isEmpty())
            fixture.progress(25_000)
            fixture.mark(NativeMixedSide.B)
            val (delta, saved) = fixture.correspondences.single()
            assertEquals(-25_000L, delta)
            assertEquals(1_700_000_020_000L, saved.aMs)
            assertEquals(9_000_000_035_000L, saved.bMs)
            assertTrue(saved.aLive && saved.bLive)
            assertEquals(first, saved.aTimeline); assertEquals(second, saved.bTimeline)
            assertEquals(0L, saved.residualMs)
            fixture.assertOnlyCorrespondenceWasForwarded()
        }
    }

    @Test fun oppositeMarkOrderForwardsTheOppositeCorrectionAndActualEventClocks() = configurations { scenario, fixture ->
        scenario.onActivity {
            fixture.openTiming()
            fixture.mark(NativeMixedSide.B)
            fixture.progress(25_000)
            fixture.mark(NativeMixedSide.A)
            val (delta, saved) = fixture.correspondences.single()
            assertEquals(25_000L, delta)
            assertEquals(525_000L, saved.aMs); assertEquals(10_000L, saved.bMs)
            assertFalse(saved.aLive || saved.bLive)
            assertEquals(NativeTimelineEvidence.Replay(1_000_000), saved.aTimeline)
            fixture.assertOnlyCorrespondenceWasForwarded()
        }
    }

    @Test fun zeroGapStillForwardsACompleteCorrespondenceWithoutLegacyAlignmentOrTransport() = configurations { scenario, fixture ->
        scenario.onActivity {
            fixture.a.value = fixture.a.value.copy(playing = false, playWhenReady = false)
            fixture.b.value = fixture.b.value.copy(playing = false, playWhenReady = false)
            fixture.openTiming()
            fixture.mark(NativeMixedSide.A); fixture.mark(NativeMixedSide.B)
            val (delta, saved) = fixture.correspondences.single()
            assertEquals(0L, delta)
            assertEquals(500_000L, saved.aMs); assertEquals(10_000L, saved.bMs)
            assertEquals(0L, saved.residualMs)
            assertTrue(descendants(fixture.viewer).filterIsInstance<TextView>().any {
                it.text.toString().contains("No adjustment needed for these marks.")
            })
            fixture.assertMarked(false)
            fixture.assertClearedMessage()
            fixture.assertOnlyCorrespondenceWasForwarded()
        }
    }

    @Test fun requestedAlignmentRetainsMarksUntilItsExactNativeSettlementAndKeepsSavedCorrespondence() = configurations { scenario, fixture ->
        scenario.onActivity {
            fixture.dispatchAlignment = true
            fixture.openTiming(); fixture.mark(NativeMixedSide.A)
            fixture.progress(5_000); fixture.mark(NativeMixedSide.B)
            fixture.assertMarked(true)
            assertTrue(fixture.pair.busy)
            assertNull(fixture.pair.lastTimingSettlement)
            val captured = fixture.correspondences.single().second
            assertEquals(500_000L, captured.aMs); assertEquals(15_000L, captured.bMs)
            fixture.viewer.refresh(); fixture.assertMarked(true)
            fixture.pair.poll(); fixture.viewer.refresh()
            assertEquals(-5_000L, fixture.pair.lastTimingSettlement?.deltaMs)
            fixture.assertMarked(false); fixture.assertClearedMessage()
            assertEquals(captured, fixture.correspondences.single().second)
            assertEquals(500_000L, captured.aMs); assertEquals(15_000L, captured.bMs)
            fixture.mark(NativeMixedSide.A)
            assertEquals(1, fixture.correspondences.size)
        }
    }

    @Test fun synchronousSettlementAndRefreshInsideCallbackAlsoClearOnlyTheCompletedMarks() = configurations { scenario, fixture ->
        scenario.onActivity {
            fixture.dispatchAlignment = true
            fixture.afterAlignment = { fixture.pair.poll(); fixture.viewer.refresh() }
            fixture.openTiming(); fixture.mark(NativeMixedSide.A)
            fixture.progress(5_000); fixture.mark(NativeMixedSide.B)
            fixture.assertMarked(false); fixture.assertClearedMessage()
            assertEquals(-5_000L, fixture.pair.lastTimingSettlement?.deltaMs)
        }
    }

    @Test fun cancellationDoesNotClearMarksOrAcceptALaterUnrelatedIdenticalSettlement() = configurations { scenario, fixture ->
        scenario.onActivity {
            fixture.dispatchAlignment = true
            fixture.openTiming(); fixture.mark(NativeMixedSide.A)
            fixture.progress(5_000); fixture.mark(NativeMixedSide.B)
            fixture.pair.pause()
            // No viewer refresh between cancellation and a different operation.
            assertEquals(NativeSeekOutcome.REQUESTED, fixture.pair.alignRelative(-5_000).outcome)
            fixture.pair.poll(); fixture.viewer.refresh()
            fixture.assertMarked(true)
            assertEquals(1, fixture.correspondences.size)
        }
    }

    @Test fun timeoutAndMismatchedSettlementDoNotClearMarks() = configurations { scenario, fixture ->
        scenario.onActivity {
            fixture.dispatchAlignment = true
            fixture.openTiming(); fixture.mark(NativeMixedSide.A)
            fixture.progress(5_000); fixture.mark(NativeMixedSide.B)
            fixture.a.value = fixture.a.value.copy(state = 2)
            fixture.nowMs = 9_000; fixture.pair.poll(); fixture.viewer.refresh()
            assertFalse(fixture.pair.busy); assertNull(fixture.pair.lastTimingSettlement)
            fixture.assertMarked(true)
            fixture.a.value = fixture.a.value.copy(state = 3)
            fixture.mark(NativeMixedSide.A) // Explicitly clear the failed pair.
            fixture.dispatchDelta = 1_000
            fixture.mark(NativeMixedSide.A); fixture.progress(5_000); fixture.mark(NativeMixedSide.B)
            fixture.pair.poll(); fixture.viewer.refresh()
            assertEquals(1_000L, fixture.pair.lastTimingSettlement?.deltaMs)
            fixture.assertMarked(true)
        }
    }

    @Test fun explicitClearAndNewMarksDuringCallbackCannotBeClearedByItsOldReceipt() = configurations { scenario, fixture ->
        scenario.onActivity {
            fixture.dispatchAlignment = true
            fixture.afterAlignment = {
                fixture.pair.pause(); fixture.viewer.refresh()
                fixture.mark(NativeMixedSide.A) // Clear the completed selection.
                fixture.mark(NativeMixedSide.B) // Start a different event.
                fixture.afterAlignment = null
            }
            fixture.openTiming(); fixture.mark(NativeMixedSide.A)
            fixture.progress(5_000); fixture.mark(NativeMixedSide.B)
            assertEquals(1, fixture.correspondences.size)
            val selected = descendants(fixture.viewer).filterIsInstance<Button>().filter { it.isSelected && it.text == "Marked" }
            assertEquals(1, selected.size)
            assertTrue(selected.single().contentDescription.toString().contains("feed B"))
        }
    }

    @Test fun replacedPairClearsOldMarksAndItsLateSettlementCannotClearANewMark() = configurations { scenario, fixture ->
        scenario.onActivity {
            fixture.dispatchAlignment = true
            fixture.openTiming(); fixture.mark(NativeMixedSide.A)
            fixture.progress(5_000); fixture.mark(NativeMixedSide.B)
            fixture.currentPair = NativeMixedPair(Member(100_000), Member(200_000), { true }, {})
            fixture.viewer.refresh(); fixture.assertMarked(false)
            fixture.mark(NativeMixedSide.A)
            fixture.pair.poll(); fixture.viewer.refresh()
            val selected = descendants(fixture.viewer).filterIsInstance<Button>().filter { it.isSelected && it.text == "Marked" }
            assertEquals(1, selected.size)
            assertTrue(selected.single().contentDescription.toString().contains("feed A"))
        }
    }

    @Test fun markedSideClearsBothWithoutAlignmentAndNextClicksCaptureFreshEventPoints() = configurations { scenario, fixture ->
        scenario.onActivity {
            fixture.openTiming()
            fixture.mark(NativeMixedSide.A)
            fixture.progress(5_000); fixture.mark(NativeMixedSide.A)
            assertTrue(fixture.correspondences.isEmpty())
            assertEquals(2, descendants(fixture.viewer).filterIsInstance<Button>().count { it.text == "Mark" })
            fixture.mark(NativeMixedSide.A)
            fixture.progress(5_000); fixture.mark(NativeMixedSide.B)
            assertEquals(-5_000L, fixture.correspondences.single().first)
            assertEquals(505_000L, fixture.correspondences.single().second.aMs)
            assertEquals(20_000L, fixture.correspondences.single().second.bMs)
            fixture.progress(10_000); fixture.mark(NativeMixedSide.B)
            assertEquals(1, fixture.correspondences.size)
            assertEquals(2, descendants(fixture.viewer).filterIsInstance<Button>().count { it.text == "Mark" })
            fixture.mark(NativeMixedSide.B)
            fixture.progress(2_000); fixture.mark(NativeMixedSide.A)
            assertEquals(listOf(-5_000L, 2_000L), fixture.correspondences.map { value -> value.first })
            assertEquals(522_000L, fixture.correspondences.last().second.aMs)
            assertEquals(30_000L, fixture.correspondences.last().second.bMs)
            fixture.assertOnlyCorrespondenceWasForwarded()
        }
    }

    @Test fun markedButtonsCanClearPartialAndCompleteMarksWhilePlaybackClocksAreUnavailable() = configurations { scenario, fixture ->
        scenario.onActivity {
            fixture.openTiming()
            val initialA = fixture.a.value; val initialB = fixture.b.value
            for (side in NativeMixedSide.entries) {
                fixture.a.value = initialA; fixture.b.value = initialB; fixture.viewer.refresh()
                fixture.mark(side)
                fixture.a.value = initialA.copy(positionMs = null, state = 2)
                fixture.b.value = initialB.copy(positionMs = null, state = 2)
                fixture.viewer.refresh()
                fixture.mark(side)
                assertEquals(2, descendants(fixture.viewer).filterIsInstance<Button>().count { it.text == "Mark" })
                assertTrue(fixture.correspondences.isEmpty())
            }
            fixture.a.value = initialA; fixture.b.value = initialB; fixture.viewer.refresh()
            fixture.mark(NativeMixedSide.A)
            fixture.progress(5_000); fixture.mark(NativeMixedSide.B)
            assertEquals(1, fixture.correspondences.size)
            fixture.a.value = fixture.a.value.copy(positionMs = null, state = 2)
            fixture.b.value = fixture.b.value.copy(positionMs = null, state = 2)
            fixture.viewer.refresh()
            fixture.mark(NativeMixedSide.B)
            assertEquals(2, descendants(fixture.viewer).filterIsInstance<Button>().count { it.text == "Mark" })
            assertEquals(1, fixture.correspondences.size)
            fixture.assertOnlyCorrespondenceWasForwarded()
        }
    }

    @Test fun videoPanesHaveNoSourceLabelTextWhileTimingControlsKeepBothNames() = configurations { scenario, fixture ->
        scenario.onActivity {
            fun assertVideoLabelsAbsent() {
                for ((player, name) in listOf(fixture.playerA to "Alpha", fixture.playerB to "Beta")) {
                    val pane = player.parent as ViewGroup
                    assertTrue(pane.contentDescription.toString().contains(name))
                    assertFalse((0 until pane.childCount).map(pane::getChildAt).any { child -> child is TextView })
                    assertFalse(descendants(pane).filterIsInstance<TextView>().any { text ->
                        text.text.toString().contains("Alpha") || text.text.toString().contains("Beta")
                    })
                }
            }
            assertVideoLabelsAbsent()
            fixture.openTiming()
            val labels = descendants(fixture.viewer).filterIsInstance<TextView>().map { text -> text.text.toString() }
            assertTrue(labels.contains("Advance A · Alpha")); assertTrue(labels.contains("Advance B · Beta"))
            for ((side, name) in listOf(NativeMixedSide.A to "Alpha", NativeMixedSide.B to "Beta")) {
                val mark = descendants(fixture.viewer).filterIsInstance<Button>().single { button ->
                    button.contentDescription?.toString()?.startsWith("Mark matching event in feed ${side.name} ·") == true
                }
                assertTrue(mark.contentDescription.toString().contains(name))
            }
            (fixture.playerB.parent as View).performClick()
            assertVideoLabelsAbsent()
        }
    }

    private fun configurations(test: (ActivityScenario<ComponentActivity>, Fixture) -> Unit) {
        for (television in listOf(false, true)) ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, television) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            try { test(scenario, fixture) } finally { scenario.onActivity { fixture.close() } }
        }
    }

    private companion object {
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    }
}
