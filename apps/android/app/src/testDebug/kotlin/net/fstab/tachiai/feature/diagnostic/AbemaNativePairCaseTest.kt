package net.fstab.tachiai.feature.diagnostic

import org.junit.Assert.*
import org.junit.Test

class AbemaNativePairCaseTest {
    @Test fun `original cases retain strict unaligned immediate preparation`() {
        for (name in listOf("LIVE", "REPLAY")) {
            val mode = checkNotNull(abemaNativePairCase(name))
            assertEquals(name == "REPLAY", mode.replay)
            assertFalse(mode.transitions)
            assertFalse(mode.alignedHelper)
            assertFalse(mode.prewarm)
            assertEquals(0L, mode.preparationDelayMs)
        }
    }
    @Test fun `original transition comparison keeps the old helper`() {
        val mode = checkNotNull(abemaNativePairCase("LIVE_TRANSITIONS"))
        assertTrue(mode.transitions)
        assertFalse(mode.alignedHelper)
        assertFalse(mode.replay)
        assertFalse(mode.prewarm)
        assertEquals(0L, mode.preparationDelayMs)
    }
    @Test fun `aligned immediate comparison is separate and News only`() {
        val mode = checkNotNull(abemaNativePairCase("LIVE_TRANSITIONS_ALIGNED"))
        assertTrue(mode.transitions)
        assertTrue(mode.alignedHelper)
        assertFalse(mode.replay)
        assertFalse(mode.prewarm)
        assertEquals(0L, mode.preparationDelayMs)
    }
    @Test fun `late start uses a fixed delay within the unchanged pair budget`() {
        val mode = checkNotNull(abemaNativePairCase("LIVE_TRANSITIONS_ALIGNED_LATE_START"))
        assertTrue(mode.transitions)
        assertTrue(mode.alignedHelper)
        assertFalse(mode.replay)
        assertFalse(mode.prewarm)
        assertEquals(130_000L, mode.preparationDelayMs)
        assertTrue(mode.preparationDelayMs < 300_000)
    }
    @Test fun `prewarm is a separate immediate aligned News comparison only`() {
        val mode = checkNotNull(abemaNativePairCase("LIVE_TRANSITIONS_ALIGNED_PREWARM"))
        assertTrue(mode.transitions)
        assertTrue(mode.alignedHelper)
        assertTrue(mode.prewarm)
        assertFalse(mode.replay)
        assertEquals(0L, mode.preparationDelayMs)
        assertEquals(listOf(mode, AbemaNativePairCase.LIVE_RELATIVE, AbemaNativePairCase.LIVE_REPLAY_RELATIVE),
            AbemaNativePairCase.entries.filter { it.prewarm })
        assertFalse(mode.relativeControls)
    }
    @Test fun `relative controls preserve older cases and bind only fixed comparison sources`() {
        val live = checkNotNull(abemaNativePairCase("LIVE_RELATIVE"))
        val replay = checkNotNull(abemaNativePairCase("REPLAY_RELATIVE"))
        assertTrue(live.relativeControls); assertTrue(live.transitions); assertTrue(live.alignedHelper); assertTrue(live.prewarm)
        assertFalse(live.replay)
        assertTrue(replay.relativeControls); assertTrue(replay.replay)
        assertFalse(replay.transitions); assertFalse(replay.alignedHelper); assertFalse(replay.prewarm)
        assertEquals(listOf(live, replay, AbemaNativePairCase.LIVE_REPLAY_RELATIVE, AbemaNativePairCase.REPLAY_LIVE_RELATIVE),
            AbemaNativePairCase.entries.filter { it.relativeControls })
    }
    @Test fun `older cases preserve matching source types`() {
        for (mode in AbemaNativePairCase.entries.filter { it !in setOf(
            AbemaNativePairCase.LIVE_REPLAY_RELATIVE, AbemaNativePairCase.REPLAY_LIVE_RELATIVE) })
            assertEquals(mode.replay, mode.twitchReplay)
    }
    @Test fun `mixed relative cases independently bind source types and ABEMA policy`() {
        val liveReplay = checkNotNull(abemaNativePairCase("LIVE_REPLAY_RELATIVE"))
        assertFalse(liveReplay.replay); assertTrue(liveReplay.twitchReplay)
        assertTrue(liveReplay.transitions); assertTrue(liveReplay.alignedHelper); assertTrue(liveReplay.prewarm)
        val replayLive = checkNotNull(abemaNativePairCase("REPLAY_LIVE_RELATIVE"))
        assertTrue(replayLive.replay); assertFalse(replayLive.twitchReplay)
        assertFalse(replayLive.transitions); assertFalse(replayLive.alignedHelper); assertFalse(replayLive.prewarm)
        for (mode in listOf(liveReplay, replayLive)) {
            assertTrue(mode.relativeControls)
            assertEquals(0L, mode.preparationDelayMs)
        }
    }
    @Test fun `unknown lowercased arbitrary delay and replay variants refuse`() {
        for (name in listOf("", "live", "LIVE_TRANSITIONS_ALIGNED ", "REPLAY_ALIGNED",
            "LIVE_TRANSITIONS_ALIGNED_999999", "https://abema.tv/")) assertNull(abemaNativePairCase(name))
    }
}
