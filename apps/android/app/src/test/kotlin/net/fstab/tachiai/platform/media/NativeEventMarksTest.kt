package net.fstab.tachiai.platform.media

import org.junit.Assert.*
import org.junit.Test

class NativeEventMarksTest {
    private fun replay(position: Long) = NativeTimingSnapshot(position, 10_000_000, position, null, null, null,
        false, false, true, true, false, true, true, 3, 1f)
    private fun live(origin: Long, position: Long) = replay(position).copy(live = true, dynamic = true,
        windowStartMs = origin, durationMs = 60_000)
    private fun hls(first: Long) = NativeTimelineEvidence.Hls((first..first + 1).map {
        NativeHlsTimelineSample(it, 100_000 + it * 5_000, 5_000, 0)
    })

    @Test fun correspondenceCapturesActualMarkClocksRatherThanLaterPlaybackOrTapTime() {
        val marks = NativeEventMarks()
        val a = live(1_700_000_000_000, 20_000)
        val b = replay(10_000)
        assertNull(marks.correspondence())
        marks.mark(NativeMixedSide.A, a, b)
        assertNull(marks.correspondence())
        marks.mark(NativeMixedSide.B, a.copy(positionMs = 45_000), replay(35_000))
        val saved = checkNotNull(marks.correspondence())
        assertEquals(1_700_000_020_000L, saved.aMs)
        assertEquals(35_000L, saved.bMs)
        assertTrue(saved.aLive); assertFalse(saved.bLive)
        assertNull(saved.aTimeline); assertNull(saved.bTimeline)
        assertEquals(0L, saved.residualMs)
        marks.offsetMs(a.copy(positionMs = 50_000), replay(50_000))
        assertEquals(saved, marks.correspondence())
    }

    @Test fun markedSideClearsBothAndTheNextClickCapturesAFreshCorrespondence() {
        val marks = NativeEventMarks()
        marks.mark(NativeMixedSide.B, replay(100_000), replay(10_000))
        marks.mark(NativeMixedSide.B, replay(105_000), replay(15_000))
        assertFalse(marks.markedA || marks.markedB)
        assertNull(marks.correspondence())
        marks.mark(NativeMixedSide.B, replay(105_000), replay(15_000))
        marks.mark(NativeMixedSide.A, replay(110_000), replay(20_000))
        assertEquals(110_000L, marks.correspondence()?.aMs)
        assertEquals(15_000L, marks.correspondence()?.bMs)
        marks.mark(NativeMixedSide.A, replay(120_000), replay(30_000))
        assertNull(marks.correspondence()); assertFalse(marks.markedA || marks.markedB)
        marks.mark(NativeMixedSide.A, replay(120_000), replay(30_000))
        marks.mark(NativeMixedSide.B, replay(125_000), replay(35_000))
        assertEquals(120_000L, marks.correspondence()?.aMs)
        assertEquals(35_000L, marks.correspondence()?.bMs)
        marks.clear(); assertNull(marks.correspondence())
    }

    @Test fun overlappingEvidenceRefreshesWitnessWithoutMovingTheMarkedEventClock() {
        val marks = NativeEventMarks()
        val a = live(100_000, 20_000); val b = live(9_000_000, 10_000)
        val bTimeline = NativeTimelineEvidence.Dash(9_000_000, 0)
        marks.mark(NativeMixedSide.A, a, b, hls(10), bTimeline)
        assertTrue(marks.canMark(a, b, hls(11), bTimeline))
        assertNull(marks.offsetMs(a, b, hls(12), bTimeline))
        marks.mark(NativeMixedSide.B, a.copy(positionMs = 45_000), b.copy(positionMs = 35_000), hls(13), bTimeline)
        val saved = checkNotNull(marks.correspondence())
        assertEquals(120_000L, saved.aMs)
        assertEquals(hls(13), saved.aTimeline)
        assertEquals(bTimeline, saved.bTimeline)
        assertEquals(9_035_000L, saved.bMs)
    }

    @Test fun unknownCurrentEvidenceNeverBecomesRetroactiveProofForAnOldMark() {
        for (initial in listOf(null, hls(10))) {
            val marks = NativeEventMarks()
            marks.mark(NativeMixedSide.A, replay(100_000), replay(10_000), initial, null)
            assertTrue(marks.canMark(replay(100_000), replay(10_000), hls(20), null))
            marks.mark(NativeMixedSide.B, replay(125_000), replay(35_000), hls(20), null)
            assertEquals(initial, marks.correspondence()?.aTimeline)
            assertNull(marks.correspondence()?.bTimeline)
        }
    }

    @Test fun knownTimelineChangeClearsOldPartialOrCompleteMarksEvenWhileBuffering() {
        val before = NativeTimelineEvidence.Dash(1_000_000, 0)
        val changed = NativeTimelineEvidence.Dash(2_000_000, 0)
        val bTimeline = NativeTimelineEvidence.Dash(9_000_000, 0)
        val a = live(1_000_000, 20_000); val b = live(9_000_000, 10_000)
        val marks = NativeEventMarks()
        marks.mark(NativeMixedSide.A, a, b, before, bTimeline)
        marks.mark(NativeMixedSide.B, a, b, changed, bTimeline)
        assertFalse(marks.markedA); assertTrue(marks.markedB)
        assertNull(marks.correspondence())
        marks.mark(NativeMixedSide.A, a, b, changed, bTimeline)
        assertTrue(marks.complete)
        assertNull(marks.offsetMs(a.copy(state = 2), b, before, bTimeline))
        assertFalse(marks.markedA || marks.markedB)
        assertNull(marks.correspondence())
        marks.mark(NativeMixedSide.A, a, b, before, bTimeline)
        assertFalse(marks.canMark(a.copy(state = 2), b, changed, bTimeline))
        assertFalse(marks.markedA || marks.markedB)
    }

    @Test fun validFirstMarkOfANewSeriesDoesNotCompareAgainstThePreviousCompletedSeries() {
        val before = NativeTimelineEvidence.Replay(1_000_000)
        val changed = NativeTimelineEvidence.Replay(2_000_000)
        val marks = NativeEventMarks()
        marks.mark(NativeMixedSide.A, replay(100_000), replay(10_000), before, before)
        marks.mark(NativeMixedSide.B, replay(125_000), replay(35_000), before, before)
        assertTrue(marks.complete)
        marks.mark(NativeMixedSide.A, replay(200_000), replay(100_000), changed, changed)
        assertFalse(marks.markedA || marks.markedB)
        marks.mark(NativeMixedSide.A, replay(200_000), replay(100_000), changed, changed)
        assertTrue(marks.markedA); assertFalse(marks.markedB)
        marks.mark(NativeMixedSide.B, replay(225_000), replay(125_000), changed, changed)
        assertEquals(200_000L, marks.correspondence()?.aMs)
        assertEquals(changed, marks.correspondence()?.aTimeline)
        assertEquals(changed, marks.correspondence()?.bTimeline)
    }

    @Test fun matchingEventUsesOwnClocksWithUnrelatedProviderEpochs() {
        val marks = NativeEventMarks()
        val a = live(1_700_000_000_000, 20_000)
        val b = live(9_000_000_000_000, 10_000)
        assertTrue(marks.mark(NativeMixedSide.A, a, b))
        val laterA = a.copy(positionMs = 45_000)
        val laterB = b.copy(positionMs = 35_000)
        assertTrue(marks.mark(NativeMixedSide.B, laterA, laterB))
        assertEquals(25_000L, marks.offsetMs(laterA, laterB))
    }

    @Test fun oppositeMarkOrderIdentifiesBBeingAhead() {
        val marks = NativeEventMarks()
        assertTrue(marks.mark(NativeMixedSide.B, replay(500_000), replay(10_000)))
        assertTrue(marks.mark(NativeMixedSide.A, replay(525_000), replay(35_000)))
        assertEquals(-25_000L, marks.offsetMs(replay(525_000), replay(35_000)))
    }

    @Test fun slidingWindowMovementDoesNotChangeTheEventClock() {
        val marks = NativeEventMarks()
        assertTrue(marks.mark(NativeMixedSide.A, live(1_000_000, 20_000), live(9_000_000, 10_000)))
        val a = live(1_030_000, 15_000)
        val b = live(9_030_000, 5_000)
        assertTrue(marks.mark(NativeMixedSide.B, a, b))
        assertEquals(25_000L, marks.offsetMs(a, b))
    }

    @Test fun remainingGapUpdatesAfterAUserAdjustment() {
        val marks = NativeEventMarks()
        marks.mark(NativeMixedSide.A, replay(500_000), replay(10_000))
        marks.mark(NativeMixedSide.B, replay(525_000), replay(35_000))
        assertEquals(20_000L, marks.offsetMs(replay(520_000), replay(35_000)))
        assertEquals(0L, marks.offsetMs(replay(500_000), replay(35_000)))
        assertEquals(-5_000L, marks.offsetMs(replay(495_000), replay(35_000)))
    }

    @Test fun pausedOrUnequalProgressUsesFootageRatherThanTapInterval() {
        val marks = NativeEventMarks()
        val paused = replay(500_000).copy(playing = false, playWhenReady = false)
        marks.mark(NativeMixedSide.A, paused, replay(10_000))
        marks.mark(NativeMixedSide.B, paused, replay(35_000))
        assertEquals(0L, marks.offsetMs(paused, replay(35_000)))
        assertEquals(3_000L, marks.offsetMs(replay(503_000), replay(35_000)))
    }

    @Test fun eitherMarkedSideClearsPartialOrCompleteMarksEvenWhenClocksAreUnavailable() {
        for (side in NativeMixedSide.entries) for (complete in listOf(false, true)) {
            val marks = NativeEventMarks()
            marks.mark(side, replay(100_000), replay(10_000))
            if (complete) marks.mark(if (side == NativeMixedSide.A) NativeMixedSide.B else NativeMixedSide.A,
                replay(105_000), replay(15_000))
            assertTrue(marks.mark(side, null, null))
            assertFalse(marks.markedA || marks.markedB)
            assertNull(marks.correspondence())
            assertNull(marks.offsetMs(replay(110_000), replay(20_000)))
            assertFalse(marks.mark(side, null, null))
        }
    }

    @Test fun unknownLiveClocksAdsAndBufferingCannotBeMarked() {
        val marks = NativeEventMarks()
        val good = replay(100_000)
        for (bad in listOf(null, live(0, 10_000).copy(windowStartMs = null), good.copy(positionMs = null),
            good.copy(positionMs = -1), good.copy(state = 2), good.copy(playingAd = true))) {
            assertFalse(marks.canMark(bad, good))
            assertFalse(marks.canMark(good, bad))
            assertFalse(marks.mark(NativeMixedSide.A, bad, good))
            assertFalse(marks.mark(NativeMixedSide.B, good, bad))
            assertFalse(marks.markedA || marks.markedB)
        }
    }

    @Test fun transientBufferingHidesEstimateWithoutForgettingMarks() {
        val marks = NativeEventMarks()
        marks.mark(NativeMixedSide.A, replay(100_000), replay(10_000))
        marks.mark(NativeMixedSide.B, replay(125_000), replay(35_000))
        assertNull(marks.offsetMs(replay(125_000).copy(state = 2), replay(35_000)))
        assertTrue(marks.complete)
        assertEquals(25_000L, marks.offsetMs(replay(125_000), replay(35_000)))
    }

    @Test fun changedClockKindAndExplicitSessionClearDiscardOldMarks() {
        val marks = NativeEventMarks()
        marks.mark(NativeMixedSide.A, replay(100_000), replay(10_000))
        marks.mark(NativeMixedSide.B, replay(125_000), replay(35_000))
        assertNull(marks.offsetMs(live(1_000_000, 25_000), replay(35_000)))
        assertFalse(marks.markedA || marks.markedB)
        marks.mark(NativeMixedSide.A, replay(100_000), replay(10_000))
        marks.clear()
        assertFalse(marks.markedA || marks.markedB)
    }

    @Test fun overflowIsUnavailableRatherThanAnInvertedEstimate() {
        val marks = NativeEventMarks()
        marks.mark(NativeMixedSide.A, replay(0), replay(0))
        marks.mark(NativeMixedSide.B, replay(Long.MAX_VALUE), replay(Long.MAX_VALUE))
        assertNull(marks.offsetMs(replay(Long.MAX_VALUE), replay(0)))
    }
}
