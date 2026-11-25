package net.fstab.tachiai.provider.abema.account

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

// Main-process RAM only (#682). An automatic renewal runs in the main process
// (route engine, deadlines, grant handoff) and in the approval-browser process
// it binds. With no main-process screen visible, for example while the stream
// picker or viewer (their own process) is in front, Android ranks both as cached, and its cached-app
// freezer stops the page, the route and every deadline mid-renewal. While any
// renewal operation is in flight, one executing JobScheduler job keeps the main
// process, and the browser process it binds, out of the cached state. The job
// carries no account data, Network or route and starts no provider work.
// JobScheduler keeps a pending hold until reboot, so a hold left pending by a
// process that died may start it later only to finish at once.
internal class AbemaRenewalExecutionHold<J : Any>(
    // Asks the platform to start the hold job; false if it refused.
    private val schedule: () -> Boolean,
    // Withdraws a hold job that has not started yet.
    private val cancel: () -> Unit,
    // Ends a running hold job.
    private val finish: (J) -> Unit,
    // Closed constants only.
    private val log: (String) -> Unit = {},
) {
    private var holders = 0
    private var pending = false
    private var running: J? = null

    // Asks for the job whenever a renewal holds it and none is pending or
    // running, so a refused or stopped hold is asked for again by the next
    // renewal (or at once after a stop) rather than only at the first holder.
    private fun request() {
        if (holders == 0 || pending || running != null) return
        pending = runCatching(schedule).getOrDefault(false)
        log(if (pending) "HOLD_SCHEDULED" else "HOLD_REFUSED")
    }

    // Returns this holder's idempotent release.
    fun acquire(): () -> Unit {
        synchronized(this) { holders++; request() }
        val released = AtomicBoolean()
        return { if (released.compareAndSet(false, true)) release() }
    }

    @Synchronized private fun release() {
        if (--holders > 0) return
        val job = running
        running = null
        if (job != null) { runCatching { finish(job) }; log("HOLD_ENDED") }
        else if (pending) runCatching(cancel)
        pending = false
    }

    // The platform started the hold job: it runs only while a renewal holds it.
    @Synchronized fun started(job: J): Boolean {
        pending = false
        if (holders == 0 || running != null) return false
        running = job
        log("HOLD_STARTED")
        return true
    }

    // The platform stopped the hold job early. Asks for a new one at once
    // while a renewal still holds it, instead of the platform's backoff
    // (longer than the freezer's delay); the stopped job is never rescheduled.
    @Synchronized fun stopped(matches: (J) -> Boolean): Boolean {
        val job = running ?: return false
        if (!runCatching { matches(job) }.getOrDefault(false)) return false
        running = null
        log("HOLD_STOPPED")
        request()
        return false
    }

    @Synchronized fun held() = holders > 0
    override fun toString() = "AbemaRenewalExecutionHold(redacted)"
}

// The hold job shares AbemaRenewalJobService with the ledger's renewal jobs,
// under one fixed ID just past the ledger's range.
internal object AndroidAbemaRenewalHold {
    val JOB_ID = AbemaRenewalJobLedger.JOB_IDS.last + 1
    // Releases a holder that never completed: well after any renewal deadline.
    private const val RELEASE_MARGIN_MS = 30_000L
    private class Running(val service: JobService, val params: JobParameters)
    private class Platform(val scheduler: JobScheduler, val component: ComponentName)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var platform: Platform? = null
    private val log = { message: String -> android.util.Log.i("TachiaiAbemaRenewal", message); Unit }
    private val hold = AbemaRenewalExecutionHold<Running>(::schedule,
        cancel = { platform?.scheduler?.cancel(JOB_ID) },
        finish = { it.service.jobFinished(it.params, false) }, log = log)

    // Every renewal operation (job, timer, readiness and account access)
    // acquires the hold until it completes, or until its deadline has long passed.
    fun acquire(context: Context, deadlineElapsedMs: Long): () -> Unit {
        if (Build.VERSION.SDK_INT < 28) return {}
        if (platform == null) platform = Platform(context.getSystemService(JobScheduler::class.java),
            ComponentName(context, AbemaRenewalJobService::class.java))
        val release = hold.acquire()
        val wait = deadlineElapsedMs - SystemClock.elapsedRealtime()
        main.postDelayed({ release() }, wait.coerceIn(0L, AbemaReadinessIpc.LIMIT_MS) + RELEASE_MARGIN_MS)
        return release
    }

    fun start(service: JobService, params: JobParameters) = hold.started(Running(service, params))
    fun stop(params: JobParameters) = hold.stopped { it.params.jobId == params.jobId }

    private fun schedule(): Boolean {
        val target = platform ?: return false
        val scheduler = target.scheduler
        // The network constraint gives the job network access where the
        // platform restricts background apps; the renewal needs it anyway.
        fun builder() = JobInfo.Builder(JOB_ID, target.component).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        // Expedited where available: it starts at once, also from the background.
        if (Build.VERSION.SDK_INT >= 31 && runCatching {
                scheduler.schedule(builder().setExpedited(true).build()) == JobScheduler.RESULT_SUCCESS
            }.getOrDefault(false)) return true
        return scheduler.schedule(builder().build()) == JobScheduler.RESULT_SUCCESS
    }
}
