package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

// #560: slot times only beside channel-filtered schedule projections, and the
// catalog's "scheduled now / next program" answer for a channel.
class AbemaChannelAiringTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val channelA = AbemaScheduleRequest("channel-A")
    private fun slot(name: String, times: String = "", duration: Long? = null) =
        """{"id":"$name","channelId":"channel-A","title":"Title $name"${duration?.let { ",\"durationSeconds\":$it" } ?: ""}$times}"""
    private fun timed(start: Long, end: Long) = ",\"startAt\":$start,\"endAt\":$end"
    private val untimed = ",\"startAt\":null,\"endAt\":null"
    private fun filtered(vararg slots: String) = """{"slots":[${slots.joinToString(",")}],"truncated":false,"channelId":"channel-A"}"""
    private fun browsable(vararg slots: String) = """{"slots":[${slots.joinToString(",")}],"truncated":false}"""
    private fun entry(name: String) = CatalogEntry(AbemaSlotRequest("channel-A", name).resource, "Title $name")
    private fun channel(value: String = "channel-A") = CatalogResource(ProviderId("abema"), "channel", value, CatalogIntent.CHANNEL)

    @Test fun filteredProjectionRequiresParallelTimesAndBrowsableProjectionRefusesThem() {
        val decoded = decodeAbemaScheduleProjection(filtered(slot("slot-A", timed(1000, 2800), 1800), slot("slot-B", untimed)), channelA)!!
        assertEquals(listOf(AbemaSlotTimes(1000, 2800), null), decoded.times)
        // Times never reach the entries themselves.
        assertTrue(decoded.entries.all { it.scheduledStartEpochMs == null && it.availability == CatalogAvailability.UNKNOWN })
        assertEquals(listOf(1800L, null), decoded.entries.map { it.durationSeconds })
        assertTrue(decodeAbemaScheduleProjection(browsable(slot("slot-A", duration = 1800)))!!.times.isEmpty())
        // Missing, one-sided, browsable or inconsistent times are refused.
        for (text in listOf(filtered(slot("slot-A")), filtered(slot("slot-A", ",\"startAt\":1000", 1800)),
            filtered(slot("slot-A", ",\"startAt\":1000,\"endAt\":null")), filtered(slot("slot-A", ",\"startAt\":null,\"endAt\":2800")),
            filtered(slot("slot-A", timed(1000, 2800))), filtered(slot("slot-A", timed(1000, 2800), 1799)),
            filtered(slot("slot-A", timed(2800, 1000))), filtered(slot("slot-A", timed(1000, 1000))),
            filtered(slot("slot-A", ",\"startAt\":0,\"endAt\":1800", 1800)),
            filtered(slot("slot-A", ",\"startAt\":01000,\"endAt\":2800", 1800)),
            filtered(slot("slot-A", ",\"startAt\":\"1000\",\"endAt\":2800", 1800)),
            filtered(slot("slot-A", ",\"startAt\":1000.0,\"endAt\":2800", 1800)),
            filtered(slot("slot-A", timed(99_999_999_000, 100_000_000_000), 1_000)),
            filtered(slot("slot-A", timed(1000, 605_801))),
            filtered(slot("slot-A", timed(1000, 2800) + ",\"startAt\":1000", 1800))))
            assertNull(text, decodeAbemaScheduleProjection(text, channelA))
        assertNull(decodeAbemaScheduleProjection(browsable(slot("slot-A", untimed))))
        assertNull(decodeAbemaScheduleProjection(browsable(slot("slot-A", timed(1000, 2800), 1800))))
    }

    @Test fun snapshotTimesStayParallelAndOnlyForAChannel() {
        assertEquals(listOf(null), AbemaScheduleSnapshot(listOf(entry("slot-A")), request = channelA).times)
        assertTrue(AbemaScheduleSnapshot(listOf(entry("slot-A"))).times.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            AbemaScheduleSnapshot(listOf(entry("slot-A")), times = listOf(AbemaSlotTimes(1, 2)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AbemaScheduleSnapshot(listOf(entry("slot-A")), request = channelA, times = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) { AbemaSlotTimes(5, 5) }
        assertThrows(IllegalArgumentException::class.java) { AbemaSlotTimes(0, 5) }
        assertThrows(IllegalArgumentException::class.java) { AbemaSlotTimes(1, 1 + MAX_CATALOG_DURATION_SECONDS + 1) }
    }

    @Test fun timesSurviveTheMetadataIpcProjectionOnlyForAChannel() {
        val snapshot = decodeAbemaScheduleProjection(filtered(slot("slot-A", timed(1000, 2800), 1800), slot("slot-B", untimed)), channelA)!!
        val projection = AbemaScheduleMetadataIpc.projection(snapshot)
        val crossed = decodeAbemaScheduleProjection(projection, channelA)!!
        assertEquals(snapshot.times, crossed.times)
        assertEquals(snapshot.entries, crossed.entries)
        val result = AbemaScheduleMetadataIpc.decodeResult(mapOf("nonce" to "a".repeat(32), "outcome" to "OK",
            "snapshot" to projection), channelA)!!
        assertEquals(listOf(AbemaSlotTimes(1000, 2800), null), result.snapshot!!.times)
        val global = AbemaScheduleMetadataIpc.projection(decodeAbemaScheduleProjection(browsable(slot("slot-A", duration = 1800)))!!)
        assertFalse(global.contains("startAt")); assertFalse(global.contains("endAt"))
    }

    private val start = 1_700_000_000L
    private fun snapshot(vararg slots: Pair<String, AbemaSlotTimes?>) =
        AbemaScheduleSnapshot(slots.map { entry(it.first) }, request = channelA, times = slots.map { it.second })
    private fun program(name: String, startSeconds: Long, endSeconds: Long) =
        CatalogScheduledProgram("Title $name", startSeconds * 1000, endSeconds * 1000)

    @Test fun airingSelectsTheCurrentSlotAndTheEarliestLaterStart() {
        val slots = snapshot("past" to AbemaSlotTimes(start - 7200, start - 3600), "now" to AbemaSlotTimes(start - 600, start + 1200),
            "later" to AbemaSlotTimes(start + 5400, start + 7200), "next" to AbemaSlotTimes(start + 1800, start + 3600),
            "unknown" to null)
        val nowMs = start * 1000
        assertEquals(CatalogAiring(program("now", start - 600, start + 1200), program("next", start + 1800, start + 3600)),
            abemaChannelAiring(slots, nowMs))
        // Between broadcasts: nothing now, the next start.
        assertEquals(CatalogAiring(null, program("later", start + 5400, start + 7200)),
            abemaChannelAiring(slots, (start + 4000) * 1000))
        // All past, or the channel absent from the timetable: neither.
        assertEquals(CatalogAiring(), abemaChannelAiring(slots, (start + 7200) * 1000))
        assertEquals(CatalogAiring(), abemaChannelAiring(AbemaScheduleSnapshot(emptyList(), request = channelA), nowMs))
        assertEquals(CatalogAiring(), abemaChannelAiring(snapshot("unknown" to null), nowMs))
    }

    @Test fun airingBoundariesStartInclusiveEndExclusive() {
        val slots = snapshot("first" to AbemaSlotTimes(start, start + 1800), "second" to AbemaSlotTimes(start + 1800, start + 3600))
        val first = program("first", start, start + 1800); val second = program("second", start + 1800, start + 3600)
        assertEquals(CatalogAiring(null, first), abemaChannelAiring(slots, start * 1000 - 1))
        assertEquals(CatalogAiring(first, second), abemaChannelAiring(slots, start * 1000))
        assertEquals(CatalogAiring(first, second), abemaChannelAiring(slots, (start + 1800) * 1000 - 1))
        assertEquals(CatalogAiring(second, null), abemaChannelAiring(slots, (start + 1800) * 1000))
        // Overlapping slots: the latest-starting one is on now.
        val overlap = snapshot("long" to AbemaSlotTimes(start - 3600, start + 3600), "short" to AbemaSlotTimes(start - 60, start + 60))
        assertEquals(program("short", start - 60, start + 60), abemaChannelAiring(overlap, start * 1000).current)
    }

    private class Delegate(override val instanceId: String) : ProviderCatalog {
        override val providerId = ProviderId("abema")
        var calls = 0
        override fun capabilities() = CatalogCapabilities()
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> { calls++; return CatalogResult.Value(CatalogPage(emptyList())) }
        override fun lookup(input: String): CatalogResult<CatalogEntry> { calls++; return CatalogResult.Failure(CatalogFailure.NOT_FOUND) }
        override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> { calls++; return CatalogResult.Failure(CatalogFailure.NOT_FOUND) }
        override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> { calls++; return CatalogResult.Failure(CatalogFailure.NOT_FOUND) }
    }
    private inner class Fixture {
        val delegate = Delegate(id)
        var allowed = true; var closes = 0
        val requests = mutableListOf<AbemaScheduleRequest>()
        var response: (AbemaScheduleRequest) -> CatalogResult<AbemaScheduleSnapshot> = { request ->
            CatalogResult.Value(AbemaScheduleSnapshot(listOf(entry("slot-A"), entry("slot-B")), request = request,
                times = if (request.channelId == null) null else listOf(AbemaSlotTimes(start - 600, start + 1200), AbemaSlotTimes(start + 1800, start + 3600))))
        }
        val catalog = AbemaScheduleCatalog(delegate, { admitted -> object : AbemaScheduleTransport {
            override fun fetch(request: AbemaScheduleRequest): CatalogResult<AbemaScheduleSnapshot> {
                check(admitted()); requests += request; return response(request)
            }
            override fun cancelActiveRequest() {}
            override fun close() { closes++ }
        } }, { allowed })
    }

    @Test fun catalogAnswersFromOneFreshChannelSnapshotAndKeepsTimesOffBrowsableRows() {
        val f = Fixture()
        val answer = (f.catalog.airing(channel(), start * 1000) as CatalogResult.Value).value
        assertEquals(program("slot-A", start - 600, start + 1200), answer.current)
        assertEquals(program("slot-B", start + 1800, start + 3600), answer.next)
        assertEquals(listOf(channelA), f.requests); assertEquals(1, f.closes); assertEquals(0, f.delegate.calls)
        // Each question is a fresh snapshot; nothing is cached in the catalog.
        f.catalog.airing(channel(), start * 1000); assertEquals(2, f.requests.size)
        // Browsing the same channel's programs still shows no times.
        val page = (f.catalog.browse(CatalogQuery(parent = channel())) as CatalogResult.Value).value
        assertTrue(page.entries.all { it.scheduledStartEpochMs == null })
        // Wrappers forward the question unchanged.
        val wrapped = AbemaSearchCatalog(AbemaSeriesChildrenCatalog(f.catalog, null), null)
        assertEquals(CatalogResult.Value(answer), wrapped.airing(channel(), start * 1000))
        assertEquals(CatalogFailure.UNSUPPORTED, (f.delegate.airing(channel(), 0) as CatalogResult.Failure).reason)
    }

    @Test fun catalogRefusesOtherResourcesWrongBindingsAndRevokedOwnersWithoutPublishing() {
        val f = Fixture()
        for (resource in listOf(channel().copy(providerId = ProviderId("twitch")), channel().copy(kind = "slot"),
            AbemaSlotRequest("channel-A", "slot-A").resource, channel().copy(intent = CatalogIntent.COLLECTION),
            channel("bad/id")))
            assertEquals(CatalogFailure.INVALID_INPUT, (f.catalog.airing(resource, start * 1000) as CatalogResult.Failure).reason)
        assertEquals(CatalogFailure.INVALID_INPUT, (f.catalog.airing(channel(), -1) as CatalogResult.Failure).reason)
        assertTrue(f.requests.isEmpty())
        f.response = { CatalogResult.Value(AbemaScheduleSnapshot(emptyList(), request = AbemaScheduleRequest("channel-B"))) }
        assertEquals(CatalogFailure.INVALID_INPUT, (f.catalog.airing(channel(), start * 1000) as CatalogResult.Failure).reason)
        f.response = { CatalogResult.Failure(CatalogFailure.RATE_LIMITED, 5000) }
        assertEquals(CatalogResult.Failure(CatalogFailure.RATE_LIMITED, 5000), f.catalog.airing(channel(), start * 1000))
        f.response = { request -> f.allowed = false; CatalogResult.Value(AbemaScheduleSnapshot(emptyList(), request = request)) }
        assertEquals(CatalogFailure.ACCESS_REQUIRED, (f.catalog.airing(channel(), start * 1000) as CatalogResult.Failure).reason)
        assertEquals(CatalogFailure.ACCESS_REQUIRED, (f.catalog.airing(channel(), start * 1000) as CatalogResult.Failure).reason)
        assertEquals(CatalogFailure.NOT_VERIFIED,
            (AbemaScheduleCatalog(f.delegate, null).airing(channel(), start * 1000) as CatalogResult.Failure).reason)
        f.allowed = true; f.catalog.close()
        assertEquals(CatalogFailure.ACCESS_REQUIRED, (f.catalog.airing(channel(), start * 1000) as CatalogResult.Failure).reason)
    }
}
