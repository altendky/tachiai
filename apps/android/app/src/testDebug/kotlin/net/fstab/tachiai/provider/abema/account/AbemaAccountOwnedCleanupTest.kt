package net.fstab.tachiai.provider.abema.account

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.network.RoutePreparation
import net.fstab.tachiai.platform.network.RouteBackend
import net.fstab.tachiai.platform.network.parseConnectionProfile
import org.junit.Assert.*
import org.junit.Test

// The actual wrapper's cleanup function, actual HTTP receipts and fake route
// owners establish ordering without opening a provider/native connection.
class AbemaAccountOwnedCleanupTest {
    private class Backend(private val fail: Boolean = false, private val fatalPort: Boolean = false,
        private val hold: (() -> Unit)? = null) : RouteBackend {
        override val proxyPort get(): Int { if (fatalPort) throw AssertionError("PRIVATE CONSTRUCTOR ERROR"); return 12345 }
        var closes = 0
        override fun close() { closes++; hold?.invoke(); if (fail) throw AssertionError("PRIVATE BACKEND CLOSE") }
    }
    private class Connection(private val streamError: Boolean, private val disconnectError: Boolean,
        private val hold: (() -> Unit)? = null) : HttpsURLConnection(URL("https://api.p-c3-e.abema-tv.com/v1/users/guest")) {
        var streamCloses = 0; var disconnects = 0
        override fun getResponseCode() = 200
        override fun getContentType() = "application/json"
        override fun getContentLengthLong() = -1L
        override fun getInputStream(): InputStream = object : ByteArrayInputStream("{\"profile\":{\"userId\":\"guest\"}}".toByteArray()) {
            override fun close() { streamCloses++; if (streamError) throw AssertionError("PRIVATE STREAM ERROR"); super.close() }
        }
        override fun disconnect() { disconnects++; hold?.invoke(); if (disconnectError) throw AssertionError("PRIVATE DISCONNECT ERROR") }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }
    @Test fun actualOwnedCleanupNeverReachesRouteOrBackendCloseAfterAnUnconfirmedHttpClose() {
        for (stream in listOf(true, false)) {
            val c = Connection(stream, !stream)
            val transport = AbemaAccountHttpTransport({ c }, { true }, { 1000 }, { 1000000 })
            try { transport.profile("guest", "guest-bearer"); fail() } catch (error: AbemaAccountException) {
                assertEquals(AbemaAccountFailure.CLEANUP_FAILED, error.category)
            }
            var routeCloses = 0; var backendCloses = 0
            val route = AutoCloseable { routeCloses++; backendCloses++ }
            repeat(2) { assertFalse(closeAbemaAccountOwnedResources(RoutePreparation(), transport, route)) }
            assertEquals(0, routeCloses); assertEquals(0, backendCloses)
            assertEquals(1, c.streamCloses); assertEquals(1, c.disconnects)
        }
    }
    @Test fun firstHeldNativeCleanupHasAFixedFailureAndCannotReleaseRouteEvenAfterLateCompletion() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        val c = Connection(false, false) { entered.countDown(); try { check(release.await(5, TimeUnit.SECONDS)) } finally { finished.countDown() } }
        val transport = AbemaAccountHttpTransport({ c }, { true }, { 1000 }, { 1000000 }, cleanupWaitMs = 50)
        val worker = Executors.newSingleThreadExecutor(); var routeCloses = 0
        val route = AutoCloseable { routeCloses++ }
        try {
            val result = worker.submit<Boolean> { try { transport.profile("guest", "guest-bearer"); false } catch (error: AbemaAccountException) { error.category == AbemaAccountFailure.CLEANUP_FAILED } }
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertTrue(result.get(1, TimeUnit.SECONDS))
            assertFalse(closeAbemaAccountOwnedResources(RoutePreparation(), transport, route)); assertEquals(0, routeCloses)
            release.countDown(); assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertFalse(closeAbemaAccountOwnedResources(RoutePreparation(), transport, route)); assertEquals(0, routeCloses)
            assertEquals(1, c.disconnects)
        } finally { release.countDown(); worker.shutdownNow() }
    }
    @Test fun routeCleanupOccursAfterConfirmedHttpAndNeverAfterFailedPreparation() {
        val c = Connection(false, false); val transport = AbemaAccountHttpTransport({ c }, { true }, { 1000 }, { 1000000 })
        assertTrue(transport.profile("guest", "guest-bearer") is AbemaAccountProfileResult.Identity)
        var closes = 0
        assertTrue(closeAbemaAccountOwnedResources(RoutePreparation(), transport, AutoCloseable {
            assertTrue(transport.cleanupConfirmed); assertEquals(1, c.streamCloses); assertEquals(1, c.disconnects); closes++
        }))
        assertEquals(1, closes)
        val failed = RoutePreparation().apply { recordCleanupFailure() }
        assertFalse(closeAbemaAccountOwnedResources(failed, null, AutoCloseable { closes++ }))
        assertEquals(1, closes)
        assertFalse(closeAbemaAccountOwnedResources(RoutePreparation(), null, AutoCloseable { throw AssertionError("PRIVATE BACKEND ERROR") }))
    }
    private fun ownedRetains(resource: Any): Boolean {
        val retained = OwnedAbemaAccountTransport::class.java.getDeclaredField("retained").apply { isAccessible = true }.get(null) as Iterable<*>
        return retained.any { entry -> (entry as Collection<*>).any { it === resource } }
    }
    @Test fun actualWrapperRetainsBackendRouteAndHttpWithoutBackendReleaseOnHttpUncertainty() {
        for (stream in listOf(true, false)) {
            val backend = Backend(); val c = Connection(stream, !stream); var poisoned = 0
            lateinit var http: AbemaAccountHttpTransport
            val owned = OwnedAbemaAccountTransport(parseConnectionProfile("http://fixture:private@proxy.example.test:3128".toByteArray()),
                { true }, { poisoned++ }, createBackend = { _, _, _ -> backend }, makeHttpTransport = { _, gate ->
                    AbemaAccountHttpTransport({ c }, gate, { 1000 }, { 1000000 }).also { http = it }
                })
            try { owned.profile("guest", "guest-bearer"); fail() } catch (error: AbemaAccountException) {
                assertEquals(AbemaAccountFailure.CLEANUP_FAILED, error.category); assertNull(error.cause)
            }
            assertEquals(0, backend.closes); assertEquals(1, poisoned)
            assertTrue(ownedRetains(backend)); assertTrue(ownedRetains(http)); assertFalse(http.cleanupConfirmed)
            repeat(2) { try { owned.close(); fail() } catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.CLEANUP_FAILED, error.category) } }
            assertEquals(0, backend.closes); assertEquals(1, c.streamCloses); assertEquals(1, c.disconnects)
        }
    }
    @Test fun returnedBackendIsRetainedWhenConstructorCancellationRollbackFailsBeforeRouteDelivery() {
        val backend = Backend(true); var poisoned = 0; var constructedHttp = false
        val owned = OwnedAbemaAccountTransport(parseConnectionProfile("http://fixture:private@proxy.example.test:3128".toByteArray()),
            { true }, { poisoned++ }, createBackend = { _, _, preparation -> preparation.cancel(); backend },
            makeHttpTransport = { _, _ -> constructedHttp = true; error("Must not create HTTP") })
        try { owned.profile("guest", "guest-bearer"); fail() } catch (error: AbemaAccountException) {
            assertEquals(AbemaAccountFailure.CLEANUP_FAILED, error.category); assertNull(error.cause)
        }
        assertFalse(constructedHttp); assertEquals(1, backend.closes); assertEquals(1, poisoned); assertTrue(ownedRetains(backend))
        try { owned.close(); fail() } catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.CLEANUP_FAILED, error.category) }
        assertEquals(1, backend.closes)
    }
    @Test fun constructorErrorAfterAllocationClosesOnceAndRetainsTheActualBackendWithoutPublishingHttp() {
        val backend = Backend(fatalPort = true); var poisoned = 0; var constructedHttp = false
        val owned = OwnedAbemaAccountTransport(parseConnectionProfile("http://fixture:private@proxy.example.test:3128".toByteArray()),
            { true }, { poisoned++ }, createBackend = { _, _, _ -> backend },
            makeHttpTransport = { _, _ -> constructedHttp = true; error("Must not create HTTP") })
        try { owned.profile("guest", "guest-bearer"); fail() } catch (error: AbemaAccountException) {
            assertEquals(AbemaAccountFailure.CLEANUP_FAILED, error.category); assertNull(error.cause)
            assertFalse(error.toString().contains("PRIVATE"))
        }
        assertFalse(constructedHttp); assertEquals(1, backend.closes); assertEquals(1, poisoned); assertTrue(ownedRetains(backend))
        repeat(2) { try { owned.close(); fail() } catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.CLEANUP_FAILED, error.category) } }
        assertEquals(1, backend.closes)
    }
    @Test fun confirmedCancellationRollbackBeforeDeliveryDoesNotRetryOrPoisonTheBackend() {
        val backend = Backend(); var poisoned = 0; var constructedHttp = false
        val owned = OwnedAbemaAccountTransport(parseConnectionProfile("http://fixture:private@proxy.example.test:3128".toByteArray()),
            { true }, { poisoned++ }, createBackend = { _, _, preparation -> preparation.cancel(); backend },
            makeHttpTransport = { _, _ -> constructedHttp = true; error("Must not create HTTP") })
        try { owned.profile("guest", "guest-bearer"); fail() } catch (_: java.io.IOException) { }
        assertFalse(constructedHttp); assertEquals(1, backend.closes); assertEquals(0, poisoned)
        owned.close(); owned.close(); assertEquals(1, backend.closes)
    }
    @Test fun firstUndeliveredBackendCloseIsBoundedAndLateCompletionCannotUnpoisonItsOwner() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        val backend = Backend(hold = { entered.countDown(); try { check(release.await(5, TimeUnit.SECONDS)) } finally { finished.countDown() } })
        var poisoned = 0; var constructedHttp = false
        val owned = OwnedAbemaAccountTransport(parseConnectionProfile("http://fixture:private@proxy.example.test:3128".toByteArray()),
            { true }, { poisoned++ }, createBackend = { _, _, preparation -> preparation.cancel(); backend },
            makeHttpTransport = { _, _ -> constructedHttp = true; error("Must not create HTTP") })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { try { owned.profile("guest", "guest-bearer"); false } catch (error: AbemaAccountException) { error.category == AbemaAccountFailure.CLEANUP_FAILED } }
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertTrue(result.get(3, TimeUnit.SECONDS))
            assertFalse(constructedHttp); assertEquals(1, backend.closes); assertEquals(1, poisoned); assertTrue(ownedRetains(backend))
            release.countDown(); assertTrue(finished.await(1, TimeUnit.SECONDS))
            repeat(2) { try { owned.close(); fail() } catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.CLEANUP_FAILED, error.category) } }
            assertEquals(1, backend.closes)
        } finally { release.countDown(); worker.shutdownNow() }
    }
}
