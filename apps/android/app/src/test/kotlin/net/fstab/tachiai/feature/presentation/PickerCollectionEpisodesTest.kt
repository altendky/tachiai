package net.fstab.tachiai.feature.presentation

import java.util.UUID
import net.fstab.tachiai.presentation.ConfiguredSource
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class PickerCollectionEpisodesTest {
    private val instance = "12345678-1234-1234-1234-123456789abc"
    private fun collection(identity: String) = ConfiguredSource(UUID.randomUUID().toString(), instance,
        CatalogEntry(CatalogResource(ProviderId("abema"), "series", identity, CatalogIntent.COLLECTION), "Collection $identity"))
    private fun entry(name: String) = CatalogEntry(CatalogResource(ProviderId("abema"), "episode", name, CatalogIntent.VIDEO), "Episode $name")
    private inner class Catalog : ProviderCatalog {
        override val providerId = ProviderId("abema")
        override val instanceId = instance
        val queries = mutableListOf<CatalogQuery>()
        var closes = 0
        var reply: (CatalogQuery) -> CatalogResult<CatalogPage> = { query ->
            if (query.cursor == null) CatalogResult.Value(CatalogPage(listOf(entry("new"), entry("mid")), "next"))
            else CatalogResult.Value(CatalogPage(listOf(entry("old"))))
        }
        override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, lookup = CatalogAccess.AVAILABLE,
            refresh = CatalogAccess.AVAILABLE, playback = CatalogAccess.AVAILABLE)
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> { queries += query; return (override ?: reply)(query) }
        override fun lookup(input: String): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        val lengthRequests = mutableListOf<List<CatalogResource>>()
        var lengthCancels = 0
        override fun replayLengths(resources: List<CatalogResource>, canContinue: () -> Boolean): CatalogResult<Map<CatalogResource, Long>> {
            lengthRequests += resources
            return if (!canContinue()) CatalogResult.Failure(CatalogFailure.TEMPORARY) else lengthReply(resources)
        }
        override fun cancelReplayLengths() { lengthCancels++ }
        override fun close() { closes++ }
    }
    private var lengthReply: (List<CatalogResource>) -> CatalogResult<Map<CatalogResource, Long>> = { resources ->
        CatalogResult.Value(resources.filter { it.identity != "sumo/live" }.associateWith { 12240L })
    }
    // Applies to every catalog, including ones reopened after a failure.
    private var override: ((CatalogQuery) -> CatalogResult<CatalogPage>)? = null
    private val queued = mutableListOf<() -> Unit>()
    private val opened = mutableListOf<Pair<ConfiguredSource, Catalog>>()
    // Lengths are shared for the app session; each test starts without any.
    private val lengthCache = ReplayLengthCache()
    private val episodes = PickerCollectionEpisodes({ task -> task.run() }, { action -> queued += action },
        lengthCache = lengthCache) { source ->
        Catalog().also { opened += source to it }
    }
    private fun deliver() { val due = queued.toList(); queued.clear(); due.forEach { it() } }

    @Test fun pagesLoadNewestFirstAppendAndStayForTheSession() {
        val sumo = collection("394-72")
        assertFalse(episodes.state(sumo).loaded)
        episodes.load(sumo, more = false)
        assertTrue(episodes.state(sumo).loading)
        deliver()
        assertEquals(listOf("new", "mid"), episodes.state(sumo).entries.map { it.resource.identity })
        assertTrue(episodes.state(sumo).more)
        val catalog = opened.single().second
        assertEquals(CatalogQuery(parent = sumo.entry.resource, newest = true), catalog.queries.single())
        episodes.load(sumo, more = true); deliver()
        assertEquals(listOf("new", "mid", "old"), episodes.state(sumo).entries.map { it.resource.identity })
        assertFalse(episodes.state(sumo).more)
        assertEquals("next", catalog.queries.last().cursor)
        // No continuation left: More does nothing.
        episodes.load(sumo, more = true)
        assertEquals(2, catalog.queries.size)
    }

    @Test fun eachCollectionHasItsOwnCatalogSoContinuationsSurvive() {
        val first = collection("394-72"); val second = collection("394-73")
        episodes.load(first, false); deliver()
        episodes.load(second, false); deliver()
        assertEquals(2, opened.size)
        episodes.load(first, true); deliver()
        assertEquals("next", opened[0].second.queries.last().cursor)
    }

    @Test fun failuresAreShownAndRetryRestartsFromTheNewestPage() {
        val sumo = collection("394-72")
        episodes.load(sumo, false); deliver()
        val catalog = opened.single().second
        override = { CatalogResult.Failure(CatalogFailure.TEMPORARY) }
        episodes.load(sumo, true); deliver()
        val failed = episodes.state(sumo)
        assertEquals("Episodes could not be loaded.", failed.failure)
        assertEquals(2, failed.entries.size); assertFalse(failed.more)
        // A failed catalog is closed; Retry opens a fresh one from the newest page.
        assertEquals(1, catalog.closes)
        override = { CatalogResult.Value(CatalogPage(listOf(entry("newer")))) }
        episodes.load(sumo, false); deliver()
        assertEquals(listOf("newer"), episodes.state(sumo).entries.map { it.resource.identity })
        assertNull(episodes.state(sumo).failure)
        assertEquals(2, opened.size)
        assertNull(opened.last().second.queries.single().cursor)
    }

    @Test fun anEmptyCollectionAndAThrowingCatalogAreReportedHonestly() {
        val sumo = collection("394-72")
        episodes.load(sumo, false); deliver()
        override = { error("fixture failure") }
        episodes.load(sumo, false); deliver()
        assertEquals("Episodes could not be loaded.", episodes.state(sumo).failure)
        override = { CatalogResult.Value(CatalogPage(emptyList())) }
        episodes.load(sumo, false); deliver()
        assertTrue(episodes.state(sumo).loaded && episodes.state(sumo).entries.isEmpty() && episodes.state(sumo).failure == null)
    }

    @Test fun closeDiscardsLateResultsAndClosesCatalogs() {
        val sumo = collection("394-72")
        episodes.load(sumo, false)
        episodes.close()
        deliver()
        assertFalse(episodes.state(sumo).loaded)
        assertEquals(1, opened.single().second.closes)
    }

    @Test fun theAccountIsCheckedBeforeTheFirstPageAndItsRefusalIsShownWithoutBrowsing() {
        val checks = mutableListOf<(String?) -> Unit>()
        val gated = PickerCollectionEpisodes({ task -> task.run() }, { action -> queued += action },
            { _, _, done -> checks += done }) { source -> Catalog().also { opened += source to it } }
        val sumo = collection("394-72")
        gated.load(sumo, false)
        assertTrue(gated.state(sumo).loading); assertTrue(opened.isEmpty())
        // A second press while the check is pending is ignored.
        gated.load(sumo, false); assertEquals(1, checks.size)
        val refusal = "ABEMA account could not be renewed. Open ABEMA account in Providers to check the connection."
        checks.removeAt(0)(refusal)
        assertEquals(refusal, gated.state(sumo).failure)
        assertFalse(gated.state(sumo).loading); assertTrue(opened.isEmpty())
        // Retry checks again; once the account is ready the page loads.
        gated.load(sumo, false)
        checks.removeAt(0)(null); deliver()
        assertEquals(listOf("new", "mid"), gated.state(sumo).entries.map { it.resource.identity })
        assertNull(gated.state(sumo).failure)
        // A later page continues the admitted listing without another check.
        gated.load(sumo, true); deliver()
        assertTrue(checks.isEmpty())
        assertEquals(listOf("new", "mid", "old"), gated.state(sumo).entries.map { it.resource.identity })
    }

    @Test fun aRenewingAccountShowsTheSharedStatusOnlyWhileTheFirstPageWaits() {
        val progress = mutableListOf<(String) -> Unit>()
        val checks = mutableListOf<(String?) -> Unit>()
        val gated = PickerCollectionEpisodes({ task -> task.run() }, { action -> queued += action },
            { _, status, done -> progress += status; checks += done }) { source -> Catalog().also { opened += source to it } }
        val sumo = collection("394-72")
        gated.load(sumo, false)
        progress.single()(net.fstab.tachiai.provider.abema.account.ABEMA_ACCOUNT_RENEWING_MESSAGE)
        assertEquals(net.fstab.tachiai.provider.abema.account.ABEMA_ACCOUNT_RENEWING_MESSAGE, gated.state(sumo).status)
        assertTrue(gated.state(sumo).loading)
        // Ready: the status ends with the account wait and the page loads.
        checks.removeAt(0)(null)
        assertNull(gated.state(sumo).status)
        deliver()
        assertEquals(listOf("new", "mid"), gated.state(sumo).entries.map { it.resource.identity })
        // A late progress report after the answer never reappears.
        progress.removeAt(0)("late")
        assertNull(gated.state(sumo).status)
        // A refusal after renewing replaces the status with the refusal.
        gated.load(sumo, false)
        progress.removeAt(0)(net.fstab.tachiai.provider.abema.account.ABEMA_ACCOUNT_RENEWING_MESSAGE)
        val refusal = checkNotNull(net.fstab.tachiai.provider.abema.account.abemaAccountReadinessMessage(
            net.fstab.tachiai.provider.abema.account.AbemaAccountReadinessResult.TEMPORARY_FAILURE))
        checks.removeAt(0)(refusal)
        assertNull(gated.state(sumo).status); assertEquals(refusal, gated.state(sumo).failure)
        assertFalse(gated.state(sumo).loading)
    }

    @Test fun aLateAccountAnswerAfterCloseIsIgnored() {
        val checks = mutableListOf<(String?) -> Unit>()
        val gated = PickerCollectionEpisodes({ task -> task.run() }, { action -> queued += action },
            { _, _, done -> checks += done }) { source -> Catalog().also { opened += source to it } }
        val sumo = collection("394-72")
        gated.load(sumo, false); gated.close()
        checks.single()(null); deliver()
        assertTrue(opened.isEmpty()); assertFalse(gated.state(sumo).loaded)
    }

    // #622: ABEMA's episode groups, in its order, opening on the first.
    private val live = CatalogChildGroup("394-72_eg1", "生中継・見逃し")
    private val highlights = CatalogChildGroup("394-72_eg5", "ハイライト")
    private fun slot(id: String) = CatalogEntry(CatalogResource(ProviderId("abema"), "slot", "sumo/$id", CatalogIntent.BROADCAST),
        "大相撲LIVE $id", durationSeconds = 17100)
    private fun grouped(query: CatalogQuery): CatalogResult<CatalogPage> {
        val group = query.group ?: if (query.cursor == "next-eg5") highlights.id else live.id
        val entries = if (group == live.id) listOf(slot("day15"), entry("talk")) else
            if (query.cursor == null) listOf(entry("h15")) else listOf(entry("h14"))
        return CatalogResult.Value(CatalogPage(entries, "next-eg5".takeIf { group == highlights.id && query.cursor == null },
            listOf(live, highlights), group))
    }

    @Test fun groupsOpenOnTheFirstAndATabSwitchListsAnotherGroupWithItsOwnPages() {
        override = ::grouped
        val sumo = collection("394-72")
        episodes.load(sumo, false); deliver()
        var state = episodes.state(sumo)
        assertEquals(listOf(live, highlights), state.groups); assertEquals(live.id, state.group)
        // Broadcasts and videos stay mixed in the provider's order.
        assertEquals(listOf("sumo/day15", "talk"), state.entries.map { it.resource.identity })
        val catalog = opened.single().second
        assertNull(catalog.queries.single().group)
        episodes.selectGroup(sumo, highlights.id)
        state = episodes.state(sumo)
        assertTrue(state.loading); assertTrue(state.entries.isEmpty()); assertEquals(highlights.id, state.group)
        assertEquals(listOf(live, highlights), state.groups)
        deliver()
        assertEquals(CatalogQuery(parent = sumo.entry.resource, newest = true, group = highlights.id), catalog.queries.last())
        assertEquals(listOf("h15"), episodes.state(sumo).entries.map { it.resource.identity })
        assertTrue(episodes.state(sumo).more)
        // A continuation carries no group; the adapter's cursor already names it.
        episodes.load(sumo, more = true); deliver()
        assertEquals(CatalogQuery(parent = sumo.entry.resource, cursor = "next-eg5", newest = true), catalog.queries.last())
        assertEquals(listOf("h15", "h14"), episodes.state(sumo).entries.map { it.resource.identity })
        // The listed group and unknown groups do not browse.
        val calls = catalog.queries.size
        episodes.selectGroup(sumo, highlights.id); episodes.selectGroup(sumo, "394-72_eg9")
        assertEquals(calls, catalog.queries.size)
        // Retry after a failure repeats the selected group.
        override = { CatalogResult.Failure(CatalogFailure.TEMPORARY) }
        episodes.selectGroup(sumo, live.id); deliver()
        assertEquals("Episodes could not be loaded.", episodes.state(sumo).failure)
        assertEquals(live.id, episodes.state(sumo).group)
        override = ::grouped
        episodes.load(sumo, false); deliver()
        assertEquals(live.id, opened.last().second.queries.single().group)
        assertEquals(listOf("sumo/day15", "talk"), episodes.state(sumo).entries.map { it.resource.identity })
    }

    @Test fun aTabSwitchSupersedesAPendingLoadAndItsLateResultIsIgnored() {
        override = ::grouped
        val sumo = collection("394-72")
        episodes.load(sumo, false); deliver()
        episodes.selectGroup(sumo, highlights.id)
        // Another tab while the first switch is pending wins.
        episodes.selectGroup(sumo, live.id)
        assertEquals(live.id, episodes.state(sumo).group)
        deliver()
        assertEquals(live.id, episodes.state(sumo).group)
        assertEquals(listOf("sumo/day15", "talk"), episodes.state(sumo).entries.map { it.resource.identity })
        assertFalse(episodes.state(sumo).loading); assertFalse(episodes.state(sumo).more)
    }

    @Test fun routeFailuresPointToProvidersAndInterruptionsDoNot() {
        assertTrue(collectionEpisodesFailureMessage(CatalogFailure.NOT_VERIFIED).contains("Providers"))
        assertEquals("Episodes could not be loaded.", collectionEpisodesFailureMessage(CatalogFailure.ACCESS_REQUIRED))
    }

    // #672: real replay lengths replace scheduled spans in the background while the page is shown.
    private fun broadcastPage(vararg ids: String) = CatalogResult.Value(CatalogPage(ids.map(::slot) + entry("talk")))
    private fun durations(state: CollectionEpisodesState) = state.entries.associate { it.resource.identity to it.durationSeconds }

    @Test fun replayLengthsLoadOnlyWhileShownForListedBroadcastsAndStayForTheSession() {
        override = { broadcastPage("day15", "live") }
        val sumo = collection("394-72")
        episodes.load(sumo, false); deliver()
        // Not shown: the listed spans stay and no length is requested.
        assertTrue(opened.single().second.lengthRequests.isEmpty())
        assertEquals(mapOf("sumo/day15" to 17100L, "sumo/live" to 17100L, "talk" to null), durations(episodes.state(sumo)))
        episodes.shown(sumo, true); deliver()
        val catalog = opened.single().second
        // Only broadcasts are asked, once; videos keep their own lengths.
        assertEquals(listOf(listOf(slot("day15").resource, slot("live").resource)), catalog.lengthRequests)
        assertEquals(mapOf("sumo/day15" to 12240L, "sumo/live" to 17100L, "talk" to null), durations(episodes.state(sumo)))
        // A broadcast without a length (live, in progress or failed) is not asked again for this listing.
        episodes.shown(sumo, false); episodes.shown(sumo, true); deliver()
        assertEquals(1, catalog.lengthRequests.size)
        // A reload retries it and a known length applies at once, without asking again.
        lengthReply = { CatalogResult.Value(emptyMap()) }
        episodes.load(sumo, false)
        deliver()
        assertEquals(listOf(slot("live").resource), catalog.lengthRequests.last())
        assertEquals(12240L, durations(episodes.state(sumo))["sumo/day15"])
    }

    @Test fun aFailedOrCancelledLengthRequestKeepsTheSpanAndIsRetriedWithTheNextPage() {
        val tasks = mutableListOf<Runnable>()
        val paced = PickerCollectionEpisodes({ task -> tasks += task }, { action -> queued += action },
            lengthCache = lengthCache) { source ->
            Catalog().also { opened += source to it }
        }
        fun run() { while (tasks.isNotEmpty() || queued.isNotEmpty()) { tasks.toList().also { tasks.clear() }.forEach { it.run() }; deliver() } }
        override = { query -> if (query.cursor == null) CatalogResult.Value(CatalogPage(listOf(slot("day15")), "next"))
            else CatalogResult.Value(CatalogPage(listOf(slot("day14")))) }
        val sumo = collection("394-72")
        paced.shown(sumo, true)
        paced.load(sumo, false); run()
        val catalog = opened.single().second
        assertEquals(listOf(listOf(slot("day15").resource)), catalog.lengthRequests)
        assertEquals(12240L, paced.state(sumo).entries.single().durationSeconds)
        // A failure shows no error and keeps the span.
        lengthReply = { CatalogResult.Failure(CatalogFailure.TEMPORARY) }
        paced.load(sumo, true)
        tasks.removeAt(0).run(); deliver()
        assertEquals(17100L, paced.state(sumo).entries.last().durationSeconds)
        assertNull(paced.state(sumo).failure)
        // A page load requested while a length request runs cancels it.
        lengthReply = { paced.load(sumo, false); CatalogResult.Failure(CatalogFailure.TEMPORARY) }
        tasks.removeAt(0).run(); deliver()
        assertEquals(1, catalog.lengthCancels)
        assertEquals(listOf(slot("day14").resource), catalog.lengthRequests.last())
        lengthReply = { resources -> CatalogResult.Value(resources.associateWith { 8853L }) }
        run()
        // The newest page lists day15 (known) and the retried request covers only the unknown.
        assertEquals(12240L, paced.state(sumo).entries.single().durationSeconds)
        paced.load(sumo, true); run()
        assertEquals(listOf(slot("day14").resource), catalog.lengthRequests.last())
        assertEquals(listOf(12240L, 8853L), paced.state(sumo).entries.map { it.durationSeconds })
        // A request queued before the page is left never reaches the provider.
        override = { CatalogResult.Value(CatalogPage(listOf(slot("day13")))) }
        paced.load(sumo, false); tasks.removeAt(0).run(); deliver()
        assertEquals(1, tasks.size)
        paced.shown(sumo, false)
        val asked = catalog.lengthRequests.size
        run()
        assertEquals(asked, catalog.lengthRequests.size)
        paced.close(); run()
        assertFalse(paced.state(sumo).loaded)
    }
}
