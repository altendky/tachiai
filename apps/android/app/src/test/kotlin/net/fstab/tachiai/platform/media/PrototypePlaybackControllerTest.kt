package net.fstab.tachiai.platform.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypePlaybackControllerTest {
    private val replay = NativeTimingSnapshot(20_000, 60_000, 30_000, null, null, null,
        false, false, true, true, false, false, false, 3, 1f)
    private class Member(var sample: NativeTimingSnapshot) : NativePairMember {
        var playRequests = 0
        var seekRequests = 0
        var closeRequests = 0
        var volume = 0.5f
        var refusePlay = false
        var throwSnapshot = false
        override fun timingSnapshot(): NativeTimingSnapshot? {
            if (throwSnapshot) throw IllegalStateException()
            return sample
        }
        override fun setTimingPlaying(playing: Boolean): Boolean {
            if (playing) { playRequests++; if (refusePlay) return false }
            sample = sample.copy(playing = playing, playWhenReady = playing)
            return true
        }
        override fun seekToMs(targetMs: Long): NativeSeekPlan {
            seekRequests++
            val plan = nativeSeekPlan(sample, targetMs)
            plan.targetMs?.let { sample = sample.copy(positionMs = it) }
            return plan
        }
        override fun setVolume(volume: Float): Boolean { this.volume = volume; return true }
        override fun close() { closeRequests++ }
    }

    @Test fun `empty slots remain inert and do not acquire focus`() {
        var focusRequests = 0
        val controller = PrototypePlaybackController({ focusRequests++; true })
        assertFalse(controller.play())
        assertTrue(controller.pause())
        assertFalse(controller.playingRequested)
        assertEquals(NativeSeekOutcome.UNAVAILABLE, controller.shiftRelative(1_000).outcome)
        assertEquals(NativeSeekOutcome.UNAVAILABLE, controller.catchUp(NativeMixedSide.A).outcome)
        assertEquals(0, focusRequests)
        assertNull(controller.pair)
    }

    @Test fun `either single slot can play and pause without inventing the other feed`() {
        for (side in NativeMixedSide.entries) {
            val feed = Member(replay)
            val controller = PrototypePlaybackController({ true })
            controller.setMember(side, feed)
            assertSame(feed, controller.member(side))
            assertNull(controller.pair)
            assertTrue(controller.play())
            assertTrue(feed.sample.playWhenReady)
            assertTrue(controller.playingRequested)
            assertTrue(controller.pause())
            assertFalse(feed.sample.playWhenReady)
            assertEquals(NativeSeekOutcome.UNAVAILABLE, controller.shiftRelative(1_000).outcome)
        }
    }

    @Test fun `two members retain pair transactions and removal preserves survivor playing`() {
        for (failedSide in NativeMixedSide.entries) {
            val a = Member(replay)
            val b = Member(replay)
            val controller = PrototypePlaybackController({ true })
            controller.setMember(NativeMixedSide.A, a)
            controller.setMember(NativeMixedSide.B, b)
            assertTrue(controller.pair != null)
            assertTrue(controller.play())
            val survivor = if (failedSide == NativeMixedSide.A) b else a
            val failed = if (failedSide == NativeMixedSide.A) a else b
            controller.setMember(failedSide, null)
            assertTrue(survivor.sample.playWhenReady)
            assertFalse(failed.sample.playWhenReady)
            assertNull(controller.pair)
            assertEquals(0, survivor.closeRequests)
            assertEquals(0, failed.closeRequests)
            controller.close()
            assertFalse(survivor.sample.playWhenReady)
            assertEquals(0, survivor.closeRequests)
        }
    }

    @Test fun `removing a member cancels pending restore including stale pair polls`() {
        val a = Member(replay)
        val b = Member(replay)
        val controller = PrototypePlaybackController({ true })
        controller.setMember(NativeMixedSide.A, a)
        controller.setMember(NativeMixedSide.B, b)
        assertTrue(controller.play())
        assertEquals(NativeSeekOutcome.REQUESTED, controller.shiftRelative(1_000).outcome)
        assertTrue(controller.busy)
        val stalePair = controller.pair!!
        controller.setMember(NativeMixedSide.B, null)
        stalePair.poll()
        controller.poll()
        assertFalse(controller.busy)
        assertFalse(a.sample.playWhenReady)
        assertFalse(b.sample.playWhenReady)
        assertFalse(stalePair.play())
        assertEquals(1, a.playRequests)
    }

    @Test fun `changing the other slot never restarts a feed or ends borrowed lifetimes`() {
        val a = Member(replay)
        val b = Member(replay)
        val replacement = Member(replay)
        val controller = PrototypePlaybackController({ true })
        controller.setMember(NativeMixedSide.A, a)
        assertTrue(controller.play())
        controller.setMember(NativeMixedSide.B, b)
        assertTrue(a.sample.playWhenReady)
        assertFalse(b.sample.playWhenReady)
        controller.setMember(NativeMixedSide.B, replacement)
        assertTrue(a.sample.playWhenReady)
        assertFalse(replacement.sample.playWhenReady)
        assertEquals(1, a.playRequests)
        controller.close()
        assertFalse(a.sample.playWhenReady)
        assertEquals(0, a.closeRequests + b.closeRequests + replacement.closeRequests)
        controller.setMember(NativeMixedSide.A, a)
        assertNull(controller.member(NativeMixedSide.A))
        assertFalse(controller.play())
    }

    @Test fun `single play enforces readiness ad and live window before focus`() {
        val refused = listOf(
            replay.copy(state = 2), replay.copy(playingAd = true),
            replay.copy(live = true, positionMs = null),
            replay.copy(dynamic = true, durationMs = null),
            replay.copy(live = true, durationMs = 0),
            replay.copy(live = true, positionMs = -1),
            replay.copy(live = true, positionMs = 60_001),
        )
        for (sample in refused) {
            var focusRequests = 0
            val feed = Member(sample)
            val controller = PrototypePlaybackController({ focusRequests++; true })
            controller.setMember(NativeMixedSide.A, feed)
            assertFalse(controller.play())
            assertEquals(0, focusRequests)
            assertEquals(0, feed.playRequests)
        }
        val feed = Member(replay.copy(live = true))
        val controller = PrototypePlaybackController({ true })
        controller.setMember(NativeMixedSide.A, feed)
        assertTrue(controller.play())
        controller.pause()
        feed.throwSnapshot = true
        assertFalse(controller.play())
    }

    @Test fun `focus denied and lost pause single and pair without automatic restart`() {
        for (count in 1..2) {
            var granted = false
            val a = Member(replay)
            val b = Member(replay)
            val controller = PrototypePlaybackController({ granted })
            controller.setMember(NativeMixedSide.A, a)
            if (count == 2) controller.setMember(NativeMixedSide.B, b)
            assertFalse(controller.play())
            assertFalse(a.sample.playWhenReady)
            assertFalse(b.sample.playWhenReady)
            granted = true
            assertTrue(controller.play())
            controller.focusLost()
            assertFalse(controller.playingRequested)
            controller.poll()
            assertFalse(controller.playingRequested)
            assertTrue(controller.status.contains("explicit Play"))
        }
    }

    @Test fun `missing volume is successful while invalid values and play refusal remain guarded`() {
        val feed = Member(replay)
        val controller = PrototypePlaybackController({ true })
        controller.setMember(NativeMixedSide.A, feed)
        assertTrue(controller.setVolume(NativeMixedSide.B, 0.25f))
        assertTrue(controller.setVolume(NativeMixedSide.A, 0.75f))
        assertEquals(0.75f, feed.volume)
        assertFalse(controller.setVolume(NativeMixedSide.B, Float.NaN))
        assertFalse(controller.setVolume(NativeMixedSide.A, -0.1f))
        feed.refusePlay = true
        assertFalse(controller.play())
        assertFalse(controller.playingRequested)
        controller.close()
        assertFalse(controller.setVolume(NativeMixedSide.B, 0.25f))
    }
}
