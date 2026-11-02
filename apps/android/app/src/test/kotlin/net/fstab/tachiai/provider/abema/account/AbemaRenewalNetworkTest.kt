package net.fstab.tachiai.provider.abema.account

import android.content.Context
import android.content.ContextWrapper
import android.net.Network
import java.io.File
import java.util.UUID
import javax.net.SocketFactory
import net.fstab.tachiai.platform.network.AndroidNetworkEgress
import net.fstab.tachiai.platform.network.RouteNetworkEgress
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.ProviderInstance
import net.fstab.tachiai.provider.abema.AbemaCatalogRouteOwner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AbemaRenewalNetworkTest {
    @get:Rule val temporary = TemporaryFolder()

    // The mockable SDK constructor provides a typed carrier only. It establishes
    // neither Android Network behavior nor a real Parcelable round trip.
    private fun network(): Network = Network::class.java.getDeclaredConstructor()
        .apply { isAccessible = true }.newInstance()

    private class FixtureContext(private val directory: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = directory
        override fun getPackageName() = "net.fstab.tachiai"
    }

    private class Egress : RouteNetworkEgress {
        override val socketFactory get() = SocketFactory.getDefault()
        override fun checkActive() = Unit
        override fun isActive() = true
        override fun resolveAddresses(host: String, timeoutMillis: Long) = error("No fixture DNS")
        override fun resolve(host: String?, timeoutMillis: Long) = error("No fixture DNS")
        override fun bindSocket(fd: Int) = error("No fixture socket binding")
        override fun cancel() = Unit
        override fun isCleanupConfirmed() = true
        override fun reportCleanupUnconfirmed() = Unit
        override fun closeOwnedSockets() = Unit
    }

    @Test fun eachResourceGetsAnIndependentScopeWithTheExactNetworkAndOriginalDeadline() {
        val selected = network()
        val captured = AbemaRenewalNetwork(selected, 10_000)
        val factory = captured.egressFactory()
        val first = factory() as AndroidNetworkEgress
        val second = factory() as AndroidNetworkEgress
        try {
            assertNotSame(first, second); assertNotSame(first.scope, second.scope)
            assertEquals(10_000_000_000L, first.scope.deadlineNanos)
            assertEquals(first.scope.deadlineNanos, second.scope.deadlineNanos)
            val field = AndroidNetworkEgress::class.java.getDeclaredField("network").apply { isAccessible = true }
            assertSame(selected, field.get(first)); assertSame(selected, field.get(second))
            first.cancel(); assertFalse(first.isActive()); assertTrue(second.isActive())
        } finally { first.cancel(); second.cancel(); first.closeOwnedSockets(); second.closeOwnedSockets() }
    }

    @Test fun invalidAbsoluteDeadlineFailsBeforeAnyEgressIsAllocated() {
        val selected = network()
        assertThrows(IllegalArgumentException::class.java) { AbemaRenewalNetwork(selected, 0) }
        assertThrows(IllegalArgumentException::class.java) { AbemaRenewalNetwork(selected, -1) }
        assertThrows(ArithmeticException::class.java) { AbemaRenewalNetwork(selected, Long.MAX_VALUE) }
    }

    @Test fun anotherNetworkObserverCannotReplaceTheFirstWorkOrExtendItsDeadline() {
        var now = 0L
        val coordinator = AbemaRenewalReadinessCoordinator<AbemaRenewalNetwork>({ now }, { "a".repeat(32) })
        val original = AbemaRenewalNetwork(network(), 10_000)
        val observer = AbemaRenewalNetwork(network(), 20_000)
        val created = coordinator.check("instance", "same-account", original, original.deadlineElapsedMs)
        val waiting = coordinator.check("instance", "same-account", observer, observer.deadlineElapsedMs)
        assertEquals(AbemaRenewalReadinessCoordinator.Status.WAIT, waiting.status)
        assertNull(waiting.ticket)
        val ticket = checkNotNull(created.ticket)
        assertSame(original, coordinator.claim(ticket))
        now = 9_999; assertTrue(coordinator.current(ticket))
        now = 10_000; assertFalse(coordinator.current(ticket))
    }

    @Test fun boundBindingPropagatesItsFactoryAndRefusesUnboundBootstrapWithoutStartingWork() {
        val id = UUID.randomUUID().toString()
        val context = FixtureContext(temporary.newFolder())
        val owner = AbemaCatalogRouteOwner(ProviderInstance(id, PrototypeService.ABEMA, "Fixture"), null)
        var allocated = 0
        val factory: () -> RouteNetworkEgress = { allocated++; Egress() }
        val binding = androidAbemaAccountConnectionBinding(context, id, renewalEgressFactory = factory)
        val refused = assertThrows(AbemaAccountException::class.java) {
            binding.bootstrap(owner, { error("No request may start") }, { error("No browser may start") })
        }
        assertEquals(AbemaAccountFailure.REFUSED, refused.category)
        val transport = binding.transport(owner) { error("No HTTP request may start") } as OwnedAbemaAccountTransport
        val field = OwnedAbemaAccountTransport::class.java.getDeclaredField("networkEgressFactory").apply { isAccessible = true }
        assertSame(factory, field.get(transport)); assertEquals(0, allocated)
        transport.close(); assertTrue(transport.cleanupConfirmed)
        val ordinary = androidAbemaAccountConnectionBinding(context, id)
            .transport(owner) { error("No HTTP request may start") } as OwnedAbemaAccountTransport
        assertNull(field.get(ordinary)); ordinary.close(); assertTrue(ordinary.cleanupConfirmed)
    }
}
