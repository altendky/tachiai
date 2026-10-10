package net.fstab.tachiai.provider.twitch.catalog

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.provider.twitch.*

internal enum class TwitchHelixOperation(val path: String) {
    FOLLOWED("channels/followed"), SEARCH("search/channels"), USERS("users"), STREAMS("streams"), VIDEOS("videos"), SCHEDULE("schedule")
}

internal class TwitchHelixRequest private constructor(val operation: TwitchHelixOperation, fields: List<Pair<String, String>>) {
    val url: URL = URL("https://api.twitch.tv/helix/${operation.path}" + if (fields.isEmpty()) "" else fields.joinToString("&", "?") {
        "${URLEncoder.encode(it.first, "UTF-8")}=${URLEncoder.encode(it.second, "UTF-8")}" })
    override fun toString() = "TwitchHelixRequest(${operation.name}, redacted)"
    companion object {
        private fun checkInput(value: Boolean) { if (!value) throw TwitchHelixException(TwitchHelixFailure.INVALID_INPUT) }
        private fun checkedId(value: String) = value.also { checkInput(validTwitchCatalogId(it)) }
        private fun ids(values: List<String>): List<String> {
            checkInput(values.size in 1..100); return values.map(::checkedId).distinct()
        }
        private fun page(after: String?): List<Pair<String, String>> {
            checkInput(after == null || validTwitchCatalogCursor(after))
            return listOf("first" to "20") + if (after == null) emptyList() else listOf("after" to after)
        }
        fun following(userId: String, after: String? = null) = TwitchHelixRequest(TwitchHelixOperation.FOLLOWED,
            listOf("user_id" to checkedId(userId)) + page(after))
        fun search(query: String, after: String? = null): TwitchHelixRequest {
            checkInput(query == query.trim() && query.length in 1..160 && query.none {
                it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() })
            return TwitchHelixRequest(TwitchHelixOperation.SEARCH, listOf("query" to query, "live_only" to "false") + page(after))
        }
        fun usersById(values: List<String>) = TwitchHelixRequest(TwitchHelixOperation.USERS, ids(values).map { "id" to it })
        fun userByLogin(login: String): TwitchHelixRequest {
            checkInput(validTwitchCatalogLogin(login)); return TwitchHelixRequest(TwitchHelixOperation.USERS, listOf("login" to login))
        }
        fun streams(userIds: List<String> = emptyList(), after: String? = null) = TwitchHelixRequest(TwitchHelixOperation.STREAMS,
            (if (userIds.isEmpty()) emptyList() else ids(userIds).map { "user_id" to it }) + page(after))
        fun video(id: String) = TwitchHelixRequest(TwitchHelixOperation.VIDEOS, listOf("id" to checkedId(id)))
        fun videos(broadcasterId: String, after: String? = null) = TwitchHelixRequest(TwitchHelixOperation.VIDEOS,
            listOf("user_id" to checkedId(broadcasterId), "type" to "all", "sort" to "time") + page(after))
        fun schedule(broadcasterId: String, startEpochMs: Long): TwitchHelixRequest {
            // A fixed UTC RFC3339 timestamp, bounded to four-digit years.
            checkInput(startEpochMs in 0..253_402_300_799_999L)
            return TwitchHelixRequest(TwitchHelixOperation.SCHEDULE,
                listOf("broadcaster_id" to checkedId(broadcasterId), "start_time" to Instant.ofEpochMilli(startEpochMs).toString()) + page(null))
        }
    }
}

// The route wrapper can independently admit only URLs reproducible by the fixed
// operations. No arbitrary host, path, extra key, redirect or query is admitted.
internal fun validTwitchHelixUrl(url: URL): Boolean = try {
    val uri = url.toURI()
    if (uri.scheme != "https" || uri.host != "api.twitch.tv" || uri.port != -1 || uri.rawUserInfo != null ||
        uri.rawFragment != null || uri.rawQuery == null || uri.rawQuery.length > 32768) false else {
        val fields = uri.rawQuery.split('&').map { part ->
            val separator = part.indexOf('='); if (separator <= 0) throw IllegalArgumentException()
            URLDecoder.decode(part.substring(0, separator), "UTF-8") to URLDecoder.decode(part.substring(separator + 1), "UTF-8")
        }.groupBy({ it.first }, { it.second })
        fun one(key: String) = fields[key]?.singleOrNull() ?: throw IllegalArgumentException()
        fun after(): String? = if (fields.containsKey("after")) one("after") else null
        fun keys(vararg allowed: String) { if (fields.keys.any { it !in allowed }) throw IllegalArgumentException() }
        fun page() { if (one("first") != "20") throw IllegalArgumentException() }
        val request = when (uri.rawPath) {
            "/helix/channels/followed" -> { keys("user_id", "first", "after"); page(); TwitchHelixRequest.following(one("user_id"), after()) }
            "/helix/search/channels" -> { keys("query", "live_only", "first", "after"); page()
                if (one("live_only") != "false") throw IllegalArgumentException()
                TwitchHelixRequest.search(one("query"), after()) }
            "/helix/users" -> { keys("id", "login")
                if (fields.containsKey("id") == fields.containsKey("login")) throw IllegalArgumentException()
                if (fields.containsKey("id")) TwitchHelixRequest.usersById(checkNotNull(fields["id"])) else TwitchHelixRequest.userByLogin(one("login")) }
            "/helix/streams" -> { keys("user_id", "first", "after"); page(); TwitchHelixRequest.streams(fields["user_id"].orEmpty(), after()) }
            "/helix/videos" -> {
                if (fields.containsKey("id")) { keys("id"); TwitchHelixRequest.video(one("id")) } else {
                    keys("user_id", "type", "sort", "first", "after"); page()
                    if (one("type") != "all" || one("sort") != "time") throw IllegalArgumentException()
                    TwitchHelixRequest.videos(one("user_id"), after())
                }
            }
            "/helix/schedule" -> {
                keys("broadcaster_id", "start_time", "first"); page()
                TwitchHelixRequest.schedule(one("broadcaster_id"), Instant.parse(one("start_time")).toEpochMilli())
            }
            else -> throw IllegalArgumentException()
        }
        url.toExternalForm() == request.url.toExternalForm()
    }
} catch (_: Exception) { false }

internal class TwitchHelixResponse(val status: Int, val fields: Map<String, Any?> = emptyMap(), val retryAtEpochMs: Long? = null) {
    init { require(status in 100..599 && (retryAtEpochMs == null || status == 429 && retryAtEpochMs >= 0)) }
    override fun toString() = "TwitchHelixResponse(status=$status, redacted)"
}
internal interface TwitchHelixTransport : AutoCloseable {
    fun execute(accessToken: String, request: TwitchHelixRequest): TwitchHelixResponse
    fun cancelActiveRequest() = Unit
}
internal data class TwitchHelixHttpFailure(val operation: TwitchHelixOperation, val stage: DeviceRequestStage,
    val category: DeviceNetworkFailure, val elapsedMs: Long)
internal class TwitchHelixNetworkException(val category: DeviceNetworkFailure) : IOException(category.name)
private class HelixBudgetExceeded : InterruptedIOException()
internal const val TWITCH_HELIX_RESPONSE_LIMIT = 128 * 1024

// One HTTP exchange only. The catalog owns bounded 401/503 retries and checks
// its session lease before dispatch and publication; this transport owns I/O.
internal class TwitchHelixHttpTransport(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val decode: (String) -> Map<String, Any?> = ::twitchHelixResponseFields,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val canRequest: () -> Boolean = { true },
    private val onFailure: (TwitchHelixHttpFailure) -> Unit = {},
) : TwitchHelixTransport {
    private val lock = Any()
    private var closed = false
    private var reserved = false
    private var active: HttpsURLConnection? = null
    private var pauseRevision = 0L
    override fun execute(accessToken: String, request: TwitchHelixRequest): TwitchHelixResponse {
        if (!validCatalogAccessToken(accessToken)) throw TwitchHelixException(TwitchHelixFailure.INVALID_INPUT)
        val started = clockMs()
        var connection: HttpsURLConnection? = null
        var ownsReservation = false
        var revision: Long? = null
        var stage = DeviceRequestStage.OPEN
        fun permitted(): Boolean = try { canRequest() }
            catch (_: Exception) { throw TwitchHelixException(TwitchHelixFailure.INVALID_RESPONSE) }
        fun checkActive() {
            if (synchronized(lock) { closed }) throw InterruptedIOException()
            if (synchronized(lock) { revision != null && revision != pauseRevision } || !permitted()) throw DeviceRequestPaused()
            if (clockMs() - started >= 30_000) throw HelixBudgetExceeded()
        }
        try {
            checkActive()
            revision = synchronized(lock) {
                if (closed || reserved) throw InterruptedIOException()
                reserved = true; ownsReservation = true; pauseRevision
            }
            checkActive()
            val http = open(request.url).also { connection = it }
            synchronized(lock) {
                if (closed) throw InterruptedIOException()
                if (revision != pauseRevision) throw DeviceRequestPaused()
                active = http
            }
            stage = DeviceRequestStage.CONFIGURE
            http.instanceFollowRedirects = false; http.useCaches = false
            http.connectTimeout = 10_000; http.readTimeout = 15_000; http.requestMethod = "GET"
            http.setRequestProperty("Accept", "application/json")
            http.setRequestProperty("Client-Id", SMART_TV_TWITCH_CLIENT_ID)
            http.setRequestProperty("Authorization", "Bearer $accessToken")
            stage = DeviceRequestStage.STATUS
            checkActive(); val status = http.responseCode; checkActive()
            if (status !in 100..599) throw TwitchHelixException(TwitchHelixFailure.INVALID_RESPONSE)
            if (status == 429) {
                val deadline = retryDeadline(http.getHeaderField("Ratelimit-Reset")); checkActive()
                return TwitchHelixResponse(status, retryAtEpochMs = deadline)
            }
            if (status != 200) return TwitchHelixResponse(status)
            stage = DeviceRequestStage.READ
            if (http.contentLengthLong > TWITCH_HELIX_RESPONSE_LIMIT) throw TwitchHelixException(TwitchHelixFailure.INVALID_RESPONSE)
            val body = http.inputStream.use { input ->
                val bytes = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) {
                    checkActive(); val count = input.read(buffer); checkActive()
                    if (count < 0) break
                    if (bytes.size() + count > TWITCH_HELIX_RESPONSE_LIMIT) throw TwitchHelixException(TwitchHelixFailure.INVALID_RESPONSE)
                    bytes.write(buffer, 0, count)
                }
                bytes.toString("UTF-8")
            }
            stage = DeviceRequestStage.DECODE
            checkActive(); val fields = decode(body); checkActive()
            return TwitchHelixResponse(status, fields)
        } catch (error: IOException) {
            val paused = !synchronized(lock) { closed } &&
                (!permitted() || synchronized(lock) { revision != null && revision != pauseRevision })
            val category = if (paused) DeviceNetworkFailure.BACKGROUND else if (error is HelixBudgetExceeded)
                DeviceNetworkFailure.BUDGET else deviceNetworkFailure(error)
            if (!synchronized(lock) { closed }) {
                try { onFailure(TwitchHelixHttpFailure(request.operation, stage, category, (clockMs() - started).coerceIn(0, 120_000))) }
                catch (_: Exception) { throw TwitchHelixException(TwitchHelixFailure.INVALID_RESPONSE) }
            }
            if (paused || error is DeviceRequestPaused) throw DeviceRequestPaused()
            throw TwitchHelixNetworkException(category)
        } catch (error: TwitchHelixException) { throw error }
        catch (_: Exception) { throw TwitchHelixException(TwitchHelixFailure.INVALID_RESPONSE) }
        finally {
            try { connection?.disconnect() }
            catch (_: Exception) { throw TwitchHelixNetworkException(DeviceNetworkFailure.IO) }
            finally { synchronized(lock) { if (active === connection) active = null; if (ownsReservation) reserved = false } }
        }
    }
    private fun retryDeadline(header: String?): Long {
        val now = wallMs().coerceIn(0, Long.MAX_VALUE - 86_400_000)
        val seconds = header?.takeIf { it.length in 1..20 && it.all(Char::isDigit) }?.toLongOrNull()
        val reset = seconds?.let { try { Math.multiplyExact(it, 1000L) } catch (_: ArithmeticException) { Long.MAX_VALUE } }
        return (reset ?: (now + 60_000)).coerceIn(now, now + 86_400_000)
    }
    override fun cancelActiveRequest() {
        val request = synchronized(lock) { pauseRevision++; active.also { active = null } }
        try { request?.disconnect() } catch (_: Exception) { throw TwitchHelixNetworkException(DeviceNetworkFailure.IO) }
    }
    override fun close() {
        val request = synchronized(lock) { closed = true; active.also { active = null } }
        try { request?.disconnect() } catch (_: Exception) { throw TwitchHelixNetworkException(DeviceNetworkFailure.IO) }
    }
    override fun toString() = "TwitchHelixHttpTransport(redacted)"
}
