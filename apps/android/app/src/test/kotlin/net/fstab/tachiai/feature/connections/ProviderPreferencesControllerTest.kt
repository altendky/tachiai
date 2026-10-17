package net.fstab.tachiai.feature.connections

import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test

class ProviderPreferencesControllerTest {
    private val japan = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION,
        "12345678-1234-1234-1234-123456789abc", "Japan")
    private val other = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION,
        "12345678-1234-1234-1234-123456789abd", "Other")

    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runNext() { checkNotNull(tasks.pollFirst()).run() }
        fun drain() { while (tasks.isNotEmpty()) runNext() }
    }

    private class Fixture {
        val io = ManualDispatcher()
        val deliveries = ArrayDeque<() -> Unit>()
        var instances = defaultProviderInstances()
        val initial get() = instances.first()
        val writes = mutableListOf<Pair<String?, SourceRouteChoice>>()
        val poolWrites = mutableListOf<Pair<String?, List<SourceRouteChoice>>>()
        val committed = mutableListOf<ProviderInstance>()
        val started = mutableListOf<ProviderPreferenceState>()
        var failNext = false
        lateinit var controller: ProviderPreferencesController
        fun open(): ProviderPreferencesController {
            controller = ProviderPreferencesController(initial, instances, save = { name, routes ->
                routes.singleOrNull()?.let { writes += name to it }
                poolWrites += name to routes
                if (failNext) { failNext = false; error("private storage details") }
                instances.map { if (it.id == initial.id) it.copy(customName = name,
                    setup = it.setup.copy(route = routes.firstOrNull(), fallbackRoutes = routes.drop(1))) else it }.also { instances = it }
            }, dispatch = { deliveries.addLast(it) }, onChangeStarted = { started += controller.state.value },
                onCommitted = { committed += it }, io = io)
            return controller
        }
        fun deliver() { while (deliveries.isNotEmpty()) deliveries.removeFirst()() }
        fun drain() { io.drain(); deliver() }
    }

    @Test fun openingDoesNotWriteAndRapidChangesAllCommitInAcceptedOrder() {
        val fixture = Fixture(); val controller = fixture.open()
        assertTrue(fixture.writes.isEmpty())
        controller.nameChanged("Account")
        controller.routeChanged(japan)
        controller.nameChanged("Account two")
        controller.routeChanged(other)
        assertTrue(controller.state.value.saving)
        assertEquals(other, controller.state.value.selectedRoute)
        assertEquals(other, fixture.started.last().selectedRoute)
        assertEquals(SourceRouteChoice.system, controller.state.value.instance.setup.route)
        fixture.drain()
        assertEquals(listOf("Account" to SourceRouteChoice.system, "Account" to japan,
            "Account two" to japan, "Account two" to other), fixture.writes)
        assertEquals(1, fixture.committed.size)
        assertEquals("Account two", fixture.committed.single().name)
        assertEquals(other, controller.state.value.instance.setup.route)
        assertFalse(controller.state.value.saving)
        assertNull(controller.state.value.error)
    }

    @Test fun aFailedRouteRollsBackToDurableChoiceAndRetryKeepsTheRequestedRoute() {
        val fixture = Fixture(); val controller = fixture.open()
        fixture.failNext = true
        controller.routeChanged(japan)
        fixture.drain()
        assertEquals(SourceRouteChoice.system, controller.state.value.selectedRoute)
        assertEquals(SourceRouteChoice.system, controller.state.value.instance.setup.route)
        assertFalse(controller.state.value.saving)
        assertEquals("Changes could not be saved. Retry.", controller.state.value.error)
        assertEquals(fixture.initial, fixture.committed.single())
        controller.retry()
        assertEquals(japan, controller.state.value.selectedRoute)
        assertTrue(controller.state.value.saving)
        fixture.drain()
        assertEquals(listOf(null to japan, null to japan), fixture.writes)
        assertEquals(japan, controller.state.value.instance.setup.route)
        assertNull(controller.state.value.error)
    }

    @Test fun failureAfterAnEarlierCommitAcknowledgesTheActualDurableRouteOnlyOnce() {
        val fixture = Fixture(); val controller = fixture.open()
        controller.routeChanged(japan)
        controller.routeChanged(other)
        fixture.io.runNext()
        fixture.failNext = true
        fixture.drain()
        assertEquals(japan, controller.state.value.instance.setup.route)
        assertEquals(japan, controller.state.value.selectedRoute)
        assertEquals(japan, fixture.committed.single().setup.route)
        assertNotNull(controller.state.value.error)
    }

    @Test fun invalidNameDraftDoesNotBlockRouteSavingOrOverwriteAnAcceptedName() {
        val fixture = Fixture(); val controller = fixture.open()
        controller.nameChanged("Valid account")
        controller.nameChanged("Bad\u200bname")
        controller.routeChanged(japan)
        assertFalse(controller.state.value.validName)
        fixture.drain()
        assertEquals(listOf("Valid account" to SourceRouteChoice.system, "Valid account" to japan), fixture.writes)
        assertEquals("Bad\u200bname", controller.state.value.nameDraft)
        assertFalse(controller.state.value.validName)
        assertEquals("Valid account", controller.state.value.instance.name)
        assertEquals(japan, controller.state.value.selectedRoute)
    }

    @Test fun duplicateNamesDoNotWriteAndBlankNameResetsTheInstancesOwnDefault() {
        val fixture = Fixture()
        fixture.instances = fixture.instances + ProviderInstance("12345678-1234-1234-1234-123456789abe",
            PrototypeService.ABEMA, "ABEMA 2", "Other account")
        val controller = fixture.open()
        controller.nameChanged("OTHER ACCOUNT")
        assertFalse(controller.state.value.validName)
        assertTrue(fixture.writes.isEmpty())
        controller.nameChanged("Custom")
        fixture.drain()
        controller.nameChanged("")
        fixture.drain()
        assertEquals(fixture.initial.defaultName, controller.state.value.instance.name)
        assertNull(controller.state.value.instance.customName)
        assertTrue(controller.state.value.validName)
    }

    @Test fun closingSuppressesCallbacksButAllAcceptedWritesDrainAndCanBeAwaited() = runBlocking {
        val fixture = Fixture(); val controller = fixture.open()
        controller.routeChanged(japan)
        controller.routeChanged(other)
        controller.close()
        controller.nameChanged("Ignored after close")
        controller.routeChanged(SourceRouteChoice.system)
        val byProvider = launch(start = CoroutineStart.UNDISPATCHED) { ProviderPreferenceWrites.await(fixture.initial.id) }
        val all = launch(start = CoroutineStart.UNDISPATCHED) { ProviderPreferenceWrites.awaitAll() }
        assertFalse(byProvider.isCompleted)
        assertFalse(all.isCompleted)
        fixture.drain()
        byProvider.join(); all.join()
        assertEquals(listOf(null to japan, null to other), fixture.writes)
        assertEquals(other, fixture.initial.setup.route)
        assertTrue(fixture.committed.isEmpty())
    }

    @Test fun delayedAcknowledgmentCannotRebindAnOlderRouteAfterAnotherAcceptedChange() {
        val fixture = Fixture(); val controller = fixture.open()
        controller.routeChanged(japan)
        fixture.io.drain()
        controller.routeChanged(other)
        fixture.deliver()
        assertTrue(fixture.committed.isEmpty())
        fixture.drain()
        assertEquals(other, fixture.committed.single().setup.route)
    }

    @Test fun renamingAnAmbiguousLegacyOwnerNeverSelectsAnImplicitRoute() {
        val fixture = Fixture()
        fixture.instances = fixture.instances.map { it.copy(setup = ProviderSetup(null)) }
        val controller = fixture.open()
        controller.nameChanged("Account")
        assertTrue(fixture.writes.isEmpty())
        assertNull(controller.state.value.selectedRoute)
        fixture.drain()
        assertEquals(listOf("Account" to emptyList<SourceRouteChoice>()), fixture.poolWrites)
        assertNull(controller.state.value.instance.setup.route)
        controller.routeChanged(japan)
        fixture.drain()
        assertEquals(listOf("Account" to japan), fixture.writes)
    }

    @Test fun pooledRouteChangesAndRenamingCommitWholeSnapshotsInOrder() {
        val fixture = Fixture(); val controller = fixture.open()
        val pool = listOf(japan, other, SourceRouteChoice.system)
        controller.routesChanged(pool)
        controller.nameChanged("Ordered account")
        controller.routesChanged(pool.drop(1))
        assertEquals(pool.drop(1), controller.state.value.selectedRoutes)
        fixture.drain()
        assertEquals(listOf(null to pool, "Ordered account" to pool, "Ordered account" to pool.drop(1)), fixture.poolWrites)
        assertEquals(other, controller.state.value.selectedRoute)
        assertEquals(pool.drop(1), fixture.committed.single().setup.routes)
        controller.routesChanged(emptyList())
        fixture.drain()
        assertNull(controller.state.value.selectedRoute)
        assertEquals(emptyList<SourceRouteChoice>(), fixture.poolWrites.last().second)
        assertEquals("Ordered account", controller.state.value.instance.name)
    }

    @Test fun failedPoolChangeRollsBackWholeOrderAndRetryRetainsTheCompleteRequestedPool() {
        val fixture = Fixture(); val controller = fixture.open()
        val pool = listOf(japan, other)
        controller.routesChanged(pool)
        fixture.drain()
        fixture.failNext = true
        controller.routesChanged(pool.reversed())
        fixture.drain()
        assertEquals(pool, controller.state.value.selectedRoutes)
        assertNotNull(controller.state.value.error)
        controller.retry()
        assertEquals(pool.reversed(), controller.state.value.selectedRoutes)
        fixture.drain()
        assertEquals(pool.reversed(), controller.state.value.instance.setup.routes)
        assertNull(controller.state.value.error)
    }

    @Test fun acceptedPoolsAreCopiedAndInvalidAlternativesNeverStartAWritingBatch() {
        val fixture = Fixture(); val controller = fixture.open()
        val mutable = mutableListOf(japan, other)
        controller.routesChanged(mutable)
        mutable.clear()
        fixture.drain()
        assertEquals(listOf(japan, other), controller.state.value.selectedRoutes)
        val started = fixture.started.size
        assertThrows(IllegalArgumentException::class.java) { controller.routesChanged(listOf(japan, japan.copy(connectionName = "Renamed"))) }
        assertThrows(IllegalArgumentException::class.java) { controller.routesChanged(listOf(SourceRouteChoice.inherit)) }
        assertEquals(started, fixture.started.size)
        assertEquals(1, fixture.poolWrites.size)
    }

    @Test fun acceptedWholePoolWritesSurviveCloseAndSuppressOldOwnerAcknowledgments() {
        val fixture = Fixture(); val controller = fixture.open()
        controller.routesChanged(listOf(japan, other))
        controller.nameChanged("Account")
        controller.close()
        fixture.drain()
        assertEquals(listOf(japan, other), fixture.initial.setup.routes)
        assertEquals("Account", fixture.initial.name)
        assertTrue(fixture.committed.isEmpty())
    }

    @Test fun renamingAnExistingPoolRetainsItsCompleteOrderAndLeavesOtherOwnersUnchanged() {
        val fixture = Fixture()
        fixture.instances = fixture.instances.mapIndexed { index, owner -> if (index == 0)
            owner.copy(setup = ProviderSetup(japan, fallbackRoutes = listOf(other, SourceRouteChoice.system))) else owner }
        val untouched = fixture.instances.drop(1)
        val controller = fixture.open()
        controller.nameChanged("Evening routes")
        fixture.drain()
        assertEquals(listOf("Evening routes" to listOf(japan, other, SourceRouteChoice.system)), fixture.poolWrites)
        assertEquals(untouched, fixture.instances.drop(1))
        controller.routesChanged(listOf(japan, other, SourceRouteChoice.system))
        fixture.drain()
        assertEquals(1, fixture.poolWrites.size)
    }
}
