package net.fstab.tachiai.platform.media

import java.io.IOException
import java.net.URI
import net.fstab.tachiai.provider.abema.AbemaNewsMediaUriPolicy
import net.fstab.tachiai.provider.abema.abemaNewsCdnUriPolicy
import org.junit.Assert.*
import org.junit.Test

class DeclaredDashMediaPolicyTest {
    private val manifest = URI("https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd")
    private fun media(name: String) = URI("https://linear-abematv.akamaized.net/declared/news/$name")
    private fun policy() = DeclaredDashMediaPolicy(manifest) { abemaNewsCdnUriPolicy(it) == AbemaNewsMediaUriPolicy.ALLOWED }

    @Test fun admitsExactDeclaredFilesOutsideGuessedRootButNotNeighbours() {
        val policy = policy()
        assertTrue(policy.allows(manifest))
        assertFalse(policy.allows(media("init.mp4")))
        policy.publish(listOf(media("init.mp4"), media("123.m4s")))
        assertTrue(policy.allows(media("init.mp4")))
        assertTrue(policy.allows(media("123.m4s")))
        assertFalse(policy.allows(media("124.m4s")))
        assertFalse(policy.allows(URI(manifest.toString().replace("manifest", "other"))))
    }

    @Test fun refreshRetainsOnePriorSnapshotNotAnUnboundedUnion() {
        val policy = policy()
        policy.publish(listOf(media("1.m4s")))
        policy.publish(listOf(media("2.m4s")))
        assertTrue(policy.allows(media("1.m4s")))
        assertTrue(policy.allows(media("2.m4s")))
        policy.publish(listOf(media("3.m4s")))
        assertFalse(policy.allows(media("1.m4s")))
        assertTrue(policy.allows(media("2.m4s")))
        assertTrue(policy.allows(media("3.m4s")))
    }

    @Test fun invalidSnapshotCannotPartiallyPublishOrErasePriorValidState() {
        val policy = policy()
        policy.publish(listOf(media("safe.m4s")))
        for (bad in listOf("https://evil.invalid/init.mp4", "http://linear-abematv.akamaized.net/init.mp4",
            "${media("init.mp4")}?fixture=1", "${media("init.mp4")}#fixture",
            "https://user@linear-abematv.akamaized.net/init.mp4",
            "https://linear-abematv.akamaized.net:444/init.mp4",
            "https://linear-abematv.akamaized.net/../init.mp4",
            "https://linear-abematv.akamaized.net/%2e/init.mp4")) {
            assertThrows(IOException::class.java) { policy.publish(listOf(media("next.m4s"), URI(bad))) }
            assertTrue(policy.allows(media("safe.m4s")))
            assertFalse(policy.allows(media("next.m4s")))
        }
    }

    @Test fun closedPolicyIsTerminalAndDropsAllUrls() {
        val policy = policy()
        policy.publish(listOf(media("safe.m4s")))
        policy.close()
        assertFalse(policy.allows(manifest))
        assertFalse(policy.allows(media("safe.m4s")))
        assertThrows(IOException::class.java) { policy.publish(listOf(media("next.m4s"))) }
    }

    @Test fun snapshotSizeAndUriLengthAreBounded() {
        assertThrows(IOException::class.java) { policy().publish(emptyList()) }
        assertThrows(IOException::class.java) { policy().publish(List(MAX_DECLARED_DASH_URIS + 1) { media("x.m4s") }) }
        assertThrows(IOException::class.java) { policy().publish(listOf(media("a".repeat(2049)))) }
    }

    @Test fun declaredSegmentWindowsAreFiniteAndOverflowSafe() {
        assertEquals(7L..9L, declaredDashSegmentNumbers(7, 3))
        assertTrue(declaredDashSegmentNumbers(0, 0).isEmpty())
        for ((first, count) in listOf(-1L to 1L, 0L to -1L, 0L to 8193L, Long.MAX_VALUE to 2L)) {
            assertThrows(IOException::class.java) { declaredDashSegmentNumbers(first, count) }
        }
    }
}
