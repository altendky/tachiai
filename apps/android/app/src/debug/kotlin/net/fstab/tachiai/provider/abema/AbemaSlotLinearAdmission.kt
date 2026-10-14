package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.nativeLiveDefaultPlan

// Selection only. The session independently checks its account, owner and
// source binding. An opt-in linear source never consumes an unused Premium
// candidate; ended sources and the default path retain that candidate gate.
internal class AbemaSlotLinearAdmission(private val allowed: Boolean = false) {
    private enum class Mode { UNSELECTED, NON_LINEAR, LINEAR, REFUSED }
    private var mode = Mode.UNSELECTED

    @get:Synchronized val linearSelected get() = mode == Mode.LINEAR

    @Synchronized fun initialLiveStartAllowed(snapshot: NativeTimingSnapshot?): Boolean {
        if (mode == Mode.REFUSED) return false
        if (mode != Mode.LINEAR) return true
        return snapshot != null && snapshot.state == 3 && !snapshot.playWhenReady &&
            nativeLiveDefaultPlan(snapshot).outcome in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE)
    }

    @Synchronized fun select(linear: Boolean): Boolean {
        val selected = if (linear) Mode.LINEAR else Mode.NON_LINEAR
        if (mode == Mode.REFUSED || linear && !allowed ||
            mode != Mode.UNSELECTED && mode != selected) {
            mode = Mode.REFUSED
            return false
        }
        mode = selected
        return true
    }

    @Synchronized fun current(premiumCurrent: () -> Boolean): Boolean {
        if (mode == Mode.REFUSED) return false
        if (mode == Mode.LINEAR || allowed && mode == Mode.UNSELECTED) return true
        val candidate = runCatching(premiumCurrent).getOrDefault(false)
        if (!candidate || mode == Mode.REFUSED) {
            mode = Mode.REFUSED
            return false
        }
        return true
    }

    override fun toString() = "AbemaSlotLinearAdmission(redacted)"
}
