package net.fstab.tachiai.provider.abema

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AbemaScheduleMetadataIpcTest {
    private val nonce = "a".repeat(32)
    private fun fields() = mapOf("nonce" to nonce, "instance" to "12345678-1234-1234-1234-123456789abc",
        "proof" to "b".repeat(64), "deadline" to 46000L)
    @Test fun closedStartRequiresOnlyTypedOwnerFieldsAndOriginalDeadline() {
        assertNotNull(AbemaScheduleMetadataIpc.decodeStart(fields(), 1000))
        for (bad in listOf(fields() + ("url" to "https://other.invalid"), fields() + ("parent" to "series-A"),
            fields() + ("deadline" to 46001L), fields() + ("deadline" to 1000L), fields() + ("deadline" to 46000),
            fields() + ("proof" to "private-invalid"), fields() + ("instance" to "other"), fields() + ("nonce" to "other")))
            assertNull(AbemaScheduleMetadataIpc.decodeStart(bad, 1000))
        assertNull(AbemaSeriesMetadataIpc.decodeStart(fields(), 1000))
    }
    @Test fun resultRejectsRawDTOExtraFieldsFailurePayloadAndMalformedProjection() {
        val good = mapOf("nonce" to nonce, "outcome" to "OK", "snapshot" to "{\"slots\":[],\"truncated\":false}")
        assertNotNull(AbemaScheduleMetadataIpc.decodeResult(good))
        for (bad in listOf(good + ("extra" to "private"), good + ("outcome" to "TEMPORARY"), good + ("snapshot" to 1),
            good + ("snapshot" to "{\"slots\":[],\"channels\":[],\"availableDates\":[]}"),
            good + ("snapshot" to "{\"slots\":[],\"truncated\":true}"), good + ("outcome" to "PRIVATE_PROVIDER_MESSAGE")))
            assertNull(AbemaScheduleMetadataIpc.decodeResult(bad))
        assertNotNull(AbemaScheduleMetadataIpc.decodeResult(mapOf("nonce" to nonce, "outcome" to "TEMPORARY")))
    }
    @Test fun scheduleClientRejectsDuplicateLateWrongNonceMissingSnapshotAndExpiredResults() {
        var now = 1000L; var foreground = true
        val start = AbemaScheduleMetadataIpc.decodeStart(fields(), now)!!
        val snapshot = AbemaScheduleSnapshot(emptyList())
        val operation = AbemaScheduleClientOperation(start, { now }) { foreground }
        assertFalse(operation.accept(AbemaScheduleMetadataIpc.Result(nonce, "OK")))
        assertFalse(operation.accept(AbemaScheduleMetadataIpc.Result("c".repeat(32), "OK", snapshot)))
        assertTrue(operation.accept(AbemaScheduleMetadataIpc.Result(nonce, "OK", snapshot)))
        assertFalse(operation.accept(AbemaScheduleMetadataIpc.Result(nonce, "OK", snapshot)))
        foreground = false; assertNull(operation.result()); foreground = true
        operation.cancel(); assertNull(operation.result()); assertFalse(operation.accept(AbemaScheduleMetadataIpc.Result(nonce, "OK", snapshot)))
        val expired = AbemaScheduleClientOperation(start, { now }) { true }; now = start.deadline
        assertFalse(expired.accept(AbemaScheduleMetadataIpc.Result(nonce, "OK", snapshot)))
    }
    @Test fun heldWorkerOwnerReadCannotPublishAfterCancelClientCloseForegroundLossOrExpiry() {
        for (mode in 0..3) {
            val now = AtomicLong(1000); val open = AtomicBoolean(true); val foreground = AtomicBoolean(true)
            val start = AbemaScheduleMetadataIpc.decodeStart(fields(), now.get())!!
            val operation = AbemaScheduleClientOperation(start, now::get, foreground::get)
            assertTrue(operation.accept(AbemaScheduleMetadataIpc.Result(nonce, "OK", AbemaScheduleSnapshot(emptyList()))))
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val result = AtomicReference<AbemaScheduleMetadataIpc.Result?>()
            val worker = Thread { result.set(operation.workerResult({ entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true },
                { open.get() && operation.current() })) }
            try {
                worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
                when (mode) { 0 -> operation.cancel(); 1 -> open.set(false); 2 -> foreground.set(false); else -> now.set(start.deadline) }
                release.countDown(); worker.join(5000); assertFalse(worker.isAlive); assertNull(result.get())
            } finally { release.countDown(); worker.join(5000) }
        }
    }
}
