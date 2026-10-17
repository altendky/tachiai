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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/** Same-app, main-process relay; it never terminates a supplied process. */
@OptIn(UnstableApi::class)
class PlaybackRestartActivity : ComponentActivity() {
    companion object {
        internal const val PRIOR_PID = "PRIOR_PLAYER_PID"
        private const val BEGAN_AT = "restart.beganAt"
        private const val OPENED = "restart.opened"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var coordinator: PlaybackRestartCoordinator? = null
    private var priorPid = 0
    private var resumed = false
    private var opened = false
    private val poll = Runnable { checkExit() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = runCatching {
            check(Build.VERSION.SDK_INT >= 28 && Application.getProcessName() == packageName)
            check(intent.extras?.keySet() == setOf(PRIOR_PID))
            priorPid = intent.getIntExtra(PRIOR_PID, 0)
            check(priorPid > 0 && priorPid != Process.myPid())
            opened = savedInstanceState?.getBoolean(OPENED, false) ?: false
            val beganAt = if (savedInstanceState == null) SystemClock.elapsedRealtime()
                else savedInstanceState.getLong(BEGAN_AT, -1)
            PlaybackRestartCoordinator(priorPid, Process.myUid(), "$packageName:prototype_cached_player", beganAt)
        }.getOrNull()
        intent = Intent(this, PlaybackRestartActivity::class.java).putExtra(PRIOR_PID, priorPid)
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
                it.pid == Process.myPid() && it.uid == Process.myUid() && it.name == packageName
            } }
        }.getOrNull()
        when (current.poll(SystemClock.elapsedRealtime(), processes)) {
            PlaybackRestartStatus.WAITING -> handler.postDelayed(poll, 50)
            PlaybackRestartStatus.READY -> {
                opened = true
                try {
                    startActivity(Intent(this, CachedPrototypeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
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
        addView(TextView(this@PlaybackRestartActivity).apply { text = message })
    }

    private fun showWaiting() { setContentView(layout("Restarting the player…")) }

    private fun showFailure() {
        handler.removeCallbacks(poll)
        setContentView(layout("The player could not be restarted safely. Saved setup and picker choices have been kept.").apply {
            if (coordinator != null) addView(Button(this@PlaybackRestartActivity).apply {
                text = "Check again"
                setOnClickListener {
                    coordinator = PlaybackRestartCoordinator(priorPid, Process.myUid(),
                        "$packageName:prototype_cached_player", SystemClock.elapsedRealtime())
                    showWaiting()
                    if (resumed) handler.post(poll)
                }
            })
            addView(Button(this@PlaybackRestartActivity).apply { text = "Close"; setOnClickListener { finish() } })
        })
    }
}
