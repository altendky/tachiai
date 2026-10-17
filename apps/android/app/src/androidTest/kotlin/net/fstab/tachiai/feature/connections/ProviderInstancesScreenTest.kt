package net.fstab.tachiai.feature.connections

import androidx.compose.material3.Text
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.presentation.*
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProviderInstancesScreenTest {
    @get:Rule val compose = createComposeRule()
    private val extra = ProviderInstance("12345678-1234-1234-1234-123456789abc", PrototypeService.TWITCH, "Twitch 2")
    private class Fixture(count: Int = 3) {
        var entries by mutableStateOf((1..count).map {
            ProviderInstance("12345678-1234-1234-1234-%012d".format(it), PrototypeService.TWITCH, "Account $it")
        })
        var busy by mutableStateOf(false)
        var opened: String? = null
        val removed = mutableListOf<String>()
        val reordered = mutableListOf<List<String>>()
        @Composable fun Render() {
            ProviderInstancesScreen(entries, emptyList(), busy, null, {}, { _, _, _ -> }, {},
                onCreate = { entries = entries + ProviderInstance("12345678-1234-1234-1234-999999999999", it, it.title) },
                onOpenProvider = { opened = it }, onReorder = { ids ->
                    reordered.add(ids); entries = ids.map { id -> entries.single { it.id == id } }; null
                }, onRemove = { id -> removed.add(id); entries = entries.filterNot { it.id == id } })
        }
    }
    @Test fun configureOpensProviderDetailDirectly() {
        var opened: String? = null
        compose.setContent { TachiaiPrototypeTheme {
            ProviderInstancesScreen(defaultProviderInstances(), emptyList(), false, null, {}, { _, _, _ -> }, {},
                onOpenProvider = { opened = it })
        } }
        compose.onNodeWithTag("Provider row ${defaultProviderInstanceId(PrototypeService.ABEMA)}").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(defaultProviderInstanceId(PrototypeService.ABEMA), opened) }
        compose.onNodeWithText("Manage streams").assertDoesNotExist()
        compose.onNodeWithText("ABEMA account").assertDoesNotExist()
        compose.onNodeWithText("Configure ABEMA").assertDoesNotExist()
    }

    @Test fun trailingDeleteConfirmsExactInstanceAndRechecksBusyStateWithoutOpeningIt() {
        val fixture = Fixture()
        val original = fixture.entries
        compose.setContent { TachiaiPrototypeTheme { fixture.Render() } }
        fun requestDelete() {
            compose.onNodeWithContentDescription("Provider actions: Account 2").performClick()
            compose.onNodeWithText("Delete…").performClick()
            compose.onNodeWithText("Delete Account 2?").assertExists()
        }
        requestDelete()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(original, fixture.entries); assertNull(fixture.opened); assertTrue(fixture.removed.isEmpty()) }
        requestDelete()
        compose.runOnIdle { fixture.busy = true }
        compose.onNodeWithText("Delete", substring = false).assertIsNotEnabled()
        compose.runOnIdle { fixture.busy = false }
        compose.onNodeWithText("Delete", substring = false).performClick()
        compose.runOnIdle {
            assertEquals(listOf(original[1].id), fixture.removed)
            assertEquals(listOf(original[0], original[2]), fixture.entries)
            assertNull(fixture.opened)
        }
    }

    @Test fun lastProviderCanBeRemovedAndEmptyListStillAllowsCreation() {
        val fixture = Fixture(1)
        compose.setContent { TachiaiPrototypeTheme { fixture.Render() } }
        compose.onNodeWithContentDescription("Reorder provider: Account 1").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Provider actions: Account 1").performClick()
        compose.onNodeWithText("Delete…").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("No providers configured.").assertIsDisplayed()
        compose.onNodeWithText("Add ABEMA instance").performClick()
        compose.runOnIdle { assertEquals(PrototypeService.ABEMA, fixture.entries.single().service) }
    }

    @Test fun handleMenuMovesOnlyThatProviderAndNeverOpensConfiguration() {
        val fixture = Fixture()
        val original = fixture.entries
        compose.setContent { TachiaiPrototypeTheme { fixture.Render() } }
        compose.onNodeWithContentDescription("Reorder provider: Account 2").performClick()
        compose.onNodeWithText("Move earlier").performClick()
        compose.runOnIdle {
            assertEquals(listOf(original[1], original[0], original[2]), fixture.entries)
            assertEquals(1, fixture.reordered.size); assertNull(fixture.opened)
        }
        compose.onNodeWithContentDescription("Reorder provider: Account 2").performClick()
        compose.onNodeWithText("Move earlier").assertIsNotEnabled()
        compose.onNodeWithText("Move later").performClick()
        compose.runOnIdle { assertEquals(original, fixture.entries); assertNull(fixture.opened) }
    }

    @Test fun dragAcrossRowsSavesOnceAndCancellationOrExternalRemovalNeverSaves() {
        val fixture = Fixture()
        val original = fixture.entries
        compose.setContent { TachiaiPrototypeTheme { fixture.Render() } }
        fun dragFirst(cancelled: Boolean) {
            val handle = compose.onNodeWithContentDescription("Reorder provider: ${fixture.entries.first().name}")
            val start = handle.fetchSemanticsNode().boundsInRoot
            val end = compose.onNodeWithContentDescription("Reorder provider: ${fixture.entries.last().name}").fetchSemanticsNode().boundsInRoot
            handle.performTouchInput {
                down(center); moveTo(Offset(center.x, center.y + end.bottom - start.center.y + 16f), 600)
                if (cancelled) cancel() else up()
            }
        }
        dragFirst(true)
        compose.runOnIdle { assertEquals(original, fixture.entries); assertTrue(fixture.reordered.isEmpty()) }
        dragFirst(false)
        compose.runOnIdle { assertEquals(original.drop(1) + original.first(), fixture.entries); assertEquals(1, fixture.reordered.size); assertNull(fixture.opened) }
        val handle = compose.onNodeWithContentDescription("Reorder provider: ${fixture.entries.first().name}")
        compose.mainClock.autoAdvance = false
        try {
            handle.performTouchInput { down(center); moveBy(Offset(0f, 100f), 200) }
            compose.runOnIdle { fixture.entries = fixture.entries.dropLast(1) }
            compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
            handle.performTouchInput { up() }
        } finally { compose.mainClock.autoAdvance = true }
        compose.runOnIdle { assertEquals(1, fixture.reordered.size) }
    }

    @Test fun longListDragScrollsToLastProviderAndKeepsOneDurableSave() {
        val fixture = Fixture(14)
        val original = fixture.entries
        compose.setContent { TachiaiPrototypeTheme { Box(Modifier.width(360.dp).height(400.dp)) { fixture.Render() } } }
        val handle = compose.onNodeWithContentDescription("Reorder provider: Account 1")
        val start = handle.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("Providers list").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            handle.performTouchInput { down(center); moveTo(Offset(center.x, center.y + viewport.bottom - start.center.y - 8f), 200) }
            repeat(40) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
            handle.performTouchInput { up() }
        } finally { compose.mainClock.autoAdvance = true }
        compose.runOnIdle { assertEquals(original.drop(1) + original.first(), fixture.entries); assertEquals(1, fixture.reordered.size) }
    }

    @Test fun pendingDragWriteKeepsPreviewWithoutAddingStatusRowsAndFailureRestoresCommittedOrder() {
        val entries = Fixture().entries
        val write = Job()
        var busy by mutableStateOf(false)
        var requested: List<String>? = null
        compose.setContent { TachiaiPrototypeTheme {
            ProviderInstancesScreen(entries, emptyList(), busy, null, {}, { _, _, _ -> }, {},
                onReorder = { requested = it; busy = true; write })
        } }
        val handle = compose.onNodeWithContentDescription("Reorder provider: Account 1")
        val start = handle.fetchSemanticsNode().boundsInRoot
        val end = compose.onNodeWithContentDescription("Reorder provider: Account 3").fetchSemanticsNode().boundsInRoot
        handle.performTouchInput { swipe(center, Offset(center.x, center.y + end.bottom - start.center.y + 16f), 600) }
        compose.runOnIdle { assertEquals(entries.drop(1).map { it.id } + entries.first().id, requested) }
        fun top(id: String) = compose.onNodeWithTag("Provider row $id").fetchSemanticsNode().boundsInRoot.top
        assertTrue(top(entries.first().id) > top(entries.last().id))
        compose.onNodeWithText("Reading or saving settings…").assertDoesNotExist()
        compose.runOnIdle { busy = false; write.complete() }
        compose.waitForIdle()
        assertTrue(top(entries.first().id) < top(entries.last().id))
    }
    @Test fun selectedInstanceNameSavesOnEditAndRestoresWithoutAnotherSave() {
        val restoration = StateRestorationTester(compose)
        lateinit var focus: FocusManager
        var savedId: String? = null
        var savedName: String? = null
        restoration.setContent { TachiaiPrototypeTheme {
            focus = LocalFocusManager.current
            ProviderInstancesScreen(defaultProviderInstances() + extra, emptyList(), false, null, {},
                { id, name, route -> savedId = id; savedName = name; assertEquals(SourceRouteChoice.system, route) }, {},
                twitchLogin = { id -> Text(if (id == extra.id) "Second instance login" else "Default instance login") })
        } }
        compose.onNodeWithTag("Provider row ${extra.id}").performScrollTo().performClick()
        compose.onNodeWithText("Second instance login").performScrollTo().assertExists()
        compose.onNodeWithText("Default instance login").assertDoesNotExist()
        compose.onNodeWithText("Provider instance name").performScrollTo().performTextReplacement("My second account")
        compose.runOnIdle { focus.clearFocus(force = true) }
        closeSoftKeyboard(); compose.waitForIdle()
        compose.runOnIdle { assertEquals(extra.id, savedId); assertEquals("My second account", savedName) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("My second account").performScrollTo().assertExists()
        compose.onNodeWithText("Second instance login").performScrollTo().assertExists()
        compose.onNodeWithText("Save Twitch 2 setup").assertDoesNotExist()
        compose.runOnIdle { assertEquals(extra.id, savedId); assertEquals("My second account", savedName) }
    }
    @Test fun creationAndDuplicateNameValidationCannotMutateAnotherInstance() {
        val instances = mutableStateOf(defaultProviderInstances())
        var created: PrototypeService? = null
        var saves = 0
        lateinit var focus: FocusManager
        compose.setContent { TachiaiPrototypeTheme {
            focus = LocalFocusManager.current
            ProviderInstancesScreen(instances.value, emptyList(), false, null, {}, { _, _, _ -> saves++ }, {},
                onCreate = { created = it; instances.value = instances.value + extra })
        } }
        compose.onNodeWithText("Add Twitch instance").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(PrototypeService.TWITCH, created); assertEquals(0, saves) }
        compose.onNodeWithTag("Provider row ${extra.id}").performScrollTo().performClick()
        compose.onNodeWithText("Provider instance name").performScrollTo().performTextReplacement("Twitch")
        compose.runOnIdle { focus.clearFocus(force = true) }
        closeSoftKeyboard(); compose.waitForIdle()
        compose.onNodeWithText("Use a unique name, up to 64 printable characters.").performScrollTo().assertExists()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun unicodeNameValidationMatchesRegistryRootLowercasePolicy() {
        val instances = defaultProviderInstances().map {
            if (it.service == PrototypeService.TWITCH) it.copy(customName = "Account \u0130") else it
        } + extra
        lateinit var focus: FocusManager
        var savedId: String? = null
        var savedName: String? = null
        compose.setContent { TachiaiPrototypeTheme {
            focus = LocalFocusManager.current
            ProviderInstancesScreen(instances, emptyList(), false, null, {},
                { id, name, route -> savedId = id; savedName = name; assertEquals(SourceRouteChoice.system, route) }, {})
        } }
        compose.onNodeWithTag("Provider row ${extra.id}").performScrollTo().performClick()
        compose.onNodeWithText("Provider instance name").performScrollTo().performTextReplacement("Account i\u0307")
        compose.runOnIdle { focus.clearFocus(force = true) }
        closeSoftKeyboard(); compose.waitForIdle()
        // ROOT lowercasing expands capital dotted I to i + combining dot.
        compose.onNodeWithText("Use a unique name, up to 64 printable characters.").performScrollTo().assertExists()
        compose.runOnIdle { assertNull(savedId) }
        compose.onNodeWithText("Provider instance name").performScrollTo().performTextReplacement("Account I")
        compose.runOnIdle { focus.clearFocus(force = true) }
        closeSoftKeyboard(); compose.waitForIdle()
        // Plain I lowercases to i without the dot, so this distinct name is allowed.
        compose.onNodeWithText("Save Twitch 2 setup").assertDoesNotExist()
        compose.runOnIdle { assertEquals(extra.id, savedId); assertEquals("Account I", savedName) }
    }
}
