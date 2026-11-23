package net.fstab.tachiai.presentation

import android.view.KeyEvent
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativePairSkipPlan
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerTransportTest {
    @Test fun timesUseHoursOnlyForLongTotals() {
        assertEquals("2:14:05 / 4:45:00", formatPlaybackPosition(8_045_000, 17_100_000))
        assertEquals("0:00:09 / 1:00:00", formatPlaybackPosition(9_999, 3_600_000))
        assertEquals("12:05 / 45:00", formatPlaybackPosition(725_000, 2_700_000))
        assertEquals("0:00 / 0:59", formatPlaybackPosition(-5, 59_999))
        // A position past an hour still shows its hours under a shorter total.
        assertEquals("1:00:01", formatPlaybackTime(3_601_000, 60_000))
    }

    @Test fun skipAmountsNameSecondsAndMinutes() {
        assertEquals("+10 s", formatSkipAmount(10_000))
        assertEquals("−1 min", formatSkipAmount(-60_000))
        assertEquals("+1 min 30 s", formatSkipAmount(90_000))
        assertEquals("−0 s", formatSkipAmount(-400))
    }

    @Test fun hiddenOverlayLeftRightSkipOnlyReplays() {
        assertEquals(ViewerHiddenKey.SKIP_BACK, viewerHiddenKey(KeyEvent.KEYCODE_DPAD_LEFT, true))
        assertEquals(ViewerHiddenKey.SKIP_FORWARD, viewerHiddenKey(KeyEvent.KEYCODE_DPAD_RIGHT, true))
        assertEquals(ViewerHiddenKey.REVEAL, viewerHiddenKey(KeyEvent.KEYCODE_DPAD_LEFT, false))
        assertEquals(ViewerHiddenKey.REVEAL, viewerHiddenKey(KeyEvent.KEYCODE_DPAD_RIGHT, false))
        for (code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN))
            assertEquals(ViewerHiddenKey.REVEAL, viewerHiddenKey(code, true))
        for (code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A))
            for (replay in listOf(true, false)) assertEquals(ViewerHiddenKey.REVEAL_ON_PLAY, viewerHiddenKey(code, replay))
    }

    @Test fun fastForwardAndRewindSkipTenSeconds() {
        assertEquals(10_000L, viewerMediaSkipMs(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(-10_000L, viewerMediaSkipMs(KeyEvent.KEYCODE_MEDIA_REWIND))
        for (code in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_DPAD_RIGHT))
            assertNull(viewerMediaSkipMs(code))
    }

    @Test fun scrubStepsAreAPercentOfTheDurationAndHoldingTakesFivePercent() {
        val replay = 12_240_000L // 3:24:00
        assertEquals(122_400L, viewerScrubStepMs(0, replay))
        assertEquals(122_400L, viewerScrubStepMs(499, replay))
        assertEquals(612_000L, viewerScrubStepMs(500, replay))
        assertEquals(612_000L, viewerScrubStepMs(60_000, replay)) // Bounded however long it is held.
        // Short items keep a 10 s minimum step.
        assertEquals(10_000L, viewerScrubStepMs(0, 600_000))
        assertEquals(30_000L, viewerScrubStepMs(800, 600_000))
        assertEquals(10_000L, viewerScrubStepMs(800, 60_000))
        assertEquals("→ 1:42:10 / 3:24:00", formatScrubTarget(6_130_000, replay))
    }

    @Test fun singleTapWaitsForADoubleTapThatSkipsByHalf() {
        val gesture = ViewerTapGesture(300)
        assertEquals(ViewerTap.DEFER_TOGGLE, gesture.tap(1_000, 100f, 1_000, true))
        assertEquals(ViewerTap.SKIP_BACK, gesture.tap(1_200, 120f, 1_000, true))
        // Each further quick tap continues the run.
        assertEquals(ViewerTap.SKIP_FORWARD, gesture.tap(1_450, 900f, 1_000, true))
        assertEquals(ViewerTap.SKIP_FORWARD, gesture.tap(1_700, 500f, 1_000, true))
        // After a pause a tap is a new single tap.
        assertEquals(ViewerTap.DEFER_TOGGLE, gesture.tap(2_100, 900f, 1_000, true))
        gesture.deferredDone()
        assertEquals(ViewerTap.DEFER_TOGGLE, gesture.tap(2_300, 900f, 1_000, true))
        assertEquals(ViewerTap.DEFER_TOGGLE, gesture.tap(2_700, 900f, 1_000, true))
    }

    @Test fun tapsActAtOnceWithoutSkipping() {
        val gesture = ViewerTapGesture(300)
        assertEquals(ViewerTap.TOGGLE_NOW, gesture.tap(1_000, 100f, 1_000, false))
        assertEquals(ViewerTap.TOGGLE_NOW, gesture.tap(1_100, 100f, 1_000, false))
        assertEquals(ViewerTap.DEFER_TOGGLE, gesture.tap(1_200, 100f, 1_000, true))
        gesture.reset()
        assertEquals(ViewerTap.DEFER_TOGGLE, gesture.tap(1_300, 100f, 1_000, true))
    }

    @Test fun refusalsNameTheFeedThatPreventedTheSkip() {
        fun plan(outcome: NativeSeekOutcome, blocked: NativeMixedSide? = null, limited: NativeMixedSide? = null) =
            NativePairSkipPlan(outcome, blockedBy = blocked, limitedBy = limited)
        assertNull(viewerSkipRefusal(plan(NativeSeekOutcome.REQUESTED), true, true))
        assertNull(viewerSkipRefusal(plan(NativeSeekOutcome.CLAMPED, limited = NativeMixedSide.B), true, true))
        assertNull(viewerSkipRefusal(plan(NativeSeekOutcome.NO_CHANGE), true, true))
        assertEquals("B is at its end; neither feed moved.",
            viewerSkipRefusal(plan(NativeSeekOutcome.NO_CHANGE, limited = NativeMixedSide.B), true, true))
        assertEquals("A is at its start; neither feed moved.",
            viewerSkipRefusal(plan(NativeSeekOutcome.NO_CHANGE, limited = NativeMixedSide.A), false, true))
        assertEquals("At the start.", viewerSkipRefusal(plan(NativeSeekOutcome.NO_CHANGE, limited = NativeMixedSide.A), false, false))
        assertEquals("Skipping waits for B's advertisement; neither feed moved.",
            viewerSkipRefusal(plan(NativeSeekOutcome.AD_BLOCKED, NativeMixedSide.B), true, true))
        assertEquals("Skipping waits for the advertisement to finish.",
            viewerSkipRefusal(plan(NativeSeekOutcome.AD_BLOCKED, NativeMixedSide.A), true, false))
        assertEquals("A can't skip, so neither feed moved.",
            viewerSkipRefusal(plan(NativeSeekOutcome.UNSUPPORTED, NativeMixedSide.A), true, true))
        assertEquals("Skipping waits for B; neither feed moved.",
            viewerSkipRefusal(plan(NativeSeekOutcome.UNAVAILABLE, NativeMixedSide.B), true, true))
        assertEquals("Skipping is unavailable right now.", viewerSkipRefusal(plan(NativeSeekOutcome.OUTSIDE_WINDOW), true, true))
    }
}
