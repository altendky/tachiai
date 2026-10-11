package net.fstab.tachiai.provider.twitch.catalog

import java.io.ByteArrayOutputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.provider.twitch.SMART_TV_TWITCH_CLIENT_ID

internal enum class TwitchContinueWatchingFailure { POLICY_REFUSED, INVALID_RESPONSE, USER_MISMATCH, RESPONSE_LIMIT, NETWORK_FAILED, CANCELLED, CLEANUP_FAILED }
internal class TwitchContinueWatchingException(val category: TwitchContinueWatchingFailure) : Exception(category.name, null, false, false)
internal class TwitchContinueWatchingResponse(val status: Int, val page: TwitchContinueWatchingPage? = null,
    val collectionId: String = TWITCH_CONTINUE_WATCHING_ID) {
    init { require(status in 100..599 && (status == 200) == (page != null)) }
    override fun toString() = "TwitchContinueWatchingResponse(redacted)"
}
internal interface TwitchContinueWatchingTransport : AutoCloseable {
    val collectionId: String get() = TWITCH_CONTINUE_WATCHING_ID
    val cleanupConfirmed: Boolean
    fun fetch(accessToken: String, expectedUserId: String, nowEpochMs: Long): TwitchContinueWatchingResponse
    fun cancelActiveRequest()
}

internal const val TWITCH_CONTINUE_WATCHING_URL = "https://gql.twitch.tv/gql"
// Reduced selections are all present in the coherent first-party documents.
// Fixed operation/limit; there is no arbitrary query, URL or continuation input.
internal val twitchContinueWatchingRequestBody: ByteArray get() =
    ("{\"operationName\":\"FollowedStreamsContinueWatching\",\"variables\":{\"limit\":20},\"query\":\"" +
        "query FollowedStreamsContinueWatching(\u0024limit: Int) { currentUser { id viewedVideos(first: \u0024limit) { " +
        "edges { history { position updatedAt } node { id title lengthSeconds owner { id login } } } } } }\"}").toByteArray(Charsets.UTF_8)

// Separate callers can supply separate bounded pools, preventing parent route
// or transport cleanup from occupying the workers needed by its child cleanup.
internal class TwitchCollectionCleanupReceipt<T : Any>(val resource: T, private val action: (T) -> Unit,
    private val waitMs: Long = 1000, private val executor: Executor = nativeWorkers) {
    companion object {
        private val nativeWorkers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(4), { task -> Thread(task, "twitch-collection-native-close").apply { isDaemon = true } })
    }
    init { require(waitMs in 1..5000) }
    private val lock = Any()
    private val done = CountDownLatch(1)
    private var started = false
    @Volatile private var owner: Thread? = null
    @Volatile private var failed = false
    @Volatile private var successful = false
    val confirmed get() = done.count == 0L && successful && !failed
    fun start() {
        if (!synchronized(lock) { if (started) false else { started = true; true } }) return
        try { executor.execute {
            owner = Thread.currentThread()
            try { action(resource); successful = true } catch (_: Throwable) { failed = true }
            finally { done.countDown() }
        } } catch (_: Throwable) { failed = true; done.countDown() }
    }
    fun await(): Boolean {
        start()
        if (done.count != 0L) {
            if (owner === Thread.currentThread()) { failed = true; return false }
            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
            var interrupted = false
            try {
                while (done.count != 0L) {
                    val remaining = end - System.nanoTime()
                    if (remaining <= 0) { failed = true; return false }
                    try { done.await(remaining, TimeUnit.NANOSECONDS) } catch (_: InterruptedException) { interrupted = true }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
        return confirmed
    }
    override fun toString() = "TwitchCollectionCleanupReceipt(redacted)"
}

// Process-owned uncertainty never expires or disappears through Forget. The
// Android binding checks this before catalog, account and playback admission.
internal object TwitchContinueWatchingAdmission {
    private val poisoned = ConcurrentHashMap.newKeySet<String>()
    private val retained = ConcurrentLinkedQueue<Any>()
    fun poisoned(instanceId: String) = poisoned.contains(instanceId)
    fun poison(instanceId: String, resource: Any) {
        require(net.fstab.tachiai.presentation.validProviderInstanceId(instanceId))
        poisoned.add(instanceId); retained.add(resource)
    }
}

// Mandatory opener owns a private no-cookie/no-ambient-auth route. No default
// opener, browser identity, blank Client-ID, redirects or integrity fallback.
internal class TwitchContinueWatchingHttpTransport(
    private val open: (URL) -> HttpsURLConnection,
    private val canRequest: () -> Boolean,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val cleanupWaitMs: Long = 1000,
) : TwitchContinueWatchingTransport {
    private class Handle(val connection: HttpsURLConnection, waitMs: Long) {
        val disconnect = TwitchCollectionCleanupReceipt(connection, HttpsURLConnection::disconnect, waitMs)
        @Volatile var input: TwitchCollectionCleanupReceipt<java.io.InputStream>? = null
        @Volatile var output: TwitchCollectionCleanupReceipt<java.io.OutputStream>? = null
        val confirmed get() = disconnect.confirmed && input?.confirmed != false && output?.confirmed != false
        fun close(): Boolean {
            output?.start(); input?.start(); disconnect.start()
            val out = output?.await() != false; val read = input?.await() != false; val disconnected = disconnect.await()
            return out && read && disconnected
        }
    }
    companion object {
        private val pending = ConcurrentHashMap.newKeySet<TwitchContinueWatchingHttpTransport>()
        private val retained = ConcurrentLinkedQueue<Any>()
    }
    private val lock = Any()
    private var closed = false
    private var poisoned = false
    private var busy = false
    private var revision = 0L
    private var active: Handle? = null
    override val cleanupConfirmed get() = synchronized(lock) { !poisoned && !busy && active == null }
    private fun fail(category: TwitchContinueWatchingFailure): Nothing = throw TwitchContinueWatchingException(category)
    override fun fetch(accessToken: String, expectedUserId: String, nowEpochMs: Long): TwitchContinueWatchingResponse {
        if (!validCatalogAccessToken(accessToken) || !validCatalogUserId(expectedUserId) || nowEpochMs < 0)
            fail(TwitchContinueWatchingFailure.POLICY_REFUSED)
        val began = try { clockMs() } catch (_: Throwable) { fail(TwitchContinueWatchingFailure.CANCELLED) }
        val local = synchronized(lock) {
            if (poisoned) fail(TwitchContinueWatchingFailure.CLEANUP_FAILED)
            if (closed) fail(TwitchContinueWatchingFailure.CANCELLED)
            if (busy) fail(TwitchContinueWatchingFailure.POLICY_REFUSED)
            busy = true; pending.add(this); revision
        }
        var handle: Handle? = null
        var returning = false
        fun check() {
            val now = try { clockMs() } catch (_: Throwable) { fail(TwitchContinueWatchingFailure.CANCELLED) }
            if (!synchronized(lock) { !closed && !poisoned && revision == local } ||
                now < began || now - began < 0 || now - began >= 30_000) fail(TwitchContinueWatchingFailure.CANCELLED)
            val allowed = try { canRequest() } catch (_: Throwable) { false }
            val after = try { clockMs() } catch (_: Throwable) { fail(TwitchContinueWatchingFailure.CANCELLED) }
            if (!allowed || !synchronized(lock) { !closed && !poisoned && revision == local } ||
                after < began || after - began < 0 || after - began >= 30_000) fail(TwitchContinueWatchingFailure.CANCELLED)
        }
        try {
            check()
            val connection = open(URL(TWITCH_CONTINUE_WATCHING_URL))
            val owned = Handle(connection, cleanupWaitMs); handle = owned
            synchronized(lock) { active = owned }
            check()
            if (connection.url.toExternalForm() != TWITCH_CONTINUE_WATCHING_URL) fail(TwitchContinueWatchingFailure.POLICY_REFUSED)
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Client-ID", SMART_TV_TWITCH_CLIENT_ID)
            connection.setRequestProperty("Authorization", "OAuth $accessToken")
            val request = twitchContinueWatchingRequestBody
            connection.setFixedLengthStreamingMode(request.size)
            check()
            val output = connection.outputStream
            owned.output = TwitchCollectionCleanupReceipt(output, java.io.OutputStream::close, cleanupWaitMs)
            check(); output.write(request); check()
            if (!checkNotNull(owned.output).await()) fail(TwitchContinueWatchingFailure.CLEANUP_FAILED)
            check(); val status = connection.responseCode; check()
            if (status !in 100..599) fail(TwitchContinueWatchingFailure.INVALID_RESPONSE)
            if (status != 200) { returning = true; return TwitchContinueWatchingResponse(status) }
            val type = connection.contentType?.lowercase(Locale.ROOT)?.split(';')?.map(String::trim)
            if (type == null || type.first() != "application/json" || type.drop(1).any { it != "charset=utf-8" })
                fail(TwitchContinueWatchingFailure.INVALID_RESPONSE)
            val length = connection.contentLengthLong
            if (length > TWITCH_CONTINUE_WATCHING_BYTES) fail(TwitchContinueWatchingFailure.RESPONSE_LIMIT)
            if (length < -1 || length == 0L) fail(TwitchContinueWatchingFailure.INVALID_RESPONSE)
            check(); val input = connection.inputStream
            owned.input = TwitchCollectionCleanupReceipt(input, java.io.InputStream::close, cleanupWaitMs)
            val bytes = ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) {
                check(); val count = input.read(buffer); check()
                if (count < 0) break
                if (bytes.size() + count > TWITCH_CONTINUE_WATCHING_BYTES) fail(TwitchContinueWatchingFailure.RESPONSE_LIMIT)
                bytes.write(buffer, 0, count)
            }
            val raw = bytes.toByteArray()
            try {
                if (length >= 0 && raw.size.toLong() != length) fail(TwitchContinueWatchingFailure.INVALID_RESPONSE)
                val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString() }
                catch (_: java.nio.charset.CharacterCodingException) { fail(TwitchContinueWatchingFailure.INVALID_RESPONSE) }
                val page = parseTwitchContinueWatching(text, expectedUserId, nowEpochMs)
                check(); returning = true; return TwitchContinueWatchingResponse(200, page)
            } finally { raw.fill(0); buffer.fill(0); bytes.reset() }
        } catch (error: Throwable) {
            throw if (error is TwitchContinueWatchingException) error else TwitchContinueWatchingException(TwitchContinueWatchingFailure.NETWORK_FAILED)
        } finally {
            val clean = handle?.close() != false
            if (!clean) synchronized(lock) { poisoned = true; retained.add(this); handle?.let(retained::add) }
            try {
                if (!clean || synchronized(lock) { poisoned }) fail(TwitchContinueWatchingFailure.CLEANUP_FAILED)
                if (returning) check()
            } finally {
                synchronized(lock) { busy = false; if (!poisoned) { active = null; pending.remove(this) } }
            }
        }
    }
    override fun cancelActiveRequest() {
        val handle = synchronized(lock) { revision++; active }
        handle?.disconnect?.start()
    }
    override fun close() {
        val handle = synchronized(lock) { closed = true; revision++; active }
        handle?.disconnect?.start()
    }
    override fun toString() = "TwitchContinueWatchingHttpTransport(redacted)"
}
