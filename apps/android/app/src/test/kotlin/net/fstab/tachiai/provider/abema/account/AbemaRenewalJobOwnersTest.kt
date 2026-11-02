package net.fstab.tachiai.provider.abema.account

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AbemaRenewalJobOwnersTest {
    private class Owner { var cancels = 0 }
    private val first = "00000000-0000-0000-0000-000000000001"
    private val second = "00000000-0000-0000-0000-000000000002"
    private fun key(instance: String = first, schedule: String = first, id: Int = AbemaRenewalJobLedger.JOB_IDS.first()) =
        AbemaRenewalJobKey(instance, schedule, id)
    private fun preflight(instance: String = first) = AbemaRenewalJobPreflight(
        AbemaReadinessTarget(instance, "a".repeat(64)), AbemaRenewalGeneration(1, 0, 10_000))

    @Test fun lateStopOrNetworkCallbackFromAnUnconsumedInvocationCannotReachItsRearm() {
        val registry = AbemaRenewalJobOwners<Owner>()
        val oldKey = key(); val old = Owner()
        assertTrue(registry.register(oldKey, old)); assertTrue(registry.remove(oldKey, old))
        val nextKey = key(schedule = second); val next = Owner()
        assertTrue(registry.register(nextKey, next))
        registry.callback(oldKey)?.let { it.cancels++ }
        assertEquals(0, next.cancels); assertSame(next, registry.callback(nextKey))
        assertFalse(registry.register(oldKey, Owner()))
    }

    @Test fun oldFinishAndAttachmentCannotRemoveOrChangeAReplacementOwner() {
        val registry = AbemaRenewalJobOwners<Owner>(); val key = key(); val old = Owner(); val next = Owner()
        registry.register(key, old); registry.remove(key, old); registry.register(key, next)
        registry.attach(key, old, preflight())
        assertFalse(registry.remove(key, old))
        registry.reconcile(emptySet(), { null }) { it.cancels++ }
        assertEquals(0, next.cancels); assertSame(next, registry.callback(key))
    }

    @Test fun unrelatedProviderAndDisplayChangesLeaveTheSameProtectedSourceOwnerAlone() {
        val registry = AbemaRenewalJobOwners<Owner>(); val owner = Owner(); val key = key()
        registry.register(key, owner); registry.attach(key, owner, preflight())
        registry.reconcile(setOf(first, second), { preflight(it) }) { it.cancels++ }
        assertEquals(0, owner.cancels)
        registry.reconcile(setOf(first), { preflight(it) }) { it.cancels++ }
        assertEquals(0, owner.cancels)
    }

    @Test fun changedRouteGenerationRemovalOrUnreadabilityRetiresOnlyTheAffectedOwner() {
        for (mode in 0..3) {
            val registry = AbemaRenewalJobOwners<Owner>(); val one = Owner(); val two = Owner()
            val firstKey = key(); val secondKey = key(second, second, firstKey.jobId + 1)
            registry.register(firstKey, one); registry.attach(firstKey, one, preflight())
            registry.register(secondKey, two); registry.attach(secondKey, two, preflight(second))
            registry.reconcile(if (mode == 2) setOf(second) else setOf(first, second), { instance ->
                if (instance == second) preflight(second) else when (mode) {
                    0 -> AbemaRenewalJobPreflight(AbemaReadinessTarget(first, "b".repeat(64)), preflight().generation)
                    1 -> AbemaRenewalJobPreflight(preflight().target, AbemaRenewalGeneration(2, 10_001, 20_000))
                    else -> throw IOException("Fixture protected read refused")
                }
            }) { it.cancels++ }
            assertEquals(1, one.cancels); assertEquals(0, two.cancels)
        }
    }

    @Test fun staleProtectedRecheckCannotRetireTheOwnerThatReplacedItsSnapshot() {
        val registry = AbemaRenewalJobOwners<Owner>(); val old = Owner(); val next = Owner(); val oldKey = key()
        registry.register(oldKey, old); registry.attach(oldKey, old, preflight())
        val nextKey = key(schedule = second)
        registry.reconcile(setOf(first), {
            registry.remove(oldKey, old); registry.register(nextKey, next); registry.attach(nextKey, next, preflight())
            null
        }) { it.cancels++ }
        assertEquals(0, old.cancels); assertEquals(0, next.cancels); assertSame(next, registry.callback(nextKey))
    }
}
