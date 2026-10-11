package net.fstab.tachiai.feature.connections

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
        private var previous: StreamManagementState? = null
        private var nextContext = 1L
        var state by mutableStateOf(StreamManagementState(instance, configured = emptyList(),
            capabilities = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, search = CatalogAccess.AVAILABLE,
                lookup = CatalogAccess.AVAILABLE, children = CatalogAccess.AVAILABLE,
                childrenTitle = if (service == PrototypeService.TWITCH) "Videos" else "Items",
                childrenHeadingPrefix = if (service == PrototypeService.TWITCH) "Videos from" else "Items under",
                collections = listOf(collection, CatalogCollection("history", "History", historyAccess))),
            entries = all, nextCursor = "opaque:+page2"))
        @Composable fun Render() {
            TachiaiPrototypeTheme {
                StreamManagementScreen(state,
                    onSearch = { search -> calls.add("search:$search"); state = state.copy(entries = all.filter { it.title.contains(search, true) }, nextCursor = null) },
                    onAll = { calls.add("all"); state = state.copy(entries = all, query = CatalogQuery(), failure = null, nextCursor = "opaque:+page2") },
                    onCollection = { id -> calls.add("collection:$id"); state = state.copy(query = CatalogQuery(collectionId = id),
                        entries = if (id == collection.id && collection.access == CatalogAccess.AVAILABLE) listOf(offline) else emptyList(),
                        nextCursor = null, failure = if (id == "history") CatalogResult.Failure(
                            if (historyAccess == CatalogAccess.NOT_VERIFIED) CatalogFailure.NOT_VERIFIED else CatalogFailure.UNSUPPORTED)
                            else if (collection.access == CatalogAccess.AUTHORIZATION_REQUIRED) CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) else null) },
                    onChildren = { resource ->
                        calls.add("children:${resource.identity}")
                        previous = state
                        val title = (state.entries + state.configured.orEmpty().map { it.entry })
                            .firstOrNull { it.resource == resource }?.title ?: resource.identity
                        state = state.copy(query = CatalogQuery(parent = resource), entries = listOf(episode), nextCursor = null,
                            failure = null, discoveryContextId = nextContext++, retainedDiscoveryContextIds = listOf(state.discoveryContextId),
                            childHeading = "${state.capabilities!!.childrenHeadingPrefix} $title", navigationBackLabel = "Back to channel list")
                    },
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
                    onBack = { calls.add("back") }, onDiscoveryBack = {
                        calls.add("discovery-back")
                        previous?.let { state = it.copy(configured = state.configured, saving = state.saving, loading = false) }
                        previous = null
                    })
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

    @Test fun connectedFollowingDoesNotImplyVerifiedHistoryOrLoseConfiguredItems() {
        val fixture = Fixture(PrototypeService.TWITCH, CatalogAccess.NOT_VERIFIED)
        val saved = ConfiguredSource(UUID.randomUUID().toString(), fixture.state.instance.id, fixture.offline)
        fixture.state = fixture.state.copy(configured = listOf(saved))
        compose.setContent { fixture.Render() }
        text("Following").performClick()
        text("History: Access to this list has not been verified.").assertExists()
        text("History").performClick()
        text("Provider catalog access has not been verified.").assertExists()
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
        browse.assertIsEnabled().assertTextContains("Videos ›")
        browse.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        browse.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        text("Videos from Offline channel").assertExists()
        compose.runOnIdle {
            assertEquals(listOf("add:offline", "children:offline"), fixture.calls)
            assertEquals(fixture.offline.resource, fixture.state.configured!!.single().entry.resource)
        }
    }

    @Test fun providerWordingAndChildCapabilityNeverGiveVideosAMisleadingBrowseTarget() {
        val fixture = Fixture(PrototypeService.ABEMA)
        compose.setContent { fixture.Render() }
        description("Browse A show").assertTextContains("Items ›")
        compose.onAllNodesWithText("Videos ›").assertCountEquals(0)
        compose.runOnIdle { fixture.state = fixture.state.copy(entries = listOf(fixture.episode), nextCursor = null) }
        description("Add Never-live episode").assertIsEnabled()
        compose.onNodeWithContentDescription("Browse Never-live episode").assertDoesNotExist()
        compose.onAllNodesWithText("Items ›").assertCountEquals(0)
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
        text("Back to channel list").assertIsEnabled().performClick()
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
        text("Back to channel list").performClick()
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
        all.performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Following").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertTrue(fixture.calls.contains("collection:following")) }
    }
}
