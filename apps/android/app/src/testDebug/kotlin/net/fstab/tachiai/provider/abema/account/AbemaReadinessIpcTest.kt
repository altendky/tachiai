package net.fstab.tachiai.provider.abema.account

import org.junit.Assert.*
import org.junit.Test

class AbemaReadinessIpcTest {
    private val nonce = "a".repeat(32)
    private val instance = "00000000-0000-0000-0000-000000000001"
    private fun request() = mapOf<String, Any?>("nonce" to nonce, "instance" to instance,
        "sequence" to 1L, "deadline" to 90100L)
    @Test fun sameUidExactBoundedRequestAdmitsWithoutCredentialFields() {
        val accepted = checkNotNull(AbemaReadinessIpc.decode(request(), 42, 42, 100))
        assertEquals(instance, accepted.instance); assertEquals(90100L, accepted.deadline)
        for ((sender, own) in listOf(-1 to 42, 42 to -1, 41 to 42))
            assertNull(AbemaReadinessIpc.decode(request(), sender, own, 100))
        assertNull(AbemaReadinessIpc.decode(request() + ("bearer" to "synthetic-private"), 42, 42, 100))
    }
    @Test fun malformedTypesIdentitiesSequencesAndDeadlineRefuseBeforeProtectedLookup() {
        for ((key, value) in listOf("nonce" to "b".repeat(33), "nonce" to "A".repeat(32),
            "instance" to "other", "instance" to false, "sequence" to 1, "sequence" to 0L,
            "deadline" to 90101L, "deadline" to 100L, "deadline" to "90100"))
            assertNull(AbemaReadinessIpc.decode(request() + (key to value), 42, 42, 100))
        assertNull(AbemaReadinessIpc.decode(request() - "instance", 42, 42, 100))
        assertNull(AbemaReadinessIpc.decode(request(), 42, 42, -1))
    }
    @Test fun responseRequiresCurrentNonceSequenceClosedStatusAndOnlyLaunchMayCarryTicket() {
        fun fields(status: String) = mapOf<String, Any?>("nonce" to nonce, "sequence" to 1L, "status" to status)
        for (status in listOf("READY", "WAIT", "FAILURE", "TEMPORARY_FAILURE", "INTERACTION_REQUIRED", "STORAGE_FAILURE")) {
            assertEquals(status, checkNotNull(AbemaReadinessIpc.decodeResponse(fields(status), nonce, 1)).status.name)
            assertNull(AbemaReadinessIpc.decodeResponse(fields(status) + ("ticket" to nonce), nonce, 1))
        }
        val launch = fields("LAUNCH") + ("ticket" to "b".repeat(32))
        assertEquals("b".repeat(32), checkNotNull(AbemaReadinessIpc.decodeResponse(launch, nonce, 1)).ticket)
        assertNull(AbemaReadinessIpc.decodeResponse(launch, "c".repeat(32), 1))
        assertNull(AbemaReadinessIpc.decodeResponse(launch, nonce, 2))
        assertNull(AbemaReadinessIpc.decodeResponse(fields("LAUNCH"), nonce, 1))
        assertNull(AbemaReadinessIpc.decodeResponse(launch + ("ticket" to "secret"), nonce, 1))
        assertNull(AbemaReadinessIpc.decodeResponse(launch + ("grant" to "secret"), nonce, 1))
        assertNull(AbemaReadinessIpc.decodeResponse(fields("SUCCESS"), nonce, 1))
    }
}
