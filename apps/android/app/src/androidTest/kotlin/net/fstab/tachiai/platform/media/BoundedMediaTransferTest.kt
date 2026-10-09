package net.fstab.tachiai.platform.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Media3 callbacks with an owned transport fixture: no socket or provider request. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class BoundedMediaTransferTest {
    private val uri = Uri.parse("https://media.example.test/segment.m4s?token=synthetic-sensitive-query")

    private class Connection(
        url: URL,
        private val status: Int = 200,
        private val length: Long = 5,
        private val stream: InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
    ) : HttpsURLConnection(url) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getInputStream() = stream
    }

    private class Transfers(private val expected: DataSpec) : TransferListener {
        val events = mutableListOf<String>()
        val byteCounts = mutableListOf<Int>()
        var source: DataSource? = null

        private fun record(actual: DataSource, spec: DataSpec, network: Boolean, event: String) {
            assertSame(expected, spec)
            assertTrue(network)
            source?.let { assertSame(it, actual) } ?: run { source = actual }
            events += event
        }

        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) =
            record(source, dataSpec, isNetwork, "initializing")
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) =
            record(source, dataSpec, isNetwork, "start")
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            assertTrue(bytesTransferred > 0)
            byteCounts += bytesTransferred
            record(source, dataSpec, isNetwork, "bytes")
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) =
            record(source, dataSpec, isNetwork, "end")
    }

    private fun requests(connection: Connection, canRequest: () -> Boolean = { true }) =
        BoundedMediaRequests(
            NativePlaybackBudget(120_000, { true }, clockMs = { 1_000 }),
            { it.host == "media.example.test" }, { _, _ -> }, canRequest,
            openConnection = { connection }, clockMs = { 1_000 },
        )

    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build()

    @Test fun successfulReadReportsOnlyAcceptedPositiveBytesAndEndsOnce() {
        val connection = Connection(URL(uri.toString()))
        val requests = requests(connection)
        val source = requests.create(C.DATA_TYPE_MEDIA)
        val spec = spec()
        val transfers = Transfers(spec)
        source.addTransferListener(transfers)
        source.addTransferListener(transfers) // Media3 listener registration is idempotent.
        try {
            assertEquals(5L, source.open(spec))
            assertSame(source, transfers.source)
            assertEquals(uri, source.uri)
            val buffer = ByteArray(3)
            assertEquals(0, source.read(buffer, 0, 0))
            assertEquals(3, source.read(buffer, 0, 3))
            assertEquals(2, source.read(buffer, 0, 3))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 3))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 3))
            assertEquals(listOf("initializing", "start", "bytes", "bytes"), transfers.events)
            assertEquals(listOf(3, 2), transfers.byteCounts)
            source.close()
            source.close()
            assertEquals(listOf("initializing", "start", "bytes", "bytes", "end"), transfers.events)
            assertNull(source.uri)
            assertTrue(connection.disconnected)
        } finally { source.close(); requests.close() }
    }

    @Test fun rangeReportsOnlyTheRequestedLength() {
        val connection = Connection(URL(uri.toString()), status = 206, length = 5)
        val requests = requests(connection)
        val source = requests.create(C.DATA_TYPE_MEDIA)
        val spec = spec(position = 10, length = 2)
        val transfers = Transfers(spec)
        source.addTransferListener(transfers)
        try {
            assertEquals(2L, source.open(spec))
            assertEquals("bytes=10-11", connection.getRequestProperty("Range"))
            assertEquals(2, source.read(ByteArray(8), 0, 8))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(8), 0, 8))
            source.close()
            assertEquals(listOf("initializing", "start", "bytes", "end"), transfers.events)
            assertEquals(listOf(2), transfers.byteCounts)
        } finally { source.close(); requests.close() }
    }

    @Test fun zeroResultAndUnknownLengthEofDoNotInventBytesOrEnd() {
        val stream = object : InputStream() {
            private var reads = 0
            override fun read(): Int = throw AssertionError("Bulk read expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = when (reads++) {
                0 -> 0
                1 -> { buffer[offset] = 7; 1 }
                else -> -1
            }
        }
        val requests = requests(Connection(URL(uri.toString()), length = -1, stream = stream))
        val source = requests.create(C.DATA_TYPE_MEDIA)
        val spec = spec()
        val transfers = Transfers(spec)
        source.addTransferListener(transfers)
        try {
            assertEquals(C.LENGTH_UNSET.toLong(), source.open(spec))
            assertEquals(0, source.read(ByteArray(4), 0, 4))
            assertEquals(1, source.read(ByteArray(4), 0, 4))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(4), 0, 4))
            assertEquals(listOf("initializing", "start", "bytes"), transfers.events)
            source.close()
            assertEquals(listOf("initializing", "start", "bytes", "end"), transfers.events)
            assertEquals(listOf(1), transfers.byteCounts)
        } finally { source.close(); requests.close() }
    }

    @Test fun openFailureHasInitializationButNoStartOrEndAndSanitizedError() {
        val connection = Connection(URL(uri.toString()), status = 403)
        val requests = requests(connection)
        val source = requests.create(C.DATA_TYPE_MEDIA)
        val spec = spec()
        val transfers = Transfers(spec)
        source.addTransferListener(transfers)
        try {
            val error = assertThrows(IOException::class.java) { source.open(spec) }
            assertEquals("Native media request failed", error.message)
            assertNull(error.cause)
            source.close()
            assertEquals(listOf("initializing"), transfers.events)
            assertTrue(transfers.byteCounts.isEmpty())
            assertTrue(connection.disconnected)
        } finally { source.close(); requests.close() }
    }

    @Test fun readFailureClosesTransferWithoutReportingRejectedBytes() {
        val stream = object : InputStream() {
            override fun read(): Int = throw IOException("synthetic-sensitive-query")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()
        }
        val connection = Connection(URL(uri.toString()), stream = stream)
        val requests = requests(connection)
        val source = requests.create(C.DATA_TYPE_MEDIA)
        val spec = spec()
        val transfers = Transfers(spec)
        source.addTransferListener(transfers)
        try {
            source.open(spec)
            val error = assertThrows(IOException::class.java) { source.read(ByteArray(4), 0, 4) }
            assertEquals("Native media read failed", error.message)
            assertNull(error.cause)
            assertTrue(connection.disconnected)
            assertEquals(listOf("initializing", "start", "end"), transfers.events)
            source.close()
            assertEquals(1, transfers.events.count { it == "end" })
        } finally { source.close(); requests.close() }
    }

    @Test fun cancellationDuringReadDoesNotCountTheRejectedBytes() {
        var active = true
        val stream = object : InputStream() {
            override fun read(): Int = throw AssertionError("Bulk read expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer[offset] = 7
                active = false
                return 1
            }
        }
        val requests = requests(Connection(URL(uri.toString()), stream = stream)) { active }
        val source = requests.create(C.DATA_TYPE_MEDIA)
        val spec = spec()
        val transfers = Transfers(spec)
        source.addTransferListener(transfers)
        try {
            source.open(spec)
            assertThrows(IOException::class.java) { source.read(ByteArray(4), 0, 4) }
            source.close()
            assertEquals(listOf("initializing", "start", "end"), transfers.events)
            assertTrue(transfers.byteCounts.isEmpty())
        } finally { source.close(); requests.close() }
    }

    @Test fun requestGroupCancellationEndsOnLoaderReadNotOnCancellingThread() {
        val connection = Connection(URL(uri.toString()))
        val requests = requests(connection)
        val source = requests.create(C.DATA_TYPE_MEDIA)
        val spec = spec()
        val transfers = Transfers(spec)
        source.addTransferListener(transfers)
        try {
            source.open(spec)
            requests.close()
            assertTrue(connection.disconnected)
            assertEquals(listOf("initializing", "start"), transfers.events)
            assertThrows(IOException::class.java) { source.read(ByteArray(4), 0, 4) }
            source.close()
            assertEquals(listOf("initializing", "start", "end"), transfers.events)
        } finally { source.close(); requests.close() }
    }

    @Test fun requestGroupCancellationCanAlsoEndThroughExplicitLoaderTeardown() {
        val requests = requests(Connection(URL(uri.toString())))
        val source = requests.create(C.DATA_TYPE_MEDIA)
        val spec = spec()
        val transfers = Transfers(spec)
        source.addTransferListener(transfers)
        try {
            source.open(spec)
            requests.close()
            source.close()
            source.close()
            assertEquals(listOf("initializing", "start", "end"), transfers.events)
        } finally { source.close(); requests.close() }
    }
}
