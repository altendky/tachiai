package net.fstab.tachiai.presentation

import org.junit.Assert.*
import org.junit.Test

class PrototypeSourcesTest {
    @Test fun `all ordered choices including duplicates retain slot identity`() {
        var choices = 0
        for (a in PrototypeSource.entries) for (b in PrototypeSource.entries) {
            val selected = PrototypeSelection(a, b)
            assertEquals(listOf(a, b), selected.sources)
            assertNotEquals(a.slotLabel("A"), b.slotLabel("B"))
            choices++
        }
        assertEquals(36, choices)
    }
    @Test fun `catalogue has live and replay for each initial service`() {
        for (service in PrototypeService.entries) {
            assertEquals(PrototypePlaybackKind.entries.toSet(),
                PrototypeSource.entries.filter { it.service == service }.map { it.kind }.toSet())
        }
        val twitch = PrototypeSource.entries.filter { it.service == PrototypeService.TWITCH }
        assertTrue(twitch.all { !it.resourceId.isNullOrBlank() })
        assertEquals(twitch.size, twitch.map { it.resourceId }.toSet().size)
    }
    @Test fun `assignment replaces only the selected slot and permits both on one row`() {
        val initial = PrototypeSourceAssignments()
        val duplicate = initial.assign(PrototypeSlot.B, PrototypeSource.ABEMA_LIVE, true)
        assertEquals(PrototypeSelection(PrototypeSource.ABEMA_LIVE, PrototypeSource.ABEMA_LIVE), duplicate.selectionOrNull())
        val moved = duplicate.assign(PrototypeSlot.A, PrototypeSource.TWITCH_REPLAY, true)
        assertEquals(PrototypeSource.ABEMA_LIVE, moved.b)
        assertEquals(PrototypeSource.TWITCH_REPLAY, moved.a)
        assertEquals(moved, moved.assign(PrototypeSlot.A, PrototypeSource.ABEMA_LIVE, false))
    }
    @Test fun `clearing either slot blocks complete playback selection until reassigned`() {
        for (slot in PrototypeSlot.entries) {
            val initial = PrototypeSourceAssignments()
            val source = if (slot == PrototypeSlot.A) initial.a!! else initial.b!!
            val cleared = initial.assign(slot, source, false)
            assertNull(cleared.selectionOrNull())
            assertEquals(initial, cleared.assign(slot, source, true))
        }
    }
}
