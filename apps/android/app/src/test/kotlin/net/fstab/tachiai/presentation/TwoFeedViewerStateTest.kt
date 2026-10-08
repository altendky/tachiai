package net.fstab.tachiai.presentation

import org.junit.Assert.*
import org.junit.Test

class TwoFeedViewerStateTest {
    @Test fun timingStepsRetainExactMillisecondsAndFractionalLabels() {
        assertEquals(listOf(100L, 250L, 500L, 1_000L, 5_000L), ViewerTimingStep.entries.map { it.milliseconds })
        assertEquals(listOf("0.1 s", "0.25 s", "0.5 s", "1 s", "5 s"), ViewerTimingStep.entries.map { it.label })
    }
    @Test fun centreAndEndpoints() {
        assertEquals(0.5f, TwoFeedMix().gainA, 0f)
        assertEquals(0.5f, TwoFeedMix().gainB, 0f)
        assertEquals(1f, TwoFeedMix(100, 0).gainA, 0f)
        assertEquals(0f, TwoFeedMix(100, 0).gainB, 0f)
        assertEquals(0f, TwoFeedMix(100, 100).gainA, 0f)
        assertEquals(1f, TwoFeedMix(100, 100).gainB, 0f)
    }
    @Test fun overallScalesMixWithoutChangingBalance() {
        val loud = TwoFeedMix(100, 75)
        val quiet = loud.copy(overall = 20)
        assertEquals(loud.balance, quiet.balance)
        assertEquals(loud.gainA * 0.2f, quiet.gainA, 0.00001f)
        assertEquals(loud.gainB * 0.2f, quiet.gainB, 0.00001f)
    }
    @Test fun mutesDoNotRedistributeOrChangeMix() {
        val original = TwoFeedMix(80, 40)
        val muted = original.copy(muteA = true)
        assertEquals(0f, muted.gainA, 0f)
        assertEquals(original.gainB, muted.gainB, 0f)
        assertEquals(original, muted.copy(muteA = false))
        assertEquals(0f, original.copy(muteA = true, muteB = true).gainB, 0f)
    }
    @Test fun fineNudgeAndSaturation() {
        assertEquals(49, TwoFeedMix().nudge(-1).balance)
        assertEquals(51, TwoFeedMix().nudge(1).balance)
        assertEquals(0, TwoFeedMix(balance = 0).nudge(-1).balance)
        assertEquals(100, TwoFeedMix(balance = 100).nudge(1).balance)
        assertEquals(100, TwoFeedMix().nudge(Int.MAX_VALUE).balance)
    }
    @Test fun gainsAlwaysBoundedAndMonotonic() {
        for (overall in 0..100) for (balance in 0..100) {
            val mix = TwoFeedMix(overall, balance)
            assertTrue(mix.gainA in 0f..1f && mix.gainB in 0f..1f)
            if (balance < 100) {
                assertTrue(mix.nudge(1).gainA <= mix.gainA)
                assertTrue(mix.nudge(1).gainB >= mix.gainB)
            }
        }
    }
    @Test fun portraitUsesNaturalHeightsAndStableOrder() {
        val bounds = twoFeedBounds(400, 1000, false, true, 2f, 1f)
        assertEquals(ViewerRect(0, 0, 400, 200), bounds.a)
        assertEquals(ViewerRect(0, 200, 400, 400), bounds.b)
        assertEquals(bounds, twoFeedBounds(400, 1000, false, false, 2f, 1f))
    }
    @Test fun shortPortraitFitsWithoutOverflow() {
        val bounds = twoFeedBounds(400, 300, false, true, 2f, 1f)
        assertEquals(100, bounds.a.height)
        assertEquals(200, bounds.b.height)
        assertEquals(300, bounds.b.top + bounds.b.height)
    }
    @Test fun swapOnlyChangesFeedBounds() {
        val a = twoFeedBounds(1000, 600, true, true)
        val b = twoFeedBounds(1000, 600, true, false)
        assertEquals(a.a, b.b); assertEquals(a.b, b.a)
        assertEquals(ViewerRect(0, 0, 1000, 600), a.a)
    }
    @Test fun floatingStaysAboveControlsAndClampsDrag() {
        val bounds = twoFeedBounds(1000, 600, true, true,
            floating = FloatingPosition(5f, 5f), floatingBottom = 350)
        assertTrue(bounds.b.left >= 0 && bounds.b.left + bounds.b.width <= 1000)
        assertEquals(350, bounds.b.top + bounds.b.height)
        assertEquals(FloatingPosition(1f, 0f), FloatingPosition.fromPixels(500f, -40f, 100, 100))
        assertEquals(FloatingPosition(0f, 0f), FloatingPosition.fromPixels(500f, 40f, 0, 0))
    }
    @Test fun rotationReusesNormalizedPosition() {
        val position = FloatingPosition(0.25f, 0.75f)
        for ((w, h) in listOf(1000 to 600, 600 to 1000, 300 to 100)) {
            val r = twoFeedBounds(w, h, true, false, floating = position).a
            assertEquals((w - r.width) * 0.25f, r.left.toFloat(), 0.6f)
            assertEquals((h - r.height) * 0.75f, r.top.toFloat(), 0.6f)
        }
    }
    @Test fun zeroViewportAndInvalidAspectFallback() {
        assertEquals(ViewerRect(0, 0, 0, 0), twoFeedBounds(0, 0, true, true).a)
        assertEquals(twoFeedBounds(400, 1000, false, true),
            twoFeedBounds(400, 1000, false, true, Float.NaN, -1f))
    }
}
