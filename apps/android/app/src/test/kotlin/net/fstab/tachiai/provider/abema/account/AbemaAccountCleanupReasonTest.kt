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

    @Test fun cleanupLinesCarryOnlyClosedNames() {
        assertEquals("accountHttp=CLEANUP_UNCONFIRMED stream=THREW disconnect=OK", accountHttpCleanupLine("THREW", "OK"))
        assertEquals("cachedCleanup=UNCONFIRMED part=SLOT_PROFILE_READ", cachedCleanupLine(CachedCleanupPart.SLOT_PROFILE_READ))
        // Every part has its own journal category.
        assertEquals(CachedCleanupPart.entries.size, CachedCleanupPart.entries.map { it.category }.distinct().size)
        assertTrue(CachedCleanupPart.entries.all { it.category.name == "CACHED_CLEANUP_" + it.name })
        assertTrue(FailureCategory.entries.any { it.name == "CACHED_CLEANUP_SLOT_PROFILE_READ" })
    }
}
