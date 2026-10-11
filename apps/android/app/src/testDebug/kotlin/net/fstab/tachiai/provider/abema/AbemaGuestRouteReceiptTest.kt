package net.fstab.tachiai.provider.abema

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.platform.network.RouteSession
import org.junit.Assert.*
import org.junit.Test

class AbemaGuestRouteReceiptTest {
    @Test fun heldRouteCloseKeepsActualOwnerAndSecondaryCallerCannotConfirmOrRepeatCleanup() {
        val owned = RouteSession.createAbemaGuestSchedule(null)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closes = AtomicInteger()
        val receipt = AbemaGuestRouteReceipt { route ->
            closes.incrementAndGet(); entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); route.close()
        }
        receipt.own(owned)
        val worker = Thread { receipt.close() }
        try {
            worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertSame(owned, receipt.route.get()); assertFalse(receipt.cleanupConfirmed)
            receipt.close(); assertEquals(1, closes.get()); assertSame(owned, receipt.route.get()); assertFalse(receipt.cleanupConfirmed)
            release.countDown(); worker.join(5000); assertFalse(worker.isAlive)
            assertNull(receipt.route.get()); assertTrue(receipt.cleanupConfirmed); assertEquals(1, closes.get())
        } finally { release.countDown(); worker.join(5000); owned.close() }
    }
    @Test fun systemRouteFailureRetainsActualOwnerAndNeverBecomesSuccessfulOnRepeatedClose() {
        val owned = RouteSession.createAbemaGuestSchedule(null); var closes = 0
        val receipt = AbemaGuestRouteReceipt { closes++; throw AssertionError("Fixture cleanup failure") }
        try {
            receipt.own(owned)
            assertThrows(AssertionError::class.java) { receipt.close() }
            assertSame(owned, receipt.route.get()); assertFalse(receipt.cleanupConfirmed)
            receipt.close(); assertEquals(1, closes); assertSame(owned, receipt.route.get()); assertFalse(receipt.cleanupConfirmed)
        } finally { owned.close() }
    }
    @Test fun lateOwnershipAfterSealClosesOnlyOnceAndKeepsUnconfirmedRouteOnFailure() {
        for (fails in listOf(false, true)) {
            val owned = RouteSession.createAbemaGuestSchedule(null); var closes = 0
            val receipt = AbemaGuestRouteReceipt { route -> closes++; if (fails) throw AssertionError("Fixture late cleanup failure") else route.close() }
            try {
                receipt.close(); assertTrue(receipt.cleanupConfirmed)
                if (fails) assertThrows(AssertionError::class.java) { receipt.own(owned) } else receipt.own(owned)
                assertEquals(1, closes); assertEquals(!fails, receipt.cleanupConfirmed)
                if (fails) assertSame(owned, receipt.route.get()) else assertNull(receipt.route.get())
                receipt.close(); assertEquals(1, closes); assertEquals(!fails, receipt.cleanupConfirmed)
            } finally { owned.close() }
        }
    }
}
