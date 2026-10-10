package net.fstab.tachiai.feature.connections

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.*
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// Actual shared UI/controller/store/codec with synthetic metadata only.
// Memory records never touch device-private provider records or accounts.
class ConfiguredMetadataRefreshScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var focusManager: FocusManager
    private lateinit var inputMode: InputModeManager
    private val instance = ProviderInstance("12345678-1234-1234-1234-123456789124", PrototypeService.TWITCH, "Refresh fixture")
    private val provider = ProviderId("twitch")
    private val original = CatalogEntry(CatalogResource(provider, "broadcaster", "123", CatalogIntent.CHANNEL),
        "Original channel", CatalogAvailability.OFFLINE)
    private val other = CatalogEntry(CatalogResource(provider, "video", "456", CatalogIntent.VIDEO),
        "Other video", CatalogAvailability.UNKNOWN)
    private val scheduled = 4_102_444_800_000L

    private class Memory : PrivateSecretStore {
        private var bytes: ByteArray? = null
        var writes = 0
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf(); writes++ }
    }
    private inner class IntegrationFixture(initialEntry: CatalogEntry = original) : AutoCloseable {
        val memory = Memory()
        val store = ConfiguredSourceStore(memory, instance.id, provider)
        val requests = mutableListOf<CatalogResource>()
        val chosenIds = mutableListOf<String>()
        var refreshed = original.copy(title = "Updated channel", availability = CatalogAvailability.LIVE,
            scheduledStartEpochMs = scheduled)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val catalog = object : ProviderCatalog {
            override val providerId = provider
            override val instanceId = instance.id
            override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, refresh = CatalogAccess.AVAILABLE)
            override fun browse(query: CatalogQuery) = CatalogResult.Value(CatalogPage(emptyList()))
            override fun lookup(input: String) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
            override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> {
                requests.add(resource)
                return CatalogResult.Value(refreshed)
            }
            override fun resolve(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        }
        val before: List<ConfiguredSource>
        val initialWrites: Int
        val controller: StreamManagementController
        init {
            store.add(other) { emptyList() }
            val selected = store.add(initialEntry) { emptyList() }.last()
            store.saveQuality(selected.id, NativeQualityKind.VIDEO,
                NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC,
                    bitrateBps = 1_000_000, width = 1280, height = 720))) { emptyList() }
            before = reopen()
            initialWrites = memory.writes
            controller = StreamManagementController(instance, catalog, store, { emptyList() }, scope, Dispatchers.Unconfined)
            controller.load()
        }
        fun reopen() = ConfiguredSourceStore(memory, instance.id, provider).read { error("No legacy fallback") }
        @Composable fun Render() {
            val state by controller.state.collectAsState()
            RenderScreen(state) { id -> chosenIds.add(id); controller.refresh(id) }
        }
        override fun close() { controller.close(); scope.cancel() }
    }

    private inner class StateFixture {
        val selected = ConfiguredSource("12345678-1234-1234-1234-123456789125", instance.id, original)
        val another = ConfiguredSource("12345678-1234-1234-1234-123456789126", instance.id, other)
        val calls = mutableListOf<String>()
        var state by mutableStateOf(StreamManagementState(instance, configured = listOf(selected, another),
            capabilities = CatalogCapabilities(refresh = CatalogAccess.AVAILABLE)))
        @Composable fun Render() { RenderScreen(state) { calls.add(it) } }
    }

    @Composable private fun RenderScreen(state: StreamManagementState, refresh: (String) -> Unit) {
        focusManager = LocalFocusManager.current
        inputMode = LocalInputModeManager.current
        TachiaiPrototypeTheme {
            StreamManagementScreen(state, onSearch = {}, onAll = {}, onCollection = {}, onChildren = {},
                onMore = {}, onLookup = {}, onAdd = {}, onRemove = {}, onMove = { _, _ -> },
                onRetry = {}, onBack = {}, onRefresh = refresh)
        }
    }
    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }
    private fun refresh(title: String) = node(hasContentDescription("Refresh metadata for $title"))
    private fun tapRefresh(title: String) {
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        closeSoftKeyboard(); compose.waitForIdle()
        refresh(title).assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    @Test fun explicitRefreshUpdatesSelectedUuidMetadataThroughRealControllerAndCodec() {
        val fixture = IntegrationFixture()
        try {
            compose.setContent { fixture.Render() }
            compose.runOnIdle { assertTrue(fixture.requests.isEmpty()); assertEquals(fixture.initialWrites, fixture.memory.writes) }
            tapRefresh(original.title)
            compose.waitUntil { fixture.requests.size == 1 && !fixture.controller.state.value.loading && !fixture.controller.state.value.saving }
            node(hasText("Updated channel")).assertExists()
            node(hasText("Ongoing channel · Live · Scheduled ${DateFormat.getDateTimeInstance().format(Date(scheduled))}")).assertExists()
            compose.runOnIdle {
                val selected = fixture.before.last()
                val saved = fixture.reopen()
                assertEquals(listOf(selected.id), fixture.chosenIds)
                assertEquals(listOf(selected.entry.resource), fixture.requests)
                assertEquals(fixture.before.map { it.id }, saved.map { it.id })
                assertEquals(fixture.before.first(), saved.first())
                assertEquals(selected.entry.resource, saved.last().entry.resource)
                assertEquals(selected.quality, saved.last().quality)
                assertEquals(selected.choice, saved.last().choice)
                assertEquals(fixture.refreshed, saved.last().entry)
                assertEquals(fixture.initialWrites + 1, fixture.memory.writes)
            }
        } finally { fixture.close() }
    }

    @Test fun providerUnavailableMetadataRetainsConfiguredItemAndRemovesOldScheduleContext() {
        val fixture = IntegrationFixture(original.copy(scheduledStartEpochMs = scheduled))
        try {
            fixture.refreshed = original.copy(title = "Unavailable channel", availability = CatalogAvailability.UNAVAILABLE)
            compose.setContent { fixture.Render() }
            node(hasText("Ongoing channel · Offline · Scheduled ${DateFormat.getDateTimeInstance().format(Date(scheduled))}")).assertExists()
            tapRefresh(original.title)
            node(hasText("Unavailable channel")).assertExists()
            node(hasText("Ongoing channel · Unavailable")).assertExists()
            compose.runOnIdle {
                val saved = fixture.reopen()
                assertEquals(2, saved.size)
                assertEquals(fixture.before.last().id, saved.last().id)
                assertEquals(original.resource, saved.last().entry.resource)
                assertNull(saved.last().entry.scheduledStartEpochMs)
                assertEquals(listOf(original.resource), fixture.requests)
            }
        } finally { fixture.close() }
    }

    @Test fun unavailableCapabilitiesExplainOnceAndCannotDispatchForEitherConfiguredRow() {
        val fixture = StateFixture()
        compose.setContent { fixture.Render() }
        val explanations = listOf(
            CatalogAccess.AUTHORIZATION_REQUIRED to "Refresh metadata: Connect a catalog account.",
            CatalogAccess.RECONNECT_REQUIRED to "Refresh metadata: Reconnect the catalog account.",
            CatalogAccess.SCOPE_REQUIRED to "Refresh metadata: Additional catalog permission is required.",
            CatalogAccess.UNSUPPORTED to "Refresh metadata is not supported by this provider.",
            CatalogAccess.NOT_VERIFIED to "Refresh metadata access is not verified.",
            null to "Refresh metadata is unavailable until provider access is checked.",
        )
        for ((access, explanation) in explanations) {
            compose.runOnIdle { fixture.state = fixture.state.copy(capabilities = access?.let { CatalogCapabilities(refresh = it) }) }
            node(hasText(explanation)).assertExists()
            compose.onAllNodesWithText(explanation).assertCountEquals(1)
            refresh(original.title).assertIsNotEnabled().performClick()
            refresh(other.title).assertIsNotEnabled().performClick()
            compose.runOnIdle { assertTrue(fixture.calls.isEmpty()); assertEquals(2, fixture.state.configured!!.size) }
        }
    }

    @Test fun loadingSavingStorageFailureAndFutureRateDeadlineBlockManualRefresh() {
        val fixture = StateFixture()
        compose.setContent { fixture.Render() }
        val ready = fixture.state
        val blocked = listOf(ready.copy(loading = true), ready.copy(saving = true), ready.copy(storageFailed = true),
            ready.copy(failure = CatalogResult.Failure(CatalogFailure.RATE_LIMITED, System.currentTimeMillis() + 150_000)))
        for (state in blocked) {
            compose.runOnIdle { fixture.state = state }
            refresh(original.title).assertIsNotEnabled().performClick()
            compose.runOnIdle { assertTrue(fixture.calls.isEmpty()) }
        }
        compose.runOnIdle { fixture.state = ready.copy(failure = CatalogResult.Failure(CatalogFailure.RATE_LIMITED,
            System.currentTimeMillis() - 1)) }
        refresh(original.title).assertIsEnabled()
        tapRefresh(original.title)
        compose.runOnIdle { assertEquals(listOf(fixture.selected.id), fixture.calls) }
        compose.runOnIdle { fixture.state = ready.copy(configured = null) }
        compose.onNodeWithContentDescription("Refresh metadata for ${original.title}").assertDoesNotExist()
    }

    @Test fun configuredRefreshSupportsExplicitKeyboardActivationForSecondUuid() {
        val fixture = StateFixture()
        compose.setContent { fixture.Render() }
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        val selected = refresh(other.title)
        selected.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        selected.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(listOf(fixture.another.id), fixture.calls) }
    }
}
