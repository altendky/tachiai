package net.fstab.tachiai.feature.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRestartCoordinatorTest {
    private val player = RestartProcessIdentity(42, 10001, "app:prototype_cached_player")
    private val main = RestartProcessIdentity(43, 10001, "app")
    private fun coordinator(beganAt: Long = 100) = PlaybackRestartCoordinator(player.pid, player.uid, player.name, beganAt, 1_000)

    @Test fun `waits for exact owned player to exit before reopening`() {
        val relay = coordinator()
        assertEquals(PlaybackRestartStatus.WAITING, relay.poll(100, listOf(player, main)))
        assertEquals(PlaybackRestartStatus.WAITING, relay.poll(500, listOf(player)))
        assertEquals(PlaybackRestartStatus.READY, relay.poll(600, listOf(main)))
    }

    @Test fun `unavailable process snapshot never establishes exit`() {
        val relay = coordinator()
        assertEquals(PlaybackRestartStatus.WAITING, relay.poll(100, null))
        assertEquals(PlaybackRestartStatus.TIMED_OUT, relay.poll(1100, null))
    }

    @Test fun `wrong uid process name or duplicate pid refuses replacement`() {
        for (processes in listOf(listOf(player.copy(uid = 10002)), listOf(player.copy(name = "app")),
                listOf(player, player)))
            assertEquals(PlaybackRestartStatus.IDENTITY_MISMATCH, coordinator().poll(100, processes))
    }

    @Test fun `recreation preserves original deadline rather than extending wait`() {
        val original = coordinator()
        assertEquals(PlaybackRestartStatus.WAITING, original.poll(1099, listOf(player)))
        val recreated = coordinator(original.beganAt)
        assertEquals(PlaybackRestartStatus.TIMED_OUT, recreated.poll(1100, listOf(player)))
        assertEquals(PlaybackRestartStatus.TIMED_OUT, recreated.poll(1101, emptyList()))
    }

    @Test fun `elapsed clock reversal fails closed`() {
        assertEquals(PlaybackRestartStatus.TIMED_OUT, coordinator().poll(99, emptyList()))
    }
}
