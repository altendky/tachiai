package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import org.junit.Assert.*
import org.junit.Test

class PairOffsetDeviationGuardTest {
    // A live feed playing normally, liveOffsetMs behind its live edge.
    private fun live(liveOffsetMs: Long?, position: Long = 20_000, duration: Long = 50_000) =
        NativeTimingSnapshot(position, duration, duration, liveOffsetMs, 0, null, true, true, true, true, false,
            true, true, 3, 1f)
    private val pair = Any()
    private class Run(val guard: PairOffsetDeviationGuard, val pair: Any) {
        var now = 1_000L
        var intent = 0L
        var settlement = 0L
        // Advances time in 200 ms viewer ticks, returning the first correction.
        fun tick(ms: Long, active: Boolean = true, a: () -> NativeTimingSnapshot?, b: () -> NativeTimingSnapshot?): Long? {
            var result: Long? = null
            val end = now + ms
            while (now < end) {
                now += 200
                // As the activity does: a dispatched recommendation counts as an attempt.
                guard.observe(pair, intent, settlement, active, a(), b(), now)?.let {
                    guard.attempted(now); if (result == null) result = it
                }
            }
            return result
        }
    }

    @Test fun aFeedThatJumpsTowardLiveIsRestoredAfterItSettlesSteadily() {
        // As on the SHIELD: ABEMA 44 s behind live, Twitch 12 s; then ABEMA jumps 16 s toward live.
        val run = Run(PairOffsetDeviationGuard(), pair)
        var aOffset = 44_000L
        assertNull(run.tick(10_000, a = { live(aOffset) }, b = { live(12_000) }))
        aOffset = 28_000L
        // The step itself is not steady; the sustained deviation then restores A by moving it 16 s back.
        assertNull(run.tick(1_000, a = { live(aOffset) }, b = { live(12_000) }))
        assertEquals(-16_000L, run.tick(3_000, a = { live(aOffset) }, b = { live(12_000) }))
    }

    @Test fun aBriefClockKindFlipKeepsTheFrozenRelationship() {
        // The live offset can vanish for a moment at a period boundary right after the jump.
        val run = Run(PairOffsetDeviationGuard(), pair)
        var aOffset: Long? = 44_000L
        assertNull(run.tick(3_000, a = { live(aOffset) }, b = { live(12_000) }))
        aOffset = null
        assertNull(run.tick(600, a = { live(aOffset) }, b = { live(12_000) }))
        aOffset = 28_000L
        assertEquals(-16_000L, run.tick(5_000, a = { live(aOffset) }, b = { live(12_000) }))
    }

    @Test fun theRelationshipIsNeverFrozenFromAStalledFeed() {
        // Starting while A's position is frozen (as in a commercial) takes no reference;
        // the first steady second after it does.
        val run = Run(PairOffsetDeviationGuard(), pair)
        var aOffset = 30_000L
        assertNull(run.tick(5_000, a = { live(aOffset).also { aOffset += 200 } }, b = { live(12_000) }))
        assertNull(run.tick(10_000, a = { live(aOffset) }, b = { live(12_000) }))
        aOffset -= 16_000
        assertEquals(-16_000L, run.tick(5_000, a = { live(aOffset) }, b = { live(12_000) }))
    }

    @Test fun anUndispatchedRecommendationIsNotCountedAsAnAttempt() {
        val guard = PairOffsetDeviationGuard()
        var now = 0L
        var aOffset = 44_000L
        fun step() = guard.observe(pair, 0, 0, true, live(aOffset), live(12_000), (now + 200).also { now = it })
        repeat(15) { step() }
        aOffset = 28_000L
        // Refused by the session every time (never attempted): it keeps recommending.
        val recommended = (1..100).mapNotNull { step() }
        assertTrue(recommended.size > 3)
        assertFalse(guard.exhausted)
    }

    @Test fun smallDriftAndAFrozenFeedNeverTrigger() {
        val run = Run(PairOffsetDeviationGuard(), pair)
        var aOffset = 44_000L
        assertNull(run.tick(2_000, a = { live(aOffset) }, b = { live(12_000) }))
        aOffset += 2_500 // Below the threshold.
        assertNull(run.tick(10_000, a = { live(aOffset) }, b = { live(12_000) }))
        // A position frozen while "playing" (as during ABEMA's commercials) falls behind 1:1 but is never steady.
        assertNull(run.tick(25_000, a = { live(aOffset).also { aOffset += 200 } }, b = { live(12_000) }))
    }

    @Test fun anyUserTimingActionOrInactiveStateDiscardsTheReference() {
        val run = Run(PairOffsetDeviationGuard(), pair)
        var aOffset = 44_000L
        assertNull(run.tick(2_000, a = { live(aOffset) }, b = { live(12_000) }))
        aOffset = 28_000L
        // A Mark/seek/nudge settles a new revision: the new relationship is intended.
        run.settlement++
        assertNull(run.tick(10_000, a = { live(aOffset) }, b = { live(12_000) }))
        aOffset = 44_000L
        run.intent++ // Pause/Play/Back to live.
        assertNull(run.tick(10_000, a = { live(aOffset) }, b = { live(12_000) }))
        aOffset = 28_000L
        // Inactive (offset not applied, busy, held, failed) forgets the reference too.
        assertNull(run.tick(1_000, active = false, a = { live(aOffset) }, b = { live(12_000) }))
        aOffset = 44_000L
        assertNull(run.tick(10_000, a = { live(aOffset) }, b = { live(12_000) }))
    }

    @Test fun attemptsAreSpacedAndCappedSoRecoveryCannotLoop() {
        val guard = PairOffsetDeviationGuard()
        val run = Run(guard, pair)
        var aOffset = 44_000L
        assertNull(run.tick(2_000, a = { live(aOffset) }, b = { live(12_000) }))
        aOffset = 28_000L
        val corrections = mutableListOf<Pair<Long, Long>>()
        repeat(400) { run.tick(200, a = { live(aOffset) }, b = { live(12_000) })?.let { corrections += run.now to it } }
        // A correction that never takes effect is retried after the cooldown, at most three times.
        assertEquals(3, corrections.size)
        assertTrue(corrections.zipWithNext().all { (first, second) -> second.first - first.first >= 15_000 })
        assertTrue(guard.exhausted)
    }

    @Test fun windowSawtoothClocksUseTheCoarseThreshold() {
        // Without the player's continuous live offset, distance from the window end saws by a refresh.
        // Playing normally keeps that distance while the window moves with the feed.
        val run = Run(PairOffsetDeviationGuard(), pair)
        var position = 10_000L
        fun sliding() = live(null, position, 50_000)
        assertNull(run.tick(2_000, a = { sliding() }, b = { live(12_000) }))
        position += 5_000
        assertNull(run.tick(10_000, a = { sliding() }, b = { live(12_000) }))
        position += 8_000
        assertNotNull(run.tick(6_000, a = { sliding() }, b = { live(12_000) }))
    }
}
