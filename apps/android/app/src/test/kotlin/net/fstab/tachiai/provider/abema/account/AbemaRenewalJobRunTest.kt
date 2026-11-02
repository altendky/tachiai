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
            }, { refreshes++ }, { finishes++ })
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
}
