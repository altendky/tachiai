package net.fstab.tachiai.provider.abema

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

// A page answering request: a first page lists groups, a continuation repeats its types.
internal fun abemaSeriesPage(request: AbemaSeriesPageRequest, entries: List<CatalogEntry>, more: Boolean = entries.size == 20,
    seasonId: String = request.seasonId ?: "${request.seriesId}_s1", groupId: String = request.groupId ?: "${request.seriesId}_eg1"): AbemaSeriesPage {
    val groups = (listOf("${request.seriesId}_eg1", "${request.seriesId}_eg2") + groupId).distinct().map { AbemaSeriesGroup(it, "Group $it") }
    return AbemaSeriesPage(request.seriesId, seasonId, null, groups.takeIf { request.viewingTypes == null }, groupId,
        request.viewingTypes ?: listOf(1), entries, more)
}

class AbemaSeriesChildrenCatalogTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private fun parent(value: String = "series-A") = CatalogResource(ProviderId("abema"), "series", value, CatalogIntent.COLLECTION)
    private fun entry(index: Int) = CatalogEntry(CatalogResource(ProviderId("abema"), "episode", "episode-$index", CatalogIntent.VIDEO), "Future episode $index")
    private class Delegate(override val instanceId: String, provider: String = "abema") : ProviderCatalog {
        override val providerId = ProviderId(provider)
        var calls = 0
        override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, lookup = CatalogAccess.AVAILABLE,
            refresh = CatalogAccess.AVAILABLE, playback = CatalogAccess.AVAILABLE,
            collections = listOf(CatalogCollection("my_list", "My List", CatalogAccess.AVAILABLE)))
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> { calls++; return CatalogResult.Value(CatalogPage(emptyList())) }
        override fun lookup(input: String): CatalogResult<CatalogEntry> { calls++; return CatalogResult.Failure(CatalogFailure.NOT_FOUND) }
        override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> { calls++; return CatalogResult.Failure(CatalogFailure.NOT_FOUND) }
        override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> { calls++; return CatalogResult.Failure(CatalogFailure.UNSUPPORTED) }
    }
    private inner class Fixture {
        val delegate = Delegate(id); val requests = mutableListOf<AbemaSeriesPageRequest>()
        var allowed = true; var closes = 0; var cancels = 0; var failedClose = false
        var response: (AbemaSeriesPageRequest) -> CatalogResult<AbemaSeriesPage> = { request ->
            CatalogResult.Value(abemaSeriesPage(request, if (request.offset == 0) List(20, ::entry) else listOf(entry(20))))
        }
        var afterClose: () -> Unit = {}
        var gate: () -> Boolean = { allowed }
        val catalog = AbemaSeriesChildrenCatalog(delegate, { current -> object : AbemaSeriesTransport {
            override fun fetch(request: AbemaSeriesPageRequest): CatalogResult<AbemaSeriesPage> {
                check(current()); requests += request; return response(request)
            }
            override fun cancelActiveRequest() { cancels++ }
            override fun close() { closes++; afterClose(); if (failedClose) throw AssertionError("Fixture cleanup failure") }
        } }, { gate() })
        fun first(value: String = "series-A") = catalog.browse(CatalogQuery(parent = parent(value)))
        fun value(result: CatalogResult<CatalogPage>) = (result as CatalogResult.Value).value
    }
    private fun projection(parent: String = "series-A", items: String = """{"kind":"episode","id":"episode-1","title":"Future 雪 😀"}""",
        more: Boolean = false) = """{"seriesId":"$parent","seasonId":"${parent}_s10","seasonName":"Season","groups":""" +
        """[{"id":"${parent}_eg1","name":"生中継・見逃し"},{"id":"${parent}_eg5","name":"Highlights"}],"groupId":"${parent}_eg1",""" +
        """"viewingTypes":[1],"items":[$items],"more":$more}"""
    @Test fun strictProjectionBindsTwoParentsAndMixesEpisodesAndSlots() {
        for (parent in listOf("series-A", "series-B")) {
            val page = decodeAbemaSeriesProjection(projection(parent, """{"kind":"slot","id":"slot-A","channelId":"sumo","title":"Broadcast",""" +
                """"durationSeconds":17100},{"kind":"episode","id":"episode-1","title":"Future 雪 😀"}"""), AbemaSeriesPageRequest(parent))!!
            assertEquals(parent, page.seriesId); assertEquals("${parent}_s10", page.seasonId); assertEquals("Season", page.seasonName)
            assertEquals(listOf("${parent}_eg1", "${parent}_eg5"), page.groups!!.map { it.id }); assertEquals("${parent}_eg1", page.groupId)
            assertEquals(listOf(AbemaSlotRequest("sumo", "slot-A").resource,
                CatalogResource(ProviderId("abema"), "episode", "episode-1", CatalogIntent.VIDEO)), page.entries.map { it.resource })
            assertEquals(listOf(17100L, null), page.entries.map { it.durationSeconds })
            assertTrue(page.entries.all { it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null })
            assertNull(decodeAbemaSeriesProjection(projection(parent), AbemaSeriesPageRequest("other")))
            val crossed = decodeAbemaSeriesProjection(AbemaSeriesMetadataIpc.projection(page), AbemaSeriesPageRequest(parent))!!
            assertEquals(page.entries, crossed.entries); assertEquals(page.groups!!.map { it.name }, crossed.groups!!.map { it.name })
        }
        val request = AbemaSeriesPageRequest("394-72", 40, "394-72_s10", "394-72_eg1", listOf(1, 2), newest = true)
        assertEquals("https://api.p-c3-e.abema-tv.com/v1/contentlist/series/394-72?includes=liveEvent%2Cslot", request.seriesUrl())
        assertEquals("https://api.p-c3-e.abema-tv.com/v1/contentlist/episodeGroups/394-72_eg1/sections?seasonId=394-72_s10&premiumUser=false",
            request.sectionsUrl("394-72_eg1", "394-72_s10"))
        assertEquals("https://api.p-c3-e.abema-tv.com/v1/contentlist/episodeGroups/394-72_eg1/contents?seasonId=394-72_s10&orderType=desc" +
            "&limit=20&offset=40&includes=liveEvent%2Cslot&viewingTypes=1%2C2", request.contentsUrl("394-72_eg1", "394-72_s10", listOf(1, 2)))
    }
    @Test fun seasonChildrenMapToThatSeasonAcrossPagesAndRefuseCrossSeasonCursorOrReply() {
        val f = Fixture()
        val season = abemaSearchResource("season", "series-A/series-A_s2")!!
        val first = f.value(f.catalog.browse(CatalogQuery(parent = season)))
        val second = f.value(f.catalog.browse(CatalogQuery(parent = season, cursor = first.nextCursor)))
        assertNull(second.nextCursor); assertEquals(listOf("series-A_s2", "series-A_s2"), f.requests.map { it.seasonId })
        assertEquals(listOf(null, "series-A_eg1"), f.requests.map { it.groupId }); assertEquals(listOf(null, listOf(1)), f.requests.map { it.viewingTypes })
        assertEquals(first.groups, second.groups); assertEquals("series-A_eg1", second.group)
        val next = f.value(f.catalog.browse(CatalogQuery(parent = season))).nextCursor
        val other = abemaSearchResource("season", "series-A/series-A_s3")!!
        assertEquals(CatalogFailure.INVALID_INPUT, (f.catalog.browse(CatalogQuery(parent = other, cursor = next)) as CatalogResult.Failure).reason)
        f.response = { request -> CatalogResult.Value(abemaSeriesPage(request, emptyList(), seasonId = "series-A_s3")) }
        assertEquals(CatalogFailure.INVALID_INPUT, (f.catalog.browse(CatalogQuery(parent = season)) as CatalogResult.Failure).reason)
        val requests = f.requests.size
        val unbound = abemaSearchResource("season", "series-A/season-A")!!
        assertEquals(CatalogFailure.INVALID_INPUT, (f.catalog.browse(CatalogQuery(parent = unbound)) as CatalogResult.Failure).reason)
        assertEquals(requests, f.requests.size)
    }
    @Test fun groupsAreListedAndASelectedGroupBindsItsContinuation() {
        val f = Fixture()
        val first = f.value(f.first())
        assertEquals(listOf("series-A_eg1", "series-A_eg2"), first.groups.map { it.id }); assertEquals("series-A_eg1", first.group)
        assertEquals("Group series-A_eg2", first.groups[1].title); assertNull(f.requests.single().groupId)
        val tab = f.value(f.catalog.browse(CatalogQuery(parent = parent(), group = "series-A_eg2")))
        assertEquals("series-A_eg2", tab.group); assertEquals("series-A_eg2", f.requests.last().groupId); assertNull(f.requests.last().viewingTypes)
        assertEquals(CatalogFailure.INVALID_INPUT,
            (f.catalog.browse(CatalogQuery(parent = parent(), cursor = tab.nextCursor, group = "series-A_eg1")) as CatalogResult.Failure).reason)
        val again = f.value(f.catalog.browse(CatalogQuery(parent = parent(), group = "series-A_eg2")))
        val more = f.value(f.catalog.browse(CatalogQuery(parent = parent(), cursor = again.nextCursor, group = "series-A_eg2")))
        assertEquals("series-A_eg2", more.group); assertEquals(again.groups, more.groups)
        assertEquals(listOf(1), f.requests.last().viewingTypes); assertEquals("series-A_eg2", f.requests.last().groupId)
        val requests = f.requests.size
        for (group in listOf("series-B_eg1", "series-A_", "eg1"))
            assertEquals(CatalogFailure.INVALID_INPUT, (f.catalog.browse(CatalogQuery(parent = parent(), group = group)) as CatalogResult.Failure).reason)
        assertEquals(requests, f.requests.size)
        f.response = { CatalogResult.Value(abemaSeriesPage(it, emptyList(), groupId = "series-A_eg1")) }
        assertEquals(CatalogFailure.INVALID_INPUT,
            (f.catalog.browse(CatalogQuery(parent = parent(), group = "series-A_eg2")) as CatalogResult.Failure).reason)
        assertThrows(IllegalArgumentException::class.java) { CatalogQuery(group = "series-A_eg1") }
        assertThrows(IllegalArgumentException::class.java) { CatalogPage(emptyList(), group = "series-A_eg1") }
    }
    @Test fun aFullProviderPageContinuesEvenWhenSkippedKindsLeaveFewerRows() {
        val f = Fixture()
        val slot = CatalogEntry(AbemaSlotRequest("sumo", "slot-A").resource, "Broadcast", durationSeconds = 17100)
        f.response = { request -> CatalogResult.Value(abemaSeriesPage(request, if (request.offset == 0) listOf(slot, entry(1)) else listOf(entry(2)),
            more = request.offset == 0)) }
        val first = f.value(f.first())
        assertEquals(listOf(slot, entry(1)), first.entries); assertNotNull(first.nextCursor)
        val second = f.value(f.catalog.browse(CatalogQuery(parent = parent(), cursor = first.nextCursor)))
        assertEquals(listOf(entry(2)), second.entries); assertNull(second.nextCursor)
        assertThrows(IllegalArgumentException::class.java) { abemaSeriesPage(AbemaSeriesPageRequest("series-A"), List(20, ::entry), more = false) }
    }
    @Test fun strictNormalizedProjectionRefusesDuplicatesWrongTypesUnicodeAndDrift() {
        val request = AbemaSeriesPageRequest("series-A")
        val valid = projection()
        assertNotNull(decodeAbemaSeriesProjection(valid, request))
        for (text in listOf(valid + " trailing", valid.replace("\"more\":false", "\"more\":0"), valid.replace(",\"more\":false", ""),
            valid.replace("\"viewingTypes\":[1]", "\"viewingTypes\":[\"1\"]"), valid.replace("\"viewingTypes\":[1]", "\"viewingTypes\":[0]"),
            valid.replace("\"viewingTypes\":[1]", "\"viewingTypes\":[1,1]"), valid.replace("\"viewingTypes\":[1]", "\"viewingTypes\":[1,2,3,4,5]"),
            valid.replace("\"viewingTypes\":[1]", "\"viewingTypes\":[]"), valid.replace("\"groupId\":\"series-A_eg1\"", "\"groupId\":\"series-A_eg9\""),
            valid.replace("series-A_eg5", "series-B_eg5"), valid.replace("series-A_eg5", "series-A_eg1"), valid.replace("series-A_s10", "season-A"),
            valid.replace("\"name\":\"Highlights\"", "\"name\":\"${"x".repeat(65)}\""), valid.replace("\"seasonName\":\"Season\"", "\"seasonName\":\" \""),
            valid.replace("\"seriesId\":\"series-A\"", "\"seriesId\":\"series-A\",\"\\u0073eriesId\":\"series-A\""),
            valid.replace("\"groups\"", "\"extra\":1,\"groups\""),
            projection(items = """{"kind":"episode","id":"episode-1","title":"\ud800"}"""),
            projection(items = """{"kind":"episode","id":"episode-1","channelId":"sumo","title":"Episode"}"""),
            projection(items = """{"kind":"slot","id":"slot-A","title":"Broadcast"}"""),
            projection(items = """{"kind":"live_event","id":"event-A","title":"Live"}"""),
            projection(items = """{"kind":"slot","id":"slot-A","channelId":"../x","title":"Broadcast"}"""),
            projection(items = """{"kind":"episode","id":"episode-1","title":"First"},{"kind":"episode","id":"episode-1","title":"Second"}"""),
            projection(items = List(20) { """{"kind":"episode","id":"episode-$it","title":"Episode"}""" }.joinToString(","), more = false),
            projection(items = List(21) { """{"kind":"episode","id":"episode-$it","title":"Episode"}""" }.joinToString(","), more = true)))
            assertNull(text, decodeAbemaSeriesProjection(text, request))
        assertNotNull(decodeAbemaSeriesProjection(projection(items = List(20) { """{"kind":"episode","id":"episode-$it","title":"Episode"}""" }
            .joinToString(","), more = true), request))
        // A continuation carries no groups and must repeat its viewing types.
        val continuation = AbemaSeriesPageRequest("series-A", 20, "series-A_s10", "series-A_eg1", listOf(1))
        assertNull(decodeAbemaSeriesProjection(valid, continuation))
        val bare = """{"seriesId":"series-A","seasonId":"series-A_s10","groupId":"series-A_eg1","viewingTypes":[1],"items":[],"more":false}"""
        assertNotNull(decodeAbemaSeriesProjection(bare, continuation)); assertNull(decodeAbemaSeriesProjection(bare, request))
        assertNull(decodeAbemaSeriesProjection(bare.replace("[1]", "[2]"), continuation))
        assertNull(decodeAbemaSeriesProjection(" ".repeat(32769), request))
        for (bad in listOf<() -> Any>({ AbemaSeriesPageRequest("series-A", 1) }, { AbemaSeriesPageRequest("series-A", 20) },
            { AbemaSeriesPageRequest("series-A", 10000, "series-A_s1", "series-A_eg1", listOf(1)) },
            { AbemaSeriesPageRequest("series-A", seasonId = "season-A") }, { AbemaSeriesPageRequest("series-A", groupId = "series-A_") },
            { AbemaSeriesPageRequest("series-A", seasonId = "series-A_s1", viewingTypes = listOf(1)) },
            { AbemaSeriesPageRequest("series-A", 0, "series-A_s1", "series-A_eg1", listOf(100)) }))
            assertThrows(IllegalArgumentException::class.java) { bad() }
    }
    @Test fun pagesUseBoundSingleUseHandlesAndNeverImplicitlyResolveCollection() {
        val f = Fixture(); val first = f.value(f.first())
        assertEquals(List(20, ::entry), first.entries); assertNotNull(first.nextCursor)
        assertFalse(first.nextCursor!!.contains("eg1")); assertEquals(1, f.closes)
        val next = f.value(f.catalog.browse(CatalogQuery(parent = parent(), cursor = first.nextCursor)))
        assertEquals(listOf(entry(20)), next.entries); assertNull(next.nextCursor)
        assertEquals(listOf(0, 20), f.requests.map { it.offset }); assertEquals(listOf(null, listOf(1)), f.requests.map { it.viewingTypes })
        assertEquals(CatalogFailure.INVALID_INPUT, (f.catalog.browse(CatalogQuery(parent = parent(), cursor = first.nextCursor)) as CatalogResult.Failure).reason)
        assertEquals(CatalogFailure.UNSUPPORTED, (f.catalog.resolve(parent()) as CatalogResult.Failure).reason)
        assertEquals(CatalogAccess.AVAILABLE, f.catalog.capabilities().collections.single().access)
        assertEquals("Browse", f.catalog.capabilities().childrenTitle)
    }
    @Test fun parentInstanceProviderAndChangedQueryCannotResurrectContinuations() {
        assertThrows(IllegalArgumentException::class.java) { AbemaSeriesChildrenCatalog(Delegate(id, "twitch"), null) }
        assertThrows(IllegalArgumentException::class.java) { AbemaSeriesChildrenCatalog(Delegate("bad"), null) }
        for (action in 0..3) {
            val f = Fixture(); val handle = f.value(f.first()).nextCursor!!
            when (action) { 0 -> f.catalog.browse(CatalogQuery()); 1 -> f.catalog.lookup("text")
                2 -> f.catalog.refresh(entry(0).resource); else -> f.catalog.resolve(entry(0).resource) }
            assertEquals(CatalogFailure.INVALID_INPUT, (f.catalog.browse(CatalogQuery(parent = parent(), cursor = handle)) as CatalogResult.Failure).reason)
            assertEquals(1, f.requests.size)
        }
        val a = Fixture(); val b = Fixture(); val handle = a.value(a.first()).nextCursor
        assertEquals(CatalogFailure.INVALID_INPUT, (b.catalog.browse(CatalogQuery(parent = parent(), cursor = handle)) as CatalogResult.Failure).reason)
        assertEquals(CatalogFailure.INVALID_INPUT, (a.catalog.browse(CatalogQuery(parent = parent("other"), cursor = handle)) as CatalogResult.Failure).reason)
    }
    // A repeated row is skipped instead (#622; see aRowRepeatedOnTheNextPageIsSkippedInsteadOfFailing).
    @Test fun wrongReturnedParentGroupOrTypesRefuseInsteadOfClaimingEmpty() {
        for (mode in 0..2) {
            val f = Fixture(); val handle = f.value(f.first()).nextCursor
            f.response = { _ -> CatalogResult.Value(when (mode) {
                0 -> abemaSeriesPage(AbemaSeriesPageRequest("other", 20, "other_s1", "other_eg1", listOf(1)), listOf(entry(20)))
                1 -> AbemaSeriesPage("series-A", "series-A_s1", null, null, "series-A_eg1", listOf(2), listOf(entry(20)), false)
                else -> AbemaSeriesPage("series-A", "series-A_s1", null, null, "series-A_eg2", listOf(1), listOf(entry(20)), false)
            }) }
            assertEquals(CatalogFailure.INVALID_INPUT, (f.catalog.browse(CatalogQuery(parent = parent(), cursor = handle)) as CatalogResult.Failure).reason)
            assertEquals(2, f.closes)
        }
        val f = Fixture(); f.response = { CatalogResult.Value(abemaSeriesPage(it, emptyList())) }
        val page = f.value(f.first()); assertTrue(page.entries.isEmpty()); assertNull(page.nextCursor)
        val bare = Fixture(); bare.response = { CatalogResult.Value(AbemaSeriesPage(it.seriesId, null, null, emptyList(), null, emptyList(), emptyList(), false)) }
        val none = bare.value(bare.first()); assertTrue(none.entries.isEmpty() && none.groups.isEmpty()); assertNull(none.group)
    }
    @Test fun lateOwnerLossAfterCursorPublicationClearsRefusesAndCannotReuseOldHandle() {
        val f = Fixture(); var closedChecks = 0
        f.afterClose = { f.gate = { ++closedChecks == 1 } }
        assertEquals(CatalogFailure.ACCESS_REQUIRED, (f.first() as CatalogResult.Failure).reason)
        assertEquals(2, closedChecks); assertEquals(1, f.closes)
        f.afterClose = {}; f.gate = { true }
        assertEquals(20, f.value(f.first()).entries.size)
    }
    @Test fun closeOrQueryChangeDuringHeldFinalOwnerReadCannotPublishPageOrContinuation() {
        for (close in listOf(false, true)) {
            val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
            var checks = 0
            f.afterClose = { f.gate = {
                if (++checks == 2) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                true
            } }
            val result = AtomicReference<CatalogResult<CatalogPage>>()
            val worker = Thread { result.set(f.first()) }
            try {
                worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
                if (close) f.catalog.close() else f.catalog.lookup("changed query")
                release.countDown(); worker.join(5000)
                assertFalse(worker.isAlive); assertEquals(CatalogFailure.ACCESS_REQUIRED, (result.get() as CatalogResult.Failure).reason)
                assertEquals(1, f.requests.size); assertEquals(1, f.closes)
            } finally { release.countDown(); worker.join(5000); f.catalog.close() }
        }
    }
    @Test fun closeDuringRetrievalRejectsLatePageAndSignalsActiveTransport() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.response = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); CatalogResult.Value(abemaSeriesPage(it, listOf(entry(0)))) }
        val result = AtomicReference<CatalogResult<CatalogPage>>()
        val worker = Thread { result.set(f.first()) }
        try {
            worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); f.catalog.close(); release.countDown(); worker.join(5000)
            assertFalse(worker.isAlive); assertEquals(CatalogFailure.ACCESS_REQUIRED, (result.get() as CatalogResult.Failure).reason)
            assertEquals(1, f.cancels); assertEquals(1, f.closes)
        } finally { release.countDown(); worker.join(5000) }
    }
    @Test fun cleanupFailurePoisonsRetainsAndDoesNotPublishOrDispatchAgain() {
        val f = Fixture(); f.failedClose = true
        assertEquals(CatalogFailure.TEMPORARY, (f.first() as CatalogResult.Failure).reason)
        f.failedClose = false; assertEquals(CatalogFailure.ACCESS_REQUIRED, (f.first() as CatalogResult.Failure).reason)
        assertEquals(1, f.requests.size); assertEquals(CatalogAccess.NOT_VERIFIED, f.catalog.capabilities().children)
    }

    // #622: a group gaining a broadcast between pages shifts a shown row onto the next page.
    @Test fun aRowRepeatedOnTheNextPageIsSkippedInsteadOfFailing() {
        val f = Fixture()
        f.response = { request -> CatalogResult.Value(abemaSeriesPage(request,
            if (request.offset == 0) List(20, ::entry) else listOf(entry(19), entry(20)))) }
        val first = f.value(f.first())
        val next = f.value(f.catalog.browse(CatalogQuery(parent = parent(), cursor = first.nextCursor)))
        assertEquals(listOf(entry(20)), next.entries)
    }
}
