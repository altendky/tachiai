package net.fstab.tachiai.platform.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// #618: a paused live feed past its oldest recorded point, and the delay limit.
class NativeLiveRewindPairTest {
    // A 6:30 retained window, held paused 10 s behind its live point.
    private val live = NativeTimingSnapshot(380_000, 390_000, 400_000, 10_000, 1_000_000, 380_000,
        true, true, true, true, false, false, false, 3, 1f)

    private class Member(var sample: NativeTimingSnapshot) : NativePairMember {
        val seeks = mutableListOf<Long>()
        var playRequests = 0
        var refuseSeek = false
        override fun timingSnapshot(): NativeTimingSnapshot = sample
        override fun seekToMs(targetMs: Long): NativeSeekPlan {
            seeks += targetMs
            if (refuseSeek) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
            val plan = nativeSeekPlan(sample, targetMs)
            plan.targetMs?.let { sample = sample.copy(positionMs = it) }
            return plan
        }
        override fun setTimingPlaying(playing: Boolean): Boolean {
            if (playing) playRequests++
            sample = sample.copy(playing = playing, playWhenReady = playing)
            return true
        }
        override fun setVolume(volume: Float) = true
        override fun close() = Unit
    }

    private fun pair(a: Member, b: Member) = NativeMixedPair(a, b, { true }, {}, { 0L },
        requireCurrentLiveWindowOnPlay = true)

    @Test fun `a paused feed past its oldest point resumes there with its partner, keeping the offset`() {
        val a = Member(live.copy(positionMs = -20_000, liveOffsetMs = 420_000))
        val b = Member(live.copy(positionMs = 200_000, liveOffsetMs = 200_000))
        val pair = pair(a, b)
        assertTrue(pair.play(onStaleLive = { throw AssertionError("A repairable feed must not be retired") }))
        assertEquals(listOf(5_000L), a.seeks)
        assertEquals(listOf(225_000L), b.seeks)
        assertEquals(1, a.playRequests)
        assertEquals(1, b.playRequests)
        assertTrue(pair.requestedAdjustmentValid)
        assertNotNull(pair.lastTimingAttempt)
        assertTrue(checkNotNull(pair.lastOldestResume).keepsOffset)
        assertEquals("A passed the oldest recorded point while paused, so playback resumed there.", pair.status)
    }

    @Test fun `a partner that cannot move as far stays put and the offset is no longer held`() {
        val a = Member(live.copy(positionMs = -20_000, liveOffsetMs = 420_000))
        val b = Member(live)
        val pair = pair(a, b)
        assertTrue(pair.play(onStaleLive = { throw AssertionError("A repairable feed must not be retired") }))
        assertEquals(listOf(5_000L), a.seeks)
        assertTrue(b.seeks.isEmpty())
        assertFalse(pair.requestedAdjustmentValid)
        assertFalse(checkNotNull(pair.lastOldestResume).keepsOffset)
        assertTrue(pair.status.endsWith("the offset was not kept."))
    }

    @Test fun `an unseekable or refused feed still takes the stale path`() {
        val unseekable = Member(live.copy(positionMs = -20_000, seekable = false))
        val stale = mutableListOf<Set<NativeMixedSide>>()
        assertFalse(pair(unseekable, Member(live)).play(onStaleLive = { stale += it; false }))
        assertEquals(listOf(setOf(NativeMixedSide.A)), stale)
        assertTrue(unseekable.seeks.isEmpty())

        val refused = Member(live.copy(positionMs = -20_000)).apply { refuseSeek = true }
        val partner = Member(live)
        val pair = pair(refused, partner)
        assertFalse(pair.play(onStaleLive = { throw AssertionError("Refusal pauses instead") }))
        assertEquals(0, refused.playRequests + partner.playRequests)
        assertFalse(pair.requestedAdjustmentValid)
        assertEquals("Resuming from the oldest recorded point was refused; pause requested for both.", pair.status)
    }

    @Test fun `a single feed paused past its oldest point resumes there`() {
        val feed = Member(live.copy(positionMs = -20_000))
        val controller = PrototypePlaybackController({ true }, singleFeed = true)
        controller.setMember(NativeMixedSide.A, feed)
        assertTrue(controller.play { _, _ -> throw AssertionError("A repairable feed must not be retired") })
        assertEquals(listOf(5_000L), feed.seeks)
        assertEquals(1, feed.playRequests)
        assertEquals("The feed passed the oldest recorded point while paused, so playback resumed there.", controller.status)
    }

    @Test fun `a nudge that would pass the delay limit is refused with the limit`() {
        // A is held 5:55 behind its live point; B is 5 s from its window end.
        val a = Member(live.copy(positionMs = 30_000, liveOffsetMs = 365_000))
        val b = Member(live.copy(positionMs = 385_000))
        val pair = pair(a, b)
        // A 10 s earlier relative to B: A back 10 s (past 6:00) or B forward
        // 10 s (past its live end). Neither is allowed.
        assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.shiftRelative(-10_000).outcome)
        assertEquals(NativeRelativeNudgeOutcome.NO_FULL_STEP, pair.lastRelativePlan?.outcome)
        assertEquals(LIVE_DELAY_LIMIT_MESSAGE, pair.status)
        assertTrue(a.seeks.isEmpty() && b.seeks.isEmpty())
        // Up to the limit is still allowed.
        assertEquals(NativeSeekOutcome.REQUESTED, pair.shiftRelative(-5_000).outcome)
        assertEquals(listOf(25_000L), a.seeks)
    }

    @Test fun `a direct shift or marked alignment past the delay limit is refused`() {
        val a = Member(live.copy(positionMs = 30_000, liveOffsetMs = 365_000))
        val b = Member(live.copy(positionMs = 385_000))
        val pair = pair(a, b)
        assertEquals(NativeSeekOutcome.OUTSIDE_WINDOW, pair.shift(NativeMixedSide.A, -10_000).outcome)
        assertEquals(LIVE_DELAY_LIMIT_MESSAGE, pair.status)
        // 5 s back on A and 5 s forward on B are all the room there is.
        assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.alignRelative(-20_000).outcome)
        assertTrue(checkNotNull(pair.lastAlignmentPlan).delayLimited)
        assertEquals(LIVE_DELAY_LIMIT_MESSAGE, pair.status)
        assertTrue(a.seeks.isEmpty() && b.seeks.isEmpty())
        assertNull(pair.lastOldestResume)
    }
}
