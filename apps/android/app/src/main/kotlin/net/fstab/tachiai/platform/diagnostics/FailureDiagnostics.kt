package net.fstab.tachiai.platform.diagnostics

import android.content.Context
import android.os.Looper
import android.util.Log
import java.io.File

// Debug prototype only. Neither journal nor logcat ever receives an exception.
internal object FailureDiagnostics {
    private const val TAG = "TachiaiFailure"
    // Every reporter in this process shares one journal writer (#757).
    @Volatile private var shared: FailureJournalWriter? = null

    private fun writer(context: Context): FailureJournalWriter = shared ?: synchronized(this) {
        shared ?: FailureJournalWriter(
            FailureJournal(File(context.noBackupFilesDir, "failure-diagnostics.tsv"))::recordAll,
            lost = { loss ->
                Log.e(TAG, if (loss == FailureJournalLoss.QUEUE_FULL) "event=JOURNAL_DROPPED" else "event=JOURNAL_UNAVAILABLE")
            },
            log = { line -> Log.i(TAG, line) },
        ).also { shared = it }
    }

    fun create(context: Context): FailureReporter {
        val journal = writer(context)
        return FailureReporter(journal::submit, thread = {
            if (Looper.myLooper() == Looper.getMainLooper()) FailureThread.MAIN else FailureThread.WORKER
        }, fallback = { Log.e(TAG, "event=JOURNAL_UNAVAILABLE") })
    }

    // Waits at most timeoutMs for records already reported in this process.
    // True when none is still pending, or when nothing was ever reported.
    fun flush(timeoutMs: Long): Boolean = shared?.flush(timeoutMs) ?: true
}
