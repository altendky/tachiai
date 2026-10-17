package net.fstab.tachiai.feature.connections

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
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

    private fun text(value: String): SemanticsNodeInteraction {
        val overlay = hasText(value) and !hasAnyAncestor(hasScrollToIndexAction())
        return if (compose.onAllNodes(overlay).fetchSemanticsNodes().isNotEmpty()) compose.onNode(overlay)
            else scrollTo(hasText(value))
    }
    private fun description(value: String) = scrollTo(hasContentDescription(value))
    private fun info(section: String, explanation: String) {
        description("Info about $section").performClick()
        compose.onNodeWithText(explanation, substring = true).assertExists()
        compose.onNodeWithText("Close").performClick()
    }

    private class Fixture(service: PrototypeService, private val historyAccess: CatalogAccess = CatalogAccess.UNSUPPORTED) {
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
        var collectionState: ((String, StreamManagementState) -> StreamManagementState)? = null
        private var previous: StreamManagementState? = null
        private var nextContext = 1L
        private val tabs = mutableMapOf<String, StreamManagementState>()
        var state by mutableStateOf(StreamManagementState(instance, configured = emptyList(),
            capabilities = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, search = CatalogAccess.AVAILABLE,
                lookup = CatalogAccess.AVAILABLE, children = CatalogAccess.AVAILABLE,
                childrenTitle = if (service == PrototypeService.TWITCH) "Videos" else "Items",
                childrenHeadingPrefix = if (service == PrototypeService.TWITCH) "Videos from" else "Items under",
                childCapabilities = listOf(
                    CatalogChildrenCapability("channel", CatalogIntent.CHANNEL, CatalogAccess.AVAILABLE,
                        if (service == PrototypeService.TWITCH) "Browse videos" else "Browse programs",
                        if (service == PrototypeService.TWITCH) "Videos from" else "Programs from"),
                    CatalogChildrenCapability("series", CatalogIntent.COLLECTION, CatalogAccess.AVAILABLE, "Browse episodes", "Episodes from")),
                collections = listOf(collection, CatalogCollection("history", "History", historyAccess))),
            entries = all, nextCursor = "opaque:+page2", selectedTabId = DISCOVERY_ALL_TAB))
        private fun tab(id: String, change: () -> StreamManagementState) {
            state.selectedTabId?.let { tabs[it] = state }
            state = tabs[id]?.copy(configured = state.configured, link = null) ?: change().copy(
                discoveryContextId = nextContext++, selectedTabId = id)
            state = state.copy(retainedDiscoveryContextIds = tabs.values.map { it.discoveryContextId }.distinct()
                .filterNot { it == state.discoveryContextId })
        }
        @Composable fun Render() {
            TachiaiPrototypeTheme {
                StreamManagementScreen(state,
                    onSearch = { search -> calls.add("search:$search"); state = state.copy(query = CatalogQuery(search = search),
                        selectedTabId = null, entries = all.filter { it.title.contains(search, true) }, nextCursor = null) },
                    onAll = { calls.add("all"); tab(DISCOVERY_ALL_TAB) { state.copy(entries = all, query = CatalogQuery(), failure = null, nextCursor = "opaque:+page2") } },
                    onCollection = { id -> calls.add("collection:$id"); tab(id) { state.copy(query = CatalogQuery(collectionId = id),
                        entries = if (id == collection.id && collection.access == CatalogAccess.AVAILABLE) listOf(offline) else emptyList(),
                        nextCursor = null, failure = if (id == "history") CatalogResult.Failure(
                            if (historyAccess == CatalogAccess.NOT_VERIFIED) CatalogFailure.NOT_VERIFIED else CatalogFailure.UNSUPPORTED)
                            else if (collection.access == CatalogAccess.AUTHORIZATION_REQUIRED) CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) else null) }
                        collectionState?.let { state = it(id, state) }
                    },
                    onChildren = { resource ->
                        calls.add("children:${resource.identity}")
                        previous = state
                        val title = (state.entries + state.configured.orEmpty().map { it.entry })
                            .firstOrNull { it.resource == resource }?.title ?: resource.identity
                        state = state.copy(query = CatalogQuery(parent = resource), entries = listOf(episode), nextCursor = null,
                            failure = null, discoveryContextId = nextContext++, retainedDiscoveryContextIds = listOf(state.discoveryContextId),
                            childHeading = "${state.capabilities!!.childrenFor(resource, provider)!!.headingPrefix} $title", navigationBackLabel = "Back to channel list")
                    },
                    onMore = { calls.add("more"); state = state.copy(entries = state.entries + episode, nextCursor = null) },
                    onAdd = { entry -> calls.add("add:${entry.resource.identity}"); state = state.copy(configured =
                        if (state.configured.orEmpty().any { it.entry.resource == entry.resource }) state.configured
                        else state.configured.orEmpty() + ConfiguredSource(UUID.randomUUID().toString(), instance.id, entry)) },
                    onRemove = { id -> calls.add("remove"); state = state.copy(configured = state.configured.orEmpty().filterNot { it.id == id }) },
                    onMove = { id, delta -> calls.add("move:$delta"); val sources = state.configured.orEmpty().toMutableList()
                        val index = sources.indexOfFirst { it.id == id }; val moved = sources.removeAt(index)
                        sources.add(index + delta, moved); state = state.copy(configured = sources) },
                    onRetry = { calls.add("retry"); state = state.copy(failure = null, storageFailed = false, message = null) },
                    onBack = { calls.add("back") }, onRefresh = { calls.add("refresh:$it") }, onReorder = { ids ->
                        calls.add("reorder")
                        state = state.copy(configured = ids.map { id -> state.configured.orEmpty().single { it.id == id } })
                        CompletableDeferred(Unit)
                    }, onDiscoveryBack = {
                        calls.add("discovery-back")
                        previous?.let { state = it.copy(configured = state.configured, saving = state.saving, loading = false) }
                        previous = null
                    }, onOpenLink = { state = state.copy(link = StreamLinkState()) },
                    onResolveLink = { input -> calls.add("lookup:$input"); state = state.copy(link = StreamLinkState(entry = episode)) },
                    onDismissLink = { state = state.copy(link = null) }, onEditLink = { state = state.copy(link = StreamLinkState()) }, onAddLink = {
                        val entry = state.link!!.entry!!
                        calls.add("add:${entry.resource.identity}")
                        state = state.copy(configured = state.configured.orEmpty() + ConfiguredSource(UUID.randomUUID().toString(),
                            instance.id, entry), link = null)
                    })
            }
        }
    }

    @Test fun adapterBrowseLabelIsTruthfulAndBlankSearchCannotDispatch() {
        val fixture = Fixture(PrototypeService.TWITCH)
        fixture.state = fixture.state.copy(capabilities = fixture.state.capabilities!!.copy(
            browseTitle = "Live channels", initialCollectionId = fixture.collection.id),
            query = CatalogQuery(collectionId = fixture.collection.id), selectedTabId = fixture.collection.id,
            entries = listOf(fixture.offline), nextCursor = null)
        compose.setContent { fixture.Render() }
        text("Following").assertExists()
        text("Live channels").performClick()
        compose.onNodeWithText("All").assertDoesNotExist()
        compose.runOnIdle { assertTrue(fixture.calls.contains("all")); assertEquals(CatalogQuery(), fixture.state.query) }
        description("Search streams").assertIsNotEnabled()
        text("Search streams").performTextInput("   ")
        description("Search streams").assertIsNotEnabled()
        text("Search streams").performTextReplacement("Offline")
        description("Search streams").performClick()
        compose.runOnIdle { assertTrue(fixture.calls.contains("search:Offline")) }
    }

    @Test fun inlineSearchAndKeyboardShareSubmissionAndGuardsAtLargeText() {
        val fixture = Fixture(PrototypeService.ABEMA)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp)) { fixture.Render() }
            }
        }
        val field = text("Search streams")
        compose.onNodeWithText("Search", useUnmergedTree = true).assertDoesNotExist()
        field.performImeAction()
        compose.runOnIdle { assertTrue(fixture.calls.isEmpty()) }
        field.performTextInput("Offline")
        val inputBounds = text("Search streams").fetchSemanticsNode().boundsInRoot
        val iconBounds = description("Search streams").fetchSemanticsNode().boundsInRoot
        assertTrue(iconBounds.right <= inputBounds.right && iconBounds.left > inputBounds.center.x)
        assertTrue(iconBounds.top >= inputBounds.top && iconBounds.bottom <= inputBounds.bottom)
        description("Search streams").performClick()
        text("Search streams").performImeAction()
        compose.runOnIdle { assertEquals(listOf("search:Offline", "search:Offline"), fixture.calls) }
        listOf<(StreamManagementState) -> StreamManagementState>(
            { it.copy(loading = true) }, { it.copy(saving = true) },
            { it.copy(storageFailed = true) }, { it.copy(configured = null) }
        ).forEach { blocked ->
            compose.runOnIdle { fixture.state = blocked(fixture.state.copy(loading = false, saving = false,
                storageFailed = false, configured = emptyList())) }
            description("Search streams").assertIsNotEnabled()
            text("Search streams").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(2, fixture.calls.size) }
        }
    }

    @Test fun connectedFollowingDoesNotImplyVerifiedHistoryOrLoseConfiguredItems() {
        val fixture = Fixture(PrototypeService.TWITCH, CatalogAccess.NOT_VERIFIED)
        val saved = ConfiguredSource(UUID.randomUUID().toString(), fixture.state.instance.id, fixture.offline)
        fixture.state = fixture.state.copy(configured = listOf(saved))
        compose.setContent { fixture.Render() }
        text("Following").performClick()
        info("Find streams", "History: Access to this list has not been verified.")
        text("History").performClick()
        text("Provider catalog access has not been verified.").assertExists()
        compose.onNodeWithContentDescription("Dismiss stream notice").performClick()
        description("Remove Offline channel").assertIsEnabled()
        compose.onNodeWithText("No catalog items found.").assertDoesNotExist()
        compose.onNodeWithText("Provider access is not connected yet.").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(saved), fixture.state.configured)
            assertEquals(CatalogFailure.NOT_VERIFIED, fixture.state.failure!!.reason)
            assertEquals("collection:history", fixture.calls.last())
        }
        text("Following").performClick()
        description("Add Offline channel").assertIsNotEnabled()
        compose.runOnIdle { assertNull(fixture.state.failure); assertEquals(listOf(saved), fixture.state.configured) }
    }

    @Test fun unavailableBrowseLabelExplainsAccessAndPreservesConfiguredItems() {
        val fixture = Fixture(PrototypeService.ABEMA)
        fixture.state = fixture.state.copy(configured = listOf(ConfiguredSource(UUID.randomUUID().toString(),
            fixture.state.instance.id, fixture.offline)), capabilities = fixture.state.capabilities!!.copy(
            browse = CatalogAccess.RECONNECT_REQUIRED, browseTitle = "Available streams"),
            entries = emptyList(), nextCursor = null, failure = CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED))
        compose.setContent { fixture.Render() }
        info("Find streams", "Available streams: Reconnect the catalog account to access this list.")
        text("Catalog account access is required. Your configured streams are retained.").assertExists()
        compose.onNodeWithContentDescription("Dismiss stream notice").performClick()
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
                info("Find streams", "My List: Connect a catalog account to access this list.")
            info("Find streams", "History: This list is not supported.")
            text("Ongoing channel · Offline").assertExists()
            description("Add Offline channel").performClick()
            description("Add Offline channel").assertIsNotEnabled()
            description("Add Upcoming program").performClick()
            description("Reorder Upcoming program").performClick()
            compose.onNodeWithContentDescription("Move Upcoming program earlier").performClick()
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
        description("Search streams").assertIsNotEnabled()
        text("Search streams").performTextInput("Offline")
        description("Search streams").performClick()
        compose.runOnIdle { assertTrue(fixture.calls.contains("search:Offline")) }
        text("Add link").performClick()
        text("Provider URL or channel").performTextInput("https://provider.example.test/video/1")
        text("Add").performClick()
        compose.runOnIdle { assertTrue(fixture.calls.contains("lookup:https://provider.example.test/video/1")) }
        text("Cancel").performClick()
        text("Following").performClick()
        compose.runOnIdle { assertEquals("following", fixture.state.query.collectionId) }
        text("History").performClick()
        text("This catalog operation is not supported.").assertExists()
    }

    @Test fun unvalidatedLookupUrlIsNotRestoredFromSavedState() {
        val fixture = Fixture(PrototypeService.TWITCH)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { fixture.Render() }
        text("Add link").performClick()
        text("Provider URL or channel")
            .performTextInput("https://provider.example.test/video/1?token=unvalidated-fixture")
        text("Add").assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore()
        text("Add").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(fixture.calls.isEmpty()) }
    }

    @Test fun addLinkDialogResolvesThenSavesExplicitlyWithoutReplacingBrowseResults() {
        val fixture = Fixture(PrototypeService.ABEMA)
        compose.setContent { fixture.Render() }
        text("Add link").performClick()
        text("Add by URL or channel").assertExists()
        text("Add").assertIsNotEnabled()
        text("Provider URL or channel").performTextInput("https://provider.example.test/video/1")
        text("Add").performClick()
        text("Never-live episode").assertExists()
        compose.runOnIdle {
            assertEquals(fixture.all, fixture.state.entries)
            assertTrue(fixture.state.configured!!.isEmpty())
            assertNotNull(fixture.state.link!!.entry)
        }
        text("Add").performClick()
        compose.onNodeWithText("Add by URL or channel").assertDoesNotExist()
        description("Remove Never-live episode").assertExists()
        compose.runOnIdle { assertEquals(fixture.all, fixture.state.entries); assertNull(fixture.state.link) }
        text("Add link").performClick()
        text("Provider URL or channel").performTextInput("https://provider.example.test/video/1")
        text("Add").performClick()
        text("This stream is already configured.").assertExists()
        text("Add").assertIsNotEnabled()
        text("Edit link").performClick()
        text("Provider URL or channel").assertIsEnabled()
        text("Add").assertIsEnabled()
        text("Cancel").performClick()
        text("Add link").performClick()
        text("Add").assertIsNotEnabled()
    }

    @Test fun linkFailureRemainsInDialogAndCancelLeavesTheBrowseContextAlone() {
        val fixture = Fixture(PrototypeService.ABEMA)
        compose.setContent { fixture.Render() }
        text("Add link").performClick()
        text("Provider URL or channel").performTextInput("invalid fixture")
        compose.runOnIdle { fixture.state = fixture.state.copy(link = StreamLinkState(
            failure = CatalogResult.Failure(CatalogFailure.INVALID_INPUT))) }
        text("The input or catalog selection is invalid.").assertExists()
        text("Add").assertIsEnabled()
        text("Cancel").performClick()
        compose.runOnIdle { assertEquals(fixture.all, fixture.state.entries); assertTrue(fixture.calls.isEmpty()) }
        text("Add link").performClick()
        text("Add").assertIsNotEnabled()
        compose.runOnIdle { fixture.state = fixture.state.copy(link = null, privacyRevision = 2) }
        compose.onNodeWithText("Add by URL or channel").assertDoesNotExist()
    }

    @Test fun discoveryGroupsStayOnOneHorizontalRowAtLargeTextAndExposeSelection() {
        val fixture = Fixture(PrototypeService.ABEMA)
        fixture.state = fixture.state.copy(capabilities = fixture.state.capabilities!!.copy(
            browseTitle = "Channels", collections = listOf(
                CatalogCollection("schedule", "Schedule", CatalogAccess.AVAILABLE), fixture.collection,
                CatalogCollection("history", "History", CatalogAccess.NOT_VERIFIED))))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp)) { fixture.Render() }
            }
        }
        val first = text("Channels").assertIsSelected().fetchSemanticsNode().boundsInRoot
        val last = text("History").assertIsNotSelected().fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, last.top, 1f)
        assertEquals(first.bottom, last.bottom, 1f)
        text("History").performClick()
        text("History").assertIsSelected()
        compose.runOnIdle { assertEquals("history", fixture.state.selectedTabId) }
        compose.onNodeWithContentDescription("Dismiss stream notice").performClick()
        text("Channels").performClick()
        text("Channels").assertIsSelected()
        text("Search streams").performTextInput("Offline")
        description("Search streams").performClick()
        text("Search results").assertExists()
        text("Channels").assertIsNotSelected()
    }

    @Test fun initialCatalogCapabilitiesCanAddAndSelectCollectionTabsWithoutCrashing() {
        val fixture = Fixture(PrototypeService.TWITCH)
        val ready = fixture.state.copy(query = CatalogQuery(collectionId = "following"),
            selectedTabId = "following", entries = emptyList(), nextCursor = null)
        fixture.state = ready.copy(capabilities = null, query = CatalogQuery(), selectedTabId = null, loading = true)
        compose.setContent { fixture.Render() }
        text("All").assertIsNotEnabled()
        repeat(3) {
            compose.runOnIdle { fixture.state = ready.copy(loading = false) }
            text("Following").assertIsSelected().assertIsEnabled()
            text("History").assertExists()
            compose.runOnIdle { fixture.state = ready.copy(capabilities = ready.capabilities!!.copy(collections = emptyList()),
                query = CatalogQuery(), selectedTabId = DISCOVERY_ALL_TAB) }
            text("All").assertIsSelected()
            compose.onNodeWithText("Following").assertDoesNotExist()
        }
        compose.runOnIdle { fixture.state = ready }
        text("Following").assertIsSelected()
    }

    @Test fun publicDiscoveryControlsRemainInPlaceWhileAccessIsRechecked() {
        val fixture = Fixture(PrototypeService.TWITCH)
        compose.setContent { fixture.Render() }
        val before = text("Search streams").fetchSemanticsNode().boundsInRoot
        val capabilities = fixture.state.capabilities
        compose.runOnIdle { fixture.state = fixture.state.copy(capabilities = null, loading = true,
            privacyRevision = fixture.state.privacyRevision + 1) }
        text("Following").assertIsNotEnabled()
        text("History").assertIsNotEnabled()
        text("Search streams").assertIsNotEnabled()
        assertEquals(before, text("Search streams").fetchSemanticsNode().boundsInRoot)
        text("Add link").assertIsNotEnabled()
        compose.runOnIdle { fixture.state = fixture.state.copy(capabilities = capabilities, loading = false) }
        text("Following").assertIsEnabled()
    }

    @Test fun linkRetryWaitsForProviderDeadlineWithoutClosingOrMovingThePage() {
        val fixture = Fixture(PrototypeService.ABEMA)
        compose.setContent { fixture.Render() }
        text("Add link").performClick()
        text("Provider URL or channel").performTextInput("retry fixture")
        val initial = fixture.state.entries
        compose.runOnIdle { fixture.state = fixture.state.copy(link = StreamLinkState(
            failure = CatalogResult.Failure(CatalogFailure.RATE_LIMITED, System.currentTimeMillis() + 60_000))) }
        text("Add").assertIsNotEnabled()
        text("Provider URL or channel").assertIsEnabled()
        compose.runOnIdle { fixture.state = fixture.state.copy(link = StreamLinkState(
            failure = CatalogResult.Failure(CatalogFailure.RATE_LIMITED, System.currentTimeMillis() - 1))) }
        text("Add").assertIsEnabled()
        text("Cancel").performClick()
        compose.runOnIdle { assertEquals(initial, fixture.state.entries); assertTrue(fixture.calls.isEmpty()) }
    }

    @Test fun restoredNavigationContextsRetainTheirOwnDiscoveryViewportOffsets() {
        val fixture = Fixture(PrototypeService.TWITCH)
        val channels = (0 until 30).map { fixture.offline.copy(resource = fixture.offline.resource.copy(identity = "channel$it"), title = "Channel $it") }
        fixture.state = fixture.state.copy(entries = channels)
        compose.setContent { fixture.Render() }
        val firstY = description("Browse Channel 20").fetchSemanticsNode().boundsInRoot.top
        val first = fixture.state
        compose.runOnIdle { fixture.state = first.copy(query = CatalogQuery(collectionId = "following"),
            selectedTabId = "following", discoveryContextId = 40, retainedDiscoveryContextIds = listOf(first.discoveryContextId)) }
        val secondY = description("Browse Channel 10").fetchSemanticsNode().boundsInRoot.top
        val second = fixture.state
        compose.runOnIdle { fixture.state = first.copy(retainedDiscoveryContextIds = listOf(second.discoveryContextId)) }
        assertEquals(firstY, compose.onNodeWithContentDescription("Browse Channel 20").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnIdle { fixture.state = second.copy(retainedDiscoveryContextIds = listOf(first.discoveryContextId)) }
        assertEquals(secondY, compose.onNodeWithContentDescription("Browse Channel 10").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    private fun longDiscoveryFixture(): Fixture {
        val fixture = Fixture(PrototypeService.TWITCH)
        fixture.state = fixture.state.copy(configured = List(6) { index ->
            ConfiguredSource(UUID.randomUUID().toString(), fixture.state.instance.id,
                fixture.offline.copy(resource = fixture.offline.resource.copy(identity = "saved$index"), title = "Saved $index"))
        }, nextCursor = null, entries = (0 until 30).map { fixture.offline.copy(
            resource = fixture.offline.resource.copy(identity = "channel$it"), title = "Channel $it") })
        return fixture
    }

    private fun showFindControls() {
        val list = compose.onNodeWithTag("Stream discovery list")
        list.performScrollToNode(hasTestTag("Find streams controls"))
        val controls = compose.onNodeWithTag("Find streams controls").performScrollTo()
        val offset = controls.fetchSemanticsNode().boundsInRoot.top - list.fetchSemanticsNode().boundsInRoot.top
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, offset) }
        compose.waitForIdle()
    }

    private fun discoveryControlY() = listOf("Following", "Search streams").map {
        compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
    }

    private fun assertDiscoveryControlY(expected: List<Float>) {
        expected.zip(discoveryControlY()).forEach { (before, after) -> assertEquals(before, after, 1f) }
    }

    private fun trailingGapPx() = compose.onAllNodesWithTag("Discovery trailing space")
        .fetchSemanticsNodes().singleOrNull()?.size?.height ?: 0

    @Test fun explicitTabsKeepControlsInPlaceForShortEmptyAndCachedLongResults() {
        val fixture = longDiscoveryFixture()
        compose.setContent { Box(Modifier.width(360.dp).height(500.dp)) { fixture.Render() } }
        showFindControls()
        val original = discoveryControlY()
        compose.onNodeWithText("Following").performClick()
        compose.waitForIdle()
        assertDiscoveryControlY(original)
        assertTrue("Short results preserve only the necessary trailing range", trailingGapPx() > 0)
        compose.onNodeWithText("All").performClick()
        compose.waitForIdle()
        assertDiscoveryControlY(original)
        compose.onNodeWithText("History").performClick()
        compose.waitForIdle()
        assertDiscoveryControlY(original)
        assertTrue("Empty results also preserve the controls", trailingGapPx() > 0)
        compose.onNodeWithText("All").performClick()
        compose.waitForIdle()
        assertDiscoveryControlY(original)
        compose.runOnIdle { assertEquals(30, fixture.state.entries.size) }
        val list = compose.onNodeWithTag("Stream discovery list")
        list.performScrollToNode(hasTestTag("Discovery row channel:channel29"))
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        assertEquals("A long destination must not retain an empty scrollable tail", 0, trailingGapPx())
        val last = compose.onNodeWithTag("Discovery row channel:channel29").fetchSemanticsNode().boundsInRoot
        val viewport = list.fetchSemanticsNode().boundsInRoot
        assertTrue(last.bottom >= viewport.bottom - 2f)
    }

    @Test fun loadingThenShortOrEmptyResultsDoNotMoveTheSelectedTabsOrSearch() {
        val fixture = longDiscoveryFixture()
        fixture.collectionState = { _, state -> state.copy(loading = true, entries = emptyList(), failure = null, nextCursor = null) }
        compose.setContent { Box(Modifier.width(360.dp).height(500.dp)) { fixture.Render() } }
        showFindControls()
        val original = discoveryControlY()
        compose.onNodeWithText("Following").performClick()
        compose.waitForIdle()
        assertDiscoveryControlY(original)
        compose.runOnIdle { fixture.state = fixture.state.copy(loading = false, entries = listOf(fixture.offline)) }
        compose.waitForIdle()
        assertDiscoveryControlY(original)
        compose.onNodeWithText("History").performClick()
        compose.waitForIdle()
        assertDiscoveryControlY(original)
        compose.runOnIdle { fixture.state = fixture.state.copy(loading = false) }
        compose.waitForIdle()
        assertDiscoveryControlY(original)
        // A cached long result wins immediately even when a previous tab was loading.
        compose.onNodeWithText("All").performClick()
        compose.waitForIdle()
        assertDiscoveryControlY(original)
        compose.runOnIdle { assertEquals(DISCOVERY_ALL_TAB, fixture.state.selectedTabId); assertEquals(30, fixture.state.entries.size) }
    }

    @Test fun upwardScrollingRetiresTrailingRangeAndReversingCannotRecreateIt() {
        val fixture = longDiscoveryFixture()
        compose.setContent { Box(Modifier.width(360.dp).height(500.dp)) { fixture.Render() } }
        showFindControls()
        compose.onNodeWithText("Following").performClick()
        compose.waitForIdle()
        val original = discoveryControlY()
        val gap = trailingGapPx()
        assertTrue(gap > 40)
        val list = compose.onNodeWithTag("Stream discovery list")
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -40f) }
        compose.waitForIdle()
        val reduced = trailingGapPx()
        assertEquals((gap - 40).toFloat(), reduced.toFloat(), 1f)
        val moved = discoveryControlY()
        original.zip(moved).forEach { (before, after) -> assertEquals(before + 40f, after, 1f) }
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 40f) }
        compose.waitForIdle()
        assertEquals(reduced, trailingGapPx())
        assertDiscoveryControlY(moved)
        // Returning to a cached root follows this new controls anchor, not its old one.
        compose.onNodeWithText("All").performClick()
        compose.waitForIdle()
        assertDiscoveryControlY(moved)
        compose.onNodeWithText("Following").performClick()
        compose.waitForIdle()
        assertDiscoveryControlY(moved)
        list.performScrollToIndex(0)
        compose.waitForIdle()
        assertEquals(0, trailingGapPx())
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        assertEquals("Once retired, ordinary scrolling does not rearm the gap", 0, trailingGapPx())
    }

    @Test fun viewportChangeAndPrivacyResetDiscardTemporaryTrailingRange() {
        val fixture = longDiscoveryFixture()
        var height by mutableStateOf(500.dp)
        compose.setContent { Box(Modifier.width(360.dp).height(height)) { fixture.Render() } }
        showFindControls()
        compose.onNodeWithText("Following").performClick()
        compose.waitForIdle()
        assertTrue(trailingGapPx() > 0)
        compose.runOnIdle { height = 400.dp }
        compose.waitForIdle()
        assertEquals(0, trailingGapPx())
        compose.runOnIdle { height = 500.dp }
        compose.waitForIdle()
        assertEquals(0, trailingGapPx())
        showFindControls()
        compose.onNodeWithText("History").performClick()
        compose.waitForIdle()
        assertTrue(trailingGapPx() > 0)
        compose.runOnIdle { fixture.state = fixture.state.copy(privacyRevision = fixture.state.privacyRevision + 1) }
        compose.waitForIdle()
        assertEquals(0, trailingGapPx())
        compose.onNodeWithText("${fixture.state.instance.name} · Manage streams").assertIsDisplayed()
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
        compose.runOnIdle { assertEquals(1, fixture.state.configured!!.size) }
        compose.onNodeWithContentDescription("Confirm remove Offline channel").performClick()
        compose.runOnIdle { assertTrue(fixture.state.configured!!.isEmpty()) }
        text("No configured streams. Add an item below to show it on your selection page.").assertExists()
        compose.runOnIdle { fixture.state = fixture.state.copy(failure = CatalogResult.Failure(CatalogFailure.TEMPORARY)) }
        text("Retry catalog").performClick()
        compose.runOnIdle { assertTrue(fixture.calls.contains("retry")) }
    }

    @Test fun ordinaryDiscoveryRowsAndMoreFooterHaveCompactGeometryAndFullTouchTargets() {
        val fixture = Fixture(PrototypeService.TWITCH)
        val second = fixture.offline.copy(resource = fixture.offline.resource.copy(identity = "second"), title = "Second channel")
        fixture.state = fixture.state.copy(entries = listOf(fixture.offline, second), capabilities = fixture.state.capabilities!!.copy(
            collections = emptyList(), search = CatalogAccess.UNSUPPORTED, lookup = CatalogAccess.UNSUPPORTED))
        var pixelsPerDp = 1f
        compose.setContent {
            pixelsPerDp = LocalDensity.current.density
            Box(Modifier.widthIn(max = 360.dp).fillMaxWidth().height(360.dp).testTag("Discovery viewport")) { fixture.Render() }
        }
        text("More items").assertIsDisplayed()
        val first = compose.onNodeWithTag("Discovery row channel:offline").fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithTag("Discovery row channel:second").fetchSemanticsNode().boundsInRoot
        listOf(first, next).forEach {
            assertTrue("Ordinary row should be at least 72 dp", it.height / pixelsPerDp >= 72f)
            assertTrue("Ordinary row should stay within 80 dp plus its divider", it.height / pixelsPerDp <= 81f)
        }
        assertTrue("No large gap between discovery rows", (next.top - first.bottom) / pixelsPerDp <= 1f)
        val footer = compose.onNodeWithText("More items").fetchSemanticsNode().boundsInRoot
        assertTrue("More footer follows the last row closely", (footer.top - next.bottom) / pixelsPerDp <= 20f)
        listOf("Add Offline channel", "Browse Offline channel").forEach {
            val bounds = compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height / pixelsPerDp >= 48f)
            assertTrue(bounds.width / pixelsPerDp >= 48f)
        }
    }

    private fun configuredFixture(count: Int): Fixture = Fixture(PrototypeService.TWITCH).also { fixture ->
        fixture.state = fixture.state.copy(configured = List(count) { index ->
            ConfiguredSource(UUID.randomUUID().toString(), fixture.state.instance.id,
                fixture.offline.copy(resource = fixture.offline.resource.copy(identity = "saved$index"), title = "Saved $index"))
        }, entries = emptyList(), nextCursor = null, capabilities = fixture.state.capabilities!!.copy(
            refresh = CatalogAccess.AVAILABLE, collections = emptyList(), search = CatalogAccess.UNSUPPORTED,
            lookup = CatalogAccess.UNSUPPORTED))
    }

    @Test fun transientStreamFeedbackNeverMovesConfiguredRowsAtNormalOrLargeText() {
        val fixture = configuredFixture(10)
        val ready = fixture.state
        var fontScale by mutableFloatStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.width(320.dp).height(600.dp)) { fixture.Render() }
            }
        }
        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { fontScale = scale; fixture.state = ready }
            for (index in listOf(0, 7)) {
            val handle = description("Reorder Saved $index")
            val baseline = handle.fetchSemanticsNode().boundsInRoot
            val updates = listOf(ready.copy(saving = true), ready.copy(loading = true),
                ready.copy(capabilities = ready.capabilities!!.copy(refresh = CatalogAccess.RECONNECT_REQUIRED)),
                ready.copy(storageFailed = true, message = "Configured streams could not be read. Retry to continue."),
                ready.copy(failure = CatalogResult.Failure(CatalogFailure.TEMPORARY)), ready)
            updates.forEach { state ->
                compose.runOnIdle { fixture.state = state }
                assertEquals(baseline, handle.fetchSemanticsNode().boundsInRoot)
            }
            compose.onNodeWithText("Saving configured streams…").assertDoesNotExist()
            compose.onNodeWithText("Reading streams…").assertDoesNotExist()
            compose.onNodeWithText("Configured streams saved on this device.").assertDoesNotExist()
            }
        }
    }

    @Test fun overlayFailureKeepsRetryGuardsAndCanBeDismissedWithoutChangingStreams() {
        val fixture = configuredFixture(3)
        val ready = fixture.state
        compose.setContent { fixture.Render() }
        val handle = description("Reorder Saved 0")
        val baseline = handle.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { fixture.state = ready.copy(failure = CatalogResult.Failure(CatalogFailure.RATE_LIMITED,
            System.currentTimeMillis() + 150_000)) }
        text("Retry catalog").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertTrue(fixture.calls.isEmpty()) }
        compose.onNodeWithContentDescription("Dismiss stream notice").performClick()
        compose.onNodeWithText("Retry catalog").assertDoesNotExist()
        val dismissed = fixture.state
        compose.runOnIdle { fixture.state = dismissed.copy(saving = true) }
        compose.onNodeWithText("Retry catalog").assertDoesNotExist()
        compose.runOnIdle { fixture.state = dismissed }
        compose.onNodeWithText("Retry catalog").assertDoesNotExist()
        assertEquals(baseline, handle.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { fixture.state = ready.copy(storageFailed = true, message = "Configured streams could not be read.") }
        compose.onNodeWithContentDescription("Dismiss stream notice").assertDoesNotExist()
        text("Retry").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("retry"), fixture.calls); assertEquals(ready.configured, fixture.state.configured) }
        assertEquals(baseline, handle.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { fixture.state = ready.copy(message = "Configured streams could not be saved. Retry; the list was not replaced.",
            failure = CatalogResult.Failure(CatalogFailure.TEMPORARY)) }
        text("Retry catalog").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("retry", "retry"), fixture.calls); assertEquals(ready.configured, fixture.state.configured) }
    }

    @Test fun handleDragMovesAcrossSeveralRowsInOneSaveAndCanMoveBack() {
        val fixture = configuredFixture(3)
        val original = fixture.state.configured!!.map { it.id }
        compose.setContent { fixture.Render() }
        val first = description("Reorder Saved 0")
        val start = first.fetchSemanticsNode().boundsInRoot
        val end = compose.onNodeWithContentDescription("Reorder Saved 2").fetchSemanticsNode().boundsInRoot
        first.performTouchInput { swipe(center, Offset(center.x, center.y + end.bottom - start.center.y + 16f), 600) }
        compose.runOnIdle {
            assertEquals(listOf(original[1], original[2], original[0]), fixture.state.configured!!.map { it.id })
            assertEquals(listOf("reorder"), fixture.calls)
        }
        val last = description("Reorder Saved 0")
        val lastBounds = last.fetchSemanticsNode().boundsInRoot
        val top = compose.onNodeWithContentDescription("Reorder Saved 1").fetchSemanticsNode().boundsInRoot
        last.performTouchInput { swipe(center, Offset(center.x, center.y + top.top - lastBounds.center.y - 16f), 600) }
        compose.runOnIdle {
            assertEquals(original, fixture.state.configured!!.map { it.id })
            assertEquals(listOf("reorder", "reorder"), fixture.calls)
        }
        compose.onNodeWithText("Up").assertDoesNotExist()
        compose.onNodeWithText("Down").assertDoesNotExist()
    }

    @Test fun cancellingHandleDragDoesNotWriteOrReplaceTheDurableOrder() {
        val fixture = configuredFixture(3)
        val original = fixture.state.configured
        compose.setContent { fixture.Render() }
        val first = description("Reorder Saved 0")
        val start = first.fetchSemanticsNode().boundsInRoot
        val end = compose.onNodeWithContentDescription("Reorder Saved 2").fetchSemanticsNode().boundsInRoot
        first.performTouchInput {
            down(center); moveTo(Offset(center.x, center.y + end.bottom - start.center.y), 600); cancel()
        }
        compose.runOnIdle { assertEquals(original, fixture.state.configured); assertTrue(fixture.calls.isEmpty()) }
    }

    @Test fun stationaryEdgeDragScrollsToTheLastConfiguredRowWithoutMovingDiscovery() {
        val fixture = configuredFixture(14)
        val original = fixture.state.configured!!.map { it.id }
        compose.setContent { Box(Modifier.width(360.dp).height(400.dp)) { fixture.Render() } }
        val handle = description("Reorder Saved 0")
        val start = handle.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("Stream discovery list").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            handle.performTouchInput {
                down(center); moveTo(Offset(center.x, center.y + viewport.bottom - start.center.y - 8f), 200)
            }
            repeat(40) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
            // Keep the same pointer stream after the keyed row moves/scrolls.
            handle.performTouchInput { up() }
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(original.drop(1) + original.first(), fixture.state.configured!!.map { it.id })
            assertEquals(listOf("reorder"), fixture.calls)
            assertTrue(fixture.state.entries.isEmpty())
        }
    }

    @Test fun edgeDragCanReversePastATallRowAndCancelWithoutSaving() {
        val fixture = configuredFixture(10)
        fixture.state = fixture.state.copy(configured = fixture.state.configured!!.mapIndexed { index, source ->
            if (index == 4) source.copy(entry = source.entry.copy(title = "A taller wrapped configured channel name ".repeat(3).trim(),
                scheduledStartEpochMs = 1_000)) else source
        })
        val original = fixture.state.configured
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(320.dp).height(400.dp)) { fixture.Render() }
            }
        }
        val handle = description("Reorder Saved 0")
        val start = handle.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("Stream discovery list").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            handle.performTouchInput { down(center); moveTo(Offset(center.x, center.y + viewport.bottom - start.center.y - 8f), 200) }
            repeat(60) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle(); handle.assertIsDisplayed() }
            assertEquals("10 of 10", handle.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
            val current = handle.fetchSemanticsNode().boundsInRoot
            handle.performTouchInput { moveTo(Offset(center.x, center.y + viewport.top - current.center.y + 8f), 200) }
            repeat(60) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle(); handle.assertIsDisplayed() }
            assertEquals("1 of 10", handle.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
            handle.performTouchInput { cancel() }
        } finally { compose.mainClock.autoAdvance = true }
        compose.runOnIdle { assertEquals(original, fixture.state.configured); assertTrue(fixture.calls.isEmpty()) }
    }

    @Test fun losingMutationAvailabilityCancelsAnActiveDragWithoutSaving() {
        val fixture = configuredFixture(3)
        val original = fixture.state.configured
        compose.setContent { fixture.Render() }
        val handle = description("Reorder Saved 0")
        val start = handle.fetchSemanticsNode().boundsInRoot
        val end = compose.onNodeWithContentDescription("Reorder Saved 2").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            handle.performTouchInput { down(center); moveTo(Offset(center.x, center.y + end.bottom - start.center.y), 200) }
            compose.runOnIdle { fixture.state = fixture.state.copy(loading = true) }
            compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
            handle.performTouchInput { up() }
        } finally { compose.mainClock.autoAdvance = true }
        compose.runOnIdle { assertEquals(original, fixture.state.configured); assertTrue(fixture.calls.isEmpty()) }
    }

    @Test fun removeRequiresConfirmationAndRechecksMutationAvailability() {
        val fixture = configuredFixture(2)
        compose.setContent { fixture.Render() }
        description("Remove Saved 0").performClick()
        compose.onNodeWithText("Remove “Saved 0” from your configured streams?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(2, fixture.state.configured!!.size); assertTrue(fixture.calls.isEmpty()) }
        description("Remove Saved 0").performClick()
        compose.runOnIdle { fixture.state = fixture.state.copy(loading = true) }
        compose.onNodeWithContentDescription("Confirm remove Saved 0").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertTrue(fixture.calls.isEmpty()); fixture.state = fixture.state.copy(loading = false) }
        compose.onNodeWithContentDescription("Confirm remove Saved 0").performClick()
        compose.runOnIdle {
            assertEquals(listOf("Saved 1"), fixture.state.configured!!.map { it.entry.title })
            assertEquals(listOf("remove"), fixture.calls)
        }
    }

    @Test fun staleRemovalDialogCannotRemoveAReplacementItem() {
        val fixture = configuredFixture(2)
        compose.setContent { fixture.Render() }
        description("Remove Saved 0").performClick()
        compose.runOnIdle { fixture.state = fixture.state.copy(configured = fixture.state.configured!!.drop(1)) }
        compose.onNodeWithText("Remove stream?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, fixture.state.configured!!.size); assertTrue(fixture.calls.isEmpty()) }
    }

    @Test fun handleMenuAndAccessibilityMoveActionsDoNotNeedVisibleUpDownButtons() {
        val fixture = configuredFixture(3)
        lateinit var inputMode: InputModeManager
        compose.setContent { inputMode = LocalInputModeManager.current; fixture.Render() }
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        val handle = description("Reorder Saved 1")
        handle.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        handle.performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithContentDescription("Move Saved 1 earlier").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithContentDescription("Move Saved 1 earlier").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals("Saved 1", fixture.state.configured!!.first().entry.title) }
        val row = compose.onNodeWithTag("Configured row ${fixture.state.configured!!.first().id}")
        val actions = row.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single { it.label == "Move later" }.action()) }
        compose.runOnIdle { assertEquals("Saved 1", fixture.state.configured!![1].entry.title) }
    }

    @Test fun compactConfiguredRowsKeepBrowseRefreshAndRemoveSeparateAtLargeFonts() {
        val fixture = configuredFixture(1)
        val source = fixture.state.configured!!.single()
        fixture.state = fixture.state.copy(configured = listOf(source.copy(entry = source.entry.copy(
            title = "A very long configured channel name ".repeat(4).trim()))))
        val title = fixture.state.configured!!.single().entry.title
        var pixelsPerDp = 1f
        compose.setContent {
            val density = LocalDensity.current; pixelsPerDp = density.density
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(280.dp).height(480.dp).testTag("Discovery viewport")) { fixture.Render() }
            }
        }
        for (label in listOf("Reorder $title", "Refresh metadata for $title", "Remove $title")) {
            val node = description(label)
            fitsDiscoveryViewport(node)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width / pixelsPerDp >= 48f); assertTrue(bounds.height / pixelsPerDp >= 48f)
        }
        description("Refresh metadata for $title").performClick()
        compose.runOnIdle { assertEquals(listOf("refresh:${source.id}"), fixture.calls) }
        description("Browse videos for $title").assertTextContains("Browse videos › · Offline").performClick()
        compose.onAllNodesWithText("›").assertCountEquals(0)
        compose.runOnIdle { assertEquals(listOf("refresh:${source.id}", "children:saved0"), fixture.calls) }
    }

    @Test fun addAndAddedChannelBrowsingRemainSeparateKeyboardTargets() {
        val fixture = Fixture(PrototypeService.TWITCH)
        lateinit var inputMode: InputModeManager
        compose.setContent { inputMode = LocalInputModeManager.current; fixture.Render() }
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        val add = description("Add Offline channel")
        add.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        add.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(listOf("add:offline"), fixture.calls) }
        description("Add Offline channel").assertIsNotEnabled()
        val browse = description("Browse Offline channel")
        browse.assertIsEnabled().assertTextContains("Browse videos ›")
        browse.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        browse.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        text("Videos from Offline channel").assertExists()
        compose.runOnIdle {
            assertEquals(listOf("add:offline", "children:offline"), fixture.calls)
            assertEquals(fixture.offline.resource, fixture.state.configured!!.single().entry.resource)
        }
    }

    @Test fun configuredChildActionsNameExactSupportAndNeverAdvertiseVideosUnknownKindsOrDeniedAccess() {
        val fixture = Fixture(PrototypeService.ABEMA)
        val provider = fixture.offline.resource.providerId
        val rules = listOf(
            CatalogChildrenCapability("channel", CatalogIntent.CHANNEL, CatalogAccess.AVAILABLE, "Browse programs", "Programs from"),
            CatalogChildrenCapability("series", CatalogIntent.COLLECTION, CatalogAccess.AVAILABLE, "Browse episodes", "Episodes from"),
            CatalogChildrenCapability("season", CatalogIntent.COLLECTION, CatalogAccess.AVAILABLE, "Browse episodes", "Episodes from"),
            CatalogChildrenCapability("broadcaster", CatalogIntent.CHANNEL, CatalogAccess.AVAILABLE, "Browse videos", "Videos from"))
        val entries = listOf("channel" to CatalogIntent.CHANNEL, "series" to CatalogIntent.COLLECTION,
            "season" to CatalogIntent.COLLECTION, "broadcaster" to CatalogIntent.CHANNEL,
            "video" to CatalogIntent.VIDEO, "slot" to CatalogIntent.BROADCAST,
            "unknown" to CatalogIntent.COLLECTION, "channel" to CatalogIntent.VIDEO).mapIndexed { index, (kind, intent) ->
            CatalogEntry(CatalogResource(provider, kind, "item$index", intent), "Saved item $index")
        }
        fixture.state = fixture.state.copy(configured = entries.map { ConfiguredSource(UUID.randomUUID().toString(), fixture.state.instance.id, it) },
            entries = emptyList(), nextCursor = null, capabilities = fixture.state.capabilities!!.copy(childCapabilities = rules))
        lateinit var inputMode: InputModeManager
        compose.setContent { inputMode = LocalInputModeManager.current; fixture.Render() }
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        for ((index, action) in listOf("Browse programs", "Browse episodes", "Browse episodes", "Browse videos").withIndex()) {
            val target = description("$action for Saved item $index")
            target.assertTextContains("$action › · Availability unknown")
            target.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            target.performKeyInput { pressKey(Key.Enter) }
            compose.runOnIdle { assertEquals("children:item$index", fixture.calls.last()) }
        }
        compose.runOnIdle { fixture.state = fixture.state.copy(entries = emptyList(), childHeading = null) }
        for (index in 4..7) {
            text("Saved item $index").assertHasNoClickAction()
            compose.onNodeWithContentDescription("Browse configured Saved item $index").assertDoesNotExist()
        }
        compose.onAllNodesWithText("›").assertCountEquals(0)
        compose.runOnIdle { fixture.state = fixture.state.copy(capabilities = fixture.state.capabilities!!.copy(children = CatalogAccess.RECONNECT_REQUIRED)) }
        for ((index, action) in listOf("Browse programs", "Browse episodes", "Browse episodes", "Browse videos").withIndex())
            compose.onNodeWithContentDescription("$action for Saved item $index").assertDoesNotExist()
    }

    @Test fun providerWordingAndChildCapabilityNeverGiveVideosAMisleadingBrowseTarget() {
        val fixture = Fixture(PrototypeService.ABEMA)
        compose.setContent { fixture.Render() }
        description("Browse A show").assertTextContains("Browse episodes ›")
        compose.onAllNodesWithText("Browse videos ›").assertCountEquals(0)
        compose.runOnIdle { fixture.state = fixture.state.copy(entries = listOf(fixture.episode), nextCursor = null) }
        description("Add Never-live episode").assertIsEnabled()
        compose.onNodeWithContentDescription("Browse Never-live episode").assertDoesNotExist()
        compose.onAllNodesWithText("Browse episodes ›").assertCountEquals(0)
        compose.runOnIdle { fixture.state = fixture.state.copy(entries = fixture.all,
            capabilities = fixture.state.capabilities!!.copy(children = CatalogAccess.UNSUPPORTED)) }
        description("Add Offline channel").assertIsEnabled()
        compose.onNodeWithContentDescription("Browse Offline channel").assertDoesNotExist()
        compose.onNodeWithContentDescription("Browse A show").assertDoesNotExist()
    }

    @Test fun sourceLimitAndLoadingDisableMutationWhileChildReturnRemainsAvailable() {
        val fixture = Fixture(PrototypeService.TWITCH)
        fixture.state = fixture.state.copy(configured = List(MAX_CONFIGURED_SOURCES) { index ->
            ConfiguredSource(UUID.randomUUID().toString(), fixture.state.instance.id,
                fixture.episode.copy(resource = fixture.episode.resource.copy(identity = "saved$index"), title = "Saved $index"))
        }, query = CatalogQuery(parent = fixture.offline.resource), discoveryContextId = 1,
            childHeading = "Videos from Offline channel", navigationBackLabel = "Back to channel list")
        compose.setContent { fixture.Render() }
        description("Add Offline channel").assertIsNotEnabled()
        description("Browse Offline channel").assertIsEnabled()
        compose.runOnIdle { fixture.state = fixture.state.copy(loading = true, saving = true) }
        description("Browse Offline channel").assertIsNotEnabled()
        text("More items").assertIsNotEnabled()
        description("Back to channel list").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("discovery-back"), fixture.calls)
            assertTrue(fixture.state.saving)
        }
    }

    @Test fun longNamesAndLargeFontsGrowRowsWithoutClippingSeparateActions() {
        val fixture = Fixture(PrototypeService.TWITCH)
        val long = fixture.offline.copy(title = "A long channel name " + "x".repeat(120))
        fixture.state = fixture.state.copy(entries = listOf(long), nextCursor = null,
            capabilities = fixture.state.capabilities!!.copy(collections = emptyList(), search = CatalogAccess.UNSUPPORTED,
                lookup = CatalogAccess.UNSUPPORTED))
        var landscape by mutableStateOf(false)
        var pixelsPerDp = 1f
        compose.setContent {
            val density = LocalDensity.current
            pixelsPerDp = density.density
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (landscape) 1f else 2f)) {
                Box(Modifier.width(if (landscape) 540.dp else 280.dp).height(if (landscape) 220.dp else 480.dp)
                    .testTag("Discovery viewport")) { fixture.Render() }
            }
        }
        fitsDiscoveryViewport(description("Add ${long.title}"))
        fitsDiscoveryViewport(description("Browse ${long.title}"))
        assertTrue(compose.onNodeWithTag("Discovery row channel:offline").fetchSemanticsNode().size.height / pixelsPerDp > 80f)
        compose.runOnIdle { landscape = true }
        fitsDiscoveryViewport(description("Add ${long.title}"))
        fitsDiscoveryViewport(description("Browse ${long.title}"))
        compose.runOnIdle { assertTrue(fixture.calls.isEmpty()) }
    }

    @Test fun childReturnRestoresLoadedParentScrollAnchorAfterAddingAConfiguredVideo() {
        val fixture = Fixture(PrototypeService.TWITCH)
        val channels = (0 until 30).map { fixture.offline.copy(resource = fixture.offline.resource.copy(identity = "channel$it"), title = "Channel $it") }
        fixture.state = fixture.state.copy(entries = channels, query = CatalogQuery(collectionId = "following"), nextCursor = "parent-next")
        compose.setContent { fixture.Render() }
        val target = description("Browse Channel 20")
        val originalY = target.fetchSemanticsNode().boundsInRoot.top
        target.performClick()
        text("Videos from Channel 20").assertIsDisplayed()
        description("Add Never-live episode").performClick()
        description("Back to channel list").performClick()
        compose.waitForIdle()
        val restored = compose.onNodeWithContentDescription("Browse Channel 20").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertEquals("The same discovery row retains its viewport offset", originalY, restored, 1f)
        compose.runOnIdle {
            assertEquals(channels, fixture.state.entries)
            assertEquals(CatalogQuery(collectionId = "following"), fixture.state.query)
            assertEquals("parent-next", fixture.state.nextCursor)
            assertEquals(fixture.episode.resource, fixture.state.configured!!.single().entry.resource)
            assertEquals(listOf("children:channel20", "add:show_1", "discovery-back"), fixture.calls)
        }
    }

    @Test fun discoveryScrollAnchorsAreNotSerializedIntoSavedUiState() {
        val fixture = Fixture(PrototypeService.TWITCH)
        fixture.state = fixture.state.copy(entries = (0 until 30).map {
            fixture.offline.copy(resource = fixture.offline.resource.copy(identity = "channel$it"), title = "Channel $it")
        })
        val restoration = StateRestorationTester(compose)
        restoration.setContent { fixture.Render() }
        description("Browse Channel 20").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("${fixture.state.instance.name} · Manage streams").assertIsDisplayed()
        compose.onNodeWithContentDescription("Browse Channel 20").assertDoesNotExist()
        compose.runOnIdle { assertTrue(fixture.calls.isEmpty()) }
    }

    @Test fun observedPrivacyResetDiscardsDiscoveryScrollAnchors() {
        val fixture = Fixture(PrototypeService.TWITCH)
        fixture.state = fixture.state.copy(entries = (0 until 30).map {
            fixture.offline.copy(resource = fixture.offline.resource.copy(identity = "channel$it"), title = "Channel $it")
        })
        compose.setContent { fixture.Render() }
        description("Browse Channel 20").assertIsDisplayed()
        compose.runOnIdle { fixture.state = fixture.state.copy(privacyRevision = fixture.state.privacyRevision + 1) }
        compose.onNodeWithText("${fixture.state.instance.name} · Manage streams").assertIsDisplayed()
        compose.onNodeWithContentDescription("Browse Channel 20").assertDoesNotExist()
        compose.runOnIdle { assertTrue(fixture.calls.isEmpty()) }
    }

    private fun fitsDiscoveryViewport(node: SemanticsNodeInteraction) {
        node.performScrollTo().assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("Discovery viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width <= viewport.width); assertTrue(bounds.height <= viewport.height)
        assertTrue(bounds.left >= viewport.left); assertTrue(bounds.top >= viewport.top)
        assertTrue(bounds.right <= viewport.right + 1f); assertTrue(bounds.bottom <= viewport.bottom + 1f)
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
        all.performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Following").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertTrue(fixture.calls.contains("collection:following")) }
    }
}
