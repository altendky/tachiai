package net.fstab.tachiai.platform.media

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class NativeMediaReadCostsTest {
    private val uri = URI("https://media.ttvnw.net/fixture?token=must-not-enter-diagnostics")
    private class Clock {
        var nanos = 0L
        var calls = 0
        var throws = false
        fun now(): Long { calls++; if (throws) throw AssertionError("Observer-only failure"); return nanos }
    }
    private inner class Fixture(val knownLength: Boolean = false, val declaredLength: Long = 7L) {
        val clock = Clock()
        var admissions = 0
        var inputReads = 0
        var streamCloses = 0
        var disconnects = 0
        var admit: () -> Boolean = { true }
        var beforeHeaders: () -> Unit = {}
        var beforeRead: () -> Unit = {}
        var failClose = false
        val events = mutableListOf<NativeMediaEvent>()
        private val body = object : ByteArrayInputStream(ByteArray(7) { 1 }) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                inputReads++; clock.nanos += 1_000_000
                beforeRead()
                return super.read(bytes, offset, minOf(length, 3))
            }
            override fun close() { streamCloses++; if (failClose) throw IOException("Fixture cleanup failure") }
        }
        private val connection = object : HttpsURLConnection(uri.toURL()) {
            override fun connect() = Unit
            override fun disconnect() { disconnects++ }
            override fun usingProxy() = false
            override fun getCipherSuite() = "fixture"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getResponseCode(): Int { clock.nanos += 3_000_000; beforeHeaders(); return 200 }
            override fun getContentLengthLong() = if (knownLength) declaredLength else -1L
            override fun getInputStream(): InputStream { clock.nanos += 5_000_000; return body }
        }
        fun source(costs: NativeMediaReadCosts? = null, type: Int = C.DATA_TYPE_MEDIA,
            coalesceMediaReads: Boolean = false) =
            BoundedMediaRequests(NativePlaybackBudget.continuous({ true }, { 0L }), { true },
                { event, _ -> events += event }, canRequest = {
                    admissions++; clock.nanos += 2_000_000; admit()
                }, openConnection = { clock.nanos += 1_000_000; connection }, clockMs = { 1_000L },
                readCosts = costs, coalesceMediaReads = coalesceMediaReads)
                .create(type)
    }

    @Test fun actualShortReadsSeparateAdmissionSocketAndSetupCostsWithoutChangingAdmissionCalls() {
        val fixture = Fixture()
        val costs = NativeMediaReadCosts(clockNanos = fixture.clock::now)
        val source = fixture.source(costs)
        source.openUri(uri)
        assertEquals(0, source.read(ByteArray(1), 0, 0))
        val received = mutableListOf<Int>()
        do { val count = source.read(ByteArray(8), 0, 8); received += count } while (count >= 0)
        source.close(); source.close()
        assertEquals(listOf(3, 3, 1, C.RESULT_END_OF_INPUT), received)
        assertEquals(13, fixture.admissions)
        assertEquals(4, fixture.inputReads)
        assertEquals(1, fixture.streamCloses)
        assertEquals(1, fixture.disconnects)
        val snapshot = costs.snapshot()
        val actual = snapshot.media
        assertEquals(1L, actual.openCount); assertEquals(1L, actual.openedCount)
        assertEquals(0L, actual.openFailureCount); assertEquals(1L, actual.completedCount)
        assertEquals(0L, actual.abortedCount)
        assertEquals(1_000_000L, actual.setupNanos)
        assertEquals(3_000_000L, actual.headersNanos); assertEquals(5_000_000L, actual.inputOpenNanos)
        assertEquals(5L, actual.setupAdmissionCount); assertEquals(10_000_000L, actual.setupAdmissionNanos)
        assertEquals(8L, actual.readAdmissionCount); assertEquals(16_000_000L, actual.readAdmissionNanos)
        assertEquals(4L, actual.readCount); assertEquals(20_000_000L, actual.readNanos)
        assertEquals(1L, actual.readEofCount); assertEquals(0L, actual.readFailureCount)
        assertEquals(32L, actual.requestedBytes); assertEquals(32L, actual.inputRequestedBytes)
        assertEquals(7L, actual.inputReturnedBytes); assertEquals(4_000_000L, actual.inputReadNanos)
        assertEquals(4L, actual.inputReadCount); assertEquals(3L, actual.inputPositiveReadCount)
        assertEquals(0L, actual.inputZeroReadCount); assertEquals(1L, actual.inputEofCount)
        assertEquals(NativeMediaReadSizes(4, 0, 0, 0, 0), actual.requestedSizes)
        assertEquals(NativeMediaReadSizes(4, 0, 0, 0, 0), actual.inputRequestedSizes)
        assertEquals(NativeMediaReadSizes(3, 0, 0, 0, 0), actual.returnedSizes)
        assertEquals(0L, snapshot.manifest.openCount); assertEquals(0L, snapshot.other.openCount)
        assertFalse(snapshot.toString().contains("token")); assertFalse(snapshot.toString().contains(uri.host))
    }

    @Test fun knownLengthEofUsesOriginalSingleAdmissionAndDoesNotReadTheSocketAgain() {
        val fixture = Fixture(knownLength = true)
        val costs = NativeMediaReadCosts(clockNanos = fixture.clock::now)
        val source = fixture.source(costs)
        source.openUri(uri)
        repeat(3) { source.read(ByteArray(8), 0, 8) }
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(8), 0, 8))
        source.close()
        val actual = costs.snapshot().media
        assertEquals(12, fixture.admissions); assertEquals(3, fixture.inputReads)
        assertEquals(7L, actual.readAdmissionCount)
        assertEquals(32L, actual.requestedBytes); assertEquals(12L, actual.inputRequestedBytes)
        assertEquals(1L, actual.readEofCount); assertEquals(0L, actual.inputEofCount)
        assertEquals(1L, actual.completedCount)
    }

    @Test fun coalescingKeepsActualInputCostsButBracketsMultipleFragmentsWithOneAdmissionPair() {
        for (declared in listOf(7L, 12L)) {
            val fixture = Fixture(knownLength = true, declaredLength = declared)
            val costs = NativeMediaReadCosts(clockNanos = fixture.clock::now)
            val source = fixture.source(costs, coalesceMediaReads = true)
            source.openUri(uri)
            assertEquals(7, source.read(ByteArray(16), 0, 16))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(16), 0, 16))
            source.close()
            val actual = costs.snapshot().media
            assertEquals(8, fixture.admissions)
            assertEquals(3L, actual.readAdmissionCount)
            assertEquals(6_000_000L, actual.readAdmissionNanos)
            assertEquals(2L, actual.readCount)
            assertEquals(if (declared == 7L) 3L else 4L, actual.inputReadCount)
            assertEquals(7L, actual.inputReturnedBytes)
            assertEquals(if (declared == 7L) 1L else 0L, actual.completedCount)
            assertEquals(if (declared == 7L) 0L else 1L, actual.abortedCount)
        }
    }

    @Test fun illegalSecondOpenDoesNotReplaceTheOwnedRequestDiagnostics() {
        val fixture = Fixture()
        val costs = NativeMediaReadCosts(clockNanos = fixture.clock::now)
        val source = fixture.source(costs)
        source.openUri(uri)
        source.read(ByteArray(8), 0, 8)
        assertThrows(IOException::class.java) { source.openUri(uri) }
        source.close()
        val actual = costs.snapshot().media
        // Invalid reopens fail before acquiring another request/connection.
        assertEquals(1L, actual.openCount); assertEquals(1L, actual.openedCount)
        assertEquals(0L, actual.openFailureCount); assertEquals(1L, actual.abortedCount)
        assertEquals(3L, actual.inputReturnedBytes)
        assertEquals(7, fixture.admissions)
        assertEquals(1, fixture.streamCloses); assertEquals(1, fixture.disconnects)
    }

    @Test fun prematureSocketEofDoesNotClaimTheDeclaredRequestBodyCompleted() {
        val fixture = Fixture(knownLength = true, declaredLength = 12L)
        val costs = NativeMediaReadCosts(clockNanos = fixture.clock::now)
        val source = fixture.source(costs)
        source.openUri(uri)
        repeat(3) { source.read(ByteArray(8), 0, 8) }
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(8), 0, 8))
        source.close()
        val actual = costs.snapshot().media
        assertEquals(7L, actual.inputReturnedBytes); assertEquals(1L, actual.readEofCount)
        assertEquals(1L, actual.abortedCount); assertEquals(0L, actual.completedCount)
        assertEquals(13, fixture.admissions)
    }

    @Test fun postReadRefusalCountsReceivedBytesAndFailureWithoutPublishingOrCompletingTheBody() {
        val fixture = Fixture()
        val costs = NativeMediaReadCosts(clockNanos = fixture.clock::now)
        val source = fixture.source(costs)
        source.openUri(uri)
        fixture.beforeRead = { fixture.admit = { false } }
        assertThrows(IOException::class.java) { source.read(ByteArray(8), 0, 8) }
        source.close()
        val actual = costs.snapshot().media
        assertEquals(7, fixture.admissions); assertEquals(1, fixture.inputReads)
        assertEquals(3L, actual.inputReturnedBytes)
        assertEquals(1L, actual.readFailureCount); assertEquals(1L, actual.abortedCount)
        assertEquals(0L, actual.completedCount); assertEquals(0L, actual.readEofCount)
        assertEquals(1, fixture.disconnects); assertEquals(1, fixture.streamCloses)
    }

    @Test fun headerAndSocketFailuresRetainTheirTimingAndOriginalCleanup() {
        for (headers in listOf(true, false)) {
            val fixture = Fixture()
            val costs = NativeMediaReadCosts(clockNanos = fixture.clock::now)
            val source = fixture.source(costs)
            if (headers) {
                fixture.beforeHeaders = { throw IOException("Fixture header failure") }
                assertThrows(IOException::class.java) { source.openUri(uri) }
            } else {
                source.openUri(uri)
                fixture.beforeRead = { throw IOException("Fixture input failure") }
                assertThrows(IOException::class.java) { source.read(ByteArray(8), 0, 8) }
            }
            source.close()
            val actual = costs.snapshot().media
            assertEquals(1L, actual.abortedCount); assertEquals(0L, actual.completedCount)
            assertEquals(if (headers) 1L else 0L, actual.openFailureCount)
            assertEquals(if (headers) 0L else 1L, actual.readFailureCount)
            assertEquals(3_000_000L, actual.headersNanos)
            assertEquals(if (headers) 0L else 1_000_000L, actual.inputReadNanos)
            assertEquals(if (headers) 2 else 6, fixture.admissions)
            assertEquals(1, fixture.disconnects)
            assertEquals(if (headers) 0 else 1, fixture.streamCloses)
        }
    }

    @Test fun earlyCloseAndFailedCleanupAreAbortedEvenWithAllKnownBytesReceived() {
        for (failedCleanup in listOf(false, true)) {
            val fixture = Fixture(knownLength = true)
            val costs = NativeMediaReadCosts(clockNanos = fixture.clock::now)
            val source = fixture.source(costs)
            source.openUri(uri)
            if (failedCleanup) {
                repeat(3) { source.read(ByteArray(8), 0, 8) }
                fixture.failClose = true
                assertThrows(IOException::class.java) { source.close() }
            } else source.close()
            val actual = costs.snapshot().media
            assertEquals(1L, actual.abortedCount); assertEquals(0L, actual.completedCount)
            assertEquals(1, fixture.disconnects)
        }
    }

    @Test fun diagnosticClockFailuresDoNotChangeReadsAdmissionEventsOrCleanup() {
        for (failure in listOf("none", "construction", "open", "read")) {
            val fixture = Fixture()
            if (failure == "construction") fixture.clock.throws = true
            val costs = if (failure == "none") null else NativeMediaReadCosts(clockNanos = fixture.clock::now)
            fixture.clock.throws = failure == "open"
            val source = fixture.source(costs)
            source.openUri(uri)
            fixture.clock.throws = failure == "read"
            val results = mutableListOf<Int>()
            do { val value = source.read(ByteArray(8), 0, 8); results += value } while (value >= 0)
            source.close()
            assertEquals(listOf(3, 3, 1, C.RESULT_END_OF_INPUT), results)
            assertEquals(13, fixture.admissions); assertEquals(4, fixture.inputReads)
            assertEquals(listOf(NativeMediaEvent.MEDIA_BYTES), fixture.events)
            assertEquals(1, fixture.disconnects); assertEquals(1, fixture.streamCloses)
            if (costs == null) assertEquals(0, fixture.clock.calls) else assertTrue(costs.snapshot().clockFailed)
        }
    }

    @Test fun fixedWindowFreezesCountersAndClockRollbackDisablesDiagnosticsOnly() {
        val clock = Clock()
        val costs = NativeMediaReadCosts(maximumDurationMs = 1, clockNanos = clock::now)
        val request = costs.open(NativeMediaReadCostType.MEDIA)!!
        request.opened()
        clock.nanos = 1_000_000
        val expired = costs.snapshot()
        assertTrue(expired.windowExpired); assertEquals(1_000_000L, expired.elapsedNanos)
        val reads = clock.calls
        repeat(4) {
            request.beginRead(1024); request.inputReturned(1024); request.close()
            costs.open(NativeMediaReadCostType.MANIFEST)
        }
        assertEquals(expired, costs.snapshot()); assertEquals(reads, clock.calls)
        val rollback = Clock()
        val other = NativeMediaReadCosts(clockNanos = rollback::now)
        rollback.nanos = -1
        assertTrue(other.snapshot().clockFailed)
        assertThrows(IllegalArgumentException::class.java) { NativeMediaReadCosts(90_001) }
    }

    @Test fun histogramsAreFixedNumericBinsAndDataTypesRemainSeparate() {
        val costs = NativeMediaReadCosts(clockNanos = { 0L })
        for (type in NativeMediaReadCostType.entries) {
            val request = costs.open(type)!!
            request.opened()
            for (size in listOf(256, 257, 1024, 1025, 4096, 4097, 16384, 16385)) {
                val read = request.beginRead(size)
                val input = request.beginInput(size)
                request.end(input); request.inputReturned(size); request.readFinished(read)
            }
            request.bodyComplete(); request.close()
        }
        val snapshot = costs.snapshot()
        for (bucket in listOf(snapshot.manifest, snapshot.media, snapshot.other)) {
            assertEquals(1L, bucket.completedCount)
            assertEquals(NativeMediaReadSizes(1, 2, 2, 2, 1), bucket.requestedSizes)
            assertEquals(bucket.requestedSizes, bucket.inputRequestedSizes)
            assertEquals(bucket.requestedSizes, bucket.returnedSizes)
        }
    }
}
