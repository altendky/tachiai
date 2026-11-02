package net.fstab.tachiai.provider.abema.account

import android.net.Network
import java.net.InetAddress
import javax.net.SocketFactory
import net.fstab.tachiai.platform.network.RouteNetworkEgress
import org.junit.Assert.*
import org.junit.Test

class AbemaRenewalBrowserProtocolTest {
    private val fields = mapOf("instance" to "00000000-0000-4000-8000-000000000001",
        "proof" to "a".repeat(64), "nonce" to "b".repeat(32), "deadline" to 91_000L)
    private fun request() = checkNotNull(AbemaRenewalBrowserProtocol.decodeRequest(fields, 10, 10, 1_000))

    // JVM mockable SDK identity only. This does not exercise Android Parcelable or a real Network.
    private fun network(): Network = Network::class.java.getDeclaredConstructor()
        .apply { isAccessible = true }.newInstance()

    private class Egress : RouteNetworkEgress {
        override val socketFactory get() = SocketFactory.getDefault()
        override fun checkActive() = Unit
        override fun isActive() = true
        override fun resolveAddresses(host: String, timeoutMillis: Long): List<InetAddress> = error("No owned fixture DNS")
        override fun resolve(host: String?, timeoutMillis: Long): String = error("No owned fixture DNS")
        override fun bindSocket(fd: Int) = error("No owned fixture binding")
        override fun cancel() = Unit
        override fun isCleanupConfirmed() = true
        override fun reportCleanupUnconfirmed() = Unit
        override fun closeOwnedSockets() = Unit
    }

    @Test fun legacyStartKeepsItsExactFourFieldsAndAllocatesNoNetworkScope() {
        val start = checkNotNull(AbemaRenewalBrowserProtocol.decodeStart(fields, 10, 10, 1_000))
        assertNull(start.network)
        assertEquals(fields, AbemaRenewalBrowserProtocol.startFields(start))
        assertNull(start.egressFactory { _, _ -> error("Legacy mode must remain unchanged") })
    }

    @Test fun boundStartCapturesTypedNetworkAndDoesNotChangeWhenItsInputMapChanges() {
        val selected = network()
        val input = (fields + mapOf("bound" to true, "network" to selected)).toMutableMap()
        val start = checkNotNull(AbemaRenewalBrowserProtocol.decodeStart(input, 10, 10, 1_000))
        input["network"] = network(); input["deadline"] = 92_000L; input["bound"] = false
        assertSame(selected, start.network)
        assertEquals(91_000L, start.request.deadlineElapsedMs)
        val encoded = AbemaRenewalBrowserProtocol.startFields(start)
        assertEquals(fields.keys + setOf("bound", "network"), encoded.keys)
        assertSame(selected, encoded["network"])
        assertEquals(true, encoded["bound"])
        assertEquals("AbemaRenewalBrowserStart(redacted)", start.toString())
    }

    @Test fun partialWrongTypeUnknownUidAndStaleBoundStartsCannotFallBackToLegacy() {
        val selected = network()
        val valid = fields + mapOf("bound" to true, "network" to selected)
        for (changed in listOf(
            fields + ("bound" to true), fields + ("network" to selected),
            valid + ("bound" to false), valid + ("bound" to "true"), valid + ("bound" to 1),
            valid + ("network" to null), valid + ("network" to "private-network-id"),
            valid + ("network" to 4L), valid + ("extra" to true), valid - "nonce",
            valid + ("deadline" to 91_001L), valid + ("deadline" to 1_000L),
        )) assertNull(AbemaRenewalBrowserProtocol.decodeStart(changed, 10, 10, 1_000))
        assertNull(AbemaRenewalBrowserProtocol.decodeStart(valid, 11, 10, 1_000))
        assertNull(AbemaRenewalBrowserProtocol.decodeStart(valid, -1, -1, 1_000))
        assertNull(AbemaRenewalBrowserProtocol.decodeStart(valid, 10, 10, 91_000))
        assertNull(AbemaRenewalBrowserProtocol.decodeStart(valid, 10, 10, -1))
    }

    @Test fun boundStartFactoryIsDeferredFreshAndUsesOnlyOriginalAbsoluteDeadline() {
        val selected = network()
        val start = checkNotNull(AbemaRenewalBrowserProtocol.decodeStart(
            fields + mapOf("bound" to true, "network" to selected), 10, 10, 1_000))
        val created = mutableListOf<Egress>()
        val factory = checkNotNull(start.egressFactory { actual, deadline ->
            assertSame(selected, actual)
            assertEquals(91_000_000_000L, deadline)
            Egress().also(created::add)
        })
        assertTrue(created.isEmpty())
        val first = factory(); val second = factory()
        assertNotSame(first, second)
        assertEquals(2, created.size)
    }

    @Test fun boundDeadlineConversionRefusesOverflowWithoutChangingLegacyScalarAdmission() {
        val deadline = Long.MAX_VALUE / 1_000_000 + 1
        val scalar = fields + ("deadline" to deadline)
        assertNotNull(AbemaRenewalBrowserProtocol.decodeRequest(scalar, 10, 10, deadline - 1_000))
        assertNull(AbemaRenewalBrowserProtocol.decodeStart(
            scalar + mapOf("bound" to true, "network" to network()), 10, 10, deadline - 1_000))
    }

    @Test fun boundStartDoesNotAddNetworkAuthorityToCancellationOrResultIdentity() {
        val start = checkNotNull(AbemaRenewalBrowserProtocol.decodeStart(
            fields + mapOf("bound" to true, "network" to network()), 10, 10, 1_000))
        assertEquals(fields, AbemaRenewalBrowserProtocol.requestFields(start.request))
        assertTrue(AbemaRenewalBrowserProtocol.matches(fields, 10, 10, start.request))
        assertFalse(AbemaRenewalBrowserProtocol.matches(AbemaRenewalBrowserProtocol.startFields(start), 10, 10, start.request))
        val diagnostic = AbemaRenewalDiagnostic(cleanup = AbemaRenewalDiagnostic.Cleanup.CONFIRMED)
        val response = AbemaRenewalBrowserProtocol.resultFields(start.request, AbemaRenewalBrowserResult.DELIVERED, diagnostic)
        assertFalse(response.containsKey("network")); assertFalse(response.containsKey("bound"))
        assertEquals(AbemaRenewalBrowserResult.DELIVERED,
            AbemaRenewalBrowserProtocol.decodeResult(response, 10, 10, start.request))
        assertNull(AbemaRenewalBrowserProtocol.decodeResult(response + ("network" to network()), 10, 10, start.request))
    }

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
            val diagnostic = AbemaRenewalDiagnostic(cleanup = if (result.cleanupConfirmed)
                AbemaRenewalDiagnostic.Cleanup.CONFIRMED else AbemaRenewalDiagnostic.Cleanup.UNCONFIRMED)
            val response = AbemaRenewalBrowserProtocol.resultFields(parsed, result, diagnostic)
            assertEquals(result, AbemaRenewalBrowserProtocol.decodeResult(response, 10, 10, parsed))
            assertEquals(diagnostic, AbemaRenewalBrowserProtocol.decodeOutcome(response, 10, 10, parsed)!!.diagnostic)
            assertEquals(result != AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED, result.cleanupConfirmed)
        }
        assertNull(AbemaRenewalBrowserProtocol.decodeResult(fields + ("result" to "INTERACTION_REQUIRED"), 10, 10, parsed))
        assertNull(AbemaRenewalBrowserProtocol.decodeResult(fields + ("result" to 1), 10, 10, parsed))
        assertNull(AbemaRenewalBrowserProtocol.decodeResult(fields + ("result" to "DELIVERED"), 11, 10, parsed))
    }

    @Test fun resultDiagnosticsAreClosedSameEnvelopeAndCannotSmugglePrivateTextOrContradictCleanup() {
        val parsed = request()
        val diagnostic = AbemaRenewalDiagnostic(AbemaRenewalDiagnostic.Phase.REGION, AbemaRenewalDiagnostic.Reason.REGION_FAILURE,
            AbemaRenewalDiagnostic.Progress.PAGE_COMMITTED, region = AbemaApprovalRegionFailure.TRANSPORT_FAILED,
            cleanup = AbemaRenewalDiagnostic.Cleanup.CONFIRMED)
        val response = AbemaRenewalBrowserProtocol.resultFields(parsed, AbemaRenewalBrowserResult.TEMPORARY_FAILURE, diagnostic)
        assertEquals(diagnostic, AbemaRenewalBrowserProtocol.decodeOutcome(response, 10, 10, parsed)!!.diagnostic)
        for (changed in listOf(response - "diagnosticReason", response + ("diagnosticReason" to "PRIVATE_PROVIDER_ERROR"),
            response + ("diagnosticTransportCancelled" to "false"), response + ("error" to "PRIVATE_TOKEN"),
            response + ("nonce" to "d".repeat(32)), response + ("proof" to "c".repeat(64)),
            response + ("instance" to "00000000-0000-4000-8000-000000000002"),
            response + ("deadline" to 91_001L), response + ("diagnosticCleanup" to "UNCONFIRMED"),
            response + ("result" to "DELIVERED"))) {
            assertNull(AbemaRenewalBrowserProtocol.decodeOutcome(changed, 10, 10, parsed))
        }
        assertNull(AbemaRenewalBrowserProtocol.decodeOutcome(response, 11, 10, parsed))
        assertFalse(diagnostic.toString().contains(parsed.emailNonce!!))
        assertFalse(diagnostic.toString().contains(parsed.ownerProof))
    }
}
