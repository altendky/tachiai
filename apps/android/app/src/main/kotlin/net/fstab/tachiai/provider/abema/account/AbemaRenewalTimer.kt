package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

// A waiting timer looks at the wall clock again at least this often, so a long
// sleep or a wall-clock change cannot leave it far from its due time.
internal const val ABEMA_RENEWAL_TIMER_MAXIMUM_WAIT_MS = 5 * 60_000L
// While a manual approval holds the approval browser, the timer looks again after this.
internal const val ABEMA_RENEWAL_TIMER_MANUAL_RECHECK_MS = 30_000L

// Main-process RAM only (#635 step 5). While the process lives, each saved
// ABEMA grant gets one renewal attempt at the renewal job's first due time
// (15 minutes before expiry, not before half its lifetime). The renewal job
// remains the wake-up when the process is dead; either one joins the other's
// renewal in flight. A changed grant generation (renewal
// or acceptance) schedules a new window; a route or setup change (reset)
// allows one more attempt in the current window. Closed constants only in logs.
internal class AbemaRenewalTimer(
    private val wall: () -> Long,
    // Runs the action after the delay; false if it could not be scheduled.
    private val delay: (Long, () -> Unit) -> Boolean,
    private val manualApproval: () -> Boolean,
    // Starts one attempt, which calls the last argument once it has ended.
    private val fire: (String, AbemaRenewalGeneration, () -> Unit) -> Unit,
    private val log: (String) -> Unit = {},
    private val maximumWaitMs: Long = ABEMA_RENEWAL_TIMER_MAXIMUM_WAIT_MS,
    private val manualRecheckMs: Long = ABEMA_RENEWAL_TIMER_MANUAL_RECHECK_MS,
) {
    // Slot identity is the schedule token: a replaced slot's callbacks do nothing.
    private class Slot(val generation: AbemaRenewalGeneration) {
        var fired = false
        var postponed = false
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

    // Debug builds only (#689): the due path below for a saved grant, now. It
    // bypasses only the due-time check; this window's single attempt, a
    // running attempt and a manual approval still refuse. The caller runs it on
    // the same worker as the timer's own callbacks.
    fun trigger(instance: String): AbemaRenewalTrigger {
        val slot = synchronized(this) {
            val slot = slots[instance] ?: return AbemaRenewalTrigger.NO_ACCOUNT
            if (instance in attempting) return AbemaRenewalTrigger.RUNNING
            if (slot.fired) return AbemaRenewalTrigger.WINDOW_SPENT
            if (runCatching(manualApproval).getOrDefault(true)) return AbemaRenewalTrigger.MANUAL_APPROVAL
            slot
        }
        log("TIMER_TRIGGERED")
        due(instance, slot, force = true)
        return AbemaRenewalTrigger.STARTED
    }

    private fun due(instance: String, slot: Slot, force: Boolean = false) {
        val fired = synchronized(this) {
            if (slots[instance] !== slot || slot.fired) return
            val now = runCatching(wall).getOrNull()
            val due = runCatching { abemaRenewalDueWallMs(slot.generation) }.getOrNull()
            if (!force && (now == null || due == null || now < due)) {
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
        val done = { if (ended.compareAndSet(false, true)) synchronized(this) { attempting.remove(instance) } }
        try { fire(instance, fired) { done() } } catch (_: Throwable) { done() }
    }

    override fun toString() = "AbemaRenewalTimer(redacted)"
}

// The debug-only renew-now trigger's outcome (#689). Closed constants only.
internal enum class AbemaRenewalTrigger {
    STARTED, NO_ACCOUNT, AMBIGUOUS, RUNNING, WINDOW_SPENT, MANUAL_APPROVAL, UNAVAILABLE,
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
    private val finished: () -> Unit,
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
        runCatching(finished)
    }

    override fun toString() = "AbemaRenewalTimerRun(redacted)"
}
