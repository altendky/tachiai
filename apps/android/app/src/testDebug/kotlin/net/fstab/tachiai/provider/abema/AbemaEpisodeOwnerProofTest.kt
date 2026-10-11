package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.platform.network.parseConnectionProfile
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test

class AbemaEpisodeOwnerProofTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val routeId = "12345678-1234-1234-1234-123456789abd"
    private fun instance(route: SourceRouteChoice = SourceRouteChoice.system) =
        ProviderInstance(id, PrototypeService.ABEMA, "ABEMA", setup = ProviderSetup(route))
    private fun profile(secret: String = "fixture-secret") =
        parseConnectionProfile("http://user:$secret@proxy.example.test:3128".toByteArray())

    @Test fun proofMatchesExactOwnershipButExcludesDisplayNamesAndExposesNoRawConfiguration() {
        val selected = instance(SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, routeId, "Private route"))
        val owner = AbemaCatalogRouteOwner(selected, profile())
        val digest = owner.proof()
        assertTrue(digest.matches(Regex("[a-f0-9]{64}")))
        assertEquals(digest, AbemaCatalogRouteOwner(selected.copy(customName = "Renamed"), profile()).proof())
        assertEquals(digest, AbemaCatalogRouteOwner(selected.copy(setup = ProviderSetup(
            SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, routeId, "Renamed route"))), profile()).proof())
        assertNotEquals(digest, AbemaCatalogRouteOwner(selected, profile("changed-secret")).proof())
        assertNotEquals(digest, AbemaCatalogRouteOwner(instance(), null).proof())
        assertNotEquals(digest, AbemaCatalogRouteOwner(selected.copy(id = "12345678-1234-1234-1234-123456789abe"), profile()).proof())
        assertNotEquals(digest, AbemaCatalogRouteOwner(selected.copy(setup = ProviderSetup(
            SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, "12345678-1234-1234-1234-123456789abf", "Private route"))), profile()).proof())
        assertFalse(owner.toString().contains("fixture-secret")); assertFalse(owner.toString().contains("proxy.example.test"))
    }
}
