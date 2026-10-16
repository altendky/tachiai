package net.fstab.tachiai.provider.abema.account

import org.junit.Assert.*
import org.junit.Test

class AbemaReadinessTargetsTest {
    private val first = AbemaReadinessTarget("00000000-0000-0000-0000-000000000001", "a".repeat(64))
    private val second = AbemaReadinessTarget("12345678-1234-1234-1234-123456789abc", "b".repeat(64))

    @Test fun duplicateFeedsCoalesceExactOwnershipButConflictingCandidatesRejectTheWholeBatch() {
        assertEquals(listOf(first, second), normalizedAbemaReadinessTargets(listOf(first, second, first)))
        assertNull(normalizedAbemaReadinessTargets(listOf(first, first.copy(ownerProof = "c".repeat(64)))))
        assertEquals(emptyList<AbemaReadinessTarget>(), normalizedAbemaReadinessTargets(emptyList()))
        assertEquals("AbemaReadinessTarget(redacted)", first.toString())
    }

    @Test fun malformedOrOversizedTargetsNeverTurnIntoUnscopedInstanceChecks() {
        for (target in listOf(first.copy(instanceId = "other"), first.copy(ownerProof = ""),
            first.copy(ownerProof = "A".repeat(64)), first.copy(ownerProof = "a".repeat(65))))
            assertNull(normalizedAbemaReadinessTargets(listOf(first, target)))
        assertNull(normalizedAbemaReadinessTargets(List(33) { first }))
        val draft = mutableListOf(first, second)
        val normalized = normalizedAbemaReadinessTargets(draft)
        draft.clear()
        assertEquals(listOf(first, second), normalized)
    }
}
