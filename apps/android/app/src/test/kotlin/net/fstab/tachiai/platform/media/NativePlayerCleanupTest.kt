package net.fstab.tachiai.platform.media

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

// #730: Media3 reports a release timeout (1003) after 500 ms and stops waiting,
// while its playback thread keeps releasing. Cleanup is confirmed only if that
// release is seen to finish within the bounded late-release deadline.
@UnstableApi
class NativePlayerCleanupTest {
    private class Lane : Executor {
        private val tasks = ArrayDeque<Runnable>()
        var rejection: RejectedExecutionException? = null
        override fun execute(command: Runnable) {
            rejection?.let { throw it }
            tasks.addLast(command)
        }
        fun next() { check(tasks.isNotEmpty()) { "Expected queued work" }; tasks.removeFirst().run() }
        fun drain() { while (tasks.isNotEmpty()) next() }
        val empty get() = tasks.isEmpty()
    }

    private class Fixture(timeout: Boolean, val completion: ((Long) -> Boolean)?) {
        var listener: Player.Listener? = null
        var releases = 0
        var unconfirmed = 0
        val waits = mutableListOf<Long>()
        val observations = mutableListOf<Throwable>()
        val failures = mutableListOf<Throwable>()
        var completed = 0
        var now = 100L
        val worker = Lane(); val owner = Lane(); val waiter = Lane()
        val receipt = NativeCleanupReceipt { unconfirmed++ }
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "addListener" -> { listener = args!![0] as Player.Listener; null }
                "release" -> {
                    releases++
                    // Media3 notifies its timeout synchronously, then returns void.
                    if (timeout) repeat(2) { checkNotNull(listener).onPlayerError(PlaybackException("synthetic-private", null, 1003)) }
                    now = 600L
                    null
                }
                else -> throw AssertionError("Unexpected fixture player operation")
            }
        } as Player

        fun own() {
            receipt.ownPlayer(player, { true }, { _, error -> observations += error }, { now },
                completion?.let { await -> NativePlayerReleaseCompletion { waits += it; await(it) } }, waiter)
            receipt.own(40, ownerThread = true) { }
            receipt.seal()
        }
        fun closeAsync() = receipt.closeAsync(worker, owner, { completed++ }, failures::add)
        val timeouts get() = observations.filterIsInstance<NativePlayerReleaseTimeoutException>()
    }

    @Test fun timedOutReleaseThatFinishesLateConfirmsCleanup() {
        val fixture = Fixture(timeout = true) { true }
        fixture.own(); fixture.closeAsync(); fixture.owner.drain()
        assertEquals(1, fixture.releases)
        assertTrue(fixture.receipt.pending); assertFalse(fixture.receipt.confirmed); assertEquals(0, fixture.completed)
        fixture.waiter.next()
        fixture.owner.drain()
        assertEquals(listOf(NATIVE_PLAYER_LATE_RELEASE_DEADLINE_MS), fixture.waits)
        assertFalse(fixture.receipt.pending); assertTrue(fixture.receipt.confirmed); assertEquals(1, fixture.completed)
        assertEquals(0, fixture.unconfirmed); assertTrue(fixture.failures.isEmpty())
        // The timeout itself is still recorded once as diagnostic evidence.
        assertEquals(1, fixture.timeouts.size); assertEquals(500L, fixture.timeouts.single().elapsedMs)
        assertEquals(1, fixture.observations.size)
    }

    @Test fun timedOutReleaseThatNeverFinishesStaysUnconfirmed() {
        val fixture = Fixture(timeout = true) { false }
        fixture.own(); fixture.closeAsync(); fixture.owner.drain()
        assertTrue(fixture.receipt.pending); assertEquals(0, fixture.unconfirmed)
        fixture.waiter.next(); fixture.owner.drain()
        assertFalse(fixture.receipt.pending); assertFalse(fixture.receipt.confirmed); assertEquals(1, fixture.completed)
        assertTrue(fixture.unconfirmed > 0)
        assertEquals(1, fixture.timeouts.size)
        assertTrue(fixture.failures.single() is IOException)
        assertThrows(IOException::class.java) { fixture.receipt.close() }
    }

    @Test fun failingCompletionCheckStaysUnconfirmed() {
        val fixture = Fixture(timeout = true) { throw InterruptedException("synthetic") }
        fixture.own(); fixture.closeAsync(); fixture.owner.drain()
        fixture.waiter.next(); fixture.owner.drain()
        assertFalse(fixture.receipt.confirmed); assertTrue(fixture.unconfirmed > 0); assertEquals(1, fixture.completed)
    }

    @Test fun timedOutReleaseWithoutCompletionEvidenceFailsAtOnce() {
        val fixture = Fixture(timeout = true, completion = null)
        fixture.own(); fixture.closeAsync(); fixture.owner.drain()
        assertTrue(fixture.waiter.empty)
        assertFalse(fixture.receipt.pending); assertFalse(fixture.receipt.confirmed); assertEquals(1, fixture.completed)
        assertTrue(fixture.unconfirmed > 0); assertEquals(1, fixture.timeouts.size)
    }

    @Test fun refusedLateWaitStaysUnconfirmed() {
        val fixture = Fixture(timeout = true) { true }
        fixture.waiter.rejection = RejectedExecutionException("synthetic")
        fixture.own(); fixture.closeAsync(); fixture.owner.drain()
        assertTrue(fixture.waits.isEmpty())
        assertFalse(fixture.receipt.pending); assertFalse(fixture.receipt.confirmed); assertTrue(fixture.unconfirmed > 0)
    }

    @Test fun synchronousCloseDoesNotWaitForALateRelease() {
        val fixture = Fixture(timeout = true) { true }
        fixture.own()
        assertThrows(IOException::class.java) { fixture.receipt.close() }
        assertTrue(fixture.waiter.empty); assertTrue(fixture.waits.isEmpty())
        assertFalse(fixture.receipt.confirmed); assertTrue(fixture.unconfirmed > 0)
    }

    @Test fun timeoutOutsideTheReleaseCallStillFailsAtOnce() {
        val fixture = Fixture(timeout = false) { true }
        // A queued timeout delivered before the release call began (#334).
        fixture.receipt.own(10, ownerThread = true) {
            checkNotNull(fixture.listener).onPlayerError(PlaybackException("synthetic-private", null, 1003))
        }
        fixture.own(); fixture.closeAsync(); fixture.owner.drain()
        assertTrue(fixture.waiter.empty)
        assertFalse(fixture.receipt.pending); assertFalse(fixture.receipt.confirmed); assertTrue(fixture.unconfirmed > 0)
        assertTrue(fixture.timeouts.isEmpty())
    }

    @Test fun ordinaryReleaseConfirmsWithoutWaiting() {
        val fixture = Fixture(timeout = false) { true }
        fixture.own(); fixture.closeAsync(); fixture.owner.drain()
        assertTrue(fixture.waiter.empty); assertTrue(fixture.waits.isEmpty())
        assertTrue(fixture.receipt.confirmed); assertEquals(0, fixture.unconfirmed); assertTrue(fixture.observations.isEmpty())
    }

    @Test fun onlyExoPlayerPlaybackThreadsProvideCompletionEvidence() {
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, _, _ ->
            throw AssertionError("Unexpected fixture player operation")
        } as Player
        assertNull(nativePlayerReleaseCompletion(player))
    }
}
