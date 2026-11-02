package net.fstab.tachiai.provider.abema.account

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AbemaRenewalJobLedgerTest {
    private class Storage : AbemaRenewalJobStorage {
        var bytes: ByteArray? = null
        var writes = 0
        var failRead = false
        var failWrite = false
        var failAfterWrite = false
        override fun read(): ByteArray? { check(!failRead); return bytes?.clone() }
        override fun write(bytes: ByteArray) {
            writes++
            if (failWrite) throw IOException("Fixture write refused")
            this.bytes = bytes.clone()
            if (failAfterWrite) throw IOException("Fixture acknowledgement refused")
        }
    }
    private class Fixture {
        val storage = Storage()
        val ids = AtomicInteger()
        fun ledger() = AbemaRenewalJobLedger(storage) { ids.incrementAndGet().toString(16).padStart(32, '0')
            .let { "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}" } }
            .also { it.recoverCold() }
        val ledger = ledger()
        fun seed() = checkNotNull(ledger.reconcile(INSTANCE, generation))
    }
    companion object {
        private const val INSTANCE = "00000000-0000-0000-0000-000000000001"
        private const val SECOND = "00000000-0000-0000-0000-000000000002"
        private val generation = AbemaRenewalGeneration(1, 1_000, 10_000)
        private fun refused(action: () -> Unit) {
            try { action(); fail("Expected refusal") } catch (_: Exception) { }
        }
    }

    @Test fun repeatedReconcileAndEarlyDeliveryCannotConsumeOrResetAnEra() {
        val f = Fixture(); val entry = f.seed(); val writes = f.storage.writes
        assertSame(entry, f.ledger.reconcile(INSTANCE, generation))
        assertEquals(writes, f.storage.writes)
        assertNull(f.ledger.consume(entry.key, generation, 9_999))
        assertNull(f.ledger.consume(entry.key, generation.copy(generation = 2), 10_000))
        assertEquals(writes, f.storage.writes)
        val attempt = checkNotNull(f.ledger.consume(entry.key, generation, 10_000))
        assertEquals(1, attempt.number)
        assertEquals(AbemaRenewalJobDisposition.RUNNING, f.ledger.snapshot().single().disposition)
        assertNull(f.ledger.consume(entry.key, generation, 10_000))
        assertEquals(AbemaRenewalJobDisposition.RUNNING, f.ledger.reconcile(INSTANCE, generation)!!.disposition)
    }

    @Test fun everyRearmPublishesADistinctDurableInvocationEvenWhenSchedulingFails() {
        val f = Fixture(); val initial = f.seed()
        val armed = checkNotNull(f.ledger.rearm(initial.key))
        assertNotEquals(initial.key.schedule, armed.key.schedule)
        assertEquals(initial.dueWallMs, armed.dueWallMs); assertEquals(0, armed.attempts)
        var scheduleCalls = 0
        fun schedule(): Boolean {
            scheduleCalls++
            assertEquals(armed, f.ledger().snapshot().single()) // Durable before the platform side effect.
            return false
        }
        assertFalse(schedule()); assertEquals(1, scheduleCalls)
        assertTrue(f.ledger.snapshot().single().pending)
        assertNull(f.ledger.consume(initial.key, generation, 10_000))
        assertNull(f.ledger.rearm(initial.key))
        val rearmed = checkNotNull(f.ledger.rearm(armed.key))
        assertNotEquals(armed.key.schedule, rearmed.key.schedule)
        assertEquals(0, rearmed.attempts); assertEquals(initial.dueWallMs, rearmed.dueWallMs)
    }

    @Test fun crashAfterDurableConsumeDefersInsteadOfReplayingAndRecoveryIsPersisted() {
        val f = Fixture(); val entry = f.seed()
        f.ledger.consume(entry.key, generation, 10_000)
        val recovered = f.ledger()
        assertEquals(AbemaRenewalJobDisposition.DEFERRED_INTERRUPTED, recovered.snapshot().single().disposition)
        assertNull(recovered.consume(entry.key, generation, 900_000))
        assertEquals(AbemaRenewalJobDisposition.DEFERRED_INTERRUPTED,
            recovered.reconcile(INSTANCE, generation)!!.disposition)
        assertEquals(recovered.snapshot(), f.ledger().snapshot())
    }

    @Test fun confirmedTransientRetriesAreDelayedAndExhaustAfterThreeProviderAttempts() {
        val f = Fixture(); var entry = f.seed()
        for (number in 1..3) {
            val attempt = checkNotNull(f.ledger.consume(entry.key, generation, entry.dueWallMs))
            assertEquals(number, attempt.number)
            f.ledger.settle(attempt, AbemaRenewalJobDisposition.DEFERRED_FAILURE, entry.dueWallMs, retrySafe = true)
            val next = f.ledger.snapshot().single()
            if (number < 3) {
                assertEquals(AbemaRenewalJobDisposition.RETRY, next.disposition)
                assertEquals(entry.dueWallMs + if (number == 1) 120_000 else 600_000, next.dueWallMs)
                assertNull(f.ledger.consume(next.key, generation, next.dueWallMs - 1))
            } else {
                assertEquals(AbemaRenewalJobDisposition.EXHAUSTED, next.disposition)
                assertNull(f.ledger.consume(next.key, generation, Long.MAX_VALUE))
            }
            entry = next
        }
        assertEquals(entry, f.ledger.reconcile(INSTANCE, generation))
    }

    @Test fun cleanupInteractionStorageAndStopDeferralsDoNotAcquireRetryAuthority() {
        for (disposition in listOf(AbemaRenewalJobDisposition.DEFERRED_CLEANUP,
            AbemaRenewalJobDisposition.DEFERRED_INTERACTION, AbemaRenewalJobDisposition.DEFERRED_STORAGE,
            AbemaRenewalJobDisposition.DEFERRED_STOP)) {
            val f = Fixture(); val entry = f.seed()
            val attempt = checkNotNull(f.ledger.consume(entry.key, generation, 10_000))
            refused { f.ledger.settle(attempt, disposition, 10_000, retrySafe = true) }
            f.ledger.settle(attempt, disposition, 10_000)
            f.ledger.settle(attempt, AbemaRenewalJobDisposition.COMPLETED, 10_000)
            assertEquals(disposition, f.ledger().reconcile(INSTANCE, generation)!!.disposition)
        }
    }

    @Test fun stoppedBeforeDispatchCanDeferWithoutBurningAProviderAttempt() {
        val f = Fixture(); val entry = f.seed()
        f.ledger.defer(entry.key, AbemaRenewalJobDisposition.DEFERRED_STOP)
        val saved = f.ledger.snapshot().single()
        assertEquals(0, saved.attempts); assertFalse(saved.pending)
        assertNull(f.ledger.consume(entry.key, generation, Long.MAX_VALUE))
        assertEquals(saved, f.ledger().snapshot().single())
    }

    @Test fun newAcceptedGenerationRotatesEraAndIgnoresOldOutcomeAndCancellation() {
        val f = Fixture(); val old = f.seed()
        val attempt = checkNotNull(f.ledger.consume(old.key, generation, 10_000))
        val nextGeneration = AbemaRenewalGeneration(2, 11_000, 20_000)
        val next = checkNotNull(f.ledger.reconcile(INSTANCE, nextGeneration))
        assertEquals(old.key.jobId, next.key.jobId); assertNotEquals(old.key.schedule, next.key.schedule)
        f.ledger.settle(attempt, AbemaRenewalJobDisposition.DEFERRED_CLEANUP, 12_000)
        f.ledger.defer(old.key, AbemaRenewalJobDisposition.DEFERRED_STOP)
        assertEquals(next, f.ledger.snapshot().single()); assertEquals(0, next.attempts)
    }

    @Test fun forgetAndJobIdReuseDoNotLetAnOldAttemptModifyAnotherInstance() {
        val f = Fixture(); val old = f.seed()
        val attempt = checkNotNull(f.ledger.consume(old.key, generation, 10_000))
        assertNull(f.ledger.reconcile(INSTANCE, null))
        val other = checkNotNull(f.ledger.reconcile(SECOND, generation))
        assertEquals(old.key.jobId, other.key.jobId)
        f.ledger.settle(attempt, AbemaRenewalJobDisposition.COMPLETED, 10_001)
        assertEquals(other, f.ledger.snapshot().single())
    }

    @Test fun failedConsumeNeverReturnsAuthorityAndAmbiguousWriteRecoversConservatively() {
        for (afterWrite in listOf(false, true)) {
            val f = Fixture(); val entry = f.seed()
            f.storage.failWrite = !afterWrite; f.storage.failAfterWrite = afterWrite
            refused { f.ledger.consume(entry.key, generation, 10_000) }
            refused { f.ledger.snapshot() }
            f.storage.failWrite = false; f.storage.failAfterWrite = false
            assertEquals(if (afterWrite) AbemaRenewalJobDisposition.DEFERRED_INTERRUPTED else AbemaRenewalJobDisposition.DUE,
                f.ledger().snapshot().single().disposition)
        }
    }

    @Test fun failedSettlementLeavesDurableRunningMarkerForColdDeferral() {
        val f = Fixture(); val entry = f.seed()
        val attempt = checkNotNull(f.ledger.consume(entry.key, generation, 10_000))
        f.storage.failWrite = true
        refused { f.ledger.settle(attempt, AbemaRenewalJobDisposition.COMPLETED, 10_000) }
        refused { f.ledger.snapshot() }
        f.storage.failWrite = false
        assertEquals(AbemaRenewalJobDisposition.DEFERRED_INTERRUPTED, f.ledger().snapshot().single().disposition)
    }

    @Test fun malformedOrUnreadableMetadataNeverResetsToAnEmptyLedger() {
        val f = Fixture(); f.seed(); val valid = checkNotNull(f.storage.bytes).clone()
        val samples = listOf(valid.copyOf(0), valid.copyOf(valid.size - 1), valid + 0,
            ByteArray(AbemaRenewalJobLedger.MAX_BYTES + 1), valid.clone().apply { this[3] = 2 })
        for (bytes in samples) {
            f.storage.bytes = bytes
            val ledger = AbemaRenewalJobLedger(f.storage)
            refused { ledger.recoverCold() }; refused { ledger.snapshot() }
            assertArrayEquals(bytes, f.storage.bytes)
        }
        f.storage.failRead = true
        val unreadable = AbemaRenewalJobLedger(f.storage)
        refused { unreadable.recoverCold() }; refused { unreadable.snapshot() }
    }

    @Test fun publicSchedulingObjectsKeepOpaqueIdentifiersOutOfDiagnostics() {
        val f = Fixture(); val entry = f.seed()
        val attempt = checkNotNull(f.ledger.consume(entry.key, generation, 10_000))
        for (value in listOf(f.ledger, entry, entry.key, generation, attempt)) {
            assertTrue(value.toString().contains("redacted"))
            assertFalse(value.toString().contains(INSTANCE))
        }
    }
}
