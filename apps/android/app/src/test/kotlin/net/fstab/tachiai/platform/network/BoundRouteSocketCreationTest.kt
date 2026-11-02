package net.fstab.tachiai.platform.network

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import org.junit.Assert.*
import org.junit.Test

class BoundRouteSocketCreationTest {
    private class OwnedSocket : Socket() {
        val closes = AtomicInteger()
        val connects = AtomicInteger()
        val closedReceipt = CountDownLatch(1)
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            connects.incrementAndGet()
            throw IOException("Owned fixture never dials")
        }
        override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)
        override fun close() {
            closes.incrementAndGet()
            try { super.close() } finally { closedReceipt.countDown() }
        }
    }
    private class Egress(factory: SocketFactory) : RouteNetworkEgress {
        val scope = NetworkEgressScope(90_000_000_000L, { 0L },
            { listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) }, {})
        override val socketFactory = scope.trackedSocketFactory(factory)
        override fun checkActive() = scope.checkActive()
        override fun isActive() = scope.isActive()
        override fun resolveAddresses(host: String, timeoutMillis: Long) = scope.resolveAddresses(host, timeoutMillis)
        override fun resolve(host: String?, timeoutMillis: Long) = scope.resolve(checkNotNull(host), timeoutMillis)
        override fun bindSocket(fd: Int) = scope.bindSocket(fd)
        override fun cancel() = scope.cancel()
        override fun isCleanupConfirmed() = scope.isCleanupConfirmed()
        override fun reportCleanupUnconfirmed() = scope.reportCleanupUnconfirmed()
        override fun closeOwnedSockets() = scope.closeOwnedSockets()
    }

    @Test fun cancellingActualHttpCannotConfirmItsStillBlockedSocketFactory() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val socket = OwnedSocket()
        val factory = object : SocketFactory() {
            override fun createSocket(): Socket {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return socket
            }
            override fun createSocket(host: String, port: Int): Socket = createSocket()
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = createSocket()
            override fun createSocket(host: InetAddress, port: Int): Socket = createSocket()
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = createSocket()
        }
        val egress = Egress(factory)
        val route = RouteSession.createBoundAbemaAccountSession(null, egress)
        val connection = route.open(URL("https://abema.tv/api/auth/oauth/token"))
        val worker = Executors.newSingleThreadExecutor()
        try {
            val request = worker.submit<Throwable?> {
                try { connection.responseCode; null } catch (error: Throwable) { error }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(egress.isCleanupConfirmed())
            assertThrows(IOException::class.java, route::close)
            // OkHttp can end its call while a TaskRunner still owns creation.
            assertTrue(request.get(2, TimeUnit.SECONDS) is IOException)
            assertFalse(egress.isCleanupConfirmed()); assertEquals(0, socket.closes.get())
            release.countDown(); assertTrue(socket.closedReceipt.await(2, TimeUnit.SECONDS))
            assertFalse(egress.isCleanupConfirmed()) // The first close was already unconfirmed.
            assertEquals(1, socket.closes.get()); assertEquals(0, socket.connects.get())
            assertThrows(IOException::class.java, route::close) // Original uncertainty is sticky.
        } finally {
            release.countDown(); worker.shutdownNow()
            runCatching(route::close)
        }
    }
}
