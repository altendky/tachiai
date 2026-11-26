package net.fstab.tachiai.platform.media

import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.BuildConfig

private val nativeCleanupWorker = Executors.newFixedThreadPool(2) { task ->
    Thread(task, "native-player-cleanup").apply { isDaemon = true }
}

internal fun NativeCleanupReceipt.closePlayer(handler: Handler, onFailure: NativeFailureObserver,
    onComplete: () -> Unit) = closeAsync(nativeCleanupWorker, Executor { task ->
        if (Looper.myLooper() == handler.looper) task.run()
        else check(handler.post(task)) { "Player cleanup dispatch rejected" }
    }, onComplete, { reportNativeFailure(NativeFailureStage.PLAYER_RELEASE, onFailure, it) })

// #730: Media3 stops waiting for its playback thread after the release timeout
// (500 ms by default) and reports 1003, but that thread keeps releasing. Media3's
// own release() treats a finished playback thread as released, so the same
// evidence can confirm a late release. The wait is bounded well inside the
// viewer's 20 s cleanup deadline; a release still running then stays failed.
internal const val NATIVE_PLAYER_LATE_RELEASE_DEADLINE_MS = 5_000L

// Waits up to the given time for the release to finish; true only when it did.
internal fun interface NativePlayerReleaseCompletion { fun await(timeoutMs: Long): Boolean }

private val nativeReleaseWaiter = Executors.newCachedThreadPool { task ->
    Thread(task, "native-player-release-wait").apply { isDaemon = true }
}

// An ExoPlayer built without a shared playback looper owns its playback
// thread, which quits once the queued release has run.
@UnstableApi
internal fun nativePlayerReleaseCompletion(player: Player): NativePlayerReleaseCompletion? {
    val thread = runCatching { (player as? ExoPlayer)?.playbackLooper?.thread }.getOrNull() ?: return null
    if (thread === runCatching { Looper.getMainLooper()?.thread }.getOrNull()) return null
    return NativePlayerReleaseCompletion { timeoutMs -> thread.join(timeoutMs); !thread.isAlive }
}

// Register immediately after construction, before binding or settings can fail.
// Media3 can report a release timeout through this listener and return void.
@UnstableApi
internal fun NativeCleanupReceipt.ownPlayer(player: Player, releasing: () -> Boolean,
    onFailure: NativeFailureObserver, releaseClockMs: () -> Long = SystemClock::elapsedRealtime,
    releaseCompletion: NativePlayerReleaseCompletion? = nativePlayerReleaseCompletion(player),
    lateReleaseWaiter: Executor = nativeReleaseWaiter) {
    val diagnostic = NativePlayerReleaseDiagnostics(releaseClockMs) {
        reportNativeFailure(NativeFailureStage.PLAYER_RELEASE, onFailure, it)
    }
    // Set only while player.release() runs, so its own synchronous timeout
    // notification is held for late evidence instead of failing at once.
    val releaseCall = AtomicBoolean()
    val releaseTimedOut = AtomicBoolean()
    own(30, ownerThread = true) {
        diagnostic.begin()
        releaseCall.set(true)
        try { observeNativeFailure(NativeFailureStage.PLAYER_RELEASE, onFailure) { player.release() } }
        finally { releaseCall.set(false); diagnostic.finish() }
        if (releaseTimedOut.get()) awaitLatePlayerRelease(releaseCompletion, lateReleaseWaiter, releaseClockMs)
    }
    try {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val active = releasing()
                if (active && releaseCall.get() && error.errorCode == NATIVE_PLAYER_RELEASE_TIMEOUT_CODE)
                    releaseTimedOut.set(true)
                else playerError(active, error.errorCode)
                diagnostic.playerError(active, error.errorCode)
            }
        })
    } catch (error: Throwable) {
        // Rollback cannot prove a void-returning release without its observer.
        failed()
        throw error
    }
}

private fun NativeCleanupReceipt.awaitLatePlayerRelease(completion: NativePlayerReleaseCompletion?,
    waiter: Executor, clockMs: () -> Long) {
    // Synchronous rollback cannot wait: its timeout stays an unconfirmed release.
    val resolve = awaitLateRelease() ?: run { failed(); return }
    if (completion == null) { resolve(false); return }
    try {
        waiter.execute {
            val began = runCatching(clockMs).getOrNull()
            val completed = runCatching { completion.await(NATIVE_PLAYER_LATE_RELEASE_DEADLINE_MS) }.getOrDefault(false)
            val waited = began?.let { start -> runCatching(clockMs).getOrNull()?.takeIf { it >= start }?.minus(start) } ?: -1L
            if (BuildConfig.DEBUG) runCatching {
                // Closed values only; release diagnostics never print a Throwable.
                Log.i("TachiaiNativeRelease", "event=PLAYER_RELEASE_LATE completed=$completed waitedMs=$waited")
            }
            resolve(completed)
        }
    } catch (_: Throwable) { resolve(false) }
}
