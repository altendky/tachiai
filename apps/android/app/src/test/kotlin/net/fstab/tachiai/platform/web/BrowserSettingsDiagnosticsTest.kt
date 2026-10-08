package net.fstab.tachiai.platform.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSettingsDiagnosticsTest {
    private val snapshot = BrowserSettingsSnapshot(
        BrowserSettingsRole.POPUP, true, true, true, true, true, true,
        false, false, false, false, true,
    )

    @Test
    fun `serialization contains only closed role and configuration booleans`() {
        assertEquals("role=POPUP defaultIdentity=true identityMatchesParent=true" +
            " javaScript=true domStorage=true firstPartyCookies=true thirdPartyCookies=true" +
            " wideViewport=false overviewMode=false multipleWindows=false automaticWindows=false" +
            " mixedContentBlocked=true", snapshot.fixedMessage())
        BrowserSettingsRole.entries.forEach { role ->
            val message = snapshot.copy(role = role, defaultIdentity = false, identityMatchesParent = null).fixedMessage()
            assertTrue(message.startsWith("role=${role.name} defaultIdentity=false"))
            assertFalse(message.contains("identityMatchesParent"))
            assertTrue(message.matches(Regex("role=[A-Z]+(?: [A-Za-z]+=(?:true|false))+")))
        }
    }

    @Test
    fun `nested window dispatch is distinguishable from automatic popup permission`() {
        val message = snapshot.copy(multipleWindows = true).fixedMessage()
        assertTrue(message.contains(" multipleWindows=true automaticWindows=false"))
        assertTrue(message.contains(" mixedContentBlocked=true"))
    }

    @Test
    fun `release and non opted in requests never emit settings`() {
        listOf(false to false, false to true, true to false).forEach { (enabled, debug) ->
            logBrowserSettings(snapshot, enabled, debug) { throw AssertionError("disabled logging reached sink") }
        }
        val messages = mutableListOf<String>()
        logBrowserSettings(snapshot, true, true, messages::add)
        assertEquals(listOf(snapshot.fixedMessage()), messages)
    }
}
