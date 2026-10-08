package net.fstab.tachiai.platform.media

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class NativePlaybackBudgetTest {
    @Test fun `closing a temporary child does not stop the pair but parent closure stops children`() {
        var now = 1_000L
        val parent = NativePlaybackBudget(300_000, { true }, { now }, maximumDurationMs = 300_000)
        now += 40_000
        val temporary = parent.child()
        assertEquals(260_000L, temporary.remainingMs)
        temporary.stop()
        assertFalse(temporary.active)
        assertTrue(parent.active)
        val playback = parent.child()
        now += 20_000
        assertEquals(240_000L, playback.remainingMs)
        parent.stop()
        assertFalse(playback.active)
        assertFalse(parent.child().active)
    }
    @Test fun `late child cannot extend the original five minute deadline`() {
        var now = 1_000L
        val parent = NativePlaybackBudget(300_000, { true }, { now }, maximumDurationMs = 300_000)
        now += 299_000
        val child = parent.child()
        assertEquals(1_000L, child.remainingMs)
        now += 1_000
        assertFalse(parent.active)
        assertFalse(child.active)
    }
    @Test fun `remaining budget only decreases with time and closes on invalidation`() {
        var now = 1_000L
        var active = true
        val budget = NativePlaybackBudget(10_000, { active }, { now }, maximumDurationMs = 300_000)
        assertEquals(10_000L, budget.remainingMs)
        now += 4_000
        assertEquals(6_000L, budget.remainingMs)
        active = false
        assertEquals(0L, budget.remainingMs)
        active = true
        now = 999
        assertEquals(0L, budget.remainingMs)
        now = 11_000
        assertEquals(0L, budget.remainingMs)
        budget.stop()
        now = 1_000
        assertEquals(0L, budget.remainingMs)
    }
    @Test fun `explicit longer comparison is bounded at five minutes without renewal`() {
        var now = 1_000L
        val budget = NativePlaybackBudget(3_600_000, { true }, { now }, maximumDurationMs = 300_000)
        now += 299_999
        assertTrue(budget.active)
        now++
        assertFalse(budget.active)
        assertFalse(NativePlaybackBudget(0, { true }, maximumDurationMs = 300_000).active)
        try {
            NativePlaybackBudget(3_600_000, { true }, maximumDurationMs = 300_001)
            throw AssertionError("Expected bound refusal")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `longer comparison still obeys retention foreground and terminal stop`() {
        var now = 1_000L
        var foreground = true
        val budget = NativePlaybackBudget(10_000, { foreground }, { now }, maximumDurationMs = 300_000)
        foreground = false
        assertFalse(budget.active)
        foreground = true
        now += 10_000
        assertFalse(budget.active)
        val stopped = NativePlaybackBudget(300_000, { true }, { now }, maximumDurationMs = 300_000)
        stopped.stop()
        assertFalse(stopped.active)
    }

    @Test fun `media session is capped at two minutes and never renewed`() {
        var now = 1_000L
        val budget = NativePlaybackBudget(3_600_000, { true }, { now })
        assertTrue(budget.active)
        now += 119_999
        assertTrue(budget.active)
        now++
        assertFalse(budget.active)
        try { budget.check(); throw AssertionError("Expected expiry") } catch (_: IOException) { }
    }

    @Test fun `remaining retention shortens the session`() {
        var now = 1_000L
        val budget = NativePlaybackBudget(5_000, { true }, { now })
        now += 4_999
        assertTrue(budget.active)
        now++
        assertFalse(budget.active)
        assertFalse(NativePlaybackBudget(0, { true }).active)
        assertFalse(NativePlaybackBudget(-1, { true }).active)
    }

    @Test fun `stop is terminal even when foreground returns`() {
        var foreground = true
        val budget = NativePlaybackBudget(120_000, { foreground }, { 1_000 })
        foreground = false
        assertFalse(budget.active)
        budget.stop()
        foreground = true
        assertFalse(budget.active)
    }

    @Test fun `superseded authorization or clock rollback blocks media`() {
        var current = true
        var now = 1_000L
        val budget = NativePlaybackBudget(120_000, { current }, { now })
        current = false
        assertFalse(budget.active)
        current = true
        now--
        assertFalse(budget.active)
    }

    @Test fun `plain playlists and explicit METHOD NONE are allowed`() {
        assertTrue(unencryptedHls("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:5,\nsegment.ts"))
        assertTrue(unencryptedHls("#EXTM3U\n#EXT-X-KEY:METHOD=NONE\nsegment.ts"))
    }

    @Test fun `encryption and session keys are rejected including trimmed lines`() {
        for (tag in listOf("#EXT-X-KEY:METHOD=AES-128,URI=\"key\"",
            "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"", "#EXT-X-SESSION-KEY:METHOD=NONE",
            "  #EXT-X-KEY:METHOD=AES-128,URI=\"key\"  ", "\t#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES",
            "#EXT-X-KEY:METHOD=NONE,URI=\"unexpected\"")) assertFalse(unencryptedHls("#EXTM3U\n$tag"))
        assertFalse(unencryptedHls("not a playlist"))
    }
}
