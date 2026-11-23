package net.fstab.tachiai.provider.abema.account

import java.io.IOException
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import net.fstab.tachiai.platform.network.ProviderTransportFailure
import net.fstab.tachiai.platform.network.ProviderTransportPhase

class AbemaRenewalJobRunTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun one(): Boolean { val task = tasks.removeFirstOrNull() ?: return false; task.run(); return true }
    }
    private class Storage : AbemaRenewalJobStorage {
        var bytes: ByteArray? = null
        var fail = false
        override fun read() = bytes?.clone()
        override fun write(bytes: ByteArray) { if (fail) throw IOException("Fixture write refused"); this.bytes = bytes.clone() }
    }
    private class Fixture {
        val storage = Storage()
        var ids = 1
        val ledger = AbemaRenewalJobLedger(storage) { "00000000-0000-0000-0000-${(ids++).toString().padStart(12, '0')}" }
            .also { it.recoverCold() }
        val generation = AbemaRenewalGeneration(1, 0, 10_000)
        val entry = checkNotNull(ledger.reconcile("00000000-0000-0000-0000-000000000001", generation))
        val worker = Queue()
        val main = ArrayDeque<() -> Unit>()
        val network = Any()
        var elapsed = 1_000L
        var wall = 10_000L
        var current = true
        var preflight: AbemaRenewalJobPreflight? = AbemaRenewalJobPreflight(
            AbemaReadinessTarget(entry.key.instance, "a".repeat(64)), generation)
        var inspectHook: () -> Unit = {}
        var reserveHook: () -> Unit = {}
        var reservation: AbemaRenewalReservation? = null
        var starts = 0
        var cancels = 0
        var reservations = 0
        var finishes = 0
        var refreshes = 0
        var outcome: AbemaRenewalOperationOutcome? = null
        var notify: ((AbemaRenewalOperationOutcome) -> Unit)? = null
        var posting: ((() -> Unit) -> Boolean) = { main.add(it); true }
        val logs = mutableListOf<String>()
        // A joined renewal's RAM evidence (#635 step 4); null means no same-scope probe.
        var evidence: AbemaRenewalReadinessCoordinator.Evidence? = null
        var probes = 0
        val delayed = ArrayDeque<() -> Unit>()
        val lease = AbemaRenewalOwnerLease({
            assertEquals(AbemaRenewalJobDisposition.RUNNING, ledger.snapshot().single().disposition)
            starts++; true
        }, { cancels++ }, { outcome })
        val run = AbemaRenewalJobRun(entry.key, network, 91_000, ledger, worker, { posting(it) },
            { elapsed }, { wall }, { current }, { inspectHook(); preflight },
            { _, selected, deadline, _, completed ->
                assertSame(network, selected); assertEquals(91_000L, deadline)
                reservations++; notify = completed; reserveHook()
                reservation ?: AbemaRenewalReservation.Owned(lease)
            }, { refreshes++ }, { finishes++ }, { logs.add(it) },
            join = { preflight ->
                assertSame(this.preflight, preflight)
                evidence?.let { { probes++; checkNotNull(evidence) } }
            }, delay = { ms, action -> assertEquals(500L, ms); delayed.add(action); true })
        fun complete(value: AbemaRenewalOperationOutcome) { outcome = value; checkNotNull(notify)(value) }
        fun pump() {
            repeat(100) {
                val worked = worker.one()
                val callback = main.removeFirstOrNull()
                callback?.invoke()
                if (!worked && callback == null) return
            }
            error("Fixture did not settle")
        }
        fun disposition() = ledger.snapshot().single().disposition
        fun joining(): Fixture {
            reservation = AbemaRenewalReservation.Observed(AbemaRenewalReadinessCoordinator.Status.WAIT)
            evidence = AbemaRenewalReadinessCoordinator.Evidence(AbemaRenewalReadinessCoordinator.Completion.PENDING)
            assertTrue(run.start()); pump()
            return this
        }
        fun poll() { checkNotNull(delayed.removeFirstOrNull()).invoke(); pump() }
    }
    companion object {
        private fun transient() = AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, true,
            AbemaRenewalDiagnostic(AbemaRenewalDiagnostic.Phase.ROUTE, AbemaRenewalDiagnostic.Reason.NETWORK_ERROR,
                transport = ProviderTransportFailure.DNS, transportPhase = ProviderTransportPhase.DNS,
                cleanup = AbemaRenewalDiagnostic.Cleanup.CONFIRMED))
    }

    @Test fun durableConsumptionPrecedesActivationWithTheOriginalNetworkAndDeadline() {
        val f = Fixture(); assertTrue(f.run.start()); f.pump()
        assertEquals(1, f.starts); assertEquals(1, f.reservations); assertEquals(0, f.finishes)
        f.complete(AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.SUCCESS, true)); f.pump()
        assertEquals(AbemaRenewalJobDisposition.COMPLETED, f.disposition())
        assertEquals(1, f.finishes); assertEquals(1, f.refreshes)
    }

    @Test fun stopBeforeWorkerAdmissionMakesNoReservationOrProviderAttempt() {
        val f = Fixture(); f.run.start(); f.run.stop(); f.pump()
        assertEquals(0, f.starts); assertEquals(0, f.reservations)
        assertEquals(AbemaRenewalJobDisposition.DEFERRED_STOP, f.disposition())
        assertEquals(0, f.ledger.snapshot().single().attempts); assertEquals(1, f.finishes)
    }

    @Test fun stopDuringReservationCancelsTheReturnedOwnerBeforeConsumption() {
        val f = Fixture(); f.reserveHook = { f.run.stop() }
        f.run.start(); f.pump()
        assertEquals(1, f.cancels); assertEquals(0, f.starts)
        assertEquals(0, f.ledger.snapshot().single().attempts)
        assertEquals(AbemaRenewalJobDisposition.DEFERRED_STOP, f.disposition())
    }

    @Test fun observersNeverConsumeAnAttemptOrCancelTheObservedOperation() {
        val f = Fixture(); f.reservation = AbemaRenewalReservation.Observed(AbemaRenewalReadinessCoordinator.Status.WAIT)
        f.run.start(); f.pump(); f.run.stop(); f.pump()
        assertEquals(0, f.cancels); assertEquals(0, f.starts)
        assertEquals(0, f.ledger.snapshot().single().attempts)
        assertEquals(AbemaRenewalJobDisposition.DEFERRED_FAILURE, f.disposition())
    }

    @Test fun aMutationDuringProtectedInspectionCannotDispatchOrConsume() {
        val f = Fixture(); f.inspectHook = { f.current = false }
        f.run.start(); f.pump()
        assertEquals(0, f.reservations); assertEquals(0, f.starts)
        assertEquals(AbemaRenewalJobDisposition.DEFERRED_STOP, f.disposition())
    }

    @Test fun failedDurableConsumeCancelsTheOwnerWithoutStartingProviderWork() {
        val f = Fixture(); f.reserveHook = { f.storage.fail = true }
        f.run.start(); f.pump()
        assertEquals(1, f.cancels); assertEquals(0, f.starts); assertEquals(1, f.finishes)
        f.storage.fail = false
        val recovered = AbemaRenewalJobLedger(f.storage).also { it.recoverCold() }
        assertEquals(AbemaRenewalJobDisposition.DUE, recovered.snapshot().single().disposition)
    }

    @Test fun networkRetirementUsesOwnedCancellationAndDoesNotRequestPlatformRetry() {
        val f = Fixture(); f.run.start(); f.pump(); f.run.stop(); f.pump()
        assertEquals(1, f.cancels); assertEquals(1, f.finishes)
        assertEquals(AbemaRenewalJobDisposition.DEFERRED_STOP, f.disposition())
        f.complete(AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.CANCELLED, false)); f.pump()
        assertEquals(1, f.finishes); assertFalse(f.ledger.snapshot().single().pending)
    }

    @Test fun stopAfterFailureReceiptButBeforeItsWorkerCallbackCannotCreateARetry() {
        val f = Fixture(); f.run.start(); f.pump()
        f.complete(transient()); f.run.stop(); f.pump()
        assertEquals(1, f.cancels); assertFalse(f.ledger.snapshot().single().pending)
        assertEquals(1, f.finishes)
    }

    @Test fun stopCanWithdrawAComputedRetryBeforePlatformCompletionButCannotTouchALaterEra() {
        val f = Fixture(); f.run.start(); f.pump(); f.complete(transient())
        assertTrue(f.worker.one()) // Retry persisted; platform completion still queued.
        val retry = f.ledger.snapshot().single()
        assertEquals(AbemaRenewalJobDisposition.RETRY, retry.disposition)
        assertNotEquals(f.entry.key.schedule, retry.key.schedule)
        f.run.stop(); f.pump()
        assertEquals(AbemaRenewalJobDisposition.DEFERRED_STOP, f.disposition())
        val next = checkNotNull(f.ledger.reconcile(f.entry.key.instance, AbemaRenewalGeneration(2, 11_000, 20_000)))
        f.run.stop(); f.pump()
        assertEquals(next, f.ledger.snapshot().single())
    }

    @Test fun aDeadlineStopOfAnEarlyAttemptStillFallsBackToNaturalExpiry() {
        // The job deadline and the operation deadline share one instant; the
        // job's stop runs first, then the cancelled operation's outcome arrives.
        val f = Fixture(); f.wall = 9_000
        f.run.start(); f.pump()
        assertEquals(1, f.ledger.snapshot().single().earlyAttempts)
        f.run.stop(); f.pump()
        assertEquals(1, f.cancels); assertEquals(1, f.finishes)
        assertEquals(AbemaRenewalJobDisposition.RUNNING, f.disposition())
        f.complete(AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.CANCELLED, true)); f.pump()
        val next = f.ledger.snapshot().single()
        assertEquals(AbemaRenewalJobDisposition.RETRY, next.disposition)
        assertEquals(f.generation.expiresWallMs, next.dueWallMs)
        assertEquals(1, f.finishes); assertEquals(1, f.refreshes)
        assertEquals(listOf("JOB_STOPPED early=true", "JOB_SETTLED outcome=CANCELLED early=true disposition=RETRY"), f.logs)
    }

    @Test fun stopAfterAnEarlyFallbackWasComputedKeepsTheNaturalExpiryAttempt() {
        val f = Fixture(); f.wall = 9_000
        f.run.start(); f.pump()
        f.complete(AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, true))
        assertTrue(f.worker.one()) // Fallback persisted; platform completion still queued.
        f.run.stop(); f.pump()
        assertEquals(AbemaRenewalJobDisposition.RETRY, f.disposition())
        assertEquals(f.generation.expiresWallMs, f.ledger.snapshot().single().dueWallMs)
    }

    @Test fun aNaturalExpiryStopStillWithdrawsWithoutRetry() {
        val f = Fixture(); f.run.start(); f.pump(); f.run.stop(); f.pump()
        assertEquals(AbemaRenewalJobDisposition.DEFERRED_STOP, f.disposition())
        assertEquals(listOf("JOB_STOPPED early=false"), f.logs)
    }

    @Test fun committedSuccessBeforeStopRemainsAcceptedWithoutInventingAnExtraAttempt() {
        val f = Fixture(); f.run.start(); f.pump()
        f.complete(AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.SUCCESS, true))
        f.run.stop(); f.pump()
        assertEquals(AbemaRenewalJobDisposition.COMPLETED, f.disposition())
        assertEquals(1, f.ledger.snapshot().single().attempts); assertEquals(1, f.finishes)
    }

    @Test fun generationRecheckAfterCommittedSuccessCannotRewriteTheActualSuccess() {
        val f = Fixture(); val registry = AbemaRenewalJobOwners<AbemaRenewalJobRun<Any>>()
        registry.register(f.entry.key, f.run); registry.attach(f.entry.key, f.run, checkNotNull(f.preflight))
        f.run.start(); f.pump()
        f.complete(AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.SUCCESS, true))
        registry.reconcile(setOf(f.entry.key.instance), {
            AbemaRenewalJobPreflight(checkNotNull(f.preflight).target, AbemaRenewalGeneration(2, 11_000, 20_000))
        }) { it.stop() }
        f.pump()
        assertEquals(AbemaRenewalJobDisposition.COMPLETED, f.disposition()); assertEquals(1, f.finishes)
    }

    @Test fun cleanupUnknownTlsAndUnclassifiedFailuresCannotScheduleAutomaticRetries() {
        val tls = AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, true,
            AbemaRenewalDiagnostic(AbemaRenewalDiagnostic.Phase.ROUTE, AbemaRenewalDiagnostic.Reason.TLS_REFUSED,
                transport = ProviderTransportFailure.TLS, transportPhase = ProviderTransportPhase.TLS))
        for (value in listOf(tls, AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, false),
            AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, true))) {
            val f = Fixture(); f.run.start(); f.pump(); f.complete(value); f.pump()
            assertFalse(f.ledger.snapshot().single().pending)
            assertEquals(1, f.finishes)
        }
    }

    @Test fun partialOrRejectedCompletionPostingStillEndsThePlatformInvocationOnlyOnce() {
        for (throws in listOf(false, true)) {
            val f = Fixture(); f.posting = { action -> f.main.add(action); if (throws) throw IOException("Fixture post refused") else false }
            f.run.start(); f.pump(); f.complete(AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.SUCCESS, true)); f.pump()
            assertEquals(1, f.finishes); assertEquals(AbemaRenewalJobDisposition.COMPLETED, f.disposition())
        }
    }

    @Test fun aJobJoinsSameScopeWorkInFlightAndAdoptsItsSuccessWithoutASecondLaunch() {
        val f = Fixture().joining()
        assertEquals(AbemaRenewalJobDisposition.RUNNING, f.disposition())
        assertEquals(1, f.ledger.snapshot().single().attempts)
        assertEquals(1, f.reservations); assertEquals(0, f.starts); assertEquals(0, f.cancels); assertEquals(0, f.finishes)
        f.poll() // Still in flight.
        assertEquals(AbemaRenewalJobDisposition.RUNNING, f.disposition()); assertEquals(1, f.delayed.size)
        f.evidence = AbemaRenewalReadinessCoordinator.Evidence(AbemaRenewalReadinessCoordinator.Completion.READY)
        f.poll()
        assertEquals(AbemaRenewalJobDisposition.COMPLETED, f.disposition())
        assertEquals(1, f.reservations); assertEquals(0, f.starts); assertEquals(0, f.cancels)
        assertEquals(1, f.finishes); assertEquals(1, f.refreshes); assertTrue(f.delayed.isEmpty())
        assertEquals(listOf("JOB_JOINED early=false", "JOB_SETTLED outcome=SUCCESS early=false disposition=COMPLETED"), f.logs)
    }

    @Test fun aJoinedTemporaryFailureFollowsTheJobsOwnLedgerRetryPolicy() {
        val f = Fixture().joining()
        f.evidence = AbemaRenewalReadinessCoordinator.Evidence(AbemaRenewalReadinessCoordinator.Completion.TEMPORARY_FAILURE,
            transient().diagnostic)
        f.poll()
        val retry = f.ledger.snapshot().single()
        assertEquals(AbemaRenewalJobDisposition.RETRY, retry.disposition)
        assertEquals(f.wall + 120_000, retry.dueWallMs)
        assertEquals(1, f.reservations); assertEquals(0, f.starts); assertEquals(1, f.finishes)
        // Without retryable transport evidence the job defers, as for its own attempt.
        val g = Fixture().joining()
        g.evidence = AbemaRenewalReadinessCoordinator.Evidence(AbemaRenewalReadinessCoordinator.Completion.TEMPORARY_FAILURE)
        g.poll()
        assertEquals(AbemaRenewalJobDisposition.DEFERRED_FAILURE, g.disposition())
    }

    @Test fun joinedInteractionStorageCleanupAndRetiredResultsKeepTheirExistingDispositions() {
        for ((completion, expected) in listOf(
            AbemaRenewalReadinessCoordinator.Completion.INTERACTION_REQUIRED to AbemaRenewalJobDisposition.DEFERRED_INTERACTION,
            AbemaRenewalReadinessCoordinator.Completion.STORAGE_FAILURE to AbemaRenewalJobDisposition.DEFERRED_STORAGE,
            AbemaRenewalReadinessCoordinator.Completion.CLEANUP_UNCONFIRMED to AbemaRenewalJobDisposition.DEFERRED_CLEANUP,
            AbemaRenewalReadinessCoordinator.Completion.FAILURE to AbemaRenewalJobDisposition.DEFERRED_FAILURE,
            AbemaRenewalReadinessCoordinator.Completion.ABSENT to AbemaRenewalJobDisposition.DEFERRED_STOP)) {
            val f = Fixture().joining()
            f.evidence = AbemaRenewalReadinessCoordinator.Evidence(completion); f.poll()
            assertEquals(completion.name, expected, f.disposition())
            assertEquals(0, f.cancels); assertEquals(1, f.finishes)
        }
    }

    @Test fun otherScopesOtherInstancesAndRememberedResultsStillDeferWithoutJoining() {
        // join() finds no same-scope probe: another instance holds the slot, or the scope changed.
        val other = Fixture(); other.reservation = AbemaRenewalReservation.Observed(AbemaRenewalReadinessCoordinator.Status.WAIT)
        other.run.start(); other.pump()
        // A same-instance probe that is not in flight is not this job's renewal either.
        val absent = Fixture(); absent.evidence = AbemaRenewalReadinessCoordinator.Evidence(AbemaRenewalReadinessCoordinator.Completion.ABSENT)
        absent.reservation = AbemaRenewalReservation.Observed(AbemaRenewalReadinessCoordinator.Status.WAIT)
        absent.run.start(); absent.pump()
        // A remembered terminal status is never joined.
        val remembered = Fixture(); remembered.evidence = AbemaRenewalReadinessCoordinator.Evidence(AbemaRenewalReadinessCoordinator.Completion.PENDING)
        remembered.reservation = AbemaRenewalReservation.Observed(AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE)
        remembered.run.start(); remembered.pump()
        for (f in listOf(other, absent, remembered)) {
            assertEquals(AbemaRenewalJobDisposition.DEFERRED_FAILURE, f.disposition())
            assertEquals(0, f.ledger.snapshot().single().attempts); assertEquals(0, f.cancels)
            assertTrue(f.delayed.isEmpty()); assertTrue(f.logs.isEmpty()); assertEquals(1, f.finishes)
        }
        assertEquals(0, remembered.probes)
    }

    @Test fun joinedWorkOutlastingTheJobDeadlineEndsTheJobWithoutCancellingIt() {
        // The deadline poll and the deadline Stop each end the job cleanly.
        val polled = Fixture().joining(); polled.elapsed = 91_000; polled.poll()
        val stopped = Fixture().joining(); stopped.run.stop(); stopped.pump()
        for (f in listOf(polled, stopped)) {
            assertEquals(AbemaRenewalJobDisposition.DEFERRED_STOP, f.disposition())
            assertEquals(0, f.cancels); assertEquals(0, f.starts); assertEquals(1, f.finishes)
        }
        assertEquals(listOf("JOB_JOINED early=false", "JOB_STOPPED early=false",
            "JOB_SETTLED outcome=CANCELLED early=false disposition=DEFERRED_STOP"), stopped.logs)
        // An early joined attempt still falls back to natural expiry.
        val early = Fixture(); early.wall = 9_000; early.joining()
        assertEquals(1, early.ledger.snapshot().single().earlyAttempts)
        early.run.stop(); early.pump()
        val next = early.ledger.snapshot().single()
        assertEquals(AbemaRenewalJobDisposition.RETRY, next.disposition)
        assertEquals(early.generation.expiresWallMs, next.dueWallMs); assertEquals(0, early.cancels)
    }

    @Test fun anEndedJobNeitherReusesItsNetworkNorObservesTheJoinedWorkAgain() {
        val f = Fixture().joining()
        f.run.stop(); f.pump()
        val probes = f.probes
        f.evidence = AbemaRenewalReadinessCoordinator.Evidence(AbemaRenewalReadinessCoordinator.Completion.READY)
        f.poll() // The poll armed before Stop.
        assertEquals(probes, f.probes); assertEquals(1, f.reservations); assertEquals(0, f.starts)
        assertEquals(AbemaRenewalJobDisposition.DEFERRED_STOP, f.disposition())
        assertTrue(f.delayed.isEmpty()); assertEquals(1, f.finishes)
    }
}
