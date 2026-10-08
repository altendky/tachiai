package net.fstab.tachiai.provider.twitch

import net.fstab.tachiai.platform.net.AccessProbeOutcome
import org.junit.Assert.*
import org.junit.Test

class TwitchNativeAccessProbeTest {
    @Test fun `live and replay requests retain their distinct public source identities`() {
        val live = twitchAccessProbeBody(TwitchAccessCase.LIVE, "bobross").toString(Charsets.UTF_8)
        val replay = twitchAccessProbeBody(TwitchAccessCase.REPLAY, "2080217716").toString(Charsets.UTF_8)
        assertTrue(live.contains("streamPlaybackAccessToken(channelName: \$login"))
        assertTrue(replay.contains("videoPlaybackAccessToken(id: \$id"))
        assertTrue(replay.contains("query VideoPlaybackAccessToken(\$id: ID!"))
        assertFalse(replay.contains("\\$"))
        assertTrue(live.contains("\"login\":\"bobross\""))
        assertTrue(replay.contains("\"id\":\"2080217716\""))
        assertFalse(live.contains("Authorization"))
        assertFalse(live.contains("client_secret"))
    }

    @Test fun `invalid resource cannot inject JSON or query syntax`() {
        listOf("bad\"channel", "a/b", "x", "a\nzzz").forEach {
            assertThrows(IllegalArgumentException::class.java) { twitchAccessProbeBody(TwitchAccessCase.LIVE, it) }
        }
        assertThrows(IllegalArgumentException::class.java) { twitchAccessProbeBody(TwitchAccessCase.REPLAY, "v123") }
    }

    @Test fun `access fields are not playback proof and errors take precedence`() {
        assertEquals(AccessProbeOutcome.AUTHORIZATION_FIELDS_PRESENT, twitchAccessClassification(200, emptyList(), true))
        assertEquals(AccessProbeOutcome.CLIENT_REJECTED, twitchAccessClassification(400, listOf("invalid client"), false))
        assertEquals(AccessProbeOutcome.PROVIDER_ERROR, twitchAccessClassification(200, listOf("private error"), true))
        assertEquals(AccessProbeOutcome.HTTP_REJECTED, twitchAccessClassification(403, emptyList(), true))
        assertEquals(AccessProbeOutcome.INVALID_RESPONSE, twitchAccessClassification(200, emptyList(), false))
    }

    @Test fun `malformed error shapes cannot be hidden behind authorization fields`() {
        val fields = mapOf("signature" to "fixture-signature", "value" to "fixture-value")
        assertEquals(AccessProbeOutcome.AUTHORIZATION_FIELDS_PRESENT, classifyTwitchAccessFields(200, fields))
        listOf(mapOf("errors" to mapOf("private" to "value")), mapOf("error" to true),
            mapOf("message" to 1), mapOf("errors" to listOf(null)), mapOf("errors" to "private message")).forEach {
            assertEquals(AccessProbeOutcome.INVALID_RESPONSE, classifyTwitchAccessFields(200, fields + it))
        }
        assertEquals(AccessProbeOutcome.PROVIDER_ERROR, classifyTwitchAccessFields(200, fields + ("errors" to listOf("private message"))))
        assertEquals(AccessProbeOutcome.INVALID_RESPONSE, classifyTwitchAccessFields(200, fields + ("value" to "")))
    }
}
