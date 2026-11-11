package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.*
import org.junit.Assert.*
import org.junit.Test

class PairOffsetPlaybackTest {
    private class Member : NativePairMember {
        var value = NativeTimingSnapshot(40_000, 120_000, 100_000, null, null, null,
            false, false, true, false, false, true, true, 3, 1f)
        var seeks = 0
        override fun timingSnapshot() = value
        override fun seekToMs(targetMs: Long): NativeSeekPlan {
            seeks++; value = value.copy(positionMs = targetMs)
            return NativeSeekPlan(NativeSeekOutcome.REQUESTED, targetMs)
        }
        override fun setTimingPlaying(playing: Boolean): Boolean {
            value = value.copy(playing = playing, playWhenReady = playing); return true
        }
        override fun setVolume(volume: Float) = true
        override fun close() {}
    }
    private class Fixture(preference: PairOffsetPreference?) {
        val a = Member(); val b = Member()
        val pair = NativeMixedPair(a, b, { true }, {})
        val session = PairOffsetSession(preference).apply { bind(pair) }
        fun dispatch(command: PairOffsetCommand) {
            val before = pair.lastTimingAttempt
            val result = pair.alignRelative(command.deltaMs)
            session.dispatched(command, result.outcome, pair.lastTimingAttempt !== before &&
                result.outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE))
        }
        fun poll() {
            pair.poll()
            val settled = pair.lastTimingSettlement
            session.observe(pair, settled?.revision ?: 0, settled?.deltaMs ?: 0, pair.busy)
        }
        fun revision() = pair.lastTimingSettlement?.revision ?: 0
    }

    @Test fun `saved correction applies once and can be removed reapplied and updated by settled timing`() {
        val f = Fixture(PairOffsetPreference(25_000, true))
        f.dispatch(checkNotNull(f.session.planStartup(f.pair, 0)))
        assertEquals(0L, f.session.state.appliedOffsetMs)
        assertNull(f.session.pendingSave)
        f.poll()
        assertEquals(25_000L, f.session.state.appliedOffsetMs)
        assertNull(f.session.pendingSave)
        assertTrue(f.a.value.playing && f.b.value.playing)
        assertNull(f.session.planStartup(f.pair, f.revision()))

        f.dispatch(checkNotNull(f.session.planToggle(f.pair, false, f.revision())))
        f.poll()
        assertEquals(0L, f.session.state.appliedOffsetMs)
        assertEquals(25_000L, f.session.pendingSave?.preference?.offsetMs)
        assertFalse(checkNotNull(f.session.pendingSave).preference.enabled)
        f.session.saveCompleted(checkNotNull(f.session.pendingSave).id, true)
        f.dispatch(checkNotNull(f.session.planToggle(f.pair, true, f.revision())))
        f.poll()
        assertEquals(25_000L, f.session.state.appliedOffsetMs)
        f.session.saveCompleted(checkNotNull(f.session.pendingSave).id, true)

        val manual = checkNotNull(f.session.planManual(f.pair, -5_000, f.revision()))
        f.dispatch(manual); f.poll()
        assertEquals(PairOffsetPreference(20_000, true), f.session.pendingSave?.preference)
        val reopened = Fixture(checkNotNull(f.session.pendingSave).preference)
        reopened.dispatch(checkNotNull(reopened.session.planStartup(reopened.pair, 0)))
        reopened.poll()
        assertEquals(20_000L, reopened.session.state.appliedOffsetMs)
    }

    @Test fun `refused automatic correction neither changes footage nor deletes the saved preference`() {
        val preference = PairOffsetPreference(240_000, true)
        val f = Fixture(preference)
        f.dispatch(checkNotNull(f.session.planStartup(f.pair, 0)))
        f.poll()
        assertEquals(0, f.a.seeks + f.b.seeks)
        assertEquals(0L, f.session.state.appliedOffsetMs)
        assertEquals(preference, f.session.state.preference)
        assertNull(f.session.pendingSave)
        assertNull(f.session.planStartup(f.pair, 0))
    }

    @Test fun `canceled accepted movement cannot replace a previously saved correction`() {
        val preference = PairOffsetPreference(25_000, true)
        val f = Fixture(preference)
        val command = checkNotNull(f.session.planManual(f.pair, 5_000, 0))
        f.dispatch(command)
        assertEquals(5_000L, f.pair.requestedAdjustmentMs)
        f.pair.pause(); f.session.cancelPending(f.pair); f.poll()
        assertNull(f.pair.lastTimingSettlement)
        assertNull(f.session.state.appliedOffsetMs)
        assertEquals(preference, f.session.state.preference)
        assertNull(f.session.pendingSave)
    }
}
