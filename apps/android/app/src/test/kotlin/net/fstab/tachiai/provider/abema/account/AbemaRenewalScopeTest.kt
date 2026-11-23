package net.fstab.tachiai.provider.abema.account

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.abema.AbemaCatalogRouteOwner
import net.fstab.tachiai.provider.abema.captureAbemaCatalogRouteOwner
import org.junit.Assert.*
import org.junit.Test

// #635 step 2: callers that selected different candidates of one route pool
// share one renewal scope; only a changed grant snapshot or pool supersedes.
class AbemaRenewalScopeTest {
    private val instanceId = "12345678-1234-1234-1234-123456789abc"
    private val savedId = "12345678-1234-1234-1234-123456789abd"
    private val saved = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, savedId, "Saved")
    private val configured = ProviderInstance(instanceId, PrototypeService.ABEMA, "ABEMA",
        setup = ProviderSetup(saved, fallbackRoutes = listOf(SourceRouteChoice.system)))
    private fun profile(secret: String = "fixture-secret") =
        parseConnectionProfile("http://user:$secret@proxy.example.test:3128".toByteArray())
    private val profiles = mapOf(savedId to profile())
    private fun capture(proof: String? = null, owner: ProviderInstance = configured,
        values: Map<String, ConnectionProfile> = profiles) =
        captureAbemaCatalogRouteOwner(instanceId, { listOf(owner) }, { ids -> values.filterKeys(ids::contains) }, proof)
    private val first = capture()
    private val effective = capture(captureProviderRoutePool(instanceId, PrototypeService.ABEMA, { listOf(configured) },
        { ids -> profiles.filterKeys(ids::contains) }).candidates.last().proof)
    private fun snapshot(revision: Long = 1, generation: Long = 1): AbemaAccountSnapshot {
        val claims = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"fixture-account\",\"exp\":10}".toByteArray())
        return AbemaAccountSnapshot(AbemaStoredAccount(AbemaAccountGrant("fixture-account", "e30.$claims.c2ln", 10_000), 1_000, generation), revision)
    }
    private val expired = snapshot()

    private class Work(val owner: AbemaCatalogRouteOwner, val expected: AbemaAccountSnapshot)
    private val WAIT = AbemaRenewalReadinessCoordinator.Status.WAIT
    private val LAUNCH = AbemaRenewalReadinessCoordinator.Status.LAUNCH

    @Test fun candidatesOfOneRoutePoolShareOneScopeAndAdmitEachOthersProof() {
        assertNotEquals(first.proof(), effective.proof())
        assertEquals(first.routePoolProof(), effective.routePoolProof())
        assertEquals(AbemaRenewalScope.of(first, expired), AbemaRenewalScope.of(effective, snapshot()))
        assertEquals(AbemaRenewalScope.of(first, expired).hashCode(), AbemaRenewalScope.of(effective, expired).hashCode())
        assertTrue(first.inRoutePool(effective.proof())); assertTrue(effective.inRoutePool(first.proof()))
        // A changed grant snapshot is a different scope.
        assertNotEquals(AbemaRenewalScope.of(first, expired), AbemaRenewalScope.of(effective, snapshot(revision = 2)))
        assertNotEquals(AbemaRenewalScope.of(first, expired), AbemaRenewalScope.of(effective, snapshot(generation = 2)))
        assertEquals("AbemaRenewalScope(redacted)", AbemaRenewalScope.of(first, expired).toString())
    }

    @Test fun staleProofMatchingNoCurrentCandidateIsRefusedAndAChangedPoolIsADifferentScope() {
        val rotated = profiles + (savedId to profile("rotated"))
        val current = capture(values = rotated)
        assertNotEquals(first.routePoolProof(), current.routePoolProof())
        assertNotEquals(AbemaRenewalScope.of(first, expired), AbemaRenewalScope.of(current, expired))
        for (stale in listOf(first.proof(), effective.proof())) {
            assertFalse(current.inRoutePool(stale))
            // The readiness binding's owner capture admits only a current candidate's proof.
            assertEquals(ProviderRoutePoolFailure.OWNER_MISMATCH,
                assertThrows(ProviderRoutePoolException::class.java) { capture(stale, values = rotated) }.reason)
        }
        assertFalse(first.inRoutePool("f".repeat(64)))
        // An owner without a captured pool keeps its own proof as its scope.
        val fixture = AbemaCatalogRouteOwner(first.instance, first.profile, first.proof())
        assertEquals(first.proof(), fixture.routePoolProof())
        assertTrue(fixture.inRoutePool(first.proof())); assertFalse(fixture.inRoutePool(effective.proof()))
    }

    @Test fun differentCandidateCallersJoinOneRenewalWithoutSupersedingIt() {
        var nonce = 0
        val coordinator = AbemaRenewalReadinessCoordinator<Work>({ 100 }, { (++nonce).toString(16).padStart(32, '0') })
        // Watch launches with the effective candidate; Episodes and startup use the first.
        val launched = coordinator.check(instanceId, AbemaRenewalScope.of(effective, expired), Work(effective, expired))
        assertEquals(LAUNCH, launched.status)
        val ticket = checkNotNull(launched.ticket)
        assertEquals(WAIT, coordinator.check(instanceId, AbemaRenewalScope.of(first, expired), Work(first, expired)).status)
        assertTrue(coordinator.current(ticket)); assertEquals(1, nonce)
        val work = checkNotNull(coordinator.claim(ticket)); assertSame(effective, work.owner)
        // Before reading a tentatively published record, another candidate's caller joins the claim.
        assertEquals(WAIT, coordinator.observeClaimed(instanceId) { it.owner.inRoutePool(first.proof()) }?.status)
        assertNull(coordinator.observeClaimed(instanceId) { it.owner.inRoutePool("f".repeat(64)) })
        coordinator.finish(ticket, true)
        assertEquals(AbemaRenewalReadinessCoordinator.Status.READY,
            coordinator.check(instanceId, AbemaRenewalScope.of(first, expired), Work(first, expired)).status)
        assertEquals(1, nonce)
    }

    @Test fun aChangedSnapshotOrRoutePoolStillSupersedesInFlightWork() {
        val current = capture(values = profiles + (savedId to profile("rotated")))
        for ((owner, changed) in listOf(effective to snapshot(revision = 2), current to expired)) {
            var nonce = 0
            val coordinator = AbemaRenewalReadinessCoordinator<Work>({ 100 }, { (++nonce).toString(16).padStart(32, '0') })
            val old = checkNotNull(coordinator.check(instanceId, AbemaRenewalScope.of(first, expired), Work(first, expired)).ticket)
            val fresh = coordinator.check(instanceId, AbemaRenewalScope.of(owner, changed), Work(owner, changed))
            assertEquals(LAUNCH, fresh.status)
            assertFalse(coordinator.current(old)); assertNull(coordinator.claim(old))
            assertTrue(coordinator.current(checkNotNull(fresh.ticket)))
        }
    }

    @Test fun anotherCandidatesCallerContinuesTheSameRetryLoop() {
        var elapsed = 1_000L
        val tickets = AtomicInteger()
        val coordinator = AbemaRenewalReadinessCoordinator<Work>({ elapsed },
            { tickets.incrementAndGet().toString(16).padStart(32, '0') })
        val queue = ArrayDeque<() -> Unit>()
        val constructed = mutableListOf<Pair<Work, (AbemaRenewalOperationOutcome) -> Unit>>()
        val logs = mutableListOf<String>()
        lateinit var loop: AbemaRenewalRetryLoop<Work, AbemaRenewalOwnedOperation>
        val admission = AbemaRenewalOwnedAdmission<Work, AbemaRenewalOwnedOperation>(coordinator, ConcurrentHashMap(),
            validate = { ticket, _ -> coordinator.current(ticket) }, dispatch = { queue.add(it); true },
            elapsed = { elapsed }, schedulePending = { _, _ -> true },
            authoritative = { work, outcome, ownerCancelled -> loop.completed(work, outcome, ownerCancelled) })
        loop = AbemaRenewalRetryLoop(coordinator, admission, { instanceId }, { AbemaRenewalScope.of(it.owner, it.expected) },
            { elapsed }, logs::add)
        val deadline = elapsed + AbemaReadinessIpc.LIMIT_MS
        fun poll(owner: AbemaCatalogRouteOwner): AbemaRenewalReadinessCoordinator.Status =
            when (val reservation = loop.reserve(instanceId, AbemaRenewalScope.of(owner, expired), Work(owner, expired), deadline,
                { true }, construct = { _, work, done -> constructed.add(work to done)
                    object : AbemaRenewalOwnedOperation { override fun start() {}
                        override fun cancel() = done(AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.CANCELLED, true)) } },
                retry = true)) {
                is AbemaRenewalReservation.Owned -> {
                    assertTrue(reservation.lease.activate())
                    repeat(20) { queue.removeFirstOrNull()?.invoke() ?: return WAIT }; error("Fixture did not settle")
                }
                is AbemaRenewalReservation.Observed -> reservation.status
                is AbemaRenewalReservation.NoWork -> reservation.status
                AbemaRenewalReservation.Deferred -> error("Deferred")
            }
        assertEquals(WAIT, poll(first))
        constructed.last().second(AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, true))
        // During the backoff the other candidate's caller waits for the loop.
        assertEquals(WAIT, poll(effective)); assertEquals(1, constructed.size)
        elapsed += 2_000
        assertEquals(WAIT, poll(effective)); assertEquals(2, constructed.size)
        assertSame(effective, constructed.last().first.owner)
        assertEquals(listOf("RETRY attempt=1 reason=TEMPORARY_FAILURE"), logs)
        constructed.last().second(AbemaRenewalOperationOutcome(AbemaAutomaticRenewalResult.SUCCESS, true))
        assertEquals(AbemaRenewalReadinessCoordinator.Status.READY, poll(first))
        assertEquals(2, constructed.size)
    }
}
