package net.fstab.tachiai.provider.abema

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import org.junit.Assert.*
import org.junit.Test

class AbemaNativePreparationEvidenceTest {
    @Test fun snapshotsKeepAdmittedProgressAndPendingFlagsWithoutImplyingPlayback() {
        val evidence = AbemaNativePreparationEvidence()
        assertFalse(evidence.snapshot(true, true).observed)
        evidence.observe(AbemaNativePreparationPhase.WORKER_QUEUED, true)
        val queued = evidence.snapshot(false, false)
        assertTrue(queued.observed); assertNull(queued.workerFailure); assertFalse(queued.nativeHostCreated)
        evidence.observe(AbemaNativePreparationPhase.SOURCE_SELECT, true)
        val selecting = evidence.snapshot(true, false)
        assertTrue(selecting.sourcePending); assertFalse(selecting.licensePending)
        evidence.observe(AbemaNativePreparationPhase.HOST_CREATED, true)
        val host = evidence.snapshot(false, true)
        assertTrue(host.nativeHostCreated); assertTrue(host.licensePending); assertNull(host.workerFailure)
        assertEquals(AbemaNativePreparationPhase.WORKER_QUEUED, queued.phase)
        assertFalse(queued.sourcePending); assertFalse(queued.nativeHostCreated)
        assertEquals(AbemaNativePreparationPhase.SOURCE_SELECT, selecting.phase)
        assertFalse(selecting.nativeHostCreated)
    }

    @Test fun firstActualWorkerFailureKeepsItsPhaseWhileLaterProgressAndFailuresCannotReplaceIt() {
        val evidence = AbemaNativePreparationEvidence()
        evidence.observe(AbemaNativePreparationPhase.MANIFEST_READ, true)
        evidence.fail(IOException("private worker failure"), true)
        val first = checkNotNull(evidence.snapshot(false, false).workerFailure)
        evidence.observe(AbemaNativePreparationPhase.SOURCE_RECHECK, true)
        evidence.fail(SSLException("later failure"), true)
        val later = evidence.snapshot(true, false)
        assertSame(first, later.workerFailure)
        assertEquals(AbemaNativePreparationPhase.MANIFEST_READ, first.phase)
        assertEquals(AbemaNativePreparationCategory.IO, first.category)
        assertEquals(AbemaNativePreparationPhase.SOURCE_RECHECK, later.phase)
        assertFalse(later.closedSummary().contains("private")); assertFalse(later.closedSummary().contains("later failure"))
    }

    @Test fun lostAdmissionAndSealedLifecycleRefuseLateProgressOrExceptionCapture() {
        val evidence = AbemaNativePreparationEvidence()
        evidence.observe(AbemaNativePreparationPhase.SOURCE_SELECT, false)
        evidence.fail(IOException(), false)
        assertFalse(evidence.snapshot(false, false).observed)
        evidence.observe(AbemaNativePreparationPhase.SOURCE_SELECT, true)
        evidence.observe(AbemaNativePreparationPhase.UNOBSERVED, true)
        evidence.seal(false, false)
        evidence.observe(AbemaNativePreparationPhase.HOST_CREATED, true)
        evidence.fail(IOException(), true); evidence.seal(false, false)
        val value = evidence.snapshot(false, false)
        assertEquals(AbemaNativePreparationPhase.SOURCE_SELECT, value.phase)
        assertFalse(value.nativeHostCreated); assertNull(value.workerFailure)
    }

    @Test fun directExceptionCategoriesNeverTraverseMessagesCausesOrStacks() {
        val reads = AtomicInteger()
        val hostile = object : IOException() {
            override val message: String get() { reads.incrementAndGet(); throw AssertionError() }
            override val cause: Throwable get() { reads.incrementAndGet(); throw AssertionError() }
            override fun getStackTrace(): Array<StackTraceElement> { reads.incrementAndGet(); throw AssertionError() }
            override fun toString(): String { reads.incrementAndGet(); throw AssertionError() }
        }
        val cases = listOf(
            hostile to AbemaNativePreparationCategory.IO,
            SSLException("private") to AbemaNativePreparationCategory.SSL,
            SocketTimeoutException("private") to AbemaNativePreparationCategory.TIMEOUT,
            TimeoutException("private") to AbemaNativePreparationCategory.TIMEOUT,
            InterruptedIOException("private") to AbemaNativePreparationCategory.INTERRUPTED,
            InterruptedException("private") to AbemaNativePreparationCategory.INTERRUPTED,
            ExecutionException("private", hostile) to AbemaNativePreparationCategory.EXECUTION,
            IllegalStateException("private") to AbemaNativePreparationCategory.ILLEGAL_STATE,
            SecurityException("private") to AbemaNativePreparationCategory.SECURITY,
            IllegalArgumentException("private") to AbemaNativePreparationCategory.OTHER,
        )
        for ((error, expected) in cases) {
            val evidence = AbemaNativePreparationEvidence()
            evidence.fail(error, true)
            val value = evidence.snapshot(false, false)
            assertTrue(value.observed); assertEquals(expected, value.workerFailure?.category)
            assertFalse(value.closedSummary().contains("private")); assertFalse(value.toString().contains("private"))
        }
        assertEquals(0, reads.get())
    }

    @Test fun nativeHostCreationIsStickyButDoesNotSupplyAnExceptionOrReadinessReceipt() {
        val evidence = AbemaNativePreparationEvidence()
        evidence.observe(AbemaNativePreparationPhase.NATIVE_CREATE, true)
        assertFalse(evidence.snapshot(false, false).nativeHostCreated)
        evidence.observe(AbemaNativePreparationPhase.HOST_CREATED, true)
        evidence.observe(AbemaNativePreparationPhase.NATIVE_DISPATCH, true)
        evidence.seal(false, false)
        val snapshot = evidence.snapshot(false, false)
        assertTrue(snapshot.nativeHostCreated); assertNull(snapshot.workerFailure)
        assertEquals(AbemaNativePreparationPhase.NATIVE_DISPATCH, snapshot.phase)
        assertEquals("nativePhase=NATIVE_DISPATCH sourcePending=false licensePending=false nativeHostCreated=true " +
            "workerFailurePhase=UNOBSERVED workerFailureCategory=UNOBSERVED", snapshot.closedSummary())
    }

    @Test fun firstSealFreezesPendingWorkBeforeTeardownAndCannotBeOverwrittenByClearedFutures() {
        for ((source, license) in listOf(true to false, false to true, true to true)) {
            val evidence = AbemaNativePreparationEvidence()
            evidence.observe(AbemaNativePreparationPhase.MANIFEST_READ, true)
            evidence.seal(source, license)
            val beforeCleanup = evidence.snapshot(source, license)
            evidence.seal(false, false)
            evidence.observe(AbemaNativePreparationPhase.HOST_CREATED, true)
            evidence.fail(IOException(), true)
            val afterCleanup = evidence.snapshot(false, false)
            assertEquals(source, afterCleanup.sourcePending); assertEquals(license, afterCleanup.licensePending)
            assertEquals(beforeCleanup, afterCleanup)
        }
    }
}
