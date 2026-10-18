package net.fstab.tachiai.provider.abema.account

import org.junit.Assert.*
import org.junit.Test

class AbemaRenewalProbeProtocolTest {
    private val fields = mapOf("instance" to "00000000-0000-4000-8000-000000000001",
        "proof" to "a".repeat(64), "nonce" to "b".repeat(32), "deadline" to 91_000L)

    @Test fun sameUidBoundRequestHasOnlyTheExistingAccountPageAndNeverResetsTheProfile() {
        val request = checkNotNull(AbemaRenewalProbeProtocol.decode(fields, 10, 10, 1_000))
        assertEquals("https://abema.tv/account", request.verificationUri)
        assertTrue(request.automaticReturn)
        assertFalse(request.resetBrowserSession)
        assertEquals(91_000L, request.deadlineElapsedMs)
        assertEquals("AbemaApprovalBrowserRequest(redacted)", request.toString())
    }

    @Test fun malformedForeignStaleOrUnboundedRequestsCannotInitializeABrowser() {
        assertNull(AbemaRenewalProbeProtocol.decode(fields, 11, 10, 1_000))
        assertNull(AbemaRenewalProbeProtocol.decode(fields, -1, -1, 1_000))
        for (changed in listOf(fields + ("deadline" to 91_001L), fields + ("deadline" to 1_000L),
            fields + ("deadline" to "91000"), fields + ("nonce" to "invalid"),
            fields + ("instance" to "invalid"), fields + ("proof" to "invalid"),
            fields + ("uri" to "https://abema.tv/account"), fields - "nonce")) {
            assertNull(AbemaRenewalProbeProtocol.decode(changed, 10, 10, 1_000))
        }
    }
}
