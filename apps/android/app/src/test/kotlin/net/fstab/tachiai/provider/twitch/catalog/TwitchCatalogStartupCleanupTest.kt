package net.fstab.tachiai.provider.twitch.catalog

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.platform.network.*
import org.junit.Assert.*
import org.junit.Test

class TwitchCatalogStartupCleanupTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private class Queue : Executor {
        val jobs = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { jobs.addLast(command) }
    }
    private class Resource(val confirms: Boolean = true) : ProviderStartupCleanupResource {
        var closes = 0
        override fun cancel() {}
        override fun close() { closes++ }
        override val cleanupConfirmed get() = confirms
    }

    @Test fun acceptedActionCannotAllocateTransportUntilRetiredScopeCloses() {
        val queue = Queue(); val registry = ProviderStartupCleanupRegistry(queue) { 100 }
        val retired = Resource(); registry.retire(id, retired)
        val waiting = CountDownLatch(1); val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { awaitTwitchCatalogStartupCleanup(id, 1000,
                { waiting.countDown(); true }, registry::isReady, registry::awaitReady) }
            assertTrue(waiting.await(2, TimeUnit.SECONDS)); assertFalse(result.isDone); assertEquals(0, retired.closes)
            queue.jobs.removeFirst().run()
            assertTrue(result.get(2, TimeUnit.SECONDS)); assertEquals(1, retired.closes)
        } finally { worker.shutdownNow() }
    }

    @Test fun cancelledStartupExitsItsOwnRetirementRatherThanWaitingForItsCleanupWorker() {
        val queue = Queue(); val registry = ProviderStartupCleanupRegistry(queue) { 100 }
        val retired = Resource(); registry.retire(id, retired)
        val waiting = CountDownLatch(1); val current = AtomicBoolean(true); val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { awaitTwitchCatalogStartupCleanup(id, 1000,
                { waiting.countDown(); current.get() }, registry::isReady, registry::awaitReady) }
            assertTrue(waiting.await(2, TimeUnit.SECONDS)); current.set(false)
            assertFalse(result.get(2, TimeUnit.SECONDS)); assertEquals(0, retired.closes)
            queue.jobs.removeFirst().run(); assertEquals(1, retired.closes)
        } finally { current.set(false); worker.shutdownNow() }
    }

    @Test fun uncertainCleanupAndPostWaitRevocationCannotAdmitAnotherBackend() {
        val queue = Queue(); val registry = ProviderStartupCleanupRegistry(queue) { 100 }
        registry.retire(id, Resource(false)); queue.jobs.removeFirst().run()
        assertFalse(awaitTwitchCatalogStartupCleanup(id, 1000, { true }, registry::isReady, registry::awaitReady))
        var current = true
        assertFalse(awaitTwitchCatalogStartupCleanup(id, 1000, { current }, { false }, { _, _, _ ->
            current = false; ProviderStartupCleanupAdmission.READY
        }))
    }
}
