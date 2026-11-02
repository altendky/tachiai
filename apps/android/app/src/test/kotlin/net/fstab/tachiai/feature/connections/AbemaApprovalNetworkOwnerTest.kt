package net.fstab.tachiai.feature.connections

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import net.fstab.tachiai.platform.network.RouteNetworkEgress
import org.junit.Assert.*
import org.junit.Test

class AbemaApprovalNetworkOwnerTest {
    private class Egress(
        private val checking: () -> Unit = {},
        private val closing: () -> Unit = {},
    ) : RouteNetworkEgress {
        @Volatile var active = true
        @Volatile var clean = true
        val cancels = AtomicInteger()
        val closes = AtomicInteger()
        val reports = AtomicInteger()
        override val socketFactory get() = SocketFactory.getDefault()
        override fun checkActive() { checking(); if (!active) throw IOException("Owned egress inactive") }
        override fun isActive() = active
        override fun resolveAddresses(host: String, timeoutMillis: Long): List<InetAddress> = error("No owned fixture DNS")
        override fun resolve(host: String?, timeoutMillis: Long): String = error("No owned fixture DNS")
        override fun bindSocket(fd: Int) = error("No owned fixture binding")
        override fun cancel() { cancels.incrementAndGet(); active = false }
        override fun isCleanupConfirmed() = clean
        override fun reportCleanupUnconfirmed() { reports.incrementAndGet(); clean = false; active = false }
        override fun closeOwnedSockets() { closes.incrementAndGet(); closing() }
    }

    private fun await(latch: CountDownLatch) {
        assertTrue("Owned fixture checkpoint not reached", latch.await(2, TimeUnit.SECONDS))
    }

    @Test fun actualPublishedScopeHasOneMemoizedCloseAndCannotBeReplaced() {
        val owner = AbemaApprovalNetworkOwner()
        val egress = Egress()
        assertSame(egress, owner.create { egress })
        assertThrows(IOException::class.java) { owner.create { error("No second scope") } }
        owner.cancel()
        assertFalse(egress.active)
        assertTrue(owner.closeConfirmed())
        assertTrue(owner.closeConfirmed())
        assertEquals(1, egress.closes.get())
        assertEquals(0, egress.reports.get())
        assertEquals("AbemaApprovalNetworkOwner(redacted)", owner.toString())
    }

    @Test fun legacyOwnerWithoutAnyNetworkFactoryPerformsNoNetworkWork() {
        val owner = AbemaApprovalNetworkOwner()
        owner.cancel()
        assertTrue(owner.closeConfirmed())
        assertTrue(owner.closeConfirmed())
        assertThrows(IOException::class.java) { owner.create { error("No legacy network allocation") } }
    }

    @Test fun publicationPrecedesCurrentnessCheckSoCancellationCanRetireBeforeBackendAllocation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = AbemaApprovalNetworkOwner()
        val egress = Egress(checking = { entered.countDown(); await(release) })
        val backends = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<IOException> {
                assertThrows(IOException::class.java) { owner.create { egress }; backends.incrementAndGet() }
            }
            await(entered)
            owner.cancel()
            assertEquals(1, egress.cancels.get())
            assertFalse(pending.isDone)
            release.countDown()
            assertEquals("Approval network refused", pending.get(1, TimeUnit.SECONDS).message)
            assertEquals(0, backends.get())
            assertTrue(owner.closeConfirmed())
            assertEquals(1, egress.closes.get())
        } finally { owner.cancel(); release.countDown(); worker.shutdownNow() }
    }

    @Test fun cancelledPendingFactoryRetainsAndRetiresItsLateScopeWithoutConstructingBackend() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = AbemaApprovalNetworkOwner()
        val egress = Egress()
        val backends = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<IOException> {
                assertThrows(IOException::class.java) {
                    owner.create { entered.countDown(); await(release); egress }
                    backends.incrementAndGet()
                }
            }
            await(entered)
            owner.cancel()
            assertFalse(owner.closeConfirmed())
            release.countDown()
            val refusal = pending.get(1, TimeUnit.SECONDS)
            assertEquals("Approval network refused", refusal.message); assertNull(refusal.cause)
            assertEquals(0, backends.get()); assertFalse(egress.active); assertFalse(egress.clean)
            assertFalse(owner.closeConfirmed())
            val retained = AbemaApprovalNetworkOwner::class.java.getDeclaredField("egress")
                .apply { isAccessible = true }.get(owner)
            assertSame(egress, retained)
        } finally { owner.cancel(); release.countDown(); worker.shutdownNow() }
    }

    @Test fun cancelledFactoryCanConfirmCleanupAfterItsActualOwnerJoinsBeforeTheFirstVerdict() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = AbemaApprovalNetworkOwner()
        val egress = Egress()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<IOException> {
                assertThrows(IOException::class.java) {
                    owner.create { entered.countDown(); await(release); egress }
                }
            }
            await(entered); owner.cancel(); release.countDown()
            assertEquals("Approval network refused", pending.get(1, TimeUnit.SECONDS).message)
            assertFalse(egress.active)
            assertTrue(owner.closeConfirmed())
            assertEquals(1, egress.closes.get()); assertEquals(0, egress.reports.get())
        } finally { owner.cancel(); release.countDown(); worker.shutdownNow() }
    }

    @Test fun cleanupRefusalIsStickyEvenWhenAnActualScopeLaterReportsClean() {
        val owner = AbemaApprovalNetworkOwner()
        val egress = Egress().apply { clean = false }
        owner.create { egress }
        assertFalse(owner.closeConfirmed())
        assertEquals(1, egress.closes.get())
        assertTrue(egress.reports.get() > 0)
        egress.clean = true
        assertFalse(owner.closeConfirmed())
        assertEquals(1, egress.closes.get())
    }

    @Test fun regionOrPartialRouteCleanupFailureRetiresActualScopeWithoutPretendingClosure() {
        val owner = AbemaApprovalNetworkOwner()
        val egress = Egress()
        owner.create { egress }
        assertFalse(owner.refuseCleanup())
        assertFalse(egress.active); assertFalse(egress.clean)
        assertFalse(owner.closeConfirmed())
        assertEquals(0, egress.closes.get())
        val retained = AbemaApprovalNetworkOwner::class.java.getDeclaredField("egress")
            .apply { isAccessible = true }.get(owner)
        assertSame(egress, retained)
    }

    @Test fun aConcurrentCloseCannotUpgradeAnIncompleteFirstClose() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = AbemaApprovalNetworkOwner()
        val egress = Egress(closing = { entered.countDown(); await(release) })
        owner.create { egress }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<Boolean> { owner.closeConfirmed() }
            await(entered)
            owner.cancel()
            assertFalse(owner.closeConfirmed())
            assertFalse(pending.isDone)
            release.countDown()
            assertFalse(pending.get(1, TimeUnit.SECONDS))
            assertFalse(owner.closeConfirmed())
            assertEquals(1, egress.closes.get())
        } finally { owner.cancel(); release.countDown(); worker.shutdownNow() }
    }

    @Test fun constructionAndCloseExceptionsNeverExposeTheirPrivateTextOrBecomeSuccessfulRetries() {
        val failedFactory = AbemaApprovalNetworkOwner()
        val refusal = assertThrows(IOException::class.java) {
            failedFactory.create { throw IOException("https://private.test/?grant=secret") }
        }
        assertEquals("Approval network refused", refusal.message); assertNull(refusal.cause)
        assertTrue(failedFactory.closeConfirmed()) // No actual scope was returned/allocated by this fixture.
        val owner = AbemaApprovalNetworkOwner()
        val egress = Egress(closing = { throw IOException("private-close-message") })
        owner.create { egress }
        assertFalse(owner.closeConfirmed())
        assertFalse(owner.closeConfirmed())
        assertEquals(1, egress.closes.get())
    }
}
