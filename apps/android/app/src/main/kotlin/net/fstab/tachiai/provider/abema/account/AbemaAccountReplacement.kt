package net.fstab.tachiai.provider.abema.account

// A publication receipt belongs to this exact write. The caller's acceptance
// callback runs after the serialized disk section, allowing protected owner
// reconciliation without nesting registry and account-store locks. A rejected
// publication rolls back only while this receipt still owns the slot.
internal fun withAbemaAccountReplacement(store: AbemaAccountGrantStore,
    grant: AbemaAccountGrant, profileId: String, expectedRevision: Long,
    minimumWallMs: Long, wallMs: () -> Long,
    onRestored: (AbemaAccountSnapshot) -> Unit = {},
    canCommit: () -> Boolean, accept: (AbemaAccountSnapshot, Long) -> Boolean): Boolean {
    if (profileId != grant.userId) return false
    var published: AbemaAccountSnapshot? = null
    var accepted = false
    try {
        val wall = wallMs()
        if (wall < minimumWallMs || decodedAbemaGrant(grant.bearer, grant.userId, wall).expiresAtMs != grant.expiresAtMs)
            return false
        val receipt = store.replaceSnapshot(grant, wall, expectedRevision, onRestored, canCommit)
        published = receipt
        val record = receipt.record ?: return false
        val now = wallMs()
        if (!store.usable(receipt.revision) || now < maxOf(minimumWallMs, record.acceptedWallMs) ||
            now >= grant.expiresAtMs || record.grant.userId != grant.userId || record.grant.bearer != grant.bearer ||
            record.grant.expiresAtMs != grant.expiresAtMs) return false
        accepted = accept(receipt, now)
        return accepted
    } finally {
        if (!accepted) published?.let { receipt -> store.rollbackPublished(receipt)?.let(onRestored) }
    }
}

// This predicate deliberately proves the replacement, not the predecessor that
// became obsolete when the successful CAS incremented the store revision.
internal fun abemaReplacementReceiptCurrent(store: AbemaAccountGrantStore, receipt: AbemaAccountSnapshot): Boolean {
    val expected = receipt.record ?: return false
    if (!store.usable(receipt.revision)) return false
    val current = store.readSnapshot()
    val record = current.record ?: return false
    return current.revision == receipt.revision && store.usable(receipt.revision) &&
        record.grant.userId == expected.grant.userId && record.grant.bearer == expected.grant.bearer &&
        record.grant.expiresAtMs == expected.grant.expiresAtMs && record.acceptedWallMs == expected.acceptedWallMs &&
        record.generation == expected.generation
}
