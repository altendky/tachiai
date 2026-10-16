package net.fstab.tachiai.platform.network

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// A binding pins its first reconciled candidate. Foreground admission can pause
// independently; an observed configuration mismatch/read failure cannot recover
// on this same binding even if the old configuration is restored later.
internal class ProviderRouteAdmission(expectedProof: String? = null) {
    private val selected = AtomicReference(expectedProof)
    private val refused = AtomicBoolean()
    init { require(expectedProof == null || validProof(expectedProof)) }
    val ownerProof: String? get() = selected.get()
    fun canContinue(): Boolean = !refused.get()

    fun check(canRemainActive: () -> Boolean = { true }, readOwnerProof: (String?) -> String?): Boolean {
        if (!canContinue() || !canRemainActive()) return false
        val observed = runCatching { readOwnerProof(selected.get()) }.getOrNull()
        if (observed == null || !validProof(observed)) refused.set(true)
        else {
            selected.compareAndSet(null, observed)
            if (selected.get() != observed) refused.set(true)
        }
        return canContinue() && canRemainActive()
    }

    override fun toString() = "ProviderRouteAdmission(redacted)"
    private fun validProof(value: String) = value.matches(Regex("[a-f0-9]{64}"))
}
