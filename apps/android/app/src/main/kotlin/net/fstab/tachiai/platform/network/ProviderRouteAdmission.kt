package net.fstab.tachiai.platform.network

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// A binding pins its first reconciled candidate. Foreground admission can pause
// independently; an observed configuration mismatch/read failure cannot recover
// on this same binding even if the old configuration is restored later.
// Closed first cause of a binding's refusal (#729), for diagnostics only.
internal enum class ProviderRouteRefusal { POOL_CHANGED, READ_FAILED, OWNER_CHANGED, INVALID_PROOF }

internal class ProviderRouteAdmission(expectedProof: String? = null) {
    private val selected = AtomicReference(expectedProof)
    private val refused = AtomicBoolean()
    private val firstRefusal = AtomicReference<ProviderRouteRefusal?>()
    init { require(expectedProof == null || validProof(expectedProof)) }
    val ownerProof: String? get() = selected.get()
    fun canContinue(): Boolean = !refused.get()
    // Why this binding first refused, or null while it has not.
    val refusal: ProviderRouteRefusal? get() = firstRefusal.get()
    private fun refuse(cause: ProviderRouteRefusal) {
        firstRefusal.compareAndSet(null, cause)
        refused.set(true)
    }

    fun check(canRemainActive: () -> Boolean = { true }, readCancelled: () -> Boolean = { false },
        readOwnerProof: (String?) -> String?): Boolean {
        if (!canContinue() || !canRemainActive() || readCancelled()) return false
        val result = runCatching { readOwnerProof(selected.get()) }
        val observed = result.getOrNull()
        // Cancellation is not evidence of a changed/unreadable owner. An actual
        // observed mismatch still revokes even if the same read was canceled.
        val failure = result.exceptionOrNull()
        if (failure is ProviderRoutePoolException) refuse(ProviderRouteRefusal.POOL_CHANGED)
        else if (observed == null) {
            if (!readCancelled()) refuse(if (failure != null) ProviderRouteRefusal.READ_FAILED else ProviderRouteRefusal.OWNER_CHANGED)
        }
        else if (!validProof(observed)) refuse(ProviderRouteRefusal.INVALID_PROOF)
        else {
            selected.compareAndSet(null, observed)
            if (selected.get() != observed) refuse(ProviderRouteRefusal.OWNER_CHANGED)
        }
        return canContinue() && canRemainActive() && !readCancelled()
    }

    override fun toString() = "ProviderRouteAdmission(redacted)"
    private fun validProof(value: String) = value.matches(Regex("[a-f0-9]{64}"))
}
