package net.fstab.tachiai.platform.media

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test

class OpaqueClearKeyExchangeTest {
    private val kids = listOf(UUID(0, 0))
    private val standard = "{\"kids\":[\"AAAAAAAAAAAAAAAAAAAAAA\"],\"type\":\"temporary\"}"

    private class Fake(private val challenge: ByteArray, private val fail: String = "") : OpaqueExchangeCdm {
        val calls = mutableListOf<String>()
        private fun call(name: String) { calls += name; if (fail == name) error("SECRET") }
        override fun openSession() = call("open")
        override fun request(initialization: ByteArray): ByteArray { call("request"); return challenge }
        override fun submit(response: ByteArray) = call("submit")
        override fun closeSession() = call("sessionClose")
        override fun close() = call("close")
    }

    @Test fun successUsesOneSessionAndWipesAllOwnedBytes() {
        val input = byteArrayOf(1)
        val challenge = standard.toByteArray()
        val response = byteArrayOf(2)
        val cdm = Fake(challenge)
        assertEquals(ExchangeOutcome.RESPONSE_ACCEPTED,
            exchangeClearKeyOnce(input, kids, { true }, { cdm }) { assertSame(challenge, it); response })
        assertEquals(listOf("open", "request", "submit", "sessionClose", "close"), cdm.calls)
        assertTrue(input.all { it == 0.toByte() })
        assertTrue(challenge.all { it == 0.toByte() })
        assertTrue(response.all { it == 0.toByte() })
    }

    @Test fun invalidAndDifferentRequestsNeverReachBroker() {
        for (request in listOf("SECRET", standard.replace("AAAAAAAAAAAAAAAAAAAAAA", "AQAAAAAAAAAAAAAAAAAAAA"))) {
            val cdm = Fake(request.toByteArray())
            assertEquals(ExchangeOutcome.REQUEST_REFUSED,
                exchangeClearKeyOnce(byteArrayOf(1), kids, { true }, { cdm }) { error("must not exchange") })
            assertFalse(cdm.calls.contains("submit"))
        }
    }

    @Test fun missingEmptyAndOversizedResponsesNeverReachCdm() {
        for (response in listOf(null, byteArrayOf(), ByteArray(65 * 1024))) {
            val cdm = Fake(standard.toByteArray())
            assertEquals(ExchangeOutcome.RESPONSE_REFUSED,
                exchangeClearKeyOnce(byteArrayOf(1), kids, { true }, { cdm }) { response })
            assertFalse(cdm.calls.contains("submit"))
            assertTrue(response?.all { it == 0.toByte() } ?: true)
        }
    }

    @Test fun cancellationAfterBrokerPreventsSubmissionAndStillCleansUp() {
        var active = true
        val cdm = Fake(standard.toByteArray())
        val response = byteArrayOf(2)
        assertEquals(ExchangeOutcome.CANCELLED,
            exchangeClearKeyOnce(byteArrayOf(1), kids, { active }, { cdm }) { active = false; response })
        assertFalse(cdm.calls.contains("submit"))
        assertEquals(listOf("sessionClose", "close"), cdm.calls.takeLast(2))
        assertEquals(0.toByte(), response[0])
    }

    @Test fun failuresRemainClosedAndCleanupAlwaysAttemptsRelease() {
        val stages = mapOf("open" to ExchangeOutcome.SESSION_FAILED, "request" to ExchangeOutcome.REQUEST_FAILED,
            "submit" to ExchangeOutcome.RESPONSE_REJECTED, "sessionClose" to ExchangeOutcome.CLEANUP_FAILED,
            "close" to ExchangeOutcome.CLEANUP_FAILED)
        for ((failure, expected) in stages) {
            val cdm = Fake(standard.toByteArray(), failure)
            assertEquals(expected,
                exchangeClearKeyOnce(byteArrayOf(1), kids, { true }, { cdm }) { byteArrayOf(2) })
            assertEquals("close", cdm.calls.last())
        }
        assertEquals(ExchangeOutcome.HELPER_FAILED,
            exchangeClearKeyOnce(byteArrayOf(1), kids, { true }, { Fake(standard.toByteArray()) }) { error("SECRET") })
    }

    @Test fun preCancelledDoesNotCreateCdm() {
        val initialization = byteArrayOf(1)
        assertEquals(ExchangeOutcome.CANCELLED,
            exchangeClearKeyOnce(initialization, kids, { false }, { error("must not create") }) { error("must not exchange") })
        assertEquals(0.toByte(), initialization[0])
    }

    @Test fun failedWaitSealsLateCompletionAndWipesUnclaimedCompletedBytes() {
        val incomplete = CompletableFuture<ByteArray?>()
        try { awaitOpaqueResponse(incomplete, 1); fail("must time out") } catch (_: TimeoutException) {}
        assertTrue(incomplete.isDone)
        assertNull(incomplete.getNow(null))
        assertFalse(incomplete.complete(byteArrayOf(2)))
        val value = byteArrayOf(2)
        val racing = object : CompletableFuture<ByteArray?>() {
            override fun get(timeout: Long, unit: TimeUnit): ByteArray? {
                complete(value)
                throw TimeoutException()
            }
        }
        try { awaitOpaqueResponse(racing, 1); fail("must time out") } catch (_: TimeoutException) {}
        assertEquals(0.toByte(), value[0])
        val successful = CompletableFuture.completedFuture<ByteArray?>(byteArrayOf(3))
        assertEquals(3.toByte(), checkNotNull(awaitOpaqueResponse(successful, 1))[0])
    }
}
