package net.fstab.tachiai.provider.abema

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class AbemaPlaybackAvailabilityTest {
    private val now = 1_800_000_000_000L
    private val uri = URI("https://ds-vod-abematv.akamaized.net/program/fixture/manifest.mpd?t=fixture")
    private fun projection(expiry: String = "${now + 1}", source: URI = uri) =
        "{\"uri\":\"$source\",\"expiresAtMs\":$expiry}"

    @Test fun finiteProviderWindowDoesNotRequireFiveMinutesAndExpiresAtItsActualBoundary() {
        val receipt = checkNotNull(parseAbemaPlaybackAvailability(projection(), uri, true, now))
        assertEquals(now + 1, receipt.expiresAtMs)
        assertTrue(receipt.current(now))
        assertFalse(receipt.current(now + 1))
        assertFalse("Expired availability cannot revive after wall rollback", receipt.current(now))
    }

    @Test fun rollbackIsTerminalForFiniteAndExplicitlyUnboundedAvailability() {
        for (expiry in listOf("${now + 60_000}", "null")) {
            val receipt = checkNotNull(parseAbemaPlaybackAvailability(projection(expiry), uri, false, now))
            assertTrue(receipt.current(now + 100))
            assertFalse(receipt.current(now + 99))
            assertFalse(receipt.current(now + 101))
        }
    }

    @Test fun explicitNullIsAllowedForChannelButCannotReplaceRequiredReplayEntitlement() {
        assertNull(parseAbemaPlaybackAvailability(projection("null"), uri, true, now))
        val receipt = checkNotNull(parseAbemaPlaybackAvailability(projection("null"), uri, false, now))
        assertNull(receipt.expiresAtMs)
        assertTrue(receipt.current(now + 86_400_000))
        assertTrue(receipt.current(Long.MAX_VALUE))
        assertFalse(receipt.current(0))
    }

    @Test fun availabilityMustBindTheExactPreviouslyAdmittedUri() {
        assertNull(parseAbemaPlaybackAvailability(projection(source = URI("$uri-other")), uri, true, now))
        assertNull(parseAbemaPlaybackAvailability(projection().replace("fixture/", "different/"), uri, true, now))
        assertNull(parseAbemaPlaybackAvailability(projection().replace("https://", "https:\\/\\/"), uri, true, now))
    }

    @Test fun canonicalProjectionRejectsMissingDuplicateReorderedExtraAndCoercedFields() {
        val valid = projection()
        for (text in listOf(null, "", " $valid", "$valid ", "$valid{}", "x".repeat(4097),
            valid.replace(",\"expiresAtMs\"", ", \"expiresAtMs\""),
            valid.replace("\"uri\":", "\"uri\":\"$uri\",\"uri\":"),
            valid.replace("\"expiresAtMs\"", "\"expiresAtM\\u0073\""),
            valid.dropLast(1) + ",\"extra\":true}",
            "{\"expiresAtMs\":${now + 1},\"uri\":\"$uri\"}",
            "{\"uri\":\"$uri\"}", "{\"expiresAtMs\":${now + 1}}")) {
            assertNull(text, parseAbemaPlaybackAvailability(text, uri, false, now))
        }
        for (expiry in listOf("true", "false", "\"${now + 1}\"", "0", "-1", "$now", "${now - 1}",
            "${now + 1}.0", "1.8e12", "01", "9007199254740992", "9223372036854775807")) {
            assertNull(expiry, parseAbemaPlaybackAvailability(projection(expiry), uri, false, now))
        }
        assertNull(parseAbemaPlaybackAvailability(valid, uri, false, 0))
        assertNull(parseAbemaPlaybackAvailability(valid, uri, false, -1))
        assertNull(parseAbemaPlaybackAvailability(valid, uri, false, Long.MAX_VALUE))
    }

    @Test fun exactSafeIntegerMaximumIsAcceptedAndDebugRenderingRetainsNoSourceOrTime() {
        val receipt = checkNotNull(parseAbemaPlaybackAvailability(projection("9007199254740991"), uri, true, now))
        assertEquals(9_007_199_254_740_991L, receipt.expiresAtMs)
        assertTrue(receipt.current(9_007_199_254_740_990L))
        assertFalse(receipt.current(9_007_199_254_740_991L))
        assertEquals("AbemaPlaybackAvailability(redacted)", receipt.toString())
    }
}
