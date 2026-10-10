package net.fstab.tachiai.feature.connections

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.fstab.tachiai.feature.presentation.PrototypeSourcePicker
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.abema.AbemaLocalImportCatalog
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// Actual parser, local adapter, controller and configured-record codec. All
// stores/accounts are synthetic; no device-private data or provider is contacted.
class AbemaPublicImportScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var focusManager: FocusManager

    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var writes = 0
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf(); writes++ }
    }

    private inner class Harness : AutoCloseable {
        val instance = ProviderInstance("12345678-1234-1234-1234-123456789abc", PrototypeService.ABEMA, "Import fixture")
        val memory = Memory()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = ConfiguredSourceStore(memory, instance.id, ProviderId("abema"))
        val controller = StreamManagementController(instance, AbemaLocalImportCatalog(instance, defaultSourceSetups()),
            store, { emptyList() }, scope, Dispatchers.Unconfined)
        var picker by mutableStateOf(false)
        var pickerSources = emptyList<ConfiguredSource>()
        var assignments = ConfiguredFeedAssignments(null, null)

        init { controller.load() }

        fun reopen() = ConfiguredSourceStore(memory, instance.id, ProviderId("abema")).read {
            error("Committed configured list must not fall back to sample sources")
        }

        @Composable fun Render() {
            focusManager = LocalFocusManager.current
            val state by controller.state.collectAsState()
            TachiaiPrototypeTheme {
                if (picker) PrototypeSourcePicker(null, providerInstances = listOf(instance),
                    configuredSources = pickerSources, initialAssignments = assignments, onWatch = {})
                else StreamManagementScreen(state, controller::search, controller::all, controller::collection,
                    controller::children, controller::more, controller::lookup, controller::add, controller::remove,
                    controller::move, controller::retry, {})
            }
        }

        fun showPicker(selected: ConfiguredSource) {
            pickerSources = reopen()
            assignments = ConfiguredFeedAssignments(selected.choice, selected.choice)
            picker = true
        }

        override fun close() { controller.close(); scope.cancel() }
    }

    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }
    private fun text(value: String) = scrollTo(hasText(value))
    private fun description(value: String) = scrollTo(hasContentDescription(value))
    private fun lookup(url: String) {
        text("Provider URL or channel").performTextReplacement(url)
        text("Provider URL or channel").assertTextContains(url)
        // Settle focus and IME insets before scrolling to the real tap target.
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        closeSoftKeyboard()
        compose.waitForIdle()
        text("Look up").assertIsEnabled().assertIsDisplayed().performClick()
    }
    private fun ready(harness: Harness) {
        compose.waitUntil { !harness.controller.state.value.loading && !harness.controller.state.value.saving }
    }

    @Test fun publicChannelBroadcastAndEpisodePreviewRequireAddAndSurviveReopenInThePicker() {
        val harness = Harness()
        try {
            compose.setContent { harness.Render() }
            ready(harness)
            lookup("https://abema.tv/channels/sumo")
            ready(harness)
            text("Ongoing channel · Availability unknown").assertExists()
            compose.runOnIdle {
                assertNull(harness.memory.bytes)
                assertTrue(harness.controller.state.value.configured!!.isEmpty())
                assertEquals(CatalogResource(ProviderId("abema"), "channel", "sumo", CatalogIntent.CHANNEL),
                    harness.controller.state.value.entries.single().resource)
            }
            description("Add ABEMA channel · sumo").performClick()
            ready(harness)
            description("Add ABEMA channel · sumo").assertIsNotEnabled()
            lookup("HTTPS://ABEMA.TV:443/channels/sumo")
            ready(harness)
            description("Add ABEMA channel · sumo").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(1, harness.memory.writes); assertEquals(1, harness.reopen().size) }

            lookup("https://abema.tv/channels/sumo/slots/FutureSlot108")
            ready(harness)
            text("Specific broadcast · Availability unknown").assertExists()
            description("Add ABEMA broadcast · sumo/FutureSlot108").performClick()
            ready(harness)
            lookup("https://abema.tv/video/episode/NeverLive108")
            ready(harness)
            text("On-demand video · Availability unknown").assertExists()
            description("Add ABEMA episode · NeverLive108").performClick()
            ready(harness)
            compose.runOnIdle {
                val configured = harness.reopen()
                assertEquals(listOf(CatalogIntent.CHANNEL, CatalogIntent.BROADCAST, CatalogIntent.VIDEO),
                    configured.map { it.entry.resource.intent })
                assertEquals(listOf("sumo", "sumo/FutureSlot108", "NeverLive108"),
                    configured.map { it.entry.resource.identity })
                assertTrue(configured.all { it.entry.availability == CatalogAvailability.UNKNOWN && it.entry.scheduledStartEpochMs == null })
                harness.showPicker(configured.last())
            }
            compose.onNodeWithText("ABEMA channel · sumo").assertExists()
            compose.onNodeWithText("ABEMA broadcast · sumo/FutureSlot108").assertExists()
            compose.onNodeWithText("ABEMA episode · NeverLive108").assertExists()
            compose.onNodeWithText("Watch").assertIsNotEnabled()
            compose.onNodeWithText("Playback for a selected item is not supported yet. It stays configured.").assertExists()
        } finally { harness.close() }
    }

    @Test fun importedSeriesRemainsACollectionWithoutChoosingAnEpisodeOrEnablingPlayback() {
        val harness = Harness()
        try {
            compose.setContent { harness.Render() }
            ready(harness)
            lookup("https://abema.tv/video/title/394-72")
            ready(harness)
            text("Show / series · choose an item before playback · Availability unknown").assertExists()
            description("Add ABEMA series · 394-72").performClick()
            ready(harness)
            compose.onAllNodesWithText("Browse items").assertCountEquals(0)
            compose.runOnIdle {
                val configured = harness.reopen().single()
                assertEquals(CatalogResource(ProviderId("abema"), "series", "394-72", CatalogIntent.COLLECTION), configured.entry.resource)
                assertEquals(CatalogAccess.UNSUPPORTED, harness.controller.state.value.capabilities!!.children)
                assertEquals(1, harness.memory.writes)
                harness.showPicker(configured)
            }
            compose.onNodeWithText("ABEMA series · 394-72").assertExists()
            compose.onNodeWithText("Collection · choose a stream in Manage streams").assertExists()
            compose.onNodeWithText("Watch").assertIsNotEnabled()
            compose.onNodeWithText("A collection is selected. Choose a playable item in Manage streams.").assertExists()
        } finally { harness.close() }
    }

    @Test fun rejectedSensitiveUrlShowsOnlySafeErrorAndDraftIsNotRestoredOrWritten() {
        val harness = Harness()
        try {
            val restoration = StateRestorationTester(compose)
            restoration.setContent { harness.Render() }
            ready(harness)
            lookup("https://abema.tv/video/episode/NeverLive108")
            ready(harness)
            description("Add ABEMA episode · NeverLive108").performClick()
            ready(harness)
            val original = checkNotNull(harness.memory.bytes).copyOf()
            lookup("https://abema.tv/video/episode/Other108?token=unvalidated-fixture")
            ready(harness)
            text("The input or catalog selection is invalid.").assertExists()
            compose.runOnIdle {
                assertEquals(CatalogResult.Failure(CatalogFailure.INVALID_INPUT), harness.controller.state.value.failure)
                assertTrue(harness.controller.state.value.entries.isEmpty())
                assertEquals("NeverLive108", harness.reopen().single().entry.resource.identity)
                assertArrayEquals(original, harness.memory.bytes)
                assertEquals(1, harness.memory.writes)
            }
            restoration.emulateSavedInstanceStateRestore()
            text("Look up").assertIsNotEnabled()
            text("The input or catalog selection is invalid.").assertExists()
            compose.runOnIdle { assertArrayEquals(original, harness.memory.bytes) }
        } finally { harness.close() }
    }
}
