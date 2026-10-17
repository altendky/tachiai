package net.fstab.tachiai.feature.presentation

import org.junit.Assert.*
import org.junit.Test

class PrototypeRecoveryTest {
    @Test fun `first blocking incident survives repeated and different failure notifications`() {
        val state = PrototypeRecoveryState()
        var changes = 0
        state.addObserver { changes++ }
        val first = state.fail(PrototypeRecoveryKind.PLAYBACK_CLEANUP_UNCONFIRMED)
        assertSame(first, state.fail(PrototypeRecoveryKind.PLAYBACK_CLEANUP_UNCONFIRMED))
        assertSame(first, state.fail(PrototypeRecoveryKind.ROUTE_CLEANUP_UNCONFIRMED))
        assertEquals(1, changes)
        assertTrue(first.id > 0)
        assertEquals(PrototypeRecoveryKind.PLAYBACK_CLEANUP_UNCONFIRMED, first.kind)
    }

    @Test fun `ignore acknowledges only current incident and leaves blocking state intact`() {
        val state = PrototypeRecoveryState()
        val incident = state.fail(PrototypeRecoveryKind.ROUTE_CLEANUP_UNCONFIRMED)
        state.ignore(incident.id + 1)
        assertFalse(checkNotNull(state.incident).acknowledged)
        state.ignore(incident.id)
        assertEquals(incident.copy(acknowledged = true), state.incident)
        val acknowledged = state.incident
        state.ignore(incident.id)
        state.fail(PrototypeRecoveryKind.PLAYBACK_CLEANUP_UNCONFIRMED)
        assertSame(acknowledged, state.incident)
    }

    @Test fun `restart is guarded and a failed restart permits retry without clearing the incident`() {
        val state = PrototypeRecoveryState()
        assertFalse(state.beginRestart(1))
        val incident = state.fail(PrototypeRecoveryKind.PICKER_RESTORE_FAILED)
        state.ignore(incident.id)
        assertFalse(state.beginRestart(incident.id + 1))
        assertTrue(state.beginRestart(incident.id))
        assertFalse(state.beginRestart(incident.id))
        state.restartFailed(incident.id + 1)
        assertTrue(checkNotNull(state.incident).restarting)
        state.restartFailed(incident.id)
        assertEquals(incident.copy(acknowledged = true), state.incident)
        assertTrue(state.beginRestart(incident.id))
    }

    @Test fun `observer mutations and failed observer do not strand remaining subscribers`() {
        val state = PrototypeRecoveryState()
        val events = mutableListOf<String>()
        val listener: () -> Unit = { events += "current" }
        val remover: () -> Unit = { state.removeObserver(listener); events += "remove" }
        state.addObserver(remover)
        state.addObserver { throw AssertionError("observer failure") }
        state.addObserver(listener)
        state.addObserver(listener)
        state.notifyChanged()
        assertEquals(listOf("remove", "current"), events)
        events.clear()
        state.notifyChanged()
        assertEquals(listOf("remove"), events)
        state.removeObserver(remover)
        state.notifyChanged()
        assertNull(state.incident)
    }

    @Test fun `no-op transitions do not republish and process owners start independently`() {
        val state = PrototypeRecoveryState()
        var changes = 0
        state.addObserver { changes++ }
        state.ignore(1); state.restartFailed(1)
        val first = state.fail(PrototypeRecoveryKind.ROUTE_CLEANUP_UNCONFIRMED)
        state.ignore(first.id)
        state.ignore(first.id)
        state.restartFailed(first.id)
        assertEquals(2, changes)
        val fresh = PrototypeRecoveryState()
        assertNull(fresh.incident)
        assertNotEquals(first.id, fresh.fail(PrototypeRecoveryKind.ROUTE_CLEANUP_UNCONFIRMED).id)
    }
}
