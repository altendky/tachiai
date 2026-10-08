package net.fstab.tachiai.provider.twitch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class LocalRetentionExtensionOutcome {
    SAVED, NO_TOKEN, EXPIRED, WRONG_PROFILE, SUPERSEDED, VALIDATION_REJECTED, NETWORK_FAILED, STORAGE_FAILED,
}
internal data class LocalRetentionExtensionResult(val outcome: LocalRetentionExtensionOutcome, val validationHttp: Int = 0)

// Explicit user action only, not an OAuth refresh or renewal on ordinary use.
// Revalidate a still-available LOCAL record, then update its local policy cap.
internal suspend fun extendSavedTwitchLocalRetention(
    cache: TwitchSavedAuthorization,
    transport: TwitchDeviceTransport,
    foreground: DeviceAuthorizationForeground,
    clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
): LocalRetentionExtensionResult {
    try {
        currentCoroutineContext().ensureActive()
        if (cache.profile != TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL)
            return LocalRetentionExtensionResult(LocalRetentionExtensionOutcome.WRONG_PROFILE)
        val loaded = cache.read()
        val lease = loaded.lease ?: return LocalRetentionExtensionResult(when (loaded.state) {
            SavedAuthorizationState.MISSING -> LocalRetentionExtensionOutcome.NO_TOKEN
            SavedAuthorizationState.EXPIRED -> LocalRetentionExtensionOutcome.EXPIRED
            else -> LocalRetentionExtensionOutcome.STORAGE_FAILED
        })
        if (!foreground.isForeground || !cache.isCurrent(lease))
            return LocalRetentionExtensionResult(LocalRetentionExtensionOutcome.SUPERSEDED)
        val started = clockMs()
        val acceptanceMs = minOf(SMART_TV_LIFETIME_INSPECTION_MS, cache.remainingLocalMs(lease))
        if (acceptanceMs <= 0) return LocalRetentionExtensionResult(LocalRetentionExtensionOutcome.EXPIRED)
        val response = transport.validate(lease.record.token)
        currentCoroutineContext().ensureActive()
        if (validatedDeviceToken(response, cache.profile.clientId, allowZeroLifetime = true) != DeviceAuthPhase.SUCCEEDED)
            return LocalRetentionExtensionResult(LocalRetentionExtensionOutcome.VALIDATION_REJECTED, response.status)
        if (!foreground.isForeground || !cache.isCurrent(lease))
            return LocalRetentionExtensionResult(LocalRetentionExtensionOutcome.SUPERSEDED, response.status)
        val elapsed = clockMs() - started
        if (elapsed < 0 || elapsed >= acceptanceMs || cache.remainingLocalMs(lease) <= 0)
            return LocalRetentionExtensionResult(LocalRetentionExtensionOutcome.EXPIRED, response.status)
        val lifetime = if (deviceLifetimeShape(response.fields) == DeviceLifetimeShape.ZERO) SMART_TV_LOCAL_RETENTION_MS
            else minOf(SMART_TV_LOCAL_RETENTION_MS, (response.fields["expires_in"] as Number).toLong() * 1_000)
        val saved = cache.saveValidated(lease.record.token, Math.addExact(started, lifetime), lease.revision)
        return LocalRetentionExtensionResult(when (saved) {
            SavedAuthorizationState.SAVED -> LocalRetentionExtensionOutcome.SAVED
            SavedAuthorizationState.EXPIRED -> LocalRetentionExtensionOutcome.EXPIRED
            SavedAuthorizationState.SUPERSEDED -> LocalRetentionExtensionOutcome.SUPERSEDED
            else -> LocalRetentionExtensionOutcome.STORAGE_FAILED
        }, response.status)
    } catch (error: CancellationException) { throw error }
    catch (_: java.io.IOException) { return LocalRetentionExtensionResult(LocalRetentionExtensionOutcome.NETWORK_FAILED) }
    catch (_: InvalidDeviceResponse) { return LocalRetentionExtensionResult(LocalRetentionExtensionOutcome.VALIDATION_REJECTED) }
    catch (_: Exception) { return LocalRetentionExtensionResult(LocalRetentionExtensionOutcome.STORAGE_FAILED) }
    finally { transport.close() }
}
