package net.fstab.tachiai.provider.twitch.catalog

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.twitch.*
import org.junit.Assert.*
import org.junit.Test

class TwitchCatalogStartupTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null; var writes = 0
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf(); writes++ }
    }
    private fun validation() = DeviceAuthResponse(200, mapOf("client_id" to SMART_TV_TWITCH_CLIENT_ID,
        "user_id" to "123", "scopes" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to 7200))
    private inner class Fixture(saved: Boolean = true) {
        val id = UUID.randomUUID().toString(); val routeId = UUID.randomUUID().toString()
        val instance = ProviderInstance(id, PrototypeService.TWITCH, "Twitch", setup = ProviderSetup(SourceRouteChoice.system,
            fallbackRoutes = listOf(SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, routeId, "Second route"))))
        val profile = parseConnectionProfile("http://user:fixture-secret@proxy.example.test:3128".toByteArray())
        val pool = captureProviderRoutePool(id, PrototypeService.TWITCH, { listOf(instance) }) { mapOf(routeId to profile) }
        val memory = Memory(); var wall = 1000000L; var mono = 1000L; var current = true
        val store = TwitchCatalogGrantStore(memory, id, wallMs = { wall })
        val startup = ProviderRouteStartup(pool, 46000, { mono }, { current }, scheduleDeadline = { _, _ -> AutoCloseable {} })
        val bindings = mutableListOf<Binding>(); val catalogs = mutableListOf<Catalog>()
        var onValidate: (Binding) -> DeviceAuthResponse = { validation() }
        var onClose: (Binding) -> Unit = {}
        var onCatalog: () -> Unit = {}
        init { if (saved) store.commitConnection(store.beginConnection(), TwitchCatalogReplacement(
            TwitchCatalogCredentials("fixture-access", "fixture-refresh", 7200000),
            TwitchCatalogValidation("123", setOf(TWITCH_CATALOG_SCOPE), 7200000), 7200000))!! }
        inner class Binding(val route: TwitchCatalogRouteOwner, val attempt: ProviderStartupAttemptContext) : TwitchCatalogConnectionBinding {
            val ordinal = bindings.size + 1
            var validations = 0; var posts = 0; var transportCloses = 0; var closes = 0; var cleanup = true
            var closeThrows = false
            lateinit var prepared: TwitchCatalogSession
            private val closed = AtomicBoolean()
            override fun owner() = route.presentation
            override fun session(): TwitchCatalogSession = error("Prepared catalog must retain its startup session")
            override fun startupSession(): TwitchCatalogSession = TwitchCatalogSession(store, { transport(it) }, { wall }, { mono },
                { current && !closed.get() && attempt.canContinue() }).also { prepared = it }
            override fun transport(canRequest: () -> Boolean): TwitchCatalogTransport {
                check(canRequest())
                return object : TwitchCatalogTransport {
                    private val ended = AtomicBoolean()
                    override fun device(): DeviceAuthResponse { posts++; error("Startup must not issue device authorization") }
                    override fun poll(deviceCode: String): DeviceAuthResponse { posts++; error("Startup must not poll") }
                    override fun refresh(refreshToken: String): DeviceAuthResponse { posts++; error("Startup must not refresh") }
                    override fun validate(accessToken: String): DeviceAuthResponse { check(canRequest()); validations++; return onValidate(this@Binding) }
                    override fun close() { if (ended.compareAndSet(false, true)) {
                        transportCloses++; onClose(this@Binding); if (closeThrows) error("Synthetic cleanup failure")
                    } }
                }
            }
            override val cleanupConfirmed get() = cleanup
            override fun close() { if (closed.compareAndSet(false, true)) { closes++; if (::prepared.isInitialized) prepared.close() } }
        }
        inner class Catalog(val session: TwitchCatalogSession) : ProviderCatalog {
            override val instanceId = id; override val providerId = ProviderId("twitch")
            var closes = 0
            override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE)
            override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
                assertNotNull(session.validate().lease)
                return CatalogResult.Value(CatalogPage(emptyList()))
            }
            override fun lookup(input: String) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
            override fun refresh(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
            override fun resolve(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
            override fun close() { closes++ }
        }
        fun prepare() = prepareTwitchCatalogStartup(startup, create = { owner, attempt ->
            if (bindings.isNotEmpty()) assertEquals(1, bindings.last().closes)
            val route = TwitchCatalogRouteOwner(owner)
            val binding = Binding(route, attempt).also(bindings::add)
            TwitchStartupConnection(route, binding)
        }, catalog = { connection ->
            onCatalog()
            Catalog(checkNotNull(connection.session)).also(catalogs::add)
        })
    }
    @Test fun typedAvailabilityRetriesAndWinningCatalogRetainsRealValidatedSessionWithoutAnotherGet() {
        val f = Fixture(); val before = f.memory.bytes!!.copyOf(); val writes = f.memory.writes
        f.onValidate = { if (it.ordinal == 1) throw ProviderTransportException(ProviderTransportEvidence(ProviderTransportFailure.CONNECT)) else validation() }
        val ready = f.prepare() as ProviderStartupResult.Ready
        assertEquals(f.routeId, ready.owner.choice.connectionId)
        assertEquals(listOf(1, 1), f.bindings.map { it.validations }); assertEquals(listOf(1, 1), f.bindings.map { it.transportCloses })
        assertSame(f.bindings.last().prepared, f.catalogs.single().session)
        ready.value.browse(CatalogQuery()); ready.value.browse(CatalogQuery())
        assertEquals(listOf(1, 1), f.bindings.map { it.validations }); assertTrue(f.bindings.all { it.posts == 0 })
        assertEquals(writes, f.memory.writes); assertArrayEquals(before, f.memory.bytes)
        f.startup.close(); assertEquals(listOf(1, 1), f.bindings.map { it.closes }); assertEquals(1, f.catalogs.single().closes)
    }
    @Test fun missingExpiredAnd401HoldTheirCandidateWithoutAnyPostOrFallback() {
        for (kind in 0..2) {
            val f = Fixture(saved = kind != 0)
            if (kind == 1) f.wall += 7200000
            if (kind == 2) f.onValidate = { DeviceAuthResponse(401, emptyMap()) }
            val held = f.prepare() as ProviderStartupResult.HeldForMutation
            assertEquals(SourceRouteChoice.system, held.owner.choice); assertFalse(f.startup.admitted)
            assertEquals(1, f.bindings.size); assertEquals(if (kind == 2) 1 else 0, f.bindings.single().validations)
            assertEquals(0, f.bindings.single().posts); assertTrue(f.catalogs.isEmpty()); assertEquals(1, f.bindings.single().closes)
            f.startup.close()
        }
    }
    @Test fun tlsAuthenticationMalformedAndUnknownFailuresStop() {
        for (kind in 0..3) {
            val f = Fixture()
            f.onValidate = { when (kind) {
                0 -> throw ProviderTransportException(ProviderTransportEvidence(ProviderTransportFailure.TLS))
                1 -> DeviceAuthResponse(403, emptyMap())
                2 -> DeviceAuthResponse(200, emptyMap())
                else -> throw IllegalStateException("Synthetic unclassified failure")
            } }
            assertTrue(f.prepare() is ProviderStartupResult.Failed); assertEquals(1, f.bindings.size)
            assertEquals(0, f.bindings.single().posts); assertTrue(f.catalogs.isEmpty()); f.startup.close()
        }
    }
    @Test fun cleanupUncertaintyStopsBeforeAnotherCandidateOrCatalogPublication() {
        for (throws in listOf(false, true)) {
            val f = Fixture()
            f.onValidate = { binding ->
                if (throws) binding.closeThrows = true else binding.cleanup = false
                throw ProviderTransportException(ProviderTransportEvidence(ProviderTransportFailure.DNS))
            }
            val failed = f.prepare() as ProviderStartupResult.Failed
            assertEquals(ProviderStartupFailure.CLEANUP_UNCONFIRMED, failed.failure.reason)
            assertEquals(1, f.bindings.size); assertTrue(f.catalogs.isEmpty()); f.startup.close()
        }
    }
    @Test fun cancellationConfigurationLossAndLateCatalogCannotPublishAnAcceptedResult() {
        for (kind in 0..2) {
            val f = Fixture()
            f.onValidate = { if (kind == 0) f.startup.cancel() else if (kind == 1) f.current = false; validation() }
            if (kind == 2) f.onCatalog = { f.startup.cancel() }
            assertTrue(f.prepare() is ProviderStartupResult.Failed); assertEquals(1, f.bindings.size)
            assertEquals(1, f.bindings.single().closes); assertTrue(f.catalogs.all { it.closes == 1 }); f.startup.close()
        }
    }
}
