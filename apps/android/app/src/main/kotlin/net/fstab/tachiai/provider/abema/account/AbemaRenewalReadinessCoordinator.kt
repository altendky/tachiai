package net.fstab.tachiai.provider.abema.account

// Ordinary-process RAM only. The one-shot ticket is the only value crossing
// Activity boundaries; the exact account projection remains private here.
internal class AbemaRenewalReadinessCoordinator<T>(private val elapsed: () -> Long,
    private val ticket: () -> String, private val lifetimeMs: Long = 90_000,
    private val cooldownMs: Long = 60_000) {
    enum class Status { READY, LAUNCH, WAIT, FAILURE, TEMPORARY_FAILURE, INTERACTION_REQUIRED, STORAGE_FAILURE }
    class Decision(val status: Status, val ticket: String? = null) {
        override fun toString() = "AbemaRenewalDecision(${status.name})"
    }
    private class Entry<T>(val instance: String, val scope: Any, val value: T, val ticket: String,
        val began: Long, val deadline: Long) {
        var claimed = false
        var released = false
        var finished: Long? = null
        var terminal = Status.FAILURE
        var cleanupRefused = false
    }
    private val entries = mutableListOf<Entry<T>>()
    private var lastElapsed = 0L
    private var refusedClock = false
    private fun now(): Long? {
        val value = elapsed()
        if (value < lastElapsed || value < 0) refusedClock = true
        lastElapsed = maxOf(lastElapsed, value)
        return value.takeUnless { refusedClock }
    }
    @Synchronized fun check(instance: String, scope: Any, value: T, originalDeadline: Long = Long.MAX_VALUE): Decision {
        val now = now() ?: return Decision(Status.FAILURE)
        entries.filter { it.finished == null && now >= it.deadline }.forEach {
            it.finished = now; it.terminal = Status.TEMPORARY_FAILURE
        }
        entries.removeAll { (!it.claimed || it.released) && it.finished?.let { ended -> now - ended >= cooldownMs } == true }
        entries.filter { it.instance == instance && it.scope != scope && it.finished == null }
            .forEach { it.finished = now }
        entries.firstOrNull { it.instance == instance && it.scope == scope }?.let {
            return Decision(if (it.finished == null) Status.WAIT else it.terminal)
        }
        // The approval profile/proxy is shared across instances on this device.
        if (entries.any { it.finished == null || it.claimed && !it.released }) return Decision(Status.WAIT)
        if (entries.size >= 32 || lifetimeMs <= 0 || now > Long.MAX_VALUE - lifetimeMs || originalDeadline <= now) return Decision(Status.FAILURE)
        val nonce = ticket()
        if (!nonce.matches(Regex("[a-f0-9]{32}")) || entries.any { it.ticket == nonce }) return Decision(Status.FAILURE)
        entries.add(Entry(instance, scope, value, nonce, now, minOf(now + lifetimeMs, originalDeadline)))
        return Decision(Status.LAUNCH, nonce)
    }
    fun invalidate(instance: String) = invalidate(instance) { true }
    @Synchronized fun invalidate(instance: String, matches: (T) -> Boolean) {
        val now = now() ?: 0
        entries.filter { it.instance == instance && it.finished == null && matches(it.value) }.forEach { it.finished = now }
    }
    @Synchronized fun claim(ticket: String): T? {
        val now = now() ?: return null
        val entry = entries.singleOrNull { it.ticket == ticket } ?: return null
        if (entry.claimed || entry.finished != null || now < entry.began || now >= entry.deadline) return null
        entry.claimed = true
        return entry.value
    }
    @Synchronized fun current(ticket: String): Boolean {
        val now = now() ?: return false
        return entries.singleOrNull { it.ticket == ticket }?.let {
            it.finished == null && now >= it.began && now < it.deadline
        } == true
    }
    // An observer must join claimed work before interpreting a record that its
    // CAS has published tentatively. Returning READY or invalidating that ticket
    // in this interval could expose or roll back an unaccepted replacement.
    @Synchronized fun observeClaimed(instance: String, matches: (T) -> Boolean): Decision? {
        val now = now() ?: return Decision(Status.FAILURE)
        val entry = entries.firstOrNull { it.instance == instance && it.claimed && !it.released && matches(it.value) }
            ?: return null
        if (entry.finished == null && now >= entry.deadline) {
            entry.finished = now; entry.terminal = Status.TEMPORARY_FAILURE
        }
        return Decision(if (entry.finished == null) Status.WAIT else entry.terminal)
    }
    fun finish(ticket: String, success: Boolean) = finish(ticket, if (success) Status.READY else Status.FAILURE)
    @Synchronized fun finish(ticket: String, result: Status, cleanupConfirmed: Boolean = true) {
        val now = now() ?: 0
        val entry = entries.singleOrNull { it.ticket == ticket } ?: return
        if (!cleanupConfirmed) entry.cleanupRefused = true
        entry.released = cleanupConfirmed && !entry.cleanupRefused
        if (entry.finished != null) return
        entry.terminal = when {
            !cleanupConfirmed -> Status.TEMPORARY_FAILURE
            refusedClock -> Status.FAILURE
            now >= entry.deadline -> Status.TEMPORARY_FAILURE
            result == Status.READY && !entry.claimed -> Status.FAILURE
            result in setOf(Status.READY, Status.FAILURE, Status.TEMPORARY_FAILURE, Status.INTERACTION_REQUIRED, Status.STORAGE_FAILURE) -> result
            else -> Status.FAILURE
        }
        entry.finished = now
    }
    @Synchronized fun cancelUnclaimed(ticket: String) {
        val entry = entries.singleOrNull { it.ticket == ticket } ?: return
        // No browser/controller has claimed this work, so no renewal failed.
        // A foreground successor can retry the same projection immediately.
        if (!entry.claimed) entries.remove(entry)
    }
    override fun toString() = "AbemaRenewalReadinessCoordinator(redacted)"
}
