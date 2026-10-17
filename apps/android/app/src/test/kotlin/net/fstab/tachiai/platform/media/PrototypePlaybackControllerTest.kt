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
        var defaultRequests = 0
        var pauseRequests = 0
        var volume = 0.5f
        var refusePlay = false
        var throwSnapshot = false
        var refusePause = false
        var defaultHook: () -> Unit = {}
        var pauseHook: () -> Unit = {}
        var snapshotHook: () -> Unit = {}
        override fun timingSnapshot(): NativeTimingSnapshot? {
            snapshotHook()
            if (throwSnapshot) throw IllegalStateException()
            return sample
        }
        override fun setTimingPlaying(playing: Boolean): Boolean {
            if (!playing) { pauseRequests++; pauseHook(); if (refusePause) return false }
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
        override fun seekLiveDefault(): NativeSeekPlan {
            defaultRequests++; defaultHook()
            val plan = nativeLiveDefaultPlan(sample)
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

    @Test fun `recovering either feed preserves the survivor and invalidates prior alignment`() {
        for (failedSide in NativeMixedSide.entries) {
            val a = Member(replay)
            val b = Member(replay)
            val controller = PrototypePlaybackController({ true })
            controller.setMember(NativeMixedSide.A, a)
            controller.setMember(NativeMixedSide.B, b)
            assertTrue(controller.play())
            assertTrue(controller.pair!!.requestedAdjustmentValid)
            val survivor = if (failedSide == NativeMixedSide.A) b else a
            val pauses = survivor.pauseRequests
            val seeks = survivor.seekRequests
            controller.setMember(failedSide, null)
            val replacement = Member(replay)
            controller.setMember(failedSide, replacement)
            assertSame(replacement, controller.member(failedSide))
            assertTrue(survivor.sample.playWhenReady)
            assertEquals(pauses, survivor.pauseRequests)
            assertEquals(seeks, survivor.seekRequests)
            assertEquals(0, survivor.closeRequests)
            assertFalse(controller.pair!!.requestedAdjustmentValid)
            assertTrue(controller.play())
            assertTrue(replacement.sample.playWhenReady)
        }
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

    private val live = replay.copy(positionMs = -5_000, defaultPositionMs = 50_000,
        live = true, dynamic = true, seekable = false)

    @Test fun `live position becoming stale after attachment retires only that feed at Play`() {
        for (side in NativeMixedSide.entries) for (alreadyPlaying in listOf(false, true)) {
            val healthy = Member(replay.copy(playing = alreadyPlaying, playWhenReady = alreadyPlaying))
            val stale = Member(live.copy(positionMs = 40_000))
            val controller = PrototypePlaybackController({ true })
            val other = NativeMixedSide.entries.single { it != side }
            controller.setMember(other, healthy)
            controller.setMember(side, stale)
            val oldPair = controller.pair!!
            stale.sample = live
            val retired = mutableListOf<NativeMixedSide>()
            assertTrue(controller.play { selectedSide, selected ->
                assertSame(stale, selected)
                retired.add(selectedSide)
                controller.setMember(selectedSide, null)
                true
            })
            assertEquals(listOf(side), retired)
            assertTrue(healthy.sample.playWhenReady)
            assertEquals(20_000L, healthy.sample.positionMs)
            assertEquals(0, healthy.pauseRequests)
            assertEquals(0, healthy.seekRequests)
            assertEquals(0, healthy.closeRequests)
            assertEquals(0, stale.playRequests)
            assertFalse(oldPair.play())
            assertNull(controller.pair)
            controller.setMember(side, Member(replay))
            assertFalse(controller.pair!!.requestedAdjustmentValid)
        }
    }

    @Test fun `unknown invalid live windows are isolated but valid endpoints and replay are retained`() {
        for (sample in listOf(live, live.copy(positionMs = null), live.copy(durationMs = null),
            live.copy(durationMs = 0), live.copy(positionMs = 60_001), live.copy(live = false, dynamic = true))) {
            val controller = PrototypePlaybackController({ true })
            controller.setMember(NativeMixedSide.A, Member(replay))
            controller.setMember(NativeMixedSide.B, Member(sample))
            assertTrue(controller.play { side, _ -> controller.setMember(side, null); true })
            assertNull(controller.member(NativeMixedSide.B))
        }
        for (sample in listOf(live.copy(positionMs = 0), live.copy(positionMs = 60_000), replay)) {
            val controller = PrototypePlaybackController({ true })
            controller.setMember(NativeMixedSide.A, Member(replay))
            controller.setMember(NativeMixedSide.B, Member(sample))
            assertTrue(controller.play { _, _ -> throw AssertionError("Valid feed retired") })
            assertTrue(controller.pair != null)
        }
    }

    @Test fun `both stale feeds are retired without focus or fabricated playable member`() {
        var focus = 0
        val controller = PrototypePlaybackController({ focus++; true })
        NativeMixedSide.entries.forEach { controller.setMember(it, Member(live)) }
        val retired = mutableListOf<NativeMixedSide>()
        assertFalse(controller.play { side, _ -> retired.add(side); controller.setMember(side, null); true })
        assertEquals(NativeMixedSide.entries, retired)
        assertEquals(0, focus)
        assertFalse(controller.playingRequested)
    }

    @Test fun `owner must detach stale feed and keep survivor identity before fallback play`() {
        for (mutation in 0..4) {
            val healthy = Member(replay)
            val stale = Member(live)
            val replacement = Member(replay)
            val controller = PrototypePlaybackController({ true })
            controller.setMember(NativeMixedSide.A, healthy)
            controller.setMember(NativeMixedSide.B, stale)
            assertFalse(controller.play { side, _ ->
                when (mutation) {
                    0 -> Unit // An owner that did not detach cannot authorize fallback.
                    1 -> controller.setMember(side, replacement)
                    2 -> { controller.setMember(side, null); controller.setMember(NativeMixedSide.A, replacement) }
                    3 -> controller.close()
                    4 -> controller.setMember(side, null)
                }
                mutation != 4 // Cleanup refusal after detach.
            })
            assertEquals(0, healthy.playRequests + stale.playRequests + replacement.playRequests)
        }
    }

    @Test fun `fallback rechecks admission intent and focus after retiring stale partner`() {
        for (denial in 0..3) {
            var admitted = true
            var intent = true
            val healthy = Member(replay)
            val controller = PrototypePlaybackController({
                if (denial == 3) intent = false
                denial != 2
            }, admission = { admitted }, playAllowed = { intent })
            controller.setMember(NativeMixedSide.A, healthy)
            controller.setMember(NativeMixedSide.B, Member(live))
            assertFalse(controller.play { side, _ ->
                controller.setMember(side, null)
                if (denial == 0) admitted = false
                if (denial == 1) intent = false
                true
            })
            assertEquals(0, healthy.playRequests)
        }
    }

    @Test fun `buffering ads and missing snapshots are not mislabeled as stale live`() {
        for (sample in listOf(live.copy(state = 2), live.copy(playingAd = true), live)) {
            val controller = PrototypePlaybackController({ true })
            controller.setMember(NativeMixedSide.A, Member(replay))
            controller.setMember(NativeMixedSide.B, Member(sample).apply { throwSnapshot = sample == live })
            assertFalse(controller.play { _, _ -> throw AssertionError("Not a confirmed ready live window") })
            assertTrue(controller.pair != null)
        }
    }

    @Test fun `stale snapshot cannot retire a replacement attached while sampling`() {
        val controller = PrototypePlaybackController({ true })
        val stale = Member(live)
        val replacement = Member(replay)
        controller.setMember(NativeMixedSide.A, Member(replay))
        controller.setMember(NativeMixedSide.B, stale)
        stale.snapshotHook = {
            stale.snapshotHook = {}
            controller.setMember(NativeMixedSide.B, replacement)
        }
        assertFalse(controller.play { _, _ -> throw AssertionError("Retired a replacement from old snapshot") })
        assertSame(replacement, controller.member(NativeMixedSide.B))
        assertEquals(0, replacement.playRequests)
    }

    @Test fun `focus callback cancellation prevents both single and pair starts`() {
        for (count in 1..2) {
            var intent = true
            val a = Member(replay)
            val b = Member(replay)
            val controller = PrototypePlaybackController({ intent = false; true }, playAllowed = { intent })
            controller.setMember(NativeMixedSide.A, a)
            if (count == 2) controller.setMember(NativeMixedSide.B, b)
            assertFalse(controller.play())
            assertEquals(0, a.playRequests + b.playRequests)
        }
    }

    @Test fun `either surviving live feed uses one default and held settlement with explicit play`() {
        for (side in NativeMixedSide.entries) {
            var focus = 0
            val feed = Member(live)
            val controller = PrototypePlaybackController({ focus++; true })
            controller.setMember(side, feed)
            val status = controller.status
            assertEquals(NativeSeekOutcome.REQUESTED, controller.catchUpPlan(side).outcome)
            assertEquals(status, controller.status); assertEquals(0, feed.defaultRequests); assertEquals(0, feed.pauseRequests)
            val missing = if (side == NativeMixedSide.A) NativeMixedSide.B else NativeMixedSide.A
            assertEquals(NativeSeekOutcome.UNAVAILABLE, controller.catchUpPlan(missing).outcome)
            assertEquals(NativeSeekOutcome.REQUESTED, controller.catchUp(side).outcome)
            assertTrue(controller.busy); assertEquals(1, feed.defaultRequests); assertEquals(0, feed.seekRequests)
            assertEquals(NativeSeekOutcome.UNAVAILABLE, controller.catchUp(side).outcome)
            controller.poll()
            assertFalse(controller.busy); assertFalse(feed.sample.playWhenReady); assertEquals(0, focus)
            assertTrue(controller.status.contains("Explicit Play required"))
            assertEquals(NativeSeekOutcome.NO_CHANGE, controller.catchUpPlan(side).outcome)
            assertTrue(controller.play()); assertEquals(1, focus)
            controller.close(); assertEquals(0, feed.closeRequests)
            assertEquals(NativeSeekOutcome.UNAVAILABLE, controller.catchUpPlan(side).outcome)
        }
    }

    @Test fun `single recovery refuses replay ads unavailable default and failed hold without seeking`() {
        for (sample in listOf(replay, live.copy(playingAd = true), live.copy(state = 2),
            live.copy(canSeekDefault = false), live.copy(defaultPositionMs = null),
            live.copy(defaultPositionMs = 70_000), live.copy(durationMs = 0))) {
            val feed = Member(sample)
            val controller = PrototypePlaybackController({ true })
            controller.setMember(NativeMixedSide.B, feed)
            val plan = controller.catchUpPlan(NativeMixedSide.B)
            assertTrue(plan.outcome != NativeSeekOutcome.REQUESTED)
            assertEquals(plan, controller.catchUp(NativeMixedSide.B))
            assertEquals(0, feed.defaultRequests); assertEquals(0, feed.pauseRequests); assertFalse(controller.busy)
        }
        val feed = Member(live).apply { refusePause = true }
        val controller = PrototypePlaybackController({ true })
        controller.setMember(NativeMixedSide.A, feed)
        assertEquals(NativeSeekOutcome.UNAVAILABLE, controller.catchUp(NativeMixedSide.A).outcome)
        assertEquals(0, feed.defaultRequests); assertTrue(controller.status.contains("Pause refused"))
    }

    @Test fun `single ownership is checked at click after hold and while settling`() {
        for (phase in 0..2) {
            var admitted = true
            val feed = Member(live)
            val controller = PrototypePlaybackController({ true }, admission = { admitted })
            controller.setMember(NativeMixedSide.A, feed)
            assertEquals(NativeSeekOutcome.REQUESTED, controller.catchUpPlan(NativeMixedSide.A).outcome)
            if (phase == 0) admitted = false
            if (phase == 1) feed.pauseHook = { admitted = false }
            val result = controller.catchUp(NativeMixedSide.A)
            assertEquals(if (phase == 2) NativeSeekOutcome.REQUESTED else NativeSeekOutcome.UNAVAILABLE, result.outcome)
            if (phase == 2) { admitted = false; controller.poll(); assertTrue(controller.status.contains("authorization lost")) }
            assertFalse(controller.busy); assertEquals(if (phase == 2) 1 else 0, feed.defaultRequests)
            assertFalse(feed.sample.playWhenReady); assertEquals(0, feed.closeRequests)
        }
    }

    @Test fun `single pending recovery is cancelled by pause focus removal replacement or close`() {
        for (cancel in 0..4) {
            val feed = Member(live)
            val replacement = Member(live.copy(playing = true, playWhenReady = true))
            val controller = PrototypePlaybackController({ true })
            controller.setMember(NativeMixedSide.B, feed)
            controller.catchUp(NativeMixedSide.B)
            when (cancel) {
                0 -> controller.pause()
                1 -> controller.focusLost()
                2 -> controller.setMember(NativeMixedSide.B, null)
                3 -> controller.setMember(NativeMixedSide.B, replacement)
                else -> controller.close()
            }
            val status = controller.status
            controller.poll()
            assertFalse(controller.busy); assertEquals(status, controller.status)
            assertEquals(1, feed.defaultRequests); assertEquals(0, replacement.defaultRequests)
            assertEquals(0, feed.closeRequests + replacement.closeRequests)
            if (cancel == 3) assertTrue(replacement.sample.playWhenReady)
        }
    }

    @Test fun `single hold or default reentrant replacement cannot seek or pause new participant`() {
        for (duringHold in listOf(true, false)) {
            val feed = Member(live)
            val replacement = Member(live.copy(playing = true, playWhenReady = true))
            val controller = PrototypePlaybackController({ true })
            controller.setMember(NativeMixedSide.A, feed)
            val replace = {
                feed.pauseHook = {}
                controller.setMember(NativeMixedSide.A, replacement)
            }
            if (duringHold) feed.pauseHook = replace else feed.defaultHook = replace
            assertEquals(NativeSeekOutcome.UNAVAILABLE, controller.catchUp(NativeMixedSide.A).outcome)
            assertFalse(controller.busy); assertSame(replacement, controller.member(NativeMixedSide.A))
            assertEquals(if (duringHold) 0 else 1, feed.defaultRequests)
            assertEquals(0, replacement.defaultRequests); assertEquals(0, replacement.pauseRequests)
            assertTrue(replacement.sample.playWhenReady)
            controller.poll(); assertFalse(controller.busy)
        }
    }

    @Test fun `pair removal exposes survivor recovery without restarting or closing either session`() {
        val a = Member(live); val b = Member(live)
        val controller = PrototypePlaybackController({ true })
        controller.setMember(NativeMixedSide.A, a); controller.setMember(NativeMixedSide.B, b)
        val oldPair = controller.pair!!
        assertEquals(NativeSeekOutcome.REQUESTED, controller.catchUp(NativeMixedSide.A).outcome)
        controller.setMember(NativeMixedSide.B, null)
        oldPair.poll(); controller.poll()
        assertFalse(controller.busy); assertNull(controller.pair)
        a.sample = live
        assertEquals(NativeSeekOutcome.REQUESTED, controller.catchUpPlan(NativeMixedSide.A).outcome)
        assertEquals(NativeSeekOutcome.REQUESTED, controller.catchUp(NativeMixedSide.A).outcome)
        controller.poll(); assertFalse(controller.busy)
        assertEquals(2, a.defaultRequests); assertEquals(0, b.defaultRequests)
        assertEquals(0, a.closeRequests + b.closeRequests)
    }

    @Test fun `single unsettled recovery expires at original eight seconds without playing`() {
        for (end in listOf(9_000L, 999L)) {
            var now = 1_000L
            val feed = Member(live)
            val controller = PrototypePlaybackController({ true }, { now })
            controller.setMember(NativeMixedSide.A, feed)
            controller.catchUp(NativeMixedSide.A)
            feed.sample = feed.sample.copy(positionMs = -1)
            controller.poll(); assertTrue(controller.busy)
            now = end
            controller.poll(); assertFalse(controller.busy)
            assertTrue(controller.status.contains("timed out")); assertFalse(feed.sample.playWhenReady)
            assertEquals(1, feed.defaultRequests); assertEquals(0, feed.playRequests)
        }
    }

    @Test fun `old pair cancellation during hold or default cannot pause survivor transferred to playing new pair`() {
        for (duringHold in listOf(true, false)) {
            val a = Member(live.copy(positionMs = 20_000))
            val b = Member(live.copy(positionMs = 20_000))
            val replacement = Member(live.copy(positionMs = 20_000))
            val controller = PrototypePlaybackController({ true })
            controller.setMember(NativeMixedSide.A, a); controller.setMember(NativeMixedSide.B, b)
            val oldPair = controller.pair!!
            var survivorPausesAtTransfer = -1
            val replace = {
                a.pauseHook = {}
                controller.setMember(NativeMixedSide.A, replacement)
                assertTrue(controller.play())
                survivorPausesAtTransfer = b.pauseRequests
            }
            if (duringHold) a.pauseHook = replace else a.defaultHook = replace
            assertEquals(NativeSeekOutcome.UNAVAILABLE, controller.catchUp(NativeMixedSide.A).outcome)
            assertFalse(oldPair.busy); assertFalse(controller.busy)
            assertSame(replacement, controller.member(NativeMixedSide.A)); assertSame(b, controller.member(NativeMixedSide.B))
            assertTrue(replacement.sample.playWhenReady); assertTrue(b.sample.playWhenReady)
            assertEquals(survivorPausesAtTransfer, b.pauseRequests)
            assertEquals(if (duringHold) 0 else 1, a.defaultRequests)
            assertEquals(0, b.defaultRequests + replacement.defaultRequests)
            oldPair.poll(); controller.poll()
            assertTrue(replacement.sample.playWhenReady); assertTrue(b.sample.playWhenReady)
            assertEquals(0, a.closeRequests + b.closeRequests + replacement.closeRequests)
        }
    }
}
