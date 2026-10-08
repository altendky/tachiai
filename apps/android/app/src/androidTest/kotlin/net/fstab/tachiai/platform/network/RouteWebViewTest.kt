package net.fstab.tachiai.platform.network

import android.webkit.HttpAuthHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

// No provider, credentials, DNS destination, TLS bypass or external connection.
// The synthetic proxy refuses after authentication: this measures WebView's
// proxy challenge callback, not successful HTTPS or provider playback.
@RunWith(AndroidJUnit4::class)
class RouteWebViewTest {
    @Test fun loopbackProxyChallengeReachesWebViewAndAuthenticatesConnect() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val nonce = UUID.randomUUID().toString()
        val realm = "tachiai-fixture-$nonce"
        val username = "fixture"
        val password = UUID.randomUUID().toString()
        val authorization = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 15000 }
        val authenticated = CountDownLatch(1)
        val callbackCount = AtomicInteger()
        val serverFailure = AtomicReference<Throwable>()
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            try {
                repeat(4) {
                    if (authenticated.count == 0L) return@execute
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val header = ByteArrayOutputStream()
                        val input = socket.getInputStream()
                        while (true) {
                            val next = input.read()
                            check(next >= 0 && header.size() < 8192)
                            header.write(next)
                            val bytes = header.toByteArray()
                            if (bytes.size >= 4 && bytes.takeLast(4) == listOf(13.toByte(), 10.toByte(), 13.toByte(), 10.toByte())) break
                        }
                        val lines = header.toString("US-ASCII").split("\r\n")
                        check(lines.first() == "CONNECT tachiai-proxy-fixture.invalid:443 HTTP/1.1")
                        val supplied = lines.drop(1).firstOrNull {
                            it.substringBefore(':').equals("Proxy-Authorization", ignoreCase = true)
                        }?.substringAfter(':')?.trim()
                        val response = if (supplied == authorization) {
                            authenticated.countDown()
                            "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        } else {
                            check(supplied == null)
                            "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"$realm\"\r\n" +
                                "Content-Length: 0\r\nConnection: close\r\n\r\n"
                        }
                        socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                        socket.getOutputStream().flush()
                    }
                }
            } catch (error: Throwable) { serverFailure.set(error) }
        }
        lateinit var coordinator: AbemaWebViewRoute
        var webView: WebView? = null
        val installed = CountDownLatch(1)
        val accepted = AtomicReference<Boolean>()
        try {
            instrumentation.runOnMainSync {
                coordinator = AbemaWebViewRoute()
                coordinator.installProxy(server.localPort) { success ->
                    accepted.set(success)
                    if (success) {
                        webView = WebView(instrumentation.targetContext).apply {
                            settings.javaScriptEnabled = false
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, receivedRealm: String) {
                                    if (host == "127.0.0.1" && receivedRealm == realm) {
                                        callbackCount.incrementAndGet()
                                        handler.proceed(username, password)
                                    } else handler.cancel()
                                }
                            }
                            loadUrl("https://tachiai-proxy-fixture.invalid/")
                        }
                    }
                    installed.countDown()
                }
            }
            assertTrue("Proxy installation completed", installed.await(5, TimeUnit.SECONDS))
            assertEquals(true, accepted.get())
            assertTrue("Synthetic CONNECT authenticated", authenticated.await(15, TimeUnit.SECONDS))
            assertNull("Synthetic proxy server completed without a protocol failure", serverFailure.get())
            assertTrue("WebView delivered the local proxy challenge", callbackCount.get() > 0)
        } finally {
            val cleared = CountDownLatch(1)
            instrumentation.runOnMainSync {
                webView?.stopLoading()
                webView?.destroy()
                coordinator.clear { cleared.countDown() }
            }
            server.close()
            worker.shutdownNow()
            assertTrue("Proxy cleared before other tests", cleared.await(5, TimeUnit.SECONDS))
        }
    }
}
