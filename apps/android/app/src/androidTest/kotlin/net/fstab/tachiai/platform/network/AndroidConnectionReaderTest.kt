package net.fstab.tachiai.platform.network

import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AndroidConnectionReaderTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private fun read(path: String, signal: CancellationSignal = CancellationSignal(), duration: Long = 2000) =
        readConnectionUri(resolver, Uri.parse("content://net.fstab.fixture.connections/$path"), signal, SystemClock.elapsedRealtime() + duration)

    @Test fun actualProviderBytesAreReadAndValidatedWithoutFilenameTrust() {
        assertEquals(ConnectionKind.WIREGUARD, parseConnectionProfile(read("wireguard")).kind)
        assertEquals("proxy.example.test:3128", parseConnectionProfile(read("proxy")).endpoint)
        assertThrows(ConnectionImportFailure::class.java) { read("oversized") }
        assertThrows(ConnectionImportFailure::class.java) { parseConnectionProfile(read("invalid-utf8")) }
    }

    @Test fun slowPipeAndCancelledReadEndWithoutWaitingForProducer() {
        val started = SystemClock.elapsedRealtime()
        assertThrows(Exception::class.java) { read("slow", duration = 100) }
        assertTrue(SystemClock.elapsedRealtime() - started < 1000)
        assertThrows(Exception::class.java) { read("wireguard", CancellationSignal().apply { cancel() }) }
    }

    @Test fun regularFileSliceRespectsOffsetLengthAndRejectsTruncation() {
        assertEquals("proxy.example.test:3128", parseConnectionProfile(read("slice")).endpoint)
        assertThrows(Exception::class.java) { read("truncated") }
    }
}
