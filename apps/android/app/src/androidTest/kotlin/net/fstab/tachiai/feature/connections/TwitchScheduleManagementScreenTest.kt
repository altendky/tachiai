package net.fstab.tachiai.feature.connections

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.*
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// Real shared Compose/controller/store/codec with synthetic public metadata.
// There are no provider sessions, requests or device-private record accesses.
class TwitchScheduleManagementScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var focusManager: FocusManager
    private val scheduled = 4_102_444_800_000L
    private val title = "Channel schedule fixture"
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var writes = 0
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf(); writes++ }
    }
    private inner class Fixture(availability: CatalogAvailability, date: Long?) : AutoCloseable {
        val instance = ProviderInstance("12345678-1234-1234-1234-123456789122", PrototypeService.TWITCH, "Schedule fixture")
        val provider = ProviderId("twitch")
        val resource = CatalogResource(provider, "broadcaster", "123", CatalogIntent.CHANNEL)
        var entry = CatalogEntry(resource, title, availability, date)
        val memory = Memory()
        val store = ConfiguredSourceStore(memory, instance.id, provider)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val metadata = object : ProviderCatalog {
            override val providerId = provider
            override val instanceId = instance.id
            override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE, lookup = CatalogAccess.AVAILABLE,
                playback = CatalogAccess.NOT_VERIFIED)
            override fun browse(query: CatalogQuery) = CatalogResult.Value(CatalogPage(emptyList()))
            override fun lookup(input: String) = CatalogResult.Value(entry)
            override fun refresh(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
            override fun resolve(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        }
        val controller = StreamManagementController(instance, metadata, store, { emptyList() }, scope, Dispatchers.Unconfined)
        init { controller.load() }
        fun reopen() = ConfiguredSourceStore(memory, instance.id, provider).read { error("No legacy fallback") }
        override fun close() { controller.close(); scope.cancel() }
    }

    @Composable private fun Render(fixture: Fixture) {
        focusManager = LocalFocusManager.current
        val state by fixture.controller.state.collectAsState()
        TachiaiPrototypeTheme {
            StreamManagementScreen(state, fixture.controller::search, fixture.controller::all,
                fixture.controller::collection, fixture.controller::children, fixture.controller::more,
                fixture.controller::lookup, fixture.controller::add, fixture.controller::remove,
                fixture.controller::move, fixture.controller::retry, {})
        }
    }
    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }
    private fun tap(matcher: SemanticsMatcher) {
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        closeSoftKeyboard(); compose.waitForIdle()
        scrollTo(matcher).performClick(); compose.waitForIdle()
    }
    private fun lookup() {
        scrollTo(hasText("Provider URL or channel")).performTextReplacement("fixture-channel")
        tap(hasText("Look up"))
    }
    private fun details(availability: String, date: Long) =
        "Ongoing channel · $availability · Scheduled ${DateFormat.getDateTimeInstance().format(Date(date))}"

    @Test fun scheduledPreviewRequiresAddAndLaterDatePreviewCannotOverwriteTheSavedChannelSnapshot() {
        val fixture = Fixture(CatalogAvailability.OFFLINE, scheduled)
        try {
            compose.setContent { Render(fixture) }
            lookup()
            scrollTo(hasText(details("Offline", scheduled))).assertExists()
            compose.runOnIdle {
                assertTrue(fixture.controller.state.value.configured!!.isEmpty())
                assertNull(fixture.memory.bytes); assertEquals(0, fixture.memory.writes)
            }
            tap(hasContentDescription("Add $title"))
            scrollTo(hasContentDescription("Add $title")).assertIsNotEnabled()
            val saved = fixture.memory.bytes!!.copyOf()
            compose.runOnIdle {
                val configured = fixture.reopen().single()
                assertEquals(fixture.resource, configured.entry.resource)
                assertEquals(CatalogIntent.CHANNEL, configured.entry.resource.intent)
                assertEquals(CatalogAvailability.OFFLINE, configured.entry.availability)
                assertEquals(scheduled, configured.entry.scheduledStartEpochMs)
                assertEquals(1, fixture.memory.writes)
                fixture.entry = fixture.entry.copy(availability = CatalogAvailability.LIVE,
                    scheduledStartEpochMs = scheduled + 86_400_000L)
            }
            lookup()
            scrollTo(hasText(details("Live", scheduled + 86_400_000L))).assertExists()
            scrollTo(hasContentDescription("Add $title")).assertIsNotEnabled()
            compose.runOnIdle {
                assertArrayEquals(saved, fixture.memory.bytes)
                assertEquals(1, fixture.memory.writes)
                assertEquals(scheduled, fixture.reopen().single().entry.scheduledStartEpochMs)
                assertEquals(CatalogAvailability.OFFLINE, fixture.reopen().single().entry.availability)
            }
        } finally { fixture.close() }
    }

    @Test fun missingScheduleShowsLiveChannelStateWithoutAPlaceholderDateOrUpcomingClaim() {
        val fixture = Fixture(CatalogAvailability.LIVE, null)
        try {
            compose.setContent { Render(fixture) }
            lookup()
            scrollTo(hasText("Ongoing channel · Live")).assertExists()
            compose.onAllNodes(hasText("Scheduled ", substring = true)).assertCountEquals(0)
            compose.onAllNodes(hasText("Upcoming", substring = true)).assertCountEquals(0)
            compose.runOnIdle {
                assertNull(fixture.controller.state.value.entries.single().scheduledStartEpochMs)
                assertEquals(CatalogAvailability.LIVE, fixture.controller.state.value.entries.single().availability)
                assertNull(fixture.memory.bytes); assertEquals(0, fixture.memory.writes)
            }
        } finally { fixture.close() }
    }
}
