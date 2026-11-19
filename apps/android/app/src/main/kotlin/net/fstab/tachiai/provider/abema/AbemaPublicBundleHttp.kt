package net.fstab.tachiai.provider.abema

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

// A separate public-code transport, not a widening of the media/access policy.
// No provider identity, token, license data, redirect, TLS override or logging.
internal class AbemaPublicBundleHttp(
    private val canRun: () -> Boolean = { true },
    private val identity: AbemaPublicBundleIdentity = AbemaPublicBundleIdentity(),
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onFailure: (Throwable) -> Unit = {},
    private val onConnectionOwned: (HttpsURLConnection) -> Unit = {},
    private val openInput: (HttpsURLConnection) -> InputStream = { it.inputStream },
) : AutoCloseable {
    private enum class Disconnect { CONFIRMED, AVAILABLE, IN_PROGRESS, FAILED }
    private val lock = Any()
    private var closed = false
    private var active: HttpsURLConnection? = null
    private var activeInput: InputStream? = null
    private var disconnect = Disconnect.CONFIRMED
    private var disconnectDone: CountDownLatch? = null
    private var cleanupFailed = false
    private var fetching = false
    val cleanupConfirmed get() = synchronized(lock) {
        !fetching && active == null && activeInput == null && !cleanupFailed && disconnect == Disconnect.CONFIRMED
    }

    // Keep actual HTTP/body ownership until both close receipts are confirmed.
    // A held or failed claimant is never replaced by a second disconnect.
    private fun releaseConfirmedConnection() {
        if (disconnect == Disconnect.CONFIRMED && activeInput == null && !cleanupFailed) active = null
    }

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
                if (fetching || active != null || cleanupFailed) throw AbemaBundleException(AbemaBundleFailure.POLICY_REFUSED)
                fetching = true
                claimed = true
            }
            val request = open(identity.uri.toURL())
            connection = request
            synchronized(lock) {
                active = request
                disconnect = Disconnect.AVAILABLE
            }
            onConnectionOwned(request)
            checkActive()
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
            val input = openInput(request)
            synchronized(lock) { activeInput = input }
            val bytes = try {
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
            } finally {
                try {
                    input.close()
                    synchronized(lock) {
                        if (activeInput === input) activeInput = null
                        releaseConfirmedConnection()
                    }
                } catch (error: Throwable) {
                    synchronized(lock) { cleanupFailed = true }
                    runCatching { onFailure(error) }; throw error
                }
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
            try { disconnect(connection) }
            finally { if (claimed) synchronized(lock) { fetching = false } }
        }
    }

    override fun close() {
        val connection = synchronized(lock) { closed = true; active }
        disconnect(connection)
        synchronized(lock) {
            if (active != null || activeInput != null || cleanupFailed || disconnect != Disconnect.CONFIRMED)
                throw AbemaBundleException(AbemaBundleFailure.NETWORK_FAILED)
        }
    }

    // Cancellation attempt only: seal and disconnect, but a body or disconnect
    // still held by the fetch thread is not a failure. That owner's finally and
    // close() remain the confirmation; only an actual failed release throws.
    fun cancel() {
        val connection = synchronized(lock) { closed = true; active }
        try { disconnect(connection) } catch (error: AbemaBundleException) {
            if (synchronized(lock) { cleanupFailed }) throw error
        }
        if (synchronized(lock) { cleanupFailed }) throw AbemaBundleException(AbemaBundleFailure.NETWORK_FAILED)
    }

    private fun disconnect(connection: HttpsURLConnection?) {
        if (connection == null) return
        // A concurrent claimant's disconnect is this caller's result too: wait
        // briefly for it instead of reporting a release that has not failed.
        val pending = synchronized(lock) { disconnectDone.takeIf { active === connection && disconnect == Disconnect.IN_PROGRESS } }
        if (pending != null) awaitUninterruptibly(pending, 2_000)
        val done = CountDownLatch(1)
        synchronized(lock) {
            if (active !== connection) return
            when (disconnect) {
                Disconnect.CONFIRMED -> return
                Disconnect.AVAILABLE -> { disconnect = Disconnect.IN_PROGRESS; disconnectDone = done }
                Disconnect.IN_PROGRESS, Disconnect.FAILED -> throw AbemaBundleException(AbemaBundleFailure.NETWORK_FAILED)
            }
        }
        try {
            connection.disconnect()
            synchronized(lock) {
                disconnect = Disconnect.CONFIRMED
                releaseConfirmedConnection()
            }
        }
        catch (error: Throwable) {
            synchronized(lock) { disconnect = Disconnect.FAILED; cleanupFailed = true }
            runCatching { onFailure(error) }
            throw AbemaBundleException(AbemaBundleFailure.NETWORK_FAILED)
        } finally { done.countDown() }
    }

    private fun awaitUninterruptibly(latch: CountDownLatch, timeoutMs: Long) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var interrupted = false
        try {
            while (true) {
                val remaining = until - System.nanoTime()
                if (remaining <= 0) return
                try { if (latch.await(remaining, TimeUnit.NANOSECONDS)) return }
                catch (_: InterruptedException) { interrupted = true }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}
