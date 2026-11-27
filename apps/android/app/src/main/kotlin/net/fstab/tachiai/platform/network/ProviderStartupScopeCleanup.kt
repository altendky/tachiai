package net.fstab.tachiai.platform.network

import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.presentation.validProviderInstanceId

internal enum class ProviderStartupCleanupAdmission { READY, CANCELLED, DEADLINE, CLEANUP_UNCONFIRMED }

internal interface ProviderStartupCleanupResource : AutoCloseable {
    fun cancel()
    val cleanupConfirmed: Boolean
}

// Closed cause of a retired scope's poison (#764), for diagnostics only.
internal enum class ProviderStartupCleanupPoison { CLOSE_UNCONFIRMED, SCOPE_UNCONFIRMED, CANCEL_FAILED, QUEUE_REJECTED, DEBUG_FAULT }

// Only retired scopes are registered. Healthy active scopes do not block an
// independent new owner. An unconfirmed retirement is retained and never retried
// to manufacture a successful second close receipt.
internal class ProviderStartupCleanupRegistry(private val worker: Executor,
    // Closed lines only (#764): a cause and counts, never an instance or route.
    private val log: (String) -> Unit = {},
    private val nowElapsedMs: () -> Long) {
    // #783: an instance a provider's own cleanup quarantined for this process,
    // such as an ABEMA account lease whose cleanup was not confirmed. A source
    // that throws counts as quarantined.
    @Volatile internal var quarantined: (String) -> Boolean = { false }
    private fun quarantinedNow(instanceId: String) = runCatching { quarantined(instanceId) }.getOrDefault(true)
    private class Retired(val identity: Any, val resource: ProviderStartupCleanupResource,
        val uncertainty: () -> ProviderStartupUncertainty?) {
        var poisoned = false
    }
    private val lock = Object()
    private val retired = mutableMapOf<String, MutableList<Retired>>()

    fun retire(instanceId: String, scope: ProviderRouteStartup) = retire(instanceId, scope,
        object : ProviderStartupCleanupResource {
            override fun cancel() = scope.cancel()
            override fun close() = scope.close()
            override val cleanupConfirmed get() = scope.cleanupConfirmed
        }, scope::observeCleanupFailure) { scope.cleanupUncertainty }

    internal fun retire(instanceId: String, resource: ProviderStartupCleanupResource) = retire(instanceId, resource, resource)

    // Debug fault only (#764): retain a synthetic poisoned retirement, as an
    // unconfirmed one would be, without touching any real scope.
    internal fun poisonForDebug(instanceId: String) {
        require(validProviderInstanceId(instanceId))
        val synthetic = object : ProviderStartupCleanupResource {
            override fun cancel() {}
            override fun close() {}
            override val cleanupConfirmed get() = false
        }
        poison(instanceId, Retired(synthetic, synthetic) { null }, ProviderStartupCleanupPoison.DEBUG_FAULT)
    }

    private fun retire(instanceId: String, identity: Any, resource: ProviderStartupCleanupResource,
        observeFailure: ((() -> Unit) -> Unit)? = null, uncertainty: () -> ProviderStartupUncertainty? = { null }) {
        require(validProviderInstanceId(instanceId))
        val record = synchronized(lock) {
            val records = retired.getOrPut(instanceId) { mutableListOf() }
            if (records.any { it.identity === identity }) return
            Retired(identity, resource, uncertainty).also { records += it; lock.notifyAll() }
        }
        observeFailure?.invoke { poison(instanceId, record, ProviderStartupCleanupPoison.SCOPE_UNCONFIRMED) }
        // Register before signalling, so a replacement worker cannot pass the
        // gate between lifecycle cancellation and ownership of the old scope.
        if (runCatching { resource.cancel() }.isFailure) poison(instanceId, record, ProviderStartupCleanupPoison.CANCEL_FAILED)
        try {
            worker.execute {
                val confirmed = runCatching { resource.close(); resource.cleanupConfirmed }.getOrDefault(false)
                if (!confirmed) poison(instanceId, record, ProviderStartupCleanupPoison.CLOSE_UNCONFIRMED)
                synchronized(lock) {
                    if (!record.poisoned) {
                        retired[instanceId]?.let { records ->
                            records.remove(record)
                            if (records.isEmpty()) retired.remove(instanceId)
                        }
                    }
                    lock.notifyAll()
                }
            }
        } catch (_: Throwable) { poison(instanceId, record, ProviderStartupCleanupPoison.QUEUE_REJECTED) }
    }

    private fun poison(instanceId: String, record: Retired, cause: ProviderStartupCleanupPoison) {
        // Read outside this monitor: the scope's own lock is never nested in it.
        val scope = runCatching(record.uncertainty).getOrNull()
        val line = synchronized(lock) {
            val first = !record.poisoned
            record.poisoned = true
            val records = retired.getOrPut(instanceId) { mutableListOf() }
            if (records.none { it === record }) records += record
            lock.notifyAll()
            // Only a retirement's first cause is logged.
            if (first) "startupCleanup=POISONED cause=${cause.name}" + (scope?.let { " scope=${it.name}" } ?: "") +
                " retired=${records.size} poisoned=${records.count { it.poisoned }}" else null
        }
        if (line != null) runCatching { log(line) }
    }

    // Memory-only gate suitable for existing request/lifecycle predicates.
    fun isReady(instanceId: String): Boolean = synchronized(lock) { retired[instanceId].isNullOrEmpty() }
    fun isPoisoned(instanceId: String): Boolean =
        synchronized(lock) { retired[instanceId]?.any { it.poisoned } == true } || quarantinedNow(instanceId)

    // Worker only. A pending close does not create an unbounded wait, and
    // cancellation never abandons the strongly owned retirement or its worker.
    fun awaitReady(instanceId: String, absoluteDeadline: Long, canUse: () -> Boolean): ProviderStartupCleanupAdmission {
        require(validProviderInstanceId(instanceId))
        while (true) {
            if (!runCatching(canUse).getOrDefault(false)) return ProviderStartupCleanupAdmission.CANCELLED
            if (quarantinedNow(instanceId)) return ProviderStartupCleanupAdmission.CLEANUP_UNCONFIRMED
            val admission = synchronized(lock) {
                val records = retired[instanceId]
                val remaining = absoluteDeadline - nowElapsedMs()
                when {
                    records?.any { it.poisoned } == true -> ProviderStartupCleanupAdmission.CLEANUP_UNCONFIRMED
                    remaining <= 0 -> ProviderStartupCleanupAdmission.DEADLINE
                    records.isNullOrEmpty() -> ProviderStartupCleanupAdmission.READY
                    else -> {
                        try { lock.wait(minOf(remaining, 50L)) }
                        catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return ProviderStartupCleanupAdmission.CANCELLED
                        }
                        null
                    }
                }
            }
            if (admission != null) {
                if (admission != ProviderStartupCleanupAdmission.READY) return admission
                if (!runCatching(canUse).getOrDefault(false)) return ProviderStartupCleanupAdmission.CANCELLED
                if (nowElapsedMs() >= absoluteDeadline) return ProviderStartupCleanupAdmission.DEADLINE
                if (isReady(instanceId)) return ProviderStartupCleanupAdmission.READY
            }
        }
    }
}

// Process-owned retirement survives Activity/composable replacement. Queue
// rejection retains an uncertain scope rather than forgetting its resources.
internal object ProviderStartupScopeCleanup {
    private val worker = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32),
        { task -> Thread(task, "provider-startup-cleanup").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val quarantineSources = java.util.concurrent.CopyOnWriteArrayList<(String) -> Boolean>()
    private val registry = ProviderStartupCleanupRegistry(worker, ::event, SystemClock::elapsedRealtime).also {
        it.quarantined = { id -> quarantineSources.any { source -> runCatching { source(id) }.getOrDefault(true) } }
    }
    // A provider registers its own process-lifetime cleanup quarantine (#783),
    // so the same gate and Restart notice cover it.
    fun addQuarantineSource(source: (String) -> Boolean) { quarantineSources.add(source) }
    // Closed gate lines only (#764); never an instance, route or provider value.
    internal fun event(text: String) { runCatching { android.util.Log.i("TachiaiStartupCleanup", text) } }
    fun retire(instanceId: String, scope: ProviderRouteStartup) {
        registry.retire(instanceId, scope)
        if (DebugStartupCleanupFaults.take()) registry.poisonForDebug(instanceId)
    }
    fun isReady(instanceId: String) = registry.isReady(instanceId)
    fun isPoisoned(instanceId: String) = registry.isPoisoned(instanceId)
    fun awaitReady(instanceId: String, absoluteDeadline: Long, canUse: () -> Boolean) =
        registry.awaitReady(instanceId, absoluteDeadline, canUse)
}
