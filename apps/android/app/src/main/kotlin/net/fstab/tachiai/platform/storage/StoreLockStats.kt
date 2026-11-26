package net.fstab.tachiai.platform.storage

import net.fstab.tachiai.platform.diagnostics.OperationStats

// The shared owner stores whose locks route and account checks contend for (#745).
internal enum class StoreLockKind { PROVIDER_INSTANCES, CONNECTION_PROFILES }

// READ decrypts a record; REVISION reads only its envelope; WRITE commits one.
internal enum class StoreLockUse(val event: String) { READ("read"), REVISION("revision"), WRITE("write") }

// Process-wide wait (until both the monitor and file lock are held) and hold
// times per store and use. Counts and milliseconds only.
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

    fun drain(): List<String> = nowMs().let { now -> stats.values.mapNotNull { it.drain(now) } }
}
