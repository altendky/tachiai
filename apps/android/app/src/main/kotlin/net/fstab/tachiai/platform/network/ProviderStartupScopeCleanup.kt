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

// Only retired scopes are registered. Healthy active scopes do not block an
// independent new owner. An unconfirmed retirement is retained and never retried
// to manufacture a successful second close receipt.
internal class ProviderStartupCleanupRegistry(private val worker: Executor, private val nowElapsedMs: () -> Long) {
    private class Retired(val identity: Any, val resource: ProviderStartupCleanupResource) {
        var poisoned = false
    }
    private val lock = Object()
    private val retired = mutableMapOf<String, MutableList<Retired>>()

    fun retire(instanceId: String, scope: ProviderRouteStartup) = retire(instanceId, scope,
        object : ProviderStartupCleanupResource {
            override fun cancel() = scope.cancel()
            override fun close() = scope.close()
            override val cleanupConfirmed get() = scope.cleanupConfirmed
        }, scope::observeCleanupFailure)

    internal fun retire(instanceId: String, resource: ProviderStartupCleanupResource) = retire(instanceId, resource, resource)

    private fun retire(instanceId: String, identity: Any, resource: ProviderStartupCleanupResource,
        observeFailure: ((() -> Unit) -> Unit)? = null) {
        require(validProviderInstanceId(instanceId))
        val record = synchronized(lock) {
            val records = retired.getOrPut(instanceId) { mutableListOf() }
            if (records.any { it.identity === identity }) return
            Retired(identity, resource).also { records += it; lock.notifyAll() }
        }
        observeFailure?.invoke { poison(instanceId, record) }
        // Register before signalling, so a replacement worker cannot pass the
        // gate between lifecycle cancellation and ownership of the old scope.
        if (runCatching { resource.cancel() }.isFailure) poison(instanceId, record)
        try {
            worker.execute {
                val confirmed = runCatching { resource.close(); resource.cleanupConfirmed }.getOrDefault(false)
                synchronized(lock) {
                    if (!confirmed) record.poisoned = true
                    if (!record.poisoned) {
                        retired[instanceId]?.let { records ->
                            records.remove(record)
                            if (records.isEmpty()) retired.remove(instanceId)
                        }
                    }
                    lock.notifyAll()
                }
            }
        } catch (_: Throwable) { poison(instanceId, record) }
    }

    private fun poison(instanceId: String, record: Retired) = synchronized(lock) {
        record.poisoned = true
        val records = retired.getOrPut(instanceId) { mutableListOf() }
        if (records.none { it === record }) records += record
        lock.notifyAll()
    }

    // Memory-only gate suitable for existing request/lifecycle predicates.
    fun isReady(instanceId: String): Boolean = synchronized(lock) { retired[instanceId].isNullOrEmpty() }
    fun isPoisoned(instanceId: String): Boolean = synchronized(lock) { retired[instanceId]?.any { it.poisoned } == true }

    // Worker only. A pending close does not create an unbounded wait, and
    // cancellation never abandons the strongly owned retirement or its worker.
    fun awaitReady(instanceId: String, absoluteDeadline: Long, canUse: () -> Boolean): ProviderStartupCleanupAdmission {
        require(validProviderInstanceId(instanceId))
        while (true) {
            if (!runCatching(canUse).getOrDefault(false)) return ProviderStartupCleanupAdmission.CANCELLED
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
    private val registry = ProviderStartupCleanupRegistry(worker, SystemClock::elapsedRealtime)
    fun retire(instanceId: String, scope: ProviderRouteStartup) = registry.retire(instanceId, scope)
    fun isReady(instanceId: String) = registry.isReady(instanceId)
    fun isPoisoned(instanceId: String) = registry.isPoisoned(instanceId)
    fun awaitReady(instanceId: String, absoluteDeadline: Long, canUse: () -> Boolean) =
        registry.awaitReady(instanceId, absoluteDeadline, canUse)
}
