package net.fstab.tachiai.platform.network

import java.io.IOException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import routebridge.Engine

// One transport lending loopback leases. Each lease keeps its own port,
// credentials, realm and allowed hosts; closing it leaves the transport up.
internal interface RouteEngine : AutoCloseable {
    fun lease(security: RouteProxySecurity): RouteBackend
}

internal class NativeRouteEngine(private val engine: Engine) : RouteEngine {
    override fun lease(security: RouteProxySecurity): RouteBackend = NativeRouteBackend(
        engine.lease(security.username, security.password, security.realm, security.allowedHosts))
    override fun close() = engine.close()
    override fun toString() = "NativeRouteEngine(secrets hidden)"
}

// Process-wide: at most one engine per conflict identity (for WireGuard, the
// key pair), because two engines with one key displace each other's session at
// the peer. Leases of the same configuration share it; an idle engine retires
// after idleMs. A different configuration with the same identity waits for
// the old engine to retire. Unconfirmed engine cleanup blocks the identity in
// this process instead of allowing a second engine.
internal class RouteEngineOwner(
    private val idleMs: Long = 30_000,
    private val schedule: (Long, () -> Unit) -> Unit = { delay, action ->
        retirements.schedule(action, delay, TimeUnit.MILLISECONDS)
    },
) {
    companion object {
        private val retirements = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "route-engine-retire").apply { isDaemon = true }
        }
        val shared = RouteEngineOwner()
    }
    private class Entry(val configuration: String, val engine: RouteEngine) {
        var leases = 0
        var generation = 0L
        var poisoned = false
    }
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private val gates = mutableMapOf<String, Any>()

    private fun gate(conflict: String) = synchronized(lock) { gates.getOrPut(conflict) { Any() } }

    fun lease(configuration: String, conflict: String, security: RouteProxySecurity,
        create: () -> RouteEngine): RouteBackend = synchronized(gate(conflict)) {
        val existing = synchronized(lock) { entries[conflict] }
        val (poisoned, leases) = synchronized(lock) { (existing?.poisoned == true) to (existing?.leases ?: 0) }
        if (poisoned) throw IOException("Route cleanup unconfirmed")
        val reusable = if (existing != null && existing.configuration != configuration) {
            if (leases > 0) throw IOException("Conflicting route in use")
            retire(conflict, existing)
            null
        } else existing
        val active = reusable ?: Entry(configuration, create()).also { created -> synchronized(lock) { entries[conflict] = created } }
        synchronized(lock) { active.leases++; active.generation++ }
        val backend = try { active.engine.lease(security) } catch (error: Throwable) { release(conflict, active); throw error }
        // Close once; repeated calls report the first outcome.
        val closed = lazy { runCatching { backend.close() }.also { release(conflict, active) } }
        object : RouteBackend {
            override val proxyPort get() = backend.proxyPort
            override fun close() { closed.value.getOrThrow() }
            override fun toString() = "SharedRouteLease(secrets hidden)"
        }
    }

    private fun release(conflict: String, entry: Entry) {
        val generation = synchronized(lock) {
            entry.leases--
            if (entry.leases > 0) return
            entry.generation
        }
        schedule(idleMs) { retireIfIdle(conflict, entry, generation) }
    }

    private fun retireIfIdle(conflict: String, entry: Entry, generation: Long) = synchronized(gate(conflict)) {
        val idle = synchronized(lock) {
            entries[conflict] === entry && entry.leases == 0 && entry.generation == generation && !entry.poisoned
        }
        // A failure is recorded as poison; the next lease reports it.
        if (idle) runCatching { retire(conflict, entry) }
    }

    // Caller holds the identity gate and the entry has no leases.
    private fun retire(conflict: String, entry: Entry) {
        val closed = runCatching { entry.engine.close() }.isSuccess
        synchronized(lock) { if (closed) entries.remove(conflict) else entry.poisoned = true }
        if (!closed) throw IOException("Route cleanup unconfirmed")
    }

    // Test/diagnostic view of currently held engines.
    internal fun engineCount() = synchronized(lock) { entries.size }

    override fun toString() = "RouteEngineOwner(secrets hidden)"
}
