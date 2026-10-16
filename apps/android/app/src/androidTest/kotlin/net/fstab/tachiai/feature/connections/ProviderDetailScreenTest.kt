package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.platform.network.ConnectionKind
import net.fstab.tachiai.platform.network.ConnectionSummary
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// Synthetic UI only: no provider requests, disk writes or account controllers.
class ProviderDetailScreenTest {
    @get:Rule val compose = createComposeRule()
    private val instance = defaultProviderInstances().first { it.service == PrototypeService.ABEMA }
        .copy(customName = "Sumo account")
    private val profile = ConnectionSummary("12345678-1234-1234-1234-123456789abc", "Japan route",
        ConnectionKind.WIREGUARD, "fixture.example.test:51820")
    private val savedRoute get() = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, profile.id, profile.name)

    @Test fun headlineShowsNameAndProviderAndEditingSavesImmediately() {
        var name by mutableStateOf(instance.customName.orEmpty())
        var owner by mutableStateOf(instance)
        var edits = 0; var backs = 0
        compose.setContent { TachiaiPrototypeTheme {
            ProviderDetailHeading(owner, name, true, { name = it; owner = owner.copy(customName = it); edits++ }, { backs++ })
        } }
        compose.onNodeWithText("Sumo account").assertExists()
        compose.onNodeWithText("ABEMA").assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithContentDescription("Edit provider name").performClick()
        compose.onNodeWithText("Name").performTextReplacement("Evening sumo")
        compose.runOnIdle { assertEquals("Evening sumo", name); assertEquals(1, edits) }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Evening sumo").assertExists()
        compose.onNodeWithContentDescription("Back to providers").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun invalidDraftAndSaveFailureRemainVisibleInNameEditor() {
        compose.setContent { TachiaiPrototypeTheme {
            ProviderDetailHeading(instance, "Duplicate", false, {}, {})
        } }
        compose.onNodeWithContentDescription("Edit provider name").performClick()
        compose.onNodeWithText("Use a unique name, up to 64 printable characters.").assertExists()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Edit name to fix an invalid name.").assertExists()
        compose.onNodeWithText("Sumo account").assertExists()
    }

    @Test fun nameDialogShowsSaveFailureInsteadOfClaimingSuccess() {
        compose.setContent { TachiaiPrototypeTheme {
            ProviderDetailHeading(instance, "New name", true, {}, {}, error = "Changes could not be saved. Retry.")
        } }
        compose.onNodeWithContentDescription("Edit provider name").performClick()
        compose.onNodeWithText("Changes could not be saved. Retry. Close to retry.").assertExists()
    }

    @Test fun routeMenuSelectsExplicitlyAndImportDoesNotChangeRoute() {
        var route by mutableStateOf<SourceRouteChoice?>(SourceRouteChoice.system)
        var saves = 0; var imports = 0
        compose.setContent { TachiaiPrototypeTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProviderConnectionSettings(instance, listOf(profile), route, { route = it; saves++ }, { imports++ })
            }
        } }
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithContentDescription("Choose ABEMA route").performClick()
        compose.onNodeWithContentDescription("ABEMA route: System network").assertIsSelected()
        compose.onNodeWithContentDescription("ABEMA route: Japan route").performClick()
        compose.runOnIdle { assertEquals(savedRoute, route); assertEquals(1, saves) }
        compose.onNodeWithContentDescription("Choose ABEMA route").performClick()
        compose.onNodeWithContentDescription("ABEMA route: Japan route").assertIsSelected()
        compose.onNodeWithText("Add route · setup / import").performClick()
        compose.runOnIdle { assertEquals(1, imports); assertEquals(savedRoute, route); assertEquals(1, saves) }
    }

    @Test fun missingRouteKeepsReferenceAndNeverSelectsSystem() {
        var route by mutableStateOf<SourceRouteChoice?>(savedRoute)
        var profiles by mutableStateOf(listOf(profile.copy(name = "Renamed Japan route")))
        var saves = 0
        compose.setContent { TachiaiPrototypeTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProviderConnectionSettings(instance, profiles, route, { route = it; saves++ }, {})
            }
        } }
        compose.onNodeWithText("Renamed Japan route").assertExists()
        compose.runOnIdle { profiles = emptyList() }
        compose.onNodeWithText("Japan route").assertExists()
        compose.onNodeWithText("Selected saved route is unavailable. Choose a replacement; no system fallback occurs.").assertExists()
        compose.onNodeWithContentDescription("Choose ABEMA route").performClick()
        compose.onNodeWithContentDescription("ABEMA route: System network").assertIsNotSelected()
        compose.runOnIdle { assertEquals(savedRoute, route); assertEquals(0, saves) }
    }

    @Test fun unresolvedRouteAndDisabledControlsCannotWriteOrImport() {
        var enabled by mutableStateOf(false)
        var writes = 0
        compose.setContent { TachiaiPrototypeTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProviderDetailHeading(instance, "", true, { writes++ }, {}, enabled = enabled)
                ProviderConnectionSettings(instance, listOf(profile), null, { writes++ }, { writes++ }, enabled = enabled)
            }
        } }
        compose.onNodeWithText("Choose a route to resolve earlier settings.").assertExists()
        compose.onNodeWithContentDescription("Edit provider name").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Choose ABEMA route").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, writes); enabled = true }
        compose.onNodeWithContentDescription("Choose ABEMA route").performClick()
        compose.onNodeWithContentDescription("ABEMA route: System network").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(1, writes) }
    }

    @Test fun poolAddsExplicitChoicesExcludesDuplicatesAndImportsWithoutChangingSelection() {
        var routes by mutableStateOf(emptyList<SourceRouteChoice>())
        val writes = mutableListOf<List<SourceRouteChoice>>()
        var imports = 0
        compose.setContent { TachiaiPrototypeTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProviderRoutePoolSettings(instance, listOf(profile), routes,
                    { routes = it; writes += it }, { imports++ })
            }
        } }
        compose.onNodeWithText("Choose a route.").assertExists()
        compose.runOnIdle { assertTrue(writes.isEmpty()) }
        compose.onNodeWithContentDescription("Add ABEMA route").performClick()
        compose.onNodeWithContentDescription("Add route: Japan route").performClick()
        compose.onNodeWithText("1. Japan route").assertExists()
        compose.onNodeWithContentDescription("Add ABEMA route").performClick()
        compose.onNodeWithContentDescription("Add route: Japan route").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add route: System network").performClick()
        compose.runOnIdle { assertEquals(listOf(savedRoute, SourceRouteChoice.system), routes) }
        compose.onNodeWithContentDescription("Add ABEMA route").performClick()
        compose.onNodeWithText("Setup / import routes").performClick()
        compose.runOnIdle { assertEquals(1, imports); assertEquals(2, writes.size) }
    }

    @Test fun poolReordersFromHandleMenuAndRemovingPrimaryPromotesNextWithoutSystemFallback() {
        var routes by mutableStateOf(listOf(savedRoute, SourceRouteChoice.system))
        var writes = 0
        compose.setContent { TachiaiPrototypeTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProviderRoutePoolSettings(instance, listOf(profile), routes, { routes = it; writes++ }, {})
            }
        } }
        compose.onNodeWithContentDescription("Reorder route: System network").performClick()
        compose.onNodeWithText("Move earlier").performClick()
        compose.onNodeWithText("1. System network").assertExists()
        compose.runOnIdle { assertEquals(listOf(SourceRouteChoice.system, savedRoute), routes) }
        compose.onNodeWithContentDescription("Remove route: System network").performClick()
        compose.runOnIdle { assertEquals(listOf(savedRoute), routes) }
        compose.onNodeWithContentDescription("Remove route: Japan route").performClick()
        compose.onNodeWithText("Choose a route.").assertExists()
        compose.runOnIdle { assertTrue(routes.isEmpty()); assertEquals(3, writes) }
    }

    @Test fun missingPooledProfilesKeepTheirReferencesUntilAnExplicitRepair() {
        var routes by mutableStateOf(listOf(savedRoute))
        var profiles by mutableStateOf(emptyList<ConnectionSummary>())
        var writes = 0
        compose.setContent { TachiaiPrototypeTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProviderRoutePoolSettings(instance, profiles, routes, { routes = it; writes++ }, {})
            }
        } }
        compose.onNodeWithText("Profile unavailable · remove or restore it").assertExists()
        compose.onNodeWithText("1. Japan route").assertExists()
        compose.runOnIdle { assertEquals(0, writes); assertEquals(listOf(savedRoute), routes)
            profiles = listOf(profile.copy(name = "Restored exit")) }
        compose.onNodeWithText("Profile unavailable · remove or restore it").assertDoesNotExist()
        compose.onNodeWithText("1. Restored exit").assertExists()
        compose.runOnIdle { assertEquals(0, writes); assertEquals(listOf(savedRoute), routes) }
    }

    @Test fun pooledRoutesCommitOneWholeOrderOnDragAndCancelWithoutWriting() {
        val secondProfile = profile.copy(id = "12345678-1234-1234-1234-123456789abd", name = "Other route")
        val other = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, secondProfile.id, secondProfile.name)
        var routes by mutableStateOf(listOf(savedRoute, other, SourceRouteChoice.system))
        val writes = mutableListOf<List<SourceRouteChoice>>()
        compose.setContent { TachiaiPrototypeTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProviderRoutePoolSettings(instance, listOf(profile, secondProfile), routes,
                    { routes = it; writes += it }, {})
            }
        } }
        val first = compose.onNodeWithContentDescription("Reorder route: Japan route")
        val start = first.fetchSemanticsNode().boundsInRoot
        val end = compose.onNodeWithContentDescription("Reorder route: System network").fetchSemanticsNode().boundsInRoot
        first.performTouchInput { down(center); moveTo(Offset(center.x, center.y + end.bottom - start.center.y), 600); cancel() }
        compose.runOnIdle { assertTrue(writes.isEmpty()) }
        first.performTouchInput { swipe(center, Offset(center.x, center.y + end.bottom - start.center.y + 16f), 600) }
        compose.runOnIdle { assertEquals(listOf(other, SourceRouteChoice.system, savedRoute), routes); assertEquals(1, writes.size) }
    }

    @Test fun disabledAndFullPoolsCannotAddAnAlternative() {
        val profiles = (1..MAX_PROVIDER_ROUTES).map {
            profile.copy(id = "00000000-0000-0000-0000-%012d".format(it), name = "Exit $it")
        }
        val routes = profiles.map { SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, it.id, it.name) }
        var enabled by mutableStateOf(false)
        var writes = 0
        compose.setContent { TachiaiPrototypeTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProviderRoutePoolSettings(instance, profiles, routes, { writes++ }, {}, enabled)
            }
        } }
        compose.onNodeWithContentDescription("Add ABEMA route").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { enabled = true }
        compose.onNodeWithContentDescription("Add ABEMA route").performClick()
        compose.onNodeWithContentDescription("Add route: System network").assertIsNotEnabled()
        compose.onNodeWithText("Setup / import routes").performClick()
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun routeStartupFeedbackUsesTheHeadingWithoutMovingConfiguredStreams() {
        var status by mutableStateOf("Trying 1 of 2 · Japan route")
        compose.setContent { TachiaiPrototypeTheme {
            Column {
                ProviderRoutePoolSettings(instance, listOf(profile), listOf(savedRoute, SourceRouteChoice.system),
                    {}, {}, startupStatus = status)
                androidx.compose.material3.Text("Configured streams")
            }
        } }
        val original = compose.onNodeWithText("Configured streams").fetchSemanticsNode().boundsInRoot
        for (next in listOf("Using System network", "All configured routes failed", "Connection stopped")) {
            compose.runOnIdle { status = next }
            compose.onNodeWithText(next).assertExists()
            assertEquals(original, compose.onNodeWithText("Configured streams").fetchSemanticsNode().boundsInRoot)
        }
    }
}
