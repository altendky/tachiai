package net.fstab.tachiai.platform.media

import android.content.Context
import android.content.ContextWrapper

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import java.lang.reflect.Proxy
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

// Synthetic resources only. Real players are constructed but never started;
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
                assertThrows(IOException::class.java) { host.close() }
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
                assertThrows(IOException::class.java) { host.close() }
                assertEquals(1, media.closes)
                assertEquals(1, manifest.closes)
                assertEquals(3, calls.size)
            } finally { if (!host.player.isReleased) host.player.release() }
        }
    }

    @Test fun partialNativeConstructionRollbackFailureRetainsTheActualAllocationReceipt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            var contextReads = 0
            val context = object : ContextWrapper(instrumentation.targetContext) {
                override fun getApplicationContext(): Context {
                    if (++contextReads == 3) throw IOException("Synthetic player construction refusal")
                    return super.getApplicationContext()
                }
            }
            var receipt: NativeCleanupReceipt? = null
            var failures = 0
            assertThrows(IOException::class.java) {
                BoundedNativePlayer(context, NativePlaybackBudget(60_000, { true }), Long.MAX_VALUE,
                    allowedUri = { false }, onEvent = { _, _ -> }, onCleanupOwner = { owned ->
                        receipt = owned
                        owned.own(5) { failures++; throw IOException("Synthetic rollback failure") }
                    })
            }
            val retained = checkNotNull(receipt)
            assertEquals(3, contextReads)
            assertEquals(1, failures); assertFalse(retained.confirmed)
            assertThrows(IOException::class.java) { retained.close() }
            assertEquals(1, failures)
        }
    }

    @Test fun postBuildSetupFailureStillObservesVoidReleaseTimeoutBeforeOuterConfirmation() {
        // Exercise the production registration helper, without creating a real
        // timeout or starting Media3/provider resources on the persistent device.
        var listener: Player.Listener? = null
        var releasing = false
        var releases = 0
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "addListener" -> { listener = args!![0] as Player.Listener; null }
                "release" -> { releases++; checkNotNull(listener).onPlayerError(
                    PlaybackException("Synthetic timeout", null, 1003)); null }
                else -> throw AssertionError("Unexpected fixture player operation")
            }
        } as Player
        val owner = NativeCleanupReceipt()
        assertThrows(IOException::class.java) {
            try {
                owner.ownPlayer(player, { releasing }, { _, _ -> })
                assertNotNull(listener)
                throw IOException("Synthetic post-build binding refusal")
            } finally {
                releasing = true
                assertThrows(IOException::class.java) { owner.close() }
            }
        }
        assertEquals(1, releases); assertFalse(owner.confirmed)
        assertThrows(IOException::class.java) { owner.close() }
        assertEquals(1, releases)
    }

    @Test fun refusedReleaseObserverRegistrationCannotConfirmRollbackRelease() {
        var releases = 0
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "addListener" -> throw IllegalStateException("Synthetic observer registration refusal")
                "release" -> { releases++; null }
                else -> throw AssertionError("Unexpected fixture player operation")
            }
        } as Player
        val owner = NativeCleanupReceipt()
        assertThrows(IllegalStateException::class.java) { owner.ownPlayer(player, { true }, { _, _ -> }) }
        assertThrows(IOException::class.java) { owner.close() }
        assertEquals(1, releases); assertFalse(owner.confirmed)
        assertThrows(IOException::class.java) { owner.close() }
        assertEquals(1, releases)
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
