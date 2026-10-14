package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.nativeLiveDefaultPlan

// The profile supplies candidate capability before the exact source is known.
// Only the canonical app-owned source binding selects Chase. Unused candidates
// must never shorten ordinary catch-up, and no source mode can change later.
internal class AbemaSlotChaseAdmission(private val candidateCurrent: () -> Boolean) {
    private enum class Mode { UNSELECTED, ORDINARY, CHASE, REFUSED }
    private var mode = Mode.UNSELECTED
    @get:Synchronized val chaseSelected get() = mode == Mode.CHASE
    @Synchronized fun initialLiveStartAllowed(snapshot: NativeTimingSnapshot?): Boolean {
        if (!current()) return false
        if (mode != Mode.CHASE) return true
        return snapshot != null && snapshot.state == 3 && !snapshot.playWhenReady &&
            nativeLiveDefaultPlan(snapshot).outcome in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE)
    }
    @Synchronized fun select(chase: Boolean): Boolean {
        val selected = if (chase) Mode.CHASE else Mode.ORDINARY
        if (mode == Mode.REFUSED || (mode != Mode.UNSELECTED && mode != selected)) {
            mode = Mode.REFUSED; return false
        }
        mode = selected
        return current()
    }
    @Synchronized fun current(): Boolean {
        if (mode == Mode.REFUSED) return false
        if (mode == Mode.CHASE && !runCatching(candidateCurrent).getOrDefault(false)) {
            mode = Mode.REFUSED; return false
        }
        return true
    }
    override fun toString() = "AbemaSlotChaseAdmission(redacted)"
}
