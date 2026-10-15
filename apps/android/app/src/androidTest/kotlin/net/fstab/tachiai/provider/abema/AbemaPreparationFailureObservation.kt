package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.presentation.PrototypeFailureReason

// Capture before fixture disposal. Native and helper timeouts are independent
// evidence: a native deadline must never manufacture a JavaScript timer result.
internal class AbemaPreparationFailureObservation {
    internal data class Snapshot(val nativeReason: PrototypeFailureReason, val nativeDeadlineReached: Boolean,
        val helper: AbemaCachedHelperMetadata?) {
        fun closedSummary() = "nativeReason=${nativeReason.name} nativeDeadlineReached=$nativeDeadlineReached " +
            "helper=${helper?.closedSummary() ?: "UNOBSERVED"}"
    }
    private var sealed = false
    private var value: Snapshot? = null
    @get:Synchronized val snapshot: Snapshot? get() = value
    @Synchronized fun capture(reason: PrototypeFailureReason?, deadlineReached: Boolean, helper: AbemaCachedHelperMetadata?) {
        if (sealed || value != null || reason == null) return
        value = Snapshot(reason, deadlineReached, helper)
    }
    @Synchronized fun seal() { sealed = true }
}
