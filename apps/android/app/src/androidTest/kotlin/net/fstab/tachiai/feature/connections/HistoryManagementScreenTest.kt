package net.fstab.tachiai.feature.connections

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.*
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// A synthetic optional History adapter consumed by the real shared controller,
// UI and local codec. No production adapter advertises history in this test.
class HistoryManagementScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun text(value: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(value))
        return compose.onNodeWithText(value).performScrollTo()
    }
    private fun description(value: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(value))
        return compose.onNodeWithContentDescription(value).performScrollTo()
    }
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
    }
    private class Fixture {
        val instance = defaultProviderInstances().first()
        val provider = ProviderId(instance.service.name.lowercase(java.util.Locale.ROOT))
        val episode = entry("fixture-episode", "Never-live fixture episode", CatalogAvailability.AVAILABLE)
        val missing = entry("fixture-missing", "Unavailable fixture replay", CatalogAvailability.UNAVAILABLE)
        val series = CatalogEntry(CatalogResource(provider, "series", "fixture-series", CatalogIntent.COLLECTION),
            "Unavailable fixture series", CatalogAvailability.UNAVAILABLE)
        val memory = Memory()
        val store = ConfiguredSourceStore(memory, instance.id, provider)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var denied = false
        var large = false
        private fun entry(identity: String, title: String, availability: CatalogAvailability) =
            CatalogEntry(CatalogResource(provider, "video", identity, CatalogIntent.VIDEO), title, availability)
        private fun catalog() = object : ProviderCatalog {
            override val providerId = provider
            override val instanceId = instance.id
            override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE,
                search = CatalogAccess.AVAILABLE, lookup = CatalogAccess.AVAILABLE, children = CatalogAccess.AVAILABLE,
                collections = listOf(CatalogCollection("history", "History", CatalogAccess.AVAILABLE)), initialCollectionId = "history")
            override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
                if (denied) return CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)
                if (query.parent == series.resource) return CatalogResult.Value(CatalogPage(listOf(episode)))
                if (large) {
                    val page = query.cursor?.removePrefix("page-")?.toIntOrNull() ?: 0
                    return CatalogResult.Value(CatalogPage((1..100).map {
                        entry("item-${page * 100 + it}", "Large history fixture ${page * 100 + it}", CatalogAvailability.AVAILABLE)
                    }, "page-${page + 1}"))
                }
                return when (query.cursor) {
                    null -> CatalogResult.Value(CatalogPage(listOf(episode, series), "empty"))
                    "empty" -> CatalogResult.Value(CatalogPage(emptyList(), "last"))
                    "last" -> CatalogResult.Value(CatalogPage(listOf(episode, missing)))
                    else -> CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
                }
            }
            override fun lookup(input: String): CatalogResult<CatalogEntry> =
                if (denied) CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) else CatalogResult.Value(episode)
            override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
            override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        }
        private fun newController() = StreamManagementController(instance, catalog(), store, { emptyList() }, scope, Dispatchers.Unconfined)
        var controller by mutableStateOf(newController())
        init { controller.load() }
        fun reopen() { controller.close(); controller = newController().also { it.load() } }
        fun close() { controller.close(); scope.cancel() }
        @Composable fun Render() {
            val state by controller.state.collectAsState()
            TachiaiPrototypeTheme {
                StreamManagementScreen(state, controller::search, controller::all, controller::collection,
                    controller::children, controller::more, controller::lookup, controller::add, controller::remove,
                    controller::move, controller::retry, {})
            }
        }
    }

    @Test fun historyPagesUnavailableItemsAndExplicitLocalActionsUseTheSharedScreen() {
        val fixture = Fixture()
        try {
            compose.setContent { fixture.Render() }
            text("History").assertExists()
            compose.runOnIdle { assertNull(fixture.memory.bytes) }
            description("Add Never-live fixture episode").performClick().assertIsNotEnabled()
            description("Add Unavailable fixture series").performClick()
            text("More items").performClick()
            text("More items").performClick()
            description("Add Unavailable fixture replay").performClick().assertIsNotEnabled()
            compose.runOnIdle {
                assertEquals(listOf(fixture.episode.resource, fixture.series.resource, fixture.missing.resource),
                    fixture.store.read { emptyList() }.map { it.entry.resource })
            }
            description("Browse Unavailable fixture series").performClick()
            description("Add Never-live fixture episode").assertIsNotEnabled()
            description("Remove Unavailable fixture replay").performClick()
            compose.runOnIdle { assertEquals(2, fixture.store.read { emptyList() }.size) }
        } finally { fixture.close() }
    }

    @Test fun accessLossAndRecreationClearDiscoveryDraftsWhileConfiguredItemsRemain() {
        val fixture = Fixture(); val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { fixture.Render() }
            description("Add Never-live fixture episode").performClick()
            text("Search streams").performTextInput("private fixture search")
            text("Provider URL or channel").performTextInput("https://fixture.invalid/?token=unvalidated")
            compose.runOnIdle { fixture.denied = true; fixture.controller.more() }
            text("Catalog account access is required. Your configured streams are retained.").assertExists()
            description("Remove Never-live fixture episode").assertIsEnabled()
            compose.onNodeWithContentDescription("Add Never-live fixture episode").assertDoesNotExist()
            compose.runOnIdle { fixture.denied = false }
            text("All").assertIsNotEnabled()
            text("Retry catalog").performClick()
            text("Search").assertIsNotEnabled(); text("Look up").assertIsNotEnabled()
            text("Search streams").performTextInput("another private fixture")
            text("Provider URL or channel").performTextInput("https://fixture.invalid/private")
            // Close and replace in one event: the new controller must have a
            // distinct privacy revision even before the old clear recomposes.
            compose.runOnIdle { fixture.reopen() }
            text("Search").assertIsNotEnabled(); text("Look up").assertIsNotEnabled()
            text("Search streams").performTextInput("unsaved fixture")
            text("Provider URL or channel").performTextInput("https://fixture.invalid/unsaved")
            restoration.emulateSavedInstanceStateRestore()
            text("Search").assertIsNotEnabled(); text("Look up").assertIsNotEnabled()
            description("Remove Never-live fixture episode").assertExists()
        } finally { fixture.close() }
    }

    @Test fun resultCapExplainsIncompleteDiscoveryAndAnotherQueryClearsTheNotice() {
        val fixture = Fixture()
        try {
            fixture.large = true; fixture.controller.collection("history")
            compose.setContent { fixture.Render() }
            repeat(4) { text("More items").performClick() }
            text("Showing the first 500 results. More results were withheld; narrow your search or choose another collection.").assertExists()
            compose.onNodeWithText("More items").assertDoesNotExist()
            compose.runOnIdle { assertEquals(500, fixture.controller.state.value.entries.size); assertNull(fixture.memory.bytes) }
            compose.runOnIdle { fixture.large = false }
            text("History").performClick()
            compose.onNodeWithText("Showing the first 500 results. More results were withheld; narrow your search or choose another collection.").assertDoesNotExist()
            text("More items").assertExists()
        } finally { fixture.close() }
    }
}
