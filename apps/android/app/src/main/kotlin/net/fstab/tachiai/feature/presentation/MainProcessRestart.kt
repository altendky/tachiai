package net.fstab.tachiai.feature.presentation

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import net.fstab.tachiai.platform.diagnostics.FailureDiagnostics

// #764: an unconfirmed provider startup cleanup quarantines that provider in
// the main process until it ends. Recovery mirrors the viewer's restart:
// launch a relay in a separate process, finish, flush diagnostics, then end
// only this main process. The relay reopens the main menu once it has gone.
// Never force-stop the app. The process ends only once the relay covers this
// Activity (#773): ending it earlier removed its window before the relay's
// appeared, which showed black frames.
internal fun restartMainProcess(activity: Activity) {
    if (Build.VERSION.SDK_INT < 28) error("Recovery is unavailable on this Android version")
    performMainRestart(Application.getProcessName(), activity.packageName,
        launch = {
            activity.startActivity(Intent(activity, MainProcessRestartActivity::class.java)
                .putExtra(MainProcessRestartActivity.PRIOR_PID, Process.myPid()))
        },
        finish = activity::finish,
        afterCovered = { end -> whenCovered(activity, end) },
        flush = { FailureDiagnostics.flush(RESTART_JOURNAL_FLUSH_MS) },
        terminate = { Process.killProcess(Process.myPid()) })
}

// A stop or destroy that never arrives still ends the process after this limit.
internal const val RESTART_COVER_LIMIT_MS = 2_000L

private fun whenCovered(activity: Activity, action: () -> Unit) {
    val application = activity.application
    val handler = Handler(Looper.getMainLooper())
    var ran = false
    lateinit var limit: Runnable
    lateinit var callbacks: Application.ActivityLifecycleCallbacks
    fun run() {
        if (ran) return
        ran = true
        application.unregisterActivityLifecycleCallbacks(callbacks)
        handler.removeCallbacks(limit)
        action()
    }
    callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStopped(stopped: Activity) { if (stopped === activity) run() }
        override fun onActivityDestroyed(destroyed: Activity) { if (destroyed === activity) run() }
        override fun onActivityCreated(created: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(started: Activity) = Unit
        override fun onActivityResumed(resumed: Activity) = Unit
        override fun onActivityPaused(paused: Activity) = Unit
        override fun onActivitySaveInstanceState(saved: Activity, state: Bundle) = Unit
    }
    application.registerActivityLifecycleCallbacks(callbacks)
    limit = Runnable { run() }
    handler.postDelayed(limit, RESTART_COVER_LIMIT_MS)
}

// #773: once the old main process has ended, the relay keeps "Restarting
// Tachiai…" on screen for at least RESTART_MESSAGE_MIN_MS after it first drew,
// so it can be read. A message that has not drawn delays the restart by at
// most RESTART_MESSAGE_WAIT_MS after the old process ended.
internal const val RESTART_MESSAGE_MIN_MS = 500L
internal const val RESTART_MESSAGE_WAIT_MS = 1_000L

internal fun restartMessageShown(now: Long, drawnAt: Long?, readyAt: Long): Boolean =
    if (drawnAt != null) now - drawnAt >= RESTART_MESSAGE_MIN_MS else now - readyAt >= RESTART_MESSAGE_WAIT_MS

internal fun performMainRestart(processName: String, packageName: String,
    launch: () -> Unit, finish: () -> Unit, afterCovered: (() -> Unit) -> Unit,
    flush: () -> Unit, terminate: () -> Unit) {
    check(processName == packageName) { "Recovery requires the main process" }
    launch()
    finish()
    afterCovered {
        // A bounded diagnostic wait; it can neither fail nor prevent the restart.
        try { flush() } catch (_: Exception) { /* Diagnostics cannot replace recovery. */ }
        terminate()
    }
}
