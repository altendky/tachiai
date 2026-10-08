package net.fstab.tachiai.provider.twitch

import java.net.URI
import java.net.URLDecoder
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

// Public identifier, not a secret. Registered for Tachiai's device-flow probe.
internal const val TACHIAI_TWITCH_CLIENT_ID = "djsrsrw27br55f5z4yvbttmyndpwjz" // gitleaks:allow -- Public client ID, not an access token.

internal enum class DeviceAuthPhase {
    READY, INVALID_CLIENT_ID, REQUESTING, WAITING, PAUSED, VALIDATING, SUCCEEDED,
    CANCELLED, EXPIRED, DENIED, INVALID_CODE, REJECTED, NETWORK_ERROR,
    INVALID_RESPONSE, CLIENT_MISMATCH, SCOPE_MISMATCH, BROWSER_UNAVAILABLE;

    val terminal: Boolean get() = this !in setOf(READY, REQUESTING, WAITING, PAUSED, VALIDATING)
}

// Sensitive responses never cross into UI state, saved state or logs.
internal class DeviceAuthResponse(val status: Int, val fields: Map<String, Any?>) {
    override fun toString() = "DeviceAuthResponse(redacted)"
}

internal interface TwitchDeviceTransport : AutoCloseable {
    fun device(clientId: String): DeviceAuthResponse
    fun poll(clientId: String, deviceCode: String): DeviceAuthResponse
    fun validate(accessToken: String): DeviceAuthResponse
}

internal enum class DeviceResponseIssue {
    SCHEMA, JSON, SIZE, GRANT_ERROR_FIELDS, GRANT_TYPE, GRANT_LIFETIME,
    GRANT_SCOPE_TYPE, GRANT_TOKEN, VALIDATION_CLIENT, VALIDATION_USER,
    VALIDATION_LIFETIME, VALIDATION_SCOPES, UNEXPECTED,
}
internal enum class DeviceGrantScope { OMITTED, EMPTY }
internal enum class DeviceValidationScopes { OMITTED, NULL, EMPTY, NONEMPTY, OTHER }
internal class InvalidDeviceResponse(val issue: DeviceResponseIssue = DeviceResponseIssue.SCHEMA) : Exception()

private inline fun <T> checkedResponse(issue: DeviceResponseIssue, read: () -> T): T =
    try { read() } catch (_: InvalidDeviceResponse) { throw InvalidDeviceResponse(issue) }

internal class DeviceActivation(val userCode: String, val verificationUri: URI) {
    override fun toString() = "DeviceActivation(redacted)"
}

internal class DeviceChallenge(
    val deviceCode: String,
    val activation: DeviceActivation,
    val expiresMs: Long,
    val intervalMs: Long,
) {
    override fun toString() = "DeviceChallenge(redacted)"
}

internal fun validTwitchClientId(value: String): Boolean =
    Regex("[A-Za-z0-9]{10,64}").matches(value)

private fun Map<String, Any?>.boundedString(key: String, max: Int): String =
    (this[key] as? String)?.takeIf { it.isNotEmpty() && it.length <= max }
        ?: throw InvalidDeviceResponse()

private fun Map<String, Any?>.seconds(key: String, max: Long): Long {
    val value = this[key]
    val seconds = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw InvalidDeviceResponse()
    }
    if (seconds !in 1..max) throw InvalidDeviceResponse()
    return seconds * 1000
}

internal fun parseDeviceChallenge(response: DeviceAuthResponse): DeviceChallenge {
    val fields = response.fields
    val code = fields.boundedString("user_code", 64)
    if (!Regex("[A-Za-z0-9-]{4,64}").matches(code)) throw InvalidDeviceResponse()
    val uri = try { URI(fields.boundedString("verification_uri", 2048)) }
    catch (_: Exception) { throw InvalidDeviceResponse() }
    if (uri.scheme != "https" || uri.host != "www.twitch.tv" ||
        uri.rawUserInfo != null || uri.port !in setOf(-1, 443) ||
        uri.rawPath != "/activate" || uri.rawFragment != null
    ) throw InvalidDeviceResponse()
    // Accept only the documented activation parameters, binding any URL code
    // to the code shown by our native UI. Never accept a provider-supplied
    // arbitrary URL, redirect target, token or additional query parameter.
    val query = mutableMapOf<String, String>()
    try {
        uri.rawQuery?.split("&")?.forEach { part ->
            val pair = part.split("=", limit = 2)
            if (pair.size != 2) throw InvalidDeviceResponse()
            val key = URLDecoder.decode(pair[0], "UTF-8")
            val value = URLDecoder.decode(pair[1], "UTF-8")
            if (key !in setOf("public", "device-code") || query.put(key, value) != null)
                throw InvalidDeviceResponse()
        }
    } catch (_: Exception) { throw InvalidDeviceResponse() }
    if ((query.containsKey("public") && query["public"] != "true") ||
        (query.containsKey("device-code") && query["device-code"] != code)
    ) throw InvalidDeviceResponse()
    return DeviceChallenge(
        fields.boundedString("device_code", 2048), DeviceActivation(code, uri),
        fields.seconds("expires_in", 3600),
        if (fields.containsKey("interval")) fields.seconds("interval", 300) else 5000,
    )
}

private enum class PollError { PENDING, SLOW_DOWN, DENIED, EXPIRED, INVALID, OTHER }

private fun errorCode(value: Any?): PollError? = when (value) {
    "authorization_pending" -> PollError.PENDING
    "slow_down" -> PollError.SLOW_DOWN
    "access_denied" -> PollError.DENIED
    "expired_token" -> PollError.EXPIRED
    "invalid device code", "invalid_device_code" -> PollError.INVALID
    else -> null
}

private fun pollError(response: DeviceAuthResponse): PollError {
    val rfc = errorCode(response.fields["error"])
    val twitch = errorCode(response.fields["message"])
    if (rfc != null && twitch != null && rfc != twitch) throw InvalidDeviceResponse()
    // Do not let an unknown RFC error be overridden by a recognized message.
    if (response.fields.containsKey("error") && rfc == null) return PollError.OTHER
    return rfc ?: twitch ?: PollError.OTHER
}

private fun accessToken(response: DeviceAuthResponse, onGrantScope: (DeviceGrantScope) -> Unit,
    inspectSmartTvLifetime: Boolean = false): String {
    val fields = response.fields
    if (fields["error"] != null || fields["message"] != null)
        throw InvalidDeviceResponse(DeviceResponseIssue.GRANT_ERROR_FIELDS)
    if (!(fields["token_type"] as? String).equals("bearer", ignoreCase = true))
        throw InvalidDeviceResponse(DeviceResponseIssue.GRANT_TYPE)
    if (!inspectSmartTvLifetime || deviceLifetimeShape(fields) !in setOf(DeviceLifetimeShape.OMITTED, DeviceLifetimeShape.ZERO))
        checkedResponse(DeviceResponseIssue.GRANT_LIFETIME) { fields.seconds("expires_in", Int.MAX_VALUE.toLong()) }
    // RFC 6749 section 5.1 permits omission when unchanged from the request.
    // We requested no scopes; /validate must still independently report an
    // empty array or the observed null/no-scopes convention used by Twitch CLI.
    val omitted = !fields.containsKey("scope")
    val scopes = if (omitted) emptyList<Any?>() else
        fields["scope"] as? List<*> ?: throw InvalidDeviceResponse(DeviceResponseIssue.GRANT_SCOPE_TYPE)
    if (scopes.isNotEmpty()) throw UnexpectedDeviceScopes()
    onGrantScope(if (omitted) DeviceGrantScope.OMITTED else DeviceGrantScope.EMPTY)
    return checkedResponse(DeviceResponseIssue.GRANT_TOKEN) { fields.boundedString("access_token", 4096) }.also {
        if (!Regex("[A-Za-z0-9._~+/=-]+").matches(it)) throw InvalidDeviceResponse(DeviceResponseIssue.GRANT_TOKEN)
    }
}

private class UnexpectedDeviceScopes : Exception()

internal fun validatedDeviceToken(
    response: DeviceAuthResponse,
    clientId: String,
    onValidationScopes: (DeviceValidationScopes) -> Unit = {},
    allowZeroLifetime: Boolean = false,
): DeviceAuthPhase {
    if (response.status != 200) return DeviceAuthPhase.REJECTED
    val fields = response.fields
    if (checkedResponse(DeviceResponseIssue.VALIDATION_CLIENT) { fields.boundedString("client_id", 64) } != clientId)
        return DeviceAuthPhase.CLIENT_MISMATCH
    checkedResponse(DeviceResponseIssue.VALIDATION_USER) { fields.boundedString("user_id", 128) }
    if (!allowZeroLifetime || deviceLifetimeShape(fields) != DeviceLifetimeShape.ZERO)
        checkedResponse(DeviceResponseIssue.VALIDATION_LIFETIME) { fields.seconds("expires_in", Int.MAX_VALUE.toLong()) }
    // Shape only, never scope names or response values. For this zero-scope
    // probe, present null follows Twitch CLI's nil-slice/no-scopes convention.
    // This is an experimentally observed representation, not a documented
    // provider guarantee. A missing field must not be normalized to empty.
    val shape = when {
        !fields.containsKey("scopes") -> DeviceValidationScopes.OMITTED
        fields["scopes"] == null -> DeviceValidationScopes.NULL
        fields["scopes"] is List<*> -> if ((fields["scopes"] as List<*>).isEmpty())
            DeviceValidationScopes.EMPTY else DeviceValidationScopes.NONEMPTY
        else -> DeviceValidationScopes.OTHER
    }
    onValidationScopes(shape)
    return when (shape) {
        DeviceValidationScopes.EMPTY, DeviceValidationScopes.NULL -> DeviceAuthPhase.SUCCEEDED
        DeviceValidationScopes.NONEMPTY -> DeviceAuthPhase.SCOPE_MISMATCH
        else -> throw InvalidDeviceResponse(DeviceResponseIssue.VALIDATION_SCOPES)
    }
}

// Zero scopes is intentional and experimental. The default validates app OAuth
// only. An explicit own-client access case may inspect authorization fields;
// neither path authenticates a website/embed or starts media playback.
internal suspend fun authorizeTwitchDevice(
    clientId: String,
    transport: TwitchDeviceTransport,
    onPhase: suspend (DeviceAuthPhase) -> Unit,
    onActivation: suspend (DeviceActivation) -> Unit,
    clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    waitMs: suspend (Long) -> Unit = { delay(it) },
    foreground: DeviceAuthorizationForeground = DeviceAuthorizationForeground(),
    onResponseIssue: (DeviceResponseIssue) -> Unit = {},
    onGrantScope: (DeviceGrantScope) -> Unit = {},
    onValidationScopes: (DeviceValidationScopes) -> Unit = {},
    // Optional separate debug access case; the default validation-only probe
    // never hands its token onward. Exact own-client only, worker-local use.
    onOwnClientValidated: (suspend (String, Long) -> Unit)? = null,
    // Explicit separate provider-identity experiment. Never relax the own-client
    // callback or hand arbitrary client registrations onward.
    onProviderClientValidated: (suspend (String, Long) -> Unit)? = null,
    providerProfile: TwitchAuthorizationProfile = TwitchAuthorizationProfile.PROVIDER_PLAYBACK,
    // Separate non-persistent inspection only, never a saved/access callback.
    inspectSmartTvLifetime: Boolean = false,
    onLifetimeShape: (DeviceAuthEndpoint, DeviceLifetimeShape) -> Unit = { _, _ -> },
    retainSmartTvLocally: Boolean = false,
): DeviceAuthPhase {
    var lastPhase: DeviceAuthPhase? = null
    suspend fun publish(phase: DeviceAuthPhase) {
        if (lastPhase != phase) {
            onPhase(phase)
            lastPhase = phase
        }
    }
    suspend fun ready(deadline: Long, phase: DeviceAuthPhase): Boolean {
        if (!foreground.isForeground) publish(DeviceAuthPhase.PAUSED)
        if (!foreground.awaitForeground(deadline, clockMs)) return false
        publish(phase)
        return true
    }
    fun backgroundInterruption(revision: Long, error: IOException): Boolean =
        error is DeviceRequestPaused || !foreground.isForeground || foreground.pauseRevision != revision
    try {
        if (!validTwitchClientId(clientId)) return DeviceAuthPhase.INVALID_CLIENT_ID
        if (inspectSmartTvLifetime && (clientId != SMART_TV_TWITCH_CLIENT_ID ||
            providerProfile != TwitchAuthorizationProfile.PROVIDER_SMART_TV ||
            onOwnClientValidated != null || onProviderClientValidated != null)) return DeviceAuthPhase.INVALID_CLIENT_ID
        if (retainSmartTvLocally && (inspectSmartTvLifetime || clientId != SMART_TV_TWITCH_CLIENT_ID ||
            providerProfile != TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL ||
            onOwnClientValidated != null || onProviderClientValidated == null)) return DeviceAuthPhase.INVALID_CLIENT_ID
        // The new local slot cannot silently use strict-provider defaults.
        if (providerProfile == TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL && !retainSmartTvLocally)
            return DeviceAuthPhase.INVALID_CLIENT_ID
        if (onOwnClientValidated != null && clientId != TACHIAI_TWITCH_CLIENT_ID) return DeviceAuthPhase.INVALID_CLIENT_ID
        if (onProviderClientValidated != null && (providerProfile == TwitchAuthorizationProfile.TACHIAI ||
            clientId != providerProfile.clientId)) return DeviceAuthPhase.INVALID_CLIENT_ID
        if (onOwnClientValidated != null && onProviderClientValidated != null) return DeviceAuthPhase.INVALID_CLIENT_ID
        if (!ready(Long.MAX_VALUE, DeviceAuthPhase.REQUESTING)) return DeviceAuthPhase.EXPIRED
        val requestedAt = clockMs()
        val response = transport.device(clientId)
        currentCoroutineContext().ensureActive()
        if (response.status != 200) return DeviceAuthPhase.REJECTED
        val challenge = parseDeviceChallenge(response)
        val deadline = Math.addExact(requestedAt, challenge.expiresMs)
        var interval = challenge.intervalMs
        if (clockMs() >= deadline) return DeviceAuthPhase.EXPIRED
        onActivation(challenge.activation)
        publish(DeviceAuthPhase.WAITING)
        while (true) {
            val remaining = deadline - clockMs()
            if (remaining <= 0) return DeviceAuthPhase.EXPIRED
            waitMs(minOf(interval, remaining))
            currentCoroutineContext().ensureActive()
            if (!ready(deadline, DeviceAuthPhase.WAITING)) return DeviceAuthPhase.EXPIRED
            val revision = foreground.pauseRevision
            val pollStartedAt = clockMs()
            val polled = try {
                transport.poll(clientId, challenge.deviceCode)
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                if (!backgroundInterruption(revision, error)) throw error
                // A timeout may have consumed a one-time grant. Do not claim
                // approval succeeded; a later invalid-code response is terminal.
                publish(DeviceAuthPhase.PAUSED)
                if (error is java.net.SocketTimeoutException) interval = minOf(interval * 2, challenge.expiresMs)
                continue
            }
            currentCoroutineContext().ensureActive()
            if (polled.status == 200) {
                val grantLifetime = deviceLifetimeShape(polled.fields)
                onLifetimeShape(DeviceAuthEndpoint.TOKEN, grantLifetime)
                val flexibleLifetime = inspectSmartTvLifetime || retainSmartTvLocally
                val token = accessToken(polled, onGrantScope, flexibleLifetime)
                val localCap = if (retainSmartTvLocally) SMART_TV_LOCAL_RETENTION_MS else SMART_TV_LIFETIME_INSPECTION_MS
                val lifetime = if (flexibleLifetime && grantLifetime in setOf(DeviceLifetimeShape.OMITTED, DeviceLifetimeShape.ZERO))
                    localCap else polled.fields.seconds("expires_in", Int.MAX_VALUE.toLong())
                val tokenDeadline = Math.addExact(pollStartedAt, if (flexibleLifetime) minOf(lifetime, localCap) else lifetime)
                val validationDeadline = if (flexibleLifetime)
                    minOf(tokenDeadline, Math.addExact(pollStartedAt, SMART_TV_LIFETIME_INSPECTION_MS)) else tokenDeadline
                // Once granted, never poll the one-time device code again.
                // Keep only this worker-local token until foreground validation.
                while (true) {
                    if (!ready(validationDeadline, DeviceAuthPhase.VALIDATING)) return DeviceAuthPhase.EXPIRED
                    val validationRevision = foreground.pauseRevision
                    val validationStartedAt = clockMs()
                    val validation = try {
                        transport.validate(token)
                    } catch (error: IOException) {
                        currentCoroutineContext().ensureActive()
                        if (!backgroundInterruption(validationRevision, error)) throw error
                        continue
                    }
                    currentCoroutineContext().ensureActive()
                    onLifetimeShape(DeviceAuthEndpoint.VALIDATE, deviceLifetimeShape(validation.fields))
                    val result = validatedDeviceToken(validation, clientId, onValidationScopes, allowZeroLifetime = flexibleLifetime)
                    if (flexibleLifetime && clockMs() >= validationDeadline) return DeviceAuthPhase.EXPIRED
                    val onValidated = onOwnClientValidated ?: onProviderClientValidated
                    if (result == DeviceAuthPhase.SUCCEEDED && onValidated != null) {
                        val validatedLifetime = if (retainSmartTvLocally && deviceLifetimeShape(validation.fields) == DeviceLifetimeShape.ZERO)
                            SMART_TV_LOCAL_RETENTION_MS else validation.fields.seconds("expires_in", Int.MAX_VALUE.toLong())
                        val accessDeadline = minOf(tokenDeadline, Math.addExact(validationStartedAt, validatedLifetime))
                        if (!ready(if (retainSmartTvLocally) minOf(accessDeadline, validationDeadline) else accessDeadline,
                            DeviceAuthPhase.VALIDATING)) return DeviceAuthPhase.EXPIRED
                        currentCoroutineContext().ensureActive()
                        onValidated(token, accessDeadline)
                    }
                    return result
                }
            }
            if (polled.status != 400) return DeviceAuthPhase.REJECTED
            when (pollError(polled)) {
                PollError.PENDING -> Unit
                PollError.SLOW_DOWN -> interval += 5000
                PollError.DENIED -> return DeviceAuthPhase.DENIED
                PollError.EXPIRED -> return DeviceAuthPhase.EXPIRED
                PollError.INVALID -> return DeviceAuthPhase.INVALID_CODE
                PollError.OTHER -> return DeviceAuthPhase.REJECTED
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: UnexpectedDeviceScopes) {
        return DeviceAuthPhase.SCOPE_MISMATCH
    } catch (_: IOException) {
        return DeviceAuthPhase.NETWORK_ERROR
    } catch (error: InvalidDeviceResponse) {
        onResponseIssue(error.issue)
        return DeviceAuthPhase.INVALID_RESPONSE
    } catch (_: Exception) {
        // Never forward provider bodies or exception text to the UI/logs.
        onResponseIssue(DeviceResponseIssue.UNEXPECTED)
        return DeviceAuthPhase.INVALID_RESPONSE
    } finally {
        transport.close()
    }
}
