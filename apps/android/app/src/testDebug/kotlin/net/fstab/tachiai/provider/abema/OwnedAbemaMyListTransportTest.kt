package net.fstab.tachiai.provider.abema

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.network.RouteBackend
import net.fstab.tachiai.platform.network.parseConnectionProfile
import org.junit.Assert.*
import org.junit.Test

// Actual closed-purpose RouteSession construction and actual inner HTTP cleanup;
// all backends/connections are injected in memory, never native or networked.
class OwnedAbemaMyListTransportTest {
    private class Hostile : Error() {
        override val message: String get() = throw AssertionError("Private error getter used")
        override fun toString(): String = throw AssertionError("Private error text used")
    }
    private class Backend(val portError: Boolean = false, val failed: Boolean = false,
        val closing: () -> Unit = {}) : RouteBackend {
        val closes = AtomicInteger()
        override val proxyPort: Int get() { if (portError) throw Hostile(); return 12345 }
        override fun close() { closes.incrementAndGet(); closing(); if (failed) throw Hostile() }
    }
    private class Connection(val streamFailed: Boolean = false, val disconnectFailed: Boolean = false,
        val streamClosing: () -> Unit = {}, val disconnecting: () -> Unit = {},
        val requestUrl: URL = abemaMyListRequestUri().toURL()) : HttpsURLConnection(requestUrl) {
        val streamCloses = AtomicInteger(); val disconnects = AtomicInteger()
        override fun getResponseCode() = 200
        override fun getContentType() = "application/json"
        override fun getContentLengthLong() = -1L
        override fun getInputStream(): InputStream = object : ByteArrayInputStream("""{"mylist":[],"dataSet":{}}""".toByteArray()) {
            override fun close() { streamCloses.incrementAndGet(); streamClosing(); if (streamFailed) throw Hostile(); super.close() }
        }
        override fun disconnect() { disconnects.incrementAndGet(); disconnecting(); if (disconnectFailed) throw Hostile() }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private val profile get() = parseConnectionProfile("http://fixture:private@proxy.example.test:3128".toByteArray())
    private fun response() = AbemaMyListResponse(200, parseAbemaMyListResponse("""{"mylist":[],"dataSet":{}}"""))
    private fun failure(category: AbemaCollectionFailure, action: () -> Unit) {
        val error = assertThrows(AbemaCollectionException::class.java, action)
        assertEquals(category, error.category); assertEquals(category.name, error.message)
        assertNull(error.cause); assertEquals(0, error.suppressed.size); assertEquals(0, error.stackTrace.size)
    }
    private fun retained(resource: Any): Boolean {
        val values = OwnedAbemaMyListTransport::class.java.getDeclaredField("retained").apply { isAccessible = true }.get(null) as Iterable<*>
        return values.any { entry -> (entry as Collection<*>).any { it === resource } }
    }

    @Test fun cleanActualHttpAndRouteCleanupAreOrderedExactlyOnceBeforeResultPublication() {
        val order = mutableListOf<String>()
        val backend = Backend(closing = { order.add("backend") })
        val connection = Connection(streamClosing = { order.add("stream") }, disconnecting = { order.add("disconnect") })
        var reads = 0; var poison = 0
        val owned = OwnedAbemaMyListTransport(profile, { reads++; "fixture-bearer" }, { true }, { poison++ },
            createBackend = { _, security, _ ->
                assertEquals("api.p-c3-e.abema-tv.com", security.allowedHosts); backend
            }, createTransport = { _, bearer, gate -> AbemaMyListHttpTransport({ connection }, bearer, gate, clockMs = { 1000 }) })
        val result = owned.fetchMyList(null)
        assertEquals(200, result.status); assertTrue(result.page!!.entries.isEmpty())
        assertEquals(listOf("stream", "disconnect", "backend"), order)
        assertEquals(1, reads); assertEquals(0, poison)
        owned.close(); owned.close()
        assertEquals(1, backend.closes.get()); assertEquals(1, connection.disconnects.get()); assertEquals(1, connection.streamCloses.get())
        assertFalse(retained(backend)); assertEquals("OwnedAbemaMyListTransport(redacted)", owned.toString())
    }

    @Test fun cancellationRollbackCapturesBackendAndClosesOnceBeforeAnyHttpConstruction() {
        for (failed in listOf(false, true)) {
            val backend = Backend(failed = failed); var poison = 0; var httpCreated = false
            val owned = OwnedAbemaMyListTransport(profile, { "fixture" }, { true }, { poison++ },
                createBackend = { _, _, preparing -> preparing.cancel(); backend },
                createTransport = { _, _, _ -> httpCreated = true; throw AssertionError("HTTP must not be created") })
            failure(if (failed) AbemaCollectionFailure.CLEANUP_FAILED else AbemaCollectionFailure.CANCELLED) { owned.fetchMyList(null) }
            assertFalse(httpCreated); assertEquals(1, backend.closes.get()); assertEquals(if (failed) 1 else 0, poison)
            assertEquals(failed, retained(backend))
            if (failed) repeat(2) { failure(AbemaCollectionFailure.CLEANUP_FAILED) { owned.close() } }
            else { owned.close(); owned.close() }
            assertEquals(1, backend.closes.get())
        }
    }

    @Test fun constructorErrorAfterBackendAllocationIsSanitizedRetainedAndNeverUsesSystem() {
        val backend = Backend(portError = true); var poison = 0; var httpCreated = false
        val owned = OwnedAbemaMyListTransport(profile, { "fixture" }, { true }, { poison++ },
            createBackend = { _, _, _ -> backend },
            createTransport = { _, _, _ -> httpCreated = true; throw AssertionError("No HTTP after constructor Error") })
        failure(AbemaCollectionFailure.CLEANUP_FAILED) { owned.fetchMyList(null) }
        assertFalse(httpCreated); assertEquals(1, backend.closes.get()); assertEquals(1, poison)
        assertTrue(retained(backend)); failure(AbemaCollectionFailure.CLEANUP_FAILED) { owned.close() }
        assertEquals(1, backend.closes.get())
    }

    @Test fun heldBackendRollbackHasBoundedStickyFailureEvenAfterItsLateCloseCompletes() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val completed = CountDownLatch(1)
        val backend = Backend(closing = { entered.countDown(); try { check(release.await(5, TimeUnit.SECONDS)) } finally { completed.countDown() } })
        val poisons = AtomicInteger(); val worker = Executors.newSingleThreadExecutor()
        val owned = OwnedAbemaMyListTransport(profile, { "fixture" }, { true }, { poisons.incrementAndGet() },
            createBackend = { _, _, preparation -> preparation.cancel(); backend }, cleanupWaitMs = 50,
            createTransport = { _, _, _ -> throw AssertionError("No HTTP after cancelled construction") })
        try {
            val result = worker.submit<Boolean> { try { owned.fetchMyList(null); false }
                catch (error: AbemaCollectionException) { error.category == AbemaCollectionFailure.CLEANUP_FAILED } }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(result.get(2, TimeUnit.SECONDS))
            assertTrue(retained(backend)); assertEquals(1, poisons.get()); assertEquals(1, backend.closes.get())
            release.countDown(); assertTrue(completed.await(2, TimeUnit.SECONDS))
            failure(AbemaCollectionFailure.CLEANUP_FAILED) { owned.close() }
            failure(AbemaCollectionFailure.CLEANUP_FAILED) { owned.fetchMyList(null) }
            assertEquals(1, backend.closes.get())
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun actualFatalInnerCleanupCannotReachBackendReleaseAndKeepsBothOwners() {
        for (stream in listOf(false, true)) {
            val backend = Backend(); val connection = Connection(streamFailed = stream, disconnectFailed = !stream)
            var poisons = 0; lateinit var http: AbemaMyListHttpTransport
            val owned = OwnedAbemaMyListTransport(profile, { "fixture" }, { true }, { poisons++ },
                createBackend = { _, _, _ -> backend }, createTransport = { _, bearer, gate ->
                    AbemaMyListHttpTransport({ connection }, bearer, gate, clockMs = { 1000 }).also { http = it }
                })
            failure(AbemaCollectionFailure.CLEANUP_FAILED) { owned.fetchMyList(null) }
            assertEquals(0, backend.closes.get()); assertEquals(1, poisons)
            assertTrue(retained(backend)); assertTrue(retained(http))
            repeat(2) { failure(AbemaCollectionFailure.CLEANUP_FAILED) { owned.close() } }
            assertEquals(0, backend.closes.get()); assertEquals(1, connection.streamCloses.get()); assertEquals(1, connection.disconnects.get())
        }
    }

    @Test fun heldInnerCloseNeverReleasesBackendOrLaundersLateCompletionAndCallbackErrorsAreContained() {
        val backend = Backend(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val completed = CountDownLatch(1)
        val closes = AtomicInteger(); val poisons = AtomicInteger()
        val inner = object : AbemaCollectionTransport {
            override fun fetchMyList(next: String?) = response()
            override fun close() { closes.incrementAndGet(); entered.countDown(); try { check(release.await(5, TimeUnit.SECONDS)) } finally { completed.countDown() } }
        }
        val owned = OwnedAbemaMyListTransport(profile, { "fixture" }, { true }, { poisons.incrementAndGet(); throw Hostile() },
            createBackend = { _, _, _ -> backend }, createTransport = { _, _, _ -> inner }, cleanupWaitMs = 50)
        try {
            failure(AbemaCollectionFailure.CLEANUP_FAILED) { owned.fetchMyList(null) }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); assertEquals(0, backend.closes.get())
            assertTrue(retained(inner)); assertTrue(retained(backend)); assertEquals(1, poisons.get())
            release.countDown(); assertTrue(completed.await(2, TimeUnit.SECONDS))
            failure(AbemaCollectionFailure.CLEANUP_FAILED) { owned.close() }
            assertEquals(0, backend.closes.get()); assertEquals(1, closes.get())
        } finally { release.countDown() }
    }

    @Test fun exactRequestAdmissionRejectsOtherEndpointsBeforeTheOwnedRouteCanOpenThem() {
        val backend = Backend(); var bearerReads = 0
        val owned = OwnedAbemaMyListTransport(profile, { bearerReads++; "fixture" }, { true },
            createBackend = { _, _, _ -> backend }, createTransport = { open, _, _ ->
                object : AbemaCollectionTransport {
                    override fun fetchMyList(next: String?): AbemaMyListResponse {
                        open(URL("https://api.p-c3-e.abema-tv.com/v1/mylist?limit=100"))
                        throw AssertionError("Wrong request reached the route")
                    }
                    override fun close() = Unit
                }
            })
        failure(AbemaCollectionFailure.POLICY_REFUSED) { owned.fetchMyList("private-next") }
        assertEquals(0, bearerReads); assertEquals(1, backend.closes.get())
        owned.close()
    }

    @Test fun ownerLossBeforeAllocationAndAfterCleanupRejectsRequestsWithoutFallback() {
        var allowed = false; var created = 0
        val denied = OwnedAbemaMyListTransport(profile, { "fixture" }, { allowed },
            createBackend = { _, _, _ -> created++; throw AssertionError("Denied owner allocated") },
            createTransport = { _, _, _ -> throw AssertionError("Denied owner created HTTP") })
        failure(AbemaCollectionFailure.CANCELLED) { denied.fetchMyList(null) }; assertEquals(0, created)
        denied.close()
        allowed = true
        val backend = Backend(closing = { allowed = false })
        val closed = OwnedAbemaMyListTransport(profile, { "fixture" }, { allowed },
            createBackend = { _, _, _ -> backend }, createTransport = { _, _, gate ->
                object : AbemaCollectionTransport {
                    override fun fetchMyList(next: String?): AbemaMyListResponse { check(gate()); return response() }
                    override fun close() = Unit
                }
            })
        failure(AbemaCollectionFailure.CANCELLED) { closed.fetchMyList(null) }
        assertEquals(1, backend.closes.get()); assertFalse(retained(backend)); closed.close()
    }

    @Test fun cheapCloseSchedulesCancellationOffCallerAndLateResponseCannotPublish() {
        val backend = Backend(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val closes = AtomicInteger(); val caller = Thread.currentThread(); val cancellationThread = AtomicReference<Thread>()
        val inner = object : AbemaCollectionTransport {
            override fun fetchMyList(next: String?): AbemaMyListResponse { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return response() }
            override fun cancelActiveRequest() { cancellationThread.set(Thread.currentThread()); cancelled.countDown() }
            override fun close() { closes.incrementAndGet() }
        }
        val owned = OwnedAbemaMyListTransport(profile, { "fixture" }, { true },
            createBackend = { _, _, _ -> backend }, createTransport = { _, _, _ -> inner })
        val result = AtomicReference<Throwable?>()
        val worker = Thread { try { owned.fetchMyList(null) } catch (error: Throwable) { result.set(error) } }
        worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
        owned.close(); assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        assertNotSame(caller, cancellationThread.get()); assertEquals(0, backend.closes.get())
        release.countDown(); worker.join(5_000); assertFalse(worker.isAlive)
        assertEquals(AbemaCollectionFailure.CANCELLED, (result.get() as AbemaCollectionException).category)
        assertEquals(1, closes.get()); assertEquals(1, backend.closes.get()); owned.close()
    }

    @Test fun cancellationStillRunningWhenFetchReturnsCannotReleaseBackendOrHideItsLateFatalFailure() {
        val backend = Backend(); val fetchEntered = CountDownLatch(1); val releaseFetch = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1); val releaseCancel = CountDownLatch(1); val cancelFinished = CountDownLatch(1)
        val closes = AtomicInteger(); val poisons = AtomicInteger()
        val inner = object : AbemaCollectionTransport {
            override fun fetchMyList(next: String?): AbemaMyListResponse {
                fetchEntered.countDown(); check(releaseFetch.await(5, TimeUnit.SECONDS)); return response()
            }
            override fun cancelActiveRequest() {
                cancelEntered.countDown()
                try { check(releaseCancel.await(5, TimeUnit.SECONDS)); throw Hostile() }
                finally { cancelFinished.countDown() }
            }
            override fun close() { closes.incrementAndGet() }
        }
        val owned = OwnedAbemaMyListTransport(profile, { "fixture" }, { true }, { poisons.incrementAndGet() },
            createBackend = { _, _, _ -> backend }, createTransport = { _, _, _ -> inner }, cleanupWaitMs = 50)
        val result = AtomicReference<Throwable?>()
        val worker = Thread { try { owned.fetchMyList(null) } catch (error: Throwable) { result.set(error) } }
        try {
            worker.start(); assertTrue(fetchEntered.await(2, TimeUnit.SECONDS))
            owned.cancelActiveRequest(); assertTrue(cancelEntered.await(2, TimeUnit.SECONDS))
            releaseFetch.countDown(); worker.join(3_000); assertFalse(worker.isAlive)
            assertEquals(AbemaCollectionFailure.CLEANUP_FAILED, (result.get() as AbemaCollectionException).category)
            assertEquals(0, backend.closes.get()); assertEquals(1, closes.get()); assertEquals(1, poisons.get())
            assertTrue(retained(inner)); assertTrue(retained(backend))
            releaseCancel.countDown(); assertTrue(cancelFinished.await(2, TimeUnit.SECONDS))
            failure(AbemaCollectionFailure.CLEANUP_FAILED) { owned.close() }
            assertEquals(0, backend.closes.get()); assertEquals(1, poisons.get())
        } finally { releaseFetch.countDown(); releaseCancel.countDown(); worker.join(5_000) }
    }

    @Test fun cancellationInsideHttpConstructionCapturesItsLateObjectBeforeClosingTheRoute() {
        val backend = Backend(); val closes = AtomicInteger(); var fetches = 0; var poisons = 0
        val inner = object : AbemaCollectionTransport {
            override fun fetchMyList(next: String?): AbemaMyListResponse { fetches++; throw AssertionError("Late object dispatched") }
            override fun close() { closes.incrementAndGet() }
        }
        lateinit var owned: OwnedAbemaMyListTransport
        owned = OwnedAbemaMyListTransport(profile, { "fixture" }, { true }, { poisons++ },
            createBackend = { _, _, _ -> backend }, createTransport = { _, _, _ -> owned.close(); inner })
        failure(AbemaCollectionFailure.CANCELLED) { owned.fetchMyList(null) }
        assertEquals(0, fetches); assertEquals(1, closes.get()); assertEquals(1, backend.closes.get()); assertEquals(0, poisons)
        owned.close(); assertEquals(1, closes.get()); assertEquals(1, backend.closes.get())
    }

    @Test fun explicitSystemRouteCanReadOneEncodedContinuationWithoutCreatingAnImportedBackend() {
        val next = "opaque+/=&limit=100"
        val connection = Connection(requestUrl = abemaMyListRequestUri(next).toURL())
        var opened = 0; var tokenReads = 0
        val owned = OwnedAbemaMyListTransport(null, { tokenReads++; "fixture" }, { true },
            createBackend = { _, _, _ -> throw AssertionError("System allocated imported backend") },
            createTransport = { _, bearer, gate -> AbemaMyListHttpTransport({ opened++; connection }, bearer, gate, clockMs = { 1000 }) })
        assertEquals(200, owned.fetchMyList(next).status)
        assertEquals(1, opened); assertEquals(1, tokenReads); assertEquals(1, connection.disconnects.get())
        owned.close()
    }

    @Test fun reentrantInnerClosePoisonsItsReceiptAndCannotReachBackendRelease() {
        val backend = Backend(); var poisons = 0
        lateinit var http: AbemaMyListHttpTransport
        val connection = Connection(disconnecting = { http.cancelActiveRequest() })
        val owned = OwnedAbemaMyListTransport(profile, { "fixture" }, { true }, { poisons++ },
            createBackend = { _, _, _ -> backend }, createTransport = { _, bearer, gate ->
                AbemaMyListHttpTransport({ connection }, bearer, gate, clockMs = { 1000 }).also { http = it }
            })
        failure(AbemaCollectionFailure.CLEANUP_FAILED) { owned.fetchMyList(null) }
        assertEquals(1, connection.disconnects.get()); assertEquals(0, backend.closes.get()); assertEquals(1, poisons)
        assertTrue(retained(http)); assertTrue(retained(backend))
    }

    @Test fun wholeRouteCleanupIsBoundedRetainedAndCannotLaunderItsLateCompletion() {
        val backend = Backend(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val completed = CountDownLatch(1)
        val innerCloses = AtomicInteger(); val poisons = AtomicInteger(); val route = AtomicReference<Any>()
        val inner = object : AbemaCollectionTransport {
            override fun fetchMyList(next: String?) = response()
            override fun close() { innerCloses.incrementAndGet() }
        }
        val owned = OwnedAbemaMyListTransport(profile, { "fixture" }, { true }, { poisons.incrementAndGet() },
            createBackend = { _, _, _ -> backend }, createTransport = { _, _, _ -> inner },
            closeRoute = { captured ->
                route.set(captured); assertEquals(1, innerCloses.get()); entered.countDown()
                try { check(release.await(5, TimeUnit.SECONDS)); captured.close() } finally { completed.countDown() }
            }, cleanupWaitMs = 50)
        try {
            failure(AbemaCollectionFailure.CLEANUP_FAILED) { owned.fetchMyList(null) }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); assertEquals(0, backend.closes.get())
            assertTrue(retained(route.get())); assertTrue(retained(backend)); assertEquals(1, poisons.get())
            release.countDown(); assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(1, backend.closes.get())
            failure(AbemaCollectionFailure.CLEANUP_FAILED) { owned.fetchMyList(null) }
            failure(AbemaCollectionFailure.CLEANUP_FAILED) { owned.close() }
            assertEquals(1, backend.closes.get()); assertEquals(1, poisons.get())
        } finally { release.countDown() }
    }

    @Test fun fatalWholeRouteCleanupIsSanitizedRetainedWithoutBackendRetry() {
        val backend = Backend(); var poisons = 0; var routeCloses = 0; var captured: Any? = null
        val owned = OwnedAbemaMyListTransport(profile, { "fixture" }, { true }, { poisons++ },
            createBackend = { _, _, _ -> backend }, createTransport = { _, _, _ ->
                object : AbemaCollectionTransport {
                    override fun fetchMyList(next: String?) = response()
                    override fun close() = Unit
                }
            }, closeRoute = { captured = it; routeCloses++; throw Hostile() })
        failure(AbemaCollectionFailure.CLEANUP_FAILED) { owned.fetchMyList(null) }
        assertEquals(1, routeCloses); assertEquals(0, backend.closes.get()); assertEquals(1, poisons)
        assertTrue(retained(captured!!)); assertTrue(retained(backend))
        repeat(2) { failure(AbemaCollectionFailure.CLEANUP_FAILED) { owned.close() } }
        assertEquals(1, routeCloses); assertEquals(0, backend.closes.get())
    }

    @Test fun twoSimultaneousImportedRouteClosesCannotStarveTheirBackendReceipts() {
        val routesEntered = CountDownLatch(2)
        val innerCloses = AtomicInteger(); val poisons = AtomicInteger()
        val backends = List(2) { Backend() }
        val owned = backends.map { backend ->
            OwnedAbemaMyListTransport(profile, { "fixture" }, { true }, { poisons.incrementAndGet() },
                createBackend = { _, _, _ -> backend }, createTransport = { _, _, _ ->
                    object : AbemaCollectionTransport {
                        override fun fetchMyList(next: String?) = response()
                        override fun close() { innerCloses.incrementAndGet() }
                    }
                }, closeRoute = { route ->
                    routesEntered.countDown()
                    check(routesEntered.await(2, TimeUnit.SECONDS))
                    assertEquals(2, innerCloses.get())
                    route.close()
                }, cleanupWaitMs = 3000)
        }
        val requests = Executors.newFixedThreadPool(2)
        try {
            val results = owned.map { transport -> requests.submit<AbemaMyListResponse> { transport.fetchMyList(null) } }
            assertTrue(routesEntered.await(2, TimeUnit.SECONDS))
            results.forEach { assertEquals(200, it.get(5, TimeUnit.SECONDS).status) }
            assertEquals(0, poisons.get()); assertEquals(2, innerCloses.get())
            backends.forEach { assertEquals(1, it.closes.get()); assertFalse(retained(it)) }
            owned.forEach { it.close(); it.close() }
            backends.forEach { assertEquals(1, it.closes.get()) }
        } finally { requests.shutdownNow() }
    }
}
