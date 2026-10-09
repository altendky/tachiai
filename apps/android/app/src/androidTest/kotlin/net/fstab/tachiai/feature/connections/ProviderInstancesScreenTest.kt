package net.fstab.tachiai.feature.connections

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import net.fstab.tachiai.feature.presentation.TachiaiPrototypeTheme
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProviderInstancesScreenTest {
    @get:Rule val compose = createComposeRule()
    private val extra = ProviderInstance("12345678-1234-1234-1234-123456789abc", PrototypeService.TWITCH, "Twitch 2")
    @Test fun selectedInstanceNameAndRouteDraftRestoreAndRequireExplicitSave() {
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
        compose.onNodeWithText("Configure Twitch 2").performScrollTo().performClick()
        compose.onNodeWithText("Second instance login").performScrollTo().assertExists()
        compose.onNodeWithText("Default instance login").assertDoesNotExist()
        compose.onNodeWithText("Provider instance name").performScrollTo().performTextReplacement("My second account")
        compose.runOnIdle { focus.clearFocus(force = true) }
        closeSoftKeyboard(); compose.waitForIdle()
        compose.runOnIdle { assertNull(savedId) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("My second account").performScrollTo().assertExists()
        compose.onNodeWithText("Second instance login").performScrollTo().assertExists()
        compose.onNodeWithText("Save Twitch 2 setup").performScrollTo().performClick()
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
        compose.onNodeWithText("Configure Twitch 2").performScrollTo().performClick()
        compose.onNodeWithText("Provider instance name").performScrollTo().performTextReplacement("Twitch")
        compose.runOnIdle { focus.clearFocus(force = true) }
        closeSoftKeyboard(); compose.waitForIdle()
        compose.onNodeWithText("Save Twitch 2 setup").performScrollTo().assertIsNotEnabled()
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
        compose.onNodeWithText("Configure Twitch 2").performScrollTo().performClick()
        compose.onNodeWithText("Provider instance name").performScrollTo().performTextReplacement("Account i\u0307")
        compose.runOnIdle { focus.clearFocus(force = true) }
        closeSoftKeyboard(); compose.waitForIdle()
        // ROOT lowercasing expands capital dotted I to i + combining dot.
        compose.onNodeWithText("Save Twitch 2 setup").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertNull(savedId) }
        compose.onNodeWithText("Provider instance name").performScrollTo().performTextReplacement("Account I")
        compose.runOnIdle { focus.clearFocus(force = true) }
        closeSoftKeyboard(); compose.waitForIdle()
        // Plain I lowercases to i without the dot, so this distinct name is allowed.
        compose.onNodeWithText("Save Twitch 2 setup").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(extra.id, savedId); assertEquals("Account I", savedName) }
    }
}
