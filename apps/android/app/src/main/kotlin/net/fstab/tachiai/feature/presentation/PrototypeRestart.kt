package net.fstab.tachiai.feature.presentation

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Process
import android.os.Build
import net.fstab.tachiai.MainActivity
import net.fstab.tachiai.platform.diagnostics.FailureDiagnostics
import net.fstab.tachiai.presentation.ConfiguredFeedAssignments

// Terminate only this owned prototype process after a durable picker checkpoint
// and successful launch of the separate main process. Never force-stop the app.
internal fun restartPrototypeProcess(activity: Activity, cached: Boolean, assignments: ConfiguredFeedAssignments) {
    if (Build.VERSION.SDK_INT < 28) error("Recovery is unavailable on this Android version")
    performPrototypeRestart(Application.getProcessName(), activity.packageName, cached,
        checkpoint = { ConfiguredRecoveryCheckpoint(activity.noBackupFilesDir, cached).write(assignments) },
        launch = {
            val destination = if (cached) Intent(activity, PlaybackRestartActivity::class.java)
                .putExtra(PlaybackRestartActivity.PRIOR_PID, Process.myPid())
            else Intent(activity, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("net.fstab.tachiai.extra.NATIVE_ACCESS_PROBE", true)
            activity.startActivity(destination)
        },
        finish = activity::finish,
        flush = { FailureDiagnostics.flush(RESTART_JOURNAL_FLUSH_MS) },
        terminate = { Process.killProcess(Process.myPid()) })
}

// Bounds how long termination waits for failure records already reported (#757).
internal const val RESTART_JOURNAL_FLUSH_MS = 1_000L

internal fun performPrototypeRestart(processName: String, packageName: String, cached: Boolean,
    checkpoint: () -> Unit, launch: () -> Unit, finish: () -> Unit, flush: () -> Unit, terminate: () -> Unit) {
    val suffix = if (cached) ":prototype_cached_player" else ":prototype_player"
    check(processName == packageName + suffix) { "Recovery requires the dedicated prototype process" }
    checkpoint()
    launch()
    finish()
    // A bounded diagnostic wait; it can neither fail nor prevent the restart.
    try { flush() } catch (_: Exception) { /* Diagnostics cannot replace recovery. */ }
    terminate()
}
