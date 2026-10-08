package net.fstab.tachiai.platform.net

import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal enum class AccessProbeEndpoint { ABEMA_CHANNELS, ABEMA_DASH, TWITCH_ACCESS, ABEMA_HLS }
internal enum class AccessProbeOutcome {
    AUTHORIZATION_FIELDS_PRESENT, CLIENT_REJECTED, PROVIDER_ERROR, INVALID_RESPONSE,
    HTTP_REJECTED, DASH_MARKERS_PRESENT, DASH_PROTECTION_MARKERS_PRESENT,
    CHANNEL_MISSING, SOURCE_NOT_ALLOWLISTED, NETWORK_FAILED, CANCELLED,
    DASH_WIDEVINE_MARKERS_PRESENT, HLS_MASTER_MARKERS_PRESENT, HLS_MEDIA_MARKERS_PRESENT,
    HLS_PROTECTION_MARKERS_PRESENT, HLS_ABEMA_KEY_MARKERS_PRESENT,
    DASH_COMMON_ENCRYPTION_MARKERS_PRESENT, DASH_PLAYREADY_MARKERS_PRESENT,
    DASH_CLEARKEY_MARKERS_PRESENT, DASH_MARLIN_MARKERS_PRESENT, DASH_MULTIPLE_DRM_MARKERS_PRESENT,
}
internal data class AccessProbeResult(val endpoint: AccessProbeEndpoint, val outcome: AccessProbeOutcome, val http: Int = 0)

internal fun allowedAccessProbeUri(endpoint: AccessProbeEndpoint, uri: URI): Boolean {
    if (uri.scheme != "https" || uri.rawUserInfo != null || uri.rawFragment != null ||
        uri.port !in listOf(-1, 443)) return false
    return when (endpoint) {
        AccessProbeEndpoint.TWITCH_ACCESS -> uri.toString() == "https://gql.twitch.tv/gql"
        AccessProbeEndpoint.ABEMA_CHANNELS -> uri.toString() == "https://api.abema.io/v1/channels"
        // The exact public metadata source is used, never a synthesized URL.
        AccessProbeEndpoint.ABEMA_DASH -> uri.host == "linear-abematv.akamaized.net" &&
            uri.rawQuery == null && uri.path.endsWith(".mpd")
        AccessProbeEndpoint.ABEMA_HLS -> uri.host == "linear-abematv.akamaized.net" &&
            uri.rawQuery == null && uri.path.endsWith(".m3u8")
    }
}

// Request-local bodies/headers never escape this transport except to the caller's
// classifier. No logging, cookies imported from WebView, redirects or TLS overrides.
internal class AccessProbeHttp(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val canRequest: () -> Boolean = { true },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var active: HttpsURLConnection? = null

    fun <T> exchange(
        endpoint: AccessProbeEndpoint, uri: URI,
        body: ByteArray? = null, token: String? = null, clientId: String? = null,
        inspectTwitchErrors: Boolean = false,
        blankTwitchClientHeader: Boolean = false,
        classify: (Int, String) -> T,
    ): T {
        require(allowedAccessProbeUri(endpoint, uri))
        require(!inspectTwitchErrors || endpoint == AccessProbeEndpoint.TWITCH_ACCESS)
        // Explicit authenticated comparison only; never weakens anonymous/ABEMA policy.
        require(!blankTwitchClientHeader || (endpoint == AccessProbeEndpoint.TWITCH_ACCESS &&
            inspectTwitchErrors && token != null && body != null))
        require(body == null || endpoint == AccessProbeEndpoint.TWITCH_ACCESS)
        require(if (endpoint == AccessProbeEndpoint.TWITCH_ACCESS)
            clientId != null && Regex("[A-Za-z0-9]{10,64}").matches(clientId) else clientId == null)
        require(token == null || (endpoint == AccessProbeEndpoint.TWITCH_ACCESS &&
            Regex("[A-Za-z0-9._~+/=-]{1,4096}").matches(token)))
        val started = clockMs()
        fun checkActive() {
            if (synchronized(lock) { closed } || !canRequest() || clockMs() - started >= 30_000) {
                throw InterruptedIOException()
            }
        }
        var connection: HttpsURLConnection? = null
        try {
            checkActive()
            val request = synchronized(lock) {
                checkActive()
                val created = open(uri.toURL())
                if (closed) { created.disconnect(); throw InterruptedIOException() }
                created.also { active = it; connection = it }
            }
            request.instanceFollowRedirects = false
            request.connectTimeout = 10_000
            request.readTimeout = 10_000
            request.useCaches = false
            request.requestMethod = if (body == null) "GET" else "POST"
            request.setRequestProperty("Accept", when (endpoint) {
                AccessProbeEndpoint.ABEMA_DASH -> "application/dash+xml"
                AccessProbeEndpoint.ABEMA_HLS -> "application/vnd.apple.mpegurl"
                else -> "application/json"
            })
            if (endpoint == AccessProbeEndpoint.TWITCH_ACCESS) {
                request.setRequestProperty("Client-ID", if (blankTwitchClientHeader) "" else clientId)
                if (token != null) request.setRequestProperty("Authorization", "OAuth $token")
            }
            if (body != null) {
                require(body.size <= 4096)
                request.doOutput = true
                request.setRequestProperty("Content-Type", "application/json")
                request.setFixedLengthStreamingMode(body.size)
                checkActive()
                request.outputStream.use { it.write(body) }
            }
            checkActive()
            val status = request.responseCode
            checkActive()
            val readableError = endpoint == AccessProbeEndpoint.TWITCH_ACCESS &&
                (status == 400 || (inspectTwitchErrors && status in listOf(401, 403)))
            if (status != 200 && !readableError) {
                return classify(status, "")
            }
            val limit = when (endpoint) {
                AccessProbeEndpoint.ABEMA_CHANNELS -> 1024 * 1024
                AccessProbeEndpoint.ABEMA_DASH, AccessProbeEndpoint.ABEMA_HLS -> 256 * 1024
                AccessProbeEndpoint.TWITCH_ACCESS -> if (inspectTwitchErrors) 16 * 1024 else 64 * 1024
            }
            if (request.contentLengthLong > limit) throw InterruptedIOException()
            val input = if (status == 200) request.inputStream else request.errorStream
            if (input == null) return classify(status, "")
            val text = input.use {
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    checkActive()
                    val count = it.read(buffer)
                    checkActive()
                    if (count < 0) break
                    if (bytes.size() + count > limit) throw InterruptedIOException()
                    bytes.write(buffer, 0, count)
                }
                bytes.toString("UTF-8")
            }
            checkActive()
            return classify(status, text)
        } finally {
            synchronized(lock) { if (active === connection) active = null }
            connection?.disconnect()
        }
    }

    override fun close() {
        val connection = synchronized(lock) { closed = true; active.also { active = null } }
        connection?.disconnect()
    }
}
