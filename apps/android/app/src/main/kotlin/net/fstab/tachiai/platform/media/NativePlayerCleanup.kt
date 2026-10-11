package net.fstab.tachiai.platform.media

import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi

// Register immediately after construction, before binding or settings can fail.
// Media3 can report a release timeout through this listener and return void.
@UnstableApi
internal fun NativeCleanupReceipt.ownPlayer(player: Player, releasing: () -> Boolean,
    onFailure: NativeFailureObserver) {
    own(30) { observeNativeFailure(NativeFailureStage.PLAYER_RELEASE, onFailure) { player.release() } }
    try {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { playerError(releasing(), error.errorCode) }
        })
    } catch (error: Throwable) {
        // Rollback cannot prove a void-returning release without its observer.
        failed()
        throw error
    }
}
