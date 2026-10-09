package net.fstab.tachiai.platform.network

import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test

class ProviderInstanceRoutePlanTest {
    private val secondId = "12345678-1234-1234-1234-123456789abc"
    private val routeId = "12345678-1234-1234-1234-123456789abd"
    private val duplicateRouteId = "12345678-1234-1234-1234-123456789abe"
    private fun choice(id: String) = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, id, "Route")
    private fun profile(text: String) = parseConnectionProfile(text.toByteArray())
    @Test fun twoAbemaInstancesRequireCanonicalRouteEqualityBeforeAnyBackendIsCreated() {
        val initial = defaultProviderInstances()
        val first = initial.first().copy(setup = ProviderSetup(choice(routeId)))
        val second = ProviderInstance(secondId, PrototypeService.ABEMA, "ABEMA 2", setup = ProviderSetup(choice(duplicateRouteId)))
        val selected = PrototypeSelection(PrototypeSource.ABEMA_LIVE, PrototypeSource.ABEMA_REPLAY, first.id, second.id)
        val same = profile("http://fixture.example.test:8080")
        val duplicate = planProviderInstanceRoutes(selected, listOf(first, initial.last(), second)) { same }
        assertTrue(duplicate.abemaCompatible); assertEquals(2, duplicate.profiles.size)
        val different = planProviderInstanceRoutes(selected, listOf(first, initial.last(), second)) {
            if (it == routeId) same else profile("http://other.example.test:8080")
        }
        assertFalse(different.abemaCompatible)
        val mixed = planProviderInstanceRoutes(selected, listOf(first, initial.last(), second.copy(setup = ProviderSetup(SourceRouteChoice.system)))) { same }
        assertFalse(mixed.abemaCompatible)
        val missing = planProviderInstanceRoutes(selected, listOf(first, initial.last(), second)) { error("Deleted route") }
        assertFalse(missing.abemaCompatible); assertTrue(missing.profiles.values.all { it.isFailure })
    }
    @Test fun duplicateFeedsUseOneProfileSnapshotButDifferentTwitchInstancesKeepDistinctRoutes() {
        val defaults = defaultProviderInstances()
        val first = defaults.last().copy(setup = ProviderSetup(choice(routeId)))
        val other = ProviderInstance(secondId, PrototypeService.TWITCH, "Twitch 2", setup = ProviderSetup(choice(duplicateRouteId)))
        val instances = listOf(defaults.first(), first, other)
        var reads = 0
        val duplicate = PrototypeSelection(PrototypeSource.TWITCH_LIVE, PrototypeSource.TWITCH_LIVE, first.id, first.id)
        val single = planProviderInstanceRoutes(duplicate, instances) { reads++; profile("http://fixture.example.test:8080") }
        assertEquals(1, reads); assertEquals(1, single.profiles.size)
        val separate = planProviderInstanceRoutes(duplicate.copy(bInstanceId = other.id), instances) {
            profile(if (it == routeId) "http://fixture.example.test:8080" else "http://other.example.test:8080")
        }
        assertTrue(separate.abemaCompatible)
        assertNotEquals(routeConfigurationKey(separate.profiles[first.id]!!.getOrThrow()!!),
            routeConfigurationKey(separate.profiles[other.id]!!.getOrThrow()!!))
    }
    @Test fun staleInstanceAndPendingMigrationNeverReadAReplacementProfile() {
        val stale = PrototypeSelection(PrototypeSource.TWITCH_LIVE, PrototypeSource.TWITCH_LIVE, secondId, secondId)
        val plan = planProviderInstanceRoutes(stale, defaultProviderInstances()) { error("Must not resolve stale identity") }
        assertTrue(plan.profiles.values.single().isFailure)
        val pending = defaultProviderInstances().map { if (it.service == PrototypeService.TWITCH) it.copy(setup = ProviderSetup(null)) else it }
        val missing = planProviderInstanceRoutes(PrototypeSelection(), pending) { error("Must not resolve pending route") }
        assertTrue(missing.profiles[defaultProviderInstanceId(PrototypeService.TWITCH)]!!.isFailure)
        val absentAbema = defaultProviderInstances().map { if (it.service == PrototypeService.ABEMA)
            it.copy(setup = ProviderSetup(choice(routeId))) else it }
        val partial = planProviderInstanceRoutes(PrototypeSelection(), absentAbema) { error("Deleted ABEMA route") }
        assertTrue(partial.abemaCompatible) // Preserve ordinary single-instance per-feed failure.
        assertTrue(partial.profiles[defaultProviderInstanceId(PrototypeService.ABEMA)]!!.isFailure)
        assertTrue(partial.profiles[defaultProviderInstanceId(PrototypeService.TWITCH)]!!.isSuccess)
    }
}
