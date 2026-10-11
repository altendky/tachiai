package net.fstab.tachiai.provider.abema.account

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.presentation.validProviderInstanceId

internal interface AbemaAccountStorage : PrivateSecretStore {
    fun markForgetPending()
    fun forgetPending(): Boolean
    fun finishForget()
}

internal class AbemaStoredAccount(val grant: AbemaAccountGrant, val acceptedWallMs: Long, val generation: Long) {
    override fun toString() = "AbemaStoredAccount(redacted)"
}
internal class AbemaAccountSnapshot(val record: AbemaStoredAccount?, val revision: Long,
    internal val previous: AbemaStoredAccount? = null, internal val replacementOwner: Any? = null) {
    override fun toString() = "AbemaAccountSnapshot(redacted)"
}

// One ordinary application process owns account storage. Shared revisions make
// Forget invalidate all in-memory leases synchronously before worker I/O.
internal class AbemaAccountGrantStore(private val instanceId: String, private val storage: AbemaAccountStorage) {
    // The state monitor protects only scalars. It must never cover storage or
    // owner checks: Forget revocation is available while a disk write is held.
    private class Shared { val disk = Any(); var revision = 0L; var forgetGeneration = 0L; var blocked = false; var poisoned = false }
    companion object { private val owners = ConcurrentHashMap<String, Shared>() }
    private val shared = owners.computeIfAbsent(instanceId) { Shared() }
    private class Rollback(val revision: Long, val forgetGeneration: Long, val mayUnblock: Boolean)
    private class Restoration(val confirmed: Boolean, val fatal: Boolean)
    init { require(validProviderInstanceId(instanceId)) }
    fun revision(): Long = synchronized(shared) {
        if (shared.poisoned) throw AbemaAccountException(AbemaAccountFailure.STORAGE_FAILED)
        shared.revision
    }
    fun usable(revision: Long) = synchronized(shared) { !shared.blocked && !shared.poisoned && shared.revision == revision }
    private fun invalidate(): Pair<Long, Long> = synchronized(shared) {
        shared.blocked = true; shared.forgetGeneration++; ++shared.revision to shared.forgetGeneration
    }
    fun invalidateForget(): Long = invalidate().first

    fun read(): AbemaStoredAccount? = readSnapshot().record
    fun readSnapshot(): AbemaAccountSnapshot = synchronized(shared.disk) {
        val revision = revision()
        if (!usable(revision)) return AbemaAccountSnapshot(null, revision)
        val record = readDisk()
        // A concurrent Forget may revoke this read without waiting for it.
        synchronized(shared) {
            AbemaAccountSnapshot(record.takeIf { !shared.blocked && !shared.poisoned && shared.revision == revision }, revision)
        }
    }
    private fun readDisk(): AbemaStoredAccount? {
        try {
            if (storage.forgetPending()) {
                synchronized(shared) { if (!shared.blocked) { shared.blocked = true; shared.revision++ } }
                return null
            }
        } catch (error: Throwable) { storageFailed(error); }
        val bytes = try { storage.read() } catch (error: Throwable) { storageFailed(error) } ?: return null
        try {
            if (bytes.size > PRIVATE_SECRET_LIMIT) accountRefused()
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != 1 || input.readUTF() != instanceId) accountRefused()
            val present = input.readBoolean()
            val result = if (!present) null else {
                val id = input.readUTF(); val token = input.readUTF(); val expiry = input.readLong()
                val accepted = input.readLong(); val generation = input.readLong()
                if (accepted < 0 || generation < 1) accountRefused()
                AbemaStoredAccount(AbemaAccountGrant(id, token, expiry), accepted, generation)
            }
            if (input.read() != -1) accountRefused()
            return result
        } catch (error: Throwable) { storageFailed(error) }
        finally { bytes.fill(0) }
    }
    fun replaceSnapshot(grant: AbemaAccountGrant, wallMs: Long, expectedRevision: Long,
        onRestored: (AbemaAccountSnapshot) -> Unit = {}, canCommit: () -> Boolean): AbemaAccountSnapshot = synchronized(shared.disk) {
        if (!usable(expectedRevision) || !canCommit() || wallMs < 0 || grant.expiresAtMs <= wallMs) accountRefused()
        val old = readDisk()
        if (!usable(expectedRevision) || (old != null && (wallMs < old.acceptedWallMs || old.generation == Long.MAX_VALUE))) accountRefused()
        val record = AbemaStoredAccount(grant, wallMs, (old?.generation ?: 0) + 1)
        val bytes = encode(record)
        try {
            if (!canCommit() || !usable(expectedRevision)) accountRefused()
            try { storage.write(bytes) }
            catch (error: Throwable) {
                val revoked = revokeRollback(fatal = error !is Exception)!!
                val restored = restore(old)
                finishRollback(revoked, old, restored)?.let(onRestored)
                throw AbemaAccountException(AbemaAccountFailure.STORAGE_FAILED)
            }
            val allowed = try { canCommit() } catch (error: Throwable) {
                val revoked = revokeRollback(fatal = error !is Exception)!!
                val restored = restore(old)
                finishRollback(revoked, old, restored)?.let(onRestored)
                throw AbemaAccountException(AbemaAccountFailure.STORAGE_FAILED)
            }
            val snapshot = synchronized(shared) {
                if (!allowed || shared.blocked || shared.poisoned || shared.revision != expectedRevision) null
                else AbemaAccountSnapshot(record, ++shared.revision, old, shared)
            }
            if (snapshot != null) return snapshot
            // Lifecycle/Forget loss during publication restores the prior
            // record before the serialized Forget worker can clear it.
            val revoked = revokeRollback()!!
            val restored = restore(old)
            finishRollback(revoked, old, restored)?.let(onRestored)
            throw AbemaAccountException(if (restored.confirmed && !restored.fatal) AbemaAccountFailure.REFUSED else AbemaAccountFailure.STORAGE_FAILED)
        } finally { bytes.fill(0) }
    }
    // A replacement does not alter the prior record until all validation and
    // owner gates have passed. Uncertain writes invalidate this process's leases.
    fun replace(grant: AbemaAccountGrant, wallMs: Long, expectedRevision: Long, canCommit: () -> Boolean): AbemaStoredAccount =
        replaceSnapshot(grant, wallMs, expectedRevision, canCommit = canCommit).record ?: accountRefused()
    // The receipt is tied to this exact publication and its prior projection.
    // A later replacement or Forget owns the slot instead and is never undone.
    fun rollbackPublished(snapshot: AbemaAccountSnapshot): AbemaAccountSnapshot? = synchronized(shared.disk) {
        if (snapshot.replacementOwner !== shared || snapshot.record == null) accountRefused()
        val revoked = revokeRollback(snapshot.revision) ?: return null
        val published = try { readDisk() } catch (_: Throwable) {
            finishRollback(revoked, snapshot.previous, Restoration(false, false))
            throw AbemaAccountException(AbemaAccountFailure.STORAGE_FAILED)
        }
        if (!rollbackCurrent(revoked)) return null
        if (!sameRecord(published, snapshot.record)) {
            finishRollback(revoked, snapshot.previous, Restoration(false, false))
            throw AbemaAccountException(AbemaAccountFailure.STORAGE_FAILED)
        }
        val restored = restore(snapshot.previous)
        val result = finishRollback(revoked, snapshot.previous, restored)
        if (!restored.confirmed || restored.fatal) throw AbemaAccountException(AbemaAccountFailure.STORAGE_FAILED)
        result
    }
    private fun revokeRollback(expectedRevision: Long? = null, fatal: Boolean = false): Rollback? = synchronized(shared) {
        if (expectedRevision != null && (shared.blocked || shared.poisoned || shared.revision != expectedRevision)) return null
        val mayUnblock = !shared.blocked && !shared.poisoned
        shared.blocked = true; if (fatal) shared.poisoned = true
        Rollback(++shared.revision, shared.forgetGeneration, mayUnblock)
    }
    private fun rollbackCurrent(receipt: Rollback) = synchronized(shared) {
        shared.revision == receipt.revision && shared.forgetGeneration == receipt.forgetGeneration && !shared.poisoned
    }
    private fun finishRollback(receipt: Rollback, old: AbemaStoredAccount?, restored: Restoration): AbemaAccountSnapshot? = synchronized(shared) {
        if (!restored.confirmed || restored.fatal) { shared.blocked = true; shared.poisoned = true; return null }
        if (!receipt.mayUnblock || shared.poisoned || shared.revision != receipt.revision || shared.forgetGeneration != receipt.forgetGeneration) return null
        shared.blocked = false
        AbemaAccountSnapshot(old, receipt.revision)
    }
    private fun sameRecord(a: AbemaStoredAccount?, b: AbemaStoredAccount?) =
        a != null && b != null && a.grant.userId == b.grant.userId && a.grant.bearer == b.grant.bearer &&
            a.grant.expiresAtMs == b.grant.expiresAtMs && a.acceptedWallMs == b.acceptedWallMs && a.generation == b.generation
    private fun restore(old: AbemaStoredAccount?): Restoration {
        val bytes = encode(old)
        return try { storage.write(bytes); Restoration(true, false) }
        catch (error: Throwable) {
            var fatal = error !is Exception
            // AtomicFile may have restored its prior bytes before reporting a
            // failed write. Confirm that exact plaintext projection locally;
            // failure to establish it keeps every process lease closed.
            val confirmed = try {
                val existing = storage.read()
                try { if (existing == null) old == null else existing.contentEquals(bytes) }
                finally { existing?.fill(0) }
            } catch (readError: Throwable) { fatal = fatal || readError !is Exception; false }
            if (confirmed) return Restoration(true, fatal)
            try { storage.markForgetPending() } catch (markerError: Throwable) { fatal = fatal || markerError !is Exception }
            Restoration(false, fatal)
        } finally { bytes.fill(0) }
    }
    fun forget() {
        val revoked = invalidate().second
        synchronized(shared.disk) {
            try {
                storage.markForgetPending()
                val bytes = encode(null)
                try { storage.write(bytes) } finally { bytes.fill(0) }
                storage.finishForget()
                synchronized(shared) {
                    // A newer Forget keeps admission blocked until its own
                    // known cleared record is confirmed.
                    if (shared.forgetGeneration == revoked) {
                        shared.revision++; shared.blocked = false; shared.poisoned = false
                    }
                }
            } catch (error: Throwable) { storageFailed(error) }
        }
    }
    private fun storageFailed(error: Throwable): Nothing {
        synchronized(shared) { shared.revision++; if (error !is Exception) shared.poisoned = true }
        throw AbemaAccountException(AbemaAccountFailure.STORAGE_FAILED)
    }
    private fun encode(record: AbemaStoredAccount?): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.writeUTF(instanceId); out.writeBoolean(record != null)
            if (record != null) {
                out.writeUTF(record.grant.userId); out.writeUTF(record.grant.bearer); out.writeLong(record.grant.expiresAtMs)
                out.writeLong(record.acceptedWallMs); out.writeLong(record.generation)
            }
        }
        return bytes.toByteArray().takeIf { it.size <= PRIVATE_SECRET_LIMIT } ?: accountRefused()
    }
    override fun toString() = "AbemaAccountGrantStore(redacted)"
}
