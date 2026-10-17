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

    private fun continuous() = AbemaAccountAccessIpc.Request(request.nonce, request.instance, request.proof,
        request.source, 31000L, playback = true, continuousPlayback = true)

    @Test fun continuousPlaybackIsExplicitAndRetainsAFiniteStartupEnvelope() {
        val chosen = continuous()
        val fields = AbemaAccountAccessIpc.base(chosen, 1)
        assertEquals(7, base.size)
        assertEquals(8, fields.size)
        assertTrue(AbemaAccountAccessIpc.request(fields, 1000)!!.continuousPlayback)
        assertFalse(AbemaAccountAccessIpc.request(base, 1000)!!.continuousPlayback)
        for (bad in listOf(fields + ("playback" to false), fields + ("continuous" to false),
            fields + ("continuous" to "true"), fields + ("deadline" to Long.MAX_VALUE),
            fields + ("deadline" to 301001L), fields + ("deadline" to 1000L)))
            assertNull(AbemaAccountAccessIpc.request(bad, 1000))
        assertNull(AbemaAccountAccessIpc.request(fields, 31000))
    }

    @Test fun onlyAnAcquiredContinuousPlaybackSessionOutlivesItsStartupDeadline() {
        val chosen = continuous()
        assertTrue(AbemaAccountAccessIpc.sessionCurrent(chosen, 1000, 30999, acquired = false))
        assertFalse(AbemaAccountAccessIpc.sessionCurrent(chosen, 1000, 31000, acquired = false))
        assertTrue(AbemaAccountAccessIpc.sessionCurrent(chosen, 1000, 601000, acquired = true))
        assertFalse(AbemaAccountAccessIpc.sessionCurrent(chosen, 1000, 999, acquired = true))
        assertFalse(AbemaAccountAccessIpc.sessionCurrent(chosen, -1, 1000, acquired = true))
        assertFalse(AbemaAccountAccessIpc.sessionCurrent(request, 1000, request.deadline, acquired = true))
        val invalid = AbemaAccountAccessIpc.Request(chosen.nonce, chosen.instance, chosen.proof, chosen.source,
            chosen.deadline, playback = false, continuousPlayback = true)
        assertFalse(AbemaAccountAccessIpc.sessionCurrent(invalid, 1000, 601000, acquired = true))
    }

    @Test fun continuousChecksGetFreshBoundedDeadlinesWhileAcquisitionStaysBoundedByStartup() {
        val chosen = continuous()
        assertEquals(31000L, AbemaAccountAccessIpc.operationDeadline(chosen, 2000, acquired = false, limitMs = 30000))
        assertNull(AbemaAccountAccessIpc.operationDeadline(chosen, 31000, acquired = false, limitMs = 30000))
        assertEquals(606000L, AbemaAccountAccessIpc.operationDeadline(chosen, 601000, acquired = true, limitMs = 5000))
        assertNull(AbemaAccountAccessIpc.operationDeadline(request, 601000, acquired = true, limitMs = 5000))
        assertNull(AbemaAccountAccessIpc.operationDeadline(chosen, Long.MAX_VALUE - 4999, acquired = true, limitMs = 5000))
        assertNull(AbemaAccountAccessIpc.operationDeadline(chosen, -1, acquired = true, limitMs = 5000))
        assertNull(AbemaAccountAccessIpc.operationDeadline(chosen, 1000, acquired = true, limitMs = 30001))
    }

    @Test fun continuousResultPinsItsModeAndStillRejectsAnExpiredProviderGrant() {
        val chosen = continuous()
        val fields = AbemaAccountAccessIpc.base(chosen, 2) + mapOf("status" to "ACCOUNT", "userId" to "account",
            "bearer" to token("{\"sub\":\"account\",\"exp\":2000,\"dev\":\"private-device\"}"),
            "expiry" to 2000000L, "deviceId" to "private-device")
        assertEquals(13, fields.size)
        assertEquals("private-device", AbemaAccountAccessIpc.result(fields, chosen, 2, 601000)?.deviceId)
        assertNull(AbemaAccountAccessIpc.result(fields, chosen, 2, 2000000))
        for (bad in listOf(fields - "continuous", fields + ("continuous" to false),
            fields + ("playback" to false), fields + ("source" to "c".repeat(64)),
            fields + ("proof" to "c".repeat(64))))
            assertNull(AbemaAccountAccessIpc.result(bad, chosen, 2, 1000))
        val finite = AbemaAccountAccessIpc.Request(chosen.nonce, chosen.instance, chosen.proof, chosen.source,
            chosen.deadline, playback = true)
        assertNull(AbemaAccountAccessIpc.result(fields, finite, 2, 1000))
        assertEquals(AbemaAccountAccessIpc.Status.GUEST,
            AbemaAccountAccessIpc.result(AbemaAccountAccessIpc.base(chosen, 2) + ("status" to "GUEST"), chosen, 2, 601000)?.status)
    }
}
