package net.fstab.tachiai.provider.abema.account

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class AbemaAccountAccessIpcTest {
    private val request = AbemaAccountAccessIpc.Request("1".repeat(32), "12345678-1234-1234-1234-123456789abc",
        "a".repeat(64), "b".repeat(64), 301000, false)
    private val base get() = AbemaAccountAccessIpc.base(request, 1)
    private fun token(claims: String) = "e30." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(claims.toByteArray(Charsets.UTF_8)) + ".c2ln"
    private fun account(playback: Boolean = false): Pair<AbemaAccountAccessIpc.Request, Map<String, Any>> {
        val chosen = AbemaAccountAccessIpc.Request(request.nonce, request.instance, request.proof, request.source,
            request.deadline, playback)
        return chosen to (AbemaAccountAccessIpc.base(chosen, 2) + mapOf("status" to "ACCOUNT", "userId" to "account",
            "bearer" to token("{\"sub\":\"account\",\"exp\":2000,\"dev\":\"private-device\"}"), "expiry" to 2000000L) +
            if (playback) mapOf("deviceId" to "private-device") else emptyMap())
    }
    @Test fun requestRequiresExactTypedTupleAndOriginalFiniteDeadline() {
        assertNotNull(AbemaAccountAccessIpc.request(base, 1000))
        for (bad in listOf(base + ("extra" to "private"), base + ("sequence" to 1), base + ("sequence" to 0L),
            base + ("playback" to "false"), base + ("source" to "B".repeat(64)), base + ("proof" to "private"),
            base + ("nonce" to "other"), base + ("instance" to "other"), base + ("deadline" to 301001L),
            base + ("deadline" to 1000L))) assertNull(AbemaAccountAccessIpc.request(bad, 1000))
        assertNull(AbemaAccountAccessIpc.request(base, -1))
    }
    @Test fun accountResultPinsAllBindingsAndValidatesGrantWithoutPrivateStringOutput() {
        val (chosen, fields) = account()
        val parsed = AbemaAccountAccessIpc.result(fields, chosen, 2, 1000)!!
        assertEquals(AbemaAccountAccessIpc.Status.ACCOUNT, parsed.status)
        assertNotNull(parsed.grant); assertNull(parsed.deviceId)
        assertFalse(parsed.toString().contains("account")); assertFalse(parsed.toString().contains("private"))
        for (bad in listOf(fields + ("sequence" to 3L), fields + ("nonce" to "2".repeat(32)),
            fields + ("source" to "c".repeat(64)), fields + ("proof" to "c".repeat(64)),
            fields + ("deadline" to 301001L), fields + ("playback" to true), fields + ("userId" to "other"),
            fields + ("expiry" to 2000001L), fields + ("deviceId" to "private-device"), fields + ("cookie" to "private")))
            assertNull(AbemaAccountAccessIpc.result(bad, chosen, 2, 1000))
        assertNull(AbemaAccountAccessIpc.result(fields, chosen, 2, 2000000))
    }
    @Test fun playbackRequiresExactStrictDeviceClaimOnlyInPlaybackEnvelope() {
        val (chosen, fields) = account(true)
        assertEquals("private-device", AbemaAccountAccessIpc.result(fields, chosen, 2, 1000)?.deviceId)
        for (bad in listOf(fields - "deviceId", fields + ("deviceId" to "other"), fields +
            ("bearer" to token("{\"sub\":\"account\",\"exp\":2000}")), fields +
            ("bearer" to token("{\"sub\":\"account\",\"exp\":2000,\"dev\":\"one\",\"dev\":\"private-device\"}"))))
            assertNull(AbemaAccountAccessIpc.result(bad, chosen, 2, 1000))
    }
    @Test fun guestAndFailureHaveClosedDistinctSchemasNeverCredentialFallback() {
        val guest = base + ("status" to "GUEST")
        val failure = base + mapOf("status" to "FAILURE", "failure" to "EXPIRED")
        assertEquals(AbemaAccountAccessIpc.Status.GUEST, AbemaAccountAccessIpc.result(guest, request, 1, 1000)?.status)
        assertEquals(AbemaAccountFailure.EXPIRED, AbemaAccountAccessIpc.result(failure, request, 1, 1000)?.failure)
        for (bad in listOf(guest + ("bearer" to "private"), guest + ("failure" to "EXPIRED"),
            failure - "failure", failure + ("failure" to "UNKNOWN"), failure + ("bearer" to "private"),
            base + ("status" to "CONNECTED"))) assertNull(AbemaAccountAccessIpc.result(bad, request, 1, 1000))
    }
}
