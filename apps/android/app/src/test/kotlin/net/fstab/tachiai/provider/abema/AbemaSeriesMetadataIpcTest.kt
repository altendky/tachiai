package net.fstab.tachiai.provider.abema

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AbemaSeriesMetadataIpcTest {
    private val nonce = "a".repeat(32)
    private fun fields(offset: Any = 0, version: Any? = null) = mapOf("nonce" to nonce,
        "instance" to "12345678-1234-1234-1234-123456789abc", "parent" to "series-A", "offset" to offset,
        "version" to version, "proof" to "b".repeat(64), "deadline" to 46000L)
    @Test fun immutableStartRequiresExactTypedFieldsOwnerProofOffsetVersionAndDeadline() {
        assertEquals(0, AbemaSeriesMetadataIpc.decodeStart(fields(), 1000)!!.page.offset)
        assertEquals("v1", AbemaSeriesMetadataIpc.decodeStart(fields(20, "v1"), 1000)!!.page.version)
        for (bad in listOf(fields(1), fields(20), fields(0, "v1"), fields(20, 1), fields(20L, "v1"),
            fields() + ("extra" to "ignored"), fields() + ("deadline" to 46001L), fields() + ("proof" to "private-invalid"),
            fields() + ("parent" to "../other"))) assertNull(AbemaSeriesMetadataIpc.decodeStart(bad, 1000))
    }
    @Test fun resultBindsExactProjectionAndRefusesExtraOrFailurePayload() {
        val request = AbemaSeriesPageRequest("series-A", 20, "v1")
        val good = mapOf("nonce" to nonce, "outcome" to "OK", "page" to "{\"seriesId\":\"series-A\",\"version\":\"v1\",\"programs\":[]}")
        assertNotNull(AbemaSeriesMetadataIpc.decodeResult(good, request))
        assertNull(AbemaSeriesMetadataIpc.decodeResult(good + ("extra" to "private"), request))
        assertNull(AbemaSeriesMetadataIpc.decodeResult(good + ("outcome" to "TEMPORARY"), request))
        assertNull(AbemaSeriesMetadataIpc.decodeResult(good, AbemaSeriesPageRequest("other")))
        assertNull(AbemaSeriesMetadataIpc.decodeResult(good, AbemaSeriesPageRequest("series-A", 20, "changed")))
        assertNotNull(AbemaSeriesMetadataIpc.decodeResult(mapOf("nonce" to nonce, "outcome" to "TEMPORARY"), request))
    }
    @Test fun clientOperationRejectsStaleNonceOwnerLifetimeAndLateReplacement() {
        var now = 1000L; var foreground = true
        val start = AbemaSeriesMetadataIpc.decodeStart(fields(), now)!!
        val page = decodeAbemaSeriesProjection("{\"seriesId\":\"series-A\",\"version\":\"v1\",\"programs\":[]}", start.page)!!
        val operation = AbemaSeriesClientOperation(start, { now }) { foreground }
        assertFalse(operation.accept(AbemaSeriesMetadataIpc.Result("c".repeat(32), "OK", page)))
        assertTrue(operation.accept(AbemaSeriesMetadataIpc.Result(nonce, "OK", page)))
        assertFalse(operation.accept(AbemaSeriesMetadataIpc.Result(nonce, "OK", page)))
        foreground = false; assertNull(operation.result()); foreground = true
        operation.cancel(); assertNull(operation.result()); assertFalse(operation.accept(AbemaSeriesMetadataIpc.Result(nonce, "OK", page)))
        val expired = AbemaSeriesClientOperation(start, { now }) { true }; now = start.deadline
        assertFalse(expired.accept(AbemaSeriesMetadataIpc.Result(nonce, "OK", page)))
    }
    @Test fun heldFinalWorkerOwnerReadCannotOverrideCancelClientCloseForegroundLossOrExpiry() {
        for (mode in 0..3) {
            val now = AtomicLong(1000); val open = AtomicBoolean(true); val foreground = AtomicBoolean(true)
            val start = AbemaSeriesMetadataIpc.decodeStart(fields(), now.get())!!
            val page = decodeAbemaSeriesProjection("{\"seriesId\":\"series-A\",\"version\":\"v1\",\"programs\":[]}", start.page)!!
            val operation = AbemaSeriesClientOperation(start, now::get, foreground::get)
            assertTrue(operation.accept(AbemaSeriesMetadataIpc.Result(nonce, "OK", page)))
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val result = AtomicReference<AbemaSeriesMetadataIpc.Result?>()
            val worker = Thread { result.set(operation.workerResult({
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true
            }, { open.get() && operation.current() })) }
            try {
                worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
                when (mode) { 0 -> operation.cancel(); 1 -> open.set(false)
                    2 -> foreground.set(false); else -> now.set(start.deadline) }
                release.countDown(); worker.join(5000)
                assertFalse(worker.isAlive); assertNull(result.get())
            } finally { release.countDown(); worker.join(5000) }
        }
    }
    @Test fun factoryAdmissionRechecksCheapLifecycleAfterHeldProtectedOwnerRead() {
        val open = AtomicBoolean(true); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val result = AtomicBoolean(true)
        val worker = Thread { result.set(abemaCatalogOwnerCurrent(open::get) {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true
        }) }
        try {
            worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); open.set(false)
            release.countDown(); worker.join(5000)
            assertFalse(worker.isAlive); assertFalse(result.get())
        } finally { release.countDown(); worker.join(5000) }
        assertTrue(abemaCatalogOwnerCurrent({ true }, { true }))
        assertFalse(abemaCatalogOwnerCurrent({ false }, { error("Must not read protected owner") }))
    }
}
