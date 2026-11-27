package net.fstab.tachiai.platform.media

import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.platform.diagnostics.ViewerMainSteps

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
        // How long release() held the viewer's main thread (#769).
        try { observeNativeFailure(NativeFailureStage.PLAYER_RELEASE, onFailure) { ViewerMainSteps.time("playerRelease") { player.release() } } }
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

// #769: ExoPlayer.release() blocks its application (main) thread until the
// playback thread has disabled and reset its renderers, released their codecs
// and released the media source. At Stop, two such waits were most of the
// viewer's skipped frames. stop() asks the playback thread for that same reset
// without waiting. It runs first, so the reset overlaps the worker lane's
// transport close, and a worker then waits, bounded, for it to finish before
// release() is posted. release(), its timeout and late-release handling are
// unchanged: when the wait lapses, release() waits for the rest as before.
internal const val NATIVE_PLAYER_STOP_DRAIN_MS = 1_000L

// Marks the playback thread's queue after stop(); null when it cannot.
internal fun interface NativePlayerStopDrain { fun mark(): NativePlayerReleaseCompletion? }

@UnstableApi
internal fun nativePlayerStopDrain(player: Player): NativePlayerStopDrain? {
    val looper = runCatching { (player as? ExoPlayer)?.playbackLooper }.getOrNull() ?: return null
    if (looper === runCatching { Looper.getMainLooper() }.getOrNull()) return null
    return NativePlayerStopDrain {
        // Its queue runs in order, so this runs once the stop has been handled.
        val drained = CountDownLatch(1)
        if (!Handler(looper).post { drained.countDown() }) null
        else NativePlayerReleaseCompletion { timeoutMs -> drained.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }
}

// Register right after ownPlayer. Synchronous cleanup on the application
// thread never waits here; its release() waits for the reset as before.
@UnstableApi
internal fun NativeCleanupReceipt.stopPlayerAhead(player: Player,
    onApplicationThread: () -> Boolean = { Looper.myLooper() == player.applicationLooper },
    drain: NativePlayerStopDrain? = nativePlayerStopDrain(player), drainMs: Long = NATIVE_PLAYER_STOP_DRAIN_MS) {
    val stopped = AtomicReference<NativePlayerReleaseCompletion?>()
    own(1, ownerThread = true) {
        // Best effort only: release() still performs the whole reset.
        runCatching { player.stop(); stopped.set(drain?.mark()) }
    }
    own(29) {
        val pending = stopped.getAndSet(null) ?: return@own
        if (runCatching(onApplicationThread).getOrDefault(true)) return@own
        try { pending.await(drainMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        catch (_: Throwable) { }
    }
}
