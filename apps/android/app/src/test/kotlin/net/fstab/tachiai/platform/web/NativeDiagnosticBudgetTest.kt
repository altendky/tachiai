package net.fstab.tachiai.platform.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDiagnosticBudgetTest {
    @Test
    fun `duplicate native events do not consume budget or mark overflow`() {
        val budget = NativeDiagnosticBudget<Pair<PageEventKind, Int>>(2)
        val network = PageEventKind.SUBRESOURCE_NETWORK_ERROR to -2
        val http = PageEventKind.SUBRESOURCE_HTTP_ERROR to 403
        assertTrue(budget.admit(network))
        assertFalse(budget.admit(network))
        assertTrue(budget.admit(http))
        assertFalse(budget.admit(http))
        assertFalse(budget.takeLimitMarker())
    }

    @Test
    fun `overflow emits at most one marker and does not reset the lifetime budget`() {
        val budget = NativeDiagnosticBudget<Int>(8)
        (1..8).forEach { assertTrue(budget.admit(it)) }
        assertFalse(budget.takeLimitMarker())
        assertFalse(budget.admit(9))
        assertTrue(budget.takeLimitMarker())
        (10..20).forEach { assertFalse(budget.admit(it)) }
        assertFalse(budget.takeLimitMarker())
        assertFalse(budget.admit(1))
    }

    @Test
    fun `console severities are emitted once each and empty budgets are forbidden`() {
        val budget = NativeDiagnosticBudget<android.webkit.ConsoleMessage.MessageLevel>(2)
        val error = android.webkit.ConsoleMessage.MessageLevel.ERROR
        val warning = android.webkit.ConsoleMessage.MessageLevel.WARNING
        repeat(10) { attempt ->
            if (attempt == 0) { assertTrue(budget.admit(error)); assertTrue(budget.admit(warning)) }
            else { assertFalse(budget.admit(error)); assertFalse(budget.admit(warning)) }
        }
        assertFalse(budget.takeLimitMarker())
        assertThrows(IllegalArgumentException::class.java) { NativeDiagnosticBudget<Int>(0) }
    }
}
