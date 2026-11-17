package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AbemaAccountWallClockTest {
    @Test fun `backward and negative samples never lower high water or become a fabricated zero`() {
        var now = 0L
        val clock = AbemaAccountWallClock { now }
        assertEquals(0L, clock.sample())
        now = 100; assertEquals(100L, clock.sample())
        assertEquals(100L, clock.sample())
        now = 99; assertNull(clock.sample())
        now = -1; assertNull(clock.sample())
        now = 0; assertNull(clock.sample())
        now = 101; assertEquals(101L, clock.sample())
        now = 100; assertNull(clock.sample())
    }

    @Test fun `expiry check receives the real boundary value while invalid samples remain unavailable`() {
        var now = 99L
        val clock = AbemaAccountWallClock { now }
        val expiry = 100L
        fun unexpired() = clock.sample()?.let { it < expiry } == true
        assertTrue(unexpired())
        now = expiry
        assertEquals(expiry, clock.sample())
        assertFalse(unexpired())
        now = 99
        assertNull(clock.sample())
        assertFalse(unexpired())
        now = Long.MAX_VALUE
        assertEquals(Long.MAX_VALUE, clock.sample())
        assertFalse(unexpired())
    }

    @Test fun `sampling failure propagates without resetting the previous high water`() {
        var now = 100L
        var broken = false
        val failure = IllegalStateException("Synthetic wall clock failure")
        val clock = AbemaAccountWallClock { if (broken) throw failure else now }
        assertEquals(100L, clock.sample())
        broken = true
        assertSame(failure, assertThrows(IllegalStateException::class.java) { clock.sample() })
        broken = false; now = 99
        assertNull(clock.sample())
    }

    @Test fun `concurrent increasing samples cannot publish in reverse order or sample inside another sample commit`() {
        val enteredA = CountDownLatch(1); val attemptedB = CountDownLatch(1); val completedB = CountDownLatch(1)
        val firstThread = AtomicReference<Thread?>()
        val samplingA = AtomicBoolean()
        val sampledInsideA = AtomicBoolean()
        val clock = AbemaAccountWallClock {
            if (Thread.currentThread() === firstThread.get()) {
                samplingA.set(true)
                try {
                    val captured = 100L
                    enteredA.countDown()
                    check(attemptedB.await(3, TimeUnit.SECONDS))
                    // With serialization B cannot finish until this bounded
                    // wait ends. An outside-lock sampler lets B publish101
                    // before A compares its already-captured100.
                    completedB.await(500, TimeUnit.MILLISECONDS)
                    captured
                } finally { samplingA.set(false) }
            } else {
                if (samplingA.get()) sampledInsideA.set(true)
                101L
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val a = executor.submit<Long?> { firstThread.set(Thread.currentThread()); clock.sample() }
            assertTrue(enteredA.await(3, TimeUnit.SECONDS))
            val b = executor.submit<Long?> {
                attemptedB.countDown()
                try { clock.sample() } finally { completedB.countDown() }
            }
            assertEquals(100L, a.get(3, TimeUnit.SECONDS))
            assertEquals(101L, b.get(3, TimeUnit.SECONDS))
            assertFalse(sampledInsideA.get())
            assertEquals(101L, clock.sample())
        } finally {
            attemptedB.countDown(); completedB.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}
