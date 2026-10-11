package net.fstab.tachiai.provider.abema.account

internal enum class AbemaAccountStatus { MISSING, UNVERIFIED, PAUSED, CONNECTED, RECONNECT_REQUIRED, NOT_VERIFIED, TEMPORARY, STORAGE_FAILED }
internal class AbemaAccountLease internal constructor(val grant: AbemaAccountGrant, private val current: () -> Boolean) {
    fun usable() = current()
    override fun toString() = "AbemaAccountLease(redacted)"
}

// No persisted 'validated' bit. Every recreated/resumed session needs a fresh
// authenticated profile check plus positive/invalid-bearer enforcement evidence
// made through this captured owner. Pausing drops all usable account leases.
internal class AbemaAccountSession(private val store: AbemaAccountGrantStore,
    private val wallMs: () -> Long, private val canUse: () -> Boolean) {
    private val lock = Any()
    private var record: AbemaStoredAccount? = null
    private var loadedRevision: Long? = null
    private var validatedRevision: Long? = null
    private var foreground = true
    private var generation = 0L
    private var lastWall = 0L
    private var status = AbemaAccountStatus.MISSING
    fun status(): AbemaAccountStatus = synchronized(lock) {
        val value = record
        if (status == AbemaAccountStatus.CONNECTED && value != null) {
            val now = wallMs()
            if (loadedRevision?.let(store::usable) != true || now < maxOf(value.acceptedWallMs, lastWall) || now >= value.grant.expiresAtMs) {
                validatedRevision = null; status = AbemaAccountStatus.RECONNECT_REQUIRED
            }
        }
        status
    }
    fun load() {
        val run = synchronized(lock) { validatedRevision = null; loadedRevision = null; ++generation }
        try {
            val snapshot = store.readSnapshot()
            synchronized(lock) { if (generation == run) {
                record = snapshot.record; loadedRevision = snapshot.revision
                status = if (!foreground) AbemaAccountStatus.PAUSED else if (record == null) AbemaAccountStatus.MISSING else AbemaAccountStatus.UNVERIFIED
            } }
        } catch (_: Exception) { synchronized(lock) { if (generation == run) { record = null; status = AbemaAccountStatus.STORAGE_FAILED } } }
    }
    fun setForeground(value: Boolean) = synchronized(lock) {
        foreground = value; generation++; validatedRevision = null
        status = if (!value) AbemaAccountStatus.PAUSED else if (record == null) AbemaAccountStatus.MISSING else AbemaAccountStatus.UNVERIFIED
    }
    private class Snapshot(val record: AbemaStoredAccount, val revision: Long, val generation: Long, val lastWall: Long, val validated: Boolean)
    // Only scalar checks run under the session monitor. Protected owner checks,
    // decoding and serialized disk I/O happen after capturing this generation.
    private fun capture(expected: AbemaStoredAccount? = null, validated: Boolean = false): Snapshot? = synchronized(lock) {
        val value = record ?: return null
        val revision = loadedRevision ?: return null
        if (!foreground || (expected != null && value !== expected)) return null
        if (!store.usable(revision)) {
            validatedRevision = null; status = AbemaAccountStatus.RECONNECT_REQUIRED; return null
        }
        if (validated && validatedRevision != revision) return null
        Snapshot(value, revision, generation, lastWall, validatedRevision == revision)
    }
    private fun current(value: Snapshot, validated: Boolean = false): Boolean =
        foreground && generation == value.generation && record === value.record && loadedRevision == value.revision &&
            store.usable(value.revision) && (!validated || validatedRevision == value.revision)
    private fun currentGeneration(run: Long) = synchronized(lock) { foreground && generation == run }
    private fun valid(value: Snapshot): Long? {
        if (!synchronized(lock) { current(value) }) return null
        val now = wallMs()
        val valid = canUse() && now >= maxOf(value.record.acceptedWallMs, value.lastWall) && now < value.record.grant.expiresAtMs &&
            runCatching { decodedAbemaGrant(value.record.grant.bearer, value.record.grant.userId, now).expiresAtMs == value.record.grant.expiresAtMs }.getOrDefault(false)
        return synchronized(lock) {
            if (!current(value)) null
            else if (!valid || now < lastWall) {
                validatedRevision = null; status = AbemaAccountStatus.RECONNECT_REQUIRED; null
            } else { lastWall = now; now }
        }
    }
    fun candidate(): AbemaStoredAccount? {
        val value = capture() ?: return null
        if (valid(value) == null) return null
        return synchronized(lock) { value.record.takeIf { current(value) } }
    }
    fun acceptExisting(expected: AbemaStoredAccount, profileId: String, enforcement: Boolean): Boolean {
        if (profileId != expected.grant.userId || !enforcement) return false
        val value = capture(expected) ?: return false
        if (valid(value) == null) return false
        return synchronized(lock) {
            if (!current(value)) false
            else { validatedRevision = value.revision; status = AbemaAccountStatus.CONNECTED; true }
        }
    }
    fun acceptReplacement(grant: AbemaAccountGrant, profileId: String, enforcement: Boolean,
        expectedRevision: Long, canCommit: () -> Boolean): Boolean {
        if (!enforcement || profileId != grant.userId) return false
        val prior = capture()
        val (run, minimumWall) = synchronized(lock) { if (!foreground) return false; generation to lastWall }
        if (!canUse() || !currentGeneration(run)) return false
        var published: AbemaAccountSnapshot? = null
        var restored: AbemaAccountSnapshot? = null
        var accepted = false
        return try {
            val acceptedWall = wallMs()
            if (acceptedWall < minimumWall || decodedAbemaGrant(grant.bearer, grant.userId, acceptedWall).expiresAtMs != grant.expiresAtMs) return false
            val snapshot = store.replaceSnapshot(grant, acceptedWall, expectedRevision, onRestored = { restored = it }) {
                currentGeneration(run) && canUse() && canCommit() && currentGeneration(run)
            }
            published = snapshot
            val replacement = snapshot.record ?: return false
            val now = wallMs()
            if (!currentGeneration(run) || !canUse() || !canCommit() || !currentGeneration(run)) return false
            accepted = synchronized(lock) {
                if (!foreground || generation != run || !store.usable(snapshot.revision) ||
                    now < maxOf(replacement.acceptedWallMs, lastWall) || now >= replacement.grant.expiresAtMs) false
                else {
                    record = replacement; loadedRevision = snapshot.revision; validatedRevision = snapshot.revision
                    lastWall = now; status = AbemaAccountStatus.CONNECTED; true
                }
            }
            accepted
        } catch (_: Exception) { synchronized(lock) { if (generation == run) status = AbemaAccountStatus.STORAGE_FAILED }; false }
        finally { if (!accepted) {
            published?.let { receipt ->
                try { restored = store.rollbackPublished(receipt) }
                catch (_: Exception) { restored = null; synchronized(lock) { if (generation == run) status = AbemaAccountStatus.STORAGE_FAILED } }
            }
            if (prior != null && prior.revision == expectedRevision) restored?.let { recoverPrior(prior, it, run) }
        } }
    }
    private fun recoverPrior(prior: Snapshot, restored: AbemaAccountSnapshot, run: Long) {
        val value = restored.record ?: return
        val original = prior.record
        if (prior.generation != run || value.grant.userId != original.grant.userId || value.grant.bearer != original.grant.bearer ||
            value.grant.expiresAtMs != original.grant.expiresAtMs || value.acceptedWallMs != original.acceptedWallMs || value.generation != original.generation) return
        if (!currentGeneration(run) || !store.usable(restored.revision) || !canUse() || !currentGeneration(run)) return
        val now = wallMs()
        if (now < maxOf(value.acceptedWallMs, prior.lastWall) || now >= value.grant.expiresAtMs ||
            runCatching { decodedAbemaGrant(value.grant.bearer, value.grant.userId, now).expiresAtMs == value.grant.expiresAtMs }.getOrDefault(false) != true) return
        synchronized(lock) {
            if (!foreground || generation != run || record !== original || loadedRevision != prior.revision || !store.usable(restored.revision) || now < lastWall) return
            record = value; loadedRevision = restored.revision; validatedRevision = restored.revision.takeIf { prior.validated }
            lastWall = now; status = if (prior.validated) AbemaAccountStatus.CONNECTED else AbemaAccountStatus.UNVERIFIED
        }
    }
    private fun leaseUsable(value: Snapshot): Boolean {
        if (!synchronized(lock) { current(value, true) }) return false
        if (valid(value) == null) return false
        return synchronized(lock) { current(value, true) }
    }
    fun lease(): AbemaAccountLease? {
        val value = capture(validated = true) ?: return null
        if (!leaseUsable(value)) return null
        return AbemaAccountLease(value.record.grant) { leaseUsable(value) }
    }
    fun forgetImmediately() = synchronized(lock) {
        store.invalidateForget(); generation++; record = null; loadedRevision = null; validatedRevision = null; status = AbemaAccountStatus.MISSING
    }
    fun failed(category: AbemaAccountFailure) {
        val run = synchronized(lock) { generation }
        // A refused new connection leaves the prior usable grant intact. The
        // owner check is outside the monitor so pause/Forget can revoke it.
        if (lease() != null) return
        synchronized(lock) { if (generation == run) {
            status = when (category) {
                AbemaAccountFailure.EXPIRED -> AbemaAccountStatus.RECONNECT_REQUIRED
                AbemaAccountFailure.STORAGE_FAILED -> AbemaAccountStatus.STORAGE_FAILED
                AbemaAccountFailure.NOT_VERIFIED, AbemaAccountFailure.REFUSED -> AbemaAccountStatus.NOT_VERIFIED
                else -> AbemaAccountStatus.TEMPORARY
            }
        } }
    }
    override fun toString() = "AbemaAccountSession(redacted)"
}
