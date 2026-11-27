package net.fstab.tachiai.platform.diagnostics

import android.os.Looper
import android.os.SystemClock
import android.util.Log

// Closed main-thread step timings at Stop and feed start (#769). Each line
// holds a fixed step name and its milliseconds, never a value, URL or identity.
// Work on any other thread is not recorded.
internal object ViewerMainSteps {
    private val STEP = Regex("[a-z][A-Za-z]{0,31}")
    fun line(step: String, ms: Long): String {
        require(step.matches(STEP))
        return "viewer=MAIN_STEP step=$step ms=${ms.coerceIn(0L, 86_400_000L)}"
    }
    fun onMain() = Looper.myLooper() == Looper.getMainLooper()
    inline fun <T> time(step: String, block: () -> T): T {
        if (!onMain()) return block()
        val began = SystemClock.uptimeMillis()
        try { return block() } finally { record(step, SystemClock.uptimeMillis() - began) }
    }
    fun record(step: String, ms: Long) { runCatching { Log.d("TachiaiViewerMain", line(step, ms)) } }
}
