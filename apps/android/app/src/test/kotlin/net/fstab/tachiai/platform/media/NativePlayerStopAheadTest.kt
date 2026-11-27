package net.fstab.tachiai.platform.media

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

// #769: the player is stopped first, a worker waits (bounded) for that stop to
// drain on the playback thread, and only then is release() run on the owner.
@UnstableApi
class NativePlayerStopAheadTest {
    private class Lane : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun next() { check(tasks.isNotEmpty()) { "Expected queued work" }; tasks.removeFirst().run() }
        val empty get() = tasks.isEmpty()
    }

    private class Fixture(val drained: Boolean = true, val stopFails: Boolean = false, val application: Boolean = false) {
        val calls = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        val failures = mutableListOf<Throwable>()
        val observations = mutableListOf<Throwable>()
        var completed = 0
        var unconfirmed = 0
        val worker = Lane(); val owner = Lane(); val waiter = Lane()
        val receipt = NativeCleanupReceipt { unconfirmed++ }
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "addListener" -> null
                "stop" -> { calls += "stop"; if (stopFails) throw IllegalStateException("synthetic") else null }
                "release" -> { calls += "release"; null }
                else -> throw AssertionError("Unexpected fixture player operation")
            }
        } as Player

        init {
            receipt.own(-10, ownerThread = true) { calls += "pause" }
            receipt.ownPlayer(player, { true }, { _, error -> observations += error }, { 100L }, null, waiter)
            receipt.stopPlayerAhead(player, { application }, NativePlayerStopDrain {
                calls += "mark"
                NativePlayerReleaseCompletion { timeoutMs -> calls += "drain"; waits += timeoutMs; drained }
            })
            receipt.own(25) { calls += "transport" }
            receipt.seal()
        }
        fun closeAsync() = receipt.closeAsync(worker, owner, { completed++ }, failures::add)
        fun confirmedCleanly() {
            assertTrue(receipt.confirmed); assertFalse(receipt.pending); assertEquals(1, completed)
            assertTrue(failures.isEmpty()); assertTrue(observations.isEmpty()); assertEquals(0, unconfirmed)
        }
    }

    @Test fun stopDrainsOnAWorkerBeforeTheOwnerReleases() {
        val fixture = Fixture()
        fixture.closeAsync()
        fixture.owner.next()
        assertEquals(listOf("pause", "stop", "mark"), fixture.calls)
        assertTrue("The owner does not wait for the drain", fixture.owner.empty)
        fixture.worker.next()
        assertEquals(listOf("pause", "stop", "mark", "transport", "drain"), fixture.calls)
        assertEquals(listOf(NATIVE_PLAYER_STOP_DRAIN_MS), fixture.waits)
        fixture.owner.next(); fixture.owner.next()
        assertEquals("release", fixture.calls.last())
        assertEquals(1, fixture.calls.count { it == "release" })
        fixture.confirmedCleanly()
    }

    @Test fun lapsedDrainStillReleasesAndConfirmsWithoutANewFailure() {
        val fixture = Fixture(drained = false)
        fixture.closeAsync()
        fixture.owner.next(); fixture.worker.next(); fixture.owner.next(); fixture.owner.next()
        assertEquals(listOf("pause", "stop", "mark", "transport", "drain", "release"), fixture.calls)
        fixture.confirmedCleanly()
    }

    @Test fun failedStopIsOnlySkippedAndReleaseStillOwnsCleanup() {
        val fixture = Fixture(stopFails = true)
        fixture.closeAsync()
        fixture.owner.next(); fixture.worker.next(); fixture.owner.next(); fixture.owner.next()
        assertEquals(listOf("pause", "stop", "transport", "release"), fixture.calls)
        fixture.confirmedCleanly()
    }

    @Test fun synchronousCleanupOnTheApplicationThreadNeverWaits() {
        val fixture = Fixture(application = true)
        fixture.receipt.close()
        assertEquals(listOf("pause", "stop", "mark", "transport", "release"), fixture.calls)
        assertTrue(fixture.waits.isEmpty())
        assertTrue(fixture.receipt.confirmed)
    }
}
