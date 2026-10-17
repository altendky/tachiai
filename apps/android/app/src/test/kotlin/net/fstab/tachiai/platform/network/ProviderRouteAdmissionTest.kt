package net.fstab.tachiai.platform.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test

class ProviderRouteAdmissionTest {
    private val first = "a".repeat(64)
    private val second = "b".repeat(64)

    @Test fun aStandaloneBindingPinsFirstCandidateAndCannotReselectAfterAnObservedPoolEdit() {
        val original = defaultProviderInstances().first().copy(setup = ProviderSetup(SourceRouteChoice.system))
        var instance = original
        fun owner(expected: String?) = captureProviderRoutePool(instance.id, instance.service,
            { listOf(instance) }, { emptyMap() }).resolve(expected).proof
        val admission = ProviderRouteAdmission()
        assertTrue(admission.check(readOwnerProof = ::owner))
        val captured = checkNotNull(admission.ownerProof)
        instance = original.copy(setup = ProviderSetup(SourceRouteChoice.system, fallbackRoutes = listOf(
            SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, "12345678-1234-1234-1234-123456789abc", "Unused alternative"))))
        assertFalse(admission.check(readOwnerProof = ::owner))
        instance = original
        assertFalse(admission.check(readOwnerProof = ::owner))
        assertEquals(captured, admission.ownerProof)
        assertTrue(ProviderRouteAdmission().check(readOwnerProof = ::owner))
    }

    @Test fun foregroundPauseAndLossDuringSuccessfulReadDoNotRevokeThePinnedRoute() {
        val admission = ProviderRouteAdmission(first)
        var active = false; var reads = 0
        assertFalse(admission.check({ active }) { reads++; error("Must not read while paused") })
        assertEquals(0, reads); assertTrue(admission.canContinue())
        active = true
        assertFalse(admission.check({ active }) { expected -> reads++; assertEquals(first, expected); active = false; first })
        assertTrue(admission.canContinue()); assertEquals(first, admission.ownerProof)
        active = true
        assertTrue(admission.check({ active }) { first })
    }

    @Test fun actualMismatchOrReadFailureRemainsRevokedEvenWhenItCoincidesWithPause() {
        for (observed in listOf(null, second, "invalid")) {
            val admission = ProviderRouteAdmission(first)
            var active = true
            assertFalse(admission.check({ active }) { active = false; observed })
            assertFalse(admission.canContinue())
            active = true
            assertFalse(admission.check({ active }) { error("A revoked binding must not read again") })
        }
        val admission = ProviderRouteAdmission(first)
        assertFalse(admission.check { error("Synthetic unreadable configuration") })
        assertFalse(admission.canContinue())
        assertFalse(admission.check { first })
    }

    @Test fun lateMatchingReadCannotPublishAfterAnotherReadObservesRevocation() {
        val admission = ProviderRouteAdmission(first)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var accepted = true
        val worker = thread {
            accepted = admission.check { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); first }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(admission.check { second })
        } finally { release.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive); assertFalse(accepted); assertFalse(admission.canContinue())
        assertEquals("ProviderRouteAdmission(redacted)", admission.toString())
    }

    @Test fun competingFirstCapturesCannotAdoptTwoDifferentCandidates() {
        val admission = ProviderRouteAdmission()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var accepted = true
        val worker = thread {
            accepted = admission.check { expected ->
                assertNull(expected); entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); first
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(admission.check { expected -> assertNull(expected); second })
        } finally { release.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive); assertFalse(accepted)
        assertEquals(second, admission.ownerProof); assertFalse(admission.canContinue())
    }
}
