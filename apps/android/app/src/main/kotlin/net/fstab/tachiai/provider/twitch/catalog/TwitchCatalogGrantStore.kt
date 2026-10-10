package net.fstab.tachiai.provider.twitch.catalog

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.defaultProviderInstanceId
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.twitch.SMART_TV_TWITCH_CLIENT_ID
import net.fstab.tachiai.provider.twitch.validTwitchClientId

internal enum class TwitchCatalogGrantState { READY, REFRESHING, RECONNECT, CLEARED }
internal enum class TwitchCatalogStoreFailure { STORAGE, INVALID_RECORD, CLIENT_MISMATCH }
internal class TwitchCatalogStoreException(val failure: TwitchCatalogStoreFailure) : Exception(failure.name)

internal class TwitchCatalogConnectionAttempt internal constructor(
    internal val generation: String,
    internal val revision: Long,
) {
    override fun toString() = "TwitchCatalogConnectionAttempt(redacted)"
}

// Secrets remain in worker-only provider objects. Neither a persisted marker nor
// a UI summary contains the old pair once a refresh has been accepted.
internal class TwitchCatalogStoredGrant internal constructor(
    val generation: String,
    val state: TwitchCatalogGrantState,
    val revision: Long,
    val userId: String? = null,
    val savedAtMs: Long? = null,
    val expiresAtMs: Long? = null,
    val credentials: TwitchCatalogCredentials? = null,
    val providerExpiresAtMs: Long? = expiresAtMs,
    val localRetentionUntilMs: Long? = null,
) {
    override fun toString() = "TwitchCatalogStoredGrant($state, redacted)"
}

// Worker-only ownership of a marker written by this operation. It contains no
// credentials/account values and never adopts an arbitrary replacement read.
internal class TwitchCatalogAbandonedGrantException(val marker: TwitchCatalogStoredGrant) :
    TwitchCatalogAuthException(TwitchCatalogAuthFailure.EXPIRED) {
    init { require(marker.state == TwitchCatalogGrantState.RECONNECT && marker.credentials == null && marker.userId == null) }
}

internal class TwitchCatalogReplacement(
    val credentials: TwitchCatalogCredentials,
    val validation: TwitchCatalogValidation,
    val remainingMs: Long,
    val providerExpiresAtMs: Long? = null,
    val localRetentionUntilMs: Long? = null,
    val savedAtMs: Long? = null,
) {
    init {
        require(remainingMs in 1..minOf(credentials.expiresInMs, validation.expiresInMs ?: Long.MAX_VALUE))
        require(validation.expiresInMs != null || localRetentionUntilMs != null)
        require(savedAtMs == null || savedAtMs >= 0)
        require(providerExpiresAtMs == null || providerExpiresAtMs >= 0)
        require(localRetentionUntilMs == null || localRetentionUntilMs >= 0)
    }
    override fun toString() = "TwitchCatalogReplacement(redacted)"
}

// The Android owner supplies a separate encrypted/noBackup binding and lock file
// for this instance. Every read-modify-write, including the HTTP refresh, holds
// the same process and OS lock. This is deliberately a blocking worker API.
internal class TwitchCatalogGrantStore(
    private val storage: PrivateSecretStore,
    val instanceId: String,
    private val lockFile: File? = null,
    private val wallMs: () -> Long = System::currentTimeMillis,
) {
    init {
        require(validProviderInstanceId(instanceId))
        require(instanceId != defaultProviderInstanceId(PrototypeService.ABEMA))
    }

    private class Shared {
        val lock = Any()
        val signalLock = Any()
        val revision = AtomicLong()
        val blocked = AtomicBoolean()
    }
    companion object {
        private val sharedBindings = ConcurrentHashMap<String, Shared>()
    }
    private val shared = sharedBindings.computeIfAbsent(instanceId) { Shared() }
    private val pendingClearFile = lockFile?.absoluteFile?.let { File(it.parentFile, "${it.name}.forget-pending") }

    fun revision(): Long = shared.revision.get()
    fun isRevisionCurrent(revision: Long): Boolean = synchronized(shared.signalLock) {
        revision == shared.revision.get() && !shared.blocked.get()
    }
    private fun allowRevision(revision: Long) = synchronized(shared.signalLock) {
        if (shared.revision.get() == revision) shared.blocked.set(false)
    }

    // Called synchronously when Forget/replacement is accepted, before waiting
    // for a worker or an outstanding refresh. Failed disk writes stay blocked.
    fun invalidate(): Long = synchronized(shared.signalLock) {
        shared.blocked.set(true)
        shared.revision.incrementAndGet()
    }

    private fun <T> locked(action: () -> T): T = synchronized(shared.lock) {
        val file = lockFile
        if (file == null) action() else {
            val handle = try { RandomAccessFile(file, "rw") }
            catch (_: java.io.IOException) { throw TwitchCatalogStoreException(TwitchCatalogStoreFailure.STORAGE) }
            handle.use {
                val fileLock = try { handle.channel.lock() }
                catch (_: java.io.IOException) { throw TwitchCatalogStoreException(TwitchCatalogStoreFailure.STORAGE) }
                fileLock.use { action() }
            }
        }
    }

    fun read(): TwitchCatalogStoredGrant? = locked { readUnlocked() }

    private fun checkPendingClear() {
        if (pendingClearFile?.exists() == true) throw TwitchCatalogStoreException(TwitchCatalogStoreFailure.STORAGE)
    }

    private fun readUnlocked(ignorePendingClear: Boolean = false): TwitchCatalogStoredGrant? {
        if (!ignorePendingClear) checkPendingClear()
        val bytes = try { storage.read() }
        catch (_: Exception) { throw TwitchCatalogStoreException(TwitchCatalogStoreFailure.STORAGE) }
        if (bytes == null) return null
        try {
            check(bytes.size in 1..PRIVATE_SECRET_LIMIT)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val version = input.readInt().also { check(it in 1..2) }
                check(input.readUTF() == instanceId)
                val client = input.readUTF().also { check(validTwitchClientId(it)) }
                // A differently bound grant needs explicit reconnection. Never
                // interpret its credentials or implicitly rewrite its record.
                if (client != SMART_TV_TWITCH_CLIENT_ID)
                    throw TwitchCatalogStoreException(TwitchCatalogStoreFailure.CLIENT_MISMATCH)
                check(input.readUTF() == TWITCH_CATALOG_SCOPE)
                val generation = input.readUTF().also { check(validProviderInstanceId(it)) }
                val state = TwitchCatalogGrantState.valueOf(input.readUTF())
                val grant = if (state == TwitchCatalogGrantState.READY) {
                    val user = input.readUTF().also { check(validCatalogUserId(it)) }
                    val saved = input.readLong()
                    val provider = if (version == 1 || input.readBoolean()) input.readLong() else null
                    val local = if (version == 2 && input.readBoolean()) input.readLong() else null
                    val expires = listOfNotNull(provider, local).minOrNull() ?: error("missing bound")
                    check(saved >= 0 && expires > saved)
                    check(provider == null || provider > saved)
                    check(local == null || local > saved && local - saved <= TWITCH_CATALOG_LOCAL_RETENTION_MS)
                    val duration = Math.subtractExact(expires, saved)
                    val credentials = TwitchCatalogCredentials(input.readUTF(), input.readUTF(), duration)
                    TwitchCatalogStoredGrant(generation, state, revision(), user, saved, expires, credentials, provider, local)
                } else TwitchCatalogStoredGrant(generation, state, revision())
                check(input.read() == -1)
                grant
            }
        } catch (error: TwitchCatalogStoreException) {
            throw error
        } catch (_: Exception) {
            throw TwitchCatalogStoreException(TwitchCatalogStoreFailure.INVALID_RECORD)
        } finally { bytes.fill(0) }
    }

    private fun writeUnlocked(grant: TwitchCatalogStoredGrant) {
        val bytes = try {
            ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeInt(2)
                    output.writeUTF(instanceId)
                    output.writeUTF(SMART_TV_TWITCH_CLIENT_ID)
                    output.writeUTF(TWITCH_CATALOG_SCOPE)
                    output.writeUTF(grant.generation)
                    output.writeUTF(grant.state.name)
                    if (grant.state == TwitchCatalogGrantState.READY) {
                        val credentials = checkNotNull(grant.credentials)
                        output.writeUTF(checkNotNull(grant.userId))
                        output.writeLong(checkNotNull(grant.savedAtMs))
                        output.writeBoolean(grant.providerExpiresAtMs != null)
                        grant.providerExpiresAtMs?.let(output::writeLong)
                        output.writeBoolean(grant.localRetentionUntilMs != null)
                        grant.localRetentionUntilMs?.let(output::writeLong)
                        output.writeUTF(credentials.accessToken)
                        output.writeUTF(credentials.refreshToken)
                    }
                }
            }.toByteArray().also { check(it.size <= PRIVATE_SECRET_LIMIT) }
        } catch (_: Exception) { throw TwitchCatalogStoreException(TwitchCatalogStoreFailure.INVALID_RECORD) }
        try { storage.write(bytes) }
        catch (_: Exception) { throw TwitchCatalogStoreException(TwitchCatalogStoreFailure.STORAGE) }
        finally { bytes.fill(0) }
    }

    fun beginConnection(): TwitchCatalogConnectionAttempt {
        // A pending failed clear can only be retried, never replaced by Connect.
        checkPendingClear()
        val revision = invalidate()
        return locked {
            checkPendingClear()
            val generation = UUID.randomUUID().toString()
            writeUnlocked(TwitchCatalogStoredGrant(generation, TwitchCatalogGrantState.RECONNECT, revision))
            allowRevision(revision)
            TwitchCatalogConnectionAttempt(generation, revision)
        }
    }

    private fun syncClearDirectory(file: File) {
        FileChannel.open(checkNotNull(file.parentFile).toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    fun forget(expectedRevision: Long? = null, expectedGeneration: String? = null): Boolean {
        var revision = expectedRevision ?: invalidate()
        return locked {
            val retryPending = pendingClearFile?.exists() == true
            if (retryPending) revision = invalidate()
            else if (shared.revision.get() != revision) return@locked false
            // A known last-observed identity also protects against another
            // process's newer account/rotation, whose local revision is absent.
            if (!retryPending && expectedGeneration != null) {
                val current = try { readUnlocked(ignorePendingClear = true) }
                catch (error: TwitchCatalogStoreException) {
                    if (error.failure in setOf(TwitchCatalogStoreFailure.INVALID_RECORD,
                            TwitchCatalogStoreFailure.CLIENT_MISMATCH)) null else throw error
                }
                if (current != null && current.generation != expectedGeneration &&
                    current.state != TwitchCatalogGrantState.CLEARED) return@locked false
            }
            val marker = pendingClearFile
            if (marker != null) {
                try {
                    RandomAccessFile(marker, "rw").use { handle ->
                        handle.setLength(0); handle.writeInt(1); handle.fd.sync()
                    }
                    syncClearDirectory(marker)
                } catch (_: Exception) { throw TwitchCatalogStoreException(TwitchCatalogStoreFailure.STORAGE) }
            }
            // Explicit clearing may replace a corrupt record; implicit reads,
            // validation and refresh never overwrite corruption.
            writeUnlocked(TwitchCatalogStoredGrant(UUID.randomUUID().toString(), TwitchCatalogGrantState.CLEARED, revision))
            if (marker != null) {
                try {
                    check(marker.delete()); syncClearDirectory(marker)
                } catch (_: Exception) { throw TwitchCatalogStoreException(TwitchCatalogStoreFailure.STORAGE) }
            }
            allowRevision(revision)
            true
        }
    }

    fun commitConnection(
        attempt: TwitchCatalogConnectionAttempt,
        replacement: TwitchCatalogReplacement,
        canCommit: () -> Boolean = { true },
    ): TwitchCatalogStoredGrant? = locked {
        val previous = readUnlocked() ?: return@locked null
        if (previous.generation != attempt.generation || previous.state != TwitchCatalogGrantState.RECONNECT ||
            !isRevisionCurrent(attempt.revision) || !canCommit()) return@locked null
        val ready = ready(attempt.generation, attempt.revision, replacement)
        writeUnlocked(ready)
        if (wallMs() < checkNotNull(ready.savedAtMs) || wallMs() >= checkNotNull(ready.expiresAtMs)) {
            expiredAfterWrite(ready, attempt.revision)
        }
        if (!isRevisionCurrent(attempt.revision) || !canCommit()) {
            abandonUnlocked(ready, attempt.revision)
            return@locked null
        }
        ready
    }

    private fun ready(generation: String, revision: Long, replacement: TwitchCatalogReplacement): TwitchCatalogStoredGrant {
        val now = wallMs()
        val saved = replacement.savedAtMs ?: now
        check(saved >= 0)
        val local = replacement.localRetentionUntilMs
        val provider = replacement.providerExpiresAtMs ?: if (local == null) Math.addExact(saved, replacement.remainingMs) else null
        val expires = listOfNotNull(provider, local).minOrNull() ?: error("missing bound")
        if (now < saved || now >= expires) throw TwitchCatalogAuthException(TwitchCatalogAuthFailure.EXPIRED)
        check(local == null || local > saved && local - saved <= TWITCH_CATALOG_LOCAL_RETENTION_MS)
        return TwitchCatalogStoredGrant(generation, TwitchCatalogGrantState.READY, revision,
            replacement.validation.userId, saved, expires,
            TwitchCatalogCredentials(replacement.credentials.accessToken, replacement.credentials.refreshToken,
                Math.subtractExact(expires, saved)), provider, local)
    }

    private fun abandonUnlocked(grant: TwitchCatalogStoredGrant, revision: Long): TwitchCatalogStoredGrant? {
        try {
            val current = readUnlocked()
            if (current?.generation != grant.generation || current.state != TwitchCatalogGrantState.READY) return null
            val marker = TwitchCatalogStoredGrant(grant.generation, TwitchCatalogGrantState.RECONNECT, revision)
            writeUnlocked(marker)
            return marker
        } catch (error: TwitchCatalogStoreException) {
            invalidate()
            throw error
        }
    }

    private fun expiredAfterWrite(grant: TwitchCatalogStoredGrant, revision: Long): Nothing {
        val marker = abandonUnlocked(grant, revision)
        if (marker != null) throw TwitchCatalogAbandonedGrantException(marker)
        throw TwitchCatalogAuthException(TwitchCatalogAuthFailure.EXPIRED)
    }

    fun requireReconnect(grant: TwitchCatalogStoredGrant): TwitchCatalogStoredGrant? = locked {
        val previous = readUnlocked() ?: return@locked null
        if (previous.generation != grant.generation || !isRevisionCurrent(grant.revision)) return@locked null
        invalidate()
        val revision = revision()
        val marker = TwitchCatalogStoredGrant(UUID.randomUUID().toString(), TwitchCatalogGrantState.RECONNECT, revision)
        writeUnlocked(marker)
        allowRevision(revision)
        marker
    }

    fun isStoredCurrent(grant: TwitchCatalogStoredGrant): Boolean = locked {
        if (!isRevisionCurrent(grant.revision)) return@locked false
        val current = readUnlocked()
        val now = wallMs()
        pairCurrentUnlocked(grant, current, now) && now < checkNotNull(current?.expiresAtMs)
    }

    // Refresh may use the current pair after its provider bound has expired,
    // but never a rotated pair, a rolled-back clock or an expired local cap.
    fun isStoredPairCurrent(grant: TwitchCatalogStoredGrant): Boolean = locked {
        pairCurrentUnlocked(grant, readUnlocked())
    }

    private fun pairCurrentUnlocked(grant: TwitchCatalogStoredGrant, current: TwitchCatalogStoredGrant?, now: Long = wallMs()): Boolean =
        isRevisionCurrent(grant.revision) && current?.state == TwitchCatalogGrantState.READY &&
            current.generation == grant.generation && now >= checkNotNull(current.savedAtMs) &&
            (current.localRetentionUntilMs == null || now < current.localRetentionUntilMs)

    fun localRetentionCurrent(grant: TwitchCatalogStoredGrant): Boolean {
        val cap = grant.localRetentionUntilMs ?: return true
        val now = wallMs()
        return now >= checkNotNull(grant.savedAtMs) && now < cap
    }

    // Same-token validation may only reduce a durable provider bound. Keep the
    // pair generation so concurrent consumers remain valid until the tightened
    // deadline, which every worker admission rereads from durable storage.
    fun tightenProviderExpiry(grant: TwitchCatalogStoredGrant, expiresAtMs: Long,
        canCommit: () -> Boolean = { true }): TwitchCatalogStoredGrant? = locked {
        val current = readUnlocked() ?: return@locked null
        if (current.state != TwitchCatalogGrantState.READY || current.generation != grant.generation ||
            !isRevisionCurrent(grant.revision) || !canCommit()) return@locked null
        if (!localRetentionCurrent(current)) throw TwitchCatalogAuthException(TwitchCatalogAuthFailure.EXPIRED)
        check(current.localRetentionUntilMs != null)
        val provider = minOf(current.providerExpiresAtMs ?: Long.MAX_VALUE, expiresAtMs)
        if (wallMs() >= provider) throw TwitchCatalogAuthException(TwitchCatalogAuthFailure.EXPIRED)
        if (provider == current.providerExpiresAtMs) return@locked current
        val saved = checkNotNull(current.savedAtMs)
        val expires = minOf(provider, current.localRetentionUntilMs)
        val credentials = checkNotNull(current.credentials)
        val tightened = TwitchCatalogStoredGrant(current.generation, current.state, current.revision,
            current.userId, saved, expires,
            TwitchCatalogCredentials(credentials.accessToken, credentials.refreshToken, expires - saved),
            provider, current.localRetentionUntilMs)
        writeUnlocked(tightened)
        if (!localRetentionCurrent(tightened) || wallMs() >= expires) {
            expiredAfterWrite(tightened, grant.revision)
        }
        if (!isRevisionCurrent(grant.revision) || !canCommit() || !localRetentionCurrent(tightened)) {
            abandonUnlocked(tightened, grant.revision)
            return@locked null
        }
        tightened
    }

    // The callback runs only once, after the old pair has been durably removed.
    // It must validate the new access token before returning its atomic pair.
    fun refresh(
        grant: TwitchCatalogStoredGrant,
        canCommit: () -> Boolean = { true },
        exchange: (TwitchCatalogCredentials) -> TwitchCatalogReplacement,
    ): TwitchCatalogStoredGrant? = locked {
        val current = readUnlocked() ?: return@locked null
        if (current.generation != grant.generation || current.state != TwitchCatalogGrantState.READY ||
            !isRevisionCurrent(grant.revision) || !canCommit()) return@locked null
        if (!localRetentionCurrent(current)) throw TwitchCatalogAuthException(TwitchCatalogAuthFailure.EXPIRED)
        val credentials = checkNotNull(current.credentials)
        writeUnlocked(TwitchCatalogStoredGrant(current.generation, TwitchCatalogGrantState.REFRESHING, grant.revision))
        if (!isRevisionCurrent(grant.revision) || !canCommit()) return@locked null
        val replacement = exchange(credentials)
        check(replacement.validation.userId == current.userId)
        check(replacement.localRetentionUntilMs == current.localRetentionUntilMs)
        if (current.localRetentionUntilMs != null) check(replacement.savedAtMs == current.savedAtMs)
        if (!isRevisionCurrent(grant.revision) || !canCommit()) return@locked null
        val ready = ready(UUID.randomUUID().toString(), grant.revision, replacement)
        writeUnlocked(ready)
        if (wallMs() < checkNotNull(ready.savedAtMs) || wallMs() >= checkNotNull(ready.expiresAtMs)) {
            expiredAfterWrite(ready, grant.revision)
        }
        if (!isRevisionCurrent(grant.revision) || !canCommit()) {
            abandonUnlocked(ready, grant.revision)
            return@locked null
        }
        ready
    }

    override fun toString() = "TwitchCatalogGrantStore(redacted)"
}
