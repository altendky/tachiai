package net.fstab.tachiai.platform.media

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

// Disposable device only. Real players are constructed but never started;
// injected transport entries use no network, provider, license or saved state.
@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePlayerCleanupDeviceTest {
    @Test fun hlsTransportFailuresStillReleaseActualPlayerAndQualityExactlyOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val observations = mutableListOf<NativeFailureStage>()
            val budget = NativePlaybackBudget(60_000, { true })
            val host = BoundedNativePlayer(instrumentation.targetContext, budget, Long.MAX_VALUE,
                allowedUri = { false }, onEvent = { _, _ -> },
                onFailure = { stage, _ -> observations += stage })
            val first = IOException("synthetic cleanup failure")
            val connection = FailedConnection(first)
            val other = FailedConnection(null)
            installConnection(host, "requests", connection)
            installConnection(host, "requests", other)
            try {
                assertSame(first, assertThrows(IOException::class.java) { host.close() })
                assertTrue(host.player.isReleased)
                assertQualityClosed(host)
                assertFalse(budget.active)
                assertEquals(1, connection.closes)
                assertEquals(1, other.closes)
                assertEquals(listOf(NativeFailureStage.MEDIA_DISCONNECT), observations)
                host.close()
                assertEquals(1, connection.closes)
            } finally { if (!host.player.isReleased) host.player.release() }
        }
    }

    @Test fun dashSealsFirstAndReleasesActualPlayerEvenWhenBothRequestGroupsFail() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val calls = mutableListOf<String>()
            val budget = NativePlaybackBudget(60_000, { true })
            val host = BoundedNativeDashPlayer(instrumentation.targetContext, budget,
                expectedKids = listOf(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                allowedUri = { false }, manifestUri = URI("https://fixture.invalid/never"),
                manifestParser = DashManifestParser(), exchange = { fail("No DRM exchange permitted"); null },
                onEvent = { _, _ -> }, onMediaEvent = { _, _ -> }, beforeRelease = { calls += "seal" })
            val first = IllegalStateException("synthetic response-close failure")
            val second = IOException("synthetic manifest-close failure")
            val media = FailedConnection(first) { calls += "media" }
            val manifest = FailedConnection(second) { calls += "manifest" }
            installConnection(host, "requests", media)
            installConnection(host, "manifests", manifest)
            try {
                assertSame(first, assertThrows(IllegalStateException::class.java) { host.close() })
                assertArrayEquals(arrayOf(second), first.suppressed)
                assertEquals(listOf("seal", "media", "manifest"), calls)
                assertTrue(host.player.isReleased)
                assertQualityClosed(host)
                assertFalse(budget.active)
                host.close()
                assertEquals(1, media.closes)
                assertEquals(1, manifest.closes)
                assertEquals(3, calls.size)
            } finally { if (!host.player.isReleased) host.player.release() }
        }
    }

    private fun field(owner: Any, name: String): Any = checkNotNull(owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner))

    @Suppress("UNCHECKED_CAST")
    private fun installConnection(host: Any, group: String, connection: FailedConnection) {
        val requests = field(host, group)
        (field(requests, "active") as MutableSet<HttpsURLConnection>).add(connection)
    }

    private fun assertQualityClosed(host: Any) {
        val quality = field(host, "quality")
        assertEquals(true, field(quality, "closed"))
        assertNull(quality.javaClass.getDeclaredField("attached").apply { isAccessible = true }.get(quality))
    }

    private class FailedConnection(private val error: Exception?, private val onClose: () -> Unit = {}) :
        HttpsURLConnection(URL("https://fixture.invalid/never")) {
        var closes = 0
        override fun disconnect() { closes++; onClose(); error?.let { throw it } }
        override fun connect() = fail("No connection permitted")
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}
