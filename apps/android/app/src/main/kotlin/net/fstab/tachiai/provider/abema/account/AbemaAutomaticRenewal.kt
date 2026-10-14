package net.fstab.tachiai.provider.abema.account

internal enum class AbemaAutomaticRenewalResult {
    SUCCESS, INTERACTION_REQUIRED, CANCELLED, TEMPORARY_FAILURE, STORAGE_FAILURE,
}

// An expired, previously accepted exact record is input to browser reacquisition,
// not usable account authority. No token lifetime or renewal protocol is invented.
internal fun abemaRenewalSnapshotMatches(expected: AbemaAccountSnapshot, current: AbemaAccountSnapshot,
    nowMs: Long): Boolean {
    val old = expected.record ?: return false
    val saved = current.record ?: return false
    return expected.revision >= 0 && current.revision == expected.revision && old.generation in 1 until Long.MAX_VALUE &&
        old.acceptedWallMs >= 0 && nowMs >= old.acceptedWallMs && nowMs >= old.grant.expiresAtMs &&
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
