package net.fstab.tachiai.presentation

// Presentation-thread arbitration only. A delayed durable read never replaces
// restored Activity state or an assignment the user has already changed.
internal class ViewerSelectionRestore(private val preferCurrent: Boolean) {
    var pending = true
        private set
    private var edited = false

    fun userChanged() { edited = true }

    fun loaded(saved: ConfiguredFeedAssignments?, success: Boolean = true): ConfiguredFeedAssignments? {
        if (!pending) return null
        pending = false
        return saved.takeIf { success && !preferCurrent && !edited }
    }
}
