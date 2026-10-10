package net.fstab.tachiai.provider.twitch.catalog

import java.io.IOException
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.twitch.*
import org.junit.Assert.*
import org.junit.Test

class TwitchCatalogRouteOwnerTest {
    private val instanceId = "12345678-1234-1234-1234-123456789abc"
    private val routeId = "12345678-1234-1234-1234-123456789abd"
    private fun instance(route: SourceRouteChoice? = SourceRouteChoice.system) =
        ProviderInstance(instanceId, PrototypeService.TWITCH, "Twitch 2", setup = ProviderSetup(route))
    private fun profile(host: String = "proxy.example.test") = parseConnectionProfile("http://user:fixture-secret@$host:3128".toByteArray())
    private fun owner(selected: ProviderInstance, route: ConnectionProfile? = null) =
        readTwitchCatalogRouteOwner(instanceId, { listOf(selected) }, { checkNotNull(route) })

    @Test fun onlyExplicitSavedSystemChoicePermitsNoProfile() {
        val selected = owner(instance())
        assertNull(selected.profile)
        assertEquals("System network", selected.presentation.routeTitle)
        assertThrows(IllegalArgumentException::class.java) { instance(SourceRouteChoice.inherit) }
        listOf(instance(null), instance().copy(service = PrototypeService.ABEMA)).forEach {
            assertThrows(IllegalStateException::class.java) { owner(it) }
        }
        assertThrows(IllegalStateException::class.java) {
            readTwitchCatalogRouteOwner(instanceId, { emptyList() }, { error("Must not read stale profile") })
        }
        val imported = instance(SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, routeId, "Private route"))
        assertThrows(IllegalStateException::class.java) { owner(imported) }
        assertSame(imported, owner(imported, profile()).instance)
    }

    @Test fun ownerComparisonDetectsSavedRouteAndSecretProfileChangesWithoutExposingThem() {
        val imported = instance(SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, routeId, "Route"))
        val original = owner(imported, profile())
        assertTrue(original.presentation.sameOwnership(owner(imported, profile()).presentation))
        assertTrue(original.presentation.sameOwnership(owner(imported.copy(customName = "Renamed"), profile()).presentation))
        assertFalse(original.presentation.sameOwnership(owner(imported, profile("changed.example.test")).presentation))
        assertFalse(original.presentation.sameOwnership(owner(instance()).presentation))
        assertFalse(original.toString().contains("fixture-secret"))
        assertFalse(original.presentation.toString().contains("proxy.example.test"))
    }

    private class FakeTransport(private val request: () -> DeviceAuthResponse = { DeviceAuthResponse(200, emptyMap()) }) : TwitchCatalogTransport {
        var closed = false
        override fun device() = request()
        override fun poll(deviceCode: String) = request()
        override fun validate(accessToken: String) = request()
        override fun refresh(refreshToken: String) = request()
        override fun close() { closed = true }
    }

    @Test fun eachExchangeClosesItsImportedBackendAndAllowsOnlyExactOAuthEndpoints() {
        var closes = 0
        val routes = mutableListOf<RouteSession>()
        val transports = mutableListOf<FakeTransport>()
        val selected = profile()
        val owned = OwnedTwitchCatalogTransport(selected, { true },
            createRoute = { actual, preparation ->
                assertSame(selected, actual)
                RouteSession.create(actual, preparation) { _, _ -> object : RouteBackend {
                    override val proxyPort = 12345
                    override fun close() { closes++ }
                } }.also(routes::add)
            }, createTransport = { open, gate -> FakeTransport {
                assertTrue(gate())
                listOf("https://api.twitch.tv/helix/users", "https://id.twitch.tv/oauth2/token?secret=1",
                    "https://id.twitch.tv/oauth2/token#fragment", "http://id.twitch.tv/oauth2/device").forEach {
                    assertThrows(IllegalStateException::class.java) { open(URL(it)) }
                }
                open(URL("https://id.twitch.tv/oauth2/validate")).disconnect() // Creation only; no network.
                DeviceAuthResponse(200, emptyMap())
            }.also(transports::add) })
        owned.device(); owned.validate("fixture-access")
        assertEquals(2, closes)
        assertTrue(transports.all { it.closed })
        routes.forEach { assertThrows(IOException::class.java) { it.open(URL("https://id.twitch.tv/oauth2/token")) } }
        owned.close()
        assertThrows(IllegalStateException::class.java) { owned.device() }
    }

    @Test fun failedImportedPreparationNeverCreatesSystemOrProviderTransport() {
        var providerCalls = 0
        val owned = OwnedTwitchCatalogTransport(profile(), { true },
            createRoute = { _, _ -> throw IOException("route unavailable") },
            createTransport = { _, _ -> providerCalls++; FakeTransport() })
        assertThrows(IOException::class.java) { owned.device() }
        assertEquals(0, providerCalls)
        owned.close()
    }

    @Test fun localRetentionPassingDuringRoutePreparationBlocksProviderHttpAndRequiresReconnect() {
        var wall = 1_000_000L; var mono = 10_000L
        val memory = object : PrivateSecretStore {
            var bytes: ByteArray? = null
            override fun read() = bytes?.copyOf()
            override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
        }
        val store = TwitchCatalogGrantStore(memory, UUID.randomUUID().toString(), wallMs = { wall })
        val credentials = TwitchCatalogCredentials("fixture-access", "fixture-refresh", 1000L)
        val validation = TwitchCatalogValidation("123", setOf(TWITCH_CATALOG_SCOPE), null)
        assertNotNull(store.commitConnection(store.beginConnection(), TwitchCatalogReplacement(
            credentials, validation, 1000L, localRetentionUntilMs = wall + 1000L, savedAtMs = wall)))
        var routes = 0; var closes = 0; var providerTransports = 0
        val session = TwitchCatalogSession(store, transportFactory = { gate ->
            OwnedTwitchCatalogTransport(profile(), gate, createRoute = { selected, preparation ->
                routes++
                // An imported backend can take long enough to exhaust retention.
                wall += 1000L; mono += 1000L
                RouteSession.create(selected, preparation) { _, _ -> object : RouteBackend {
                    override val proxyPort = 12345
                    override fun close() { closes++ }
                } }
            }, createTransport = { _, _ -> providerTransports++; FakeTransport() })
        }, wallMs = { wall }, monotonicMs = { mono })
        session.use {
            val result = it.validate(force = true)
            assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, result.summary.state)
            assertEquals(TwitchCatalogAuthFailure.EXPIRED, result.summary.failure)
            assertNull(result.lease)
            assertEquals(1, routes); assertEquals(1, closes); assertEquals(0, providerTransports)
            assertNull(it.validate(force = true).lease)
            assertEquals(1, routes); assertEquals(0, providerTransports)
        }
    }

    @Test fun pauseCancelsPreparationAndRejectsLateRouteWithoutDiscardingTransport() {
        val preparing = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0
        var first = true
        var failure: Throwable? = null
        val owned = OwnedTwitchCatalogTransport(null, { true },
            createRoute = { _, preparation ->
                if (first) {
                    first = false
                    preparation.onCancel { cancelled.countDown() }.use {
                        preparing.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    }
                }
                RouteSession.create(null) // Deliberately late fake completion.
            }, createTransport = { _, _ -> calls++; FakeTransport() })
        val worker = thread { try { owned.device() } catch (error: Throwable) { failure = error } }
        assertTrue(preparing.await(5, TimeUnit.SECONDS))
        owned.cancelActiveRequest()
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive)
        assertTrue(failure is DeviceRequestPaused)
        assertEquals(0, calls)
        owned.device()
        assertEquals(1, calls)
        owned.close()
    }

    @Test fun repeatedPauseCoalescesActiveCancellationAndRejectsStaleResponse() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        var cancelCalls = 0
        var failure: Throwable? = null
        val raw = object : TwitchCatalogTransport {
            override fun device(): DeviceAuthResponse {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                return DeviceAuthResponse(200, emptyMap())
            }
            override fun poll(deviceCode: String) = device()
            override fun validate(accessToken: String) = device()
            override fun refresh(refreshToken: String) = device()
            override fun cancelActiveRequest() { cancelCalls++; cancelled.countDown() }
            override fun close() { }
        }
        val owned = OwnedTwitchCatalogTransport(null, { true }, createTransport = { _, _ -> raw })
        val worker = thread { try { owned.device() } catch (error: Throwable) { failure = error } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repeat(100) { owned.cancelActiveRequest() }
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals(1, cancelCalls)
        assertTrue(failure is DeviceRequestPaused)
        owned.close()
    }

    @Test fun uncertainBackendCleanupBlocksEverySubsequentRequest() {
        var creates = 0
        var failures = 0
        val owned = OwnedTwitchCatalogTransport(profile(), { true },
            createRoute = { selected, preparation -> creates++
                RouteSession.create(selected, preparation) { _, _ -> object : RouteBackend {
                    override val proxyPort = 12345
                    override fun close() { error("fixture cleanup failure") }
                } }
            }, createTransport = { _, _ -> FakeTransport() }, onCleanupFailure = { failures++ })
        val error = assertThrows(IOException::class.java) { owned.device() }
        assertEquals("Catalog route cleanup failed", error.message)
        assertThrows(IllegalStateException::class.java) { owned.device() }
        assertEquals(1, creates)
        assertEquals(1, failures)
        owned.close()
    }
}
