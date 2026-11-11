package net.fstab.tachiai.provider.youtube

import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class YouTubeCatalogTest {
    private val instance = "12000000-0000-0000-0000-000000000001"
    @Test fun publicAliasesPreserveVideoIdentity() {
        val id = "_bcdefghi-1"
        listOf(id, "https://youtu.be/$id?si=public-share", "https://www.youtube.com/watch?v=$id&t=12",
            "https://m.youtube.com/watch?v=$id", "https://youtube.com/embed/$id",
            "https://www.youtube.com/shorts/$id", "https://youtube.com/live/$id")
            .forEach { assertEquals(it, id, parseYouTubeVideoId(it)) }
        assertEquals("-bcdefghi_1", parseYouTubeVideoId("https://youtu.be/-bcdefghi_1"))
        assertTrue(supportedYouTubeResource(CatalogResource(ProviderId("youtube"), "video", id, CatalogIntent.VIDEO)))
    }
    @Test fun unsafeAmbiguousAndNonVideoInputsAreRejected() {
        listOf("", "abcdefghij", "abcdefghijkl", "http://youtu.be/abcdefghijk",
            "https://youtu.be.evil.test/abcdefghijk", "https://evil.test/watch?v=abcdefghijk",
            "https://youtube.com@evil.test/watch?v=abcdefghijk", "https://private@youtube.com/watch?v=abcdefghijk",
            "https://youtube.com:444/watch?v=abcdefghijk", "https://youtube.com/watch?v=abcdefghijk&v=12345678901",
            "https://youtube.com/watch?v=%61bcdefghijk", "https://youtube.com/embed/%61bcdefghijk",
            "https://youtu.be/abcdefghijk/extra", "https://youtube.com/feed/subscriptions",
            "https://youtu.be/abcdefghijk\n", "https://youtu.be/abcdefghijk\u200B")
            .forEach { assertNull(it, parseYouTubeVideoId(it)) }
    }
    @Test fun localLookupDoesNotClaimMetadataAccountOrAvailability() {
        val catalog = YouTubeCatalog(instance)
        val entry = (catalog.lookup("https://youtu.be/abcdefghijk") as CatalogResult.Value).value
        assertEquals(CatalogResource(ProviderId("youtube"), "video", "abcdefghijk", CatalogIntent.VIDEO), entry.resource)
        assertEquals(CatalogAvailability.UNKNOWN, entry.availability)
        assertTrue(entry.title.contains("metadata not checked"))
        assertNull(entry.scheduledStartEpochMs)
        assertEquals(CatalogAccess.AUTHORIZATION_REQUIRED, catalog.capabilities().collections.single().access)
        assertEquals(CatalogFailure.ACCESS_REQUIRED,
            (catalog.browse(CatalogQuery(collectionId = "subscriptions")) as CatalogResult.Failure).reason)
        assertTrue(catalog.resolve(entry.resource) is CatalogResult.Value)
        assertEquals(CatalogFailure.NOT_VERIFIED, (catalog.refresh(entry.resource) as CatalogResult.Failure).reason)
    }
    @Test fun resolveRejectsOtherProviderAndInvalidIntentAndStopsAfterClose() {
        val catalog = YouTubeCatalog(instance)
        listOf(CatalogResource(ProviderId("twitch"), "video", "abcdefghijk", CatalogIntent.VIDEO),
            CatalogResource(ProviderId("youtube"), "channel", "abcdefghijk", CatalogIntent.CHANNEL),
            CatalogResource(ProviderId("youtube"), "video", "short", CatalogIntent.VIDEO)).forEach {
            assertEquals(CatalogFailure.INVALID_INPUT, (catalog.resolve(it) as CatalogResult.Failure).reason)
        }
        catalog.close()
        assertEquals(CatalogFailure.TEMPORARY, (catalog.lookup("abcdefghijk") as CatalogResult.Failure).reason)
        assertEquals(CatalogAccess.UNSUPPORTED, catalog.capabilities().lookup)
    }
}
