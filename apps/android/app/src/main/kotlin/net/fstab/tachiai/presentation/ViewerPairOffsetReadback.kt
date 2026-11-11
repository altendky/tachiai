package net.fstab.tachiai.presentation

// The session owner publishes its offset value and confirmed applied timing.
// Save failures belong in message; the value need not have reached storage.
// Unknown applied timing stays null. The UI never assumes a seek completed.
internal data class ViewerPairOffsetReadback(
    val savedOffsetMs: Long?,
    val enabled: Boolean,
    val appliedOffsetMs: Long?,
    val busy: Boolean = false,
    val available: Boolean = true,
    val message: String? = null,
    val offsetActive: Boolean? = null,
    val hasCorrespondence: Boolean = false,
    val timelineStatus: String? = null,
)
