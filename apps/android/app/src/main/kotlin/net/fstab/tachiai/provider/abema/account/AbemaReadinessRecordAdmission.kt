package net.fstab.tachiai.provider.abema.account

// Ordering shared by the real readiness service and deterministic race tests.
// Protected owner reconciliation may overlap another worker's publication; a
// stale record must rejoin that claim before a failure can invalidate it.
internal fun abemaReadinessRecordAdmission(ownerCurrent: () -> Boolean,
    claimed: () -> AbemaRenewalReadinessCoordinator.Decision?, recordCurrent: () -> Boolean,
    refused: () -> AbemaRenewalReadinessCoordinator.Decision): AbemaRenewalReadinessCoordinator.Decision? {
    if (!ownerCurrent()) return refused()
    claimed()?.let { return it }
    if (!recordCurrent()) return claimed() ?: refused()
    return null
}
