package net.fstab.tachiai.provider.abema.account

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test
import net.fstab.tachiai.platform.network.*

class AbemaAccountHttpTransportTest {
    @Test fun profileReadPreservesClosedTransportEvidenceAndCleanupFailureOverridesIt() {
        val evidence = ProviderTransportEvidence(ProviderTransportFailure.CONNECT, ProviderTransportPhase.CONNECT)
        for (failCleanup in listOf(false, true)) {
            val connection = Connection(profile).apply {
                responseHook = { throw ProviderTransportException(evidence) }
                throwClose = failCleanup
            }
            val transport = AbemaAccountHttpTransport({ connection }, { true }, { 1000 }, { 1000000 })
            val error = assertThrows(AbemaAccountException::class.java) { transport.profile("guest", "guest-bearer") }
            assertEquals(if (failCleanup) AbemaAccountFailure.CLEANUP_FAILED else AbemaAccountFailure.TEMPORARY, error.category)
            assertEquals(if (failCleanup) null else evidence, error.transportFailure)
            assertNull(error.cause); assertEquals(1, connection.disconnected); assertEquals(0, connection.sent.size())
            assertEquals(!failCleanup, transport.cleanupConfirmed)
            if (!failCleanup) transport.close()
        }
    }

    private class HostileError : Error() {
        var messageReads = 0; var stringReads = 0
        override val message: String get() { messageReads++; throw AssertionError("PRIVATE MESSAGE MUST NOT BE READ") }
        override fun toString(): String { stringReads++; throw AssertionError("PRIVATE ERROR MUST NOT BE STRINGIFIED") }
    }
    private class Connection(url: URL, val status: Int = 200, var bytes: ByteArray = "{\"profile\":{\"userId\":\"guest\"}}".toByteArray()) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream(); var opened = 0; var disconnected = 0; var throwClose = false
        var responseHook: () -> Unit = {}; var disconnectHook: () -> Unit = {}; var mediaType = "application/json; charset=UTF-8"
        var readHook: () -> Unit = {}
        var streamCloseHook: () -> Unit = {}; var streamCloses = 0
        var streamError: Throwable? = null; var disconnectError: Throwable? = null
        var returnedStream: InputStream? = null
        override fun getResponseCode(): Int { responseHook(); return status }
        override fun getContentType() = mediaType
        override fun getContentLengthLong() = bytes.size.toLong()
        override fun getInputStream(): InputStream { opened++; return object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { readHook(); return super.read(b, off, len) }
            override fun close() { streamCloses++; streamCloseHook(); streamError?.let { throw it }; super.close() }
        }.also { returnedStream = it } }
        override fun getErrorStream() = inputStream
        override fun getOutputStream() = sent
        override fun disconnect() { disconnected++; disconnectHook(); disconnectError?.let { throw it }; if (throwClose) error("PRIVATE CLOSE DETAILS") }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }
    private fun transport(connection: Connection, gate: () -> Boolean = { true }, elapsed: () -> Long = { 1000 }) =
        AbemaAccountHttpTransport({ connection }, gate, elapsed, { 1000000 })
    private val profile = URL("https://api.p-c3-e.abema-tv.com/v1/users/guest")
    private val token = URL("https://abema.tv/api/auth/oauth/device/token")
    private val email = URL("https://api.p-c3-e.abema-tv.com/v1/users/guest/email")
    @Test fun subscriptionProfileMakesOneUnchangedProfileGetAndCleansBeforeMatchingTypedResult() {
        val c = Connection(profile, bytes = """{"profile":{"userId":"guest"},"userSubscriptions":[{"expire":2000,"viewingAuthorities":[{"isPremium":true}],"featureAuthorities":[{"id":"fixture-authority","type":2}]}]}""".toByteArray())
        var allocations = 0
        val t = AbemaAccountHttpTransport({ url -> allocations++; assertEquals(profile, url); c }, { true }, { 1000 }, { 1000000 })
        val result = t.subscriptionProfile("guest", "private-bearer") as AbemaSubscriptionProfileResult.Identity
        assertEquals("guest", result.id); assertEquals(AbemaSubscriptionFieldState.PREMIUM, result.fieldState)
        assertEquals(2_000_000L, result.premiumExpiresAtMs)
        assertEquals(AbemaChaseFieldState.VERIFIED, result.chase.state)
        assertEquals("fixture-authority", result.chase.claims.single().id)
        assertEquals(2_000_000L, result.chase.claims.single().expiresAtMs)
        assertEquals(1, allocations); assertEquals("GET", c.requestMethod)
        assertEquals("Bearer private-bearer", c.getRequestProperty("Authorization")); assertEquals("application/json", c.getRequestProperty("Accept"))
        assertNull(c.getRequestProperty("Cookie")); assertEquals(0, c.sent.size()); assertFalse(c.doOutput)
        assertFalse(c.instanceFollowRedirects); assertFalse(c.useCaches)
        assertEquals(10000, c.connectTimeout); assertEquals(10000, c.readTimeout)
        assertEquals(1, c.opened); assertEquals(1, c.streamCloses); assertEquals(1, c.disconnected)
        assertTrue(t.cleanupConfirmed); t.close(); assertEquals(1, c.disconnected)
    }
    @Test fun subscriptionChaseRefusalStaysIndependentOfPremiumWithOneGetAndActualCleanup() {
        for (features in listOf("null", """[{"id":"fixture-authority","type":"2"}]""", """[{"id":"","type":2}]""")) {
            val c = Connection(profile, bytes = """{"profile":{"userId":"guest"},"userSubscriptions":[{"expire":2000,"viewingAuthorities":[{"isPremium":true}],"featureAuthorities":$features}]}""".toByteArray())
            var allocations = 0
            val t = AbemaAccountHttpTransport({ url -> allocations++; assertEquals(profile, url); c }, { true }, { 1000 }, { 1000000 })
            val result = t.subscriptionProfile("guest", "private-bearer") as AbemaSubscriptionProfileResult.Identity
            assertEquals(2_000_000L, result.premiumExpiresAtMs)
            assertEquals(AbemaChaseFieldState.UNVERIFIED, result.chase.state); assertTrue(result.chase.claims.isEmpty())
            assertEquals(1, allocations); assertEquals(1, c.opened)
            assertEquals(1, c.streamCloses); assertEquals(1, c.disconnected); assertTrue(t.cleanupConfirmed)
            t.close(); assertEquals(1, c.disconnected)
        }
        val c = Connection(profile, bytes = """{"profile":{"userId":"guest"},"userSubscriptions":[{"expire":"2000","featureAuthorities":[{"id":"fixture-authority","type":2}]}]}""".toByteArray())
        val t = transport(c)
        assertSame(AbemaSubscriptionProfileResult.Inconclusive, t.subscriptionProfile("guest", "private-bearer"))
        assertEquals(1, c.opened); assertEquals(1, c.streamCloses); assertEquals(1, c.disconnected); assertTrue(t.cleanupConfirmed)
    }
    @Test fun subscriptionRefusalsAndUnexpectedStatusDoNotReadAnyResponseBody() {
        for (status in listOf(401, 403, 400, 404, 302, 429, 500, 201)) {
            val c = Connection(profile, status)
            assertSame(if (status in listOf(401, 403)) AbemaSubscriptionProfileResult.Rejected else AbemaSubscriptionProfileResult.Inconclusive,
                transport(c).subscriptionProfile("guest", "private-bearer"))
            assertEquals(0, c.opened); assertEquals(1, c.disconnected)
        }
    }
    @Test fun subscriptionAbsentEmptyMalformedAndMismatchedProfileNeverBecomePositiveAuthority() {
        for ((body, state) in listOf("""{"profile":{"userId":"guest"}}""" to AbemaSubscriptionFieldState.ABSENT,
            """{"profile":{"userId":"guest"},"userSubscriptions":[]}""" to AbemaSubscriptionFieldState.EMPTY)) {
            val c = Connection(profile, bytes = body.toByteArray())
            val result = transport(c).subscriptionProfile("guest", "private-bearer") as AbemaSubscriptionProfileResult.Identity
            assertEquals(state, result.fieldState); assertNull(result.premiumExpiresAtMs)
            assertEquals(1, c.streamCloses); assertEquals(1, c.disconnected)
        }
        for (body in listOf("""{"profile":{"userId":"other"}}""", """{"profile":{"userId":"guest"},"userSubscriptions":null}""",
            """{"profile":{"userId":"guest"},"userSubscriptions":[],"ignored":{"x":1,"x":2}}""")) {
            val c = Connection(profile, bytes = body.toByteArray())
            val t = transport(c)
            assertSame(AbemaSubscriptionProfileResult.Inconclusive, t.subscriptionProfile("guest", "private-bearer"))
            assertTrue(t.cleanupConfirmed); assertEquals(1, c.streamCloses); assertEquals(1, c.disconnected)
        }
    }
    @Test fun subscriptionReadKeepsOwnerAndOriginalHttpBudgetGatesBeforePublication() {
        for (phase in 0..2) {
            val c = Connection(profile); var allowed = true; var elapsed = 1000L
            when (phase) {
                0 -> c.responseHook = { allowed = false }
                1 -> c.disconnectHook = { allowed = false }
                else -> c.responseHook = { elapsed += 15000 }
            }
            val error = assertThrows(AbemaAccountException::class.java) {
                transport(c, { allowed }, { elapsed }).subscriptionProfile("guest", "private-bearer")
            }
            assertEquals(if (phase == 2) AbemaAccountFailure.TIME_LIMIT_REACHED else AbemaAccountFailure.CANCELLED, error.category)
            assertEquals(if (phase == 1) 1 else 0, c.opened); assertEquals(1, c.disconnected)
        }
    }
    @Test fun subscriptionCleanupFailureRetainsActualResourcesAndNeverReturnsAnIdentity() {
        for (stream in listOf(true, false)) {
            val c = Connection(profile, bytes = """{"profile":{"userId":"guest"},"userSubscriptions":[]}""".toByteArray())
            if (stream) c.streamError = AssertionError("PRIVATE") else c.disconnectError = AssertionError("PRIVATE")
            val t = transport(c)
            cleanupFailure { t.subscriptionProfile("guest", "private-bearer") }; retained(t, c)
            c.streamError = null; c.disconnectError = null
            cleanupFailure { t.close() }; assertFalse(t.cleanupConfirmed)
            assertEquals(1, c.streamCloses); assertEquals(1, c.disconnected)
        }
    }
    @Test fun unrelatedTransportFixturesDefaultToInconclusiveWithoutAnotherOperation() {
        val fixture = object : AbemaAccountTransport {
            override fun profile(id: String, bearer: String): AbemaAccountProfileResult = error("No operation")
            override fun token(challenge: AbemaAccountChallenge): AbemaAccountTokenResult = error("No operation")
            override fun cancelActiveRequest() = Unit
            override fun close() = Unit
        }
        assertSame(AbemaSubscriptionProfileResult.Inconclusive, fixture.subscriptionProfile("guest", "private-bearer"))
    }
    @Test fun emailRegistrationUsesExactGetAndSavedBearerWithConfirmedCleanup() {
        val c = Connection(email, bytes = """{"email":"fixture@example.test","hasPassword":false}""".toByteArray())
        val t = transport(c)
        assertEquals(AbemaAccountEmailResult.REGISTERED, t.emailRegistration("guest", "private-bearer"))
        assertEquals("GET", c.requestMethod); assertEquals("Bearer private-bearer", c.getRequestProperty("Authorization"))
        assertNull(c.getRequestProperty("Cookie")); assertEquals(0, c.sent.size())
        assertFalse(c.instanceFollowRedirects); assertFalse(c.useCaches)
        assertEquals(1, c.streamCloses); assertEquals(1, c.disconnected); t.close(); assertEquals(1, c.disconnected)
    }
    @Test fun emailRefusalsHaveClosedCategoriesAndNeverReadResponseBodies() {
        for (status in listOf(401, 403, 404, 400, 302, 429, 500, 201)) {
            val c = Connection(email, status)
            assertEquals(when (status) {
                401, 403 -> AbemaAccountEmailResult.REJECTED
                404 -> AbemaAccountEmailResult.UNREGISTERED
                else -> AbemaAccountEmailResult.INCONCLUSIVE
            }, transport(c).emailRegistration("guest", "private-bearer"))
            assertEquals(0, c.opened); assertEquals(1, c.disconnected)
        }
    }
    @Test fun emailRegistrationCannotReturnAfterOwnerLossOrUnconfirmedCleanup() {
        for (cleanupFailure in listOf(false, true)) {
            val c = Connection(email, bytes = """{"email":"fixture@example.test"}""".toByteArray())
            var allowed = true
            if (cleanupFailure) c.throwClose = true else c.disconnectHook = { allowed = false }
            val error = assertThrows(AbemaAccountException::class.java) { transport(c, { allowed }).emailRegistration("guest", "private-bearer") }
            assertEquals(if (cleanupFailure) AbemaAccountFailure.CLEANUP_FAILED else AbemaAccountFailure.CANCELLED, error.category)
            assertEquals(1, c.streamCloses); assertEquals(1, c.disconnected)
        }
    }
    @Test fun exactProfileRequestHasBearerAndNoCookiesRedirectOrCachingAndCleansBeforeReturn() {
        val c = Connection(profile); val t = transport(c)
        assertEquals("guest", (t.profile("guest", "private-bearer") as AbemaAccountProfileResult.Identity).id)
        assertEquals("GET", c.requestMethod); assertEquals("Bearer private-bearer", c.getRequestProperty("Authorization"))
        assertNull(c.getRequestProperty("Cookie")); assertFalse(c.instanceFollowRedirects); assertFalse(c.useCaches)
        assertEquals(10000, c.connectTimeout); assertEquals(1, c.disconnected); t.close(); assertEquals(1, c.disconnected)
    }
    @Test fun positiveAndInvalidBearerEnforcementResponsesAreDistinctAndRefusalBodiesStayClosed() {
        for (status in listOf(401, 403, 400, 302, 500, 201)) {
            val c = Connection(profile, status)
            val result = transport(c).profile("guest", "tachiai-invalid-bearer")
            assertSame(if (status in listOf(401, 403)) AbemaAccountProfileResult.Rejected else AbemaAccountProfileResult.Inconclusive, result)
            assertEquals(0, c.opened); assertEquals(1, c.disconnected)
        }
    }
    @Test fun onlyToken400ReadsStrictPendingBodyAndWritesExactCapturedDto() {
        val c = Connection(token, 400, "{\"code\":16,\"message\":\"PRIVATE_PROVIDER_TEXT\"}".toByteArray())
        val challenge = fixtureAccountChallenge()
        assertSame(AbemaAccountTokenResult.Pending, transport(c).token(challenge))
        assertEquals(challenge.tokenBody, c.sent.toString("UTF-8")); assertEquals("POST", c.requestMethod)
        assertEquals("Bearer guest-bearer", c.getRequestProperty("Authorization")); assertEquals(1, c.disconnected)
    }
    @Test fun unsupportedSuccessRefusalAndRedirectNeverReadTokenBody() {
        for (status in listOf(201, 204, 302, 401, 403, 429, 500)) {
            val c = Connection(token, status)
            assertTrue(transport(c).token(fixtureAccountChallenge()) is AbemaAccountTokenResult.Failed)
            assertEquals(0, c.opened); assertEquals(1, c.disconnected)
        }
    }
    @Test fun strictUtf8MediaTypeByteBoundsAndOwnerLossDuringReadRefuseClosed() {
        for (bad in 0..3) {
            val c = Connection(profile); var allowed = true
            when (bad) { 0 -> c.bytes = byteArrayOf(0xff.toByte()); 1 -> c.mediaType = "text/html"; 2 -> c.bytes = ByteArray(65537); 3 -> c.readHook = { allowed = false } }
            try { transport(c, { allowed }).profile("guest", "guest-bearer"); fail() }
            catch (error: AbemaAccountException) { assertFalse(error.toString().contains("PRIVATE")) }
            assertEquals(1, c.disconnected)
        }
    }
    @Test fun cancellationOrOwnerLossAtStatusAndDuringDisconnectCannotReturnIdentity() {
        for (cancel in listOf(true, false)) {
            val c = Connection(profile); var allowed = true; val t = transport(c, { allowed })
            if (cancel) c.responseHook = t::cancelActiveRequest else c.disconnectHook = { allowed = false }
            try { t.profile("guest", "guest-bearer"); fail() } catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.CANCELLED, error.category) }
            assertEquals(1, c.disconnected)
        }
    }
    @Test fun originalOperationBudgetAndChallengeExpiryApplyBeforeDispatch() {
        val c = Connection(token); val t = transport(c, elapsed = { 300000 })
        try { t.token(fixtureAccountChallenge()); fail() } catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.EXPIRED, error.category) }
        assertEquals(0, c.opened); assertEquals(0, c.disconnected)
        val slow = Connection(profile); var elapsed = 1000L; slow.responseHook = { elapsed += 15000 }
        try { transport(slow, elapsed = { elapsed }).profile("guest", "guest-bearer"); fail() }
        catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.TIME_LIMIT_REACHED, error.category) }
        assertEquals(0, slow.opened); assertEquals(1, slow.disconnected)
    }
    @Test fun invalidOperationClockIsTemporaryAndNeverDiagnosesGrantExpiry() {
        val negative = Connection(profile)
        val error = assertThrows(AbemaAccountException::class.java) { transport(negative, elapsed = { -1 }).profile("guest", "private-bearer") }
        assertEquals(AbemaAccountFailure.TIME_LIMIT_REACHED, error.category)
        assertEquals(0, negative.opened); assertEquals(0, negative.disconnected)
        var now = 1000L; val rollback = Connection(profile).apply { responseHook = { now-- } }
        val refused = assertThrows(AbemaAccountException::class.java) { transport(rollback, elapsed = { now }).profile("guest", "private-bearer") }
        assertEquals(AbemaAccountFailure.TIME_LIMIT_REACHED, refused.category)
        assertEquals(0, rollback.opened); assertEquals(1, rollback.disconnected)
    }
    @Test fun globalCookieHandlerRefusesBeforeOpeningAndAfterStatusWithoutMutatingHandler() {
        val previous = CookieHandler.getDefault(); val handler = CookieManager()
        try {
            var opens = 0; CookieHandler.setDefault(handler)
            val t = AbemaAccountHttpTransport({ opens++; Connection(it) }, { true }, { 1000 }, { 1000000 })
            try { t.profile("guest", "guest-bearer"); fail() } catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.NOT_VERIFIED, error.category) }
            assertEquals(0, opens); assertSame(handler, CookieHandler.getDefault())
            CookieHandler.setDefault(null); val c = Connection(profile); c.responseHook = { CookieHandler.setDefault(handler) }
            try { transport(c).profile("guest", "guest-bearer"); fail() } catch (_: AbemaAccountException) { }
            assertEquals(0, c.opened); assertSame(handler, CookieHandler.getDefault()); assertEquals(1, c.disconnected)
        } finally { CookieHandler.setDefault(previous) }
    }
    @Test fun throwingDisconnectIsStickyAndRejectsAllFurtherRequests() {
        val c = Connection(profile); c.throwClose = true; val t = transport(c)
        try { t.profile("guest", "guest-bearer"); fail() } catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.CLEANUP_FAILED, error.category) }
        c.throwClose = false
        try { t.profile("guest", "guest-bearer"); fail() } catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.CANCELLED, error.category) }
        assertEquals(1, c.disconnected)
    }
    private fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
    private fun retained(t: AbemaAccountHttpTransport, c: Connection) {
        val handle = field(t, "active")!!
        assertSame(c, field(handle, "connection"))
        assertSame(c.returnedStream, field(field(handle, "input")!!, "resource"))
    }
    private fun cleanupFailure(action: () -> Unit) {
        try { action(); fail() } catch (error: AbemaAccountException) {
            assertEquals(AbemaAccountFailure.CLEANUP_FAILED, error.category); assertNull(error.cause)
            assertFalse(error.toString().contains("PRIVATE"))
        }
    }
    @Test fun streamOrDisconnectErrorRetainsTheExactResourcesAndRepeatedCloseNeverRetries() {
        for (stream in listOf(true, false)) {
            val c = Connection(profile)
            if (stream) c.streamError = AssertionError("PRIVATE STREAM FAILURE") else c.disconnectError = AssertionError("PRIVATE DISCONNECT FAILURE")
            val t = transport(c)
            cleanupFailure { t.profile("guest", "guest-bearer") }
            assertFalse(t.cleanupConfirmed); retained(t, c)
            c.streamError = null; c.disconnectError = null
            repeat(2) { cleanupFailure { t.close() }; retained(t, c) }
            assertEquals(1, c.streamCloses); assertEquals(1, c.disconnected); assertFalse(t.cleanupConfirmed)
        }
    }
    @Test fun firstStreamCloseAndDisconnectAreBoundedAndLateSuccessNeverUnpoisons() {
        for (stream in listOf(true, false)) {
            val c = Connection(profile); val entered = CountDownLatch(1); val release = CountDownLatch(1); val completed = CountDownLatch(1)
            val hold: () -> Unit = { entered.countDown(); try { check(release.await(5, TimeUnit.SECONDS)) } finally { completed.countDown() } }
            if (stream) c.streamCloseHook = hold else c.disconnectHook = hold
            val t = AbemaAccountHttpTransport({ c }, { true }, { 1000 }, { 1000000 }, cleanupWaitMs = 50)
            val worker = Executors.newSingleThreadExecutor()
            try {
                val request = worker.submit { t.profile("guest", "guest-bearer") }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                try { request.get(1, TimeUnit.SECONDS); fail() } catch (error: ExecutionException) {
                    assertEquals(AbemaAccountFailure.CLEANUP_FAILED, (error.cause as AbemaAccountException).category)
                }
                assertFalse(t.cleanupConfirmed); retained(t, c)
                val closing = worker.submit { cleanupFailure { t.close() } }; closing.get(1, TimeUnit.SECONDS)
                assertEquals(1, c.streamCloses); assertEquals(1, c.disconnected)
                release.countDown(); assertTrue(completed.await(1, TimeUnit.SECONDS))
                cleanupFailure { t.close() }; assertFalse(t.cleanupConfirmed); retained(t, c)
                assertEquals(1, c.streamCloses); assertEquals(1, c.disconnected)
            } finally { release.countDown(); worker.shutdownNow() }
        }
    }
    @Test fun reentrantNativeCleanupRefusesWithoutASecondCloseOrUnboundedWait() {
        val c = Connection(profile); val t = transport(c)
        c.disconnectHook = { t.close() }
        cleanupFailure { t.profile("guest", "guest-bearer") }
        assertFalse(t.cleanupConfirmed); retained(t, c)
        assertEquals(1, c.streamCloses); assertEquals(1, c.disconnected)
        cleanupFailure { t.close() }
    }
    @Test fun nativeOpenStatusOrReadErrorIsNormalizedWithoutInspectingHostilePrivateValues() {
        for (phase in 0..3) {
            val hostile = HostileError(); val c = Connection(profile)
            when (phase) { 1 -> c.responseHook = { throw hostile }; 2, 3 -> c.readHook = { throw hostile } }
            if (phase == 3) c.disconnectError = hostile
            val t = AbemaAccountHttpTransport({ if (phase == 0) throw hostile else c }, { true }, { 1000 }, { 1000000 })
            try { t.profile("guest", "guest-bearer"); fail() } catch (error: AbemaAccountException) {
                assertEquals(if (phase == 3) AbemaAccountFailure.CLEANUP_FAILED else AbemaAccountFailure.TEMPORARY, error.category); assertNull(error.cause)
                assertTrue(error.stackTrace.isEmpty()); assertFalse(error.toString().contains("PRIVATE"))
            }
            assertEquals(0, hostile.messageReads); assertEquals(0, hostile.stringReads)
            assertEquals(if (phase == 0) 0 else 1, c.disconnected)
            assertEquals(if (phase >= 2) 1 else 0, c.streamCloses)
            if (phase == 3) { assertFalse(t.cleanupConfirmed); cleanupFailure { t.close() } }
            else { assertTrue(t.cleanupConfirmed); t.close() }
            assertEquals(if (phase == 0) 0 else 1, c.disconnected)
            assertEquals(if (phase >= 2) 1 else 0, c.streamCloses)
        }
    }
}
