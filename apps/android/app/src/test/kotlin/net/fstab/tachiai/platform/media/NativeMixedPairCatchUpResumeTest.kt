package net.fstab.tachiai.platform.media

import org.junit.Assert.*
import org.junit.Test

class NativeMixedPairCatchUpResumeTest {
    private fun live(position: Long = 20_000, playing: Boolean = true, origin: Long = 1_000_000) =
        NativeTimingSnapshot(position, 60_000, 40_000, null, origin, 50_000,
            true, true, true, true, false, playing, playing, 3, 1f)
    private class Member(var value: NativeTimingSnapshot) : NativePairMember {
        var defaults = 0
        var seeks = 0
        var plays = 0
        var refuseDefault = false
        var refusePause = false
        var onPause: () -> Unit = {}
        var onRead: () -> Unit = {}
        var onPlay: () -> Unit = {}
        var onDefault: () -> Unit = {}
        override fun timingSnapshot(): NativeTimingSnapshot { onRead(); return value }
        override fun seekLiveDefault(): NativeSeekPlan {
            defaults++; onDefault()
            if (refuseDefault) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
            value = value.copy(positionMs = value.defaultPositionMs)
            return NativeSeekPlan(NativeSeekOutcome.REQUESTED, value.positionMs)
        }
        override fun seekToMs(targetMs: Long): NativeSeekPlan { seeks++; error("Catch-up must use SDK default") }
        override fun setTimingPlaying(playing: Boolean): Boolean {
            if (!playing && refusePause) return false
            value = value.copy(playing = playing, playWhenReady = playing)
            if (playing) { plays++; onPlay() } else onPause()
            return true
        }
        override fun setVolume(volume: Float) = true
        override fun close() {}
    }

    @Test fun `playing pair resumes once after either selected live feed settles`() {
        for (side in NativeMixedSide.entries) {
            val a = Member(live()); val b = Member(live(origin = 9_000_000))
            val pair = NativeMixedPair(a, b, { true }, {}, requireCurrentLiveWindowOnPlay = true)
            assertEquals(NativeSeekOutcome.REQUESTED, pair.catchUp(side).outcome)
            assertTrue(pair.busy); assertFalse(a.value.playing); assertFalse(b.value.playing)
            assertFalse(pair.requestedAdjustmentValid)
            pair.poll(); assertFalse(pair.busy)
            assertTrue(a.value.playing); assertTrue(b.value.playing)
            assertEquals(1, a.plays); assertEquals(1, b.plays)
            assertEquals(1, a.defaults + b.defaults); assertEquals(0, a.seeks + b.seeks)
            pair.poll(); assertEquals(1, a.plays); assertEquals(1, b.plays)
        }
    }

    @Test fun `untouched playing peer continues the same buffered clock as live window ages`() {
        for (side in NativeMixedSide.entries) {
            val selected = Member(live())
            val peer = Member(live(-2_000, origin = 9_000_000))
            val a = if (side == NativeMixedSide.A) selected else peer
            val b = if (side == NativeMixedSide.B) selected else peer
            val pair = NativeMixedPair(a, b, { true }, {}, requireCurrentLiveWindowOnPlay = true)
            pair.catchUp(side)
            peer.value = peer.value.copy(windowStartMs = 9_030_000, positionMs = -32_000, bufferedMs = 10_000)
            pair.poll(); assertFalse(pair.busy)
            assertTrue(peer.value.playing); assertTrue(selected.value.playing)
            assertEquals(0, peer.defaults + peer.seeks); assertEquals(1, selected.defaults)
            pair.pause()
            assertFalse("Explicit Play still refuses an expired live window", pair.play())
        }
    }

    @Test fun `originally paused or partially playing pair is never automatically started`() {
        for ((playingA, playingB) in listOf(false to false, true to false, false to true)) {
            val a = Member(live(playing = playingA)); val b = Member(live(playing = playingB))
            val pair = NativeMixedPair(a, b, { true }, {})
            pair.catchUp(NativeMixedSide.A); pair.poll()
            assertFalse(pair.busy); assertFalse(a.value.playing); assertFalse(b.value.playing)
            assertEquals(0, a.plays + b.plays)
            assertTrue(pair.status.contains("Explicit Play required"))
        }
    }

    @Test fun `peer buffer loss clock drift or ads cannot authorize automatic resume`() {
        for (invalid in 0..3) {
            val a = Member(live()); val b = Member(live(-2_000, origin = 9_000_000))
            var now = 0L
            val pair = NativeMixedPair(a, b, { true }, {}, { now }, requireCurrentLiveWindowOnPlay = true)
            pair.catchUp(NativeMixedSide.A)
            b.value = when (invalid) {
                0 -> b.value.copy(bufferedMs = b.value.positionMs)
                1 -> b.value.copy(windowStartMs = 9_000_101)
                2 -> b.value.copy(live = false, dynamic = false)
                else -> b.value.copy(playingAd = true)
            }
            pair.poll()
            if (invalid == 3) { assertTrue(pair.busy); now = 8_000; pair.poll() }
            assertFalse(pair.busy); assertFalse(a.value.playing); assertFalse(b.value.playing)
            assertEquals(0, a.plays + b.plays); assertEquals(1, a.defaults)
        }
    }

    @Test fun `pause focus loss detach close ownership loss timeout and rollback cancel restoration`() {
        for (cancel in 0..6) {
            val a = Member(live()); val b = Member(live())
            var allowed = true; var now = 0L
            val pair = NativeMixedPair(a, b, { true }, {}, { now }, admission = { allowed })
            pair.catchUp(NativeMixedSide.A)
            when (cancel) {
                0 -> pair.pause()
                1 -> pair.focusLost()
                2 -> pair.detach()
                3 -> pair.close()
                4 -> allowed = false
                5 -> now = 8_000
                else -> now = -1
            }
            pair.poll(); assertFalse(pair.busy); assertEquals(0, a.plays + b.plays)
            assertFalse(a.value.playing); assertFalse(b.value.playing)
            assertEquals(1, a.defaults + b.defaults)
        }
    }

    @Test fun `refused default or hold does not resume or retry`() {
        for (holdRefused in listOf(true, false)) {
            val a = Member(live()); val b = Member(live())
            if (holdRefused) b.refusePause = true else a.refuseDefault = true
            val pair = NativeMixedPair(a, b, { true }, {})
            assertEquals(NativeSeekOutcome.UNAVAILABLE, pair.catchUp(NativeMixedSide.A).outcome)
            pair.poll(); assertFalse(pair.busy); assertEquals(0, a.plays + b.plays)
            assertEquals(if (holdRefused) 0 else 1, a.defaults)
            assertEquals(0, b.defaults)
        }
    }

    @Test fun `reentrant cancellation during hold dispatch capture or resume cannot resurrect old pair`() {
        for (phase in 0..3) {
            val a = Member(live()); val b = Member(live())
            val pair = NativeMixedPair(a, b, { true }, {})
            if (phase == 0) a.onPause = { pair.detach() }
            if (phase == 1) a.onDefault = { pair.detach() }
            if (phase == 2) a.onDefault = { b.onRead = { b.onRead = {}; pair.pause() } }
            if (phase == 3) a.onPlay = { pair.pause() }
            val result = pair.catchUp(NativeMixedSide.A)
            assertEquals(if (phase < 3) NativeSeekOutcome.UNAVAILABLE else NativeSeekOutcome.REQUESTED, result.outcome)
            pair.poll(); assertFalse(pair.busy)
            assertEquals(0, b.plays)
            assertEquals(if (phase == 3) 1 else 0, a.plays)
        }
    }

    @Test fun `focus callback cannot bypass fresh peer continuity or authority checks`() {
        for (change in 0..2) {
            val a = Member(live()); val b = Member(live(-2_000, origin = 9_000_000))
            var allowed = true
            val pair = NativeMixedPair(a, b, {
                when (change) {
                    0 -> allowed = false
                    1 -> b.value = b.value.copy(bufferedMs = b.value.positionMs)
                    else -> b.value = b.value.copy(windowStartMs = 9_000_101)
                }
                true
            }, {}, requireCurrentLiveWindowOnPlay = true, admission = { allowed })
            pair.catchUp(NativeMixedSide.A); pair.poll()
            assertFalse(pair.busy); assertEquals(0, a.plays + b.plays)
            assertFalse(a.value.playing); assertFalse(b.value.playing)
        }
    }
}
