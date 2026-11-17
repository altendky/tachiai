package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticFeedRecoveryIntegrationTest {
    private class Member(playing: Boolean = false) : NativePairMember {
        var value = NativeTimingSnapshot(20_000, 60_000, 40_000, null, null, null,
            false, false, true, true, false, playing, playing, 3, 1f)
        val transport = mutableListOf<Boolean>()
        var seeks = 0
        override fun timingSnapshot() = value
        override fun setTimingPlaying(playing: Boolean): Boolean {
            transport += playing
            value = value.copy(playing = playing, playWhenReady = playing)
            return true
        }
        override fun seekToMs(targetMs: Long): NativeSeekPlan {
            seeks++
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        }
        override fun setVolume(volume: Float) = true
        override fun close() = Unit
    }
    private fun other(side: NativeMixedSide) = NativeMixedSide.entries[1 - side.ordinal]

    @Test fun bothAbsentRecoverInEitherOrderAndSecondRejoinsWithoutRecommendingFirst() {
        for (firstSide in NativeMixedSide.entries) {
            var desired = false
            val controller = PrototypePlaybackController({ true }, playAllowed = { desired })
            val intent = FeedRetryPlaybackIntent()
            val tokens = List(2) { Any() }
            val receipts = NativeMixedSide.entries.map { side ->
                intent.arm(side.ordinal, tokens[side.ordinal], null, true, allowSiblingReplacement = true)
            }
            val first = Member(); val second = Member(); val secondSide = other(firstSide)
            controller.setMember(firstSide, first)
            if (intent.consume(receipts[firstSide.ordinal], tokens[firstSide.ordinal], null)) desired = true
            assertTrue(controller.play())
            assertEquals(listOf(true), first.transport)
            // Already-buffered healthy footage must remain untouched, even
            // when its position has aged outside the current live window.
            first.value = first.value.copy(live = true, dynamic = true, positionMs = -10_000,
                bufferedMs = -1_000, windowStartMs = 1_000_000)
            controller.setMember(secondSide, second)
            assertTrue(intent.consume(receipts[secondSide.ordinal], tokens[secondSide.ordinal], controller.member(firstSide)))
            assertTrue(controller.rejoin(secondSide, second, first))
            assertEquals(listOf(true), first.transport)
            assertEquals(listOf(true), second.transport)
            assertEquals(0, first.seeks + second.seeks)
            assertTrue(first.value.playing && second.value.playing)
            assertFalse(intent.consume(receipts[secondSide.ordinal], tokens[secondSide.ordinal], first))
            controller.close()
        }
    }

    @Test fun bothReadyBeforeEitherStartsUseOrdinaryJointPlayInEitherPreparationOrder() {
        for (firstSide in NativeMixedSide.entries) {
            var desired = false
            val controller = PrototypePlaybackController({ true }, playAllowed = { desired })
            val intent = FeedRetryPlaybackIntent(); val aToken = Any(); val bToken = Any()
            val aReceipt = intent.arm(0, aToken, null, true, allowSiblingReplacement = true)
            val bReceipt = intent.arm(1, bToken, null, true, allowSiblingReplacement = true)
            val a = Member(); val b = Member()
            controller.setMember(firstSide, if (firstSide == NativeMixedSide.A) a else b)
            controller.setMember(other(firstSide), if (firstSide == NativeMixedSide.A) b else a)
            assertTrue(intent.consume(aReceipt, aToken, controller.member(NativeMixedSide.B)))
            assertTrue(intent.consume(bReceipt, bToken, controller.member(NativeMixedSide.A)))
            desired = true
            assertTrue(controller.play())
            assertEquals(listOf(true), a.transport); assertEquals(listOf(true), b.transport)
            assertEquals(0, a.seeks + b.seeks)
            controller.close()
        }
    }

    @Test fun explicitPauseCancelsBothAutomaticCompletionsAndNewerReceiptsRejectOldAttempts() {
        var desired = true
        val controller = PrototypePlaybackController({ true }, playAllowed = { desired })
        val intent = FeedRetryPlaybackIntent(); val tokens = List(2) { Any() }
        val old = (0..1).map { intent.arm(it, tokens[it], null, true, allowSiblingReplacement = true) }
        desired = false; intent.cancelAll(); controller.pause()
        val a = Member(); val b = Member()
        controller.setMember(NativeMixedSide.A, a); controller.setMember(NativeMixedSide.B, b)
        assertFalse(intent.consume(old[0], tokens[0], b)); assertFalse(intent.consume(old[1], tokens[1], a))
        assertFalse(controller.play())
        assertFalse(a.transport.contains(true) || b.transport.contains(true))
        val fresh = intent.arm(0, tokens[0], b, true, allowSiblingReplacement = true)
        assertFalse(intent.consume(old[0], tokens[0], b))
        assertTrue(intent.consume(fresh, tokens[0], b))
        controller.close()
    }

    @Test fun onlyAutomaticIntentCanAdoptASiblingPreparedAfterTheRequest() {
        for (automatic in listOf(false, true)) for (side in NativeMixedSide.entries) {
            var desired = false
            val controller = PrototypePlaybackController({ true }, playAllowed = { desired })
            val intent = FeedRetryPlaybackIntent(); val token = Any()
            val receipt = intent.arm(side.ordinal, token, null, true, allowSiblingReplacement = automatic)
            val healthy = Member(true); val target = Member()
            controller.setMember(other(side), healthy); controller.setMember(side, target)
            val consumed = intent.consume(receipt, token, controller.member(other(side)))
            assertEquals(automatic, consumed)
            if (consumed) { desired = true; assertTrue(controller.rejoin(side, target, healthy)) }
            assertEquals(if (automatic) listOf(true) else emptyList<Boolean>(), target.transport)
            assertTrue(healthy.transport.isEmpty())
            controller.close()
        }
    }

    @Test fun automaticSiblingFlexibilityStillRequiresExactAttemptTokenAndExplicitResumeIntent() {
        val intent = FeedRetryPlaybackIntent(); val token = Any(); val healthy = Member(true)
        val wrongToken = intent.arm(0, token, null, true, allowSiblingReplacement = true)
        assertFalse(intent.consume(wrongToken, Any(), healthy))
        assertFalse(intent.consume(wrongToken, token, healthy))
        val noResume = intent.arm(1, token, null, false, allowSiblingReplacement = true)
        assertFalse(intent.consume(noResume, token, healthy))
        val superseded = intent.arm(0, token, null, true, allowSiblingReplacement = true)
        val newer = intent.arm(0, token, null, true, allowSiblingReplacement = true)
        assertFalse(intent.consume(superseded, token, healthy))
        assertTrue(intent.consume(newer, token, healthy))
    }

    @Test fun consumedAutomaticIntentCannotBypassCurrentAdmissionFocusOrMemberOwnership() {
        for (side in NativeMixedSide.entries) for (refusal in 0..3) {
            var desired = false; var admitted = true
            val controller = PrototypePlaybackController({ refusal != 1 },
                admission = { admitted }, playAllowed = { desired })
            val intent = FeedRetryPlaybackIntent(); val token = Any()
            val receipt = intent.arm(side.ordinal, token, null, true, allowSiblingReplacement = true)
            val healthy = Member(true); val target = Member()
            controller.setMember(other(side), healthy); controller.setMember(side, target)
            assertTrue(intent.consume(receipt, token, healthy))
            desired = true
            when (refusal) { 0 -> admitted = false; 2 -> desired = false }
            val allegedTarget = if (refusal == 3) Member() else target
            assertFalse(controller.rejoin(side, allegedTarget, healthy))
            assertTrue(healthy.transport.isEmpty()); assertTrue(target.transport.isEmpty())
            controller.close()
        }
    }

    @Test fun admissionOrSiblingChangedDuringFocusCannotBeInheritedFromAutomaticReceipt() {
        for (side in NativeMixedSide.entries) for (replace in listOf(false, true)) {
            var admitted = true
            val target = Member(); val healthy = Member(true); val newer = Member(true)
            lateinit var controller: PrototypePlaybackController
            controller = PrototypePlaybackController({
                if (replace) controller.setMember(other(side), newer) else admitted = false
                true
            }, admission = { admitted })
            val intent = FeedRetryPlaybackIntent(); val token = Any()
            val receipt = intent.arm(side.ordinal, token, null, true, allowSiblingReplacement = true)
            controller.setMember(side, target); controller.setMember(other(side), healthy)
            assertTrue(intent.consume(receipt, token, healthy))
            assertFalse(controller.rejoin(side, target, healthy))
            assertFalse(target.transport.contains(true))
            assertTrue(newer.transport.isEmpty())
            // setMember alone may pause the retired healthy member; recovery
            // must never issue Play or alter its replacement after that race.
            assertFalse(healthy.transport.contains(true))
            controller.close()
        }
    }
}
