package net.fstab.tachiai.provider.abema.account

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AbemaApprovalAutomaticReturnTest {
    @Test fun irrelevantSubresourceErrorsDoNotAbortAndOnlyCapturedEmailFailureIsCritical() {
        val endpoint = "https://api.p-c3-e.abema-tv.com/v1/users/synthetic/email"
        assertTrue(abemaAutomaticReturnFatalResource(true, "https://abema.tv/account", "GET", false, null))
        assertTrue(abemaAutomaticReturnFatalResource(false, endpoint, "GET", true, "synthetic"))
        for (url in listOf("https://abema.tv/favicon.ico", "https://image.p-c3-e.abema-tv.com/fixture",
            "https://gateway.p-c2-x.abema-tv.com/tracking", endpoint.replace("synthetic", "other"),
            endpoint + "?fixture=1", endpoint.replace("api.p-c3-e.abema-tv.com", "other.invalid")))
            assertFalse(abemaAutomaticReturnFatalResource(false, url, "GET", true, "synthetic"))
        assertFalse(abemaAutomaticReturnFatalResource(false, endpoint, "GET", false, "synthetic"))
        assertFalse(abemaAutomaticReturnFatalResource(false, endpoint, "GET", true, null))
        assertFalse(abemaAutomaticReturnFatalResource(false, endpoint, "POST", true, "synthetic"))
    }
    @Test fun passiveCaptureQueuesExactlyOnceAndNeedsMainClaimBeforeConfirmedDelivery() {
        val gate = AbemaApprovalAutomaticReturn()
        assertFalse(gate.deliveryAllowed(true))
        assertTrue(gate.queue(true, true, true))
        assertFalse(gate.queue(true, true, true)); assertFalse(gate.deliveryAllowed(true))
        assertTrue(gate.claim(true, true, true))
        assertFalse(gate.claim(true, true, true)); assertFalse(gate.queue(true, true, true))
        assertTrue(gate.deliveryAllowed(true))
        assertEquals("AbemaApprovalAutomaticReturn(redacted)", gate.toString())
    }
    @Test fun untrustedOrInactiveCaptureCannotScheduleAndQueuedStaleCandidateNeverReturns() {
        for (values in listOf(Triple(false, true, true), Triple(true, false, true), Triple(true, true, false))) {
            val unqueued = AbemaApprovalAutomaticReturn()
            assertFalse(unqueued.queue(values.first, values.second, values.third))
            assertFalse(unqueued.claim(true, true, true)); assertFalse(unqueued.deliveryAllowed(true))
            val queued = AbemaApprovalAutomaticReturn()
            assertTrue(queued.queue(true, true, true))
            assertFalse(queued.claim(values.first, values.second, values.third))
            assertFalse(queued.claim(true, true, true)); assertFalse(queued.queue(true, true, true))
            assertFalse(queued.deliveryAllowed(true))
        }
    }
    @Test fun cancellationAtEveryDispatchBoundaryBlocksLateClaimAndSuccess() {
        for (stage in 0..2) {
            val gate = AbemaApprovalAutomaticReturn()
            if (stage >= 1) assertTrue(gate.queue(true, true, true))
            if (stage >= 2) assertTrue(gate.claim(true, true, true))
            gate.cancel()
            assertFalse(gate.queue(true, true, true)); assertFalse(gate.claim(true, true, true))
            assertFalse(gate.deliveryAllowed(true))
        }
    }
    @Test fun firstUncertainCleanupCannotBeLaunderedByLateSuccessOrRepeatedCapture() {
        val gate = AbemaApprovalAutomaticReturn()
        assertTrue(gate.queue(true, true, true)); assertTrue(gate.claim(true, true, true))
        assertFalse(gate.deliveryAllowed(false))
        assertFalse(gate.deliveryAllowed(true)); assertFalse(gate.queue(true, true, true))
        assertFalse(gate.claim(true, true, true))
    }

    @Test fun parallelIdenticalGrantCapturesSelectOneObjectAndReturnOnceWithoutReplacement() {
        val gate = AbemaApprovalAutomaticReturn()
        val first = AbemaAccountGrant("synthetic", "synthetic-bearer", 1000)
        val second = AbemaAccountGrant("synthetic", "synthetic-bearer", 1000)
        assertNotSame(first, second)
        val selected = AtomicReference<AbemaAccountGrant?>()
        val queued = AtomicInteger()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val workers = listOf(first, second).map { candidate -> Thread {
            try {
                assertTrue(start.await(2, TimeUnit.SECONDS))
                synchronized(gate) {
                    if (gate.queue(true, true, true)) { selected.set(candidate); queued.incrementAndGet() }
                }
            } finally { done.countDown() }
        }.apply { isDaemon = true } }
        try {
            workers.forEach { it.start() }; start.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(1, queued.get())
            val chosen = checkNotNull(selected.get())
            assertTrue(chosen === first || chosen === second)
            assertFalse(gate.queue(true, true, true)); assertSame(chosen, selected.get())
            assertTrue(gate.claim(true, true, selected.get() === chosen))
            assertTrue(gate.deliveryAllowed(true)); assertFalse(gate.claim(true, true, true))
        } finally { start.countDown(); workers.forEach { it.join(2000) } }
    }
}
