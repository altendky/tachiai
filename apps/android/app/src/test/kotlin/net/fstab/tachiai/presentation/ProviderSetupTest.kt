package net.fstab.tachiai.presentation

import org.junit.Assert.*
import org.junit.Test

class ProviderSetupTest {
    private val japan = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, "12345678-1234-1234-1234-123456789abc", "Proton Japan")
    @Test fun providerDefaultCoversLiveReplayAndDuplicateFeedsWithoutAffectingOtherProvider() {
        val settings = legacyProviderSetups(defaultSourceSetups()) + (PrototypeService.ABEMA to ProviderSetup(japan))
        for (stream in listOf(PrototypeSource.ABEMA_LIVE, PrototypeSource.ABEMA_REPLAY)) {
            assertEquals(listOf(PrototypeSlot.A, PrototypeSlot.B), unsupportedProviderRoutes(PrototypeSelection(stream, stream), settings))
            assertEquals(listOf(PrototypeSlot.A), unsupportedProviderRoutes(PrototypeSelection(stream, PrototypeSource.TWITCH_REPLAY), settings))
        }
        assertTrue(unsupportedProviderRoutes(PrototypeSelection(PrototypeSource.TWITCH_LIVE, PrototypeSource.TWITCH_REPLAY), settings).isEmpty())
    }
    @Test fun consistentLegacyDefaultsAreRetainedWithoutSelectingImportedRoutesAutomatically() {
        val initial = defaultSourceSetups()
        assertEquals(SourceRouteChoice.system, legacyProviderSetups(initial)[PrototypeService.ABEMA]!!.route)
        val previous = initial.mapValues { (stream, setup) -> if (stream.service == PrototypeService.ABEMA) setup.copy(defaultRoute = japan) else setup }
        assertEquals(japan, legacyProviderSetups(previous)[PrototypeService.ABEMA]!!.route)
    }
    @Test fun conflictingDefaultsAndExplicitOverridesRequireReviewRatherThanFallback() {
        val previous = defaultSourceSetups() + (PrototypeSource.ABEMA_REPLAY to SourceSetup("Sumo", japan))
        assertNull(legacyProviderSetups(previous)[PrototypeService.ABEMA]!!.route)
        val override = defaultSourceSetups() + (PrototypeSource.ABEMA_REPLAY to SourceSetup("Sumo", feedA = SourceRouteChoice.system))
        assertNull(legacyProviderSetups(override)[PrototypeService.ABEMA]!!.route)
        assertEquals(listOf(PrototypeSlot.A), unsupportedProviderRoutes(PrototypeSelection(), legacyProviderSetups(previous)))
    }
    @Test fun incompleteOrInheritedProviderSettingsCannotBecomeSystem() {
        assertThrows(IllegalArgumentException::class.java) { ProviderSetup(SourceRouteChoice.inherit) }
        assertThrows(IllegalStateException::class.java) { legacyProviderSetups(emptyMap()) }
        assertThrows(IllegalStateException::class.java) { unsupportedProviderRoutes(PrototypeSelection(), emptyMap()) }
    }
    @Test fun reviewIncludesDefaultEvenWhenBothHistoricalOverridesReplaceIt() {
        val previous = defaultSourceSetups() + (PrototypeSource.ABEMA_REPLAY to SourceSetup("Sumo", japan,
            feedA = SourceRouteChoice.system, feedB = SourceRouteChoice.system))
        val setup = legacyProviderSetups(previous)[PrototypeService.ABEMA]!!
        assertNull(setup.route)
        assertEquals(setOf(japan, SourceRouteChoice.system), setup.previousRoutes.toSet())
    }
}
