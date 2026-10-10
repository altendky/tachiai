package net.fstab.tachiai.provider.twitch.catalog

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.provider.twitch.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal interface TwitchCatalogTransport : AutoCloseable {
    fun device(): DeviceAuthResponse
    fun poll(deviceCode: String): DeviceAuthResponse
    fun validate(accessToken: String): DeviceAuthResponse
    fun refresh(refreshToken: String): DeviceAuthResponse
    // Interrupt current I/O for foreground pause without consuming this transport.
    fun cancelActiveRequest() = Unit
}

internal enum class TwitchCatalogAuthEndpoint { DEVICE, POLL, VALIDATE, REFRESH }
internal data class TwitchCatalogHttpFailure(
    val endpoint: TwitchCatalogAuthEndpoint, val stage: DeviceRequestStage,
    val category: DeviceNetworkFailure, val elapsedMs: Long,
)
internal class TwitchCatalogNetworkException(val category: DeviceNetworkFailure) : IOException(category.name)
private class CatalogResponseBudgetExceeded : InterruptedIOException()

// Fixed first-party endpoints, one request at a time and no browser cookie jar,
// redirects, TLS exceptions or response/header logging. The owner injects its route.
internal class TwitchCatalogAuthTransport(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val decode: (String) -> Map<String, Any?> = ::catalogResponseFields,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val canRequest: () -> Boolean = { true },
    private val onFailure: (TwitchCatalogHttpFailure) -> Unit = {},
) : TwitchCatalogTransport {
    private val lock = Any()
    private var closed = false
    private var active: HttpsURLConnection? = null
    private var reserved = false
    private var pauseRevision = 0L

    override fun device() = exchange(TwitchCatalogAuthEndpoint.DEVICE,
        mapOf("client_id" to TACHIAI_TWITCH_CLIENT_ID, "scopes" to TWITCH_CATALOG_SCOPE))

    override fun poll(deviceCode: String): DeviceAuthResponse {
        require(validCatalogRefreshToken(deviceCode))
        return exchange(TwitchCatalogAuthEndpoint.POLL, mapOf("client_id" to TACHIAI_TWITCH_CLIENT_ID,
            "scopes" to TWITCH_CATALOG_SCOPE, "device_code" to deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
    }

    override fun validate(accessToken: String): DeviceAuthResponse {
        require(validCatalogAccessToken(accessToken))
        return exchange(TwitchCatalogAuthEndpoint.VALIDATE, token = accessToken)
    }

    override fun refresh(refreshToken: String): DeviceAuthResponse {
        require(validCatalogRefreshToken(refreshToken))
        return exchange(TwitchCatalogAuthEndpoint.REFRESH, mapOf("client_id" to TACHIAI_TWITCH_CLIENT_ID,
            "grant_type" to "refresh_token", "refresh_token" to refreshToken))
    }

    private fun exchange(endpoint: TwitchCatalogAuthEndpoint, form: Map<String, String>? = null,
        token: String? = null): DeviceAuthResponse {
        val started = clockMs()
        var connection: HttpsURLConnection? = null
        var ownsReservation = false
        var revision: Long? = null
        var stage = DeviceRequestStage.OPEN
        fun checkActive() {
            if (synchronized(lock) { closed }) throw InterruptedIOException()
            if (synchronized(lock) { revision != null && revision != pauseRevision }) throw DeviceRequestPaused()
            if (clockMs() - started >= 30_000) throw CatalogResponseBudgetExceeded()
            if (!canRequest()) throw DeviceRequestPaused()
        }
        try {
            checkActive()
            revision = synchronized(lock) {
                if (closed) throw InterruptedIOException()
                check(!reserved)
                reserved = true
                ownsReservation = true
                pauseRevision
            }
            val path = when (endpoint) {
                TwitchCatalogAuthEndpoint.DEVICE -> "device"
                TwitchCatalogAuthEndpoint.VALIDATE -> "validate"
                else -> "token"
            }
            // Route preparation may block. Cancellation must remain able to signal
            // the owner and reject a handle returned after pause/close.
            checkActive()
            val request = open(URL("https://id.twitch.tv/oauth2/$path")).also { connection = it }
            synchronized(lock) {
                if (closed) throw InterruptedIOException()
                if (revision != pauseRevision) throw DeviceRequestPaused()
                active = request
            }
            stage = DeviceRequestStage.CONFIGURE
            request.instanceFollowRedirects = false
            request.connectTimeout = 10_000
            request.readTimeout = 15_000
            request.useCaches = false
            request.requestMethod = if (form == null) "GET" else "POST"
            request.setRequestProperty("Accept", "application/json")
            if (token != null) request.setRequestProperty("Authorization", "OAuth $token")
            if (form != null) {
                val body = deviceForm(form)
                request.doOutput = true
                request.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                request.setFixedLengthStreamingMode(body.size)
                stage = DeviceRequestStage.WRITE
                checkActive()
                request.outputStream.use { it.write(body) }
            }
            stage = DeviceRequestStage.STATUS
            checkActive()
            val status = request.responseCode
            checkActive()
            // Never consume a redirect target or arbitrary error text.
            if (status != 200 && status != 400) return DeviceAuthResponse(status, emptyMap())
            stage = DeviceRequestStage.READ
            if (request.contentLengthLong > DEVICE_RESPONSE_LIMIT) throw TwitchCatalogAuthException(TwitchCatalogAuthFailure.INVALID_RESPONSE)
            val input = if (status == 200) request.inputStream else request.errorStream
            if (input == null) throw TwitchCatalogAuthException(TwitchCatalogAuthFailure.INVALID_RESPONSE)
            val body = input.use {
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    checkActive()
                    val count = it.read(buffer)
                    checkActive()
                    if (count < 0) break
                    if (bytes.size() + count > DEVICE_RESPONSE_LIMIT) throw TwitchCatalogAuthException(TwitchCatalogAuthFailure.INVALID_RESPONSE)
                    bytes.write(buffer, 0, count)
                }
                bytes.toString("UTF-8")
            }
            stage = DeviceRequestStage.DECODE
            checkActive()
            val fields = decode(body)
            checkActive()
            return DeviceAuthResponse(status, fields)
        } catch (error: IOException) {
            val paused = !synchronized(lock) { closed } &&
                (!canRequest() || synchronized(lock) { revision != null && revision != pauseRevision })
            val category = if (paused) DeviceNetworkFailure.BACKGROUND
                else if (error is CatalogResponseBudgetExceeded) DeviceNetworkFailure.BUDGET else deviceNetworkFailure(error)
            if (!synchronized(lock) { closed }) onFailure(TwitchCatalogHttpFailure(endpoint, stage, category,
                (clockMs() - started).coerceIn(0, 120_000)))
            if (paused || error is DeviceRequestPaused) throw DeviceRequestPaused()
            throw TwitchCatalogNetworkException(category)
        } finally {
            synchronized(lock) {
                if (active === connection) active = null
                if (ownsReservation) reserved = false
            }
            connection?.disconnect()
        }
    }

    override fun close() {
        val request = synchronized(lock) { closed = true; active.also { active = null } }
        request?.disconnect()
    }
    override fun cancelActiveRequest() {
        val request = synchronized(lock) { pauseRevision++; active.also { active = null } }
        request?.disconnect()
    }
    override fun toString() = "TwitchCatalogAuthTransport(redacted)"
}

private fun catalogResponseFields(body: String): Map<String, Any?> {
    val json = try { JSONObject(body) }
    catch (_: JSONException) { throw TwitchCatalogAuthException(TwitchCatalogAuthFailure.INVALID_RESPONSE) }
    val allowed = setOf("device_code", "user_code", "verification_uri", "interval", "expires_in", "access_token",
        "refresh_token", "token_type", "scope", "scopes", "client_id", "user_id", "login", "error", "message")
    return allowed.filter(json::has).associateWith { key ->
        when (val value = json.opt(key)) {
            null, JSONObject.NULL -> null
            is JSONArray -> (0 until value.length()).map(value::opt)
            else -> value
        }
    }
}
