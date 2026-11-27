package net.fstab.tachiai.feature.presentation

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import net.fstab.tachiai.MainActivity

/** Same-app relay in its own process (#764); it never terminates a supplied process. */
class MainProcessRestartActivity : ComponentActivity() {
    companion object {
        internal const val PRIOR_PID = "PRIOR_MAIN_PID"
        internal const val PROCESS_SUFFIX = ":main_restart"
        private const val BEGAN_AT = "restart.beganAt"
        private const val OPENED = "restart.opened"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var coordinator: PlaybackRestartCoordinator? = null
    private var priorPid = 0
    private var resumed = false
    private var opened = false
    // When "Restarting Tachiai…" first drew, and when the old process was first seen gone (#773).
    private var messageDrawnAt = -1L
    private var readyAt = -1L
    private val poll = Runnable { checkExit() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = runCatching {
            check(Build.VERSION.SDK_INT >= 28 && Application.getProcessName() == packageName + PROCESS_SUFFIX)
            check(intent.extras?.keySet() == setOf(PRIOR_PID))
            priorPid = intent.getIntExtra(PRIOR_PID, 0)
            check(priorPid > 0 && priorPid != Process.myPid())
            opened = savedInstanceState?.getBoolean(OPENED, false) ?: false
            val beganAt = if (savedInstanceState == null) SystemClock.elapsedRealtime()
                else savedInstanceState.getLong(BEGAN_AT, -1)
            PlaybackRestartCoordinator(priorPid, Process.myUid(), packageName, beganAt)
        }.getOrNull()
        intent = Intent(this, MainProcessRestartActivity::class.java).putExtra(PRIOR_PID, priorPid)
        if (opened) { finish(); return }
        coordinator = request
        if (request == null) showFailure() else showWaiting()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        coordinator?.let { outState.putLong(BEGAN_AT, it.beganAt) }
        outState.putBoolean(OPENED, opened)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (coordinator != null && !opened) handler.post(poll)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() { handler.removeCallbacks(poll); super.onDestroy() }

    private fun checkExit() {
        if (!resumed || opened || isFinishing || isDestroyed) return
        val current = coordinator ?: return
        val processes = runCatching {
            getSystemService(ActivityManager::class.java).runningAppProcesses?.map {
                RestartProcessIdentity(it.pid, it.uid, it.processName)
            }?.takeIf { snapshot -> snapshot.any {
                it.pid == Process.myPid() && it.uid == Process.myUid() && it.name == packageName + PROCESS_SUFFIX
            } }
        }.getOrNull()
        when (current.poll(SystemClock.elapsedRealtime(), processes)) {
            PlaybackRestartStatus.WAITING -> handler.postDelayed(poll, 50)
            PlaybackRestartStatus.READY -> {
                val now = SystemClock.elapsedRealtime()
                if (readyAt < 0) readyAt = now
                if (!restartMessageShown(now, messageDrawnAt.takeIf { it >= 0 }, readyAt)) {
                    handler.postDelayed(poll, 50); return
                }
                opened = true
                try {
                    // The launcher form opens the main menu in debug builds too.
                    startActivity(Intent(this, MainActivity::class.java)
                        .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                } catch (_: Exception) { opened = false; showFailure() }
            }
            PlaybackRestartStatus.IDENTITY_MISMATCH, PlaybackRestartStatus.TIMED_OUT -> showFailure()
        }
    }

    private fun layout(message: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        addView(TextView(this@MainProcessRestartActivity).apply { text = message })
    }

    private fun showWaiting() {
        messageDrawnAt = -1L; readyAt = -1L
        val view = layout("Restarting Tachiai…")
        setContentView(view)
        // Tachiai reopens only once this message has reached the screen (#773).
        view.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (messageDrawnAt < 0) messageDrawnAt = SystemClock.elapsedRealtime()
                view.post { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(this) }
            }
        })
    }

    private fun showFailure() {
        handler.removeCallbacks(poll)
        setContentView(layout("Tachiai could not be restarted safely. Saved app data has been kept.").apply {
            if (coordinator != null) addView(Button(this@MainProcessRestartActivity).apply {
                text = "Check again"
                setOnClickListener {
                    coordinator = PlaybackRestartCoordinator(priorPid, Process.myUid(), packageName, SystemClock.elapsedRealtime())
                    showWaiting()
                    if (resumed) handler.post(poll)
                }
            })
            addView(Button(this@MainProcessRestartActivity).apply { text = "Close"; setOnClickListener { finish() } })
        })
    }
}
