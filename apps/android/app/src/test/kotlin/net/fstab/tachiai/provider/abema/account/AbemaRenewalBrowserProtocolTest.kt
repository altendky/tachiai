package net.fstab.tachiai.provider.abema.account

import org.junit.Assert.*
import org.junit.Test

class AbemaRenewalBrowserProtocolTest {
    private val fields = mapOf("instance" to "00000000-0000-4000-8000-000000000001",
        "proof" to "a".repeat(64), "nonce" to "b".repeat(32), "deadline" to 91_000L)
    private fun request() = checkNotNull(AbemaRenewalBrowserProtocol.decodeRequest(fields, 10, 10, 1_000))

    @Test fun requestIsSameUidBoundAndCannotChooseOriginOrResetRememberedSession() {
        val parsed = request()
        assertEquals("https://abema.tv/account", parsed.verificationUri)
        assertTrue(parsed.automaticReturn)
        assertFalse(parsed.resetBrowserSession)
        assertTrue(AbemaRenewalBrowserProtocol.accepts(parsed, 1_000))
        for (changed in listOf(fields + ("uri" to "https://auth.abema.tv/"), fields + ("automaticReturn" to false),
            fields + ("resetBrowserSession" to true), fields - "nonce", fields + ("deadline" to "91000"),
            fields + ("deadline" to 91_000), fields + ("deadline" to 91_001L), fields + ("deadline" to 1_000L),
            fields + ("deadline" to Long.MAX_VALUE), fields + ("instance" to "invalid"),
            fields + ("proof" to "invalid"), fields + ("nonce" to "invalid"))) {
            assertNull(AbemaRenewalBrowserProtocol.decodeRequest(changed, 10, 10, 1_000))
        }
        assertNull(AbemaRenewalBrowserProtocol.decodeRequest(fields, 11, 10, 1_000))
        assertNull(AbemaRenewalBrowserProtocol.decodeRequest(fields, -1, -1, 1_000))
        assertNull(AbemaRenewalBrowserProtocol.decodeRequest(fields, 10, 10, -1))
        assertFalse(AbemaRenewalBrowserProtocol.accepts(AbemaApprovalBrowserRequest(
            request().instanceId, request().ownerProof, "https://abema.tv/account", 91_000,
            request().emailNonce), 1_000))
        assertFalse(AbemaRenewalBrowserProtocol.accepts(AbemaApprovalBrowserRequest(
            request().instanceId, request().ownerProof, "https://abema.tv/", 91_000), 1_000))
        assertFalse(AbemaRenewalBrowserProtocol.accepts(AbemaApprovalBrowserRequest(
            request().instanceId, request().ownerProof, "https://abema.tv/account", 91_000,
            request().emailNonce, resetBrowserSession = true), 1_000))
    }

    @Test fun cancellationAndResultMatchEveryCapturedFieldWithoutRenewingDeadline() {
        val parsed = request()
        assertTrue(AbemaRenewalBrowserProtocol.matches(fields, 10, 10, parsed))
        for (changed in listOf(fields + ("instance" to "00000000-0000-4000-8000-000000000002"),
            fields + ("proof" to "c".repeat(64)), fields + ("nonce" to "d".repeat(32)),
            fields + ("deadline" to 91_001L), fields - "nonce", fields + ("extra" to true))) {
            assertFalse(AbemaRenewalBrowserProtocol.matches(changed, 10, 10, parsed))
            assertNull(AbemaRenewalBrowserProtocol.decodeResult(changed + ("result" to "DELIVERED"), 10, 10, parsed))
        }
        for (result in AbemaRenewalBrowserResult.entries) {
            assertEquals(result, AbemaRenewalBrowserProtocol.decodeResult(fields + ("result" to result.name), 10, 10, parsed))
            assertEquals(result != AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED, result.cleanupConfirmed)
        }
        assertNull(AbemaRenewalBrowserProtocol.decodeResult(fields + ("result" to "INTERACTION_REQUIRED"), 10, 10, parsed))
        assertNull(AbemaRenewalBrowserProtocol.decodeResult(fields + ("result" to 1), 10, 10, parsed))
        assertNull(AbemaRenewalBrowserProtocol.decodeResult(fields + ("result" to "DELIVERED"), 11, 10, parsed))
    }
}
