package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AbemaRenewalRetryLoopTest {
    private class Work(val instance: String, val scope: String, val deadline: Long)
    private class Operation(val completed: (AbemaRenewalOperationOutcome) -> Unit) : AbemaRenewalOwnedOperation {
        var starts = 0
        override fun start() { starts++ }
        override fun cancel() = completed(AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.CANCELLED, true))
    }
    private class Fixture(val callerDeadline: Long = 1_000L + AbemaReadinessIpc.LIMIT_MS) {
        var elapsed = 1_000L
        val tickets = AtomicInteger()
        val coordinator = AbemaRenewalReadinessCoordinator<Work>({ elapsed },
            { tickets.incrementAndGet().toString(16).padStart(32, '0') })
        val operations = ConcurrentHashMap<String, Operation>()
        val queue = ArrayDeque<() -> Unit>()
        val constructed = mutableListOf<Operation>()
        val logs = mutableListOf<String>()
        lateinit var loop: AbemaRenewalRetryLoop<Work, Operation>
        val admission = AbemaRenewalOwnedAdmission(coordinator, operations,
            validate = { ticket, _ -> coordinator.current(ticket) }, dispatch = { queue.add(it); true },
            elapsed = { elapsed }, schedulePending = { _, _ -> true },
            authoritative = { work, outcome, ownerCancelled -> loop.completed(work, outcome, ownerCancelled) })
        init {
            loop = AbemaRenewalRetryLoop(coordinator, admission, { it.instance }, { it.scope }, { elapsed }, logs::add)
        }
        // One readiness poll; an owned reservation is activated as the service does.
        fun poll(retry: Boolean = true, scope: String = "account", instance: String = "instance",
            deadline: Long = callerDeadline): AbemaRenewalReadinessCoordinator.Status =
            when (val reservation = loop.reserve(instance, scope, Work(instance, scope, deadline), deadline, { true },
                construct = { _, _, done -> Operation(done).also(constructed::add) }, retry = retry)) {
                is AbemaRenewalReservation.Owned -> {
                    assertTrue(reservation.lease.activate()); pump()
                    AbemaRenewalReadinessCoordinator.Status.WAIT
                }
                is AbemaRenewalReservation.Observed -> reservation.status
                is AbemaRenewalReservation.NoWork -> reservation.status
                AbemaRenewalReservation.Deferred -> error("Deferred")
            }
        fun pump() { repeat(20) { queue.removeFirstOrNull()?.invoke() ?: return }; error("Fixture did not settle") }
        fun finish(outcome: AbemaRenewalOperationOutcome) = constructed.last().completed(outcome)
        fun fail() = finish(temporary())
    }
    companion object {
        private fun temporary(cleanup: Boolean = true, diagnostic: AbemaRenewalDiagnostic = AbemaRenewalDiagnostic(),
            producer: AbemaRenewalDiagnostic? = null) =
            AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, cleanup, diagnostic, producer)
        private fun result(result: AbemaAutomaticRenewalResult) = AbemaRenewalOperationOutcome(result, true)
        private val WAIT = AbemaRenewalReadinessCoordinator.Status.WAIT
        private val TEMPORARY = AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE
    }

    @Test fun temporaryFailureRelaunchesAfterBackoffAndTheCallerReceivesReady() {
        val f = Fixture()
        assertEquals(WAIT, f.poll()); assertEquals(1, f.constructed.single().starts)
        f.fail()
        // Polling callers keep seeing WAIT during the backoff.
        assertEquals(WAIT, f.poll()); assertEquals(1, f.constructed.size)
        f.elapsed += 2_000
        assertEquals(WAIT, f.poll()); assertEquals(2, f.constructed.size); assertEquals(1, f.constructed.last().starts)
        assertEquals(listOf("RETRY attempt=1 reason=TEMPORARY_FAILURE"), f.logs)
        f.finish(result(AbemaAutomaticRenewalResult.SUCCESS))
        assertEquals(AbemaRenewalReadinessCoordinator.Status.READY, f.poll())
        assertEquals(2, f.constructed.size)
    }

    @Test fun backoffFollowsTheFakeClockAndExhaustedRetriesAnswerTemporaryFailure() {
        val f = Fixture()
        f.poll(); f.fail()
        for ((attempt, backoff) in listOf(2_000L, 5_000L, 15_000L).withIndex()) {
            f.elapsed += backoff - 1
            assertEquals(WAIT, f.poll()); assertEquals(attempt + 1, f.constructed.size)
            f.elapsed += 1
            assertEquals(WAIT, f.poll()); assertEquals(attempt + 2, f.constructed.size)
            f.fail()
        }
        assertEquals(TEMPORARY, f.poll())
        // Ordinary callers keep the brief replay after the loop is exhausted.
        f.elapsed += 9_999
        assertEquals(TEMPORARY, f.poll())
        assertEquals(4, f.constructed.size)
        assertEquals((1..3).map { "RETRY attempt=$it reason=TEMPORARY_FAILURE" }, f.logs)
    }

    @Test fun nonCallerCancellationIsRetriedButTheCallersOwnCancellationIsNot() {
        val f = Fixture()
        f.poll(); f.finish(result(AbemaAutomaticRenewalResult.CANCELLED))
        assertEquals(WAIT, f.poll()); f.elapsed += 2_000; f.poll()
        assertEquals(listOf("RETRY attempt=1 reason=CANCELLED"), f.logs)

        val g = Fixture()
        val lease = (g.loop.reserve("instance", "account", Work("instance", "account", g.callerDeadline), g.callerDeadline,
            { true }, construct = { _, _, done -> Operation(done).also(g.constructed::add) }, retry = true)
            as AbemaRenewalReservation.Owned).lease
        lease.activate(); g.pump(); lease.cancel()
        // The owner's cancellation is forgotten rather than replayed or retried:
        // the next ordinary launch starts at once with no RETRY.
        assertEquals(WAIT, g.poll()); assertEquals(2, g.constructed.size)
        assertTrue(g.logs.isEmpty())
    }

    @Test fun finalInteractionStorageUnconfirmedClockAndOccupiedBrowserResultsAreNeverRetried() {
        val admission = AbemaRenewalDiagnostic(AbemaRenewalDiagnostic.Phase.ADMISSION, AbemaRenewalDiagnostic.Reason.REFUSED)
        val clock = AbemaRenewalDiagnostic(AbemaRenewalDiagnostic.Phase.LIFETIME, AbemaRenewalDiagnostic.Reason.CLOCK_REFUSED)
        val cases = listOf(
            result(AbemaAutomaticRenewalResult.INTERACTION_REQUIRED) to AbemaRenewalReadinessCoordinator.Status.INTERACTION_REQUIRED,
            result(AbemaAutomaticRenewalResult.STORAGE_FAILURE) to AbemaRenewalReadinessCoordinator.Status.STORAGE_FAILURE,
            temporary(cleanup = false) to TEMPORARY,
            temporary(diagnostic = clock) to TEMPORARY,
            // A manual approval holding the approval browser refuses its admission.
            temporary(diagnostic = admission, producer = admission) to TEMPORARY,
        )
        for ((outcome, answer) in cases) {
            val f = Fixture()
            f.poll(); f.finish(outcome)
            assertEquals(answer, f.poll())
            f.elapsed += 2_000
            assertEquals(answer, f.poll())
            assertEquals(1, f.constructed.size)
            assertTrue(f.logs.isEmpty())
        }
    }

    @Test fun finalCoordinatorFailureIsNotRetriedEvenWithARetryableReceipt() {
        val f = Fixture()
        f.poll(); f.coordinator.invalidate("instance"); f.finish(result(AbemaAutomaticRenewalResult.CANCELLED))
        assertEquals(AbemaRenewalReadinessCoordinator.Status.FAILURE, f.poll())
        f.elapsed += 2_000
        assertEquals(AbemaRenewalReadinessCoordinator.Status.FAILURE, f.poll())
        assertEquals(1, f.constructed.size); assertTrue(f.logs.isEmpty())
    }

    @Test fun retryThatCannotFitTheCallersDeadlineAnswersTemporaryFailureWithoutExtendingIt() {
        val f = Fixture(callerDeadline = 1_000L + ABEMA_RENEWAL_RETRY_MINIMUM_ATTEMPT_MS + 1_999L)
        f.poll(); f.fail()
        assertEquals(TEMPORARY, f.poll())
        // A caller with a later deadline still retries the same scope.
        f.elapsed += 2_000
        assertEquals(WAIT, f.poll(deadline = f.elapsed + AbemaReadinessIpc.LIMIT_MS))
        assertEquals(2, f.constructed.size)
    }

    @Test fun retryWaitsForOtherWorkOnTheDeviceInsteadOfSupersedingIt() {
        val f = Fixture()
        f.poll(); f.fail()
        // Another instance's ordinary renewal takes the device's one slot.
        assertEquals(WAIT, f.poll(instance = "other", scope = "other"))
        val other = f.constructed.last()
        f.elapsed += 2_000
        assertEquals(WAIT, f.poll()); assertEquals(2, f.constructed.size)
        assertTrue(f.coordinator.evidence("other") { true }.completion == AbemaRenewalReadinessCoordinator.Completion.PENDING)
        other.completed(result(AbemaAutomaticRenewalResult.SUCCESS))
        assertEquals(WAIT, f.poll()); assertEquals(3, f.constructed.size)
        assertEquals(listOf("RETRY attempt=1 reason=TEMPORARY_FAILURE"), f.logs)
    }

    @Test fun differentScopeAndJobCallersDoNotContinueTheLoop() {
        val f = Fixture()
        f.poll(); f.fail()
        f.elapsed += 2_000
        // The renewal job (retry = false) sees the ordinary remembered result.
        assertEquals(TEMPORARY, f.poll(retry = false))
        // A changed route pool or grant snapshot is a different scope.
        assertEquals(WAIT, f.poll(scope = "changed")); assertEquals(2, f.constructed.size)
        assertTrue(f.logs.isEmpty())
        assertEquals("AbemaRenewalRetryLoop(redacted)", f.loop.toString())
    }
}
