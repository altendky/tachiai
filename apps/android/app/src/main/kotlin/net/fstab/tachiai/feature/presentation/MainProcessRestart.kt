package net.fstab.tachiai.feature.presentation

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Process
import net.fstab.tachiai.platform.diagnostics.FailureDiagnostics

// #764: an unconfirmed provider startup cleanup quarantines that provider in
// the main process until it ends. Recovery mirrors the viewer's restart:
// launch a relay in a separate process, finish, flush diagnostics, then end
// only this main process. The relay reopens the main menu once it has gone.
// Never force-stop the app. The process ends only once the relay covers this
// Activity (#773).
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
