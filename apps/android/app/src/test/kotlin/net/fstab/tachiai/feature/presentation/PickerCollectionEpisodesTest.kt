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
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> { queries += query; return reply(query) }
        override fun lookup(input: String): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        override fun close() { closes++ }
    }
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
        catalog.reply = { CatalogResult.Failure(CatalogFailure.TEMPORARY) }
        episodes.load(sumo, true); deliver()
        val failed = episodes.state(sumo)
        assertEquals("Episodes could not be loaded.", failed.failure)
        assertEquals(2, failed.entries.size); assertFalse(failed.more)
        catalog.reply = { CatalogResult.Value(CatalogPage(listOf(entry("newer")))) }
        episodes.load(sumo, false); deliver()
        assertEquals(listOf("newer"), episodes.state(sumo).entries.map { it.resource.identity })
        assertNull(episodes.state(sumo).failure)
        assertNull(catalog.queries.last().cursor)
    }

    @Test fun anEmptyCollectionAndAThrowingCatalogAreReportedHonestly() {
        val sumo = collection("394-72")
        episodes.load(sumo, false); deliver()
        opened.single().second.reply = { error("fixture failure") }
        episodes.load(sumo, false); deliver()
        assertEquals("Episodes could not be loaded.", episodes.state(sumo).failure)
        opened.single().second.reply = { CatalogResult.Value(CatalogPage(emptyList())) }
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
}
