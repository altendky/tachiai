package net.fstab.tachiai.platform.network

import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

// Per-client CONNECT credentials, never a JVM-wide Authenticator or origin header.
// OkHttp synthesizes a preemptive CONNECT challenge; real retries must also match
// the local listener's realm. A failed attempt is not retried indefinitely.
internal fun routeProxyAuthenticator(username: String, password: String, realm: String, port: Int) =
    Authenticator { route, response ->
        val endpoint = route?.proxy?.address() as? java.net.InetSocketAddress
        val local = endpoint?.address?.hostAddress == "127.0.0.1" && endpoint.port == port
        val challenge = response.challenges().any {
            it.scheme.equals("OkHttp-Preemptive", true) ||
                (it.scheme.equals("Basic", true) && it.realm == realm)
        }
        if (!local || response.request.method != "CONNECT" || !challenge ||
            response.request.header("Proxy-Authorization") != null) null
        else response.request.newBuilder()
            .header("Proxy-Authorization", Credentials.basic(username, password)).build()
    }

// Compatibility facade for the existing bounded provider transports. This does
// not decrypt at the proxy, replace TLS verification, widen origin policy, store
// cookies, follow redirects or emit raw URLs/bodies/headers in diagnostics.
internal class RoutedHttpsConnection(
    url: URL,
    private val client: OkHttpClient,
    private val routeActive: () -> Boolean,
    private val onDisconnect: (RoutedHttpsConnection) -> Unit = {},
) : HttpsURLConnection(url) {
    private val lock = Any()
    // OkHttp body streams are not safe for concurrent read/close. Cancellation
    // must happen outside this lock so it can interrupt the current reader.
    private val responseBodyLock = ReentrantLock()
    private var disconnected = false
    private var disconnectReported = false
    private var responseBodyClosed = false
    private var responseStream: InputStream? = null
    private var call: Call? = null
    private var reply: Response? = null
    private val headers = linkedMapOf<String, MutableList<String>>()
    private val body = object : ByteArrayOutputStream() {
        override fun write(value: Int) = synchronized(lock) {
            writable(); if (count >= MAXIMUM_BODY) throw IOException("Route request body exceeds limit")
            super.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) = synchronized(lock) {
            writable(); if (length < 0 || length > MAXIMUM_BODY - count) throw IOException("Route request body exceeds limit")
            super.write(bytes, offset, length)
        }
        fun erase() { buf.fill(0); reset() }
    }

    init {
        require(url.protocol == "https" && url.userInfo == null && url.ref == null && url.port in listOf(-1, 443))
        instanceFollowRedirects = false
        useCaches = false
        connectTimeout = 10_000
        readTimeout = 10_000
    }

    private fun writable() {
        if (disconnected || !routeActive()) throw IOException("Route is closed")
        check(!connected) { "Request already started" }
    }

    override fun setRequestProperty(key: String, value: String) = synchronized(lock) {
        writable(); validateHeader(key, value)
        headers.keys.filter { it.equals(key, true) }.forEach(headers::remove)
        headers[key] = mutableListOf(value)
    }

    override fun addRequestProperty(key: String, value: String) {
        synchronized(lock) {
            writable(); validateHeader(key, value)
            val existing = headers.keys.firstOrNull { it.equals(key, true) } ?: key
            headers.getOrPut(existing) { mutableListOf() }.add(value)
        }
    }

    override fun getRequestProperty(key: String): String? = synchronized(lock) {
        headers.entries.firstOrNull { it.key.equals(key, true) }?.value?.lastOrNull()
    }
    override fun getRequestProperties(): Map<String, List<String>> = synchronized(lock) {
        check(!connected); headers.mapValues { it.value.toList() }
    }

    private fun validateHeader(key: String, value: String) {
        require(key.length in 1..128 && value.length <= 8192 &&
            Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(key) && value.none { it == '\r' || it == '\n' })
        require(!key.equals("Proxy-Authorization", true) && !key.equals("Proxy-Authenticate", true))
    }

    override fun getOutputStream(): OutputStream = synchronized(lock) {
        writable(); check(doOutput && requestMethod in listOf("POST", "PUT")); body
    }

    override fun connect() { response() }

    private fun response(): Response {
        val pending = synchronized(lock) {
            if (disconnected || !routeActive()) throw IOException("Route is closed")
            reply?.let { return it }
            check(call == null) { "Request already started" }
            require(!instanceFollowRedirects && !useCaches)
            val bytes = body.toByteArray()
            val request = try {
                val builder = Request.Builder().url(url)
                headers.forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }
                // Keep native bounded-reader bytes unchanged. OkHttp otherwise
                // adds gzip and transparently decompresses without caller consent.
                if (headers.keys.none { it.equals("Accept-Encoding", true) }) builder.header("Accept-Encoding", "identity")
                require(requestMethod in listOf("GET", "POST", "PUT", "OPTIONS", "HEAD"))
                if (!doOutput) require(bytes.isEmpty())
                builder.method(requestMethod, if (doOutput || requestMethod in listOf("POST", "PUT")) bytes.toRequestBody() else null).build()
            } finally { body.erase() }
            val configured = client.newBuilder()
                .connectTimeout(connectTimeout.takeIf { it > 0 }?.coerceAtMost(15_000)?.toLong() ?: 15_000L, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeout.takeIf { it > 0 }?.coerceAtMost(15_000)?.toLong() ?: 15_000L, TimeUnit.MILLISECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
            connected = true
            configured.newCall(request).also { call = it }
        }
        val result = try { pending.execute() } catch (_: Exception) {
            disconnect(); throw IOException("Routed HTTPS request failed")
        }
        return synchronized(lock) {
            if (disconnected || !routeActive()) {
                result.close(); throw IOException("Route is closed")
            }
            result.also { reply = it }
        }
    }

    override fun disconnect() {
        val pending = synchronized(lock) {
            if (disconnectReported) return
            disconnected = true
            body.erase()
            headers.clear()
            call
        }
        pending?.cancel()
        withResponseBodyLock {
            closeResponseBody()
            synchronized(lock) { call = null; reply = null }
        }
        val notify = synchronized(lock) {
            if (disconnectReported) false else { disconnectReported = true; true }
        }
        if (notify) onDisconnect(this)
    }

    private fun <T> withResponseBodyLock(action: () -> T): T {
        val acquired = try { responseBodyLock.tryLock(RESPONSE_CLOSE_WAIT_MS, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Routed response ownership wait interrupted")
        }
        if (!acquired) throw IOException("Routed response ownership could not be confirmed")
        try { return action() } finally { responseBodyLock.unlock() }
    }

    // Called only with responseBodyLock held. A failed close remains owned and
    // retryable; it must not unregister the connection as confirmed cleanup.
    private fun closeResponseBody() {
        if (responseBodyClosed) return
        synchronized(lock) { reply }?.close()
        responseBodyClosed = true
    }

    private fun checkBodyReadable() {
        if (responseBodyClosed || synchronized(lock) { disconnected } || !routeActive())
            throw IOException("Routed response is closed")
    }

    private fun bodyStream(response: Response): InputStream = withResponseBodyLock {
        checkBodyReadable()
        responseStream ?: object : InputStream() {
            private val input = response.body.byteStream()
            private fun <T> readBody(action: () -> T): T = withResponseBodyLock {
                checkBodyReadable()
                val result = action()
                checkBodyReadable()
                result
            }
            override fun read(): Int = readBody { input.read() }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                readBody { input.read(bytes, offset, length) }
            override fun skip(count: Long): Long = readBody { input.skip(count) }
            override fun available(): Int = readBody { input.available() }
            override fun close() = withResponseBodyLock { closeResponseBody() }
        }.also { responseStream = it }
    }

    override fun usingProxy() = true
    override fun getResponseCode() = response().code
    override fun getResponseMessage() = response().message
    override fun getContentLengthLong() = response().body.contentLength()
    override fun getContentType(): String? = response().header("Content-Type")
    override fun getHeaderField(name: String?): String? = if (name == null) null else response().header(name)
    override fun getHeaderFields(): Map<String, List<String>> = response().headers.toMultimap()
    override fun getInputStream(): InputStream {
        val result = response()
        if (result.code >= 400) throw FileNotFoundException("Provider HTTP request rejected")
        return bodyStream(result)
    }
    override fun getErrorStream(): InputStream? {
        val result = response()
        return if (result.code >= 400) bodyStream(result) else null
    }
    override fun getCipherSuite() = response().handshake?.cipherSuite?.javaName ?: throw IOException("TLS handshake missing")
    override fun getLocalCertificates(): Array<Certificate>? = response().handshake?.localCertificates?.toTypedArray()
    override fun getServerCertificates(): Array<Certificate> = response().handshake?.peerCertificates?.toTypedArray()
        ?: throw IOException("TLS handshake missing")
    override fun setHostnameVerifier(verifier: HostnameVerifier) = throw UnsupportedOperationException("TLS overrides are not allowed")
    override fun setSSLSocketFactory(factory: SSLSocketFactory) = throw UnsupportedOperationException("TLS overrides are not allowed")
    override fun toString() = "RoutedHttpsConnection(redacted)"

    companion object {
        private const val MAXIMUM_BODY = 64 * 1024
        private const val RESPONSE_CLOSE_WAIT_MS = 1_000L
    }
}
