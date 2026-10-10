package net.fstab.tachiai.feature.connections

import androidx.compose.runtime.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import java.util.UUID
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StreamManagementScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        // A lazy row may not exist in the semantics tree until its list scrolls to it.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }

    private fun text(value: String) = scrollTo(hasText(value))
    private fun description(value: String) = scrollTo(hasContentDescription(value))

    private class Fixture(service: PrototypeService) {
        private val provider = ProviderId(service.name.lowercase(java.util.Locale.ROOT))
        private val instance = defaultProviderInstances().single { it.service == service }
        val collection = if (service == PrototypeService.TWITCH)
            CatalogCollection("following", "Following", CatalogAccess.AVAILABLE)
        else CatalogCollection("my_list", "My List", CatalogAccess.AUTHORIZATION_REQUIRED)
        val offline = CatalogEntry(CatalogResource(provider, "channel", "offline", CatalogIntent.CHANNEL), "Offline channel", CatalogAvailability.OFFLINE)
        val upcoming = CatalogEntry(CatalogResource(provider, "broadcast", "future", CatalogIntent.BROADCAST), "Upcoming program", CatalogAvailability.UPCOMING, 1_000)
        val parent = CatalogEntry(CatalogResource(provider, "series", "show", CatalogIntent.COLLECTION), "A show")
        val episode = CatalogEntry(CatalogResource(provider, "episode", "show_1", CatalogIntent.VIDEO), "Never-live episode", CatalogAvailability.AVAILABLE)
        val all = listOf(offline, upcoming, parent)
        var calls = mutableListOf<String>()
        var state by mutableStateOf(StreamManagementState(instance, configured = emptyList(),
            capabilities = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, search = CatalogAccess.AVAILABLE,
                lookup = CatalogAccess.AVAILABLE, children = CatalogAccess.AVAILABLE,
                collections = listOf(collection, CatalogCollection("history", "History", CatalogAccess.UNSUPPORTED))),
            entries = all, nextCursor = "opaque:+page2"))
        @Composable fun Render() {
            TachiaiPrototypeTheme {
                StreamManagementScreen(state,
                    onSearch = { search -> calls.add("search:$search"); state = state.copy(entries = all.filter { it.title.contains(search, true) }, nextCursor = null) },
                    onAll = { calls.add("all"); state = state.copy(entries = all, query = CatalogQuery(), failure = null, nextCursor = "opaque:+page2") },
                    onCollection = { id -> calls.add("collection:$id"); state = state.copy(query = CatalogQuery(collectionId = id),
                        entries = if (id == collection.id && collection.access == CatalogAccess.AVAILABLE) listOf(offline) else emptyList(),
                        nextCursor = null, failure = if (id == "history") CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
                            else if (collection.access == CatalogAccess.AUTHORIZATION_REQUIRED) CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) else null) },
                    onChildren = { resource -> calls.add("children:${resource.identity}"); state = state.copy(query = CatalogQuery(parent = resource),
                        entries = listOf(episode), nextCursor = null, failure = null) },
                    onMore = { calls.add("more"); state = state.copy(entries = state.entries + episode, nextCursor = null) },
                    onLookup = { input -> calls.add("lookup:$input"); state = state.copy(entries = listOf(episode), nextCursor = null, failure = null) },
                    onAdd = { entry -> calls.add("add:${entry.resource.identity}"); state = state.copy(configured =
                        if (state.configured.orEmpty().any { it.entry.resource == entry.resource }) state.configured
                        else state.configured.orEmpty() + ConfiguredSource(UUID.randomUUID().toString(), instance.id, entry)) },
                    onRemove = { id -> calls.add("remove"); state = state.copy(configured = state.configured.orEmpty().filterNot { it.id == id }) },
                    onMove = { id, delta -> calls.add("move:$delta"); val sources = state.configured.orEmpty().toMutableList()
                        val index = sources.indexOfFirst { it.id == id }; val moved = sources.removeAt(index)
                        sources.add(index + delta, moved); state = state.copy(configured = sources) },
                    onRetry = { calls.add("retry"); state = state.copy(failure = null, storageFailed = false) },
                    onBack = { calls.add("back") })
            }
        }
    }

    @Test fun adapterBrowseLabelIsTruthfulAndBlankSearchCannotDispatch() {
        val fixture = Fixture(PrototypeService.TWITCH)
        fixture.state = fixture.state.copy(capabilities = fixture.state.capabilities!!.copy(
            browseTitle = "Live channels", initialCollectionId = fixture.collection.id),
            query = CatalogQuery(collectionId = fixture.collection.id), entries = listOf(fixture.offline), nextCursor = null)
        compose.setContent { fixture.Render() }
        text("Following").assertExists()
        text("Live channels").performClick()
        compose.onNodeWithText("All").assertDoesNotExist()
        compose.runOnIdle { assertTrue(fixture.calls.contains("all")); assertEquals(CatalogQuery(), fixture.state.query) }
        text("Search").assertIsNotEnabled()
        text("Search streams").performTextInput("   ")
        text("Search").assertIsNotEnabled()
        text("Search streams").performTextReplacement("Offline")
        text("Search").performClick()
        compose.runOnIdle { assertTrue(fixture.calls.contains("search:Offline")) }
    }

    @Test fun unavailableBrowseLabelExplainsAccessAndPreservesConfiguredItems() {
        val fixture = Fixture(PrototypeService.ABEMA)
        fixture.state = fixture.state.copy(configured = listOf(ConfiguredSource(UUID.randomUUID().toString(),
            fixture.state.instance.id, fixture.offline)), capabilities = fixture.state.capabilities!!.copy(
            browse = CatalogAccess.RECONNECT_REQUIRED, browseTitle = "Available streams"),
            entries = emptyList(), nextCursor = null, failure = CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED))
        compose.setContent { fixture.Render() }
        text("Available streams: Reconnect the catalog account to access this list.").assertExists()
        text("Catalog account access is required. Your configured streams are retained.").assertExists()
        description("Remove Offline channel").assertIsEnabled()
        compose.onNodeWithText("No catalog items found.").assertDoesNotExist()
        compose.runOnIdle { assertEquals(fixture.offline.resource, fixture.state.configured!!.single().entry.resource) }
    }

    @Test fun sameScreenShowsProviderCollectionsAndPreservesOfflineUpcomingAndOnDemandChoices() {
        val fixtures = defaultProviderInstances().map { Fixture(it.service) }
        val active = mutableStateOf(fixtures.first())
        compose.setContent { active.value.Render() }
        fixtures.forEach { fixture ->
            compose.runOnIdle { active.value = fixture }
            text(fixture.collection.title).assertExists()
            if (fixture.collection.access == CatalogAccess.AUTHORIZATION_REQUIRED)
                text("My List: Connect a catalog account to access this list.").assertExists()
            text("History: This list is not supported.").assertExists()
            text("Ongoing channel · Offline").assertExists()
            description("Add Offline channel").performClick()
            description("Add Offline channel").assertIsNotEnabled()
            description("Add Upcoming program").performClick()
            description("Move Upcoming program earlier").performClick()
            compose.runOnIdle { assertEquals(fixture.upcoming.resource, fixture.state.configured!!.first().entry.resource) }
            description("Browse A show").performClick()
            text("On-demand video · Available").assertExists()
            description("Add Never-live episode").performClick()
            compose.runOnIdle {
                assertEquals(fixture.parent.resource, fixture.state.query.parent)
                assertEquals(CatalogIntent.VIDEO, fixture.state.configured!!.last().entry.resource.intent)
                assertTrue(fixture.calls.contains("children:show"))
            }
        }
    }

    @Test fun searchLookupPagesUseSharedCallbacksWithoutRestoringDiscoveryInputs() {
        val fixture = Fixture(PrototypeService.TWITCH)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { fixture.Render() }
        text("More items").performClick()
        text("Never-live episode").assertExists()
        text("Search streams").performTextInput("Offline")
        restoration.emulateSavedInstanceStateRestore()
        text("Search").assertIsNotEnabled()
        text("Search streams").performTextInput("Offline")
        text("Search").performClick()
        compose.runOnIdle { assertTrue(fixture.calls.contains("search:Offline")) }
        text("Provider URL or channel").performTextInput("https://provider.example.test/video/1")
        text("Look up").performClick()
        compose.runOnIdle { assertTrue(fixture.calls.contains("lookup:https://provider.example.test/video/1")) }
        text("Following").performClick()
        compose.runOnIdle { assertEquals("following", fixture.state.query.collectionId) }
        text("History").performClick()
        text("This catalog operation is not supported.").assertExists()
    }

    @Test fun unvalidatedLookupUrlIsNotRestoredFromSavedState() {
        val fixture = Fixture(PrototypeService.TWITCH)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { fixture.Render() }
        text("Provider URL or channel")
            .performTextInput("https://provider.example.test/video/1?token=unvalidated-fixture")
        text("Look up").assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore()
        text("Look up").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(fixture.calls.isEmpty()) }
    }

    @Test fun removalEmptyStatesAndErrorsDoNotReplaceConfiguredItemsOrEnableFailedStorageWrites() {
        val fixture = Fixture(PrototypeService.ABEMA)
        compose.setContent { fixture.Render() }
        text("No configured streams. Add an item below to show it on your selection page.").assertExists()
        description("Add Offline channel").performClick()
        text("My List").performClick()
        text("Catalog account access is required. Your configured streams are retained.").assertExists()
        description("Remove Offline channel").assertExists()
        compose.runOnIdle { fixture.state = fixture.state.copy(storageFailed = true, message = "Configured streams could not be read.") }
        description("Remove Offline channel").assertIsNotEnabled()
        text("Retry").performClick()
        description("Remove Offline channel").performClick()
        compose.runOnIdle { assertTrue(fixture.state.configured!!.isEmpty()) }
        text("No configured streams. Add an item below to show it on your selection page.").assertExists()
        compose.runOnIdle { fixture.state = fixture.state.copy(failure = CatalogResult.Failure(CatalogFailure.TEMPORARY)) }
        text("Retry catalog").performClick()
        compose.runOnIdle { assertTrue(fixture.calls.contains("retry")) }
    }

    @Test fun catalogButtonsSupportKeyboardFocusAndActivation() {
        val fixture = Fixture(PrototypeService.TWITCH)
        lateinit var inputMode: InputModeManager
        compose.setContent {
            inputMode = LocalInputModeManager.current
            fixture.Render()
        }
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        val all = text("All")
        all.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        all.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertTrue(fixture.calls.contains("all")) }
        all.performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Following").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertTrue(fixture.calls.contains("collection:following")) }
    }
}
