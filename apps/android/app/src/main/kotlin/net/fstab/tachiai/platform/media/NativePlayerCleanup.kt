package net.fstab.tachiai.platform.media

import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private val nativeCleanupWorker = Executors.newFixedThreadPool(2) { task ->
    Thread(task, "native-player-cleanup").apply { isDaemon = true }
}

internal fun NativeCleanupReceipt.closePlayer(handler: Handler, onFailure: NativeFailureObserver,
    onComplete: () -> Unit) = closeAsync(nativeCleanupWorker, Executor { task ->
        if (Looper.myLooper() == handler.looper) task.run()
        else check(handler.post(task)) { "Player cleanup dispatch rejected" }
    }, onComplete, { reportNativeFailure(NativeFailureStage.PLAYER_RELEASE, onFailure, it) })

// Register immediately after construction, before binding or settings can fail.
// Media3 can report a release timeout through this listener and return void.
@UnstableApi
internal fun NativeCleanupReceipt.ownPlayer(player: Player, releasing: () -> Boolean,
    onFailure: NativeFailureObserver) {
    own(30, ownerThread = true) { observeNativeFailure(NativeFailureStage.PLAYER_RELEASE, onFailure) { player.release() } }
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
