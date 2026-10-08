package net.fstab.tachiai.platform.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMixedPairRecoveryTest {
    private val live = NativeTimingSnapshot(-5_000, 60_000, 0, null, 1_000_000, 50_000,
        true, true, true, true, false, false, false, 3, 1f)
    private class Member(var sample: NativeTimingSnapshot) : NativePairMember {
        var defaults = 0
        var seeks = 0
        var refusePause = false
        var failDefault = false
        var throwDefault = false
        var defaultResult: NativeSeekPlan? = null
        var refusePauseAfterDefault = false
        var closed = false
        override fun timingSnapshot() = if (closed) null else sample
        override fun seekLiveDefault(): NativeSeekPlan {
            defaults++
            if (refusePauseAfterDefault) refusePause = true
            if (throwDefault) throw IllegalStateException()
            if (failDefault) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
            defaultResult?.let { return it }
            val plan = nativeLiveDefaultPlan(sample)
            sample = sample.copy(positionMs = plan.targetMs)
            return plan
        }
        override fun seekToMs(targetMs: Long): NativeSeekPlan {
            seeks++
            val plan = nativeSeekPlan(sample, targetMs)
            sample = sample.copy(positionMs = plan.targetMs)
            return plan
        }
        override fun setTimingPlaying(playing: Boolean): Boolean {
            if (!playing && refusePause) return false
            sample = sample.copy(playing = playing, playWhenReady = playing)
            return true
        }
        override fun setVolume(volume: Float) = true
        override fun close() { closed = true }
    }
    private fun pair(a: Member, b: Member, clock: () -> Long = { 0L }) =
        NativeMixedPair(a, b, { true }, {}, clock, requireCurrentLiveWindowOnPlay = true)

    @Test fun `expired live recovery holds both dispatches one default and requires explicit play`() {
        for (side in NativeMixedSide.entries) {
            val a = Member(live); val b = Member(live)
            val pair = pair(a, b)
            pair.play()
            assertEquals(NativeSeekOutcome.REQUESTED, pair.catchUp(side).outcome)
            assertFalse(pair.requestedAdjustmentValid)
            assertTrue(pair.busy)
            assertFalse(a.sample.playing); assertFalse(b.sample.playing)
            pair.poll()
            assertFalse(pair.busy)
            assertEquals(1, a.defaults + b.defaults)
            assertEquals(0, a.seeks + b.seeks)
            assertFalse(a.sample.playing); assertFalse(b.sample.playing)
            pair.catchUp(if (side == NativeMixedSide.A) NativeMixedSide.B else NativeMixedSide.A)
            pair.poll()
            assertFalse(pair.busy)
            assertEquals(2, a.defaults + b.defaults)
            assertTrue(pair.play())
        }
    }

    @Test fun `default command does not require arbitrary seek or a live epoch`() {
        val a = Member(live.copy(seekable = false, windowStartMs = null)); val b = Member(live)
        val pair = pair(a, b)
        assertEquals(NativeSeekOutcome.REQUESTED, pair.catchUp(NativeMixedSide.A).outcome)
        pair.poll()
        assertFalse(pair.busy)
    }

    @Test fun `preflight refusal and no change preserve anchor without hold or dispatch`() {
        val samples = listOf(
            live.copy(live = false, dynamic = false), live.copy(canSeekDefault = false),
            live.copy(defaultPositionMs = null), live.copy(defaultPositionMs = 70_000),
            live.copy(playingAd = true), live.copy(state = 2),
            live.copy(positionMs = 50_000), live.copy(durationMs = 0),
        )
        for (sample in samples) {
            val a = Member(sample); val b = Member(live)
            val pair = pair(a, b)
            assertTrue(pair.catchUp(NativeMixedSide.A).outcome != NativeSeekOutcome.REQUESTED)
            assertEquals(0, a.defaults + b.defaults)
            assertTrue(pair.requestedAdjustmentValid)
            assertFalse(pair.busy)
        }
        val a = Member(live); val b = Member(live.copy(playingAd = true))
        assertEquals(NativeSeekOutcome.AD_BLOCKED, pair(a, b).catchUp(NativeMixedSide.A).outcome)
    }

    @Test fun `hold failure never dispatches and ambiguous dispatch failure invalidates anchor`() {
        val a = Member(live); val b = Member(live)
        b.refusePause = true
        val held = pair(a, b)
        assertEquals(NativeSeekOutcome.UNAVAILABLE, held.catchUp(NativeMixedSide.A).outcome)
        assertEquals(0, a.defaults); assertTrue(held.requestedAdjustmentValid)
        for (throws in listOf(false, true)) {
            val selected = Member(live).apply { throwDefault = throws; failDefault = !throws }
            val peer = Member(live)
            val pair = pair(selected, peer)
            assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.catchUp(NativeMixedSide.A).outcome)
            assertFalse(pair.requestedAdjustmentValid); assertFalse(pair.busy)
            assertEquals(1, selected.defaults); assertEquals(0, peer.defaults)
            assertFalse(selected.sample.playing); assertFalse(peer.sample.playing)
        }
    }

    @Test fun `settlement observes current live window not a stale fixed target`() {
        val a = Member(live); val b = Member(live)
        val pair = pair(a, b)
        pair.catchUp(NativeMixedSide.A)
        a.sample = a.sample.copy(positionMs = 20_000, defaultPositionMs = 55_000, windowStartMs = 2_000_000)
        pair.poll()
        assertFalse(pair.busy)
        assertTrue(pair.status.contains("current live window"))
    }

    @Test fun `out of window or not held ready waits and timeout never resumes`() {
        for (changed in listOf(live.copy(positionMs = -1), live.copy(positionMs = 60_001),
                live.copy(positionMs = 50_000, state = 2), live.copy(positionMs = 50_000, playingAd = true),
                live.copy(positionMs = 50_000, live = false), live.copy(positionMs = null))) {
            var now = 0L
            val a = Member(live); val b = Member(live)
            val pair = pair(a, b) { now }
            pair.catchUp(NativeMixedSide.A)
            a.sample = changed
            pair.poll(); assertTrue(pair.busy)
            now = 8_000; pair.poll()
            assertFalse(pair.busy); assertFalse(a.sample.playing); assertFalse(b.sample.playing)
            assertFalse(pair.requestedAdjustmentValid)
        }
    }

    @Test fun `recovery cannot redispatch while busy and pause focus loss or close cancels checks`() {
        for (cancel in 0..2) {
            val a = Member(live); val b = Member(live)
            val pair = pair(a, b)
            pair.catchUp(NativeMixedSide.A)
            assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.catchUp(NativeMixedSide.B).outcome)
            assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.shiftRelative(5_000).outcome)
            when (cancel) { 0 -> pair.pause(); 1 -> pair.focusLost(); else -> pair.close() }
            pair.poll()
            assertFalse(pair.busy); assertEquals(1, a.defaults); assertEquals(0, b.defaults)
            assertFalse(pair.requestedAdjustmentValid)
        }
    }

    @Test fun `subsequent nudges do not restore an invalid anchor or erase history`() {
        val a = Member(live.copy(positionMs = 20_000)); val b = Member(live.copy(positionMs = 20_000))
        val pair = pair(a, b)
        pair.shiftRelative(5_000); pair.poll()
        assertEquals(5_000L, pair.requestedAdjustmentMs)
        pair.catchUp(NativeMixedSide.A); pair.poll()
        pair.shiftRelative(-5_000); pair.poll()
        assertEquals(0L, pair.requestedAdjustmentMs)
        assertFalse(pair.requestedAdjustmentValid)
    }

    @Test fun `changed dispatch metadata refuses without retry and keeps anchor invalid`() {
        for (outcome in listOf(NativeSeekOutcome.CLAMPED, NativeSeekOutcome.UNSUPPORTED, NativeSeekOutcome.AD_BLOCKED)) {
            val a = Member(live).apply { defaultResult = NativeSeekPlan(outcome) }
            val b = Member(live)
            val pair = pair(a, b)
            assertEquals(outcome, pair.catchUp(NativeMixedSide.A).outcome)
            pair.poll()
            assertFalse(pair.busy); assertFalse(pair.requestedAdjustmentValid)
            assertEquals(1, a.defaults); assertEquals(0, b.defaults)
        }
    }

    @Test fun `B recovery with replay peer waits for peer hold and clock rollback cancels`() {
        for (changed in listOf(live.copy(state = 2), live.copy(playingAd = true), live.copy(playing = true))) {
            var now = 1_000L
            val a = Member(live.copy(live = false, dynamic = false, positionMs = 10_000)); val b = Member(live)
            val pair = pair(a, b) { now }
            pair.catchUp(NativeMixedSide.B)
            a.sample = changed
            pair.poll(); assertTrue(pair.busy)
            now = 999; pair.poll()
            assertFalse(pair.busy); assertFalse(pair.requestedAdjustmentValid)
            assertEquals(0, a.defaults); assertEquals(1, b.defaults)
        }
    }

    @Test fun `guarded play refuses an expired or unknown live window before focus or play`() {
        for (bad in listOf(live, live.copy(positionMs = null), live.copy(durationMs = null),
                live.copy(durationMs = 0), live.copy(positionMs = 60_001))) {
            for (badSide in NativeMixedSide.entries) {
                val good = live.copy(positionMs = 20_000)
                val a = Member(if (badSide == NativeMixedSide.A) bad else good)
                val b = Member(if (badSide == NativeMixedSide.B) bad else good)
                var focus = 0
                val pair = NativeMixedPair(a, b, { focus++; true }, {}, requireCurrentLiveWindowOnPlay = true)
                assertFalse(pair.play()); assertEquals(0, focus)
                assertFalse(a.sample.playing); assertFalse(b.sample.playing)
                assertTrue(pair.requestedAdjustmentValid)
                assertEquals(0, a.defaults + b.defaults)
            }
        }
    }

    @Test fun `guard is opt in and replay playback does not require a live window`() {
        val a = Member(live); val b = Member(live)
        assertTrue(NativeMixedPair(a, b, { true }, {}).play())
        val replay = live.copy(live = false, dynamic = false, positionMs = null, durationMs = null)
        assertTrue(pair(Member(replay), Member(replay)).play())
    }

    @Test fun `guard keeps Stop required on failed hold and handles dynamic or endpoint windows`() {
        val a = Member(live.copy(playing = true, playWhenReady = true)).apply { refusePause = true }
        val b = Member(live)
        val failed = pair(a, b)
        assertFalse(failed.play()); assertTrue(failed.status.contains("Stop required"))
        assertTrue(a.sample.playing); assertFalse(b.sample.playing)
        assertFalse(pair(Member(live.copy(live = false)), Member(live)).play())
        assertTrue(pair(Member(live.copy(positionMs = 0)), Member(live.copy(positionMs = 60_000))).play())
    }

    @Test fun `dispatch refusal and timeout preserve Stop required when a later pause fails`() {
        val failed = Member(live).apply { failDefault = true; refusePauseAfterDefault = true }
        val pair = pair(failed, Member(live))
        pair.catchUp(NativeMixedSide.A)
        assertTrue(pair.status.contains("Stop required"))
        assertFalse(pair.requestedAdjustmentValid)
        var now = 0L
        val a = Member(live); val b = Member(live)
        val timed = pair(a, b) { now }
        timed.catchUp(NativeMixedSide.A)
        b.refusePause = true
        b.sample = b.sample.copy(playing = true, playWhenReady = true)
        now = 8_000; timed.poll()
        assertTrue(timed.status.contains("Stop required")); assertFalse(timed.busy)
        assertTrue(b.sample.playing); assertFalse(timed.requestedAdjustmentValid)
    }
}
