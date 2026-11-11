package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.*
import org.junit.Assert.*
import org.junit.Test

class FeedRetryPlaybackIntegrationTest {
    private class Member(playing: Boolean) : NativePairMember {
        var value = NativeTimingSnapshot(20_000, 60_000, 40_000, null, null, null,
            false, false, true, false, false, playing, playing, 3, 1f)
        var closes = 0
        val transport = mutableListOf<Boolean>()
        var refusePlay = false
        var playHook: () -> Unit = {}
        override fun timingSnapshot() = value
        override fun seekToMs(targetMs: Long) = NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        override fun setTimingPlaying(playing: Boolean): Boolean {
            transport += playing
            if (playing && refusePlay) return false
            value = value.copy(playing = playing, playWhenReady = playing)
            if (playing) playHook()
            return true
        }
        override fun setVolume(volume: Float) = true
        override fun close() { closes++ }
    }

    @Test fun `retried feed rejoins still playing sibling after native timing cleared Activity play intent`() {
        for (side in NativeMixedSide.entries) {
            var desired = false // Native timing has already resumed its members.
            val a = Member(true); val b = Member(true)
            val controller = PrototypePlaybackController({ true }, playAllowed = { desired })
            controller.setMember(NativeMixedSide.A, a); controller.setMember(NativeMixedSide.B, b)
            val siblingSide = if (side == NativeMixedSide.A) NativeMixedSide.B else NativeMixedSide.A
            controller.setMember(side, null)
            val sibling = checkNotNull(controller.member(siblingSide)) as Member
            assertTrue(sibling.timingSnapshot()!!.playWhenReady)
            sibling.value = sibling.value.copy(live = true, dynamic = true,
                positionMs = -20_000, bufferedMs = -2_000, windowStartMs = 1_000_000)
            val intent = FeedRetryPlaybackIntent(); val token = Any()
            val receipt = intent.arm(side.ordinal, token, sibling, desired || sibling.timingSnapshot()!!.playWhenReady)
            val replacement = Member(false)
            controller.setMember(side, replacement)
            assertFalse(replacement.value.playWhenReady)
            assertFalse(controller.pair!!.requestedAdjustmentValid)
            if (intent.consume(receipt, token, controller.member(siblingSide))) desired = true
            assertTrue(controller.rejoin(side, replacement, sibling))
            assertTrue(replacement.value.playWhenReady)
            assertTrue(sibling.timingSnapshot()!!.playWhenReady)
            assertEquals(0, a.closes + b.closes + replacement.closes)
            assertTrue(sibling.transport.isEmpty())
            assertEquals(listOf(true), replacement.transport)
        }
    }

    @Test fun `explicit Pause during retry cannot be undone by replacement readiness`() {
        val sibling = Member(true); val replacement = Member(false)
        var desired = false
        val controller = PrototypePlaybackController({ true }, playAllowed = { desired })
        controller.setMember(NativeMixedSide.B, sibling)
        val intent = FeedRetryPlaybackIntent(); val token = Any()
        val receipt = intent.arm(0, token, sibling, true)
        intent.cancelAll(); controller.pause()
        controller.setMember(NativeMixedSide.A, replacement)
        if (intent.consume(receipt, token, controller.member(NativeMixedSide.B))) desired = true
        assertFalse(desired); assertFalse(controller.play())
        assertFalse(replacement.value.playWhenReady || sibling.value.playWhenReady)
    }

    @Test fun `retry receipt never authorizes a replaced sibling or bypasses current admission`() {
        for (replaceSibling in listOf(false, true)) {
            var desired = false; var admitted = true
            val sibling = Member(true); val replacement = Member(false)
            val controller = PrototypePlaybackController({ true }, admission = { admitted }, playAllowed = { desired })
            controller.setMember(NativeMixedSide.B, sibling)
            val intent = FeedRetryPlaybackIntent(); val token = Any()
            val receipt = intent.arm(0, token, sibling, true)
            if (replaceSibling) controller.setMember(NativeMixedSide.B, Member(true)) else admitted = false
            controller.setMember(NativeMixedSide.A, replacement)
            if (intent.consume(receipt, token, controller.member(NativeMixedSide.B))) desired = true
            assertFalse(controller.play())
            assertFalse(replacement.value.playWhenReady)
        }
    }

    @Test fun `invalid target sibling permission or focus never starts or pauses the healthy peer`() {
        for (side in NativeMixedSide.entries) for (invalid in 0..6) {
            val target = Member(false)
            target.value = target.value.copy(live = true, dynamic = true, windowStartMs = 1_000_000)
            val sibling = Member(true)
            var allowed = true; var admitted = true
            val controller = PrototypePlaybackController({ invalid != 6 }, admission = { admitted }, playAllowed = { allowed })
            controller.setMember(side, target)
            val other = if (side == NativeMixedSide.A) NativeMixedSide.B else NativeMixedSide.A
            controller.setMember(other, sibling)
            when (invalid) {
                0 -> target.value = target.value.copy(positionMs = -1)
                1 -> target.value = target.value.copy(state = 2)
                2 -> target.value = target.value.copy(playingAd = true)
                3 -> sibling.value = sibling.value.copy(playing = false)
                4 -> allowed = false
                5 -> admitted = false
            }
            assertFalse(controller.rejoin(side, target, sibling))
            assertTrue(sibling.transport.isEmpty()); assertTrue(target.transport.isEmpty())
        }
    }

    @Test fun `ownership and snapshots are rechecked after focus without re-commanding sibling`() {
        for (side in NativeMixedSide.entries) for (invalid in 0..3) {
            val target = Member(false); val sibling = Member(true)
            var admitted = true
            lateinit var controller: PrototypePlaybackController
            controller = PrototypePlaybackController({
                when (invalid) {
                    0 -> admitted = false
                    1 -> target.value = target.value.copy(playingAd = true)
                    2 -> sibling.value = sibling.value.copy(playWhenReady = false)
                    else -> controller.setMember(side, Member(false))
                }
                true
            }, admission = { admitted })
            controller.setMember(side, target)
            controller.setMember(if (side == NativeMixedSide.A) NativeMixedSide.B else NativeMixedSide.A, sibling)
            assertFalse(controller.rejoin(side, target, sibling))
            assertTrue(sibling.transport.isEmpty())
            assertFalse(target.transport.contains(true))
        }
    }

    @Test fun `target refusal and post-play admission loss roll back only the current replacement`() {
        for (side in NativeMixedSide.entries) for (loseAdmission in listOf(false, true)) {
            val target = Member(false); val sibling = Member(true)
            var admitted = true
            val controller = PrototypePlaybackController({ true }, admission = { admitted })
            controller.setMember(side, target)
            controller.setMember(if (side == NativeMixedSide.A) NativeMixedSide.B else NativeMixedSide.A, sibling)
            if (loseAdmission) target.playHook = { admitted = false } else target.refusePlay = true
            assertFalse(controller.rejoin(side, target, sibling))
            assertEquals(listOf(true, false), target.transport)
            assertTrue(sibling.transport.isEmpty()); assertTrue(sibling.value.playing && sibling.value.playWhenReady)
            assertFalse(target.value.playing || target.value.playWhenReady)
        }
    }

    @Test fun `target replaced during Play receives no stale rollback command`() {
        for (side in NativeMixedSide.entries) {
            val target = Member(false); val sibling = Member(true); val newer = Member(false)
            val controller = PrototypePlaybackController({ true })
            controller.setMember(side, target)
            controller.setMember(if (side == NativeMixedSide.A) NativeMixedSide.B else NativeMixedSide.A, sibling)
            target.playHook = { controller.setMember(side, newer) }
            assertFalse(controller.rejoin(side, target, sibling))
            // setMember itself pauses the retired member once. rejoin does not
            // issue a second stale command or touch either current participant.
            assertEquals(listOf(true, false), target.transport)
            assertTrue(newer.transport.isEmpty()); assertTrue(sibling.transport.isEmpty())
        }
    }

    @Test fun `newer explicit Play on the same SDK supersedes rejoin without stale rollback`() {
        for (side in NativeMixedSide.entries) {
            val target = Member(false); val sibling = Member(true)
            val controller = PrototypePlaybackController({ true })
            controller.setMember(side, target)
            controller.setMember(if (side == NativeMixedSide.A) NativeMixedSide.B else NativeMixedSide.A, sibling)
            var newerPlayAccepted = false
            target.playHook = {
                target.playHook = {} // The new Play is a separate one-shot user command.
                newerPlayAccepted = controller.play()
            }
            assertFalse("The superseded rejoin cannot claim completion", controller.rejoin(side, target, sibling))
            assertTrue(newerPlayAccepted)
            assertEquals(listOf(true, true), target.transport)
            assertEquals("Only the newer explicit Play commands the sibling", listOf(true), sibling.transport)
            assertTrue(target.value.playing && target.value.playWhenReady)
            assertTrue(sibling.value.playing && sibling.value.playWhenReady)
        }
    }
}
