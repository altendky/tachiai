package net.fstab.tachiai.provider.youtube

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.HttpsURLConnection

/** Public GoogleVideo transport; redirects never enter the generic datasource policy. */
internal class YouTubeMediaConnection(
    initialUrl: URL,
    private val current: () -> Boolean,
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : HttpsURLConnection(initialUrl) {
    private val ownership = Object()
    private val resolution = Any()
    private val releases = Any()
    private val owned = Collections.newSetFromMap(IdentityHashMap<HttpsURLConnection, Boolean>())
    private val released = Collections.newSetFromMap(IdentityHashMap<HttpsURLConnection, Boolean>())
    private val headers = linkedMapOf<String, String>()
    private val deadline = System.nanoTime() / 1_000_000 + 30_000
    private var allocations = 0
    private var stopped = false
    private var started = false
    private var finalConnection: HttpsURLConnection? = null
    private var finalStatus = -1
    @Volatile private var releaseFailed = false

    private fun checkCurrent(setup: Boolean = true) {
        if (synchronized(ownership) { stopped } || !current() || (setup && System.nanoTime() / 1_000_000 >= deadline))
            throw IOException("YouTube media setup ended")
    }

    override fun setRequestProperty(key: String, value: String) {
        val canonical = when {
            key.equals("Range", true) && Regex("bytes=[0-9]+-[0-9]*").matches(value) -> "Range"
            key.equals("Accept-Encoding", true) && value == "identity" -> "Accept-Encoding"
            else -> throw IllegalArgumentException("YouTube media header rejected")
        }
        synchronized(ownership) {
            check(!started && !stopped)
            headers[canonical] = value
        }
    }
    override fun addRequestProperty(key: String, value: String) = setRequestProperty(key, value)
    override fun getRequestProperty(key: String): String? = synchronized(ownership) {
        headers.entries.firstOrNull { it.key.equals(key, true) }?.value
    }
    override fun getRequestProperties(): Map<String, List<String>> = synchronized(ownership) {
        headers.mapValues { listOf(it.value) }
    }

    private fun allocate(target: URL): HttpsURLConnection {
        synchronized(ownership) {
            if (stopped) throw IOException("YouTube media ended")
            allocations++
        }
        val connection = try { open(target) } catch (error: Throwable) {
            synchronized(ownership) { allocations--; ownership.notifyAll() }
            throw error
        }
        val late = synchronized(ownership) {
            owned.add(connection)
            allocations--
            ownership.notifyAll()
            stopped
        }
        if (late) { release(connection); throw IOException("YouTube media ended") }
        return connection
    }

    private fun release(connection: HttpsURLConnection) = synchronized(releases) {
        if (!released.contains(connection)) {
            try { connection.disconnect() }
            catch (error: Throwable) { releaseFailed = true; throw error }
            released.add(connection)
        }
    }

    private fun resolve(): HttpsURLConnection = synchronized(resolution) {
        finalConnection?.let { checkCurrent(setup = false); return@synchronized it }
        checkCurrent()
        val forwarded = synchronized(ownership) { started = true; headers.toMap() }
        if (requestMethod != "GET" || doOutput) throw IOException("YouTube media method rejected")
        var target = url.toURI()
        for (hop in 0..5) {
            checkCurrent()
            if (!allowedYouTubeMediaUri(target)) throw IOException("YouTube media destination rejected")
            val connection = allocate(target.toURL())
            try {
                checkCurrent()
                val remaining = (deadline - System.nanoTime() / 1_000_000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = if (connectTimeout > 0) minOf(connectTimeout, remaining) else remaining
                connection.readTimeout = if (readTimeout > 0) minOf(readTimeout, remaining) else remaining
                connection.requestMethod = "GET"
                for ((name, value) in forwarded) connection.setRequestProperty(name, value)
                val status = connection.responseCode
                checkCurrent()
                if (status in setOf(301, 302, 303, 307, 308)) {
                    if (hop == 5) throw IOException("YouTube media redirect limit")
                    val location = connection.getHeaderField("Location") ?: throw IOException("YouTube media redirect unavailable")
                    val next = target.resolve(URI(location))
                    if (!allowedYouTubeMediaUri(next)) throw IOException("YouTube media redirect rejected")
                    release(connection)
                    target = next
                } else {
                    finalConnection = connection
                    finalStatus = status
                    connected = true
                    return@synchronized connection
                }
            } catch (error: Throwable) {
                try { release(connection) } catch (_: Throwable) { /* Still owned for disconnect to retry. */ }
                throw error
            }
        }
        throw IOException("YouTube media redirect limit")
    }

    override fun connect() { resolve() }
    override fun getResponseCode(): Int { resolve(); return finalStatus }
    override fun getResponseMessage(): String = resolve().responseMessage
    override fun getInputStream(): InputStream = resolve().inputStream
    override fun getErrorStream(): InputStream? = resolve().errorStream
    override fun getContentLength(): Int = resolve().contentLength
    override fun getContentLengthLong(): Long = resolve().contentLengthLong
    override fun getHeaderField(name: String?): String? = resolve().getHeaderField(name)
    override fun getHeaderField(index: Int): String? = resolve().getHeaderField(index)
    override fun getHeaderFieldKey(index: Int): String? = resolve().getHeaderFieldKey(index)
    override fun getHeaderFields(): Map<String, List<String>> = resolve().headerFields
    override fun getCipherSuite(): String = resolve().cipherSuite
    override fun getLocalCertificates(): Array<Certificate>? = resolve().localCertificates
    override fun getServerCertificates(): Array<Certificate> = resolve().serverCertificates
    override fun getPeerPrincipal(): Principal = resolve().peerPrincipal
    override fun getLocalPrincipal(): Principal? = resolve().localPrincipal
    override fun usingProxy(): Boolean = finalConnection?.usingProxy() ?: false

    override fun disconnect() {
        val captured = synchronized(ownership) {
            stopped = true
            while (allocations != 0) ownership.wait()
            owned.toList()
        }
        var failed = false
        for (connection in captured) try { release(connection) } catch (_: Throwable) { failed = true }
        if (failed || releaseFailed) throw IOException("YouTube media cleanup unconfirmed")
    }
}
