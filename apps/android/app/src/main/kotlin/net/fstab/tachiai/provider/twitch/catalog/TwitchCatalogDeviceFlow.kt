package net.fstab.tachiai.provider.twitch.catalog

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import net.fstab.tachiai.provider.twitch.*

internal sealed interface TwitchCatalogAuthorizationResult {
    class Approved(val credentials: TwitchCatalogCredentials, val validation: TwitchCatalogValidation,
        val deadlineMs: Long, val validatedAtMs: Long? = null) : TwitchCatalogAuthorizationResult {
        override fun toString() = "TwitchCatalogAuthorizationResult.Approved(redacted)"
    }
    data class Failed(val phase: DeviceAuthPhase, val failure: TwitchCatalogAuthFailure? = null) : TwitchCatalogAuthorizationResult
}

private enum class CatalogPollError { PENDING, SLOW_DOWN, DENIED, EXPIRED, INVALID, OTHER }
private fun catalogPollCode(value: Any?) = when (value) {
    "authorization_pending" -> CatalogPollError.PENDING
    "slow_down" -> CatalogPollError.SLOW_DOWN
    "access_denied" -> CatalogPollError.DENIED
    "expired_token" -> CatalogPollError.EXPIRED
    "invalid device code", "invalid_device_code" -> CatalogPollError.INVALID
    else -> null
}
private fun catalogPollError(response: DeviceAuthResponse): CatalogPollError {
    val error = catalogPollCode(response.fields["error"])
    val message = catalogPollCode(response.fields["message"])
    if (error != null && message != null && error != message)
        throw TwitchCatalogAuthException(TwitchCatalogAuthFailure.INVALID_RESPONSE)
    if (response.fields.containsKey("error") && error == null) return CatalogPollError.OTHER
    return error ?: message ?: CatalogPollError.OTHER
}

private val catalogPollWait: suspend (Long) -> Unit = { delay(it) }

// Worker-only result: only phase and allowlisted activation instructions may enter UI.
// The transport belongs to this attempt and is always closed, including cancellation.
internal suspend fun requestTwitchCatalogAuthorization(
    transport: TwitchCatalogTransport,
    foreground: DeviceAuthorizationForeground = DeviceAuthorizationForeground(),
    clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    waitMs: suspend (Long) -> Unit = catalogPollWait,
    onPhase: suspend (DeviceAuthPhase) -> Unit = {},
    onActivation: suspend (DeviceActivation) -> Unit = {},
    onResponseShape: suspend (TwitchCatalogAuthResponseShape) -> Unit = {},
): TwitchCatalogAuthorizationResult {
    var lastPhase: DeviceAuthPhase? = null
    suspend fun publish(phase: DeviceAuthPhase) {
        if (lastPhase != phase) { onPhase(phase); lastPhase = phase }
    }
    suspend fun failed(phase: DeviceAuthPhase, failure: TwitchCatalogAuthFailure? = null): TwitchCatalogAuthorizationResult.Failed {
        publish(phase)
        return TwitchCatalogAuthorizationResult.Failed(phase, failure)
    }
    suspend fun ready(deadline: Long, phase: DeviceAuthPhase): Boolean {
        currentCoroutineContext().ensureActive()
        if (!foreground.isForeground) publish(DeviceAuthPhase.PAUSED)
        if (!foreground.awaitForeground(deadline, clockMs)) return false
        currentCoroutineContext().ensureActive()
        publish(phase)
        return true
    }
    fun interrupted(revision: Long, error: IOException) =
        error is DeviceRequestPaused || !foreground.isForeground || foreground.pauseRevision != revision
    try {
        if (!ready(Long.MAX_VALUE, DeviceAuthPhase.REQUESTING)) return failed(DeviceAuthPhase.EXPIRED)
        val requestedAt = clockMs()
        val device = transport.device()
        currentCoroutineContext().ensureActive()
        if (device.status != 200) return failed(DeviceAuthPhase.REJECTED, TwitchCatalogAuthFailure.REJECTED)
        val challenge = parseDeviceChallenge(device)
        val challengeDeadline = Math.addExact(requestedAt, challenge.expiresMs)
        if (clockMs() >= challengeDeadline) return failed(DeviceAuthPhase.EXPIRED)
        onActivation(challenge.activation)
        var interval = challenge.intervalMs
        while (true) {
            val remaining = challengeDeadline - clockMs()
            if (remaining <= 0) return failed(DeviceAuthPhase.EXPIRED)
            publish(DeviceAuthPhase.WAITING)
            waitMs(minOf(interval, remaining))
            if (!ready(challengeDeadline, DeviceAuthPhase.WAITING)) return failed(DeviceAuthPhase.EXPIRED)
            val pollRevision = foreground.pauseRevision
            val pollStarted = clockMs()
            val response = try { transport.poll(challenge.deviceCode) }
            catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                if (!interrupted(pollRevision, error)) throw error
                publish(DeviceAuthPhase.PAUSED)
                continue
            }
            currentCoroutineContext().ensureActive()
            if (clockMs() >= challengeDeadline) return failed(DeviceAuthPhase.EXPIRED)
            if (response.status == 200) {
                onResponseShape(twitchCatalogAuthResponseShape(DeviceAuthEndpoint.TOKEN, response.fields))
                val token = parseTwitchCatalogToken(response)
                val tokenDeadline = token.expiresInMs?.let { Math.addExact(pollStarted, it) }
                val acceptanceDeadline = tokenDeadline ?: Math.addExact(pollStarted, TWITCH_CATALOG_UNKNOWN_GRANT_VALIDATION_MS)
                // A successful poll consumes its device code. Background validation
                // retries use only the worker-local access token, never that code.
                while (true) {
                    if (!ready(acceptanceDeadline, DeviceAuthPhase.VALIDATING)) return failed(DeviceAuthPhase.EXPIRED)
                    val validationRevision = foreground.pauseRevision
                    val validationStarted = clockMs()
                    if (validationStarted >= acceptanceDeadline) return failed(DeviceAuthPhase.EXPIRED)
                    val validated = try { transport.validate(token.accessToken) }
                    catch (error: IOException) {
                        currentCoroutineContext().ensureActive()
                        if (!interrupted(validationRevision, error)) throw error
                        continue
                    }
                    currentCoroutineContext().ensureActive()
                    if (validated.status == 200)
                        onResponseShape(twitchCatalogAuthResponseShape(DeviceAuthEndpoint.VALIDATE, validated.fields))
                    val validation = parseTwitchCatalogValidation(validated)
                    if (clockMs() >= acceptanceDeadline) return failed(DeviceAuthPhase.EXPIRED)
                    val validationDeadline = Math.addExact(validationStarted, validation.expiresInMs)
                    val deadline = tokenDeadline?.let { minOf(it, validationDeadline) } ?: validationDeadline
                    if (clockMs() >= deadline) return failed(DeviceAuthPhase.EXPIRED)
                    if (!foreground.isForeground || foreground.pauseRevision != validationRevision) continue
                    val credentials = token.validatedCredentials(validation)
                    publish(DeviceAuthPhase.SUCCEEDED)
                    return TwitchCatalogAuthorizationResult.Approved(credentials, validation, deadline, validationStarted)
                }
            }
            if (response.status != 400) return failed(DeviceAuthPhase.REJECTED, TwitchCatalogAuthFailure.REJECTED)
            when (catalogPollError(response)) {
                CatalogPollError.PENDING -> Unit
                CatalogPollError.SLOW_DOWN -> interval = minOf(interval + 5000, challenge.expiresMs)
                CatalogPollError.DENIED -> return failed(DeviceAuthPhase.DENIED)
                CatalogPollError.EXPIRED -> return failed(DeviceAuthPhase.EXPIRED)
                CatalogPollError.INVALID -> return failed(DeviceAuthPhase.INVALID_CODE)
                CatalogPollError.OTHER -> return failed(DeviceAuthPhase.REJECTED, TwitchCatalogAuthFailure.REJECTED)
            }
        }
    } catch (error: CancellationException) { throw error }
    catch (error: TwitchCatalogAuthException) {
        val phase = when (error.failure) {
            TwitchCatalogAuthFailure.CLIENT_MISMATCH, TwitchCatalogAuthFailure.USER_MISMATCH -> DeviceAuthPhase.CLIENT_MISMATCH
            TwitchCatalogAuthFailure.SCOPE_MISMATCH -> DeviceAuthPhase.SCOPE_MISMATCH
            TwitchCatalogAuthFailure.EXPIRED -> DeviceAuthPhase.EXPIRED
            TwitchCatalogAuthFailure.REJECTED -> DeviceAuthPhase.REJECTED
            TwitchCatalogAuthFailure.NETWORK -> DeviceAuthPhase.NETWORK_ERROR
            TwitchCatalogAuthFailure.INVALID_RESPONSE -> DeviceAuthPhase.INVALID_RESPONSE
        }
        return failed(phase, error.failure)
    } catch (_: IOException) { return failed(DeviceAuthPhase.NETWORK_ERROR, TwitchCatalogAuthFailure.NETWORK) }
    catch (_: Exception) { return failed(DeviceAuthPhase.INVALID_RESPONSE, TwitchCatalogAuthFailure.INVALID_RESPONSE) }
    finally { transport.close() }
}
