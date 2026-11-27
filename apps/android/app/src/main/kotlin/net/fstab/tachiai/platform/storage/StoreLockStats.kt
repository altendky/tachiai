package net.fstab.tachiai.platform.storage

import net.fstab.tachiai.platform.diagnostics.OperationStats

// The shared owner stores whose locks route and account checks contend for (#745).
internal enum class StoreLockKind { PROVIDER_INSTANCES, CONNECTION_PROFILES }

// READ reads a record to decrypt after release (#771); REVISION reads only
// its envelope; WRITE commits one.
internal enum class StoreLockUse(val event: String) { READ("read"), REVISION("revision"), WRITE("write") }

// The parts of a READ that run outside the store's locks (#771).
internal enum class StoreReadStep(val event: String) { DECRYPT("decrypt"), DECODE("decode") }

// Process-wide wait (until both the monitor and file lock are held) and hold
// times per store and use, and the time of each read step after its locks
// are released. Counts and milliseconds only.
internal object StoreLockStats {
    private val stats = StoreLockKind.entries.associateWith { OperationStats("store=${it.name}") }
    private fun nowMs() = System.nanoTime() / 1_000_000

    fun <T> measure(kind: StoreLockKind, use: StoreLockUse, enter: (() -> T) -> T, action: () -> T): T {
        val began = System.nanoTime()
        var acquired = began
        var released = began
        var held = false
        try {
            return enter {
                acquired = System.nanoTime(); held = true
                try { action() } finally { released = System.nanoTime() }
            }
        } finally {
            if (held) runCatching {
                val stat = stats.getValue(kind); val now = nowMs()
                stat.time("${use.event}Wait", (acquired - began) / 1_000_000, now)
                stat.time("${use.event}Hold", (released - acquired) / 1_000_000, now)
            }
        }
    }

    // A read's work after its lock is released (#771): DECRYPT opens the
    // record, DECODE parses it into the caller's result.
    fun <T> measure(kind: StoreLockKind, step: StoreReadStep, action: () -> T): T {
        val began = System.nanoTime()
        try { return action() } finally {
            runCatching { stats.getValue(kind).time(step.event, (System.nanoTime() - began) / 1_000_000, nowMs()) }
        }
    }

    fun drain(): List<String> = nowMs().let { now -> stats.values.mapNotNull { it.drain(now) } }
}
