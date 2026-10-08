package net.fstab.tachiai.platform.media

import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class OneShotOpaquePlaybackLicenseTest {
    private val kids = listOf(UUID(0, 1))
    private fun challenge() = "{\"kids\":[\"AAAAAAAAAAAAAAAAAAAAAQ\"],\"type\":\"temporary\"}".toByteArray()

    @Test fun handsOffOnceWithoutChangingBorrowedRequestOrReturnedResponse() {
        val input = challenge()
        val before = input.copyOf()
        val response = byteArrayOf(1, 2, 3)
        var owned: ByteArray? = null
        var requests = 0
        val gate = OneShotOpaquePlaybackLicense(kids, { true }, {
            requests++
            owned = it
            assertArrayEquals(before, it)
            response
        })
        assertSame(response, gate.request(input, true))
        assertArrayEquals(before, input)
        assertArrayEquals(ByteArray(before.size), owned)
        assertArrayEquals(byteArrayOf(1, 2, 3), response)
        assertThrows(IOException::class.java) { gate.request(input, true) }
        assertEquals(1, requests)
    }

    @Test fun invalidInitialShapeAndDifferentIdsNeverReachBrowser() {
        for (input in listOf(byteArrayOf(), ByteArray(16 * 1024 + 1), "{}".toByteArray(),
            "{\"kids\":[\"AAAAAAAAAAAAAAAAAAAAAg\"]}".toByteArray())) {
            var requests = 0
            val gate = OneShotOpaquePlaybackLicense(kids, { true }, { requests++; byteArrayOf(1) })
            assertThrows(IOException::class.java) { gate.request(input, true) }
            assertEquals(0, requests)
        }
    }

    @Test fun renewalAndInactiveRequestsNeverReachBrowser() {
        var requests = 0
        val renewal = OneShotOpaquePlaybackLicense(kids, { true }, { requests++; byteArrayOf(1) })
        assertThrows(IOException::class.java) { renewal.request(challenge(), false) }
        val inactive = OneShotOpaquePlaybackLicense(kids, { false }, { requests++; byteArrayOf(1) })
        assertThrows(IOException::class.java) { inactive.request(challenge(), true) }
        assertEquals(0, requests)
    }

    @Test fun cancellationAfterReplyWipesItWithoutHandingOff() {
        var active = true
        val response = byteArrayOf(1, 2, 3)
        val gate = OneShotOpaquePlaybackLicense(kids, { active }, { active = false; response })
        assertThrows(IOException::class.java) { gate.request(challenge(), true) }
        assertArrayEquals(ByteArray(3), response)
    }

    @Test fun nullEmptyAndOversizedRepliesFailWithoutRetry() {
        for (response in listOf(null, byteArrayOf(), ByteArray(64 * 1024 + 1) { 1 })) {
            var requests = 0
            val gate = OneShotOpaquePlaybackLicense(kids, { true }, { requests++; response })
            assertThrows(IOException::class.java) { gate.request(challenge(), true) }
            assertThrows(IOException::class.java) { gate.request(challenge(), true) }
            assertEquals(1, requests)
            if (response != null) assertArrayEquals(ByteArray(response.size), response)
        }
    }

    @Test fun callbackFailureHasNoRawCauseAndWipesOwnedRequest() {
        var owned: ByteArray? = null
        val gate = OneShotOpaquePlaybackLicense(kids, { true }, {
            owned = it
            throw IOException("PRIVATE_FIXTURE")
        })
        val error = assertThrows(IOException::class.java) { gate.request(challenge(), true) }
        assertNull(error.cause)
        assertFalse(error.message.orEmpty().contains("PRIVATE_FIXTURE"))
        assertTrue(owned!!.all { it == 0.toByte() })
    }
}
