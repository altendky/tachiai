package net.fstab.tachiai.feature.presentation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

// Shared by the main-process (#773) and viewer (#784) restart relays.

// A stop or destroy that never arrives still ends the process after this limit.
internal const val RESTART_COVER_LIMIT_MS = 2_000L

// The restarting process ends only once the relay covers this Activity: ending
// it earlier removed its window before the relay's appeared, showing black frames.
internal fun whenCovered(activity: Activity, action: () -> Unit) {
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

// Once the old process has ended, a relay keeps its message on screen for at
// least RESTART_MESSAGE_MIN_MS after it first showed, so it can be read. A
// message that has not shown delays the restart by at most
// RESTART_MESSAGE_WAIT_MS after the old process ended.
internal const val RESTART_MESSAGE_MIN_MS = 500L
internal const val RESTART_MESSAGE_WAIT_MS = 1_000L

internal fun restartMessageShown(now: Long, drawnAt: Long?, readyAt: Long): Boolean =
    if (drawnAt != null) now - drawnAt >= RESTART_MESSAGE_MIN_MS else now - readyAt >= RESTART_MESSAGE_WAIT_MS

// A relay's page: its message, clear of the edge-to-edge system bars.
internal fun restartRelayLayout(activity: Activity, message: String) = LinearLayout(activity).apply {
    orientation = LinearLayout.VERTICAL
    val padding = (24 * resources.displayMetrics.density).toInt()
    setPadding(padding, padding, padding, padding)
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(bars.left + padding, bars.top + padding, bars.right + padding, bars.bottom + padding); insets
    }
    addView(TextView(activity).apply { text = message })
}

// Tracks when a relay's waiting message is first on screen. A first draw can
// precede the window replacing its starting window, so the message counts as
// shown once it has drawn and the window has focus.
internal class RestartMessageHold(private val activity: Activity) {
    private var drawn = false
    private var shownAt = -1L
    private var readyAt = -1L

    fun show(message: String) {
        drawn = false; shownAt = -1L; readyAt = -1L
        val view = restartRelayLayout(activity, message)
        activity.setContentView(view)
        view.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                drawn = true
                focusChanged()
                view.post { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(this) }
            }
        })
    }

    // Call from the Activity's onWindowFocusChanged.
    fun focusChanged() {
        if (drawn && shownAt < 0 && activity.hasWindowFocus()) shownAt = SystemClock.elapsedRealtime()
    }

    // The old process has ended: whether the relay may now reopen Tachiai.
    fun ready(now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (readyAt < 0) readyAt = now
        return restartMessageShown(now, shownAt.takeIf { it >= 0 }, readyAt)
    }
}
