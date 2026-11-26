package net.fstab.tachiai.platform.diagnostics

// Process-local counts and millisecond totals under closed names (#745),
// drained as one line at most once per interval. Callers pass only fixed
// event names and measured durations; no value, URL, host or identity.
internal class OperationStats(private val name: String, private val intervalMs: Long = 30_000L) {
    private class Timer { var count = 0L; var totalMs = 0L; var maxMs = 0L }
    private val lock = Any()
    private val counts = linkedMapOf<String, Long>()
    private val timers = linkedMapOf<String, Timer>()
    private var since = Long.MIN_VALUE
    init { require(name.matches(NAME) && intervalMs > 0) }

    fun count(event: String, nowMs: Long, delta: Long = 1) {
        require(event.matches(EVENT))
        synchronized(lock) { started(nowMs); counts[event] = (counts[event] ?: 0L) + delta }
    }

    fun time(event: String, durationMs: Long, nowMs: Long) {
        require(event.matches(EVENT))
        val bounded = durationMs.coerceIn(0L, 86_400_000L)
        synchronized(lock) {
            started(nowMs)
            val timer = timers.getOrPut(event, ::Timer)
            timer.count++; timer.totalMs += bounded; timer.maxMs = maxOf(timer.maxMs, bounded)
        }
    }

    // The accumulated line once intervalMs has passed since the first event
    // it covers; the totals then restart.
    fun drain(nowMs: Long): String? = synchronized(lock) {
        if (since == Long.MIN_VALUE || nowMs - since < intervalMs) return null
        val line = buildString {
            append(name); append(" windowMs="); append(nowMs - since)
            for ((event, count) in counts) { append(' '); append(event); append('='); append(count) }
            for ((event, timer) in timers) {
                append(' '); append(event); append('='); append(timer.count)
                append(' '); append(event); append("Ms="); append(timer.totalMs)
                append(' '); append(event); append("MaxMs="); append(timer.maxMs)
            }
        }
        counts.clear(); timers.clear(); since = Long.MIN_VALUE
        line
    }

    private fun started(nowMs: Long) { if (since == Long.MIN_VALUE) since = nowMs }

    private companion object {
        val NAME = Regex("[a-z][A-Za-z]{0,15}=[A-Z_]{1,32}")
        val EVENT = Regex("[a-z][A-Za-z]{0,31}")
    }
}
