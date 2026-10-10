package net.fstab.tachiai.provider.twitch.catalog

import java.io.IOException
import java.net.URL
import java.util.UUID
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.twitch.*
import org.junit.Assert.*
import org.junit.Test

class AndroidTwitchLiveIdentityResolverTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var forbidden = false
        override fun read(): ByteArray? { check(!forbidden); return bytes?.copyOf() }
        override fun write(plaintext: ByteArray) { check(!forbidden); bytes = plaintext.copyOf() }
    }
    private fun page(rows: List<Map<String, Any?>>) = TwitchHelixResponse(200,
        mapOf("data" to rows, "pagination" to emptyMap<String, Any?>()))
    private val resource = CatalogResource(ProviderId("twitch"), "broadcaster", "123", CatalogIntent.CHANNEL)
    private inner class Fixture {
        val id = UUID.randomUUID().toString()
        val routeId = UUID.randomUUID().toString()
        val profile = parseConnectionProfile("http://fixture:fixture-secret@proxy.example.test:3128".toByteArray())
        val choice = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, routeId, "Fixture route")
        val route = TwitchCatalogRouteOwner(ProviderInstance(id, PrototypeService.TWITCH, "Fixture", setup = ProviderSetup(choice)), profile)
        val memory = Memory()
        val store = TwitchCatalogGrantStore(memory, id, wallMs = { 1_000L })
        var owner = route.presentation
        var admission = true
        var ownerReads = 0
        var requests = 0
        var authRequests = 0
        var routeCloses = 0
        var failCleanup = false
        var status = 200
        var now = 1_000L
        val retryGate = TwitchBroadcasterRetryGate { now }
        val routes = mutableListOf<RouteSession>()
        init {
            store.commitConnection(store.beginConnection(), TwitchCatalogReplacement(
                TwitchCatalogCredentials("fixture-catalog-access", "fixture-refresh", 7200_000L),
                TwitchCatalogValidation("catalog-user", setOf(TWITCH_CATALOG_SCOPE), 7200_000L), 7200_000L))!!
        }
        fun resolver(expectedProfile: ConnectionProfile? = profile, expectedRoute: SourceRouteChoice = choice): TwitchLiveIdentityResolver {
            val binding = object : TwitchCatalogConnectionBinding {
                var closed = false
                override fun owner(): TwitchCatalogConnectionOwner { check(!memory.forbidden); ownerReads++; return owner }
                override fun canPublishLocally() = admission && !closed
                override fun session() = TwitchCatalogSession(store, { transport { true } },
                    wallMs = { 1_000L }, monotonicMs = { 10_000L }, canCommit = { canPublishLocally() && owner().sameOwnership(route.presentation) })
                override fun transport(canRequest: () -> Boolean) = object : TwitchCatalogTransport {
                    override fun device(): DeviceAuthResponse = error("No consent")
                    override fun poll(deviceCode: String): DeviceAuthResponse = error("No polling")
                    override fun refresh(refreshToken: String): DeviceAuthResponse = error("No refresh")
                    override fun validate(accessToken: String): DeviceAuthResponse {
                        check(canRequest()); assertEquals("fixture-catalog-access", accessToken); authRequests++
                        return DeviceAuthResponse(200, mapOf("client_id" to TACHIAI_TWITCH_CLIENT_ID, "user_id" to "catalog-user",
                            "scopes" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to 7200))
                    }
                    override fun close() = Unit
                }
                override fun close() { closed = true }
            }
            return ownedTwitchLiveIdentityResolver(id, expectedProfile, route, binding, { admission },
                transportFactory = { actual, gate, cleanup ->
                    assertSame(profile, actual)
                    OwnedTwitchHelixTransport(actual, gate, onCleanupFailure = cleanup,
                        createRoute = { selected, preparation -> RouteSession.createTwitchCatalog(selected, preparation) { _, security ->
                            assertEquals("id.twitch.tv,api.twitch.tv", security.allowedHosts)
                            object : RouteBackend {
                                override val proxyPort = 12345
                                override fun close() { routeCloses++; if (failCleanup) error("Fixture cleanup") }
                            }
                        }.also(routes::add) }, createTransport = { open, admitted -> object : TwitchHelixTransport {
                            override fun execute(accessToken: String, request: TwitchHelixRequest): TwitchHelixResponse {
                                check(admitted()); assertEquals("fixture-catalog-access", accessToken); requests++
                                assertThrows(IllegalStateException::class.java) { open(URL("https://gql.twitch.tv/gql")) }
                                if (status != 200) return TwitchHelixResponse(status, retryAtEpochMs = if (status == 429) 2_000L else null)
                                return when (request.operation) {
                                    TwitchHelixOperation.USERS -> page(listOf(mapOf("id" to "123", "login" to "renamed_channel", "display_name" to "Renamed")))
                                    TwitchHelixOperation.STREAMS -> page(listOf(mapOf("id" to "456", "user_id" to "123",
                                        "user_login" to "renamed_channel", "user_name" to "Renamed", "type" to "live")))
                                    else -> error("Unexpected metadata endpoint")
                                }
                            }
                            override fun close() = Unit
                        } })
                }, retryGate = retryGate, expectedRoute = expectedRoute, wallMs = { now })
        }
    }
    private fun identity(result: CatalogResult<TwitchLiveIdentity>) = (result as CatalogResult.Value).value

    @Test fun typedFactoryRejectsChangedNativeProfileAndRouteChoiceBeforeAnyRequest() {
        val fixture = Fixture()
        val other = parseConnectionProfile("http://changed.example.test:3128".toByteArray())
        assertThrows(IllegalStateException::class.java) { fixture.resolver(other) }
        assertThrows(IllegalStateException::class.java) { fixture.resolver(null) }
        assertThrows(IllegalStateException::class.java) { fixture.resolver(expectedRoute = SourceRouteChoice.system) }
        assertThrows(IllegalStateException::class.java) { fixture.resolver(expectedRoute = fixture.choice.copy(connectionId = UUID.randomUUID().toString())) }
        assertEquals(0, fixture.requests); assertEquals(0, fixture.authRequests)
        fixture.resolver(expectedRoute = fixture.choice.copy(connectionName = "Renamed route")).close()
    }

    @Test fun everyMappingExchangeUsesSelectedClosedMetadataRouteAndPublicationNeverReadsOwnerOrGrant() {
        val fixture = Fixture(); val resolver = fixture.resolver()
        try {
            val result = identity(resolver.begin(resource))
            assertFalse(resolver.canPublish(result))
            assertTrue(resolver.confirm(result) is CatalogResult.Value)
            assertEquals(3, fixture.requests); assertEquals(3, fixture.routeCloses)
            fixture.routes.forEach { assertThrows(IOException::class.java) { it.open(URL("https://api.twitch.tv/helix/users?id=123")) } }
            val reads = fixture.ownerReads
            fixture.memory.forbidden = true
            assertTrue(resolver.canPublish(result)); assertEquals(reads, fixture.ownerReads)
            fixture.store.invalidate()
            assertFalse(resolver.canPublish(result)); assertEquals(reads, fixture.ownerReads)
        } finally { resolver.close() }
    }

    @Test fun laterRouteOwnerChangeAndUncertainCleanupBlockPublicationWithoutSystemFallback() {
        val fixture = Fixture(); val resolver = fixture.resolver()
        try {
            val result = identity(resolver.begin(resource))
            fixture.owner = TwitchCatalogConnectionOwner(fixture.id, "Fixture", "Route", "changed-owner")
            assertEquals(CatalogFailure.ACCESS_REQUIRED, (resolver.confirm(result) as CatalogResult.Failure).reason)
            assertFalse(resolver.canPublish(result)); assertEquals(2, fixture.requests)
        } finally { resolver.close() }
        val failed = Fixture(); failed.failCleanup = true
        val unavailable = failed.resolver()
        try {
            assertTrue(unavailable.begin(resource) is CatalogResult.Failure)
            assertEquals(1, failed.requests); assertEquals(1, failed.routeCloses)
            assertEquals(CatalogFailure.ACCESS_REQUIRED, (unavailable.begin(resource) as CatalogResult.Failure).reason)
            assertEquals(1, failed.requests)
        } finally { unavailable.close() }
    }

    @Test fun rateHintSurvivesNewResolverAndBlocksExplicitRetryUntilDeadlineWithoutWaiting() {
        val fixture = Fixture(); fixture.status = 429
        fixture.resolver().use { assertEquals(CatalogFailure.RATE_LIMITED, (it.begin(resource) as CatalogResult.Failure).reason) }
        assertEquals(1, fixture.requests); assertTrue(fixture.retryGate.isBlocked(fixture.id))
        fixture.status = 200
        fixture.resolver().use { assertEquals(2_000L, (it.begin(resource) as CatalogResult.Failure).retryAtEpochMs) }
        assertEquals(1, fixture.requests); assertEquals(1, fixture.authRequests)
        fixture.now = 2_000L
        assertFalse(fixture.retryGate.isBlocked(fixture.id))
        fixture.resolver().use { assertEquals("renamed_channel", identity(it.begin(resource)).login) }
        assertEquals(3, fixture.requests)
    }
}
