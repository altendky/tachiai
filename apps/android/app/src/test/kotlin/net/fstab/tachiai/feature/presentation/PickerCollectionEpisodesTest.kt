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
        override fun close() { closes++ }
    }
    // Applies to every catalog, including ones reopened after a failure.
    private var override: ((CatalogQuery) -> CatalogResult<CatalogPage>)? = null
    private val queued = mutableListOf<() -> Unit>()
    private val opened = mutableListOf<Pair<ConfiguredSource, Catalog>>()
    private val episodes = PickerCollectionEpisodes({ task -> task.run() }, { action -> queued += action }) { source ->
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
            { _, done -> checks += done }) { source -> Catalog().also { opened += source to it } }
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

    @Test fun aLateAccountAnswerAfterCloseIsIgnored() {
        val checks = mutableListOf<(String?) -> Unit>()
        val gated = PickerCollectionEpisodes({ task -> task.run() }, { action -> queued += action },
            { _, done -> checks += done }) { source -> Catalog().also { opened += source to it } }
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
}
