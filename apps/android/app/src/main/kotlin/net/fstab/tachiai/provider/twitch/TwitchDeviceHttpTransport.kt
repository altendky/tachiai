package net.fstab.tachiai.provider.twitch

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException

internal const val DEVICE_RESPONSE_LIMIT = 16 * 1024
internal enum class DeviceAuthEndpoint { DEVICE, TOKEN, VALIDATE }
internal enum class DeviceRequestStage { OPEN, CONFIGURE, WRITE, STATUS, READ, DECODE }
internal enum class DeviceNetworkFailure { DNS, TIMEOUT, TLS, CONNECTION, INTERRUPTED, BUDGET, BACKGROUND, IO }
private class DeviceResponseBudgetExceeded : InterruptedIOException()
internal data class DeviceHttpFailure(
    val endpoint: DeviceAuthEndpoint,
    val stage: DeviceRequestStage,
    val category: DeviceNetworkFailure,
    val elapsedMs: Long,
)

internal fun deviceNetworkFailure(error: IOException): DeviceNetworkFailure = when (error) {
    is DeviceRequestPaused -> DeviceNetworkFailure.BACKGROUND
    is DeviceResponseBudgetExceeded -> DeviceNetworkFailure.BUDGET
    is SocketTimeoutException -> DeviceNetworkFailure.TIMEOUT
    is UnknownHostException -> DeviceNetworkFailure.DNS
    is SSLException -> DeviceNetworkFailure.TLS
    is SocketException -> DeviceNetworkFailure.CONNECTION
    is InterruptedIOException -> DeviceNetworkFailure.INTERRUPTED
    else -> DeviceNetworkFailure.IO
}

internal fun deviceForm(fields: Map<String, String>): ByteArray = fields.entries.joinToString("&") {
    "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
}.toByteArray(Charsets.UTF_8)

// A fresh transport per attempt. Only fixed first-party HTTPS endpoints; no
// redirect following, cookie jar, body/header logging or TLS exceptions.
internal class TwitchDeviceHttpTransport(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val decode: (String) -> Map<String, Any?> = ::deviceResponseFields,
    private val onHttpStatus: (DeviceAuthEndpoint, Int) -> Unit = { _, _ -> },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onFailure: (DeviceHttpFailure) -> Unit = {},
    private val canRequest: () -> Boolean = { true },
    private val onDeviceRejection: (DeviceRejection) -> Unit = {},
) : TwitchDeviceTransport {
    private val lock = Any()
    private var closed = false
    private var active: HttpsURLConnection? = null
    private val observed = mutableSetOf<Pair<DeviceAuthEndpoint, Int>>()

    override fun device(clientId: String) = exchange(
        DeviceAuthEndpoint.DEVICE, mapOf("client_id" to clientId, "scopes" to ""),
    )

    override fun poll(clientId: String, deviceCode: String) = exchange(
        DeviceAuthEndpoint.TOKEN, mapOf("client_id" to clientId, "scopes" to "", "device_code" to deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"),
    )

    override fun validate(accessToken: String) = exchange(DeviceAuthEndpoint.VALIDATE, token = accessToken)

    private fun exchange(
        endpoint: DeviceAuthEndpoint,
        form: Map<String, String>? = null,
        token: String? = null,
    ): DeviceAuthResponse {
        val startedAt = clockMs()
        var connection: HttpsURLConnection? = null
        var stage = DeviceRequestStage.OPEN
        fun checkActive() {
            if (synchronized(lock) { closed }) throw InterruptedIOException()
            if (clockMs() - startedAt >= 30_000) throw DeviceResponseBudgetExceeded()
        }
        try {
            val request = synchronized(lock) {
                check(!closed)
                val created = open(URL("https://id.twitch.tv/oauth2/${endpoint.name.lowercase()}"))
                if (closed) {
                    created.disconnect()
                    throw InterruptedIOException()
                }
                created.also { active = it; connection = it }
            }
            stage = DeviceRequestStage.CONFIGURE
            request.instanceFollowRedirects = false
            request.connectTimeout = 10_000
            request.readTimeout = 15_000
            request.useCaches = false
            request.requestMethod = if (form == null) "GET" else "POST"
            request.setRequestProperty("Accept", "application/json")
            if (token != null) {
                require(Regex("[A-Za-z0-9._~+/=-]{1,4096}").matches(token))
                request.setRequestProperty("Authorization", "OAuth $token")
            }
            if (form != null) {
                val body = deviceForm(form)
                request.doOutput = true
                request.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                request.setFixedLengthStreamingMode(body.size)
                stage = DeviceRequestStage.WRITE
                checkActive()
                if (!canRequest()) throw DeviceRequestPaused()
                request.outputStream.use { it.write(body) }
            }
            stage = DeviceRequestStage.STATUS
            checkActive()
            if (form == null && !canRequest()) throw DeviceRequestPaused()
            val status = request.responseCode
            checkActive()
            val report = synchronized(lock) {
                !closed && observed.size < 8 && observed.add(endpoint to status)
            }
            if (report) onHttpStatus(endpoint, status)
            // Redirects and other errors are terminal; never open their target.
            if (status != 200 && status != 400) return DeviceAuthResponse(status, emptyMap())
            stage = DeviceRequestStage.READ
            if (request.contentLengthLong > DEVICE_RESPONSE_LIMIT) throw InvalidDeviceResponse(DeviceResponseIssue.SIZE)
            val input = if (status == 200) request.inputStream else request.errorStream
            if (input == null) throw InvalidDeviceResponse()
            val text = input.use {
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    checkActive()
                    val count = it.read(buffer)
                    checkActive()
                    if (count < 0) break
                    if (bytes.size() + count > DEVICE_RESPONSE_LIMIT) throw InvalidDeviceResponse(DeviceResponseIssue.SIZE)
                    bytes.write(buffer, 0, count)
                }
                bytes.toString("UTF-8")
            }
            stage = DeviceRequestStage.DECODE
            checkActive()
            val fields = decode(text)
            if (endpoint == DeviceAuthEndpoint.DEVICE && status == 400) onDeviceRejection(classifyDeviceRejection(fields))
            return DeviceAuthResponse(status, fields)
        } catch (error: IOException) {
            if (!synchronized(lock) { closed }) {
                onFailure(DeviceHttpFailure(endpoint, stage, deviceNetworkFailure(error),
                    (clockMs() - startedAt).coerceIn(0, 120_000)))
            }
            throw error
        } finally {
            synchronized(lock) { if (active === connection) active = null }
            connection?.disconnect()
        }
    }

    override fun close() {
        val connection = synchronized(lock) {
            closed = true
            active.also { active = null }
        }
        connection?.disconnect()
    }
}

// Keep only fields used by this probe; refresh tokens/account names are not
// retained in the normalized response. Raw JSON is never surfaced or logged.
private fun deviceResponseFields(body: String): Map<String, Any?> {
    val json = try { JSONObject(body) }
    catch (_: JSONException) { throw InvalidDeviceResponse(DeviceResponseIssue.JSON) }
    val allowed = setOf("device_code", "user_code", "verification_uri", "interval", "expires_in",
        "access_token", "token_type", "scope", "scopes", "client_id", "user_id", "error", "message")
    return allowed.filter(json::has).associateWith { key ->
        val value = json.opt(key)
        if (value == null || value == JSONObject.NULL) null
        else if (value is JSONArray) (0 until value.length()).map(value::opt)
        else value
    }
}
