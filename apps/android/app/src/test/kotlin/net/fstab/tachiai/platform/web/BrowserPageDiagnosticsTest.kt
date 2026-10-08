package net.fstab.tachiai.platform.web

import net.fstab.tachiai.provider.BrowserRequest
import net.fstab.tachiai.provider.BrowserProbeRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPageDiagnosticsTest {
    @Test
    fun `default dialog comparison is debug only and does not disable console suppression`() {
        val protected = BrowserRequest("https://example.com", suppressScriptDialogsAndConsole = true)
        val comparison = protected.copy(debugUseDefaultScriptDialogs = true)
        assertTrue(suppressScriptDialogs(protected, debug = true))
        assertFalse(suppressScriptDialogs(comparison, debug = true))
        assertTrue(suppressScriptDialogs(comparison, debug = false))
        assertTrue(comparison.suppressScriptDialogsAndConsole)
        assertFalse(suppressScriptDialogs(BrowserRequest("https://example.com"), debug = false))
    }

    @Test
    fun `host policy and geometry use the lifecycle browser id and fixed native values`() {
        val output = mutableListOf<String>()
        val diagnostics = BrowserPageDiagnostics(true, true, output::add)
        diagnostics.record(PageEventKind.STARTED)
        diagnostics.recordHostPolicy(BrowserProbeRole.WEB, dialogsSuppressed = false)
        diagnostics.recordGeometry(2146, 754)
        val id = output[0].substringBefore(" event=")
        assertEquals("$id event=HOST_POLICY role=WEB dialogs=DEFAULT", output[1])
        assertEquals("$id event=GEOMETRY widthPx=2146 heightPx=754", output[2])
        assertThrows(IllegalArgumentException::class.java) { diagnostics.recordGeometry(-1, 100) }
    }

    @Test
    fun `geometry deduplicates adjacent callbacks but records returning dimensions`() {
        val output = mutableListOf<String>()
        val diagnostics = BrowserPageDiagnostics(true, true, output::add)
        diagnostics.recordGeometry(100, 200)
        diagnostics.recordGeometry(100, 200)
        diagnostics.recordGeometry(100, 80)
        diagnostics.recordGeometry(100, 200)
        assertEquals(3, output.size)
        assertEquals(output[0], output[2])
    }

    @Test
    fun `geometry changes are capped with exactly one overflow marker`() {
        val output = mutableListOf<String>()
        val diagnostics = BrowserPageDiagnostics(true, true, output::add)
        repeat(80) { diagnostics.recordGeometry(100, it) }
        assertEquals(33, output.size)
        assertTrue(output.last().endsWith("event=GEOMETRY_LIMIT_REACHED"))
    }

    @Test
    fun `subresource failures remain numeric and host callback markers cannot carry codes`() {
        assertEquals("browser=1 event=SUBRESOURCE_HTTP_ERROR code=403",
            PageDiagnosticEvent(1, PageEventKind.SUBRESOURCE_HTTP_ERROR, 403).fixedMessage())
        assertEquals("browser=1 event=SUBRESOURCE_NETWORK_ERROR code=-2",
            PageDiagnosticEvent(1, PageEventKind.SUBRESOURCE_NETWORK_ERROR, -2).fixedMessage())
        assertThrows(IllegalArgumentException::class.java) {
            PageDiagnosticEvent(1, PageEventKind.SCRIPT_PROMPT_CANCELLED, 200)
        }
    }

    @Test
    fun `events accept only closed kinds local ids and native numeric codes`() {
        assertEquals("browser=2 event=HTTP_ERROR code=403",
            PageDiagnosticEvent(2, PageEventKind.HTTP_ERROR, 403).fixedMessage())
        assertEquals("browser=2 event=NETWORK_ERROR code=-2",
            PageDiagnosticEvent(2, PageEventKind.NETWORK_ERROR, -2).fixedMessage())
        PageEventKind.entries.forEach { kind ->
            assertTrue(PageDiagnosticEvent(1, kind).fixedMessage().matches(Regex("browser=1 event=[A-Z_]+")))
        }
        assertThrows(IllegalArgumentException::class.java) { PageDiagnosticEvent(0, PageEventKind.STARTED) }
        assertThrows(IllegalArgumentException::class.java) { PageDiagnosticEvent(1, PageEventKind.FINISHED, 200) }
    }

    @Test
    fun `disabled and release probes never reach the sink and normal requests opt out`() {
        assertFalse(BrowserRequest("https://example.com").logNativePageEvents)
        listOf(false to false, false to true, true to false).forEach { (enabled, debug) ->
            val diagnostics = BrowserPageDiagnostics(enabled, debug) {
                throw AssertionError("disabled logging reached sink")
            }
            PageEventKind.entries.forEach { diagnostics.record(it) }
            diagnostics.recordHostPolicy(BrowserProbeRole.LOGIN, dialogsSuppressed = true)
            diagnostics.recordGeometry(100, 200)
        }
    }

    @Test
    fun `each opted in debug browser has a distinct local id with stable event serialization`() {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        val a = BrowserPageDiagnostics(true, true, first::add)
        val b = BrowserPageDiagnostics(true, true, second::add)
        a.record(PageEventKind.STARTED)
        a.record(PageEventKind.HTTP_ERROR, 403)
        b.record(PageEventKind.COMMIT_VISIBLE)
        val firstId = first[0].substringBefore(" event=")
        assertEquals("$firstId event=HTTP_ERROR code=403", first[1])
        assertTrue(first[0].matches(Regex("browser=[1-9][0-9]* event=STARTED")))
        assertFalse(firstId == second[0].substringBefore(" event="))
    }
}
