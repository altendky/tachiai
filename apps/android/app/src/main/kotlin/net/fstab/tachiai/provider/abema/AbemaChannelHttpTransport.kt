package net.fstab.tachiai.provider.abema

import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

private const val ABEMA_CHANNEL_URL = "https://api.abema.io/v1/channels"

internal fun validAbemaChannelUrl(url: URL): Boolean = url.toExternalForm() == ABEMA_CHANNEL_URL

// The opener is mandatory: production uses an owned route with authentication
// disabled, including System. Java URL.openConnection can inherit a process-wide
// Authenticator, which Android has no public getter to inspect safely.
internal class AbemaChannelHttpTransport(
    private val open: (URL) -> HttpsURLConnection,
    private val canRequest: () -> Boolean = { true },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val onFailure: (Exception) -> Unit = {},
) : AbemaChannelTransport {
    private class Handle(val connection: HttpsURLConnection) {
        private val lock = Any()
        private var started = false
        private var completed = false
        private var failed = false
        fun disconnect(): Boolean {
            synchronized(lock) {
                if (started) return completed && !failed
                started = true
            }
            val success = try { connection.disconnect(); true } catch (_: Exception) { false }
            synchronized(lock) { completed = true; failed = !success }
            return success
        }
    }

    private val lock = Any()
    private var closed = false
    private var poisoned = false
    private var busy = false
    private var revision = 0L
    private var active: Handle? = null

    override fun fetch(): AbemaChannelResponse {
        var handle: Handle? = null
        var reserved = false
        var returning = false
        var started = 0L
        var current = 0L
        try {
            started = clockMs()
            current = synchronized(lock) {
                if (poisoned) fail(AbemaChannelFailure.CLEANUP_FAILED)
                if (closed) fail(AbemaChannelFailure.CANCELLED)
                if (busy) fail(AbemaChannelFailure.POLICY_REFUSED)
                busy = true; reserved = true; revision
            }
            fun checkActive() = checkActive(current, started, handle)
            checkActive()
            val request = open(URL(ABEMA_CHANNEL_URL))
            val owned = Handle(request).also { handle = it }
            checkActive()
            synchronized(lock) {
                if (closed || revision != current) fail(AbemaChannelFailure.CANCELLED)
                active = owned
            }
            request.instanceFollowRedirects = false
            request.connectTimeout = 10_000
            request.readTimeout = 10_000
            request.useCaches = false
            request.requestMethod = "GET"
            request.setRequestProperty("Accept", "application/json")
            checkActive()
            val status = request.responseCode
            checkActive()
            if (status !in 100..599) fail(AbemaChannelFailure.INVALID_RESPONSE)
            if (status != 200) {
                val retry = if (status == 429) retryDeadline(request.getHeaderField("Retry-After")) else null
                checkActive()
                returning = true
                return AbemaChannelResponse(status, retryAtEpochMs = retry)
            }
            if (!validJsonContentType(request.contentType)) fail(AbemaChannelFailure.INVALID_RESPONSE)
            val length = request.contentLengthLong
            if (length > ABEMA_CHANNEL_RESPONSE_LIMIT) fail(AbemaChannelFailure.RESPONSE_LIMIT)
            if (length < -1 || length == 0L) fail(AbemaChannelFailure.INVALID_RESPONSE)
            checkActive()
            val bytes = request.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    checkActive()
                    if (count < 0) break
                    if (output.size() + count > ABEMA_CHANNEL_RESPONSE_LIMIT) fail(AbemaChannelFailure.RESPONSE_LIMIT)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            checkActive()
            if (length >= 0 && bytes.size.toLong() != length) fail(AbemaChannelFailure.INVALID_RESPONSE)
            val body = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) { fail(AbemaChannelFailure.INVALID_RESPONSE) }
            val entries = parseAbemaChannelResponse(body)
            checkActive()
            returning = true
            return AbemaChannelResponse(status, entries)
        } catch (error: Exception) {
            val safe = if (error is AbemaChannelException) error else AbemaChannelException(AbemaChannelFailure.NETWORK_FAILED)
            report(safe)
            throw safe
        } finally {
            val clean = handle?.disconnect() != false
            if (!clean) poison()
            try {
                if (!clean) fail(AbemaChannelFailure.CLEANUP_FAILED)
                if (returning) checkActive(current, started, handle)
            } catch (error: Exception) {
                val safe = if (error is AbemaChannelException) error else AbemaChannelException(AbemaChannelFailure.NETWORK_FAILED)
                if (clean) report(safe)
                throw safe
            } finally {
                synchronized(lock) {
                    if (active === handle) active = null
                    if (reserved) busy = false
                }
            }
        }
    }

    private fun checkActive(current: Long, started: Long, handle: Handle?) {
        synchronized(lock) {
            if (poisoned) fail(AbemaChannelFailure.CLEANUP_FAILED)
            if (closed || revision != current) fail(AbemaChannelFailure.CANCELLED)
        }
        if (Thread.currentThread().isInterrupted || !canRequest()) fail(AbemaChannelFailure.CANCELLED)
        val elapsed = clockMs() - started
        if (elapsed !in 0 until 30_000) fail(AbemaChannelFailure.TIME_LIMIT_REACHED)
        if (CookieHandler.getDefault() != null) fail(AbemaChannelFailure.COOKIE_HANDLER_REFUSED)
        handle?.let { if (!validAbemaChannelUrl(it.connection.url)) fail(AbemaChannelFailure.POLICY_REFUSED) }
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
        return try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli().coerceIn(now, maximum)
        } catch (_: Exception) { null }
    }

    override fun cancelActiveRequest() {
        val handle = synchronized(lock) { revision++; active }
        if (handle?.disconnect() == false) { poison(); fail(AbemaChannelFailure.CLEANUP_FAILED) }
    }

    override fun close() {
        val handle = synchronized(lock) {
            if (closed) return
            closed = true; revision++; active
        }
        if (handle?.disconnect() == false) { poison(); fail(AbemaChannelFailure.CLEANUP_FAILED) }
    }

    private fun poison() {
        val first = synchronized(lock) {
            if (poisoned) false else { poisoned = true; revision++; true }
        }
        if (first) report(AbemaChannelException(AbemaChannelFailure.CLEANUP_FAILED))
    }
    private fun report(error: AbemaChannelException) { runCatching { onFailure(error) } }
    private fun fail(category: AbemaChannelFailure): Nothing = throw AbemaChannelException(category)
    override fun toString() = "AbemaChannelHttpTransport(redacted)"
}

private fun validJsonContentType(value: String?): Boolean {
    val parts = value?.takeIf { it.length <= 128 }?.split(';') ?: return false
    if (parts.size !in 1..2 || parts[0].trim().lowercase(Locale.ROOT) != "application/json") return false
    if (parts.size == 1) return true
    val charset = parts[1].split('=')
    if (charset.size != 2 || charset[0].trim().lowercase(Locale.ROOT) != "charset") return false
    return charset[1].trim().lowercase(Locale.ROOT) in setOf("utf-8", "utf8", "\"utf-8\"", "\"utf8\"")
}
