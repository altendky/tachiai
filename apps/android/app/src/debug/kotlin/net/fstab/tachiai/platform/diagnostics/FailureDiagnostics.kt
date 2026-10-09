package net.fstab.tachiai.platform.diagnostics

import android.content.Context
import android.os.Looper
import android.util.Log
import java.io.File

// Debug prototype only. Neither journal nor logcat ever receives an exception.
internal object FailureDiagnostics {
    fun create(context: Context): FailureReporter {
        val journal = FailureJournal(File(context.noBackupFilesDir, "failure-diagnostics.tsv"))
        return FailureReporter(journal::record, thread = {
            if (Looper.myLooper() == Looper.getMainLooper()) FailureThread.MAIN else FailureThread.WORKER
        }, fallback = { Log.e("TachiaiFailure", "event=JOURNAL_UNAVAILABLE") })
    }
}
