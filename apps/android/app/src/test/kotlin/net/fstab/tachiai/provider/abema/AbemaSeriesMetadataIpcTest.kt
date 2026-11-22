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
    private fun fields(offset: Any = 0, vararg extra: Pair<String, Any?>) = mapOf("nonce" to nonce,
        "instance" to "12345678-1234-1234-1234-123456789abc", "parent" to "series-A", "offset" to offset,
        "proof" to "b".repeat(64), "deadline" to 46000L) + extra
    private val continuation = arrayOf("season" to "series-A_s1", "group" to "series-A_eg1", "types" to "1,2")
    private val empty = """{"seriesId":"series-A","groups":[],"viewingTypes":[],"items":[],"more":false}"""
    @Test fun immutableStartRequiresExactTypedFieldsOwnerProofOffsetGroupAndDeadline() {
        assertEquals(0, AbemaSeriesMetadataIpc.decodeStart(fields(), 1000)!!.page.offset)
        val next = AbemaSeriesMetadataIpc.decodeStart(fields(20, *continuation), 1000)!!.page
        assertEquals(listOf(1, 2), next.viewingTypes); assertEquals("series-A_eg1", next.groupId); assertEquals("series-A_s1", next.seasonId)
        assertEquals("series-A_eg2", AbemaSeriesMetadataIpc.decodeStart(fields(0, "group" to "series-A_eg2"), 1000)!!.page.groupId)
        for (bad in listOf(fields(1), fields(20), fields(0, "version" to null), fields(20L, *continuation), fields(20, "season" to "series-A_s1"),
            fields(20, *continuation, "types" to "1,1"), fields(20, *continuation, "types" to "0"), fields(20, *continuation, "types" to "1,2,3,4,5"),
            fields(20, *continuation, "types" to "1%2C2"), fields(20, *continuation, "types" to null), fields(0, "group" to "series-B_eg1"),
            fields(0, "season" to "season-A"), fields(0, "group" to null), fields(0, "order" to "-seq"),
            fields() + ("extra" to "ignored"), fields() + ("deadline" to 46001L), fields() + ("proof" to "private-invalid"),
            fields() + ("parent" to "../other"))) assertNull(AbemaSeriesMetadataIpc.decodeStart(bad, 1000))
        assertTrue(AbemaSeriesMetadataIpc.decodeStart(fields(20, *continuation, "order" to "desc"), 1000)!!.page.newest)
    }
    @Test fun resultBindsExactProjectionAndRefusesExtraOrFailurePayload() {
        val request = AbemaSeriesPageRequest("series-A")
        val good = mapOf("nonce" to nonce, "outcome" to "OK", "page" to empty)
        assertNotNull(AbemaSeriesMetadataIpc.decodeResult(good, request))
        assertNull(AbemaSeriesMetadataIpc.decodeResult(good + ("extra" to "private"), request))
        assertNull(AbemaSeriesMetadataIpc.decodeResult(good + ("outcome" to "TEMPORARY"), request))
        assertNull(AbemaSeriesMetadataIpc.decodeResult(good, AbemaSeriesPageRequest("other")))
        assertNull(AbemaSeriesMetadataIpc.decodeResult(good, AbemaSeriesPageRequest("series-A", 20, "series-A_s1", "series-A_eg1", listOf(1))))
        assertNull(AbemaSeriesMetadataIpc.decodeResult(good, AbemaSeriesPageRequest("series-A", groupId = "series-A_eg1")))
        assertNotNull(AbemaSeriesMetadataIpc.decodeResult(mapOf("nonce" to nonce, "outcome" to "TEMPORARY"), request))
    }
    @Test fun clientOperationRejectsStaleNonceOwnerLifetimeAndLateReplacement() {
        var now = 1000L; var foreground = true
        val start = AbemaSeriesMetadataIpc.decodeStart(fields(), now)!!
        val page = decodeAbemaSeriesProjection(empty, start.page)!!
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
            val page = decodeAbemaSeriesProjection(empty, start.page)!!
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
