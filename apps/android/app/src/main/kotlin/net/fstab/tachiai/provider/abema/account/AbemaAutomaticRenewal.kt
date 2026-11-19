package net.fstab.tachiai.provider.abema.account

internal enum class AbemaAutomaticRenewalResult {
    SUCCESS, INTERACTION_REQUIRED, CANCELLED, TEMPORARY_FAILURE, STORAGE_FAILURE,
}

// The renewal job starts this long before natural expiry, so the replacement
// is in place before the grant stops authorizing playback.
internal const val ABEMA_RENEWAL_LEAD_MS = 15 * 60_000L
// Early attempts before natural expiry, earliest first; natural expiry is the
// last scheduled attempt.
internal val ABEMA_RENEWAL_EARLY_STEPS_MS = listOf(ABEMA_RENEWAL_LEAD_MS, 10 * 60_000L, 5 * 60_000L)

// A previously accepted exact record within the lead of its natural expiry, or
// past it, is input to browser reacquisition. No token lifetime or renewal
// protocol is invented; the candidate must still decode to a later expiry.
internal fun abemaRenewalSnapshotMatches(expected: AbemaAccountSnapshot, current: AbemaAccountSnapshot,
    nowMs: Long): Boolean {
    val old = expected.record ?: return false
    val saved = current.record ?: return false
    return expected.revision >= 0 && current.revision == expected.revision && old.generation in 1 until Long.MAX_VALUE &&
        old.acceptedWallMs >= 0 && nowMs >= old.acceptedWallMs && nowMs >= old.grant.expiresAtMs - ABEMA_RENEWAL_LEAD_MS &&
        old.grant.userId == saved.grant.userId && old.grant.bearer == saved.grant.bearer &&
        old.grant.expiresAtMs == saved.grant.expiresAtMs && old.acceptedWallMs == saved.acceptedWallMs &&
        old.generation == saved.generation && runCatching {
            decodedAbemaGrant(old.grant.bearer, old.grant.userId, old.acceptedWallMs).expiresAtMs == old.grant.expiresAtMs
        }.getOrDefault(false)
}

internal fun abemaRenewalCandidateExtends(expected: AbemaAccountSnapshot, grant: AbemaAccountGrant,
    nowMs: Long): Boolean {
    val old = expected.record ?: return false
    return nowMs >= old.acceptedWallMs && grant.userId == old.grant.userId && grant.expiresAtMs > old.grant.expiresAtMs &&
        runCatching { decodedAbemaGrant(grant.bearer, grant.userId, nowMs).expiresAtMs == grant.expiresAtMs }.getOrDefault(false)
}
