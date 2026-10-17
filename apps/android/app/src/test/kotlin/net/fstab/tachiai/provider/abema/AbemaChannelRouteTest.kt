package net.fstab.tachiai.provider.abema

import java.io.IOException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test

class AbemaChannelRouteTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val routeId = "12345678-1234-1234-1234-123456789abd"
    private fun instance(route: SourceRouteChoice? = SourceRouteChoice.system) =
        ProviderInstance(id, PrototypeService.ABEMA, "ABEMA 2", setup = ProviderSetup(route))
    private fun profile(host: String = "proxy.example.test") =
        parseConnectionProfile("http://user:fixture-secret@$host:3128".toByteArray())
    private fun owner(instance: ProviderInstance, profile: ConnectionProfile? = null) =
        readAbemaCatalogRouteOwner(id, { listOf(instance) }, { checkNotNull(profile) })
    private class Fake(private val request: () -> AbemaChannelResponse = { AbemaChannelResponse(200) }) : AbemaChannelTransport {
        var closed = false
        override fun fetch() = request()
        override fun close() { closed = true }
    }

    @Test fun explicitInstanceAndRouteAreRequiredAndConfigurationChangesInvalidateOwnership() {
        assertNull(owner(instance()).profile)
        listOf(instance(null), instance().copy(service = PrototypeService.TWITCH)).forEach {
            assertThrows(IllegalStateException::class.java) { owner(it) }
        }
        assertThrows(IllegalStateException::class.java) {
            readAbemaCatalogRouteOwner(id, { emptyList() }, { error("Deleted owner") })
        }
        val imported = instance(SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, routeId, "Route"))
        assertThrows(IllegalStateException::class.java) { owner(imported) }
        val original = owner(imported, profile())
        assertTrue(original.sameOwnership(owner(imported.copy(customName = "Renamed"), profile())))
        assertFalse(original.sameOwnership(owner(imported, profile("changed.example.test"))))
        assertFalse(original.sameOwnership(owner(instance())))
        assertFalse(original.toString().contains("fixture-secret"))
        assertFalse(original.toString().contains("proxy.example.test"))
    }

    @Test fun eachSnapshotClosesItsSelectedBackendAndRejectsEveryOtherEndpoint() {
        val selected = profile()
        var closes = 0
        val transports = mutableListOf<Fake>()
        val owned = OwnedAbemaChannelTransport(selected, { true }, createRoute = { actual, preparation ->
            assertSame(selected, actual)
            RouteSession.createAbemaCatalog(actual, preparation) { _, security ->
                assertEquals("api.abema.io", security.allowedHosts)
                object : RouteBackend { override val proxyPort = 12345; override fun close() { closes++ } }
            }
        }, createTransport = { open, gate -> Fake {
            assertTrue(gate())
            listOf("https://api.abema.io/v1/channels?token=fixture", "https://api.abema.io/v1/channels#fragment",
                "https://api.abema.io/v1/users", "https://abema.tv/", "http://api.abema.io/v1/channels").forEach {
                assertThrows(IllegalStateException::class.java) { open(URL(it)) }
            }
            open(URL("https://api.abema.io/v1/channels")).disconnect() // Creation only; no network.
            AbemaChannelResponse(200)
        }.also(transports::add) })
        owned.fetch(); owned.fetch()
        assertEquals(2, closes)
        assertTrue(transports.all { it.closed })
        owned.close()
        assertThrows(IllegalStateException::class.java) { owned.fetch() }
    }

    @Test fun failedPreparationNeverCreatesProviderTransportOrSystemFallback() {
        var calls = 0
        val owned = OwnedAbemaChannelTransport(profile(), { true },
            createRoute = { _, _ -> throw IOException("Unavailable imported route") },
            createTransport = { _, _ -> calls++; Fake() })
        assertThrows(IOException::class.java) { owned.fetch() }
        assertEquals(0, calls)
        owned.close()
    }

    @Test fun closeSignalsPreparationWithoutWaitingAndRejectsItsLateCompletion() {
        val entered = CountDownLatch(1); val cancelled = CountDownLatch(1); val release = CountDownLatch(1)
        var calls = 0
        var failure: Throwable? = null
        val owned = OwnedAbemaChannelTransport(null, { true }, createRoute = { _, preparation ->
            preparation.onCancel { cancelled.countDown() }.use {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
            RouteSession.createAbemaCatalog(null) // Deliberately late fake completion.
        }, createTransport = { _, _ -> calls++; Fake() })
        val worker = thread { try { owned.fetch() } catch (error: Throwable) { failure = error } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        owned.close()
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive)
        assertTrue(failure is IOException)
        assertEquals(0, calls)
    }

    @Test fun cancellationCoalescesAndCleanupFailureBlocksPublicationAndFutureRequests() {
        val entered = CountDownLatch(1); val cancelled = CountDownLatch(1); val release = CountDownLatch(1)
        var cancels = 0
        var failure: Throwable? = null
        val raw = object : AbemaChannelTransport {
            override fun fetch(): AbemaChannelResponse {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return AbemaChannelResponse(200)
            }
            override fun cancelActiveRequest() { cancels++; cancelled.countDown() }
            override fun close() = Unit
        }
        val owned = OwnedAbemaChannelTransport(null, { true }, createTransport = { _, _ -> raw })
        val worker = thread { try { owned.fetch() } catch (error: Throwable) { failure = error } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repeat(100) { owned.cancelActiveRequest() }
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals(1, cancels)
        assertTrue(failure is IOException)
        owned.close()

        var creates = 0; var failures = 0
        val uncertain = OwnedAbemaChannelTransport(profile(), { true }, createRoute = { selected, preparation ->
            creates++
            RouteSession.createAbemaCatalog(selected, preparation) { _, _ -> object : RouteBackend {
                override val proxyPort = 12345
                override fun close() { error("Fixture cleanup failure") }
            } }
        }, createTransport = { _, _ -> Fake() }, onCleanupFailure = { failures++ })
        assertThrows(IOException::class.java) { uncertain.fetch() }
        assertThrows(IllegalStateException::class.java) { uncertain.fetch() }
        assertEquals(1, creates)
        assertEquals(1, failures)
        uncertain.close()
    }
}
