package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.platform.diagnostics.FailureCategory
import net.fstab.tachiai.provider.abema.CachedCleanupPart
import net.fstab.tachiai.provider.abema.cachedCleanupLine
import org.junit.Assert.*
import org.junit.Test

// #783: an unconfirmed cleanup names its closed reason, never a value.
class AbemaAccountCleanupReasonTest {
    @Test fun aReceiptNamesWhyItIsUnconfirmed() {
        val threw = AbemaAccountCleanupReceipt(Any(), { error("PRIVATE") })
        assertFalse(threw.await()); assertEquals("THREW", threw.failureReason)
        val release = CountDownLatch(1)
        val slow = AbemaAccountCleanupReceipt(Any(), { release.await(2, TimeUnit.SECONDS) }, waitMs = 20)
        try { assertFalse(slow.await()); assertEquals("TIMEOUT", slow.failureReason) } finally { release.countDown() }
        val fine = AbemaAccountCleanupReceipt(Any(), { })
        assertTrue(fine.await()); assertNull(fine.failureReason)
    }

    // #783 on the emulator: two feeds stopping at once each close a stream and
    // disconnect together. Blocked closes must not refuse the others.
    @Test fun concurrentBlockedClosesAllRunAndConfirmInsteadOfBeingRefused() {
        val release = CountDownLatch(1)
        val running = CountDownLatch(8)
        val receipts = List(8) { AbemaAccountCleanupReceipt(Any(), { running.countDown(); release.await(2, TimeUnit.SECONDS) }) }
        receipts.forEach { it.start() }
        assertTrue("every close runs at once", running.await(1, TimeUnit.SECONDS))
        release.countDown()
        assertTrue(receipts.all { it.await() })
        assertTrue(receipts.all { it.failureReason == null })
    }

    // A stream close that waits for its disconnect still confirms when both
    // start together, as Handle.cleanup starts them.
    @Test fun aStreamCloseBlockedUntilItsDisconnectConfirmsWithIt() {
        val disconnected = CountDownLatch(1)
        val stream = AbemaAccountCleanupReceipt(Any(), { check(disconnected.await(1, TimeUnit.SECONDS)) })
        val disconnect = AbemaAccountCleanupReceipt(Any(), { disconnected.countDown() })
        stream.start(); disconnect.start()
        assertTrue(stream.await()); assertTrue(disconnect.await())
    }

    @Test fun cleanupLinesCarryOnlyClosedNames() {
        assertEquals("accountHttp=CLEANUP_UNCONFIRMED stream=THREW disconnect=OK", accountHttpCleanupLine("THREW", "OK"))
        assertEquals("cachedCleanup=UNCONFIRMED part=SLOT_PROFILE_READ", cachedCleanupLine(CachedCleanupPart.SLOT_PROFILE_READ))
        // Every part has its own journal category.
        assertEquals(CachedCleanupPart.entries.size, CachedCleanupPart.entries.map { it.category }.distinct().size)
        assertTrue(CachedCleanupPart.entries.all { it.category.name == "CACHED_CLEANUP_" + it.name })
        assertTrue(FailureCategory.entries.any { it.name == "CACHED_CLEANUP_SLOT_PROFILE_READ" })
    }
}
