package net.fstab.tachiai.platform.network

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class MediaResponseSetupDeadlineTest {
    @Test fun realSetupSchedulerHasBoundedCapacityAndReleasesEveryCancelledReservation() {
        repeat(2) {
            val deadlines = mutableListOf<MediaResponseSetupDeadline>()
            try {
                repeat(64) { deadlines += MediaResponseSetupDeadline({}) }
                assertThrows(IOException::class.java) { MediaResponseSetupDeadline({}) }
            } finally { deadlines.forEach { it.close() } }
        }
    }

    private class Alarm {
        lateinit var action: () -> Unit
        var closes = 0
        fun schedule(delayMs: Long, callback: () -> Unit): AutoCloseable {
            assertEquals(30_000L, delayMs)
            action = callback
            return AutoCloseable { closes++ }
        }
    }

    @Test fun acceptedBodyReleasesAlarmAndEvenAnAlreadyQueuedCallbackCannotCancelIt() {
        val alarm = Alarm(); var now = 1_000L; var cancelled = 0
        val deadline = MediaResponseSetupDeadline({ cancelled++ }, { now }, alarm::schedule)
        assertTrue(deadline.acceptBody()); assertEquals(1, alarm.closes)
        now += 90_000
        alarm.action()
        assertTrue(deadline.acceptBody()); assertFalse(deadline.expired); assertEquals(0, cancelled)
        deadline.close(); assertEquals(1, alarm.closes)
    }

    @Test fun setupExpiryCancelsOnceRejectsBodyAndReleasesTheAlarm() {
        val alarm = Alarm(); var now = 1_000L; var cancelled = 0
        val deadline = MediaResponseSetupDeadline({ cancelled++ }, { now }, alarm::schedule)
        now += 30_000
        alarm.action(); alarm.action()
        assertTrue(deadline.expired); assertFalse(deadline.acceptBody())
        deadline.close(); assertTrue(deadline.expired)
        assertEquals(1, cancelled); assertEquals(1, alarm.closes)
    }

    @Test fun lateBodyCannotBeatADelayedAlarmAndClosingBeforeSetupPreventsLateCancellation() {
        for (closeFirst in listOf(false, true)) {
            val alarm = Alarm(); var now = 1_000L; var cancelled = 0
            val deadline = MediaResponseSetupDeadline({ cancelled++ }, { now }, alarm::schedule)
            if (closeFirst) deadline.close()
            now += 30_000
            assertFalse(deadline.acceptBody())
            alarm.action()
            assertEquals(if (closeFirst) 0 else 1, cancelled)
            assertEquals(!closeFirst, deadline.expired)
            deadline.close(); assertEquals(1, alarm.closes)
        }
    }

    @Test fun synchronousExpiryDuringSchedulingDoesNotLeakTheReturnedAlarm() {
        var cancelled = 0; var released = 0
        val deadline = MediaResponseSetupDeadline({ cancelled++ }, { 0L }, { _, action ->
            action(); AutoCloseable { released++ }
        })
        assertTrue(deadline.expired); assertFalse(deadline.acceptBody())
        deadline.close(); assertEquals(1, cancelled); assertEquals(1, released)
    }

    @Test fun racingBodyAcceptanceAndAlarmHaveOneWinnerAndNeverCancelAnAcceptedBody() {
        repeat(100) {
            val ready = CountDownLatch(2); val start = CountDownLatch(1)
            val cancelled = AtomicInteger(); val closes = AtomicInteger()
            lateinit var expire: () -> Unit
            val deadline = MediaResponseSetupDeadline({ cancelled.incrementAndGet() }, { 1_000L }, { _, action ->
                expire = action; AutoCloseable { closes.incrementAndGet() }
            })
            val accepted = AtomicBoolean()
            val body = Thread { ready.countDown(); check(start.await(1, TimeUnit.SECONDS)); accepted.set(deadline.acceptBody()) }
            val alarm = Thread { ready.countDown(); check(start.await(1, TimeUnit.SECONDS)); expire() }
            body.start(); alarm.start(); assertTrue(ready.await(1, TimeUnit.SECONDS)); start.countDown()
            body.join(1000); alarm.join(1000)
            assertFalse(body.isAlive); assertFalse(alarm.isAlive)
            assertEquals(if (accepted.get()) 0 else 1, cancelled.get())
            assertEquals(!accepted.get(), deadline.expired)
            deadline.close(); assertEquals(1, closes.get())
        }
    }
}
