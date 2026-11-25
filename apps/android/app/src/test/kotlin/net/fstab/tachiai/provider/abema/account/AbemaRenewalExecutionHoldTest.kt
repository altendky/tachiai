package net.fstab.tachiai.provider.abema.account

import org.junit.Assert.*
import org.junit.Test

class AbemaRenewalExecutionHoldTest {
    private class Job(val id: Int)
    private class Fixture(var accept: Boolean = true) {
        var scheduled = 0
        var cancelled = 0
        val finished = mutableListOf<Job>()
        val logs = mutableListOf<String>()
        val hold = AbemaRenewalExecutionHold<Job>({ scheduled++; accept }, { cancelled++ }, { finished.add(it) }, logs::add)
    }

    @Test fun oneJobCoversOverlappingRenewalsUntilTheLastEnds() {
        val f = Fixture()
        val first = f.hold.acquire()
        val second = f.hold.acquire()
        assertEquals(1, f.scheduled)
        val job = Job(1)
        assertTrue(f.hold.started(job))
        first()
        assertTrue(f.finished.isEmpty())
        assertTrue(f.hold.held())
        second()
        assertEquals(listOf(job), f.finished)
        assertFalse(f.hold.held())
        assertEquals(listOf("HOLD_SCHEDULED", "HOLD_STARTED", "HOLD_ENDED"), f.logs)
    }

    @Test fun aReleaseIsIdempotentAndAPendingJobIsWithdrawn() {
        val f = Fixture()
        val release = f.hold.acquire()
        val other = f.hold.acquire()
        release(); release()
        assertTrue(f.hold.held())
        other()
        assertEquals(1, f.cancelled)
        // The withdrawn job may still start; it then has nothing to hold.
        assertFalse(f.hold.started(Job(1)))
        assertTrue(f.finished.isEmpty())
    }

    @Test fun aNewRenewalAfterTheLastEndedSchedulesAgain() {
        val f = Fixture()
        f.hold.acquire().also { assertTrue(f.hold.started(Job(1))) }()
        f.hold.acquire()
        assertEquals(2, f.scheduled)
        assertTrue(f.hold.started(Job(2)))
        // A second start while one is running is refused.
        assertFalse(f.hold.started(Job(3)))
    }

    @Test fun aPlatformStopAsksForANewJobAtOnceOnlyWhileHeld() {
        val f = Fixture()
        val release = f.hold.acquire()
        val job = Job(1)
        f.hold.started(job)
        assertFalse(f.hold.stopped { it.id == 2 })
        // Never the platform's backoff: a fresh job is requested right away.
        assertFalse(f.hold.stopped { it === job })
        assertEquals(listOf("HOLD_STOPPED", "HOLD_SCHEDULED"), f.logs.takeLast(2))
        assertEquals(2, f.scheduled)
        assertTrue(f.hold.started(Job(1)))
        release()
        assertFalse(f.hold.stopped { true })
        assertEquals(2, f.scheduled)
    }

    @Test fun aRefusedScheduleIsLoggedAndLeavesTheRenewalUnaffected() {
        val f = Fixture(accept = false)
        val release = f.hold.acquire()
        assertEquals(listOf("HOLD_REFUSED"), f.logs)
        // An overlapping renewal asks again instead of relying on the first.
        f.accept = true
        val second = f.hold.acquire()
        assertEquals(listOf("HOLD_REFUSED", "HOLD_SCHEDULED"), f.logs)
        release(); second()
        assertEquals(1, f.cancelled)
        val failing = AbemaRenewalExecutionHold<Job>({ error("unavailable") }, { error("unavailable") }, { })
        failing.acquire()()
        assertFalse(failing.held())
    }
}
