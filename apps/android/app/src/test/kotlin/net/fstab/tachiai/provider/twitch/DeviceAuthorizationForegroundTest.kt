package net.fstab.tachiai.provider.twitch

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAuthorizationForegroundTest {
    @Test fun `pause revision survives resume and ignores duplicate lifecycle states`() {
        val gate = DeviceAuthorizationForeground()
        gate.setForeground(false)
        gate.setForeground(false)
        assertEquals(1L, gate.pauseRevision)
        gate.setForeground(true)
        assertTrue(gate.isForeground)
        assertEquals(1L, gate.pauseRevision)
        gate.setForeground(false)
        assertEquals(2L, gate.pauseRevision)
    }

    @Test fun `wait resumes on foreground and remains cancellable`() = runBlocking {
        val gate = DeviceAuthorizationForeground(false)
        val wait = async { gate.awaitForeground(60_000) { 0 } }
        yield()
        assertFalse(wait.isCompleted)
        gate.setForeground(true)
        assertTrue(wait.await())
        gate.setForeground(false)
        val cancelled = async { gate.awaitForeground(60_000) { 0 } }
        yield()
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
    }

    @Test fun `expiry completes even when foreground never returns`() = runBlocking {
        val gate = DeviceAuthorizationForeground(false)
        val clock = { System.nanoTime() / 1_000_000 }
        assertFalse(gate.awaitForeground(clock() + 20, clock))
        assertFalse(gate.awaitForeground(0) { 0 })
    }

    @Test fun `unbounded initial wait accepts a negative monotonic clock`() = runBlocking {
        assertTrue(DeviceAuthorizationForeground().awaitForeground(Long.MAX_VALUE) { -1000 })
    }
}
