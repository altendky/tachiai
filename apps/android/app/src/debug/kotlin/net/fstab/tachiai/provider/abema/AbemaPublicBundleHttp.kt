package net.fstab.tachiai.provider.abema

import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.URL
import javax.net.ssl.HttpsURLConnection

// A separate public-code transport, not a widening of the media/access policy.
// No provider identity, token, license data, redirect, TLS override or logging.
internal class AbemaPublicBundleHttp(
    private val canRun: () -> Boolean = { true },
    private val identity: AbemaPublicBundleIdentity = AbemaPublicBundleIdentity(),
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var active: HttpsURLConnection? = null
    private var fetching = false

    fun fetch(): ByteArray {
        val started = clockMs()
        fun checkActive() {
            if (synchronized(lock) { closed } || !canRun() || Thread.currentThread().isInterrupted)
                throw AbemaBundleException(AbemaBundleFailure.CANCELLED)
            if (clockMs() - started >= 30_000)
                throw AbemaBundleException(AbemaBundleFailure.TIME_LIMIT_REACHED)
            if (CookieHandler.getDefault() != null)
                throw AbemaBundleException(AbemaBundleFailure.COOKIE_HANDLER_REFUSED)
        }
        var connection: HttpsURLConnection? = null
        var claimed = false
        try {
            checkActive()
            if (!allowedAbemaPublicBundleUri(identity.uri)) throw AbemaBundleException(AbemaBundleFailure.POLICY_REFUSED)
            synchronized(lock) {
                checkActive()
                if (fetching) throw AbemaBundleException(AbemaBundleFailure.POLICY_REFUSED)
                fetching = true
                claimed = true
            }
            val request = open(identity.uri.toURL())
            connection = request
            synchronized(lock) {
                checkActive()
                active = request
            }
            if (request.url.toURI() != identity.uri) throw AbemaBundleException(AbemaBundleFailure.POLICY_REFUSED)
            request.instanceFollowRedirects = false
            request.connectTimeout = 10_000
            request.readTimeout = 10_000
            request.useCaches = false
            request.requestMethod = "GET"
            request.setRequestProperty("Accept", "application/javascript, text/javascript")
            checkActive()
            if (request.responseCode != 200) throw AbemaBundleException(AbemaBundleFailure.HTTP_REFUSED)
            checkActive()
            val contentType = request.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (contentType !in setOf("application/javascript", "text/javascript", "application/x-javascript"))
                throw AbemaBundleException(AbemaBundleFailure.ASSET_TYPE_REFUSED)
            if (request.contentLengthLong > identity.maximumBytes ||
                (request.contentLengthLong >= 0 && request.contentLengthLong != identity.expectedBytes.toLong()))
                throw AbemaBundleException(AbemaBundleFailure.SIZE_REFUSED)
            val bytes = request.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    checkActive()
                    if (count < 0) break
                    if (output.size() + count > identity.maximumBytes)
                        throw AbemaBundleException(AbemaBundleFailure.SIZE_REFUSED)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            checkActive()
            identity.verify(bytes)
            checkActive()
            return bytes
        } catch (error: AbemaBundleException) { throw error }
        catch (_: Exception) {
            checkActive()
            throw AbemaBundleException(AbemaBundleFailure.NETWORK_FAILED)
        } finally {
            synchronized(lock) {
                if (active === connection) active = null
                if (claimed) fetching = false
            }
            disconnect(connection)
        }
    }

    override fun close() {
        val connection = synchronized(lock) { closed = true; active.also { active = null } }
        disconnect(connection)
    }

    private fun disconnect(connection: HttpsURLConnection?) {
        try { connection?.disconnect() }
        catch (_: Exception) { throw AbemaBundleException(AbemaBundleFailure.NETWORK_FAILED) }
    }
}
