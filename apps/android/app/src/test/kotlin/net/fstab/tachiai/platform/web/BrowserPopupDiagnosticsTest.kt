package net.fstab.tachiai.platform.web

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPopupDiagnosticsTest {
    @Test
    fun `subresource errors are nonterminal numeric observations not native popup failures`() {
        assertFalse(PopupEventKind.SUBRESOURCE_HTTP_ERROR.failure)
        assertFalse(PopupEventKind.SUBRESOURCE_NETWORK_ERROR.failure)
        assertEquals("popup=1 event=SUBRESOURCE_HTTP_ERROR code=403",
            PopupDiagnosticEvent(1, PopupEventKind.SUBRESOURCE_HTTP_ERROR, platformCode = 403).fixedMessage())
        assertEquals("popup=1 event=SUBRESOURCE_NETWORK_ERROR code=-2",
            PopupDiagnosticEvent(1, PopupEventKind.SUBRESOURCE_NETWORK_ERROR, platformCode = -2).fixedMessage())
        assertThrows(IllegalArgumentException::class.java) {
            PopupDiagnosticEvent(1, PopupEventKind.SCRIPT_ALERT_CANCELLED, platformCode = 200)
        }
    }

    @Test
    fun `debug output has only fixed typed fields and numeric platform codes`() {
        val output = mutableListOf<String>()
        val event = PopupDiagnosticEvent(3, PopupEventKind.HTTP_ERROR, PopupDestinationClass.ALLOWED_HTTPS, 403)
        logPopupEvent(event, debug = true, sink = output::add)
        assertEquals(listOf("popup=3 event=HTTP_ERROR destination=ALLOWED_HTTPS code=403"), output)
        assertEquals("popup=4 event=NETWORK_ERROR code=-2",
            PopupDiagnosticEvent(4, PopupEventKind.NETWORK_ERROR, platformCode = -2).fixedMessage())
        PopupEventKind.entries.forEach { kind ->
            assertTrue(PopupDiagnosticEvent(1, kind).fixedMessage().matches(Regex("popup=1 event=[A-Z_]+")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PopupDiagnosticEvent(1, PopupEventKind.OPENED, platformCode = 200)
        }
        assertThrows(IllegalArgumentException::class.java) { PopupDiagnosticEvent(0, PopupEventKind.OPENED) }
    }

    @Test
    fun `release mode never calls the log sink`() {
        logPopupEvent(PopupDiagnosticEvent(1, PopupEventKind.TLS_ERROR), debug = false) {
            throw AssertionError("release logging reached sink")
        }
    }

    @Test
    fun `classification never serializes URI data or widens the navigation allowlist`() {
        val hosts = setOf("www.twitch.tv")
        val alternateHosts = setOf("m.twitch.tv")
        val known = URI("https://m.twitch.tv/login?opaque=not-for-output#private")
        assertEquals(PopupDestinationClass.ALTERNATE_PROVIDER_HTTPS, BrowserPopupPolicy.classify(known, hosts, alternateHosts))
        assertFalse(BrowserPopupPolicy.allows(known, hosts))
        assertEquals(PopupDestinationClass.ALLOWED_HTTPS,
            BrowserPopupPolicy.classify(URI("https://www.twitch.tv/login?opaque=not-for-output"), hosts, alternateHosts))
        assertEquals(PopupDestinationClass.INITIAL_BLANK, BrowserPopupPolicy.classify(URI("about:blank"), hosts, alternateHosts))
        listOf(null, URI("https://user@m.twitch.tv/login"), URI("https://m.twitch.tv:444/login"),
            URI("https://m.twitch.tv.evil.example/login"), URI("https://evil.example/login"), URI("intent://login")).forEach {
            assertEquals(PopupDestinationClass.OTHER, BrowserPopupPolicy.classify(it, hosts, alternateHosts))
        }
        val message = PopupDiagnosticEvent(1, PopupEventKind.NAVIGATION_BLOCKED,
            BrowserPopupPolicy.classify(known, hosts, alternateHosts)).fixedMessage()
        assertEquals("popup=1 event=NAVIGATION_BLOCKED destination=ALTERNATE_PROVIDER_HTTPS", message)
        assertFalse(message.contains("twitch.tv"))
        assertFalse(message.contains("not-for-output"))
        assertFalse(message.contains("login"))
    }
}
