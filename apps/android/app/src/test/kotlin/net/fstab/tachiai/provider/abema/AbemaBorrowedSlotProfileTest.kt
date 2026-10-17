package net.fstab.tachiai.provider.abema

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.provider.abema.account.AbemaAccountException
import net.fstab.tachiai.provider.abema.account.AbemaAccountFailure
import net.fstab.tachiai.provider.abema.account.AbemaSubscriptionProfileResult
import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.HttpsURLConnection

class AbemaBorrowedSlotProfileTest {
    private class Connection(url: URL, private val failStreamClose: Boolean = false,
        private val disconnectAction: () -> Unit = {}, private val responseAction: () -> Unit = {}) : HttpsURLConnection(url) {
        var disconnects = 0; var streamCloses = 0
        override fun getResponseCode(): Int { responseAction(); return 200 }
        override fun getContentType() = "application/json"
        override fun getContentLengthLong() = -1L
        override fun getInputStream(): InputStream = object : ByteArrayInputStream(
            """{"profile":{"userId":"fixture"},"userSubscriptions":[{"expire":100,"viewingAuthorities":[{"isPremium":true}]}]}""".toByteArray()) {
            override fun close() {
                streamCloses++
                if (failStreamClose) throw AssertionError("PRIVATE CLOSE ERROR")
                super.close()
            }
        }
        override fun disconnect() { disconnects++; disconnectAction() }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }

    @Test fun oneProfileUsesOnlyTheBorrowedOpenerAndPublishesAfterActualCleanup() {
        var opens = 0; lateinit var connection: Connection
        val operation = AbemaBorrowedSlotProfile({ url ->
            assertEquals("https://api.p-c3-e.abema-tv.com/v1/users/fixture", url.toExternalForm())
            opens++; Connection(url).also { connection = it }
        }, { true }, { 1000 }, { 2000 }, 45000)
        assertFalse(operation.cleanupConfirmed)
        val profile = operation.read("fixture", "private-bearer") as AbemaSubscriptionProfileResult.Identity
        assertEquals(100000L, profile.premiumExpiresAtMs)
        assertEquals(1, opens); assertEquals(1, connection.streamCloses); assertEquals(1, connection.disconnects)
        assertEquals("GET", connection.requestMethod)
        assertTrue(operation.cleanupConfirmed)
        try { operation.read("fixture", "private-bearer"); fail() }
        catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.CANCELLED, error.category) }
        operation.cancel(); assertTrue(operation.cleanupConfirmed); assertEquals(1, connection.disconnects)
        assertFalse(operation.toString().contains("bearer"))
    }

    @Test fun originalReadinessOwnerLossAndCancellationRefuseBeforeOpeningAnything() {
        for (mode in 0..2) {
            var elapsed = 1000L; var current = true; var opens = 0
            val operation = AbemaBorrowedSlotProfile({ opens++; error("No network permitted") },
                { current }, { elapsed }, { 2000 }, 2000)
            when (mode) { 0 -> elapsed = 2000; 1 -> current = false; else -> operation.cancel() }
            try { operation.read("fixture", "private-bearer"); fail() }
            catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.CANCELLED, error.category) }
            assertEquals(0, opens); assertTrue(operation.cleanupConfirmed)
        }
    }

    @Test fun lateOwnerOrDeadlineCannotPublishACompletedProfile() {
        for (expire in listOf(true, false)) {
            var elapsed = 1000L; var current = true
            lateinit var connection: Connection
            val operation = AbemaBorrowedSlotProfile({ url -> Connection(url, responseAction = {
                if (expire) elapsed = 2000 else current = false
            }).also { connection = it } }, { current }, { elapsed }, { 2000 }, 2000)
            try { operation.read("fixture", "private-bearer"); fail() }
            catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.CANCELLED, error.category) }
            assertTrue(operation.cleanupConfirmed); assertEquals(1, connection.disconnects)
        }
    }

    @Test fun blockingDurableCheckCannotAdmitNetworkAfterTheOriginalReadinessDeadline() {
        var elapsed = 1000L; var opens = 0
        val operation = AbemaBorrowedSlotProfile({ opens++; error("Late network prohibited") },
            { elapsed = 2000; true }, { elapsed }, { 2000 }, 2000)
        try { operation.read("fixture", "private-bearer"); fail() }
        catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.CANCELLED, error.category) }
        assertEquals(0, opens); assertTrue(operation.cleanupConfirmed)
    }

    @Test fun uncertainFirstStreamCloseRemainsUnconfirmedAndCannotBecomeAuthority() {
        lateinit var connection: Connection
        val operation = AbemaBorrowedSlotProfile({ url -> Connection(url, failStreamClose = true).also { connection = it } },
            { true }, { 1000 }, { 2000 }, 45000)
        try { operation.read("fixture", "private-bearer"); fail() }
        catch (error: AbemaAccountException) {
            assertEquals(AbemaAccountFailure.CLEANUP_FAILED, error.category)
            assertNull(error.cause); assertFalse(error.toString().contains("PRIVATE"))
        }
        repeat(2) { operation.cancel(); assertFalse(operation.cleanupConfirmed) }
        assertEquals(1, connection.streamCloses); assertEquals(1, connection.disconnects)
    }

    @Test fun timedOutDisconnectCannotBecomeConfirmationAfterLateCompletion() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        lateinit var connection: Connection
        val operation = AbemaBorrowedSlotProfile({ url -> Connection(url, disconnectAction = {
            entered.countDown(); try { check(release.await(5, TimeUnit.SECONDS)) } finally { finished.countDown() }
        }).also { connection = it } }, { true }, { 1000 }, { 2000 }, 45000, cleanupWaitMs = 50)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                try { operation.read("fixture", "private-bearer"); false }
                catch (error: AbemaAccountException) { error.category == AbemaAccountFailure.CLEANUP_FAILED }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertTrue(result.get(2, TimeUnit.SECONDS))
            assertFalse(operation.cleanupConfirmed)
            release.countDown(); assertTrue(finished.await(1, TimeUnit.SECONDS))
            operation.cancel(); assertFalse(operation.cleanupConfirmed); assertEquals(1, connection.disconnects)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
