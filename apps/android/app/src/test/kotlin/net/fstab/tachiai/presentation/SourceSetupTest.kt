package net.fstab.tachiai.presentation

import org.junit.Assert.*
import org.junit.Test

class SourceSetupTest {
    private val japan = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, "12345678-1234-1234-1234-123456789abc", "Japan · Proton")

    @Test fun absentPreferencesUseSystemNetworkNotAnAssumedVpnExit() {
        assertEquals(PrototypeSource.entries.toSet(), defaultSourceSetups().keys)
        assertTrue(unsupportedSourceRoutes(PrototypeSelection(), defaultSourceSetups()).isEmpty())
        assertEquals("System network", SourceRouteChoice.system.title)
    }

    @Test fun duplicateSourceCanResolveTwoDifferentConnections() {
        val setup = SourceSetup("Sumo", japan, feedA = SourceRouteChoice.system)
        assertEquals(SourceRouteChoice.system, setup.route(PrototypeSlot.A))
        assertEquals(japan, setup.route(PrototypeSlot.B))
        val selection = PrototypeSelection(PrototypeSource.ABEMA_REPLAY, PrototypeSource.ABEMA_REPLAY)
        val preferences = defaultSourceSetups() + (PrototypeSource.ABEMA_REPLAY to setup)
        assertEquals(listOf(PrototypeSlot.B), unsupportedSourceRoutes(selection, preferences))
    }

    @Test fun defaultsApplyWithoutOverwritingExplicitFeedOverrides() {
        val setup = SourceSetup("News", SourceRouteChoice.system, feedB = japan)
        assertEquals(japan, setup.copy(defaultRoute = japan).route(PrototypeSlot.B))
        assertEquals(japan, setup.route(PrototypeSlot.B))
        assertEquals(SourceRouteChoice.system, setup.route(PrototypeSlot.A))
        assertEquals(SourceRouteChoice.system, setup.copy(feedB = SourceRouteChoice.system).route(PrototypeSlot.B))
    }

    @Test fun unresolvedOrMalformedPreferencesCannotFallBackToSystem() {
        assertThrows(IllegalStateException::class.java) { unsupportedSourceRoutes(PrototypeSelection(), emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { SourceSetup("News", SourceRouteChoice.inherit) }
        assertThrows(IllegalArgumentException::class.java) { SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, "not-an-id", "Japan") }
        assertThrows(IllegalArgumentException::class.java) { SourceRouteChoice(SourceRouteMode.SYSTEM, japan.connectionId, "Japan") }
        assertThrows(IllegalArgumentException::class.java) { SourceSetup("Hidden\u202Ename") }
    }
}
