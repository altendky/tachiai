package net.fstab.tachiai.platform.media

import org.junit.Assert.*
import org.junit.Test

class NativeTimingSettlementTest {
    private class Member : NativePairMember {
        var value = NativeTimingSnapshot(20_000, 60_000, 40_000, null, null, null,
            false, false, true, false, false, false, false, 3, 1f)
        override fun timingSnapshot() = value
        override fun seekToMs(targetMs: Long): NativeSeekPlan {
            value = value.copy(positionMs = targetMs)
            return NativeSeekPlan(NativeSeekOutcome.REQUESTED, targetMs)
        }
        override fun setTimingPlaying(playing: Boolean): Boolean {
            value = value.copy(playing = playing, playWhenReady = playing); return true
        }
        override fun setVolume(volume: Float) = true
        override fun close() {}
    }

    @Test fun `only observed completion publishes a signed relative movement once`() {
        val a = Member(); val b = Member()
        val pair = NativeMixedPair(a, b, { true }, {})
        val previousAttempt = pair.lastTimingAttempt
        pair.shift(NativeMixedSide.B, 1_000)
        assertNotSame(previousAttempt, pair.lastTimingAttempt)
        assertNull(pair.lastTimingSettlement)
        b.value = b.value.copy(state = 2)
        pair.poll(); assertNull(pair.lastTimingSettlement)
        b.value = b.value.copy(state = 3)
        pair.poll()
        assertEquals(NativeTimingSettlement(1, -1_000), pair.lastTimingSettlement)
        pair.poll(); assertEquals(1L, pair.lastTimingSettlement?.revision)
        pair.alignRelative(2_000); pair.poll()
        assertEquals(NativeTimingSettlement(2, 2_000), pair.lastTimingSettlement)
    }

    @Test fun `cancellation timeout and preflight refusal never publish requested history`() {
        for (failure in 0..2) {
            var now = 0L
            val a = Member(); val b = Member()
            val pair = NativeMixedPair(a, b, { true }, {}, { now })
            if (failure == 2) a.value = a.value.copy(playingAd = true)
            pair.shiftRelative(1_000)
            if (failure == 0) pair.pause()
            if (failure == 1) { a.value = a.value.copy(state = 2); now = 8_000 }
            pair.poll()
            assertNull(pair.lastTimingSettlement)
            if (failure == 2) assertNull(pair.lastTimingAttempt)
        }
    }

    @Test fun `reentrant polling during resume cannot publish or resume twice`() {
        val a = Member(); val b = Member()
        a.value = a.value.copy(playing = true, playWhenReady = true)
        b.value = b.value.copy(playing = true, playWhenReady = true)
        var focusCalls = 0
        lateinit var pair: NativeMixedPair
        pair = NativeMixedPair(a, b, { focusCalls++; pair.poll(); true }, {})
        pair.alignRelative(1_000); pair.poll()
        assertEquals(1, focusCalls)
        assertEquals(NativeTimingSettlement(1, 1_000), pair.lastTimingSettlement)
        assertTrue(a.value.playing && b.value.playing)
    }
}
