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
import net.fstab.tachiai.provider.twitch.TACHIAI_TWITCH_CLIENT_ID

internal enum class TwitchCatalogGrantState { READY, REFRESHING, RECONNECT, CLEARED }
internal enum class TwitchCatalogStoreFailure { STORAGE, INVALID_RECORD }
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
) {
    override fun toString() = "TwitchCatalogStoredGrant($state, redacted)"
}

internal class TwitchCatalogReplacement(
    val credentials: TwitchCatalogCredentials,
    val validation: TwitchCatalogValidation,
    val remainingMs: Long,
) {
    init { require(remainingMs in 1..minOf(credentials.expiresInMs, validation.expiresInMs)) }
    override fun toString() = "TwitchCatalogReplacement(redacted)"
}

// The Android owner supplies a separate encrypted/noBackup binding and lock file
// for this instance. Every read-modify-write, including the HTTP refresh, holds
// the same process and OS lock. This is deliberately a blocking worker API.
internal class TwitchCatalogGrantStore(
    private val storage: PrivateSecretStore,
    private val instanceId: String,
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
                check(input.readInt() == 1)
                check(input.readUTF() == instanceId)
                check(input.readUTF() == TACHIAI_TWITCH_CLIENT_ID)
                check(input.readUTF() == TWITCH_CATALOG_SCOPE)
                val generation = input.readUTF().also { check(validProviderInstanceId(it)) }
                val state = TwitchCatalogGrantState.valueOf(input.readUTF())
                val grant = if (state == TwitchCatalogGrantState.READY) {
                    val user = input.readUTF().also { check(validCatalogUserId(it)) }
                    val saved = input.readLong()
                    val expires = input.readLong()
                    check(saved >= 0 && expires > saved)
                    val duration = Math.subtractExact(expires, saved)
                    val credentials = TwitchCatalogCredentials(input.readUTF(), input.readUTF(), duration)
                    TwitchCatalogStoredGrant(generation, state, revision(), user, saved, expires, credentials)
                } else TwitchCatalogStoredGrant(generation, state, revision())
                check(input.read() == -1)
                grant
            }
        } catch (_: Exception) {
            throw TwitchCatalogStoreException(TwitchCatalogStoreFailure.INVALID_RECORD)
        } finally { bytes.fill(0) }
    }

    private fun writeUnlocked(grant: TwitchCatalogStoredGrant) {
        val bytes = try {
            ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeInt(1)
                    output.writeUTF(instanceId)
                    output.writeUTF(TACHIAI_TWITCH_CLIENT_ID)
                    output.writeUTF(TWITCH_CATALOG_SCOPE)
                    output.writeUTF(grant.generation)
                    output.writeUTF(grant.state.name)
                    if (grant.state == TwitchCatalogGrantState.READY) {
                        val credentials = checkNotNull(grant.credentials)
                        output.writeUTF(checkNotNull(grant.userId))
                        output.writeLong(checkNotNull(grant.savedAtMs))
                        output.writeLong(checkNotNull(grant.expiresAtMs))
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
                    if (error.failure == TwitchCatalogStoreFailure.INVALID_RECORD) null else throw error
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
        if (!isRevisionCurrent(attempt.revision) || !canCommit()) {
            abandonUnlocked(ready, attempt.revision)
            return@locked null
        }
        ready
    }

    private fun ready(generation: String, revision: Long, replacement: TwitchCatalogReplacement): TwitchCatalogStoredGrant {
        val saved = wallMs()
        check(saved >= 0)
        val expires = Math.addExact(saved, replacement.remainingMs)
        return TwitchCatalogStoredGrant(generation, TwitchCatalogGrantState.READY, revision,
            replacement.validation.userId, saved, expires,
            TwitchCatalogCredentials(replacement.credentials.accessToken, replacement.credentials.refreshToken,
                replacement.remainingMs))
    }

    private fun abandonUnlocked(grant: TwitchCatalogStoredGrant, revision: Long) {
        try {
            writeUnlocked(TwitchCatalogStoredGrant(grant.generation, TwitchCatalogGrantState.RECONNECT, revision))
        } catch (error: TwitchCatalogStoreException) {
            invalidate()
            throw error
        }
    }

    fun requireReconnect(grant: TwitchCatalogStoredGrant) = locked {
        val previous = readUnlocked() ?: return@locked
        if (previous.generation != grant.generation || !isRevisionCurrent(grant.revision)) return@locked
        invalidate()
        val revision = revision()
        writeUnlocked(TwitchCatalogStoredGrant(UUID.randomUUID().toString(), TwitchCatalogGrantState.RECONNECT, revision))
        allowRevision(revision)
    }

    fun isStoredCurrent(grant: TwitchCatalogStoredGrant): Boolean = locked {
        if (!isRevisionCurrent(grant.revision)) return@locked false
        val current = readUnlocked()
        current?.state == TwitchCatalogGrantState.READY && current.generation == grant.generation
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
        val credentials = checkNotNull(current.credentials)
        writeUnlocked(TwitchCatalogStoredGrant(current.generation, TwitchCatalogGrantState.REFRESHING, grant.revision))
        if (!isRevisionCurrent(grant.revision) || !canCommit()) return@locked null
        val replacement = exchange(credentials)
        check(replacement.validation.userId == current.userId)
        if (!isRevisionCurrent(grant.revision) || !canCommit()) return@locked null
        val ready = ready(UUID.randomUUID().toString(), grant.revision, replacement)
        writeUnlocked(ready)
        if (!isRevisionCurrent(grant.revision) || !canCommit()) {
            abandonUnlocked(ready, grant.revision)
            return@locked null
        }
        ready
    }

    override fun toString() = "TwitchCatalogGrantStore(redacted)"
}
