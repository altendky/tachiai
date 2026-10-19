package net.fstab.tachiai.platform.media

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.SocketTimeoutException
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class BoundedMediaReadBatchingTest {
    private val uri = URI("https://media.example.test/fixture?token=synthetic-private-detail")

    private inner class Fixture(
        val body: ByteArray = ByteArray(32) { it.toByte() },
        val fragment: Int = 3,
        declaredLength: Long = -1,
        type: Int = C.DATA_TYPE_MEDIA,
        coalesce: Boolean = true,
        status: Int = 200,
    ) : AutoCloseable {
        var now = 1_000L
        var admitted = true
        var active = true
        var admissions = 0
        var inputReads = 0
        var inputCloses = 0
        var disconnected = false
        var beforeRead: (Int) -> Unit = {}
        var afterRead: (Int) -> Unit = {}
        val zeroReads = mutableSetOf<Int>()
        val requested = mutableListOf<Int>()
        val failures = mutableListOf<Pair<NativeFailureStage, NativeMediaRequestFailure>>()
        val events = mutableListOf<NativeMediaEvent>()
        private fun stream() = object : ByteArrayInputStream(body) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                inputReads++
                requested += length
                beforeRead(inputReads)
                val count = if (inputReads in zeroReads) 0 else super.read(buffer, offset, minOf(fragment, length))
                afterRead(inputReads)
                return count
            }
            override fun close() { inputCloses++ }
        }
        val connection = object : HttpsURLConnection(uri.toURL()) {
            override fun connect() = Unit
            override fun disconnect() { disconnected = true }
            override fun usingProxy() = false
            override fun getCipherSuite() = "fixture"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getResponseCode() = status
            override fun getContentLengthLong() = declaredLength
            override fun getInputStream() = stream()
        }
        private val requests = BoundedMediaRequests(
            NativePlaybackBudget.continuous({ active }, { now }), { it.host == uri.host },
            { event, _ -> events += event }, canRequest = { admissions++; admitted },
            openConnection = { connection }, clockMs = { now },
            onFailure = { stage, error -> failures += stage to (error as NativeMediaRequestException).failure },
            coalesceMediaReads = coalesce,
        )
        val source = requests.create(type)
        fun open(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) {
            source.openUri(uri, position, length)
            admissions = 0
        }
        override fun close() { source.close(); requests.close() }
    }

    @Test fun fragmentedMediaFillsOnlyTheCallerSliceWithFreshAdmissionBeforeAndAfterDelivery() {
        Fixture().use { fixture ->
            fixture.open()
            val buffer = ByteArray(15) { 99 }
            assertEquals(9, fixture.source.read(buffer, 2, 9))
            assertArrayEquals(ByteArray(9) { it.toByte() }, buffer.copyOfRange(2, 11))
            assertTrue(buffer.take(2).all { it == 99.toByte() })
            assertTrue(buffer.drop(11).all { it == 99.toByte() })
            assertEquals(listOf(9, 6, 3), fixture.requested)
            assertEquals(2, fixture.admissions)
            assertEquals(3, fixture.inputReads)
        }
    }

    @Test fun batchingDoesNotReadBeyondTheExplicitRangeOrDeclaredRemainingLength() {
        Fixture(declaredLength = 32, status = 206).use { fixture ->
            fixture.open(position = 10, length = 5)
            val buffer = ByteArray(12) { 99 }
            assertEquals(5, fixture.source.read(buffer, 4, 8))
            assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), buffer.copyOfRange(4, 9))
            assertEquals("bytes=10-14", fixture.connection.getRequestProperty("Range"))
            assertEquals(listOf(5, 2), fixture.requested)
            val reads = fixture.inputReads
            assertEquals(C.RESULT_END_OF_INPUT, fixture.source.read(buffer, 0, buffer.size))
            assertEquals(reads, fixture.inputReads)
        }
        Fixture(declaredLength = 5).use { fixture ->
            fixture.open()
            assertEquals(5, fixture.source.read(ByteArray(20), 0, 20))
            assertEquals(listOf(5, 2), fixture.requested)
        }
    }

    @Test fun oneLogicalReadIsCappedAtSixteenKibibytesWithoutPrefetchingTheNextCall() {
        Fixture(body = ByteArray(40_000) { (it % 113).toByte() }, fragment = 4_096).use { fixture ->
            fixture.open()
            val buffer = ByteArray(35_000)
            assertEquals(16 * 1024, fixture.source.read(buffer, 0, buffer.size))
            assertArrayEquals(fixture.body.copyOfRange(0, 16 * 1024), buffer.copyOfRange(0, 16 * 1024))
            assertEquals(4, fixture.inputReads)
            assertEquals(16 * 1024, fixture.source.read(buffer, 0, buffer.size))
            assertArrayEquals(fixture.body.copyOfRange(16 * 1024, 32 * 1024), buffer.copyOfRange(0, 16 * 1024))
            assertEquals(8, fixture.inputReads)
            assertEquals(4, fixture.admissions)
        }
    }

    @Test fun fragmentCountAndElapsedTimeBoundEachBatchWithoutWaitingForTheCallerBufferToFill() {
        Fixture(body = ByteArray(100), fragment = 1).use { fixture ->
            fixture.open()
            assertEquals(16, fixture.source.read(ByteArray(100), 0, 100))
            assertEquals(16, fixture.inputReads)
            assertEquals(2, fixture.admissions)
        }
        Fixture(body = ByteArray(100), fragment = 2).use { fixture ->
            fixture.open()
            fixture.afterRead = { fixture.now += 50 }
            assertEquals(4, fixture.source.read(ByteArray(100), 0, 100))
            assertEquals(2, fixture.inputReads)
            assertEquals(1_100L, fixture.now)
            assertEquals(2, fixture.admissions)
        }
    }

    @Test fun aZeroInputResultEndsTheBatchAndDoesNotSpinOrLoseEarlierBytes() {
        Fixture().use { fixture ->
            fixture.open(); fixture.zeroReads += 1
            assertEquals(0, fixture.source.read(ByteArray(8), 0, 8))
            assertEquals(1, fixture.inputReads)
            val buffer = ByteArray(8)
            assertEquals(8, fixture.source.read(buffer, 0, 8))
            assertArrayEquals(ByteArray(8) { it.toByte() }, buffer)
        }
        Fixture().use { fixture ->
            fixture.open(); fixture.zeroReads += 2
            val buffer = ByteArray(8)
            assertEquals(3, fixture.source.read(buffer, 0, 8))
            assertEquals(2, fixture.inputReads)
            assertEquals(3, fixture.source.read(buffer, 0, 3))
            assertArrayEquals(byteArrayOf(3, 4, 5), buffer.copyOfRange(0, 3))
        }
    }

    @Test fun partialEofReturnsAcceptedBytesBeforeReportingEndOnTheNextCall() {
        Fixture(body = byteArrayOf(1, 2, 3, 4, 5), fragment = 2).use { fixture ->
            fixture.open()
            val buffer = ByteArray(8) { 99 }
            assertEquals(5, fixture.source.read(buffer, 1, 7))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), buffer.copyOfRange(1, 6))
            assertEquals(C.RESULT_END_OF_INPUT, fixture.source.read(buffer, 0, buffer.size))
            assertEquals(C.RESULT_END_OF_INPUT, fixture.source.read(buffer, 0, buffer.size))
            assertTrue(buffer.drop(6).all { it == 99.toByte() })
            assertFalse(fixture.disconnected)
        }
    }

    @Test fun reopeningTheSameSourceAfterEofResetsTheInputAndRemainingRangeState() {
        Fixture(body = byteArrayOf(1, 2, 3, 4, 5), fragment = 2).use { fixture ->
            val buffer = ByteArray(8)
            fixture.open()
            assertEquals(5, fixture.source.read(buffer, 0, buffer.size))
            assertEquals(C.RESULT_END_OF_INPUT, fixture.source.read(buffer, 0, buffer.size))
            fixture.source.close()

            fixture.open(length = 2)
            assertEquals(2, fixture.source.read(buffer, 0, buffer.size))
            assertArrayEquals(byteArrayOf(1, 2), buffer.copyOfRange(0, 2))
            assertEquals(C.RESULT_END_OF_INPUT, fixture.source.read(buffer, 0, buffer.size))
            fixture.source.close()

            fixture.open(length = 4)
            assertEquals(4, fixture.source.read(buffer, 0, buffer.size))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), buffer.copyOfRange(0, 4))
            assertEquals(C.RESULT_END_OF_INPUT, fixture.source.read(buffer, 0, buffer.size))
            fixture.source.close()
            assertEquals(3, fixture.inputCloses)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test fun clockRollbackStopsBeforeStartingAnotherFragmentWithoutDiscardingAcceptedBytes() {
        Fixture(fragment = 2).use { fixture ->
            fixture.open()
            // Roll back the batch clock without invalidating the earlier
            // playback budget: this isolates the batch's own elapsed bound.
            fixture.now = 1_100L
            fixture.afterRead = { fixture.now = 1_099L }
            val buffer = ByteArray(8)
            assertEquals(2, fixture.source.read(buffer, 0, buffer.size))
            assertArrayEquals(byteArrayOf(0, 1), buffer.copyOfRange(0, 2))
            assertEquals(1, fixture.inputReads)
            assertEquals(2, fixture.admissions)
            assertFalse(fixture.disconnected)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test fun zeroLengthReadDoesNotConsultAuthorityOrInput() {
        Fixture().use { fixture ->
            fixture.open(); fixture.admitted = false
            assertEquals(0, fixture.source.read(ByteArray(1), 0, 0))
            assertEquals(0, fixture.inputReads)
            assertEquals(0, fixture.admissions)
        }
    }

    @Test fun finalDurableAdmissionRejectsAnEntireBatchIfRevokedDuringItsReads() {
        Fixture().use { fixture ->
            fixture.open()
            fixture.afterRead = { fixture.admitted = false }
            val error = assertThrows(IOException::class.java) { fixture.source.read(ByteArray(9), 0, 9) }
            assertEquals("Native media read failed", error.message)
            assertNull(error.cause)
            assertEquals(2, fixture.admissions)
            assertFalse(fixture.events.contains(NativeMediaEvent.MEDIA_BYTES))
            assertTrue(fixture.disconnected)
            assertEquals(1, fixture.inputCloses)
            assertEquals(listOf(NativeFailureStage.MEDIA_REQUEST_READ to
                NativeMediaRequestFailure.REQUEST_ADMISSION_REFUSED), fixture.failures)
        }
    }

    @Test fun stoppedBudgetOrLoaderInterruptionPreventsAnotherFragmentAndRejectsPartialData() {
        for (interrupt in listOf(false, true)) {
            Fixture().use { fixture ->
                fixture.open()
                fixture.afterRead = {
                    if (interrupt) Thread.currentThread().interrupt() else fixture.active = false
                }
                try {
                    val error = assertThrows(IOException::class.java) { fixture.source.read(ByteArray(9), 0, 9) }
                    assertEquals("Native media read failed", error.message)
                    assertNull(error.cause)
                    assertEquals(1, fixture.inputReads)
                    assertEquals(1, fixture.admissions)
                    assertFalse(fixture.events.contains(NativeMediaEvent.MEDIA_BYTES))
                    assertTrue(fixture.disconnected)
                    if (interrupt) assertTrue(Thread.currentThread().isInterrupted)
                } finally { Thread.interrupted() }
            }
        }
    }

    @Test fun alreadyInterruptedLoaderDoesNotEnterDurableAdmissionOrReadInput() {
        Fixture().use { fixture ->
            fixture.open()
            Thread.currentThread().interrupt()
            try {
                assertThrows(IOException::class.java) { fixture.source.read(ByteArray(9), 0, 9) }
                assertEquals(0, fixture.admissions)
                assertEquals(0, fixture.inputReads)
                assertTrue(Thread.currentThread().isInterrupted)
                assertTrue(fixture.disconnected)
            } finally { Thread.interrupted() }
        }
    }

    @Test fun transportFailureAfterPartialProgressDoesNotReturnOrPublishThoseBytes() {
        Fixture().use { fixture ->
            fixture.open()
            fixture.beforeRead = { if (it == 2) throw IOException("Sensitive provider detail $uri") }
            val error = assertThrows(IOException::class.java) { fixture.source.read(ByteArray(9), 0, 9) }
            assertEquals("Native media read failed", error.message)
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
            assertFalse(error.stackTraceToString().contains("synthetic-private-detail"))
            assertFalse(fixture.events.contains(NativeMediaEvent.MEDIA_BYTES))
            assertTrue(fixture.disconnected)
            assertEquals(1, fixture.inputCloses)
            assertEquals(listOf(NativeFailureStage.MEDIA_REQUEST_READ to NativeMediaRequestFailure.IO), fixture.failures)
        }
    }

    @Test fun manifestsAndCallersWithoutOptInKeepTheirOriginalShortReadBehavior() {
        for ((type, coalesce) in listOf(C.DATA_TYPE_MANIFEST to true, C.DATA_TYPE_MEDIA to false)) {
            Fixture(type = type, coalesce = coalesce).use { fixture ->
                fixture.open()
                assertEquals(3, fixture.source.read(ByteArray(9), 0, 9))
                assertEquals(1, fixture.inputReads)
                assertEquals(2, fixture.admissions)
            }
        }
    }

    @Test fun timeoutAfterPartialProgressRetainsItsCategoryWithoutReturningPartialSuccess() {
        Fixture().use { fixture ->
            fixture.open()
            fixture.beforeRead = { if (it == 2) throw SocketTimeoutException("Sensitive provider detail $uri") }
            val error = assertThrows(IOException::class.java) { fixture.source.read(ByteArray(9), 0, 9) }
            assertEquals("Native media read failed", error.message)
            assertNull(error.cause)
            assertFalse(fixture.events.contains(NativeMediaEvent.MEDIA_BYTES))
            assertTrue(fixture.disconnected)
            assertEquals(1, fixture.inputCloses)
            assertEquals(listOf(NativeFailureStage.MEDIA_REQUEST_READ to NativeMediaRequestFailure.TIMEOUT), fixture.failures)
        }
    }
}
