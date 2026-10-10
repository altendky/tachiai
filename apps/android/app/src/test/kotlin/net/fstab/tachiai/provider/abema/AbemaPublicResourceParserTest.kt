package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.PrototypeSource
import net.fstab.tachiai.presentation.prototypeCatalogResource
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class AbemaPublicResourceParserTest {
    private val provider = ProviderId("abema")
    private fun resource(input: String) = parseAbemaPublicResource(input)!!.resource

    @Test fun publicShapesKeepChannelBroadcastVideoAndCollectionIntentsDistinct() {
        val examples = listOf(
            "https://abema.tv/channels/sumo" to CatalogResource(provider, "channel", "sumo", CatalogIntent.CHANNEL),
            "https://abema.tv/channels/sumo/slots/A63wMmchm3x8Uo" to
                CatalogResource(provider, "slot", "sumo/A63wMmchm3x8Uo", CatalogIntent.BROADCAST),
            "https://abema.tv/video/episode/394-72_s10_p8510" to
                CatalogResource(provider, "episode", "394-72_s10_p8510", CatalogIntent.VIDEO),
            "https://abema.tv/video/title/394-72" to CatalogResource(provider, "series", "394-72", CatalogIntent.COLLECTION),
        )
        examples.forEach { (url, expected) ->
            val entry = parseAbemaPublicResource(url)!!
            assertEquals(expected, entry.resource)
            assertEquals(CatalogAvailability.UNKNOWN, entry.availability)
            assertNull(entry.scheduledStartEpochMs)
            assertTrue(entry.title.length <= 160)
            assertFalse(entry.title.contains("https://"))
        }
    }

    @Test fun authorityCaseDefaultPortAndBoundarySpacesNormalizeWithoutChangingPublicIds() {
        val original = parseAbemaPublicResource("https://abema.tv/channels/sumo/slots/A63wMmchm3x8Uo")
        assertEquals(original, parseAbemaPublicResource("  HTTPS://ABEMA.TV:443/channels/sumo/slots/A63wMmchm3x8Uo  "))
        assertNotEquals(original, parseAbemaPublicResource("https://abema.tv/channels/sumo/slots/a63wmmchm3x8uo"))
        assertNull(parseAbemaPublicResource("https://abema.tv/CHANNELS/sumo"))
    }

    @Test fun sameDaySlotsAndTheirChannelContextDoNotCollapseIntoOneSelection() {
        val lower = resource("https://abema.tv/channels/sumo/slots/A63wMmchm3x8Uo")
        val makuuchi = resource("https://abema.tv/channels/sumo/slots/A63wR6irBEskZM")
        val otherChannel = resource("https://abema.tv/channels/world-sports/slots/A63wMmchm3x8Uo")
        assertNotEquals(lower, makuuchi); assertNotEquals(lower, otherChannel)
        assertEquals(CatalogIntent.BROADCAST, lower.intent)
        assertNotEquals(resource("https://abema.tv/channels/sumo"), lower)
    }

    @Test fun normalizationRoundTripsPublicResourceShapesWithoutRetainingUrls() {
        listOf("https://abema.tv/channels/sumo", "https://abema.tv/channels/sumo/slots/A63wMmchm3x8Uo",
            "https://abema.tv/video/episode/394-72_s10_p8510", "https://abema.tv/video/title/394-72").forEach { input ->
            val entry = parseAbemaPublicResource(input)!!
            val path = when (entry.resource.kind) {
                "channel" -> "/channels/${entry.resource.identity}"
                "slot" -> entry.resource.identity.split('/').let { "/channels/${it[0]}/slots/${it[1]}" }
                "episode" -> "/video/episode/${entry.resource.identity}"
                else -> "/video/title/${entry.resource.identity}"
            }
            assertEquals(entry, parseAbemaPublicResource("https://abema.tv$path"))
        }
    }

    @Test fun knownSamplesRetainTheirExactAlreadySupportedResources() {
        assertEquals(prototypeCatalogResource(PrototypeSource.ABEMA_LIVE), resource("https://abema.tv/channels/abema-news"))
        assertEquals(prototypeCatalogResource(PrototypeSource.ABEMA_REPLAY), resource("https://abema.tv/video/episode/394-72_s10_p8529"))
    }

    @Test fun accountMediaForeignOriginsAndSensitiveUrlComponentsAreRejected() {
        val rejected = listOf("", "sumo", "394-72", "https://abema.tv", "http://abema.tv/channels/sumo",
            "//abema.tv/channels/sumo", "https:abema.tv/channels/sumo", "https://www.abema.tv/channels/sumo",
            "https://abema.tv.evil.test/channels/sumo", "https://evilabema.tv/channels/sumo",
            "https://abema.tv./channels/sumo", "https://abema.tv:8443/channels/sumo", "https://user@abema.tv/channels/sumo",
            "https://user:password@abema.tv/channels/sumo", "https://abema.tv/channels/sumo?token=fixture",
            "https://abema.tv/channels/sumo?", "https://abema.tv/video/title/394-72?s=394-72_s10",
            "https://abema.tv/channels/sumo#fixture", "https://abema.tv/channels/sumo#",
            "https://abema.tv/account", "https://abema.tv/login", "https://abema.tv/auth/fixture",
            "https://api.abema.io/v1/channels/sumo", "https://linear-abematv.akamaized.net/channel/sumo/playlist.m3u8")
        rejected.forEach { assertNull(it, parseAbemaPublicResource(it)) }
    }

    @Test fun malformedEncodedTraversalAndExtraPathPartsAreRejectedWithoutNormalizingAwayEvidence() {
        val rejected = listOf("https://abema.tv/channels/sumo/", "https://abema.tv//channels/sumo",
            "https://abema.tv/channels//sumo", "https://abema.tv/channels/../sumo", "https://abema.tv/channels/.",
            "https://abema.tv/channels/sumo/slots/", "https://abema.tv/channels/sumo/slots/id/extra",
            "https://abema.tv/video/title/394-72/episode/1", "https://abema.tv/video/episode/394-72/",
            "https://abema.tv/channels/%73umo", "https://abema.tv/channels/sumo%2Fslots%2Fid",
            "https://abema.tv/channels/%2E%2E", "https://abema.tv/channels/sumo%252Fid",
            "https://abema.tv/channels/sumo%", "https://abema.tv/channels/sumo\\slots\\id",
            "https://abema.tv/channels/sumo?id=%00", "https://abema.tv/channels/sumo.m3u8",
            "https://abema.tv/channels/_sumo", "https://abema.tv/video/episode/-id",
            "https://abema.tv/channels/相撲", "https://abema.tv/channels/su mo")
        rejected.forEach { assertNull(it, parseAbemaPublicResource(it)) }
    }

    @Test fun controlsAndUnicodeFormattingAreRejectedEvenAtInputBoundaries() {
        val valid = "https://abema.tv/channels/sumo"
        listOf("\n$valid", "$valid\t", "$valid\u0000", "$valid\u007f", "$valid\u200b", "$valid\u202e",
            "https://abema.tv/channels/su\nmo", "https://abema.tv/channels/su\u200bmo").forEach {
            assertNull(parseAbemaPublicResource(it))
        }
    }

    @Test fun identifierAndWholeInputBoundsKeepGeneratedMetadataWithinTheSharedLimits() {
        val channel = "a".repeat(64); val slot = "B".repeat(64); val video = "c".repeat(128)
        listOf("https://abema.tv/channels/$channel", "https://abema.tv/channels/$channel/slots/$slot",
            "https://abema.tv/video/episode/$video", "https://abema.tv/video/title/$video").forEach { input ->
            val entry = parseAbemaPublicResource(input)!!
            assertTrue(entry.title.length <= 160); assertTrue(entry.resource.identity.length <= 256)
        }
        listOf("https://abema.tv/channels/${channel}a", "https://abema.tv/channels/sumo/slots/${slot}b",
            "https://abema.tv/video/episode/${video}c", "https://abema.tv/video/title/${video}c",
            " ".repeat(2049), " ".repeat(2048) + "https://abema.tv/channels/sumo").forEach {
            assertNull(parseAbemaPublicResource(it))
        }
    }
}
