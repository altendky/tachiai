package net.fstab.tachiai.platform.media

import org.junit.Assert.*
import org.junit.Test

class NativeMarkedAlignmentTest {
    private fun sample(position: Long, origin: Long? = null) = NativeTimingSnapshot(position, 60_000,
        position, null, origin, null, origin != null, origin != null, true, true, false, true, true, 3, 1f)
    private class Member(var value: NativeTimingSnapshot) : NativePairMember {
        var seeks = 0
        var plays = 0
        val seekTargets = mutableListOf<Long>()
        var refuse = false
        var refusePause = false
        var pauseHook: () -> Unit = {}
        var seekHook: () -> Unit = {}
        var playHook: () -> Unit = {}
        var readHook: () -> Unit = {}
        override fun timingSnapshot(): NativeTimingSnapshot { readHook(); return value }
        override fun seekToMs(targetMs: Long): NativeSeekPlan {
            seeks++; seekTargets += targetMs; seekHook()
            if (refuse) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
            value = value.copy(positionMs = targetMs)
            return NativeSeekPlan(NativeSeekOutcome.REQUESTED, targetMs)
        }
        override fun setTimingPlaying(playing: Boolean): Boolean {
            if (!playing && refusePause) return false
            value = value.copy(playing = playing, playWhenReady = playing)
            if (playing) { plays++; playHook() } else pauseHook()
            return true
        }
        override fun setVolume(volume: Float) = true
        override fun close() {}
    }

    @Test fun `combined rooms deliver both signs with unrelated own clocks`() {
        for (delta in listOf(26_400L, -26_400L)) {
            val a = sample(if (delta > 0) 39_500 else 20_500, 1_000_000)
            val b = sample(if (delta > 0) 10_000 else 50_000, 9_000_000)
            assertEquals(NativeRelativeNudgeOutcome.NO_FULL_STEP, nativeRelativeNudgePlan(a, b, delta).outcome)
            val plan = nativeMarkedAlignmentPlan(a, b, delta)
            assertEquals(NativeSeekOutcome.REQUESTED, plan.outcome)
            assertEquals(2, plan.targets.size)
            assertEquals(delta, plan.targets[0].movementMs - plan.targets[1].movementMs)
            assertEquals(if (delta > 0) 57_950L else 2_050L, plan.targets[0].targetMs)
            assertEquals(if (delta > 0) 18_450L else -18_450L, plan.targets[0].movementMs)
            assertEquals(if (delta > 0) -7_950L else 7_950L, plan.targets[1].movementMs)
        }
    }

    @Test fun `single source remains preferred and insufficient aggregate rooms refuse`() {
        assertEquals(1, nativeMarkedAlignmentPlan(sample(30_000), sample(30_000), 5_000).targets.size)
        assertEquals(NativeMixedSide.A, nativeMarkedAlignmentPlan(sample(30_000), sample(30_000), 5_000).targets.single().side)
        assertEquals(NativeSeekOutcome.UNAVAILABLE,
            nativeMarkedAlignmentPlan(sample(20_500), sample(59_000), -26_400).outcome)
        for (delta in listOf(Long.MIN_VALUE, Long.MAX_VALUE))
            assertEquals(NativeSeekOutcome.UNAVAILABLE, nativeMarkedAlignmentPlan(sample(20_500), sample(59_000), delta).outcome)
        assertEquals(NativeSeekOutcome.AD_BLOCKED,
            nativeMarkedAlignmentPlan(sample(20_500).copy(playingAd = true), sample(50_000), -26_400).outcome)
        assertEquals(NativeSeekOutcome.UNAVAILABLE,
            nativeMarkedAlignmentPlan(sample(20_500).copy(seekable = false), sample(50_000), -26_400).outcome)
    }

    @Test fun `both target clocks must settle before either resumes and manual nudge stays unchanged`() {
        val a = Member(sample(20_500, 1_000_000)); val b = Member(sample(50_000, 9_000_000))
        val pair = NativeMixedPair(a, b, { true }, {})
        assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.shiftRelative(-26_400).outcome)
        assertEquals(0, a.seeks + b.seeks)
        assertEquals(NativeSeekOutcome.REQUESTED, pair.alignRelative(-26_400).outcome)
        assertEquals(1, a.seeks); assertEquals(1, b.seeks)
        assertEquals(-26_400L, pair.requestedAdjustmentMs)
        assertEquals(2, pair.pendingSeekDiagnostics.size)
        val targetB = b.value.positionMs
        b.value = b.value.copy(positionMs = targetB!! - 1_000)
        pair.poll(); assertTrue(pair.busy); assertEquals(0, a.plays + b.plays)
        assertEquals(9_057_950L, pair.pendingSeekDiagnostics.last().targetClockMs)
        assertEquals(9_056_950L, pair.pendingSeekDiagnostics.last().actualClockMs)
        b.value = b.value.copy(positionMs = targetB)
        pair.poll(); assertFalse(pair.busy); assertEquals(1, a.plays); assertEquals(1, b.plays)
        pair.poll(); assertEquals(1, a.plays); assertTrue(pair.pendingSeekDiagnostics.isEmpty())
    }

    @Test fun `paused pair stays paused and live window slide preserves both clock targets`() {
        val a = Member(sample(20_500, 1_000_000).copy(playing = false, playWhenReady = false))
        val b = Member(sample(50_000, 9_000_000).copy(playing = false, playWhenReady = false))
        val pair = NativeMixedPair(a, b, { true }, {})
        pair.alignRelative(-26_400)
        // A later window cannot use the same relative position as evidence.
        // Keep it pending until its own exact clock is observed.
        a.value = a.value.copy(windowStartMs = 1_001_000)
        pair.poll(); assertTrue(pair.busy)
        a.value = a.value.copy(windowStartMs = 1_000_000)
        pair.poll(); assertFalse(pair.busy); assertEquals(0, a.plays + b.plays)
    }

    @Test fun `interior targets survive a window slide before the second dispatch`() {
        val a = Member(sample(20_500, 1_000_000)); val b = Member(sample(50_000, 9_000_000))
        val pair = NativeMixedPair(a, b, { true }, {})
        a.seekHook = {
            b.value = b.value.copy(windowStartMs = 9_001_000, positionMs = 49_000)
        }
        assertEquals(NativeSeekOutcome.REQUESTED, pair.alignRelative(-26_400).outcome)
        assertEquals(2_050L, a.value.positionMs)
        assertEquals(56_950L, b.value.positionMs)
        assertEquals(1, a.seeks); assertEquals(1, b.seeks)
        pair.poll(); assertFalse(pair.busy)
        assertEquals(1, a.plays); assertEquals(1, b.plays)
    }

    @Test fun `confirmed composite target may continue buffered data after its window slides`() {
        val a = Member(sample(20_500, 1_000_000)); val b = Member(sample(50_000, 9_000_000))
        val pair = NativeMixedPair(a, b, { true }, {}, requireCurrentLiveWindowOnPlay = true)
        assertEquals(NativeSeekOutcome.REQUESTED, pair.alignRelative(-26_400).outcome)
        // The original dispatch targeted a legal point. It later left the
        // advertised window, but SDK READY confirms that exact buffered point.
        a.value = a.value.copy(windowStartMs = 1_003_000, positionMs = -950, bufferedMs = -500)
        pair.poll()
        assertFalse(pair.busy); assertEquals(2, a.plays + b.plays)
        assertTrue(a.value.playing); assertTrue(b.value.playing)
        assertEquals(1, a.seeks); assertEquals(1, b.seeks)
        assertTrue((a.seekTargets + b.seekTargets).all { it >= 0 })
        assertEquals(NativeTimingSettlement(1, -26_400), pair.lastTimingSettlement)
    }

    @Test fun `paused expired composite target never publishes a settlement even with buffered data`() {
        val a = Member(sample(20_500, 1_000_000).copy(playing = false, playWhenReady = false))
        val b = Member(sample(50_000, 9_000_000).copy(playing = false, playWhenReady = false))
        val pair = NativeMixedPair(a, b, { true }, {}, requireCurrentLiveWindowOnPlay = true)
        pair.alignRelative(-26_400)
        a.value = a.value.copy(windowStartMs = 1_003_000, positionMs = -950, bufferedMs = 10_000)
        pair.poll()
        assertFalse(pair.busy); assertEquals(0, a.plays + b.plays)
        assertNull(pair.lastTimingSettlement)
        assertTrue(pair.status.contains("expired while playback was paused"))
    }

    @Test fun `selected buffered continuation is Mark only and never authorizes an explicit Play`() {
        for (marked in listOf(true, false)) for (side in NativeMixedSide.entries) {
            val a = Member(sample(20_000, 1_000_000).copy(seekable = side == NativeMixedSide.A))
            val b = Member(sample(30_000, 9_000_000).copy(seekable = side == NativeMixedSide.B))
            val pair = NativeMixedPair(a, b, { true }, {}, requireCurrentLiveWindowOnPlay = true)
            if (marked) pair.alignRelative(-5_000) else pair.shiftRelative(-5_000)
            assertEquals(side, if (marked) pair.lastAlignmentPlan?.targets?.single()?.side else pair.lastRelativePlan?.side)
            val selected = if (side == NativeMixedSide.A) a else b
            selected.value = selected.value.copy(windowStartMs = if (side == NativeMixedSide.A) 1_016_000 else 9_036_000,
                positionMs = -1_000, bufferedMs = 5_000)
            pair.poll(); assertFalse(pair.busy)
            assertEquals(if (marked) 2 else 0, a.plays + b.plays)
            assertEquals(if (marked) NativeTimingSettlement(1, -5_000) else null, pair.lastTimingSettlement)
            assertTrue((a.seekTargets + b.seekTargets).all { it >= 0 })
            pair.pause(); assertFalse(pair.play())
        }
    }

    @Test fun `buffer or window proof lost before resume refuses the selected expired target`() {
        for (invalid in 0..2) {
            val a = Member(sample(20_000, 1_000_000))
            val b = Member(sample(30_000, 9_000_000).copy(seekable = false))
            val pair = NativeMixedPair(a, b, { true }, {})
            pair.alignRelative(-5_000)
            assertEquals(NativeMixedSide.A, pair.lastAlignmentPlan?.targets?.single()?.side)
            a.value = a.value.copy(windowStartMs = 1_016_000, positionMs = -1_000,
                bufferedMs = if (invalid == 0) null else if (invalid == 1) -1_000 else 5_000,
                durationMs = if (invalid == 2) null else 60_000)
            pair.poll(); assertFalse(pair.busy)
            assertNull(pair.lastTimingSettlement); assertEquals(0, a.plays + b.plays)
            assertTrue(pair.status.contains(if (invalid == 2) "window is unavailable" else "buffered continuation is unavailable"))
        }
    }

    @Test fun `focus phase rechecks exact target buffer clock kind ad lifetime and deadline`() {
        for (invalid in 0..7) {
            val a = Member(sample(20_000, 1_000_000))
            val b = Member(sample(30_000, 9_000_000).copy(seekable = false))
            var allowed = true; var now = 0L
            lateinit var pair: NativeMixedPair
            pair = NativeMixedPair(a, b, {
                when (invalid) {
                    0 -> a.value = a.value.copy(bufferedMs = null)
                    1 -> a.value = a.value.copy(windowStartMs = 1_016_101)
                    2 -> a.value = a.value.copy(live = false, dynamic = false)
                    3 -> a.value = a.value.copy(playingAd = true)
                    4 -> allowed = false
                    5 -> pair.pause()
                    6 -> now = 8_000
                    else -> a.value = a.value.copy(durationMs = 0)
                }
                true
            }, {}, { now }, admission = { allowed })
            pair.alignRelative(-5_000)
            assertEquals(NativeMixedSide.A, pair.lastAlignmentPlan?.targets?.single()?.side)
            a.value = a.value.copy(windowStartMs = 1_016_000, positionMs = -1_000, bufferedMs = 5_000)
            pair.poll(); assertFalse(pair.busy)
            assertNull(pair.lastTimingSettlement); assertEquals(0, a.plays + b.plays)
            assertFalse(a.value.playing); assertFalse(b.value.playing)
        }
    }

    @Test fun `second selected target is rechecked immediately before its Play call`() {
        for (invalid in 0..4) {
            val a = Member(sample(20_000, 1_000_000).copy(seekable = false))
            val b = Member(sample(30_000, 9_000_000))
            var allowed = true
            val pair = NativeMixedPair(a, b, { true }, {}, admission = { allowed })
            pair.alignRelative(-5_000) // A cannot move; B advances to 35 seconds.
            assertEquals(NativeMixedSide.B, pair.lastAlignmentPlan?.targets?.single()?.side)
            b.value = b.value.copy(windowStartMs = 9_036_000, positionMs = -1_000, bufferedMs = 5_000)
            a.playHook = {
                when (invalid) {
                    0 -> b.value = b.value.copy(bufferedMs = null)
                    1 -> b.value = b.value.copy(windowStartMs = 9_036_101)
                    2 -> b.value = b.value.copy(playingAd = true)
                    3 -> allowed = false
                    else -> pair.pause()
                }
            }
            pair.poll(); assertFalse(pair.busy)
            assertNull(pair.lastTimingSettlement); assertEquals(1, a.plays); assertEquals(0, b.plays)
            assertFalse(a.value.playing); assertFalse(b.value.playing)
            assertTrue(b.seekTargets.all { it >= 0 })
        }
    }

    @Test fun `a changed target before any SDK seek preserves confirmed history`() {
        val a = Member(sample(20_500, 1_000_000)); val b = Member(sample(50_000, 9_000_000))
        val pair = NativeMixedPair(a, b, { true }, {})
        var reads = 0
        a.readHook = { if (++reads == 3) a.value = a.value.copy(playingAd = true) }
        assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.alignRelative(-26_400).outcome)
        assertEquals(0, a.seeks + b.seeks)
        assertTrue(pair.requestedAdjustmentValid); assertNull(pair.lastTimingAttempt)
        assertNull(pair.lastTimingSettlement); assertTrue(pair.status.contains("before dispatch"))
    }

    @Test fun `failed second target verification cannot claim both paused when first target refuses pause`() {
        val a = Member(sample(20_500, 1_000_000)); val b = Member(sample(50_000, 9_000_000))
        val pair = NativeMixedPair(a, b, { true }, {})
        pair.alignRelative(-26_400)
        a.playHook = {
            a.refusePause = true
            b.value = b.value.copy(windowStartMs = 9_001_000)
        }
        pair.poll()
        assertTrue(a.value.playing); assertFalse(b.value.playing)
        assertNull(pair.lastTimingSettlement); assertFalse(pair.busy)
        assertTrue(pair.status.contains("pause refused; Stop required"))
        assertFalse(pair.status.contains("both paused"))
    }

    @Test fun `second dispatch refusal invalidates ledger and never retries`() {
        val a = Member(sample(20_500)); val b = Member(sample(50_000)).apply { refuse = true }
        val pair = NativeMixedPair(a, b, { true }, {})
        assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.alignRelative(-26_400).outcome)
        assertEquals(1, a.seeks); assertEquals(1, b.seeks)
        assertEquals(0L, pair.requestedAdjustmentMs); assertFalse(pair.requestedAdjustmentValid)
        assertFalse(a.value.playing); assertFalse(b.value.playing); assertFalse(pair.busy)
        pair.poll(); assertEquals(2, a.seeks + b.seeks); assertEquals(0, a.plays + b.plays)
    }

    @Test fun `window or authority change after hold refuses before any seek`() {
        for (change in 0..1) {
            val a = Member(sample(20_500)); val b = Member(sample(50_000))
            var allowed = true
            val pair = NativeMixedPair(a, b, { true }, {}, admission = { allowed })
            b.pauseHook = { if (change == 0) b.value = b.value.copy(positionMs = 60_000) else allowed = false }
            assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.alignRelative(-26_400).outcome)
            assertEquals(0, a.seeks + b.seeks); assertEquals(0, a.plays + b.plays)
        }
    }

    @Test fun `pause focus loss detach close timeout and rollback cancel restoration`() {
        for (cancel in 0..5) {
            val a = Member(sample(20_500)); val b = Member(sample(50_000))
            var now = 0L
            val pair = NativeMixedPair(a, b, { true }, {}, { now })
            pair.alignRelative(-26_400)
            when (cancel) {
                0 -> pair.pause()
                1 -> pair.focusLost()
                2 -> pair.detach()
                3 -> pair.close()
                4 -> now = 8_000
                else -> now = -1
            }
            pair.poll(); assertFalse(pair.busy); assertEquals(0, a.plays + b.plays)
            assertFalse(pair.requestedAdjustmentValid)
        }
    }

    @Test fun `reentrant detach during hold or first dispatch never dispatches second`() {
        for (duringHold in listOf(true, false)) {
            val a = Member(sample(20_500)); val b = Member(sample(50_000))
            val pair = NativeMixedPair(a, b, { true }, {})
            if (duringHold) a.pauseHook = { pair.detach() } else a.seekHook = { pair.detach() }
            assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.alignRelative(-26_400).outcome)
            assertEquals(if (duringHold) 0 else 1, a.seeks); assertEquals(0, b.seeks)
            pair.poll(); assertEquals(0, a.plays + b.plays)
        }
    }

    @Test fun `authority or focus loss during settlement never resumes`() {
        for (loseAuthority in listOf(true, false)) {
            val a = Member(sample(20_500)); val b = Member(sample(50_000))
            var allowed = true
            val pair = NativeMixedPair(a, b, { loseAuthority }, {}, admission = { allowed })
            pair.alignRelative(-26_400)
            if (loseAuthority) allowed = false
            pair.poll(); assertFalse(pair.busy); assertEquals(0, a.plays + b.plays)
        }
    }
}
