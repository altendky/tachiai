package net.fstab.tachiai.platform.network

import android.os.Looper
import java.io.IOException
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

// Provider-free JNI/ABI/initialization smoke tests. The synthetic endpoint is
// loopback discard port 9; no provider request, imported profile, system VPN or
// real peer is used. WireGuard initialization is NOT a successful handshake.
class NativeRouteBindingTest {
    @Test(timeout = 20_000) fun actualHttpProxyBindingCreatesAndClosesItsLocalListener() {
        assertNotSame("Native initialization and shutdown must stay off the UI thread", Looper.getMainLooper(), Looper.myLooper())
        val profile = parseConnectionProfile("http://127.0.0.1:9".toByteArray())
        val route = RouteSession.create(profile)
        try {
            assertFalse(route.isSystem)
            assertTrue(route.proxyPort in 1..65535)
            assertTrue(route.matchesProxyChallenge("127.0.0.1", route.proxyRealm))
            assertFalse(route.matchesProxyChallenge("localhost", route.proxyRealm))
            assertFalse(route.matchesProxyChallenge("127.0.0.1", "wrong-realm"))
            assertFalse(route.toString().contains(route.proxyPassword))
        } finally { route.close() }
        route.close()
        assertFalse(route.matchesProxyChallenge("127.0.0.1", route.proxyRealm))
        assertThrows(IOException::class.java) { route.open(URL("https://api.abema.io/v1/channels")) }
    }

    @Test(timeout = 20_000) fun actualWireGuardBindingInitializesAndClosesWithoutARealPeer() {
        assertNotSame("Native initialization and shutdown must stay off the UI thread", Looper.getMainLooper(), Looper.myLooper())
        val random = SecureRandom()
        fun key(): String {
            val bytes = ByteArray(32)
            do { random.nextBytes(bytes) } while (bytes.all { it == 0.toByte() })
            return Base64.getEncoder().encodeToString(bytes).also { bytes.fill(0) }
        }
        val configuration = """
            [Interface]
            PrivateKey = ${key()}
            Address = 10.2.0.2/32
            DNS = 10.2.0.1
            MTU = 1280

            [Peer]
            PublicKey = ${key()}
            AllowedIPs = 0.0.0.0/0
            Endpoint = 127.0.0.1:9
            PersistentKeepalive = off
        """.trimIndent()
        val route = RouteSession.create(parseConnectionProfile(configuration.toByteArray()))
        try {
            assertFalse(route.isSystem)
            assertTrue(route.proxyPort in 1..65535)
            assertTrue(route.matchesProxyChallenge("127.0.0.1", route.proxyRealm))
            // No open()/connect()/read(), DNS request or protected source.
        } finally { route.close() }
        route.close()
        assertFalse(route.matchesProxyChallenge("127.0.0.1", route.proxyRealm))
        assertThrows(IOException::class.java) { route.open(URL("https://api.abema.io/v1/channels")) }
    }
}
