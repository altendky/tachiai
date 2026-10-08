package net.fstab.tachiai.provider.abema

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.FileSystemException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AbemaCdnReviewTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = AbemaReplayCdnScope.APPROVED_PROTOTYPE
    private val route = "/program/394-72_s10_p8529/manifest.mpd"
    private fun review(host: String = "ds-vod-abematv.akamaized.net", time: Long = 123,
        stage: AbemaCdnStage = AbemaCdnStage.SELECTED_MANIFEST) = checkNotNull(
        AbemaCdnApprovalPolicy.review(URI("https://$host$route"), stage, time))
    private fun journalFile() = File(temporary.newFolder(), "cdn-review.txt")
    private fun records(file: File) = AbemaCdnReviewCodec.decode(file.readBytes())
    private fun refusesDecode(text: String) {
        assertThrows(RuntimeException::class.java) { AbemaCdnReviewCodec.decode(text.toByteArray()) }
    }

    @Test fun `only the approved single DNS label family matches`() {
        for (host in listOf("ds-vod-abematv.akamaized.net", "vod-abematv.akamaized.net",
            "linear-abematv.akamaized.net", "a-abematv.akamaized.net",
            "a".repeat(55) + "-abematv.akamaized.net", "VOD-ABEMATV.AKAMAIZED.NET")) {
            assertTrue(host, AbemaCdnApprovalPolicy.approvedHost(host))
            assertEquals(AbemaReplayUriPolicy.ALLOWED,
                abemaReplaySelectedSourcePolicy(URI("https://$host$route"), scope))
            val observed = review(host)
            assertEquals(AbemaCdnDecision.APPROVED, observed.decision)
            assertEquals(AbemaCdnRule.ABEMATV_AKAMAI_FAMILY, observed.rule)
        }
        for (host in listOf("abematv.akamaized.net", "-abematv.akamaized.net",
            "vod-abema-tv.akamaized.net", "vod-abematv.akamaized.net.attacker.example",
            "prefix.vod-abematv.akamaized.net", "vod-abematv.attacker.akamaized.net",
            "vod-abematv.akamaized.net.", "a".repeat(56) + "-abematv.akamaized.net",
            "vod_abematv.akamaized.net", "other.akamaized.net")) {
            assertFalse(host, AbemaCdnApprovalPolicy.approvedHost(host))
            assertEquals(AbemaReplayUriPolicy.AUTHORITY,
                abemaReplaySelectedSourcePolicy(URI("https://$host$route"), scope))
        }
        assertFalse(AbemaCdnApprovalPolicy.approvedHost(null))
    }

    @Test fun `historical default stays exact and prototype keeps route and URI guards`() {
        val historical = URI("https://ds-vod-abematv.akamaized.net$route")
        val broadened = URI("https://vod-abematv.akamaized.net$route")
        assertEquals(AbemaReplayUriPolicy.ALLOWED, abemaReplaySelectedSourcePolicy(historical))
        assertEquals(AbemaReplayUriPolicy.AUTHORITY, abemaReplaySelectedSourcePolicy(broadened))
        assertEquals(AbemaReplayUriPolicy.AUTHORITY, abemaReplayCdnUriPolicy(broadened))
        assertEquals(AbemaReplayUriPolicy.ALLOWED, abemaReplaySelectedSourcePolicy(broadened, scope))
        for ((candidate, expected) in listOf(
            "http://vod-abematv.akamaized.net$route" to AbemaReplayUriPolicy.AUTHORITY,
            "https://vod-abematv.akamaized.net:444$route" to AbemaReplayUriPolicy.AUTHORITY,
            "https://user@vod-abematv.akamaized.net$route" to AbemaReplayUriPolicy.USER_INFO,
            "$broadened#synthetic-fragment" to AbemaReplayUriPolicy.FRAGMENT,
            "https://vod-abematv.akamaized.net/../$route" to AbemaReplayUriPolicy.PATH,
            "https://vod-abematv.akamaized.net/%2e/$route" to AbemaReplayUriPolicy.PATH,
            "$broadened?token=${"x".repeat(1025)}" to AbemaReplayUriPolicy.QUERY_SIZE,
            broadened.toString().replace("394-72_s10_p8529", "other-program") to AbemaReplayUriPolicy.SOURCE_ROUTE,
            broadened.toString().replace("manifest.mpd", "segment.m4s") to AbemaReplayUriPolicy.SOURCE_ROUTE,
        )) assertEquals(expected, abemaReplaySelectedSourcePolicy(URI(candidate), scope))
        assertEquals(AbemaReplayUriPolicy.ALLOWED,
            abemaReplayCdnUriPolicy(URI("https://vod-abematv.akamaized.net/declared/segment.m4s"), scope))
    }

    @Test fun `review discards path query fragment and refuses unsafe authorities`() {
        val first = checkNotNull(AbemaCdnApprovalPolicy.review(
            URI("https://VOD-ABEMATV.AKAMAIZED.NET:443/synthetic-PATH?token=synthetic-QUERY#synthetic-FRAGMENT"),
            AbemaCdnStage.SELECTED_MANIFEST, 10))
        val second = checkNotNull(AbemaCdnApprovalPolicy.review(
            URI("https://vod-abematv.akamaized.net/other-path?token=other-query"),
            AbemaCdnStage.SELECTED_MANIFEST, 20))
        assertEquals("https://vod-abematv.akamaized.net/<redacted-path>", first.pattern)
        assertEquals(first.identity, second.identity)
        val encoded = AbemaCdnReviewCodec.encode(listOf(first)).toString(Charsets.US_ASCII)
        for (secret in listOf("synthetic-PATH", "synthetic-QUERY", "synthetic-FRAGMENT", "token="))
            assertFalse(encoded.contains(secret))
        for (uri in listOf("http://unknown.example/path", "https://unknown.example:444/path",
            "https://synthetic-user:synthetic-password@unknown.example/path",
            "https://unknown.example./path", "https://localhost/path", "https://[::1]/path")) {
            assertNull(AbemaCdnApprovalPolicy.review(URI(uri), AbemaCdnStage.DECLARED_MEDIA, 1))
        }
    }

    @Test fun `cached News family scope is opt in and retains its channel route`() {
        val bare = URI("https://other-abematv.akamaized.net/channel/abema-news/manifest.mpd")
        val selected = URI("$bare?t=fixture&enc=clear&dt=pc_chrome&ut=1")
        assertEquals(AbemaNewsMediaUriPolicy.AUTHORITY, abemaNewsMediaUriPolicy(bare))
        assertEquals(AbemaNewsMediaUriPolicy.ALLOWED, abemaNewsMediaUriPolicy(bare, approvedPrototype = true))
        assertFalse(AbemaNativeBootstrapPolicy.allowsNewsSource(selected))
        assertFalse(AbemaNativeBootstrapPolicy.allowsNewsCdn(selected))
        assertTrue(AbemaNativeBootstrapPolicy.allowsNewsSource(selected, approvedPrototype = true))
        assertTrue(AbemaNativeBootstrapPolicy.allowsNewsCdn(selected, approvedPrototype = true))
        assertFalse(AbemaNativeBootstrapPolicy.allowsNewsSource(
            URI(selected.toString().replace("abema-news", "other-channel")), approvedPrototype = true))
    }

    @Test fun `unknown origins remain pending even when journal claims approval`() {
        val value = review("unreviewed.example")
        assertEquals(AbemaCdnDecision.PENDING, value.decision)
        assertEquals(AbemaCdnRule.UNKNOWN_ORIGIN, value.rule)
        val file = journalFile()
        AbemaCdnReviewJournal(file).record(value.copy(decision = AbemaCdnDecision.APPROVED,
            rule = AbemaCdnRule.APPROVED_EXACT))
        assertEquals(AbemaCdnDecision.APPROVED, records(file).single().decision)
        assertFalse(AbemaCdnApprovalPolicy.approvedHost("unreviewed.example"))
        assertEquals(AbemaCdnDecision.PENDING, review("unreviewed.example").decision)
        assertEquals(AbemaReplayUriPolicy.AUTHORITY,
            abemaReplaySelectedSourcePolicy(URI("https://unreviewed.example$route"), scope))
    }

    @Test fun `codec round trips bounded unique records and rejects malformed data`() {
        val values = List(AbemaCdnReviewCodec.MAX_RECORDS) { review("fixture-$it.example", it.toLong()) }
        assertEquals(values, AbemaCdnReviewCodec.decode(AbemaCdnReviewCodec.encode(values)))
        assertTrue(AbemaCdnReviewCodec.encode(values).size <= AbemaCdnReviewCodec.MAX_BYTES)
        assertEquals(emptyList<AbemaCdnReviewRecord>(), AbemaCdnReviewCodec.decode(AbemaCdnReviewCodec.encode(emptyList())))
        assertThrows(RuntimeException::class.java) { AbemaCdnReviewCodec.encode(values + review("overflow.example")) }
        assertThrows(RuntimeException::class.java) { AbemaCdnReviewCodec.encode(listOf(values[0], values[0])) }
        assertThrows(RuntimeException::class.java) { AbemaCdnReviewCodec.encode(listOf(review().copy(firstSeenMs = -1))) }
        assertThrows(RuntimeException::class.java) {
            AbemaCdnReviewCodec.decode(ByteArray(AbemaCdnReviewCodec.MAX_BYTES + 1) { 32 })
        }
        val header = AbemaCdnReviewCodec.HEADER
        val row = "123\tPENDING\tDECLARED_MEDIA\tUNKNOWN_ORIGIN\thttps://fixture.example/<redacted-path>"
        for (text in listOf("wrong-header\n$row\n", "$header\n$row", "$header\n\n",
            "$header\n$row\n$row\n", "$header\n${row.replace("123", "12345678901234")}\n",
            "$header\n${row.replace("PENDING", "UNKNOWN")}\n",
            "$header\n${row.replace("DECLARED_MEDIA", "UNKNOWN")}\n",
            "$header\n${row.replace("UNKNOWN_ORIGIN", "UNKNOWN")}\n",
            "$header\n${row.replace("/<redacted-path>", "/raw-path?token=synthetic")}\n",
            "$header\n${row.replace("fixture.example", "user@fixture.example")}\n",
            "$header\n${row.replace("fixture.example", "fixture.example:443")}\n",
            "$header\n${row.replace("fixture.example", "fixture.éxample")}\n")) refusesDecode(text)
    }

    @Test fun `journal deduplicates across restarts and preserves first observation`() {
        val file = journalFile()
        val value = review(time = 10)
        val journal = AbemaCdnReviewJournal(file)
        journal.record(value)
        val original = file.readBytes()
        journal.record(value.copy(firstSeenMs = 20))
        AbemaCdnReviewJournal(file).record(value.copy(firstSeenMs = 30))
        assertArrayEquals(original, file.readBytes())
        AbemaCdnReviewJournal(file).record(value.copy(stage = AbemaCdnStage.DECLARED_MEDIA))
        assertEquals(listOf(value, value.copy(stage = AbemaCdnStage.DECLARED_MEDIA)), records(file))
        assertEquals(listOf(file.name), checkNotNull(file.parentFile).list()?.toList())
    }

    @Test fun `concurrent journal instances merge without dropping duplicate slot observations`() {
        val file = journalFile()
        val journals = List(4) { AbemaCdnReviewJournal(file) }
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val jobs = (0 until 32).map { index -> executor.submit {
                check(start.await(5, TimeUnit.SECONDS))
                journals[index % journals.size].record(review("fixture-$index.example", index.toLong()))
                journals[(index + 1) % journals.size].record(review("fixture-$index.example", 999))
            } }
            start.countDown()
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            val saved = records(file)
            assertEquals(32, saved.size)
            assertEquals((0 until 32).map { review("fixture-$it.example").identity }.toSet(),
                saved.map { it.identity }.toSet())
        } finally { executor.shutdownNow() }
    }

    @Test fun `corrupt oversized or full journal is preserved rather than reset or pruned`() {
        for (bytes in listOf("synthetic-corrupt-content".toByteArray(),
            ByteArray(AbemaCdnReviewCodec.MAX_BYTES + 1) { 32 },
            AbemaCdnReviewCodec.encode(List(AbemaCdnReviewCodec.MAX_RECORDS) { review("fixture-$it.example") }))) {
            val file = journalFile().apply { writeBytes(bytes) }
            assertThrows(RuntimeException::class.java) { AbemaCdnReviewJournal(file).record(review()) }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(listOf(file.name), checkNotNull(file.parentFile).list()?.toList())
        }
    }

    @Test fun `symlink journal or parent cannot overwrite an unrelated target`() {
        val folder = temporary.newFolder()
        val target = File(folder, "target.txt").apply { writeText("synthetic-unrelated-content") }
        val link = File(folder, "journal-link.txt")
        try { Files.createSymbolicLink(link.toPath(), target.toPath()) }
        catch (error: UnsupportedOperationException) { assumeNoException(error); return }
        catch (error: FileSystemException) { assumeNoException(error); return }
        catch (error: SecurityException) { assumeNoException(error); return }
        assertThrows(RuntimeException::class.java) { AbemaCdnReviewJournal(link).record(review()) }
        assertEquals("synthetic-unrelated-content", target.readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        val linkedParent = File(temporary.root, "linked-parent")
        Files.createSymbolicLink(linkedParent.toPath(), folder.toPath())
        assertThrows(RuntimeException::class.java) {
            AbemaCdnReviewJournal(File(linkedParent, target.name)).record(review())
        }
        assertEquals("synthetic-unrelated-content", target.readText())
        assertTrue(Files.isSymbolicLink(linkedParent.toPath()))
    }
}
