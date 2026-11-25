package net.fstab.tachiai.presentation

import android.view.KeyEvent
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativePairSkipPlan
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeSkipTarget
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

    @Test fun hiddenOverlayLeftRightSkipWhileSkippingIsAvailable() {
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
        assertEquals("Skipping is unavailable right now.", viewerSkipRefusal(plan(NativeSeekOutcome.UNAVAILABLE), true, true))
    }

    // #660: a live feed reads how far behind its live point it is.
    @Test fun liveReadsBehindLiveOrLiveWithinTwoSeconds() {
        assertEquals("LIVE", formatBehindLive(0))
        assertEquals("LIVE", formatBehindLive(-3_000)) // Ahead of the computed live point.
        assertEquals("LIVE", formatBehindLive(ViewerTransportSteps.LIVE_EDGE_MS))
        assertEquals("LIVE", formatBehindLive(2_999)) // Within 3 s, including a just-short skip to the live point.
        assertEquals("−0:03", formatBehindLive(3_001))
        assertEquals("−1:35", formatBehindLive(95_000))
        assertEquals("−59:59", formatBehindLive(3_599_999))
        assertEquals("−1:02:05", formatBehindLive(3_725_000))
        // The bar spans the window to the live point; the position is behind it.
        assertEquals("−1:35", formatTransportPosition(85_000, 180_000, live = true))
        assertEquals("LIVE", formatTransportPosition(179_000, 180_000, live = true))
        assertEquals("1:25 / 3:00", formatTransportPosition(85_000, 180_000, live = false))
        assertEquals("→ −1:35", formatScrubTarget(85_000, 180_000, live = true))
        assertEquals("→ LIVE", formatScrubTarget(180_000, 180_000, live = true))
        assertEquals("→ 1:25 / 3:00", formatScrubTarget(85_000, 180_000))
        assertEquals("1:35 behind live", describeTransportPosition(85_000, 180_000, live = true))
        assertEquals("Live", describeTransportPosition(180_000, 180_000, live = true))
        assertEquals("1:25 of 3:00", describeTransportPosition(85_000, 180_000, live = false))
    }

    @Test fun aSkipStoppedAtTheLivePointSaysSo() {
        val atLive = NativePairSkipPlan(NativeSeekOutcome.NO_CHANGE, limitedBy = NativeMixedSide.A, limitedAtLive = true)
        assertEquals("A is live; neither feed moved.", viewerSkipRefusal(atLive, true, true))
        assertEquals("Already live.", viewerSkipRefusal(atLive, true, false))
        // Reaching the live point part of the way is a successful skip.
        assertNull(viewerSkipRefusal(atLive.copy(outcome = NativeSeekOutcome.CLAMPED, movementMs = 4_000), true, true))
        // The oldest point keeps the replay start wording.
        assertEquals("B is at its start; neither feed moved.", viewerSkipRefusal(
            NativePairSkipPlan(NativeSeekOutcome.NO_CHANGE, limitedBy = NativeMixedSide.B), false, true))
    }

    // #660: a feed outside its window is named, with what brings it back.
    @Test fun aFeedOutsideItsWindowIsNamedWithWhatBringsItBack() {
        val outside = NativePairSkipPlan(NativeSeekOutcome.OUTSIDE_WINDOW, blockedBy = NativeMixedSide.B)
        assertEquals("B is outside its window; neither feed moved. Live brings it back.",
            viewerSkipRefusal(outside, true, true))
        assertEquals("The feed is outside its window. Live brings it back.", viewerSkipRefusal(outside, false, false))
        assertEquals("B is outside its window; use Back to live B in ⋯ More.",
            viewerSkipRefusal(outside, true, true, live = true))
        assertEquals("The feed is outside its window; use Back to live in ⋯ More.",
            viewerSkipRefusal(outside, true, false, live = true))
        // A pair's refusal after holding both feeds carries its own reason.
        assertEquals("B changed while holding both feeds; neither feed moved.", viewerSkipRefusal(
            NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE, blockedBy = NativeMixedSide.B,
                reason = "B changed while holding both feeds"), true, true, live = true))
    }

    @Test fun liveSaysWhetherAPairKeptItsOffsetAfterBringingAFeedBack() {
        val a = NativeSkipTarget(NativeMixedSide.A, 150_000, 170_000, true, 1_170_000)
        val b = NativeSkipTarget(NativeMixedSide.B, -74_000, 170_000, true, 7_170_000)
        assertEquals("B was outside its window, so each feed returned to live; the offset was not kept.",
            viewerLiveNotice(NativePairSkipPlan(NativeSeekOutcome.REQUESTED, 244_000, listOf(a, b), keepsOffset = false), true))
        assertEquals("B was outside its window; both feeds moved forward to live together, keeping the offset.",
            viewerLiveNotice(NativePairSkipPlan(NativeSeekOutcome.REQUESTED, 20_000, listOf(a, b.copy(targetMs = 90_000))), true))
        assertEquals("Both feeds were outside their windows, so each feed returned to live; the offset was not kept.",
            viewerLiveNotice(NativePairSkipPlan(NativeSeekOutcome.REQUESTED, 244_000,
                listOf(a.copy(fromMs = -1_000), b), keepsOffset = false), true))
        assertEquals("The feed was outside its window; it returned to live.",
            viewerLiveNotice(NativePairSkipPlan(NativeSeekOutcome.REQUESTED, 244_000, listOf(b)), false))
        // An ordinary move to live, or a refusal, needs no notice.
        assertNull(viewerLiveNotice(NativePairSkipPlan(NativeSeekOutcome.REQUESTED, 20_000, listOf(a)), true))
        assertNull(viewerLiveNotice(NativePairSkipPlan(NativeSeekOutcome.OUTSIDE_WINDOW, blockedBy = NativeMixedSide.B), true))
    }
}
