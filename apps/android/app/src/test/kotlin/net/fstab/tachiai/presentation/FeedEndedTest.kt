package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import org.junit.Assert.*
import org.junit.Test

class FeedEndedTest {
    // Provider-free player snapshots shaped like each source kind at its end (#553).
    private fun snapshot(state: Int, live: Boolean = false, dynamic: Boolean = false, playingAd: Boolean = false) =
        NativeTimingSnapshot(591_626, 591_557, 591_626, null, null, null, live, dynamic, !live, true, playingAd,
            false, true, state, 1f)
    private val sources = mapOf(
        "ABEMA episode or slot replay (static DASH)" to (snapshot(4) to PrototypePlaybackKind.REPLAY),
        "Twitch video (finished HLS)" to (snapshot(4) to PrototypePlaybackKind.REPLAY),
        "YouTube video" to (snapshot(4) to PrototypePlaybackKind.REPLAY),
        "Twitch broadcaster after its stream closed" to (snapshot(4, live = true) to PrototypePlaybackKind.LIVE),
        "ABEMA live slot whose dynamic manifest ended" to (snapshot(4, live = true, dynamic = true) to PrototypePlaybackKind.LIVE),
    )

    @Test fun eachSourceKindEndsOnlyFromItsPlayersEndedState() {
        for ((name, value) in sources) {
            val (ended, kind) = value
            val expected = if (kind == PrototypePlaybackKind.LIVE) PrototypeFailureReason.BROADCAST_ENDED
                else PrototypeFailureReason.REPLAY_ENDED
            assertEquals(name, PrototypeFeedFailure(expected), endedFeedFailure(ended, kind))
            // Idle, buffering, ready (playing or paused) and advertisements are never an end.
            for (other in listOf(ended.copy(state = 1), ended.copy(state = 2), ended.copy(state = 3),
                ended.copy(state = 3, playing = true), ended.copy(playingAd = true)))
                assertNull(name, endedFeedFailure(other, kind))
        }
        assertNull(endedFeedFailure(null, PrototypePlaybackKind.REPLAY))
        assertNull(endedFeedFailure(snapshot(4), null))
    }

    @Test fun anEndedFeedOffersReopeningWithItsOwnWording() {
        val replay = PrototypeFeedFailure(PrototypeFailureReason.REPLAY_ENDED)
        val live = PrototypeFeedFailure(PrototypeFailureReason.BROADCAST_ENDED)
        assertEquals(FeedRecoveryAction.PLAY_AGAIN, replay.recoveryAction())
        assertEquals("Play again", FeedRecoveryAction.PLAY_AGAIN.label)
        assertEquals(FeedRecoveryAction.RETRY, live.recoveryAction())
        assertEquals("This replay has ended.", replay.viewerMessage)
        assertEquals("This broadcast has ended.", live.viewerMessage)
        // Automatic recovery is covered in FeedAutoRecoveryStateTest.
    }

    @Test fun playAgainForgetsTheEndedReplaysLastPosition() {
        val retry = FeedRetryState()
        retry.rememberReplay(591_000)
        assertEquals(591_000L, retry.replayPositionMs)
        retry.forgetReplay()
        assertNull(retry.replayPositionMs)
    }
}
