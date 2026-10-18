package net.fstab.tachiai.platform.media

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.os.Handler

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import java.util.UUID
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        val observations = Collections.synchronizedList(mutableListOf<Pair<NativeFailureStage, Throwable>>())
        val budget = NativePlaybackBudget(60_000, { true })
        val complete = CountDownLatch(1)
        lateinit var host: BoundedNativePlayer
        lateinit var receipt: NativeCleanupReceipt
        val first = IOException("synthetic cleanup failure")
        val connection = FailedConnection(first)
        val other = FailedConnection(null)
        instrumentation.runOnMainSync {
            host = BoundedNativePlayer(instrumentation.targetContext, budget, Long.MAX_VALUE,
                allowedUri = { false }, onEvent = { _, _ -> },
                onFailure = { stage, error -> observations += stage to error },
                onCleanupOwner = { receipt = it }, onCleanupChanged = { complete.countDown() })
            installConnection(host, "requests", connection)
            installConnection(host, "requests", other)
        }
        try {
            instrumentation.runOnMainSync {
                host.close(); host.close()
                assertTrue(receipt.pending); assertFalse(receipt.confirmed)
                assertFalse(host.player.isReleased)
            }
            awaitCleanup(complete)
            instrumentation.runOnMainSync {
                assertTrue(host.player.isReleased)
                assertQualityClosed(host)
                assertFalse(budget.active)
                assertFalse(receipt.pending); assertFalse(receipt.confirmed)
                assertEquals(1, connection.closes)
                assertEquals(1, other.closes)
                assertNotSame(Looper.getMainLooper().thread, connection.closeThread)
                assertNotSame(Looper.getMainLooper().thread, other.closeThread)
                assertEquals(listOf(NativeFailureStage.MEDIA_DISCONNECT, NativeFailureStage.PLAYER_RELEASE),
                    observations.map { it.first })
                assertTrue(observations.all { it.second === first })
                assertThrows(IOException::class.java) { host.close() }
                assertEquals(1, connection.closes)
            }
        } finally {
            instrumentation.runOnMainSync { if (!host.player.isReleased) host.player.release() }
        }
    }

    @Test fun dashSealsFirstAndReleasesActualPlayerEvenWhenBothRequestGroupsFail() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val budget = NativePlaybackBudget(60_000, { true })
        val complete = CountDownLatch(1)
        lateinit var host: BoundedNativeDashPlayer
        lateinit var receipt: NativeCleanupReceipt
        val first = IllegalStateException("synthetic response-close failure")
        val second = IOException("synthetic manifest-close failure")
        val media = FailedConnection(first) { calls += "media" }
        val manifest = FailedConnection(second) { calls += "manifest" }
        instrumentation.runOnMainSync {
            host = BoundedNativeDashPlayer(instrumentation.targetContext, budget,
                expectedKids = listOf(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                allowedUri = { false }, manifestUri = URI("https://fixture.invalid/never"),
                manifestParser = DashManifestParser(), exchange = { fail("No DRM exchange permitted"); null },
                onEvent = { _, _ -> }, onMediaEvent = { _, _ -> }, beforeRelease = {
                    assertSame(Looper.getMainLooper(), Looper.myLooper()); calls += "seal"
                }, onFailure = { stage, error -> if (stage == NativeFailureStage.PLAYER_RELEASE) failures += error },
                onCleanupOwner = { receipt = it }, onCleanupChanged = { complete.countDown() })
            installConnection(host, "requests", media)
            installConnection(host, "manifests", manifest)
        }
        try {
            instrumentation.runOnMainSync {
                host.close(); host.close()
                assertTrue(receipt.pending); assertFalse(receipt.confirmed)
            }
            awaitCleanup(complete)
            instrumentation.runOnMainSync {
                assertSame(first, failures.single())
                assertArrayEquals(arrayOf(second), first.suppressed)
                assertEquals(listOf("seal", "media", "manifest"), calls)
                assertTrue(host.player.isReleased)
                assertQualityClosed(host)
                assertFalse(budget.active)
                assertFalse(receipt.pending); assertFalse(receipt.confirmed)
                assertNotSame(Looper.getMainLooper().thread, media.closeThread)
                assertNotSame(Looper.getMainLooper().thread, manifest.closeThread)
                assertThrows(IOException::class.java) { host.close() }
                assertEquals(1, media.closes)
                assertEquals(1, manifest.closes)
                assertEquals(3, calls.size)
            }
        } finally {
            instrumentation.runOnMainSync { if (!host.player.isReleased) host.player.release() }
        }
    }

    @Test fun hlsAndDashCloseOwnedHttpsAndInputsOffMainBeforeOwnerPlayerAndQualityRelease() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (dash in listOf(false, true)) {
            val complete = CountDownLatch(1)
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            val ownerSteps = mutableListOf<String>()
            val mediaUri = URI("https://fixture.invalid/media")
            val manifestUri = URI("https://fixture.invalid/manifest")
            val media = TransportConnection(mediaUri.toURL())
            val manifest = TransportConnection(manifestUri.toURL())
            val budget = NativePlaybackBudget(60_000, { true })
            lateinit var host: NativePairMember
            lateinit var player: ExoPlayer
            lateinit var receipt: NativeCleanupReceipt
            instrumentation.runOnMainSync {
                val own: (NativeCleanupReceipt) -> Unit = { owned ->
                    receipt = owned
                    owned.own(31, ownerThread = true) {
                        assertSame(player.applicationLooper, Looper.myLooper())
                        assertTrue(player.isReleased); ownerSteps += "player"
                    }
                    owned.own(41, ownerThread = true) {
                        assertSame(player.applicationLooper, Looper.myLooper())
                        assertQualityClosed(host); ownerSteps += "quality"
                    }
                }
                val open: (URL) -> HttpsURLConnection = { url ->
                    when (url.toURI()) {
                        mediaUri -> media
                        manifestUri -> manifest
                        else -> throw AssertionError("Unexpected synthetic resource")
                    }
                }
                host = if (dash) BoundedNativeDashPlayer(instrumentation.targetContext, budget,
                    expectedKids = listOf(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                    allowedUri = { it == mediaUri }, manifestUri = manifestUri,
                    manifestParser = DashManifestParser(), exchange = { fail("No DRM exchange permitted"); null },
                    onEvent = { _, _ -> }, onMediaEvent = { _, _ -> }, beforeRelease = {
                        assertSame(Looper.getMainLooper(), Looper.myLooper()); ownerSteps += "seal"
                    }, openConnection = open, onFailure = { _, error -> failures += error },
                    onCleanupOwner = own, onCleanupChanged = { complete.countDown() })
                else BoundedNativePlayer(instrumentation.targetContext, budget, Long.MAX_VALUE,
                    allowedUri = { it == mediaUri }, onEvent = { _, _ -> }, openConnection = open,
                    onFailure = { _, error -> failures += error },
                    onCleanupOwner = own, onCleanupChanged = { complete.countDown() })
                player = when (val created = host) {
                    is BoundedNativePlayer -> created.player
                    is BoundedNativeDashPlayer -> created.player
                    else -> throw AssertionError("Unexpected synthetic player")
                }
            }
            try {
                // Exercise the real ownership wrappers while returning only
                // synthetic streams. No player preparation or network occurs.
                val requests = field(host, "requests") as BoundedMediaRequests
                assertEquals(1L, requests.create(C.DATA_TYPE_MEDIA).openUri(mediaUri))
                if (dash) {
                    val manifests = field(host, "manifests") as BoundedMediaRequests
                    assertEquals(1L, manifests.create(C.DATA_TYPE_MANIFEST).openUri(manifestUri))
                }
                instrumentation.runOnMainSync {
                    host.close(); host.close()
                    assertTrue(receipt.pending); assertFalse(receipt.confirmed)
                }
                awaitCleanup(complete)
                instrumentation.runOnMainSync {
                    assertTrue("Unexpected cleanup failures: ${failures.size}", failures.isEmpty())
                    assertTrue(receipt.confirmed); assertFalse(receipt.pending)
                    assertEquals(if (dash) listOf("seal", "player", "quality") else listOf("player", "quality"), ownerSteps)
                    assertTrue(player.isReleased); assertQualityClosed(host)
                    for (connection in if (dash) listOf(media, manifest) else listOf(media)) {
                        assertTrue(connection.disconnectThreads.isNotEmpty())
                        assertTrue(connection.disconnectThreads.all { it !== Looper.getMainLooper().thread })
                        assertEquals(1, connection.inputCloses)
                        assertNotSame(Looper.getMainLooper().thread, connection.inputCloseThread)
                    }
                    host.close()
                }
            } finally {
                instrumentation.runOnMainSync { if (!player.isReleased) player.release() }
            }
        }
    }

    @Test fun hlsAndDashStopPlaybackImmediatelyWhileTransportCleanupIsBlocked() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (dash in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val unblock = CountDownLatch(1)
            val complete = CountDownLatch(1)
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            val connection = FailedConnection(null) {
                entered.countDown()
                check(unblock.await(10, TimeUnit.SECONDS)) { "Synthetic disconnect was not unblocked" }
            }
            lateinit var host: NativePairMember
            lateinit var player: ExoPlayer
            lateinit var receipt: NativeCleanupReceipt
            instrumentation.runOnMainSync {
                val budget = NativePlaybackBudget(60_000, { true })
                host = if (dash) BoundedNativeDashPlayer(instrumentation.targetContext, budget,
                    expectedKids = listOf(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                    allowedUri = { false }, manifestUri = URI("https://fixture.invalid/never"),
                    manifestParser = DashManifestParser(), exchange = { fail("No DRM exchange permitted"); null },
                    onEvent = { _, _ -> }, onMediaEvent = { _, _ -> }, beforeRelease = {},
                    onFailure = { _, error -> failures += error },
                    onCleanupOwner = { receipt = it }, onCleanupChanged = { complete.countDown() })
                else BoundedNativePlayer(instrumentation.targetContext, budget, Long.MAX_VALUE,
                    allowedUri = { false }, onEvent = { _, _ -> },
                    onFailure = { _, error -> failures += error },
                    onCleanupOwner = { receipt = it }, onCleanupChanged = { complete.countDown() })
                player = when (val created = host) {
                    is BoundedNativePlayer -> created.player
                    is BoundedNativeDashPlayer -> created.player
                    else -> throw AssertionError("Unexpected synthetic player")
                }
                installConnection(host, "requests", connection)
                player.volume = 0.75f
                player.playWhenReady = true
            }
            try {
                instrumentation.runOnMainSync {
                    host.close()
                    assertFalse("Stop clears playback intent before returning", player.playWhenReady)
                    assertEquals("Stop silences buffered playback before transport cleanup", 0f, player.volume, 0f)
                    assertTrue(receipt.pending); assertFalse(receipt.confirmed)
                    assertFalse(player.isReleased)
                }
                assertTrue("Synthetic disconnect reached the worker", entered.await(5, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    assertFalse(player.playWhenReady); assertEquals(0f, player.volume, 0f)
                    assertTrue(receipt.pending); assertFalse(player.isReleased)
                    assertNotSame(Looper.getMainLooper().thread, connection.closeThread)
                }
                unblock.countDown()
                awaitCleanup(complete)
                instrumentation.runOnMainSync {
                    assertTrue("Unexpected cleanup failures: ${failures.size}", failures.isEmpty())
                    assertTrue(receipt.confirmed); assertFalse(receipt.pending)
                    assertTrue(player.isReleased); assertQualityClosed(host)
                    assertEquals(1, connection.closes)
                    host.close()
                }
            } finally {
                unblock.countDown()
                if (receipt.pending) complete.await(10, TimeUnit.SECONDS)
                instrumentation.runOnMainSync { if (!player.isReleased) player.release() }
            }
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

    @Test(timeout = 30_000) fun asyncVoidReleaseTimeoutReportsOnceAndContinuesEveryCleanupLane() {
        assertAsyncRelease(timeout = true, uncertain = false)
    }

    @Test(timeout = 30_000) fun asyncSuccessfulReleaseAndUnrelatedUncertaintyNeverInventTimeout() {
        assertAsyncRelease(timeout = false, uncertain = false)
        assertAsyncRelease(timeout = false, uncertain = true)
        assertAsyncRelease(timeout = false, uncertain = true, earlyTimeout = true)
    }

    private fun assertAsyncRelease(timeout: Boolean, uncertain: Boolean, earlyTimeout: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val handler = Handler(Looper.getMainLooper())
        val owner = NativeCleanupReceipt()
        val calls = Collections.synchronizedList(mutableListOf<Pair<String, Thread>>())
        val observations = Collections.synchronizedList(mutableListOf<Pair<NativeFailureStage, Throwable>>())
        val complete = CountDownLatch(1)
        val completions = AtomicInteger()
        var listener: Player.Listener? = null
        var now = 100L
        var releases = 0
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "addListener" -> { listener = args!![0] as Player.Listener; null }
                "release" -> {
                    releases++; calls += "player" to Thread.currentThread()
                    if (timeout) repeat(2) { checkNotNull(listener).onPlayerError(
                        PlaybackException("synthetic-private-provider-message", IOException("synthetic-private-cause"), 1003)) }
                    now = 124L
                    null // Media3's release returns void even after its timeout.
                }
                else -> throw AssertionError("Unexpected fixture player operation")
            }
        } as Player
        val observer: NativeFailureObserver = { stage, error -> observations += stage to error }
        val completed = { completions.incrementAndGet(); complete.countDown() }
        instrumentation.runOnMainSync {
            owner.own(10) {
                calls += "before" to Thread.currentThread()
                if (earlyTimeout) checkNotNull(listener).onPlayerError(PlaybackException("synthetic-private-queued", null, 1003))
                else if (uncertain) owner.failed()
            }
            owner.ownPlayer(player, { true }, observer, { now })
            owner.own(35) { calls += "after" to Thread.currentThread() }
            owner.own(40, ownerThread = true) { calls += "quality" to Thread.currentThread() }
            owner.seal()
            owner.closePlayer(handler, observer, completed)
            owner.closePlayer(handler, observer, completed)
        }
        awaitCleanup(complete)
        instrumentation.runOnMainSync {
            assertEquals(listOf("before", "player", "after", "quality"), calls.map { it.first })
            assertNotSame(Looper.getMainLooper().thread, calls[0].second)
            assertSame(Looper.getMainLooper().thread, calls[1].second)
            assertNotSame(Looper.getMainLooper().thread, calls[2].second)
            assertSame(Looper.getMainLooper().thread, calls[3].second)
            assertEquals(1, releases); assertEquals(1, completions.get())
            assertFalse(owner.pending); assertEquals(!timeout && !uncertain, owner.confirmed)
            val timeouts = observations.map { it.second }.filterIsInstance<NativePlayerReleaseTimeoutException>()
            assertEquals(if (timeout) 1 else 0, timeouts.size)
            if (timeout) {
                assertEquals(1003, timeouts.single().errorCode)
                assertEquals(24L, timeouts.single().elapsedMs)
                assertNull(timeouts.single().cause); assertTrue(timeouts.single().suppressed.isEmpty())
                assertFalse(timeouts.single().toString().contains("synthetic-private"))
            }
            assertTrue(observations.all { it.first == NativeFailureStage.PLAYER_RELEASE })
            val ordinary = observations.map { it.second }.filterNot { it is NativePlayerReleaseTimeoutException }
            assertEquals(if (timeout || uncertain) 1 else 0, ordinary.size)
            assertTrue(ordinary.all { it is IOException })
            val reports = observations.size
            if (timeout || uncertain) assertThrows(IOException::class.java) {
                owner.closePlayer(handler, observer, completed)
            } else owner.closePlayer(handler, observer, completed)
            if (timeout) checkNotNull(listener).onPlayerError(PlaybackException("synthetic-private-late", null, 1003))
            assertEquals(reports, observations.size)
            assertEquals(1, releases); assertEquals(1, completions.get())
            assertEquals(!timeout && !uncertain, owner.confirmed)
        }
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

    private fun awaitCleanup(complete: CountDownLatch) {
        assertTrue("Asynchronous native cleanup completed", complete.await(10, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

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
        var closeThread: Thread? = null
        override fun disconnect() { closes++; closeThread = Thread.currentThread(); onClose(); error?.let { throw it } }
        override fun connect() = fail("No connection permitted")
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    private class TransportConnection(url: URL) : HttpsURLConnection(url) {
        val disconnectThreads = mutableListOf<Thread>()
        var inputCloses = 0
        var inputCloseThread: Thread? = null
        private val stream = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { inputCloses++; inputCloseThread = Thread.currentThread(); super.close() }
        }
        override fun disconnect() { disconnectThreads += Thread.currentThread() }
        override fun connect() = fail("No connection permitted")
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = 200
        override fun getContentLengthLong() = 1L
        override fun getInputStream() = stream
    }
}
