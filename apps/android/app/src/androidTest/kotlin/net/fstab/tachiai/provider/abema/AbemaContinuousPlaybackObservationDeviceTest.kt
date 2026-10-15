package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.provider.twitch.ConfiguredTwitchMovementSampler.Result
import net.fstab.tachiai.provider.twitch.ConfiguredTwitchMovementSampler.SampleState
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import org.junit.Assert.*
import org.junit.Test

class AbemaContinuousPlaybackObservationDeviceTest {
    @Test fun eachMilestoneKeepsItsFirstBoundedTimeAndHeadroom() {
        val observed = AbemaContinuousPlaybackObservation(1000, 51_000)
        observed.prepared(2000); observed.prepared(3000)
        observed.frame(49_500); observed.frame(50_000)
        observed.beginSampling(49_501); observed.beginSampling(50_001)
        observed.endSampling(51_001, Result.PENDING, SampleState.READY, SampleState.BUFFERING)
        observed.endSampling(52_000, Result.MOVED, SampleState.READY, SampleState.READY)
        val snapshot = checkNotNull(observed.snapshot)
        assertEquals(1000L, snapshot.initialPrepared?.elapsedMs)
        assertEquals(48_500L, snapshot.acceptedFrame?.elapsedMs)
        assertEquals(1500L, snapshot.acceptedFrame?.remainingMs)
        assertEquals(1499L, snapshot.samplerBegin?.remainingMs)
        assertEquals(50_001L, snapshot.samplerEnd?.elapsedMs)
        assertEquals(0L, snapshot.samplerEnd?.remainingMs)
        assertEquals(Result.PENDING, snapshot.result)
        assertEquals(SampleState.BUFFERING, snapshot.lastState)
        assertTrue(checkNotNull(snapshot.acceptedFrame).closedSummary().contains("headroom1500=true"))
        assertTrue(checkNotNull(snapshot.samplerBegin).closedSummary().contains("headroom1500=false"))
    }

    @Test fun sealBeforeCleanupKeepsAllMilestonesAndEligibilityImmutable() {
        val observed = AbemaContinuousPlaybackObservation(1000, 51_000)
        observed.prepared(2000); observed.eligibility(true, false)
        observed.seal()
        val beforeCleanup = observed.snapshot
        observed.frame(3000); observed.beginSampling(4000)
        observed.endSampling(5000, Result.MOVED, SampleState.READY, SampleState.READY)
        observed.eligibility(false, true)
        assertEquals(beforeCleanup, observed.snapshot)
    }

    @Test fun emptyInvalidOrOutOfLifetimeCapturesCannotManufactureTimingEvidence() {
        val observed = AbemaContinuousPlaybackObservation(1000, 51_000)
        assertNull(observed.snapshot)
        observed.prepared(999); observed.frame(Long.MIN_VALUE); observed.beginSampling(Long.MAX_VALUE)
        observed.endSampling(71_001, Result.REFUSED, SampleState.UNAVAILABLE, SampleState.UNAVAILABLE)
        observed.eligibility(false, false)
        assertNull(observed.snapshot)
        observed.frame(71_000)
        assertEquals(70_000L, observed.snapshot?.acceptedFrame?.elapsedMs)
        assertEquals(0L, observed.snapshot?.acceptedFrame?.remainingMs)
        assertThrows(IllegalArgumentException::class.java) { AbemaContinuousPlaybackObservation(-1, 50_000) }
        assertThrows(IllegalArgumentException::class.java) { AbemaContinuousPlaybackObservation(1000, 91_001) }
    }

    @Test fun evenMovedAndEligibleEvidenceRemainsProgressWithoutAcceptanceAuthority() {
        val observed = AbemaContinuousPlaybackObservation(1000, 51_000)
        observed.beginSampling(2000)
        observed.endSampling(4000, Result.MOVED, SampleState.READY, SampleState.READY)
        observed.eligibility(true, true)
        val summary = checkNotNull(observed.snapshot).closedSummary()
        assertTrue(summary.startsWith("observation=PROGRESS "))
        assertTrue(summary.contains("samplerResult=MOVED"))
        assertFalse(summary.contains("ACCEPTED")); assertFalse(summary.contains("SUCCESS"))
    }

    @Test fun continuousBudgetKeepsOriginalDeadlineThroughSetupLicenseAndMovement() {
        val timing = AbemaBoundedPlaybackTiming.CONTINUOUS
        val started = 1000L
        var now = started
        val budget = NativePlaybackBudget(timing.observationMs, { true }, { now }, timing.observationMs)
        val observed = AbemaContinuousPlaybackObservation(started, started + timing.observationMs)
        now = started + 45_000
        assertEquals(45_000L, budget.remainingMs)
        now += 30_000
        observed.prepared(now); observed.frame(now); observed.beginSampling(now)
        assertEquals(15_000L, budget.remainingMs)
        assertEquals(15_000L, observed.snapshot?.acceptedFrame?.remainingMs)
        now = started + 89_999
        assertTrue(budget.active)
        now++
        assertFalse(budget.active); assertEquals(0L, budget.remainingMs)
        assertEquals(started + 110_000, timing.cleanupEnd(started, now))
        assertEquals(started + 60_000, timing.cleanupEnd(started, started + 40_000))
        observed.endSampling(started + 110_000, Result.PENDING, SampleState.READY, SampleState.BUFFERING)
        assertEquals(110_000L, observed.snapshot?.samplerEnd?.elapsedMs)
        val outside = AbemaContinuousPlaybackObservation(started, started + timing.observationMs)
        outside.frame(started + 110_001)
        assertNull(outside.snapshot)
    }

    @Test fun manifestBudgetStillEndsAtFiftySecondsWithOnlyTwentyForCleanup() {
        val timing = AbemaBoundedPlaybackTiming.MANIFEST
        var now = 0L
        val budget = NativePlaybackBudget(timing.observationMs, { true }, { now }, maximumDurationMs = 70_000)
        assertEquals(50_000L, budget.remainingMs)
        now = 49_999; assertTrue(budget.active)
        now = 50_000; assertFalse(budget.active)
        assertEquals(70_000L, timing.totalMs)
        assertEquals(70_000L, timing.cleanupEnd(0, now))
        assertEquals(30_000L, timing.cleanupEnd(0, 10_000))
    }
}
