package net.fstab.tachiai.provider.abema.account

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.SystemClock

// Revalidating the renewed grant (profile and email) needs this much of the
// acquisition's own deadline, so the wait for a renewal ends this early.
internal const val ABEMA_ACCESS_REFRESH_VALIDATION_MS = 5_000L

// #635 step 6: an account acquisition that finds its saved grant expired asks
// the device's refresh owner (the main-process readiness path) to renew it and
// waits within the acquisition's own deadline. Worker only.
internal fun interface AbemaAccountAccessRefresh {
    enum class Outcome {
        // The saved grant is usable again: validate it once more.
        RENEWED,
        // Not renewed, or not allowed to wait here: the original failure stands.
        UNCHANGED,
        // A renewal may still finish, but not before this deadline.
        PENDING,
    }
    fun await(deadlineElapsedMs: Long, canContinue: () -> Boolean): Outcome
}

// Polls the readiness reserve like any foreground readiness observer: it
// starts or joins the one renewal for the account's scope (with step 1's retry
// loop and step 2's shared scope), activates an owned reservation as the
// readiness service does, and never cancels or extends that work. The renewal
// keeps the readiness path's own 90-second limit and outlives this waiter.
internal class AbemaAccountRefreshWait(
    private val elapsed: () -> Long,
    private val sleep: (Long) -> Unit,
    // The readiness reserve for this account with a renewal deadline and an admission gate.
    private val reserve: (Long, () -> Boolean) -> AbemaRenewalReservation,
    // A manual approval holds the approval browser: do not wait behind it.
    private val held: () -> Boolean,
    // This thread must never wait for a renewal (for example the account worker the renewal itself uses).
    private val blocked: () -> Boolean = { false },
    private val pollMs: Long = 250,
    private val renewalLimitMs: Long = AbemaReadinessIpc.LIMIT_MS,
) : AbemaAccountAccessRefresh {
    companion object {
        // No wait re-enters another wait on the same thread.
        private val waiting = ThreadLocal.withInitial { false }
    }

    override fun await(deadlineElapsedMs: Long, canContinue: () -> Boolean): AbemaAccountAccessRefresh.Outcome {
        if (waiting.get() || runCatching(blocked).getOrDefault(true)) return AbemaAccountAccessRefresh.Outcome.UNCHANGED
        waiting.set(true)
        return try { poll(deadlineElapsedMs, canContinue) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); AbemaAccountAccessRefresh.Outcome.UNCHANGED }
        catch (_: Throwable) { AbemaAccountAccessRefresh.Outcome.UNCHANGED }
        finally { waiting.set(false) }
    }

    private fun poll(deadline: Long, canContinue: () -> Boolean): AbemaAccountAccessRefresh.Outcome {
        val began = elapsed()
        if (began < 0 || began > Long.MAX_VALUE - renewalLimitMs) return AbemaAccountAccessRefresh.Outcome.UNCHANGED
        val renewal = began + renewalLimitMs
        fun admitted() = runCatching(canContinue).getOrDefault(false) && !runCatching(held).getOrDefault(true)
        while (true) {
            if (!admitted()) return AbemaAccountAccessRefresh.Outcome.UNCHANGED
            val now = elapsed()
            if (now < began || now >= deadline) return AbemaAccountAccessRefresh.Outcome.PENDING
            val status = when (val reservation = reserve(renewal, ::admitted)) {
                is AbemaRenewalReservation.Owned -> {
                    reservation.lease.activate()
                    AbemaRenewalReadinessCoordinator.Status.WAIT
                }
                is AbemaRenewalReservation.Observed -> reservation.status
                is AbemaRenewalReservation.NoWork -> reservation.status
                AbemaRenewalReservation.Deferred -> return AbemaAccountAccessRefresh.Outcome.UNCHANGED
            }
            when (status) {
                AbemaRenewalReadinessCoordinator.Status.READY -> return AbemaAccountAccessRefresh.Outcome.RENEWED
                AbemaRenewalReadinessCoordinator.Status.WAIT -> Unit
                // Interaction, storage, unconfirmed cleanup, final and exhausted results.
                else -> return AbemaAccountAccessRefresh.Outcome.UNCHANGED
            }
            val left = deadline - elapsed()
            if (left <= 0) return AbemaAccountAccessRefresh.Outcome.PENDING
            sleep(minOf(pollMs, left))
        }
    }

    override fun toString() = "AbemaAccountRefreshWait(redacted)"
}

// Main process only, where the readiness coordinator owns the device's single
// renewal: the process's own route (no job Network) and no lead, so only an
// expired grant renews. Elsewhere acquisitions fail as before.
internal fun androidAbemaAccountAccessRefresh(context: Context, target: AbemaReadinessTarget): AbemaAccountAccessRefresh? {
    val application = context.applicationContext
    if (Build.VERSION.SDK_INT < 28 || Application.getProcessName() != application.packageName) return null
    return AbemaAccountRefreshWait(SystemClock::elapsedRealtime, { Thread.sleep(it) },
        { deadline, admitted -> AndroidAbemaRenewalReadiness.reserve(application, target, deadline, admitted = admitted) },
        AndroidAbemaEmailGrantHandoff.inbox::manualPending,
        blocked = { Looper.getMainLooper().isCurrentThread || AbemaAccountWorkers.onWorker() })
}
