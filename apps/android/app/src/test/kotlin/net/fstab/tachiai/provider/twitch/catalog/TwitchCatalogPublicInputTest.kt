package net.fstab.tachiai.provider.twitch.catalog

import org.junit.Assert.*
import org.junit.Test

class TwitchCatalogPublicInputTest {
    @Test fun ChannelAliasesAndImmutableVideoOrUserIdsRemainDifferentIntents() {
        assertEquals("midnightsumo", (parseTwitchCatalogPublicInput(" MidnightSumo ") as TwitchCatalogPublicInput.Login).login)
        assertEquals("midnightsumo", (parseTwitchCatalogPublicInput("https://www.twitch.tv/MidnightSumo/") as TwitchCatalogPublicInput.Login).login)
        assertEquals("123", (parseTwitchCatalogPublicInput("123") as TwitchCatalogPublicInput.BroadcasterId).id)
        assertEquals("123", (parseTwitchCatalogPublicInput("https://twitch.tv/123") as TwitchCatalogPublicInput.Login).login)
        assertEquals("456", (parseTwitchCatalogPublicInput("https://twitch.tv/videos/456/") as TwitchCatalogPublicInput.VideoId).id)
        assertEquals("sumo", (parseTwitchCatalogPublicInput("https://twitch.tv:443/sumo") as TwitchCatalogPublicInput.Login).login)
    }

    @Test fun SignedCredentialBearingLookalikeAndAmbiguousUrlsAreRefused() {
        listOf("", " ", "0", "00123", "http://twitch.tv/sumo", "https://twitch.tv.evil.test/sumo", "https://evil.test/twitch.tv/sumo",
            "https://user:secret@twitch.tv/sumo", "https://twitch.tv:444/sumo", "https://twitch.tv/sumo?token=private",
            "https://twitch.tv/sumo?", "https://twitch.tv/sumo#private", "https://twitch.tv/sumo#",
            "https://twitch.tv/%73umo", "https://twitch.tv/sumo%2Fvideos", "https://twitch.tv/sumo//",
            "https://twitch.tv/sumo/videos", "https://twitch.tv/videos/0456", "https://twitch.tv/videos/0",
            "https://twitch.tv/directory", "https://twitch.tv/login", "https://twitch.tv/settings",
            "https://twitch.tv/../sumo", "https://twitch.tv\\@evil.test/sumo", "sumo\n", "sumo\u200b", "a".repeat(2049),
            "https://usher.ttvnw.net/api/channel/hls/sumo.m3u8").forEach { input ->
            val error = assertThrows(TwitchHelixException::class.java) { parseTwitchCatalogPublicInput(input) }
            assertEquals(TwitchHelixFailure.INVALID_INPUT, error.failure)
            assertNull(error.cause); assertFalse(error.toString().contains("private"))
        }
    }

    @Test fun PublicInputHandlesNeverPrintAliasesOrIdentifiers() {
        listOf(parseTwitchCatalogPublicInput("fixturealias"), parseTwitchCatalogPublicInput("987654321"),
            parseTwitchCatalogPublicInput("https://twitch.tv/videos/987654321")).forEach {
            assertFalse(it.toString().contains("fixturealias")); assertFalse(it.toString().contains("987654321"))
        }
    }
}
