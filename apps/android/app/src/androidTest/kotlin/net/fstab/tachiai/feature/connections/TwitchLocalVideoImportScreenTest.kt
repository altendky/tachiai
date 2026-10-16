package net.fstab.tachiai.feature.connections

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import kotlinx.coroutines.*
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.twitch.catalog.TwitchLocalVideoImportCatalog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// Actual decorator/controller/UI/codec with synthetic metadata and in-memory
// private records. No provider session or device-private store is accessed.
class TwitchLocalVideoImportScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var focusManager: FocusManager
    private val videoUrl = "https://www.twitch.tv/videos/789"
    private val publicTitle = "Public Twitch video 789 (metadata not checked)"
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var writes = 0
        var failRead = false
        override fun read(): ByteArray? { check(!failRead); return bytes?.copyOf() }
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf(); writes++ }
    }
    private class Fixture(connected: Boolean = false) : AutoCloseable {
        val instance = ProviderInstance("12345678-1234-1234-1234-123456789120", PrototypeService.TWITCH, "Import fixture")
        val provider = ProviderId("twitch")
        val video = CatalogEntry(CatalogResource(provider, "video", "789", CatalogIntent.VIDEO),
            "Connected fixture video", CatalogAvailability.AVAILABLE)
        val channel = CatalogEntry(CatalogResource(provider, "broadcaster", "123", CatalogIntent.CHANNEL),
            "Private fixture Following channel", CatalogAvailability.OFFLINE)
        var access = if (connected) CatalogAccess.AVAILABLE else CatalogAccess.AUTHORIZATION_REQUIRED
        var browseFailure: CatalogResult.Failure? = null
        var lookupFailure: CatalogResult.Failure? = null
        var metadataLookups = 0
        var metadataPages = 0
        val memory = Memory()
        val store = ConfiguredSourceStore(memory, instance.id, provider)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val metadata = object : ProviderCatalog {
            override val providerId = provider
            override val instanceId = instance.id
            override fun capabilities() = CatalogCapabilities(browse = access, search = access, lookup = access,
                children = access, refresh = access, playback = CatalogAccess.NOT_VERIFIED,
                childCapabilities = listOf(CatalogChildrenCapability("broadcaster", CatalogIntent.CHANNEL, access, "Browse videos", "Videos from")),
                collections = listOf(CatalogCollection("following", "Following", access)),
                browseTitle = "Live channels", initialCollectionId = "following")
            override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
                metadataPages++
                return browseFailure ?: CatalogResult.Value(CatalogPage(listOf(channel, video), "fixture-next"))
            }
            override fun lookup(input: String): CatalogResult<CatalogEntry> {
                metadataLookups++
                return lookupFailure ?: CatalogResult.Value(video)
            }
            override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
            override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        }
        val controller = StreamManagementController(instance, TwitchLocalVideoImportCatalog(metadata), store,
            { emptyList() }, scope, Dispatchers.Unconfined)
        init { controller.load() }
        fun reopen() = ConfiguredSourceStore(memory, instance.id, provider).read { error("No sample fallback") }
        override fun close() { controller.close(); scope.cancel() }
    }

    @Composable private fun Render(fixture: Fixture) {
        focusManager = LocalFocusManager.current
        val state by fixture.controller.state.collectAsState()
        TachiaiPrototypeTheme {
            StreamManagementScreen(state, fixture.controller::search, fixture.controller::all,
                fixture.controller::collection, fixture.controller::children, fixture.controller::more,
                fixture.controller::add, fixture.controller::remove,
                fixture.controller::move, fixture.controller::retry, {}, onOpenLink = fixture.controller::openLink,
                onResolveLink = fixture.controller::resolveLink, onDismissLink = fixture.controller::dismissLink,
                onAddLink = fixture.controller::addLink, onEditLink = fixture.controller::editLink)
        }
    }
    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }
    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val overlay = matcher and !hasAnyAncestor(hasScrollToIndexAction())
        return if (compose.onAllNodes(overlay).fetchSemanticsNodes().isNotEmpty()) compose.onNode(overlay) else scrollTo(matcher)
    }
    private fun text(value: String) = node(hasText(value))
    private fun description(value: String) = scrollTo(hasContentDescription(value))
    private fun tap(matcher: SemanticsMatcher) {
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        closeSoftKeyboard(); compose.waitForIdle()
        node(matcher).performClick(); compose.waitForIdle()
    }
    private fun lookup(url: String) {
        if (compose.onAllNodesWithText("Add by URL or channel").fetchSemanticsNodes().isNotEmpty()) tap(hasText("Cancel"))
        tap(hasText("Add link"))
        text("Provider URL or channel").performTextReplacement(url)
        tap(hasText("Add"))
    }

    @Test fun disconnectedVideoPreviewUsesExplicitAddAndExactDeduplicationWithNoMetadataCalls() {
        val fixture = Fixture()
        try {
            compose.setContent { Render(fixture) }
            description("Info about Find streams").performClick()
            compose.onNodeWithText("Following: Connect a catalog account to access this list.", substring = true).assertExists()
            compose.onNodeWithText("Close").performClick()
            tap(hasText("Add link"))
            text("Add").assertIsNotEnabled()
            tap(hasText("Cancel"))
            lookup(videoUrl)
            text("On-demand video · Availability unknown").assertExists()
            compose.runOnIdle {
                assertNull(fixture.memory.bytes); assertEquals(0, fixture.memory.writes)
                assertEquals(0, fixture.metadataLookups); assertEquals(0, fixture.metadataPages)
            }
            tap(hasText("Add"))
            description("Remove $publicTitle").assertExists()
            lookup("https://twitch.tv:443/videos/789/")
            text("Add").assertIsNotEnabled()
            text("This stream is already configured.").assertExists()
            compose.runOnIdle {
                val saved = fixture.reopen().single()
                assertEquals(fixture.video.resource, saved.entry.resource)
                assertEquals(CatalogAvailability.UNKNOWN, saved.entry.availability)
                assertEquals(publicTitle, saved.entry.title)
                assertEquals(1, fixture.memory.writes)
                assertEquals(0, fixture.metadataLookups); assertEquals(0, fixture.metadataPages)
            }
        } finally { fixture.close() }
    }

    @Test fun invalidLocalInputsExposeOnlySafeErrorsAndDoNotRestoreOrPersistTheDraft() {
        val fixture = Fixture(); val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { Render(fixture) }
            lookup(videoUrl); tap(hasText("Add"))
            val saved = fixture.memory.bytes!!.copyOf()
            listOf("789", "https://twitch.tv/fixturechannel", "$videoUrl?token=unvalidated-fixture").forEach { input ->
                lookup(input)
                text("The input or catalog selection is invalid.").assertExists()
                compose.runOnIdle {
                    assertNull(fixture.controller.state.value.link!!.entry)
                    assertEquals(CatalogResult.Failure(CatalogFailure.INVALID_INPUT), fixture.controller.state.value.link!!.failure)
                }
            }
            compose.runOnIdle {
                assertArrayEquals(saved, fixture.memory.bytes); assertEquals(1, fixture.memory.writes)
                assertEquals(0, fixture.metadataLookups)
            }
            restoration.emulateSavedInstanceStateRestore()
            text("Add").assertIsNotEnabled()
            tap(hasText("Cancel"))
            description("Remove $publicTitle").assertExists()
        } finally { fixture.close() }
    }

    @Test fun accessLossClearsPrivatePagesAndDraftsBeforeExplicitReloadAndFreshLocalLookup() {
        val fixture = Fixture(connected = true)
        try {
            compose.setContent { Render(fixture) }
            tap(hasContentDescription("Add Connected fixture video"))
            val saved = fixture.memory.bytes!!.copyOf()
            text("Search streams").performTextInput("private fixture search")
            tap(hasText("Add link"))
            text("Provider URL or channel").performTextInput("https://fixture.invalid/?token=unvalidated")
            compose.runOnIdle {
                fixture.access = CatalogAccess.AUTHORIZATION_REQUIRED
                fixture.browseFailure = CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)
                fixture.controller.load()
            }
            text("Catalog account access is required. Your configured streams are retained.").assertExists()
            compose.onNodeWithContentDescription("Add Private fixture Following channel").assertDoesNotExist()
            compose.onNodeWithText("More items").assertDoesNotExist()
            compose.onNodeWithText("Add by URL or channel").assertDoesNotExist()
            description("Remove Connected fixture video").assertIsEnabled()
            tap(hasText("Retry catalog"))
            description("Search streams").assertIsNotEnabled()
            lookup(videoUrl)
            text("On-demand video · Availability unknown").assertExists()
            text("Add").assertIsNotEnabled()
            text("This stream is already configured.").assertExists()
            tap(hasText("Cancel"))
            description("Remove Connected fixture video").assertIsEnabled()
            compose.runOnIdle {
                assertArrayEquals(saved, fixture.memory.bytes)
                assertEquals(fixture.video, fixture.reopen().single().entry)
                // Reload observes denied capabilities before requesting another page.
                assertEquals(0, fixture.metadataLookups); assertEquals(1, fixture.metadataPages)
                assertEquals(CatalogAccess.AUTHORIZATION_REQUIRED, fixture.controller.state.value.capabilities!!.browse)
            }
        } finally { fixture.close() }
    }

    @Test fun connectedFailureHasNoLocalPreviewAndConfiguredReadFailureDisablesWrites() {
        val fixture = Fixture(connected = true)
        try {
            compose.setContent { Render(fixture) }
            tap(hasContentDescription("Add Connected fixture video"))
            val saved = fixture.memory.bytes!!.copyOf(); val writes = fixture.memory.writes
            compose.runOnIdle { fixture.lookupFailure = CatalogResult.Failure(CatalogFailure.TEMPORARY) }
            lookup(videoUrl)
            text("The catalog could not be loaded. Retry; your configured streams are retained.").assertExists()
            compose.runOnIdle {
                val state = fixture.controller.state.value
                val dialog = checkNotNull(state.link)
                assertEquals(CatalogResult.Failure(CatalogFailure.TEMPORARY), dialog.failure)
                assertNull(dialog.entry)
                assertNull(state.failure)
                assertEquals(listOf(fixture.channel, fixture.video), state.entries)
                assertEquals("following", state.selectedTabId)
            }
            tap(hasText("Cancel"))
            description("Remove Connected fixture video").assertIsEnabled()
            compose.runOnIdle { fixture.memory.failRead = true; fixture.controller.load() }
            text("Configured streams could not be read. Nothing was replaced. Retry to continue.").assertExists()
            description("Remove Connected fixture video").assertIsNotEnabled()
            text("Add link").assertIsNotEnabled()
            compose.runOnIdle {
                fixture.controller.add(fixture.video)
                assertArrayEquals(saved, fixture.memory.bytes); assertEquals(writes, fixture.memory.writes)
                assertEquals(1, fixture.metadataLookups)
            }
        } finally { fixture.close() }
    }
}
