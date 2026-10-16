package net.fstab.tachiai.provider.twitch.catalog

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.provider.twitch.SMART_TV_TWITCH_CLIENT_ID
import org.junit.Assert.*
import org.junit.Test

class TwitchCatalogMaintenanceRegistryTest {
    private fun scope(storage: String = "fixture-protected-storage", id: String = "instance",
        route: String = "SYSTEM", name: String = "Twitch", client: String = SMART_TV_TWITCH_CLIENT_ID,
        permission: String = TWITCH_CATALOG_SCOPE) = TwitchCatalogMaintenanceScope(storage,
        TwitchCatalogConnectionOwner(id, name, "Route title", route), client, permission)

    private fun coordinator(onIdle: () -> Unit) = TwitchCatalogMaintenanceCoordinator(
        TwitchCatalogGrantStore(object : PrivateSecretStore {
            override fun read(): ByteArray? = null
            override fun write(plaintext: ByteArray) = error("Registry checks cannot write grants")
        }, UUID.randomUUID().toString()),
        transportFactory = { error("Registry checks cannot open transports") },
        canCommit = { error("Registry checks cannot read the owner") }, onIdle = onIdle)

    @Test fun exactStorageClientScopeInstanceAndRouteDefineSharingWithoutPrintingThem() {
        val original = scope(route = "fixture-secret-route")
        val renamed = scope(route = "fixture-secret-route", name = "Renamed")
        assertEquals(original, renamed)
        assertEquals(original.hashCode(), renamed.hashCode())
        listOf(scope(storage = "other-storage", route = "fixture-secret-route"),
            scope(id = "other-instance", route = "fixture-secret-route"),
            scope(client = "other-client", route = "fixture-secret-route"),
            scope(permission = "other-scope", route = "fixture-secret-route"), scope()).forEach {
            assertNotEquals(original, it)
        }
        assertEquals("TwitchCatalogMaintenanceScope(redacted)", original.toString())
    }

    @Test fun bindingReferencesPreventIdleRemovalUntilTheFinalReferenceIsReleased() {
        val registry = TwitchCatalogMaintenanceRegistry()
        var creates = 0
        lateinit var idle: () -> Unit
        val first = registry.acquire(scope()) { callback -> creates++; idle = callback; coordinator(callback) }
        val second = registry.acquire(scope()) { error("Must share the captured owner") }
        first.close(); first.close(); idle()
        val third = registry.acquire(scope()) { error("The second reference is still alive") }
        assertSame(second.coordinator, third.coordinator)
        second.close(); third.close()
        val replacement = registry.acquire(scope()) { callback -> creates++; coordinator(callback) }
        assertNotSame(first.coordinator, replacement.coordinator)
        assertEquals(2, creates)
        replacement.close()
    }

    @Test fun aForegroundAttachmentRetainsTheCoordinatorAfterItsBindingReferenceCloses() {
        val registry = TwitchCatalogMaintenanceRegistry()
        val first = registry.acquire(scope(), ::coordinator)
        val attached = first.coordinator.attach(live = { true }, revisionCurrent = { true })
        first.close()
        val second = registry.acquire(scope()) { error("The attached consumer still owns this coordinator") }
        assertSame(first.coordinator, second.coordinator)
        second.close(); attached.close()
        val replacement = registry.acquire(scope(), ::coordinator)
        assertNotSame(first.coordinator, replacement.coordinator)
        replacement.close()
    }

    @Test fun observedScopeRevocationBlocksEveryOldHandleButAReopenedBindingGetsAFreshOwner() {
        val registry = TwitchCatalogMaintenanceRegistry()
        val first = registry.acquire(scope(), ::coordinator)
        val sibling = registry.acquire(scope()) { error("The original scope must be shared") }
        val attached = first.coordinator.attach(live = { first.canContinue() }, revisionCurrent = { first.canContinue() })
        first.revoke()
        assertFalse(first.canContinue()); assertFalse(sibling.canContinue())
        val fresh = registry.acquire(scope(), ::coordinator)
        assertTrue(fresh.canContinue()); assertNotSame(first.coordinator, fresh.coordinator)
        // Retiring the original binding/attachment must not evict the reopened
        // scope or release its independent ownership reservation.
        first.close(); sibling.close(); attached.close()
        val later = registry.acquire(scope()) { error("The new scope is still referenced") }
        assertSame(fresh.coordinator, later.coordinator); assertTrue(later.canContinue())
        fresh.close(); later.close()
    }

    @Test fun constructingAnotherOwnerCanProceedWhileAFactoryIsBlocked() {
        val registry = TwitchCatalogMaintenanceRegistry()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var failure: Throwable? = null
        val worker = thread {
            try {
                registry.acquire(scope()) { callback ->
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); coordinator(callback)
                }.close()
            } catch (error: Throwable) { failure = error }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            registry.acquire(scope(storage = "independent-storage"), ::coordinator).close()
        } finally { release.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive)
        failure?.let { throw it }
    }

    @Test fun simultaneousSameScopeConstructionAdmitsOnlyOneCoordinator() {
        val registry = TwitchCatalogMaintenanceRegistry()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val handles = ConcurrentLinkedQueue<TwitchCatalogMaintenanceRegistry.Handle>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val workers = List(2) { thread {
            try {
                handles.add(registry.acquire(scope()) { callback ->
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); coordinator(callback)
                })
            } catch (error: Throwable) { failures.add(error) }
        } }
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
        finally { release.countDown(); workers.forEach { it.join(5000) } }
        assertTrue(workers.none { it.isAlive })
        failures.firstOrNull()?.let { throw it }
        assertEquals(2, handles.size)
        val admitted = handles.toList()
        assertSame(admitted[0].coordinator, admitted[1].coordinator)
        val third = registry.acquire(scope()) { error("Both bindings must retain the one admitted coordinator") }
        assertSame(admitted[0].coordinator, third.coordinator)
        admitted.forEach { it.close() }; third.close()
    }
}
