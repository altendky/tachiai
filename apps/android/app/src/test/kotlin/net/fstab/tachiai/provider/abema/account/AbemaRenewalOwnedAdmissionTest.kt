package net.fstab.tachiai.provider.abema.account

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AbemaRenewalOwnedAdmissionTest {
    private class Work(val network: Any, val deadline: Long)
    private class Operation(val completed: (AbemaRenewalOperationOutcome) -> Unit) : AbemaRenewalOwnedOperation {
        var starts = 0
        var cancels = 0
        var onStart: () -> Unit = {}
        var onCancel: () -> Unit = { completed(cancelled()) }
        override fun start() { starts++; onStart() }
        override fun cancel() { cancels++; onCancel() }
    }
    private class Fixture {
        var elapsed = 1_000L
        val tickets = AtomicInteger()
        val coordinator = AbemaRenewalReadinessCoordinator<Work>({ elapsed },
            { tickets.incrementAndGet().toString(16).padStart(32, '0') })
        val operations = ConcurrentHashMap<String, Operation>()
        val queue = ArrayDeque<() -> Unit>()
        val pendingExpiry = mutableListOf<Pair<Long, () -> Unit>>()
        val constructed = mutableListOf<Pair<Work, Operation>>()
        val notifications = mutableListOf<AbemaRenewalOperationOutcome>()
        val acceptedReceipts = mutableListOf<AbemaRenewalOperationOutcome>()
        val admitted = AtomicBoolean(true)
        var admissionGate: () -> Boolean = admitted::get
        var validation: (String, Work) -> Boolean = { ticket, _ -> coordinator.current(ticket) }
        var posting: ((() -> Unit) -> Boolean) = { queue.add(it); true }
        var expiryClock: () -> Long = { elapsed }
        var scheduling: (Long, () -> Unit) -> Boolean = { deadline, action -> pendingExpiry.add(deadline to action); true }
        var construction: ((AbemaRenewalOperationOutcome) -> Unit) -> Operation = { Operation(it) }
        var receiptHook: () -> Unit = {}
        val owner = AbemaRenewalOwnedAdmission(coordinator, operations,
            validate = { ticket, work -> validation(ticket, work) }, dispatch = { posting(it) },
            elapsed = { expiryClock() }, schedulePending = { deadline, action -> scheduling(deadline, action) },
            authoritative = { _, outcome -> receiptHook(); acceptedReceipts.add(outcome) })
        fun reserve(work: Work = Work(Any(), elapsed + 90_000),
            scope: String = "account", notify: (AbemaRenewalOperationOutcome) -> Unit = notifications::add,
        ) = owner.reserve("instance", scope, work, work.deadline, { admissionGate() },
            construct = { _, captured, done -> construction(done).also { constructed.add(captured to it) } }, notify = notify)
        fun lease(result: AbemaRenewalReservation = reserve()) = (result as AbemaRenewalReservation.Owned).lease
        fun pump() { repeat(20) { queue.removeFirstOrNull()?.invoke() ?: return }; error("Owned fixture did not settle") }
        fun evidence() = coordinator.evidence("instance") { true }.completion
    }
    companion object {
        private fun cancelled() = AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.CANCELLED, true)
        private fun success() = AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.SUCCESS, true)
    }

    @Test fun reservationReturnsControlBeforeAnyDispatchAndActivationIsExactlyOnce() {
        val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
        assertTrue(f.queue.isEmpty()); assertEquals(0, operation.starts); assertNull(lease.outcome())
        assertSame(operation, f.operations.values.single())
        assertTrue(lease.activate()); assertFalse(lease.activate()); assertEquals(0, operation.starts)
        f.pump(); assertEquals(1, operation.starts)
        lease.cancel(); assertEquals(1, operation.cancels); assertTrue(lease.outcome()!!.cleanupConfirmed)
        assertTrue(f.operations.isEmpty()); assertEquals(1, f.notifications.size)
        lease.cancel(); assertEquals(1, operation.cancels)
        assertEquals("AbemaRenewalOwnerLease(redacted)", lease.toString())
    }

    @Test fun repeatedActivationCannotApplyAdmissionLossToAnAlreadyActiveOperation() {
        val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
        assertTrue(lease.activate()); f.pump(); assertEquals(1, operation.starts)
        f.admitted.set(false)
        assertFalse(lease.activate())
        assertEquals(0, operation.cancels); assertEquals(1, operation.starts)
        assertNull(lease.outcome()); assertSame(operation, f.operations.values.single())
        lease.cancel(); assertEquals(1, operation.cancels)
    }

    @Test fun redispatchedLaunchClosureCannotCancelAnAlreadyActiveOperationAfterAdmissionLoss() {
        val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
        assertTrue(lease.activate())
        val originalLaunch = f.queue.removeFirst()
        originalLaunch(); assertEquals(1, operation.starts)
        f.admitted.set(false)
        originalLaunch(); originalLaunch()
        assertEquals(0, operation.cancels); assertEquals(1, operation.starts)
        assertNull(lease.outcome()); assertSame(operation, f.operations.values.single())
        lease.cancel(); assertEquals(1, operation.cancels)
    }

    @Test fun activationLosingDuringGateEvaluationCannotCancelTheWinningActivation() {
        val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.admissionGate = {
            if (Thread.currentThread().name == "owned-losing-activation") {
                entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); false
            } else true
        }
        val worker = Executors.newSingleThreadExecutor { Thread(it, "owned-losing-activation") }
        try {
            val loser = worker.submit<Boolean> { lease.activate() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(lease.activate()); f.pump(); assertEquals(1, operation.starts)
            release.countDown(); assertFalse(loser.get(1, TimeUnit.SECONDS))
            assertEquals(0, operation.cancels); assertNull(lease.outcome())
            lease.cancel()
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun ineligibleTransitionClaimsCancellationBeforeACompetingActivationCanWin() {
        val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        operation.onCancel = {
            entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); operation.completed(cancelled())
        }
        f.admitted.set(false)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val rejected = worker.submit<Boolean> { lease.activate() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            f.admitted.set(true)
            assertFalse(lease.activate()); assertTrue(f.queue.isEmpty()); assertEquals(0, operation.starts)
            release.countDown(); assertFalse(rejected.get(1, TimeUnit.SECONDS))
            assertEquals(1, operation.cancels); assertTrue(lease.outcome()!!.cleanupConfirmed)
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun sameScopeObserverCannotReplaceFirstWorkNetworkDeadlineOrAcquireControl() {
        val f = Fixture(); val original = Work(Any(), 91_000)
        val lease = f.lease(f.reserve(original))
        val observer = f.reserve(Work(Any(), 181_000)) as AbemaRenewalReservation.Observed
        assertEquals(AbemaRenewalReadinessCoordinator.Status.WAIT, observer.status)
        assertSame(original, f.constructed.single().first)
        assertSame(original.network, f.constructed.single().first.network)
        assertEquals(91_000L, f.constructed.single().first.deadline)
        assertEquals(91_000L, f.pendingExpiry.single().first)
        assertEquals(1, f.constructed.size)
        f.elapsed = 91_000
        assertFalse(lease.activate()); assertEquals(0, f.constructed.single().second.starts)
        assertEquals(AbemaRenewalReadinessCoordinator.Completion.TEMPORARY_FAILURE, f.evidence())
    }

    @Test fun cancellingBeforeActivationConfirmsZeroResourcesAndFutureActivationCannotStart() {
        val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
        lease.cancel()
        assertEquals(0, operation.starts); assertEquals(1, operation.cancels)
        assertEquals(AbemaAutomaticRenewalResult.CANCELLED, lease.outcome()!!.result)
        assertTrue(lease.outcome()!!.cleanupConfirmed)
        assertFalse(lease.activate()); f.pump(); assertEquals(0, operation.starts)
        assertTrue(f.operations.isEmpty())
    }

    @Test fun evenInlineActivationCannotCompleteUntilCallerHasReceivedAndStoredItsLease() {
        val f = Fixture(); var stored: AbemaRenewalOwnerLease? = null
        val reservation = f.reserve(notify = { assertNotNull(stored) })
        assertTrue(f.acceptedReceipts.isEmpty()); assertTrue(f.queue.isEmpty())
        val lease = f.lease(reservation)
        stored = lease
        val operation = f.constructed.single().second
        operation.onStart = { operation.completed(success()) }
        f.posting = { it(); true }
        assertTrue(lease.activate())
        assertEquals(AbemaAutomaticRenewalResult.SUCCESS, lease.outcome()!!.result)
        assertTrue(f.operations.isEmpty())
    }

    @Test fun stopDuringConstructionReturnsNoControlAndWaitsForTheActualZeroResourceReceipt() {
        val f = Fixture()
        f.construction = { done -> f.admitted.set(false); Operation(done) }
        assertSame(AbemaRenewalReservation.Deferred, f.reserve())
        val operation = f.constructed.single().second
        assertEquals(0, operation.starts); assertEquals(1, operation.cancels)
        assertTrue(f.operations.isEmpty()); assertTrue(f.notifications.isEmpty())
    }

    @Test fun stoppedAdmissionCannotDispatchWhenProtectedValidationReturnsLate() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.validation = { _, _ -> entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); true }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<AbemaRenewalReservation> { f.reserve() }
            assertTrue(entered.await(1, TimeUnit.SECONDS)); f.admitted.set(false); release.countDown()
            assertSame(AbemaRenewalReservation.Deferred, pending.get(1, TimeUnit.SECONDS))
            assertTrue(f.constructed.isEmpty()); assertTrue(f.operations.isEmpty()); assertTrue(f.queue.isEmpty())
        } finally { release.countDown(); worker.shutdownNow() }
        val alreadyStopped = Fixture().apply { admitted.set(false) }
        assertSame(AbemaRenewalReservation.Deferred, alreadyStopped.reserve())
        assertEquals(0, alreadyStopped.tickets.get())
    }

    @Test fun stoppingAfterDispatchQueueingRetiresTheExactOperationBeforeAnyStart() {
        val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
        assertTrue(lease.activate()); f.admitted.set(false)
        f.pump()
        assertEquals(0, operation.starts); assertEquals(1, operation.cancels)
        assertTrue(lease.outcome()!!.cleanupConfirmed)
    }

    @Test fun cancelCallsActualOperationBeforeChangingItsCoordinatorCommitGate() {
        val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
        val ticket = f.operations.keys.single()
        operation.onCancel = {
            assertTrue(f.coordinator.current(ticket))
            operation.completed(cancelled())
        }
        lease.activate(); f.pump(); lease.cancel()
        assertEquals(1, operation.cancels); assertFalse(f.coordinator.current(ticket))
    }

    @Test fun staleLeaseAndLateReceiptCannotControlAConfirmedSuccessor() {
        val f = Fixture(); val first = f.lease(); val old = f.constructed.single().second
        first.cancel(); f.elapsed += 60_001
        val second = f.lease(); val successor = f.constructed.last().second
        assertFalse(first.activate()); first.cancel(); old.completed(success())
        assertSame(successor, f.operations.values.single())
        assertEquals(0, successor.starts); assertEquals(0, successor.cancels)
        assertEquals(AbemaAutomaticRenewalResult.CANCELLED, first.outcome()!!.result)
        second.activate(); f.pump(); assertEquals(1, successor.starts)
        second.cancel()
    }

    @Test fun pendingCleanupRetainsOperationAndUnknownReceiptCannotUpgradeLater() {
        val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
        operation.onCancel = {}
        lease.activate(); f.pump(); lease.cancel()
        assertNull(lease.outcome()); assertSame(operation, f.operations.values.single())
        operation.completed(AbemaRenewalOwnedAdmission.unknown())
        val first = lease.outcome()
        operation.completed(success())
        assertSame(first, lease.outcome()); assertFalse(first!!.cleanupConfirmed)
        assertSame(operation, f.operations.values.single()); assertEquals(1, f.notifications.size)
        assertEquals(AbemaRenewalReadinessCoordinator.Completion.CLEANUP_UNCONFIRMED, f.evidence())
        assertTrue(f.reserve(scope = "another-account") is AbemaRenewalReservation.Observed)
        assertEquals(1, f.constructed.size)
    }

    @Test fun rejectedOrPartiallyEnqueuedDispatchNeverReleasesWithoutActualCancellationReceipt() {
        for (throws in listOf(false, true)) {
            val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
            f.posting = { task -> f.queue.add(task); if (throws) throw IOException("private-dispatch-error") else false }
            operation.onCancel = {}
            assertFalse(lease.activate()); assertEquals(1, operation.cancels)
            assertNull(lease.outcome()); assertSame(operation, f.operations.values.single())
            f.pump(); assertEquals(0, operation.starts)
            operation.completed(cancelled())
            assertTrue(lease.outcome()!!.cleanupConfirmed); assertTrue(f.operations.isEmpty())
        }
    }

    @Test fun startOrCancelExceptionRetainsUnknownActualOwnerAndIgnoresLateSuccess() {
        for (startFails in listOf(false, true)) {
            val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
            if (startFails) {
                operation.onStart = { throw IOException("private-start-error") }
                operation.onCancel = {}
            }
            else operation.onCancel = { throw IOException("private-cancel-error") }
            if (startFails) { lease.activate(); f.pump() } else lease.cancel()
            if (startFails) {
                assertNull(lease.outcome())
                operation.completed(AbemaRenewalOwnedAdmission.unknown())
            }
            assertFalse(lease.outcome()!!.cleanupConfirmed)
            assertSame(operation, f.operations.values.single())
            operation.completed(success())
            assertFalse(lease.outcome()!!.cleanupConfirmed)
            assertEquals(AbemaRenewalReadinessCoordinator.Completion.CLEANUP_UNCONFIRMED, f.evidence())
        }
    }

    @Test fun ownerNotificationFailureCannotEraseStoredAuthoritativeReceipt() {
        val f = Fixture()
        val lease = f.lease(f.reserve(notify = { throw IOException("private-owner-notification") }))
        lease.cancel()
        assertTrue(lease.outcome()!!.cleanupConfirmed); assertTrue(f.operations.isEmpty())
        assertEquals(1, f.acceptedReceipts.size)
    }

    @Test fun ambiguousDispatchAcknowledgmentCannotRewriteAnAlreadyClosedCommittedOutcome() {
        val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
        operation.onStart = { operation.completed(success()) }
        f.posting = { it(); false }
        assertFalse(lease.activate())
        assertEquals(AbemaAutomaticRenewalResult.SUCCESS, lease.outcome()!!.result)
        assertTrue(lease.outcome()!!.cleanupConfirmed); assertTrue(f.operations.isEmpty())
        assertEquals(1, operation.starts); assertEquals(0, operation.cancels)
        assertEquals(1, f.notifications.size)
    }

    @Test fun internalNotificationFailureCannotRewriteAnActualClosedReceipt() {
        val f = Fixture(); f.receiptHook = { throw IOException("private-publication-error") }
        val lease = f.lease(); val operation = f.constructed.single().second
        lease.cancel()
        assertTrue(lease.outcome()!!.cleanupConfirmed); assertTrue(f.operations.isEmpty())
        assertEquals(AbemaRenewalReadinessCoordinator.Completion.FAILURE, f.evidence())
        assertEquals(1, operation.cancels)
    }

    @Test fun constructorFailureCreatesNoDispatchAndPrematureReceiptCannotBecomeAuthority() {
        val noOperation = Fixture().apply { construction = { throw IOException("private-constructor-error") } }
        assertSame(AbemaRenewalReservation.Deferred, noOperation.reserve())
        assertTrue(noOperation.operations.isEmpty()); assertTrue(noOperation.queue.isEmpty())
        val premature = Fixture().apply { construction = { done -> done(success()); Operation(done) } }
        assertSame(AbemaRenewalReservation.Deferred, premature.reserve())
        assertEquals(0, premature.constructed.single().second.starts)
        assertEquals(AbemaRenewalReadinessCoordinator.Completion.CLEANUP_UNCONFIRMED, premature.evidence())
        assertSame(premature.constructed.single().second, premature.operations.values.single())
    }

    @Test fun registryIdentityMismatchCannotCancelOrCompleteAnotherOperation() {
        val f = Fixture(); val lease = f.lease(); val original = f.constructed.single().second
        val ticket = f.operations.keys.single(); val other = Operation {}
        f.operations[ticket] = other
        lease.cancel(); assertFalse(lease.activate()); original.completed(success())
        assertSame(other, f.operations[ticket]); assertEquals(0, other.cancels)
        assertEquals(0, original.cancels); assertNull(lease.outcome())
    }

    @Test fun abandonedReservedLeaseExpiresAtItsCapturedDeadlineThroughActualCancellation() {
        val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
        val (deadline, expire) = f.pendingExpiry.single()
        assertEquals(91_000L, deadline); assertEquals(0, operation.starts)
        expire(); assertEquals(0, operation.cancels) // Not early, and no refreshed lifetime.
        f.elapsed = deadline
        expire()
        assertEquals(0, operation.starts); assertEquals(1, operation.cancels)
        assertTrue(lease.outcome()!!.cleanupConfirmed); assertTrue(f.operations.isEmpty())
        assertEquals(AbemaRenewalReadinessCoordinator.Completion.TEMPORARY_FAILURE, f.evidence())
        assertFalse(lease.activate()); expire(); assertEquals(1, operation.cancels)
        assertEquals(1, f.pendingExpiry.size)
    }

    @Test fun queuedActivationExpiryCannotReleaseOrStartUntilTheActualCancelReceipt() {
        val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
        operation.onCancel = {}
        assertTrue(lease.activate()); f.elapsed = 91_000
        f.pendingExpiry.single().second()
        assertEquals(1, operation.cancels); assertNull(lease.outcome())
        assertSame(operation, f.operations.values.single())
        f.pump(); assertEquals(0, operation.starts)
        operation.completed(cancelled())
        assertTrue(lease.outcome()!!.cleanupConfirmed); assertTrue(f.operations.isEmpty())
    }

    @Test fun expiryIsInertForAnActiveOperationAndForItsLaterSuccessor() {
        val f = Fixture(); val first = f.lease(); val original = f.constructed.single().second
        val oldExpiry = f.pendingExpiry.single().second
        first.activate(); f.pump(); f.elapsed = 91_000
        oldExpiry(); f.owner.retireExpiredPending()
        assertEquals(0, original.cancels); assertEquals(1, original.starts); assertNull(first.outcome())
        first.cancel(); f.elapsed += 60_001
        val second = f.lease(); val successor = f.constructed.last().second
        oldExpiry()
        assertEquals(0, successor.starts); assertEquals(0, successor.cancels)
        assertSame(successor, f.operations.values.single()); assertNull(second.outcome())
        second.cancel()
    }

    @Test fun reconciliationBeforeReserveUnwedgesAnExpiredClaimEvenWithoutTimerDelivery() {
        val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
        f.elapsed = 151_001 // Original deadline and cooldown both elapsed; timer intentionally not run.
        val observed = f.reserve() as AbemaRenewalReservation.Observed
        assertEquals(AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE, observed.status)
        assertEquals(1, operation.cancels); assertEquals(0, operation.starts)
        assertTrue(lease.outcome()!!.cleanupConfirmed); assertTrue(f.operations.isEmpty())
        f.elapsed += 60_001
        val next = f.lease()
        assertEquals(2, f.constructed.size)
        next.cancel()
    }

    @Test fun pendingSchedulerRefusalOrPartialPostingUsesActualZeroResourceReceiptOnly() {
        for (mode in 0..4) {
            val f = Fixture()
            f.scheduling = { deadline, action ->
                if (mode != 0) f.pendingExpiry.add(deadline to action)
                if (mode == 2) throw IOException("private-pending-scheduler-error")
                if (mode >= 3) { f.elapsed = deadline; action() }
                mode == 4
            }
            assertSame(AbemaRenewalReservation.Deferred, f.reserve())
            val operation = f.constructed.single().second
            assertEquals(0, operation.starts); assertEquals(1, operation.cancels)
            assertTrue(f.acceptedReceipts.single().cleanupConfirmed)
            assertTrue(f.operations.isEmpty()); assertTrue(f.notifications.isEmpty())
            f.elapsed = 91_000; f.pendingExpiry.forEach { it.second() }
            assertEquals(1, operation.cancels)
        }
        val unknown = Fixture()
        unknown.scheduling = { _, _ -> false }
        unknown.construction = { done -> Operation(done).apply { onCancel = {} } }
        assertSame(AbemaRenewalReservation.Deferred, unknown.reserve())
        val operation = unknown.constructed.single().second
        assertSame(operation, unknown.operations.values.single()); assertTrue(unknown.acceptedReceipts.isEmpty())
        operation.completed(AbemaRenewalOwnedAdmission.unknown())
        operation.completed(success())
        assertFalse(unknown.acceptedReceipts.single().cleanupConfirmed)
        assertSame(operation, unknown.operations.values.single()); assertTrue(unknown.notifications.isEmpty())
    }

    @Test fun expiryUnknownReceiptRemainsRetainedAndCannotUpgradeOnLateCompletion() {
        val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
        operation.onCancel = {}; f.elapsed = 91_000
        f.pendingExpiry.single().second(); f.owner.retireExpiredPending()
        assertEquals(1, operation.cancels); assertNull(lease.outcome())
        assertSame(operation, f.operations.values.single())
        operation.completed(AbemaRenewalOwnedAdmission.unknown())
        val unknown = lease.outcome()
        operation.completed(success()); f.pendingExpiry.single().second()
        assertSame(unknown, lease.outcome()); assertFalse(unknown!!.cleanupConfirmed)
        assertSame(operation, f.operations.values.single()); assertEquals(1, operation.cancels)
        assertEquals(AbemaRenewalReadinessCoordinator.Completion.CLEANUP_UNCONFIRMED, f.evidence())
    }

    @Test fun expiryClockEvaluationCannotCancelActivationThatWonBeforeTheExpiryClaim() {
        val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.expiryClock = {
            if (Thread.currentThread().name == "owned-expiry-clock") {
                entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS))
            }
            f.elapsed
        }
        val worker = Executors.newSingleThreadExecutor { Thread(it, "owned-expiry-clock") }
        try {
            val expired = worker.submit { f.pendingExpiry.single().second() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(lease.activate()); f.pump(); assertEquals(1, operation.starts)
            f.elapsed = 91_000; release.countDown(); expired.get(1, TimeUnit.SECONDS)
            assertEquals(0, operation.cancels); assertNull(lease.outcome())
            lease.cancel()
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun expiryClaimsPendingCancellationBeforeCompetingActivationAndDoesNotWaitUnderLock() {
        val f = Fixture(); val lease = f.lease(); val operation = f.constructed.single().second
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        operation.onCancel = {
            entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); operation.completed(cancelled())
        }
        f.elapsed = 91_000
        val worker = Executors.newSingleThreadExecutor()
        try {
            val expired = worker.submit { f.pendingExpiry.single().second() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(lease.activate()); assertEquals(0, operation.starts); assertTrue(f.queue.isEmpty())
            assertNull(lease.outcome()); assertSame(operation, f.operations.values.single())
            release.countDown(); expired.get(1, TimeUnit.SECONDS)
            assertEquals(1, operation.cancels); assertTrue(lease.outcome()!!.cleanupConfirmed)
        } finally { release.countDown(); worker.shutdownNow() }
    }
}
