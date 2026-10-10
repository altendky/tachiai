package net.fstab.tachiai.provider.twitch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateSecretStore

internal enum class SavedAuthorizationState { MISSING, AVAILABLE, EXPIRED, UNREADABLE, SAVED, SAVE_FAILED, FORGOTTEN, SUPERSEDED }
internal class SavedTwitchToken(val token: String, val savedAtMs: Long, val expiresAtMs: Long) {
    override fun toString() = "SavedTwitchToken(redacted)"
}
internal class SavedAuthorizationLease(val record: SavedTwitchToken, val revision: Long, internal val owner: Any) {
    override fun toString() = "SavedAuthorizationLease(redacted)"
}
internal class SavedAuthorizationRead(val state: SavedAuthorizationState, val lease: SavedAuthorizationLease? = null) {
    override fun toString() = "SavedAuthorizationRead(${state.name}, redacted)"
}

internal fun encodeSavedTwitchToken(record: SavedTwitchToken, profile: TwitchAuthorizationProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV): ByteArray {
    require(Regex("[A-Za-z0-9._~+/=-]{1,4096}").matches(record.token))
    require(record.savedAtMs > 0 && record.expiresAtMs > record.savedAtMs)
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use {
        val localRetention = profile == TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL
        require(!localRetention || record.expiresAtMs - record.savedAtMs <= SMART_TV_LOCAL_RETENTION_MS)
        it.writeInt(if (localRetention) 2 else 1)
        if (localRetention) it.writeUTF(profile.name)
        it.writeUTF(profile.clientId)
        it.writeLong(record.savedAtMs)
        it.writeLong(record.expiresAtMs)
        it.writeUTF(record.token)
    }
    return bytes.toByteArray()
}

internal fun decodeSavedTwitchToken(bytes: ByteArray, profile: TwitchAuthorizationProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV): SavedTwitchToken = DataInputStream(ByteArrayInputStream(bytes)).use {
    val localRetention = profile == TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL
    require(it.readInt() == if (localRetention) 2 else 1)
    if (localRetention) require(it.readUTF() == profile.name)
    require(it.readUTF() == profile.clientId)
    val saved = it.readLong()
    val expiry = it.readLong()
    val token = it.readUTF()
    require(it.available() == 0 && saved > 0 && expiry > saved &&
        expiry - saved <= Int.MAX_VALUE.toLong() * 1000 && Regex("[A-Za-z0-9._~+/=-]{1,4096}").matches(token))
    require(!localRetention || expiry - saved <= SMART_TV_LOCAL_RETENTION_MS)
    SavedTwitchToken(token, saved, expiry)
}

internal class TwitchSavedAuthorization(
    private val storage: PrivateSecretStore,
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    val profile: TwitchAuthorizationProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV,
) {
    private val lock = Any()
    private val leaseOwner = Any()
    private var revision = 0L
    fun revision(): Long = synchronized(lock) { revision }
    fun isCurrent(lease: SavedAuthorizationLease): Boolean = synchronized(lock) {
        lease.owner === leaseOwner && revision == lease.revision
    }
    // Worker-only: unlike isCurrent, this reads/decrypts the stored slot. A
    // separate process can replace or Forget it without changing our revision.
    // This is a point-in-time identity check, not atomic cross-process revocation;
    // callers must still enforce lifetime/foreground and recheck before I/O.
    fun isStoredCurrent(lease: SavedAuthorizationLease): Boolean = synchronized(lock) {
        if (!isCurrent(lease)) return false
        try {
            val bytes = storage.read() ?: return false
            try {
                if (bytes.isEmpty() || bytes.size > PRIVATE_SECRET_LIMIT) return false
                val stored = decodeSavedTwitchToken(bytes, profile)
                stored.token == lease.record.token && stored.savedAtMs == lease.record.savedAtMs &&
                    stored.expiresAtMs == lease.record.expiresAtMs
            } finally { bytes.fill(0) }
        } catch (_: Exception) { false }
    }
    // Only the new local-retention case uses this budget; never extend on reuse.
    fun remainingLocalMs(lease: SavedAuthorizationLease): Long = synchronized(lock) {
        if (profile != TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL || !isCurrent(lease)) return 0L
        val now = wallMs()
        if (now < lease.record.savedAtMs) return 0L
        maxOf(0L, lease.record.expiresAtMs - now - 10_000L)
    }

    fun read(): SavedAuthorizationRead = synchronized(lock) {
        try {
            val bytes = storage.read() ?: return SavedAuthorizationRead(SavedAuthorizationState.MISSING)
            if (bytes.isEmpty()) return SavedAuthorizationRead(SavedAuthorizationState.MISSING)
            val record = try { decodeSavedTwitchToken(bytes, profile) } finally { bytes.fill(0) }
            val now = wallMs()
            if (now < record.savedAtMs || now >= record.expiresAtMs - 10_000) {
                return SavedAuthorizationRead(SavedAuthorizationState.EXPIRED)
            }
            SavedAuthorizationRead(SavedAuthorizationState.AVAILABLE, SavedAuthorizationLease(record, revision, leaseOwner))
        } catch (_: Exception) { SavedAuthorizationRead(SavedAuthorizationState.UNREADABLE) }
    }

    suspend fun saveValidated(token: String, deadlineMs: Long, attemptRevision: Long): SavedAuthorizationState {
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        return synchronized(lock) {
            coroutine.ensureActive()
            if (revision != attemptRevision) return SavedAuthorizationState.SUPERSEDED
            try {
                val remaining = deadlineMs - monotonicMs()
                if (remaining <= 10_000 || remaining > Int.MAX_VALUE.toLong() * 1000) return SavedAuthorizationState.EXPIRED
                if (profile == TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL && remaining > SMART_TV_LOCAL_RETENTION_MS)
                    return SavedAuthorizationState.EXPIRED
                val now = wallMs()
                val bytes = encodeSavedTwitchToken(SavedTwitchToken(token, now, Math.addExact(now, remaining)), profile)
                try { storage.write(bytes) } finally { bytes.fill(0) }
                revision++
                SavedAuthorizationState.SAVED
            } catch (_: Exception) { SavedAuthorizationState.SAVE_FAILED }
        }
    }

    // Explicit local Forget only. Overwrite the slot with encrypted empty data;
    // do not delete earlier experiment files, cookies, account or provider grant.
    fun forget(): SavedAuthorizationState = synchronized(lock) {
        revision++ // Even a failed clear invalidates pending save/access leases.
        try { storage.write(byteArrayOf()); SavedAuthorizationState.FORGOTTEN }
        catch (_: Exception) { SavedAuthorizationState.SAVE_FAILED }
    }
}

internal enum class SavedTwitchUseOutcome { USED, NO_TOKEN, EXPIRED, STORAGE_FAILED, VALIDATION_REJECTED, NETWORK_FAILED, SUPERSEDED }
internal data class SavedTwitchUseResult(val outcome: SavedTwitchUseOutcome, val validationHttp: Int = 0)

// No maintained active session: every explicit saved-token action validates
// afresh, then performs one bounded request. No hourly background worker/refresh.
internal suspend fun useSavedTwitchAuthorization(
    cache: TwitchSavedAuthorization,
    transport: TwitchDeviceTransport,
    foreground: DeviceAuthorizationForeground = DeviceAuthorizationForeground(),
    clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    onUse: suspend (String, Long, SavedAuthorizationLease) -> Unit,
): SavedTwitchUseResult {
    try {
        currentCoroutineContext().ensureActive()
        val loaded = cache.read()
        val lease = loaded.lease ?: return SavedTwitchUseResult(when (loaded.state) {
            SavedAuthorizationState.MISSING -> SavedTwitchUseOutcome.NO_TOKEN
            SavedAuthorizationState.EXPIRED -> SavedTwitchUseOutcome.EXPIRED
            else -> SavedTwitchUseOutcome.STORAGE_FAILED
        })
        if (!foreground.isForeground || !cache.isCurrent(lease)) return SavedTwitchUseResult(SavedTwitchUseOutcome.SUPERSEDED)
        val started = clockMs()
        val localRetention = cache.profile == TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL
        val localDeadline = if (localRetention) Math.addExact(started,
            minOf(SMART_TV_LIFETIME_INSPECTION_MS, cache.remainingLocalMs(lease))) else Long.MAX_VALUE
        if (localRetention && started >= localDeadline) return SavedTwitchUseResult(SavedTwitchUseOutcome.EXPIRED)
        val validation = transport.validate(lease.record.token)
        currentCoroutineContext().ensureActive()
        if (validatedDeviceToken(validation, cache.profile.clientId, allowZeroLifetime = localRetention) != DeviceAuthPhase.SUCCEEDED)
            return SavedTwitchUseResult(SavedTwitchUseOutcome.VALIDATION_REJECTED, validation.status)
        val lifetime = if (localRetention && deviceLifetimeShape(validation.fields) == DeviceLifetimeShape.ZERO)
            SMART_TV_LIFETIME_INSPECTION_MS else (validation.fields["expires_in"] as Number).toLong() * 1000
        val deadline = if (localRetention) minOf(localDeadline, Math.addExact(started, lifetime),
            Math.addExact(clockMs(), cache.remainingLocalMs(lease))) else Math.addExact(started, lifetime)
        if (clockMs() >= deadline) return SavedTwitchUseResult(SavedTwitchUseOutcome.EXPIRED, validation.status)
        if (!foreground.isForeground || !cache.isCurrent(lease)) return SavedTwitchUseResult(SavedTwitchUseOutcome.SUPERSEDED, validation.status)
        currentCoroutineContext().ensureActive()
        onUse(lease.record.token, deadline, lease)
        return SavedTwitchUseResult(SavedTwitchUseOutcome.USED, validation.status)
    } catch (error: kotlinx.coroutines.CancellationException) { throw error }
    catch (_: java.io.IOException) { return SavedTwitchUseResult(SavedTwitchUseOutcome.NETWORK_FAILED) }
    catch (_: InvalidDeviceResponse) { return SavedTwitchUseResult(SavedTwitchUseOutcome.VALIDATION_REJECTED) }
    catch (_: Exception) { return SavedTwitchUseResult(SavedTwitchUseOutcome.STORAGE_FAILED) }
    finally { transport.close() }
}
