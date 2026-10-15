package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.presentation.PrototypeFailureReason

// Capture before fixture disposal. Native and helper timeouts are independent
// evidence: a native deadline must never manufacture a JavaScript timer result.
internal class AbemaPreparationFailureObservation {
    internal data class Snapshot(val nativeReason: PrototypeFailureReason?, val nativeDeadlineReached: Boolean,
        val helper: AbemaCachedHelperMetadata?, val preparation: AbemaNativePreparationSnapshot?) {
        fun closedSummary() = "observation=${if (nativeReason == null) "PROGRESS" else "FAILURE"} " +
            "nativeReason=${nativeReason?.name ?: "UNOBSERVED"} nativeDeadlineReached=$nativeDeadlineReached " +
            "helper=${helper?.closedSummary() ?: "UNOBSERVED"} " +
            "nativePreparation=${preparation?.closedSummary() ?: "UNOBSERVED"}"
    }
    private var sealed = false
    private var value: Snapshot? = null
    @get:Synchronized val snapshot: Snapshot? get() = value
    @Synchronized fun capture(reason: PrototypeFailureReason?, deadlineReached: Boolean, helper: AbemaCachedHelperMetadata?,
        preparation: AbemaNativePreparationSnapshot? = null) {
        if (sealed || value?.nativeReason != null) return
        if (reason == null && helper == null && preparation?.observed != true) return
        value = Snapshot(reason, reason != null && deadlineReached, helper, preparation?.takeIf { it.observed })
    }
    @Synchronized fun seal() { sealed = true }
}
