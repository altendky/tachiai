package net.fstab.tachiai.provider.abema

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.CookieHandler
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

// The mandatory opener owns a credential-free System/imported route client.
// No ambient URL.openConnection, cookie, authenticator or redirect fallback.
internal class AbemaMyListHttpTransport(
    private val open: (URL) -> HttpsURLConnection,
    private val bearer: () -> String?,
    private val canRequest: () -> Boolean = { true },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val onFailure: (AbemaCollectionFailure) -> Unit = {},
    private val cleanupWaitMs: Long = 5_000,
) : AbemaCollectionTransport {
    init { require(cleanupWaitMs in 1..5_000) }

    // Every action has one actual invocation and a sticky first result. A held,
    // fatal or reentrant close cannot become successful through a later no-op.
    private class Receipt(private val action: () -> Unit) {
        private val lock = Any()
        private val complete = CountDownLatch(1)
        private var worker: Thread? = null
        private var done = false
        private var failed = false
        fun close(deadline: Long): Boolean {
            synchronized(lock) {
                if (done) return !failed
                if (worker == null) {
                    try {
                        worker = Thread({
                            var success = false
                            try { action(); success = true } catch (_: Throwable) { }
                            finally {
                                synchronized(lock) { done = true; if (!success) failed = true }
                                complete.countDown()
                            }
                        }, "abema-collection-cleanup").also { it.isDaemon = true; it.start() }
                    } catch (_: Throwable) {
                        done = true; failed = true; complete.countDown(); return false
                    }
                } else if (worker === Thread.currentThread()) { failed = true; return false }
            }
            var interrupted = false
            try {
                while (complete.count != 0L) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) { synchronized(lock) { failed = true }; return false }
                    try {
                        if (!complete.await(remaining, TimeUnit.NANOSECONDS)) {
                            synchronized(lock) { failed = true }; return false
                        }
                    } catch (_: InterruptedException) { interrupted = true }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
            return synchronized(lock) { done && !failed }
        }
    }
    private class Handle(val connection: HttpsURLConnection, val expected: String) {
        val disconnect = Receipt { connection.disconnect() }
        @Volatile var stream: Receipt? = null
    }
    private val lock = Any()
    private var closed = false
    private var poisoned = false
    private var busy = false
    private var revision = 0L
    private var active: Handle? = null

    override fun fetchMyList(next: String?): AbemaMyListResponse {
        var reserved = false
        var handle: Handle? = null
        var returning = false
        var began = 0L
        var local = 0L
        try {
            began = clockMs()
            local = synchronized(lock) {
                if (poisoned) fail(AbemaCollectionFailure.CLEANUP_FAILED)
                if (closed) fail(AbemaCollectionFailure.CANCELLED)
                if (busy) fail(AbemaCollectionFailure.POLICY_REFUSED)
                busy = true; reserved = true; revision
            }
            MyListHttpRetention.retain(this)
            fun check() = checkActive(local, began, handle)
            check()
            val url = try { abemaMyListRequestUri(next).toURL() } catch (_: Throwable) { fail(AbemaCollectionFailure.POLICY_REFUSED) }
            val token = bearer()
            check()
            if (token == null || token.length !in 1..6144 || token.any { it.code !in 33..126 })
                fail(AbemaCollectionFailure.POLICY_REFUSED)
            // Reserve before invoking the route factory. A concurrent close
            // retains this owner even before the eventual connection exists.
            val connection = open(url)
            val owned = Handle(connection, url.toExternalForm()).also { handle = it }
            synchronized(lock) { active = owned }
            check()
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            check()
            val status = connection.responseCode
            check()
            if (status !in 100..599) fail(AbemaCollectionFailure.INVALID_RESPONSE)
            if (status != 200) {
                val retry = if (status == 429) retryDeadline(connection.getHeaderField("Retry-After")) else null
                check(); returning = true
                return AbemaMyListResponse(status, retryAtEpochMs = retry)
            }
            if (!myListJsonContentType(connection.contentType)) fail(AbemaCollectionFailure.INVALID_RESPONSE)
            val length = connection.contentLengthLong
            if (length > ABEMA_MY_LIST_RESPONSE_LIMIT) fail(AbemaCollectionFailure.RESPONSE_LIMIT)
            if (length < -1 || length == 0L) fail(AbemaCollectionFailure.INVALID_RESPONSE)
            check()
            val input = connection.inputStream
            owned.stream = Receipt { input.close() }
            check()
            val bytes = read(input, ::check)
            check()
            if (length >= 0 && bytes.size.toLong() != length) fail(AbemaCollectionFailure.INVALID_RESPONSE)
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: Throwable) { fail(AbemaCollectionFailure.INVALID_RESPONSE) }
            val page = parseAbemaMyListResponse(text)
            check(); returning = true
            return AbemaMyListResponse(200, page)
        } catch (error: Throwable) {
            val safe = if (error is AbemaCollectionException) error else AbemaCollectionException(AbemaCollectionFailure.NETWORK_FAILED)
            report(safe.category); throw safe
        } finally {
            // Clean each allocated resource even when another cleanup fails.
            // Successful metadata only escapes after these receipts and owner
            // checks. Original throwable properties are never inspected.
            val clean = cleanup(handle)
            if (!clean) poison()
            try {
                if (!clean || synchronized(lock) { poisoned }) fail(AbemaCollectionFailure.CLEANUP_FAILED)
                if (returning) checkActive(local, began, handle)
            } catch (error: Throwable) {
                val safe = if (error is AbemaCollectionException) error else AbemaCollectionException(AbemaCollectionFailure.NETWORK_FAILED)
                if (clean) report(safe.category)
                throw safe
            } finally {
                if (reserved) synchronized(lock) {
                    busy = false
                    if (!poisoned && active === handle) active = null
                    if (!poisoned) MyListHttpRetention.release(this)
                }
            }
        }
    }

    private fun read(input: InputStream, check: () -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            check()
            val count = input.read(buffer)
            check()
            if (count == -1) break
            if (count !in 1..buffer.size) fail(AbemaCollectionFailure.INVALID_RESPONSE)
            if (output.size() + count > ABEMA_MY_LIST_RESPONSE_LIMIT) fail(AbemaCollectionFailure.RESPONSE_LIMIT)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun checkActive(local: Long, began: Long, handle: Handle?) {
        synchronized(lock) {
            if (poisoned) fail(AbemaCollectionFailure.CLEANUP_FAILED)
            if (closed || revision != local) fail(AbemaCollectionFailure.CANCELLED)
        }
        if (Thread.currentThread().isInterrupted || !canRequest()) fail(AbemaCollectionFailure.CANCELLED)
        val now = clockMs()
        if (now < began || now - began !in 0 until 30_000) fail(AbemaCollectionFailure.TIME_LIMIT_REACHED)
        if (CookieHandler.getDefault() != null) fail(AbemaCollectionFailure.COOKIE_HANDLER_REFUSED)
        if (handle != null && handle.connection.url.toExternalForm() != handle.expected)
            fail(AbemaCollectionFailure.POLICY_REFUSED)
    }
    private fun cleanup(handle: Handle?): Boolean {
        if (handle == null) return true
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cleanupWaitMs)
        val stream = try { handle.stream?.close(deadline) != false } catch (_: Throwable) { false }
        val connection = try { handle.disconnect.close(deadline) } catch (_: Throwable) { false }
        return stream && connection
    }
    private fun retryDeadline(header: String?): Long? {
        val value = header?.takeIf { it.length <= 128 }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val now = wallMs().coerceAtLeast(0)
        val maximum = if (now > Long.MAX_VALUE - 86_400_000) Long.MAX_VALUE else now + 86_400_000
        val seconds = value.takeIf { it.matches(Regex("[0-9]{1,19}")) }?.toLongOrNull()
        if (seconds != null) {
            val delay = seconds.coerceAtMost(86_400) * 1000
            return if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
        }
        return try { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli().coerceIn(now, maximum) }
        catch (_: Throwable) { null }
    }
    override fun cancelActiveRequest() {
        val handle = synchronized(lock) { revision++; active }
        if (!cleanup(handle)) { poison(); fail(AbemaCollectionFailure.CLEANUP_FAILED) }
    }
    override fun close() {
        val (handle, pending) = synchronized(lock) {
            if (poisoned) fail(AbemaCollectionFailure.CLEANUP_FAILED)
            if (closed) return
            closed = true; revision++; active to busy
        }
        // close is a confirmed-cleanup boundary, unlike cancellation. A worker
        // still allocating/reading cannot be represented as fully closed.
        if (!cleanup(handle) || pending) { poison(); fail(AbemaCollectionFailure.CLEANUP_FAILED) }
    }
    private fun poison() {
        val first = synchronized(lock) { if (poisoned) false else { poisoned = true; revision++; true } }
        MyListHttpRetention.retain(this)
        if (first) report(AbemaCollectionFailure.CLEANUP_FAILED)
    }
    private fun report(category: AbemaCollectionFailure) { try { onFailure(category) } catch (_: Throwable) { } }
    private fun fail(category: AbemaCollectionFailure): Nothing = throw AbemaCollectionException(category)
    override fun toString() = "AbemaMyListHttpTransport(redacted)"
}

private object MyListHttpRetention {
    private val owners = HashSet<AbemaMyListHttpTransport>()
    @Synchronized fun retain(owner: AbemaMyListHttpTransport) { owners.add(owner) }
    @Synchronized fun release(owner: AbemaMyListHttpTransport) { owners.remove(owner) }
}

private fun myListJsonContentType(value: String?): Boolean {
    val parts = value?.takeIf { it.length <= 128 }?.split(';') ?: return false
    if (parts.size !in 1..2 || parts[0].trim().lowercase(Locale.ROOT) != "application/json") return false
    if (parts.size == 1) return true
    val charset = parts[1].split('=')
    return charset.size == 2 && charset[0].trim().lowercase(Locale.ROOT) == "charset" &&
        charset[1].trim().lowercase(Locale.ROOT) in setOf("utf-8", "utf8", "\"utf-8\"", "\"utf8\"")
}
