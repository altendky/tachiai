package net.fstab.tachiai.platform.network

import java.io.IOException

// One owner per playback run. Hooks only signal cancellation; they must never
// wait for workers, join native threads or free handles. Cleanup stays with the
// worker/backend. Remove a hook after initialization, before ownership transfers
// to a ready backend, and before freeing its handle. Removal excludes an in-flight
// hook before its handle is freed.
internal class RoutePreparation {
    private class Hook(val signal: () -> Unit)
    private val lock = Any()
    private var cancelled = false
    private var failed = false
    private val hooks = linkedSetOf<Hook>()
    val cleanupConfirmed: Boolean get() = synchronized(lock) { !failed }

    fun recordCleanupFailure() = synchronized(lock) { failed = true; cancel() }

    fun checkActive() = synchronized(lock) {
        if (cancelled) throw IOException("Route preparation was cancelled")
    }

    fun onCancel(signal: () -> Unit): AutoCloseable = synchronized(lock) {
        if (cancelled) {
            if (runCatching(signal).isFailure) failed = true
            throw IOException("Route preparation was cancelled")
        }
        val hook = Hook(signal)
        hooks.add(hook)
        AutoCloseable { synchronized(lock) { hooks.remove(hook) } }
    }

    // Serialize bounded signal delivery with removal, so after registration
    // close returns the callback can no longer access its native handle.
    fun cancel(): Boolean = synchronized(lock) {
        if (!cancelled) {
            cancelled = true
            val pending = hooks.toList()
            hooks.clear()
            pending.forEach { if (runCatching(it.signal).isFailure) failed = true }
        }
        !failed
    }
}
