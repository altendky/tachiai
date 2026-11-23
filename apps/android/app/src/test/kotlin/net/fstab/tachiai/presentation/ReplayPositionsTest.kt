package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativePairSkipPlan
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.platform.media.nativePairSkipPlan
import net.fstab.tachiai.provider.catalog.CatalogIntent
import net.fstab.tachiai.provider.catalog.CatalogResource
import org.junit.Assert.*
import org.junit.Test

// Remembered replay positions (#655 part 2): identity, resume decisions,
// pair baseline, notices, Start over and the save schedule.
class ReplayPositionsTest {
    private val first = "12345678-1234-1234-1234-123456789abc"
    private val second = "12345678-1234-1234-1234-123456789abd"
    private fun twitch(resource: String = "12345", replay: Boolean = true, instance: String = first) =
        ConfiguredPlaybackFeed(ConfiguredFeedChoice(first, instance),
            CatalogResource(ProviderId("twitch"), if (replay) "video" else "broadcaster", resource,
                if (replay) CatalogIntent.VIDEO else CatalogIntent.CHANNEL),
            PrototypeService.TWITCH, if (replay) PrototypePlaybackKind.REPLAY else PrototypePlaybackKind.LIVE)
    private fun abemaSlot(identity: String = "sumo/CQ2AnExAX8s7LT") = ConfiguredPlaybackFeed(ConfiguredFeedChoice(second, second),
        CatalogResource(ProviderId("abema"), "slot", identity, CatalogIntent.BROADCAST),
        PrototypeService.ABEMA, PrototypePlaybackKind.REPLAY)
    private fun snapshot(position: Long? = 60_000, live: Boolean = false, ad: Boolean = false, state: Int = 3,
        playing: Boolean = true) = NativeTimingSnapshot(position, 600_000, null, null, null, null, live, live,
        true, true, ad, playing, playing, state, 1f)

    @Test fun keyHashesOnlyTheItemIdentity() {
        val key = replayPositionKey(twitch().resource)
        assertTrue(validReplayPositionKey(key))
        assertEquals(key, replayPositionKey(twitch().resource))
        // Another provider instance or account shares the item's position.
        assertEquals(key, replayPositionKey(twitch(instance = second).resource))
        assertNotEquals(key, replayPositionKey(twitch("12346").resource))
        assertNotEquals(replayPositionKey(abemaSlot().resource), replayPositionKey(abemaSlot("sumo/CQ2AnExAX8s7LU").resource))
        assertFalse(key.contains("12345")); assertFalse(key.contains("twitch"))
    }

    @Test fun onlyReplayViewsRememberPositions() {
        val single = replayPositionKeys(ConfiguredPlaybackSelection(twitch(), null))
        assertEquals(listOf(replayPositionKey(twitch().resource), null), single)
        val pair = replayPositionKeys(ConfiguredPlaybackSelection(abemaSlot(), twitch()))
        assertEquals(listOf(replayPositionKey(abemaSlot().resource), replayPositionKey(twitch().resource)), pair)
        assertEquals(listOf(null, null), replayPositionKeys(ConfiguredPlaybackSelection(null, twitch(replay = false))))
        // A replay paired with a live feed follows it through Timing; neither is remembered.
        assertEquals(listOf(null, null), replayPositionKeys(ConfiguredPlaybackSelection(abemaSlot(), twitch(replay = false))))
    }

    @Test fun retryUsesThisSessionsPositionOtherwiseTheSavedOne() {
        assertEquals(8_000L, replayResumeTargetMs(retrying = false, retryPositionMs = null, savedPositionMs = 8_000))
        assertEquals(9_000L, replayResumeTargetMs(retrying = true, retryPositionMs = 9_000, savedPositionMs = 8_000))
        // A retry before the first preparation still resumes the saved position.
        assertEquals(8_000L, replayResumeTargetMs(retrying = true, retryPositionMs = null, savedPositionMs = 8_000))
        // Play again forgets both, so it starts from the beginning.
        assertNull(replayResumeTargetMs(retrying = true, retryPositionMs = null, savedPositionMs = null))
        assertNull(replayResumeTargetMs(retrying = false, retryPositionMs = 9_000, savedPositionMs = null))
    }

    @Test fun positionsInsideAdsLiveOrUnreadyFeedsAreNotRemembered() {
        assertEquals(60_000L, rememberedReplayPositionMs(snapshot()))
        assertEquals(60_000L, rememberedReplayPositionMs(snapshot(playing = false)))
        assertNull(rememberedReplayPositionMs(snapshot(ad = true)))
        assertNull(rememberedReplayPositionMs(snapshot(live = true)))
        assertNull(rememberedReplayPositionMs(snapshot(state = 2)))
        assertNull(rememberedReplayPositionMs(snapshot(position = -1)))
        assertNull(rememberedReplayPositionMs(snapshot(position = null)))
        assertNull(rememberedReplayPositionMs(null))
    }

    @Test fun resumedPairStartsFromItsDisplacement() {
        assertEquals(0L, pairOffsetResumeBaselineMs(false, snapshot(130_000), snapshot(70_000)))
        assertEquals(60_000L, pairOffsetResumeBaselineMs(true, snapshot(130_000), snapshot(70_000)))
        assertEquals(-60_000L, pairOffsetResumeBaselineMs(true, snapshot(70_000), snapshot(130_000)))
        // A live feed sits at its default position.
        assertEquals(130_000L, pairOffsetResumeBaselineMs(true, snapshot(130_000), snapshot(5_000, live = true)))
        assertNull(pairOffsetResumeBaselineMs(true, snapshot(MAX_PAIR_OFFSET_MS + 1), snapshot(0)))
    }

    @Test fun resumeNoticeNamesWhereEachFeedResumed() {
        assertNull(replayResumeNotice(emptyMap(), false))
        assertEquals("Resumed at 2:14:05. Start over is in ⋯ More.",
            replayResumeNotice(mapOf(NativeMixedSide.A to ReplayResumed(8_045_000, 17_100_000)), false))
        assertEquals("Resumed at 4:05. Start over is in ⋯ More.",
            replayResumeNotice(mapOf(NativeMixedSide.B to ReplayResumed(245_000, 600_000)), false))
        assertEquals("Resumed A at 2:14:05, B at 2:14:17. Start over is in ⋯ More.",
            replayResumeNotice(mapOf(NativeMixedSide.B to ReplayResumed(8_057_000, 17_100_000),
                NativeMixedSide.A to ReplayResumed(8_045_000, 17_100_000)), true))
    }

    @Test fun startOverMovesBackToTheEarliestStartKeepingThePairOffset() {
        val pair = nativePairSkipPlan(mapOf(NativeMixedSide.A to snapshot(130_000), NativeMixedSide.B to snapshot(70_000)),
            START_OVER_MS)
        assertEquals(NativeSeekOutcome.CLAMPED, pair.outcome)
        assertEquals(-70_000L, pair.movementMs)
        assertEquals(listOf(60_000L, 0L), pair.targets.map { it.targetMs })
        assertEquals("Started over · 1:00 / 10:00", startOverNotice(pair, 60_000, 600_000, pair = true))
        val single = nativePairSkipPlan(mapOf(NativeMixedSide.A to snapshot(245_000)), START_OVER_MS)
        assertEquals(listOf(0L), single.targets.map { it.targetMs })
        assertEquals("Started over · 0:00 / 10:00", startOverNotice(single, 0, 600_000, pair = false))
        val atStart = nativePairSkipPlan(mapOf(NativeMixedSide.A to snapshot(0)), START_OVER_MS)
        assertEquals(NativeSeekOutcome.NO_CHANGE, atStart.outcome)
        assertEquals("At the start.", startOverNotice(atStart, null, 600_000, pair = false))
        val ad = nativePairSkipPlan(mapOf(NativeMixedSide.A to snapshot(130_000), NativeMixedSide.B to snapshot(70_000, ad = true)),
            START_OVER_MS)
        assertEquals("Skipping waits for B's advertisement; neither feed moved.", startOverNotice(ad, null, 600_000, pair = true))
        assertEquals("Skipping is unavailable right now.",
            startOverNotice(NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE), null, 600_000, pair = false))
    }

    @Test fun saverWritesChangedPositionsEveryIntervalAndAtOnceWhenForced() {
        val a = "a".repeat(64); val b = "b".repeat(64)
        val saver = ReplayPositionSaver(startMs = 1_000)
        saver.seed(mapOf(a to 8_000L))
        // The resumed position is already stored.
        assertEquals(emptyMap<String, Long>(), saver.due(20_000, mapOf(a to 8_000L), force = true))
        assertEquals(emptyMap<String, Long>(), saver.due(5_000, mapOf(a to 12_000L), force = false))
        assertEquals(mapOf(a to 12_000L), saver.due(11_000, mapOf(a to 12_000L), force = false))
        assertEquals(emptyMap<String, Long>(), saver.due(15_000, mapOf(a to 16_000L, b to 1_000L), force = false))
        // Pause, leaving and the background write at once.
        assertEquals(mapOf(a to 16_000L, b to 1_000L), saver.due(15_500, mapOf(a to 16_000L, b to 1_000L), force = true))
        // A paused feed's unchanged position is not written again.
        assertEquals(emptyMap<String, Long>(), saver.due(60_000, mapOf(a to 16_000L, b to 1_000L), force = false))
    }

    @Test fun startOverAndPlayAgainForgetSoTheNextPositionIsWritten() {
        val a = "a".repeat(64)
        val saver = ReplayPositionSaver(startMs = 0)
        assertEquals(mapOf(a to 30_000L), saver.due(10_000, mapOf(a to 30_000L), force = false))
        saver.forget(setOf(a))
        assertEquals(mapOf(a to 30_000L), saver.due(10_100, mapOf(a to 30_000L), force = true))
    }
}
