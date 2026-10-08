package net.fstab.tachiai.platform.web

import java.util.Collections
import java.util.concurrent.CountDownLatch
import net.fstab.tachiai.provider.BrowserResourceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BrowserResourceDiagnosticsTest {
    @Test
    fun `logs closed categories elapsed time and errors separately without claiming success`() {
        var now = 100L
        val messages = mutableListOf<String>()
        val diagnostics = BrowserResourceDiagnostics(1, true, true, { now }, messages::add)
        now = 125
        diagnostics.record(BrowserResourceCategory.PROTECTION_SCRIPT, ResourceEventKind.REQUEST_OBSERVED)
        diagnostics.record(BrowserResourceCategory.PROTECTION_SCRIPT, ResourceEventKind.REQUEST_OBSERVED)
        now = 140
        diagnostics.record(BrowserResourceCategory.PROTECTION_SCRIPT, ResourceEventKind.HTTP_ERROR, 400)
        diagnostics.record(null, ResourceEventKind.REQUEST_OBSERVED)
        assertEquals(listOf(
            "popup=1 event=REQUEST_OBSERVED elapsedMs=25 category=PROTECTION_SCRIPT",
            "popup=1 event=HTTP_ERROR elapsedMs=40 category=PROTECTION_SCRIPT code=400",
        ), messages)
        assertFalse(messages.any { "LOADED" in it || "https" in it })
        diagnostics.close()
        diagnostics.record(BrowserResourceCategory.CONFIG_SCRIPT, ResourceEventKind.REQUEST_OBSERVED)
        assertEquals(2, messages.size)
    }

    @Test
    fun `requires diagnostic opt in and debug build`() {
        listOf(false to true, true to false, false to false).forEach { (enabled, debug) ->
            val diagnostics = BrowserResourceDiagnostics(1, enabled, debug,
                { error("disabled diagnostics must not read the clock") },
                { error("disabled diagnostics must not log") })
            diagnostics.record(BrowserResourceCategory.AUTH_UI_SCRIPT, ResourceEventKind.REQUEST_OBSERVED)
        }
    }

    @Test
    fun `resource errors are bounded to sixteen distinct markers and one overflow`() {
        val messages = mutableListOf<String>()
        val diagnostics = BrowserResourceDiagnostics(2, true, true, { 10 }, messages::add)
        repeat(30) { diagnostics.record(BrowserResourceCategory.CONFIG_SCRIPT, ResourceEventKind.HTTP_ERROR, 400 + it) }
        assertEquals(17, messages.size)
        assertEquals("popup=2 event=LIMIT_REACHED elapsedMs=0", messages.last())
    }

    @Test
    fun `concurrent callback threads retain one request marker per category`() {
        val messages = Collections.synchronizedList(mutableListOf<String>())
        val diagnostics = BrowserResourceDiagnostics(1, true, true, { 1 }, messages::add)
        val start = CountDownLatch(1)
        val threads = List(8) {
            Thread {
                start.await()
                repeat(100) {
                    BrowserResourceCategory.entries.forEach { diagnostics.record(it, ResourceEventKind.REQUEST_OBSERVED) }
                }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join(5_000) }
        assertFalse(threads.any { it.isAlive })
        assertEquals(4, messages.size)
    }

    @Test
    fun `invalid event combinations cannot cross the logging boundary`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResourceDiagnosticEvent(1, ResourceEventKind.REQUEST_OBSERVED, BrowserResourceCategory.AUTH_UI_SCRIPT, 0, 400)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResourceDiagnosticEvent(1, ResourceEventKind.HTTP_ERROR, BrowserResourceCategory.AUTH_UI_SCRIPT, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResourceDiagnosticEvent(1, ResourceEventKind.REQUEST_OBSERVED, null, 0)
        }
    }
}
