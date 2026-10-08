package net.fstab.tachiai.platform.media

import android.view.View
import androidx.media3.common.Player

internal enum class PrototypeFeedEvent { PREPARING, WAITING_PROVIDER, READY, LICENSE_REQUESTED, LICENSE_READY, VIDEO_FRAME, NETWORK_APPROVAL_REQUIRED, FAILED, STOPPED }

// Slot-local provider ownership. Presentation sees no URI, token, browser DOM,
// challenge or license response. Two equal source choices still own two hosts.
internal interface PrototypeFeedSession : AutoCloseable {
    val providerView: View?
    val member: NativePairMember?
    val player: Player?
    fun prepare(budget: NativePlaybackBudget)
    fun pauseOriginal(onResult: (Boolean) -> Unit)
    fun canContinue(): Boolean
    // Worker only. ABEMA has no saved native authorization to poll.
    fun checkAuthorization(): Boolean
}
