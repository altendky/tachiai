package net.fstab.tachiai.presentation

import org.junit.Assert.*
import org.junit.Test

class PrototypeSourcesTest {
    @Test fun `all sixteen ordered choices including duplicates retain slot identity`() {
        var choices = 0
        for (a in PrototypeSource.entries) for (b in PrototypeSource.entries) {
            val selected = PrototypeSelection(a, b)
            assertEquals(listOf(a, b), selected.sources)
            assertNotEquals(a.slotLabel("A"), b.slotLabel("B"))
            choices++
        }
        assertEquals(16, choices)
    }
    @Test fun `catalogue has live and replay for each initial service`() {
        for (service in PrototypeService.entries) {
            assertEquals(PrototypePlaybackKind.entries.toSet(),
                PrototypeSource.entries.filter { it.service == service }.map { it.kind }.toSet())
        }
    }
}
