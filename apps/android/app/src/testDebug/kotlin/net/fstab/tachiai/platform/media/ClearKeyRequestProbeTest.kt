package net.fstab.tachiai.platform.media

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ClearKeyRequestProbeTest {
    private val kids = listOf(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val encoded = "AAAAAAAAAAAAAAAAAAAAAQ"
    private fun metadata(text: String) = clearKeyRequestMetadata(text.toByteArray(), kids)

    @Test fun `standard request reports only closed shape and input agreement`() {
        val result = metadata("{\"kids\":[\"$encoded\"],\"type\":\"temporary\"}")
        assertEquals(RequestShape.STANDARD_KIDS_JSON, result.shape)
        assertEquals(RequestKidMatch.SAME_SET, result.kidMatch)
        assertEquals(RequestSessionType.TEMPORARY, result.sessionType)
        assertEquals(1, result.kidCount)
        assertFalse(result.toString().contains(encoded))
        assertFalse(result.toString().contains(kids[0].toString()))
    }

    @Test fun `field order whitespace and optional type are recognized`() {
        assertEquals(RequestShape.STANDARD_KIDS_JSON,
            metadata(" { \"type\" : \"temporary\", \"kids\" : [ \"$encoded\" ] } ").shape)
        assertEquals(RequestSessionType.ABSENT, metadata("{\"kids\":[\"$encoded\"]}").sessionType)
        assertEquals(RequestKidMatch.DIFFERENT_SET,
            metadata("{\"kids\":[\"AAAAAAAAAAAAAAAAAAAAAg\"]}").kidMatch)
    }

    @Test fun `malformed noncanonical or extra fields cannot leak through metadata`() {
        listOf("", "not json", "{\"kids\":[]}", "{\"kids\":[\"short\"]}",
            "{\"kids\":[\"AAAAAAAAAAAAAAAAAAAAAR\"]}",
            "{\"kids\":[\"$encoded\",\"$encoded\"]}",
            "{\"kids\":[\"$encoded\"],\"type\":\"persistent-license\"}",
            "{\"kids\":[\"$encoded\"],\"unknown\":\"private fixture\"}",
            "{\"kids\":[\"$encoded\"],\"type\":\"temporary\",\"type\":\"temporary\"}",
            "{\u000c\"kids\":[\"$encoded\"]}", "{\u00a0\"kids\":[\"$encoded\"]}",
            "{\"kids\":[\"$encoded\"]} trailing").forEach { text ->
            val result = metadata(text)
            assertEquals(if (text.isEmpty()) RequestShape.EMPTY else RequestShape.OTHER, result.shape)
            assertEquals(RequestKidMatch.UNAVAILABLE, result.kidMatch)
            assertFalse(result.toString().contains("private fixture"))
        }
    }

    @Test fun `invalid UTF8 and oversized requests have bounded closed results`() {
        assertEquals(RequestShape.OTHER, clearKeyRequestMetadata(byteArrayOf(0xc0.toByte(), 0xaf.toByte()), kids).shape)
        assertEquals(RequestMetadata(RequestShape.OVERSIZED, -1), clearKeyRequestMetadata(ByteArray(16385), kids))
        val seventeen = List(17) { "\"$encoded\"" }.joinToString(",")
        assertEquals(RequestShape.OTHER, metadata("{\"kids\":[$seventeen]}").shape)
    }

    private class Cdm(
        private val opened: () -> Unit = {},
        private val described: () -> Unit = {},
        private val closeError: Boolean = false,
    ) : RequestOnlyCdm {
        val calls = mutableListOf<String>()
        override fun openSession() { calls += "open"; opened() }
        override fun describeRequest(initialization: ByteArray, expectedKids: List<UUID>): RequestMetadata {
            calls += "request"; described()
            return RequestMetadata(RequestShape.STANDARD_KIDS_JSON, 56, 1)
        }
        override fun closeSession() { calls += "session-close"; if (closeError) throw IllegalStateException("private fixture") }
        override fun close() { calls += "cdm-close" }
    }

    private fun run(cdm: Cdm, active: () -> Boolean = { true }): RequestProbeResult {
        val initialization = byteArrayOf(1, 2, 3)
        val result = prepareRequestOnly(initialization, kids, active) { cdm }
        assertArrayEquals(ByteArray(3), initialization)
        return result
    }

    @Test fun `success closes session then CDM before returning metadata`() {
        val cdm = Cdm()
        assertEquals(RequestProbeOutcome.REQUEST_PREPARED, run(cdm).outcome)
        assertEquals(listOf("open", "request", "session-close", "cdm-close"), cdm.calls)
    }

    @Test fun `unsupported and provisioning are terminal without any network capability`() {
        assertEquals(RequestProbeOutcome.CDM_UNSUPPORTED,
            prepareRequestOnly(byteArrayOf(1), kids, { true }) {
                throw RequestProbeUnavailable(RequestProbeOutcome.CDM_UNSUPPORTED)
            }.outcome)
        val cdm = Cdm(opened = { throw RequestProbeUnavailable(RequestProbeOutcome.NOT_PROVISIONED) })
        assertEquals(RequestProbeOutcome.NOT_PROVISIONED, run(cdm).outcome)
        assertEquals(listOf("open", "cdm-close"), cdm.calls)
    }

    @Test fun `failed request and failed session clean only acquired resources`() {
        val sessionFailure = Cdm(opened = { throw IllegalStateException("private fixture") })
        assertEquals(RequestProbeOutcome.SESSION_FAILED, run(sessionFailure).outcome)
        assertEquals(listOf("open", "cdm-close"), sessionFailure.calls)
        val requestFailure = Cdm(described = { throw IllegalStateException("private fixture") })
        val result = run(requestFailure)
        assertEquals(RequestProbeOutcome.REQUEST_FAILED, result.outcome)
        assertNull(result.metadata)
        assertFalse(result.toString().contains("private fixture"))
        assertEquals(listOf("open", "request", "session-close", "cdm-close"), requestFailure.calls)
    }

    @Test fun `cancellation before creation after open and after request cannot return metadata`() {
        val untouched = Cdm()
        assertEquals(RequestProbeOutcome.CANCELLED, run(untouched) { false }.outcome)
        assertTrue(untouched.calls.isEmpty())
        var active = true
        val afterOpen = Cdm(opened = { active = false })
        assertEquals(RequestProbeOutcome.CANCELLED, run(afterOpen) { active }.outcome)
        assertEquals(listOf("open", "session-close", "cdm-close"), afterOpen.calls)
        active = true
        val afterRequest = Cdm(described = { active = false })
        val result = run(afterRequest) { active }
        assertEquals(RequestProbeOutcome.CANCELLED, result.outcome)
        assertNull(result.metadata)
        assertEquals(listOf("open", "request", "session-close", "cdm-close"), afterRequest.calls)
    }

    @Test fun `cleanup failure still releases CDM and suppresses success`() {
        val cdm = Cdm(closeError = true)
        val result = run(cdm)
        assertEquals(RequestProbeOutcome.CLEANUP_FAILED, result.outcome)
        assertNull(result.metadata)
        assertEquals(listOf("open", "request", "session-close", "cdm-close"), cdm.calls)
    }
}
