package net.fstab.tachiai.provider.abema.account

import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

internal sealed interface AbemaAccountProfileResult {
    class Identity(val id: String) : AbemaAccountProfileResult { override fun toString() = "AbemaAccountProfileResult.Identity(redacted)" }
    data object Rejected : AbemaAccountProfileResult
    data object Inconclusive : AbemaAccountProfileResult
}
internal enum class AbemaAccountEmailResult { REGISTERED, UNREGISTERED, REJECTED, INCONCLUSIVE }
internal interface AbemaAccountTransport : AutoCloseable {
    fun profile(id: String, bearer: String): AbemaAccountProfileResult
    fun emailRegistration(id: String, bearer: String): AbemaAccountEmailResult = AbemaAccountEmailResult.INCONCLUSIVE
    fun token(challenge: AbemaAccountChallenge): AbemaAccountTokenResult
    fun cancelActiveRequest()
}

// Account-owned native close only. Each resource has one bounded first attempt;
// a timeout, Throwable or reentrant wait is permanently unconfirmed even if the
// daemon eventually returns. The receipt keeps the original resource identity.
internal class AbemaAccountCleanupReceipt<T : Any>(val resource: T, private val cleanup: (T) -> Unit,
    private val waitMs: Long = 1000) {
    companion object {
        private val workers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(2), { task -> Thread(task, "abema-account-cleanup").apply { isDaemon = true } })
    }
    private val lock = Any()
    private val finished = CountDownLatch(1)
    private var started = false
    @Volatile private var owner: Thread? = null
    @Volatile private var failed = false
    @Volatile private var successful = false
    val confirmed get() = finished.count == 0L && successful && !failed
    fun start() {
        val first = synchronized(lock) { if (started) false else { started = true; true } }
        if (first) {
            try { workers.execute {
                owner = Thread.currentThread()
                try { cleanup(resource); successful = true } catch (_: Throwable) { failed = true }
                finally { finished.countDown() }
            } } catch (_: Throwable) { failed = true; finished.countDown() }
        }
    }
    fun await(): Boolean {
        start()
        if (finished.count != 0L) {
            if (owner === Thread.currentThread()) { failed = true; return false }
            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
            var interrupted = false
            try {
                while (finished.count != 0L) {
                    val left = end - System.nanoTime(); if (left <= 0) { failed = true; return false }
                    try { finished.await(left, TimeUnit.NANOSECONDS) } catch (_: InterruptedException) { interrupted = true }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
        return confirmed
    }
    override fun toString() = "AbemaAccountCleanupReceipt(redacted)"
}

// The supplied opener MUST be an owned credential-free route: no ambient
// Authenticator, proxy credentials or cookie jar. Java's default opener is not
// an allowed fallback. Every return follows confirmed connection cleanup.
internal class AbemaAccountHttpTransport(
    private val open: (URL) -> HttpsURLConnection,
    private val canRequest: () -> Boolean,
    private val elapsedMs: () -> Long,
    private val wallMs: () -> Long,
    private val cleanupWaitMs: Long = 1000,
) : AbemaAccountTransport {
    companion object {
        // Rejected/late/failed cleanup remains retained until process restart.
        private val retained = ConcurrentLinkedQueue<Handle>()
    }
    init { require(cleanupWaitMs in 1..5000) }
    private class Handle(val connection: HttpsURLConnection, waitMs: Long) {
        private val lock = Any()
        private val disconnect = AbemaAccountCleanupReceipt(connection, HttpsURLConnection::disconnect, waitMs)
        private var input: AbemaAccountCleanupReceipt<java.io.InputStream>? = null
        val retainedOnce = AtomicBoolean()
        private val wait = waitMs
        fun own(stream: java.io.InputStream) { synchronized(lock) { check(input == null); input = AbemaAccountCleanupReceipt(stream, java.io.InputStream::close, wait) } }
        val confirmed get() = disconnect.confirmed && synchronized(lock) { input?.confirmed != false }
        fun cleanup(): Boolean {
            val stream = synchronized(lock) { input }
            // Initiate both exactly once before either bounded wait. Disconnect
            // may signal a blocked read/close; uncertainty never releases route.
            stream?.start(); disconnect.start()
            val streamClosed = stream?.await() != false
            val disconnected = disconnect.await()
            return streamClosed && disconnected
        }
    }
    private val lock = Any()
    private var closed = false
    private var poisoned = false
    private var busy = false
    private var revision = 0L
    private var active: Handle? = null
    val cleanupConfirmed: Boolean get() = synchronized(lock) { !poisoned && !busy && active == null }
    private fun poison(handle: Handle?) {
        synchronized(lock) { poisoned = true }
        if (handle != null && handle.retainedOnce.compareAndSet(false, true)) retained.add(handle)
    }
    private class Reply(val status: Int, val body: String?) { override fun toString() = "AbemaAccountReply(redacted)" }
    private fun request(url: URL, bearer: String, body: String? = null, deadline: Long? = null): Reply {
        val revision = synchronized(lock) {
            if (closed || poisoned || busy) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            busy = true; this.revision
        }
        val began = elapsedMs()
        fun checkCurrent() {
            if (CookieHandler.getDefault() != null) throw AbemaAccountException(AbemaAccountFailure.NOT_VERIFIED)
            if (!synchronized(lock) { !closed && !poisoned && this.revision == revision } || !canRequest())
                throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
            val now = elapsedMs()
            if (now < began || now - began >= 15_000 || deadline != null && now >= deadline)
                throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
        }
        var handle: Handle? = null
        var input: java.io.InputStream? = null
        try {
            checkCurrent()
            if (bearer.length !in 1..8192 || bearer.any { it.code !in 33..126 }) accountRefused()
            handle = Handle(open(url), cleanupWaitMs)
            synchronized(lock) { active = handle }
            val connection = handle.connection
            checkCurrent()
            if (connection.url.toExternalForm() != url.toExternalForm()) accountRefused()
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                if (bytes.size > 8192) accountRefused()
                connection.doOutput = true; connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type", "application/json")
                try { connection.outputStream.use { checkCurrent(); it.write(bytes); checkCurrent() } }
                finally { bytes.fill(0) }
            }
            checkCurrent(); val status = connection.responseCode; checkCurrent()
            if (connection.url.toExternalForm() != url.toExternalForm()) accountRefused()
            // Unknown success/refusal/redirect never opens a response body.
            if (status != 200 && !(body != null && status == 400)) return Reply(status, null)
            val type = connection.contentType ?: accountRefused()
            if (!type.matches(Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:UTF-8|utf-8|\"UTF-8\"|\"utf-8\"))?", RegexOption.IGNORE_CASE))) accountRefused()
            val length = connection.contentLengthLong
            if (length > 65536) accountRefused()
            input = if (status == 400) connection.errorStream ?: accountRefused() else connection.inputStream
            handle.own(input)
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    checkCurrent(); val count = input.read(buffer); checkCurrent()
                    if (count < 0) break
                    if (bytes.size() + count > 65536) accountRefused()
                    bytes.write(buffer, 0, count)
                }
                if (length >= 0 && length != bytes.size().toLong()) accountRefused()
                val response = bytes.toByteArray()
                try { return Reply(status, strictAccountUtf8(response)) } finally { response.fill(0) }
            } finally { buffer.fill(0) }
        } catch (error: AbemaAccountException) { throw error }
        catch (_: Throwable) { throw AbemaAccountException(AbemaAccountFailure.TEMPORARY) }
        finally {
            val failed = handle?.cleanup() == false || synchronized(lock) { poisoned }
            if (failed) poison(handle)
            synchronized(lock) { if (!failed && active === handle) active = null; busy = false }
            if (failed) throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
            checkCurrent()
        }
    }
    override fun profile(id: String, bearer: String): AbemaAccountProfileResult {
        if (!accountId(id)) accountRefused()
        val result = request(URL("https://api.p-c3-e.abema-tv.com/v1/users/$id"), bearer)
        return when (result.status) {
            200 -> AbemaAccountProfileResult.Identity(parseAbemaAccountProfile(result.body ?: accountRefused()))
            401, 403 -> AbemaAccountProfileResult.Rejected
            else -> AbemaAccountProfileResult.Inconclusive
        }
    }
    override fun emailRegistration(id: String, bearer: String): AbemaAccountEmailResult {
        if (!accountId(id)) accountRefused()
        val result = request(URL("https://api.p-c3-e.abema-tv.com/v1/users/$id/email"), bearer)
        return when (result.status) {
            200 -> parseAbemaAccountEmailRegistration(result.body ?: accountRefused())
            401, 403 -> AbemaAccountEmailResult.REJECTED
            404 -> AbemaAccountEmailResult.UNREGISTERED
            else -> AbemaAccountEmailResult.INCONCLUSIVE
        }
    }
    override fun token(challenge: AbemaAccountChallenge): AbemaAccountTokenResult {
        if (!validAbemaTokenBody(challenge.tokenBody, challenge.clientId, challenge.deviceCode)) accountRefused()
        val result = request(URL("https://abema.tv/api/auth/oauth/device/token"), challenge.guestBearer,
            challenge.tokenBody, challenge.deadlineElapsedMs)
        return parseAbemaAccountToken(result.status, result.body, challenge.guestId, wallMs())
    }
    override fun cancelActiveRequest() {
        val pending = synchronized(lock) { revision++; active }
        if (pending?.cleanup() == false) poison(pending)
    }
    override fun close() {
        synchronized(lock) { closed = true }; cancelActiveRequest()
        if (synchronized(lock) { busy }) poison(synchronized(lock) { active })
        if (synchronized(lock) { poisoned }) throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
    }
    override fun toString() = "AbemaAccountHttpTransport(redacted)"
}
