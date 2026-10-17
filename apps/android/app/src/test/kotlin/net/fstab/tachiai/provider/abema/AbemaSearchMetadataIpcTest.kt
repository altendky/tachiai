package net.fstab.tachiai.provider.abema

import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AbemaSearchMetadataIpcTest {
    private val nonce = "a".repeat(32)
    private fun fields() = mapOf("nonce" to nonce, "instance" to "12345678-1234-1234-1234-123456789abc", "query" to "Public 雪 + /",
        "module" to null, "offset" to 0, "proof" to "b".repeat(64), "deadline" to 46000L)
    @Test fun closedPrimitiveStartRejectsArbitraryInputsAndBindsExactlyOneGuestAndSearchRequest() {
        val start = AbemaSearchMetadataIpc.decodeStart(fields(), 1000)!!
        for (bad in listOf(fields() + ("url" to "https://private.invalid/"), fields() + ("module" to "other"),
            fields() + ("query" to "\u202eprivate"), fields() + ("offset" to 20), fields() + ("deadline" to 46001L),
            fields() + ("deadline" to 1000L), fields() + ("offset" to 0L))) assertNull(AbemaSearchMetadataIpc.decodeStart(bad, 1000))
        val request = start.search; val policy = AbemaGuestRequestAdmission(AbemaGuestBootstrapOperation.FETCH_SEARCH, search = request)
        val uri = URI(request.url())
        assertFalse(policy.admit(uri, false, "GET")); assertTrue(policy.admit(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST"))
        assertFalse(policy.admit(URI(request.url() + "&offset=20"), false, "GET"))
        assertFalse(policy.admit(URI("https://api.p-c3-e.abema-tv.com/v1/users/private"), false, "GET"))
        assertTrue(policy.admit(uri, false, "OPTIONS", mapOf("Origin" to "https://abema.tv", "Access-Control-Request-Method" to "GET",
            "Access-Control-Request-Headers" to "authorization")))
        assertTrue(policy.admit(uri, false, "GET")); assertFalse(policy.admit(uri, false, "GET"))
        assertFalse(AbemaGuestBootstrapPolicy.allows(uri, false, "GET", AbemaGuestBootstrapOperation.FETCH_SCHEDULE))
        assertTrue(AbemaGuestBootstrapPolicy.contentSecurityPolicy(AbemaGuestBootstrapOperation.FETCH_SEARCH, search = request).contains(request.url()))
    }
    @Test fun projectionRequiresExactNormalizedFieldsModuleIdentityAndStrictCounts() {
        val request = AbemaSearchRequest("Public", "episode")
        val good = """{"modules":[{"id":"episode","title":"Episodes","total":1,"entries":[{"kind":"season","id":"series-A/season-A","title":"Future 雪 😀"}]}]}"""
        assertEquals("season", decodeAbemaSearchProjection(good, request)!!.modules.single().entries.single().resource.kind)
        for (bad in listOf(good + "true", good.replace("\"total\":1", "\"total\":\"1\""), good.replace("\"total\":1", "\"total\":1.0"),
            good.replace("\"total\":1", "\"total\":1000001"), good.replace("\"id\":\"episode\"", "\"id\":\"package\""),
            good.replace("Future 雪 😀", "\\ud800"), good.replace("\"title\":\"Episodes\"", "\"title\":\"Episodes\",\"title\":\"Other\""),
            good.replace("series-A/season-A", "series-A"), good.replace("\"entries\":[", "\"unknown\":0,\"entries\":[")))
            assertNull(decodeAbemaSearchProjection(bad, request))
        assertNull(decodeAbemaSearchProjection("{}", request))
        assertNull(AbemaSearchMetadataIpc.decodeResult(mapOf("nonce" to nonce, "outcome" to "TEMPORARY", "reply" to good), request))
        assertNotNull(AbemaSearchMetadataIpc.decodeResult(mapOf("nonce" to nonce, "outcome" to "OK", "reply" to good), request))
    }
    @Test fun heldFinalOwnerReadCannotOverrideCancellationForegroundLossCloseOrExpiry() {
        for (mode in 0..3) {
            val now = AtomicLong(1000); val open = AtomicBoolean(true); val foreground = AtomicBoolean(true)
            val start = AbemaSearchMetadataIpc.decodeStart(fields(), now.get())!!
            val operation = AbemaSearchClientOperation(start, now::get, foreground::get)
            assertFalse(operation.accept(AbemaSearchMetadataIpc.Result("c".repeat(32), "OK", AbemaSearchReply(start.search, emptyList()))))
            assertTrue(operation.accept(AbemaSearchMetadataIpc.Result(nonce, "OK", AbemaSearchReply(start.search, emptyList()))))
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val result = AtomicReference<AbemaSearchMetadataIpc.Result?>()
            val worker = Thread { result.set(operation.workerResult({ entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true },
                { open.get() && operation.current() })) }
            try { worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
                when (mode) { 0 -> operation.cancel(); 1 -> foreground.set(false); 2 -> open.set(false); else -> now.set(start.deadline) }
                release.countDown(); worker.join(5000); assertFalse(worker.isAlive); assertNull(result.get())
            } finally { release.countDown(); worker.join(5000) }
        }
    }
    @Test fun optionalSeasonLeavesOldIpcUnchangedAndFilteredProjectionProvesExactParent() {
        val old = mapOf("nonce" to nonce, "instance" to "12345678-1234-1234-1234-123456789abc", "parent" to "series-A",
            "offset" to 0, "version" to null, "proof" to "b".repeat(64), "deadline" to 46000L)
        assertNull(AbemaSeriesMetadataIpc.decodeStart(old, 1000)!!.page.seasonId)
        val filtered = AbemaSeriesMetadataIpc.decodeStart(old + ("season" to "season-A"), 1000)!!.page
        assertTrue(filtered.url().endsWith("&seasonId=season-A")); assertFalse(AbemaSeriesPageRequest("series-A").url().contains("seasonId"))
        val text = """{"seriesId":"series-A","seasonId":"season-A","version":"v1","programs":[]}"""
        assertEquals("season-A", decodeAbemaSeriesProjection(text, filtered)!!.seasonId)
        assertNull(decodeAbemaSeriesProjection(text, AbemaSeriesPageRequest("series-A")))
        assertNull(decodeAbemaSeriesProjection(text, AbemaSeriesPageRequest("series-A", seasonId = "other")))
        assertNull(AbemaSeriesMetadataIpc.decodeStart(old + ("season" to null), 1000))
    }
}
