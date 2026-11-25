package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AbemaRenewalTimerTest {
    // One fake clock serves the wall clock, elapsed time and the handler.
    private class Clock(var now: Long = 0L) {
        private val pending = mutableListOf<Pair<Long, () -> Unit>>()
        fun delay(wait: Long, action: () -> Unit): Boolean { pending.add(now + wait to action); return true }
        fun advance(by: Long) {
            val end = now + by
            while (true) {
                val next = pending.filter { it.first <= end }.minByOrNull { it.first } ?: break
                pending.remove(next); now = maxOf(now, next.first); next.second()
            }
            now = end
        }
        fun idle() = pending.isEmpty()
    }

    private class TimerFixture {
        val clock = Clock()
        var manual = false
        val fired = mutableListOf<AbemaRenewalGeneration>()
        var done: ((Boolean) -> Unit)? = null
        val logs = mutableListOf<String>()
        val timer = AbemaRenewalTimer({ clock.now }, clock::delay, { manual },
            { _, generation, finished -> fired.add(generation); done = finished }, logs::add)
    }

    private class Work(val scope: String)
    private class Operation(val completed: (AbemaRenewalOperationOutcome) -> Unit) : AbemaRenewalOwnedOperation {
        var starts = 0
        override fun start() { starts++ }
        override fun cancel() = completed(AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.CANCELLED, true))
    }

    // The real coordinator, owned admission and retry loop behind the timer run.
    private class RunFixture {
        val clock = Clock(1_000L)
        val tickets = AtomicInteger()
        val coordinator = AbemaRenewalReadinessCoordinator<Work>({ clock.now },
            { tickets.incrementAndGet().toString(16).padStart(32, '0') })
        val constructed = mutableListOf<Operation>()
        val logs = mutableListOf<String>()
        var reserves = 0
        var manual = false
        var finished = 0
        val retried = mutableListOf<Boolean>()
        var generation = GENERATION
        lateinit var loop: AbemaRenewalRetryLoop<Work, Operation>
        val admission = AbemaRenewalOwnedAdmission(coordinator, ConcurrentHashMap<String, Operation>(),
            validate = { ticket, _ -> coordinator.current(ticket) }, dispatch = { it(); true },
            elapsed = { clock.now }, schedulePending = { _, _ -> true },
            authoritative = { work, outcome, ownerCancelled -> loop.completed(work, outcome, ownerCancelled) })
        init { loop = AbemaRenewalRetryLoop(coordinator, admission, { INSTANCE }, { it.scope }, { clock.now }, logs::add) }
        fun reserve(retry: Boolean, deadline: Long, admitted: () -> Boolean) = loop.reserve(INSTANCE, "account",
            Work("account"), deadline, admitted, construct = { _, _, done -> Operation(done).also(constructed::add) }, retry = retry)
        // The renewal job's own reservation on the same account scope.
        fun job() {
            val reservation = reserve(false, clock.now + AbemaReadinessIpc.LIMIT_MS) { true }
            assertTrue((reservation as AbemaRenewalReservation.Owned).lease.activate())
        }
        fun run() = AbemaRenewalTimerRun(INSTANCE, GENERATION, clock.now + AbemaReadinessIpc.LIMIT_MS, Executor { it.run() },
            { clock.now }, { AbemaRenewalJobPreflight(TARGET, generation) },
            { _, deadline, admitted -> reserves++; reserve(true, deadline, admitted) },
            { !manual }, clock::delay, { finished++; retried.add(it) }, logs::add).also { it.start() }
        fun finish(result: AbemaAutomaticRenewalResult) =
            constructed.last().completed(AbemaRenewalOperationOutcome(result, true))
    }

    companion object {
        private const val INSTANCE = "00000000-0000-0000-0000-000000000001"
        private val TARGET = AbemaReadinessTarget(INSTANCE, "01".repeat(32))
        private const val MINUTE = 60_000L
        private val GENERATION = AbemaRenewalGeneration(1, 0, 60 * MINUTE)
        private val RENEWED = AbemaRenewalGeneration(2, 50 * MINUTE, 120 * MINUTE)
    }

    @Test fun schedulesOneAttemptAtExpiryMinusTheJobLead() {
        val f = TimerFixture()
        f.timer.reconcile(mapOf(INSTANCE to GENERATION))
        assertEquals(listOf("TIMER_SCHEDULED"), f.logs)
        f.clock.advance(45 * MINUTE - 1)
        assertTrue(f.fired.isEmpty())
        f.clock.advance(1)
        assertEquals(listOf(GENERATION), f.fired)
        assertEquals("TIMER_FIRED", f.logs.last())
        f.done!!(false)
        // One timer attempt per expiry window; the job covers the rest.
        f.timer.reconcile(mapOf(INSTANCE to GENERATION))
        f.clock.advance(60 * MINUTE)
        assertEquals(1, f.fired.size)
        assertTrue(f.clock.idle())
    }

    @Test fun aShortGrantWaitsForHalfItsLifetimeLikeTheJob() {
        val f = TimerFixture()
        val short = AbemaRenewalGeneration(1, 0, 20 * MINUTE)
        f.timer.reconcile(mapOf(INSTANCE to short))
        f.clock.advance(10 * MINUTE - 1)
        assertTrue(f.fired.isEmpty())
        f.clock.advance(1)
        assertEquals(listOf(short), f.fired)
    }

    @Test fun anAlreadyDueGrantFiresWhenTheProcessStarts() {
        val f = TimerFixture()
        f.clock.now = 70 * MINUTE
        f.timer.reconcile(mapOf(INSTANCE to GENERATION))
        f.clock.advance(0)
        assertEquals(listOf(GENERATION), f.fired)
    }

    @Test fun noScheduleWithoutAGrantAndForgetCancelsIt() {
        val f = TimerFixture()
        f.timer.reconcile(mapOf(INSTANCE to null))
        assertTrue(f.clock.idle()); assertTrue(f.logs.isEmpty())
        f.timer.reconcile(mapOf(INSTANCE to GENERATION))
        f.timer.reconcile(emptyMap())
        f.clock.advance(120 * MINUTE)
        assertTrue(f.fired.isEmpty())
        assertFalse(f.timer.current(INSTANCE, GENERATION))
    }

    @Test fun aRenewalOrAcceptanceReschedulesForTheNewExpiry() {
        val f = TimerFixture()
        f.timer.reconcile(mapOf(INSTANCE to GENERATION))
        f.clock.advance(45 * MINUTE)
        f.done!!(false)
        f.timer.reconcile(mapOf(INSTANCE to RENEWED))
        assertEquals(2, f.logs.count { it == "TIMER_SCHEDULED" })
        assertFalse(f.timer.current(INSTANCE, GENERATION))
        f.clock.advance(105 * MINUTE - f.clock.now - 1)
        assertEquals(1, f.fired.size)
        f.clock.advance(1)
        assertEquals(listOf(GENERATION, RENEWED), f.fired)
    }

    @Test fun aRouteChangeAllowsOneMoreAttemptAfterTheRunningOneEnds() {
        val f = TimerFixture()
        f.timer.reconcile(mapOf(INSTANCE to GENERATION), reset = true)
        // Before the window, a reset keeps the single armed schedule.
        assertEquals(1, f.logs.count { it == "TIMER_SCHEDULED" })
        f.clock.advance(45 * MINUTE)
        assertEquals(1, f.fired.size)
        f.timer.reconcile(mapOf(INSTANCE to GENERATION), reset = true)
        assertEquals(2, f.logs.count { it == "TIMER_SCHEDULED" })
        f.clock.advance(MINUTE)
        assertEquals(1, f.fired.size) // The first attempt is still running.
        f.done!!(false)
        f.clock.advance(ABEMA_RENEWAL_TIMER_MANUAL_RECHECK_MS)
        assertEquals(2, f.fired.size)
    }

    @Test fun aManualApprovalPostponesTheAttemptWithoutSpendingIt() {
        val f = TimerFixture()
        f.manual = true
        f.timer.reconcile(mapOf(INSTANCE to GENERATION))
        f.clock.advance(45 * MINUTE + ABEMA_RENEWAL_TIMER_MANUAL_RECHECK_MS)
        assertTrue(f.fired.isEmpty())
        assertTrue("TIMER_POSTPONED reason=MANUAL_APPROVAL" in f.logs)
        f.manual = false
        f.clock.advance(ABEMA_RENEWAL_TIMER_MANUAL_RECHECK_MS)
        assertEquals(1, f.fired.size)
    }

    @Test fun theDebugTriggerFiresTheDueAttemptBeforeItsDueTime() {
        val f = TimerFixture()
        assertEquals(AbemaRenewalTrigger.NO_ACCOUNT, f.timer.trigger(INSTANCE))
        f.timer.reconcile(mapOf(INSTANCE to GENERATION))
        assertEquals(AbemaRenewalTrigger.STARTED, f.timer.trigger(INSTANCE))
        assertEquals(listOf(GENERATION), f.fired)
        assertEquals(listOf("TIMER_SCHEDULED", "TIMER_TRIGGERED", "TIMER_FIRED"), f.logs)
        assertEquals(AbemaRenewalTrigger.RUNNING, f.timer.trigger(INSTANCE))
        f.done!!()
        // It is the window's single attempt: neither a second trigger nor the due time fires again.
        assertEquals(AbemaRenewalTrigger.WINDOW_SPENT, f.timer.trigger(INSTANCE))
        f.clock.advance(60 * MINUTE)
        assertEquals(1, f.fired.size)
        assertTrue(f.clock.idle())
    }

    @Test fun theDebugTriggerRefusesWhileAManualApprovalHoldsTheBrowser() {
        val f = TimerFixture()
        f.manual = true
        f.timer.reconcile(mapOf(INSTANCE to GENERATION))
        assertEquals(AbemaRenewalTrigger.MANUAL_APPROVAL, f.timer.trigger(INSTANCE))
        assertTrue(f.fired.isEmpty())
        f.manual = false
        f.clock.advance(45 * MINUTE)
        assertEquals(listOf(GENERATION), f.fired)
    }

    @Test fun firingReservesOnceAndObservesItsRenewalToTheResult() {
        val f = RunFixture()
        f.run()
        assertEquals(1, f.constructed.single().starts)
        f.clock.advance(5_000)
        assertEquals(1, f.constructed.size)
        f.finish(AbemaAutomaticRenewalResult.SUCCESS)
        f.clock.advance(500)
        assertEquals(1, f.constructed.size)
        assertEquals(1, f.finished)
        assertEquals("TIMER_RESULT result=READY", f.logs.last())
        assertTrue(f.clock.idle())
    }

    @Test fun firingJoinsTheJobsRenewalInFlightInsteadOfStartingAnother() {
        val f = RunFixture()
        f.job()
        f.run()
        f.clock.advance(5_000)
        assertEquals(1, f.constructed.size)
        assertEquals(0, f.finished)
        f.finish(AbemaAutomaticRenewalResult.SUCCESS)
        f.clock.advance(500)
        assertEquals(1, f.constructed.size)
        assertEquals("TIMER_RESULT result=READY", f.logs.last())
    }

    @Test fun aTemporaryFailureIsRetriedByTheReadinessRetryLoop() {
        val f = RunFixture()
        f.run()
        f.finish(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE)
        f.clock.advance(ABEMA_RENEWAL_RETRY_BACKOFF_MS.first() + 500)
        assertEquals(2, f.constructed.size)
        assertTrue("RETRY attempt=1 reason=TEMPORARY_FAILURE" in f.logs)
        f.finish(AbemaAutomaticRenewalResult.SUCCESS)
        f.clock.advance(500)
        assertEquals("TIMER_RESULT result=READY", f.logs.last())
    }

    @Test fun noRenewalWhileAManualApprovalOccupiesTheBrowser() {
        val f = RunFixture()
        f.manual = true
        f.run()
        assertTrue(f.constructed.isEmpty())
        assertEquals(0, f.reserves)
        assertEquals("TIMER_RESULT result=STOPPED", f.logs.last())
        assertEquals(1, f.finished)
    }

    @Test fun aChangedGrantEndsTheAttemptWithoutReserving() {
        val f = RunFixture()
        f.generation = RENEWED
        f.run()
        assertEquals(0, f.reserves)
        assertEquals("TIMER_RESULT result=STALE", f.logs.last())
    }

    // #682: a renewal stopped by a deadline (for example one that passed while
    // the process was frozen) has no time left for the readiness retry loop.
    @Test fun anAttemptWithoutAResultIsRetriedTwiceAfterTheTimerRetryWaits() {
        val f = TimerFixture()
        f.timer.reconcile(mapOf(INSTANCE to GENERATION))
        f.clock.advance(45 * MINUTE)
        assertEquals(1, f.fired.size)
        f.done!!(true)
        assertEquals("TIMER_RETRY_SCHEDULED attempt=1", f.logs.last())
        f.clock.advance(ABEMA_RENEWAL_TIMER_RETRY_MS[0] - 1)
        assertEquals(1, f.fired.size)
        f.clock.advance(1)
        assertEquals(2, f.fired.size)
        f.done!!(true)
        f.clock.advance(ABEMA_RENEWAL_TIMER_RETRY_MS[1])
        assertEquals(3, f.fired.size)
        // Bounded: the job and on-demand renewal cover the rest of the window.
        f.done!!(true)
        f.clock.advance(60 * MINUTE)
        assertEquals(3, f.fired.size)
        assertTrue(f.clock.idle())
    }

    @Test fun aFinalResultOrAReplacedWindowIsNotRetried() {
        val final = TimerFixture()
        final.timer.reconcile(mapOf(INSTANCE to GENERATION))
        final.clock.advance(45 * MINUTE)
        final.done!!(false)
        final.clock.advance(60 * MINUTE)
        assertEquals(1, final.fired.size)
        assertFalse(final.logs.any { it.startsWith("TIMER_RETRY_SCHEDULED") })

        val replaced = TimerFixture()
        replaced.timer.reconcile(mapOf(INSTANCE to GENERATION))
        replaced.clock.advance(45 * MINUTE)
        replaced.timer.reconcile(mapOf(INSTANCE to RENEWED))
        replaced.done!!(true)
        replaced.clock.advance(ABEMA_RENEWAL_TIMER_RETRY_MS.sum())
        assertEquals(listOf(GENERATION), replaced.fired)
    }

    @Test fun aRetryStillWaitsForAManualApproval() {
        val f = TimerFixture()
        f.timer.reconcile(mapOf(INSTANCE to GENERATION))
        f.clock.advance(45 * MINUTE)
        f.manual = true
        f.done!!(true)
        f.clock.advance(ABEMA_RENEWAL_TIMER_RETRY_MS[0] + ABEMA_RENEWAL_TIMER_MANUAL_RECHECK_MS)
        assertEquals(1, f.fired.size)
        f.manual = false
        f.clock.advance(ABEMA_RENEWAL_TIMER_MANUAL_RECHECK_MS)
        assertEquals(2, f.fired.size)
    }

    @Test fun aRunReportsWhetherItsAttemptEndedWithoutAResult() {
        val success = RunFixture()
        success.run()
        success.finish(AbemaAutomaticRenewalResult.SUCCESS)
        success.clock.advance(500)
        assertEquals(listOf(false), success.retried)

        // Every readiness retry fails too, until the loop stops for the deadline.
        val failing = RunFixture()
        failing.run()
        repeat(4) {
            failing.finish(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE)
            failing.clock.advance(ABEMA_RENEWAL_RETRY_BACKOFF_MS.last() + 500)
        }
        failing.clock.advance(AbemaReadinessIpc.LIMIT_MS)
        assertEquals(listOf(true), failing.retried)

        // A deadline that passed with the renewal still unfinished.
        val stalled = RunFixture()
        stalled.run()
        stalled.clock.advance(AbemaReadinessIpc.LIMIT_MS + 500)
        assertEquals(listOf(true), stalled.retried)

        val interaction = RunFixture()
        interaction.run()
        interaction.finish(AbemaAutomaticRenewalResult.INTERACTION_REQUIRED)
        interaction.clock.advance(500)
        assertEquals("TIMER_RESULT result=INTERACTION_REQUIRED", interaction.logs.last())
        assertEquals(listOf(false), interaction.retried)
    }

    @Test fun resumeStartupReadinessIsRemoved() {
        listOf("net.fstab.tachiai.provider.abema.account.AbemaStartupReadiness",
            "net.fstab.tachiai.feature.connections.StartupAccountReadiness").forEach {
            assertThrows(ClassNotFoundException::class.java) { Class.forName(it) }
        }
    }
}
