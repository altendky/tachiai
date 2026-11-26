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
        engine.lease(security.username, security.password, security.realm, security.allowedHosts), security.purpose)
    override fun close() = engine.close()
    override fun toString() = "NativeRouteEngine(secrets hidden)"
}

// Process-wide: at most one engine per conflict identity (for WireGuard, the
// key pair), because two engines with one key displace each other's session at
// the peer. Leases of the same configuration share it; an idle engine retires
// after idleMs. A different configuration with the same identity waits for
// the old engine to retire. Unconfirmed engine cleanup blocks the identity in
// this process instead of allowing a second engine.
//
// Renewal-job leases (#275 stage 5) join whichever engine is held, even though
// a shared engine's outer traffic then uses the default network. Only when
// none is held does the job start its own engine, bound to the job's Network.
// That engine serves job leases only and retires as soon as its last one
// closes, because its Network is the job's. An ordinary lease requested while
// it runs retires it first, revoking the job's leases, rather than adopting
// it: an adopted feed would lose its route when the job's Network ends.
internal class RouteEngineOwner(
    private val idleMs: Long = 30_000,
    // Closed events and counts only; never a configuration, key or host.
    private val log: (String) -> Unit = {},
    private val schedule: (Long, () -> Unit) -> Unit = { delay, action ->
        retirements.schedule(action, delay, TimeUnit.MILLISECONDS)
    },
) {
    companion object {
        private val retirements = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "route-engine-retire").apply { isDaemon = true }
        }
        // Process role only (for example "main" or "prototype_cached_player").
        internal val role by lazy {
            runCatching { if (android.os.Build.VERSION.SDK_INT >= 28)
                android.app.Application.getProcessName().substringAfter(':', "main") else "unknown" }.getOrDefault("unknown")
                .takeIf { it.matches(Regex("[A-Za-z0-9_]{1,40}")) } ?: "unknown"
        }
        // Closed lifecycle events only, under this process's role.
        internal fun event(text: String) { runCatching { android.util.Log.i("TachiaiRouteEngine", "process=$role $text") } }
        val shared = RouteEngineOwner(log = ::event)
    }
    // Thrown by a job-bound engine factory whose failed start left native
    // cleanup unconfirmed; the identity is then blocked like a failed close.
    class CleanupUnconfirmed : IOException("Route cleanup unconfirmed")

    private class Entry(val configuration: String, val engine: RouteEngine, val job: Boolean = false) {
        var leases = 0
        var generation = 0L
        var poisoned = false
    }
    // Holds an identity whose failed job-bound start was not confirmed closed.
    private object Unconfirmed : RouteEngine {
        override fun lease(security: RouteProxySecurity): RouteBackend = throw IOException("Route cleanup unconfirmed")
        override fun close() = throw IOException("Route cleanup unconfirmed")
    }
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private val gates = mutableMapOf<String, Any>()

    private fun gate(conflict: String) = synchronized(lock) { gates.getOrPut(conflict) { Any() } }

    fun lease(configuration: String, conflict: String, security: RouteProxySecurity,
        create: () -> RouteEngine): RouteBackend = synchronized(gate(conflict)) {
        var existing = synchronized(lock) { entries[conflict] }
        if (synchronized(lock) { existing?.poisoned == true }) throw IOException("Route cleanup unconfirmed")
        existing?.takeIf { it.job }?.let { job ->
            retire(conflict, job, "LEASE_REQUESTED")
            existing = null
        }
        val held = existing
        val reusable = if (held != null && held.configuration != configuration) {
            if (synchronized(lock) { held.leases } > 0) throw IOException("Conflicting route in use")
            retire(conflict, held)
            null
        } else held
        val active = reusable ?: Entry(configuration, create()).also { created ->
            synchronized(lock) { entries[conflict] = created }
            log("ENGINE_STARTED engines=${engineCount()}")
        }
        open(conflict, active, security)
    }

    // A renewal job's lease: join the held engine, or start a job-bound one.
    fun leaseForJob(configuration: String, conflict: String, security: RouteProxySecurity,
        createBound: () -> RouteEngine): RouteBackend = synchronized(gate(conflict)) {
        val held = synchronized(lock) { entries[conflict] }
        if (synchronized(lock) { held?.poisoned == true }) {
            log("JOB_LEASE_REFUSED reason=CLEANUP_UNCONFIRMED")
            throw IOException("Route cleanup unconfirmed")
        }
        val reusable = if (held != null && held.configuration != configuration) {
            if (synchronized(lock) { held.leases } > 0) {
                log("JOB_LEASE_REFUSED reason=CONFLICT")
                throw IOException("Conflicting route in use")
            }
            retire(conflict, held)
            null
        } else held
        if (reusable != null) log("JOB_LEASE_JOINED engine=${if (reusable.job) "JOB" else "SHARED"}")
        val active = reusable ?: Entry(configuration, try { createBound() } catch (error: CleanupUnconfirmed) {
            synchronized(lock) { entries[conflict] = Entry(configuration, Unconfirmed, job = true).apply { poisoned = true } }
            log("ENGINE_CLEANUP_UNCONFIRMED")
            throw error
        }, job = true).also { created ->
            synchronized(lock) { entries[conflict] = created }
            log("JOB_ENGINE_STARTED engines=${engineCount()}")
        }
        open(conflict, active, security)
    }

    // Caller holds the identity gate.
    private fun open(conflict: String, active: Entry, security: RouteProxySecurity): RouteBackend {
        val openLeases = synchronized(lock) { active.leases++; active.generation++; active.leases }
        log("LEASE_OPENED leases=$openLeases")
        val backend = try { active.engine.lease(security) } catch (error: Throwable) {
            runCatching { release(conflict, active) }
            throw error
        }
        // Close once; repeated calls report the first outcome. The last lease
        // of a job-bound engine also reports that engine's retirement.
        val closed = lazy {
            val leased = runCatching { backend.close() }
            val released = runCatching { release(conflict, active) }
            if (leased.isFailure) leased else released
        }
        return object : RouteBackend {
            override val proxyPort get() = backend.proxyPort
            override fun close() { closed.value.getOrThrow() }
            override fun toString() = "SharedRouteLease(secrets hidden)"
        }
    }

    private fun release(conflict: String, entry: Entry) {
        val (generation, left, current) = synchronized(lock) {
            entry.leases--; Triple(entry.generation, entry.leases, entries[conflict] === entry)
        }
        // A lease of an engine already retired for an ordinary lease says so.
        log("LEASE_CLOSED leases=$left" + if (current) "" else " engine=RETIRED")
        if (left > 0) return
        if (!entry.job) { schedule(idleMs) { retireIfIdle(conflict, entry, generation) }; return }
        // A job-bound engine's Network ends with the job: retire it now. An
        // engine already retired for an ordinary lease needs no gate wait.
        if (!idle(conflict, entry, generation)) return
        synchronized(gate(conflict)) {
            if (idle(conflict, entry, generation)) retire(conflict, entry, "RELEASED")
        }
    }

    private fun idle(conflict: String, entry: Entry, generation: Long) = synchronized(lock) {
        entries[conflict] === entry && entry.leases == 0 && entry.generation == generation && !entry.poisoned
    }

    private fun retireIfIdle(conflict: String, entry: Entry, generation: Long) = synchronized(gate(conflict)) {
        // A failure is recorded as poison; the next lease reports it.
        if (idle(conflict, entry, generation)) runCatching { retire(conflict, entry) }
    }

    // Caller holds the identity gate. Only a job-bound engine is retired while
    // leases are open (LEASE_REQUESTED); closing it revokes them.
    private fun retire(conflict: String, entry: Entry, jobReason: String = "REPLACED") {
        val closed = runCatching { entry.engine.close() }.isSuccess
        synchronized(lock) { if (closed) entries.remove(conflict) else entry.poisoned = true }
        log(when {
            !closed -> "ENGINE_CLEANUP_UNCONFIRMED"
            entry.job -> "JOB_ENGINE_RETIRED reason=$jobReason engines=${engineCount()}"
            else -> "ENGINE_RETIRED engines=${engineCount()}"
        })
        if (!closed) throw IOException("Route cleanup unconfirmed")
    }

    // Test/diagnostic view of currently held engines.
    internal fun engineCount() = synchronized(lock) { entries.size }

    override fun toString() = "RouteEngineOwner(secrets hidden)"
}
