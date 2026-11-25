package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

// A waiting timer looks at the wall clock again at least this often, so a long
// sleep or a wall-clock change cannot leave it far from its due time.
internal const val ABEMA_RENEWAL_TIMER_MAXIMUM_WAIT_MS = 5 * 60_000L
// While a manual approval holds the approval browser, the timer looks again after this.
internal const val ABEMA_RENEWAL_TIMER_MANUAL_RECHECK_MS = 30_000L
// After an attempt that ended without a result (a temporary failure, or a stop
// such as a deadline the readiness retry loop can no longer retry within), the
// timer tries again after these waits, at most once each per window (#682).
internal val ABEMA_RENEWAL_TIMER_RETRY_MS = listOf(30_000L, 2 * 60_000L)

// Main-process RAM only (#635 step 5). While the process lives, each saved
// ABEMA grant gets one renewal attempt at the renewal job's first due time
// (15 minutes before expiry, not before half its lifetime). The renewal job
// remains the wake-up when the process is dead; either one joins the other's
// renewal in flight. An attempt that ends without a result is retried after
// ABEMA_RENEWAL_TIMER_RETRY_MS (#682). A changed grant generation (renewal
// or acceptance) schedules a new window; a route or setup change (reset)
// allows one more attempt in the current window. Closed constants only in logs.
internal class AbemaRenewalTimer(
    private val wall: () -> Long,
    // Runs the action after the delay; false if it could not be scheduled.
    private val delay: (Long, () -> Unit) -> Boolean,
    private val manualApproval: () -> Boolean,
    // Starts one attempt, which calls the last argument once it has ended,
    // with true if it ended without a result and may be retried.
    private val fire: (String, AbemaRenewalGeneration, (Boolean) -> Unit) -> Unit,
    private val log: (String) -> Unit = {},
    private val maximumWaitMs: Long = ABEMA_RENEWAL_TIMER_MAXIMUM_WAIT_MS,
    private val manualRecheckMs: Long = ABEMA_RENEWAL_TIMER_MANUAL_RECHECK_MS,
    private val retryMs: List<Long> = ABEMA_RENEWAL_TIMER_RETRY_MS,
) {
    // Slot identity is the schedule token: a replaced slot's callbacks do nothing.
    private class Slot(val generation: AbemaRenewalGeneration) {
        var fired = false
        var postponed = false
        var retries = 0
    }
    private val slots = HashMap<String, Slot>()
    private val attempting = HashSet<String>()

    // Every ABEMA instance's current grant generation, or null without a grant.
    @Synchronized fun reconcile(plans: Map<String, AbemaRenewalGeneration?>, reset: Boolean = false) {
        slots.keys.filter { plans[it] == null }.forEach { slots.remove(it) }
        plans.forEach { (instance, generation) ->
            if (generation == null) return@forEach
            val existing = slots[instance]
            if (existing != null && existing.generation == generation && !(reset && existing.fired)) return@forEach
            val slot = Slot(generation)
            slots[instance] = slot
            if (arm(instance, slot)) log("TIMER_SCHEDULED") else slots.remove(instance)
        }
    }

    // An attempt continues only while its instance keeps the generation it fired for.
    @Synchronized fun current(instance: String, generation: AbemaRenewalGeneration) = slots[instance]?.generation == generation

    private fun arm(instance: String, slot: Slot, wait: Long? = null): Boolean {
        val remaining = wait ?: runCatching { abemaRenewalDueWallMs(slot.generation) - wall() }.getOrDefault(0L)
        return runCatching { delay(remaining.coerceIn(0L, maximumWaitMs)) { due(instance, slot) } }.getOrDefault(false)
    }

    private fun due(instance: String, slot: Slot) {
        val fired = synchronized(this) {
            if (slots[instance] !== slot || slot.fired) return
            val now = runCatching(wall).getOrNull()
            val due = runCatching { abemaRenewalDueWallMs(slot.generation) }.getOrNull()
            if (now == null || due == null || now < due) {
                if (!arm(instance, slot)) slots.remove(instance)
                return
            }
            // A replaced window's attempt, or a manual approval, still holds the
            // approval browser: look again later without spending this window.
            val held = instance in attempting || runCatching(manualApproval).getOrDefault(true)
            if (held) {
                if (instance !in attempting && !slot.postponed) {
                    slot.postponed = true
                    log("TIMER_POSTPONED reason=MANUAL_APPROVAL")
                }
                if (!arm(instance, slot, manualRecheckMs)) slots.remove(instance)
                return
            }
            slot.fired = true
            attempting.add(instance)
            slot.generation
        }
        log("TIMER_FIRED")
        val ended = AtomicBoolean()
        val done = { retry: Boolean -> if (ended.compareAndSet(false, true)) synchronized(this) {
            attempting.remove(instance)
            // Only the same window's slot retries; a replaced one has its own schedule.
            if (retry && slots[instance] === slot && slot.retries < retryMs.size) {
                val wait = retryMs[slot.retries++]
                slot.fired = false
                if (arm(instance, slot, wait)) log("TIMER_RETRY_SCHEDULED attempt=${slot.retries}")
                else slots.remove(instance)
            }
        } }
        try { fire(instance, fired) { done(it) } } catch (_: Throwable) { done(false) }
    }

    override fun toString() = "AbemaRenewalTimer(redacted)"
}

// One timer attempt through the foreground readiness path: the caller's own
// route (no job Network), the job's lead, the device's single renewal slot and
// the readiness retry loop. Like any readiness observer, it polls until the
// renewal it launched or joined publishes a result, and never cancels or
// extends that work at its own deadline.
internal class AbemaRenewalTimerRun(
    private val instance: String,
    private val generation: AbemaRenewalGeneration,
    private val deadline: Long,
    private val worker: Executor,
    private val elapsed: () -> Long,
    private val inspect: (String) -> AbemaRenewalJobPreflight?,
    private val reserve: (AbemaReadinessTarget, Long, () -> Boolean) -> AbemaRenewalReservation,
    private val admitted: () -> Boolean,
    private val delay: (Long, () -> Unit) -> Boolean,
    // True when the attempt ended without a result and the timer may retry it.
    private val finished: (Boolean) -> Unit,
    // Closed constants only.
    private val log: (String) -> Unit = {},
    private val pollMs: Long = 500,
) {
    private val ended = AtomicBoolean()
    private var target: AbemaReadinessTarget? = null // Worker only.

    fun start() = try { worker.execute(::step) } catch (_: Throwable) { end("ERROR") }

    private fun within() = runCatching { elapsed().let { it >= 0 && it < deadline } }.getOrDefault(false)
    private fun gate() = within() && runCatching(admitted).getOrDefault(false)

    private fun step() {
        if (ended.get()) return
        try {
            val selected = target ?: run {
                val preflight = inspect(instance)
                // A changed or removed grant is rescheduled by its own acceptance or Forget.
                if (preflight == null || preflight.generation != generation) { end("STALE"); return }
                preflight.target.also { target = it }
            }
            if (!gate()) { end("STOPPED"); return }
            when (val reservation = reserve(selected, deadline, ::gate)) {
                is AbemaRenewalReservation.Owned -> {
                    // Application-owned work outlives this observer, as for the readiness service.
                    reservation.lease.activate()
                    poll()
                }
                is AbemaRenewalReservation.Observed ->
                    if (reservation.status == AbemaRenewalReadinessCoordinator.Status.WAIT) poll()
                    else end(reservation.status.name)
                is AbemaRenewalReservation.NoWork -> end(reservation.status.name)
                AbemaRenewalReservation.Deferred -> end("STOPPED")
            }
        } catch (_: Throwable) { end("ERROR") }
    }

    private fun poll() {
        if (!within()) { end("TIMED_OUT"); return }
        val scheduled = runCatching {
            delay(pollMs) { try { worker.execute(::step) } catch (_: Throwable) { end("ERROR") } }
        }.getOrDefault(false)
        if (!scheduled) end("ERROR")
    }

    private fun end(result: String) {
        if (!ended.compareAndSet(false, true)) return
        log("TIMER_RESULT result=$result")
        // A temporary failure, or a stop or timeout before any result (for
        // example a deadline that passed while the process was frozen, #682).
        // Final, interaction and storage results are not retried. An unconfirmed
        // cleanup reads as a temporary failure here, but its retained work
        // keeps the renewal slot, so a retry only observes that result again.
        runCatching { finished(result in ABEMA_RENEWAL_TIMER_RETRIED_RESULTS) }
    }

    override fun toString() = "AbemaRenewalTimerRun(redacted)"
}

private val ABEMA_RENEWAL_TIMER_RETRIED_RESULTS =
    setOf(AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE.name, "STOPPED", "TIMED_OUT")
