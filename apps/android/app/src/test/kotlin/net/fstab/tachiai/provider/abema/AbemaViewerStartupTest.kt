package net.fstab.tachiai.provider.abema

import java.util.UUID
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.abema.account.*
import org.junit.Assert.*
import org.junit.Test

class AbemaViewerStartupTest {
    private val routeId = "12345678-1234-1234-1234-123456789abd"
    private inner class Fixture(saved: Boolean = true) {
        val id = UUID.randomUUID().toString()
        val instance = ProviderInstance(id, PrototypeService.ABEMA, "ABEMA", setup = ProviderSetup(SourceRouteChoice.system,
            fallbackRoutes = listOf(SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, routeId, "Second route"))))
        val profile = parseConnectionProfile("http://user:fixture-secret@proxy.example.test:3128".toByteArray())
        val pool = captureProviderRoutePool(id, PrototypeService.ABEMA, { listOf(instance) }) { mapOf(routeId to profile) }
        val storage = MemoryAbemaAccountStorage(); val store = AbemaAccountGrantStore(id, storage)
        var wall = 1000000L; var active = true; var routeCurrent = true
        var createdBindings = 0
        val transports = mutableListOf<Transport>()
        val startup = ProviderRouteStartup(pool, 46000, { 1000 }, { routeCurrent }, scheduleDeadline = { _, _ -> AutoCloseable {} })
        var onProfile: (Transport) -> Unit = {}; var onEmail: (Transport) -> Unit = {}; var onClose: (Transport) -> Unit = {}
        init { if (saved) store.replace(decodedAbemaGrant(fixtureAccountToken(), "intended-account", wall), wall, store.revision()) { true } }
        inner class Transport(val candidate: ProviderEffectiveRoute, private val gate: () -> Boolean) : AbemaAccountTransport {
            val ordinal = transports.size + 1
            var profiles = 0; var emails = 0; var closes = 0; var cancels = 0
            var cleanup = true; var closeThrows = false
            var profileResult: AbemaAccountProfileResult = AbemaAccountProfileResult.Identity("intended-account")
            var emailResult = AbemaAccountEmailResult.REGISTERED
            override fun profile(id: String, bearer: String): AbemaAccountProfileResult {
                check(gate()); assertEquals("intended-account", id); profiles++; onProfile(this); return profileResult
            }
            override fun emailRegistration(id: String, bearer: String): AbemaAccountEmailResult {
                check(gate()); emails++; onEmail(this); return emailResult
            }
            override fun token(challenge: AbemaAccountChallenge): AbemaAccountTokenResult = error("Startup must not issue tokens")
            override fun cancelActiveRequest() { cancels++ }
            override fun close() { closes++; onClose(this); if (closeThrows) error("Synthetic cleanup failure") }
        }
        fun prepare(): ProviderStartupResult<Unit> = prepareAbemaViewerStartup(startup, { active }, { wall }, { (it as Transport).cleanup }) { candidate ->
            if (createdBindings > 0 && transports.isNotEmpty()) assertEquals(1, transports.last().closes)
            createdBindings++
            object : AbemaAccountConnectionBinding {
                override val store = this@Fixture.store
                override val routeCurrent get() = this@Fixture.routeCurrent
                private val captured = AbemaCatalogRouteOwner(candidate)
                override fun owner() = captured
                override fun sameOwner(owner: AbemaCatalogRouteOwner) = routeCurrent && captured.sameOwnership(owner)
                override fun bootstrap(owner: AbemaCatalogRouteOwner, gate: () -> Boolean, foreground: () -> Boolean): AbemaAccountBootstrap =
                    error("Read-only startup must not bootstrap a guest")
                override fun transport(owner: AbemaCatalogRouteOwner, gate: () -> Boolean): AbemaAccountTransport {
                    assertEquals(candidate.proof, owner.proof())
                    return Transport(candidate, gate).also(transports::add)
                }
            }
        }
    }
    @Test fun existingGrantValidatesExactCandidateIdentityAndEmailWithCleanupBeforeFallbackAndPin() {
        val f = Fixture(); val before = f.storage.bytes!!.copyOf()
        f.onProfile = { if (it.ordinal == 1) throw AbemaAccountException(AbemaAccountFailure.TEMPORARY,
            ProviderTransportEvidence(ProviderTransportFailure.CONNECT)) }
        val ready = f.prepare() as ProviderStartupResult.Ready
        assertEquals(routeId, ready.owner.choice.connectionId); assertTrue(f.startup.admitted)
        assertEquals(listOf(1, 1), f.transports.map { it.profiles }); assertEquals(listOf(0, 1), f.transports.map { it.emails })
        assertEquals(listOf(1, 1), f.transports.map { it.closes }); assertArrayEquals(before, f.storage.bytes)
        f.startup.close(); assertEquals(listOf(1, 1), f.transports.map { it.closes })
    }
    @Test fun missingOrExpiredGrantHoldsFirstCandidateWithoutAnyProviderRequestOrMutation() {
        for (missing in listOf(true, false)) {
            val f = Fixture(saved = !missing)
            if (!missing) f.wall = 2000000
            val result = f.prepare() as ProviderStartupResult.HeldForMutation
            assertEquals(SourceRouteChoice.system, result.owner.choice); assertFalse(f.startup.admitted)
            assertEquals(1, f.createdBindings); assertTrue(f.transports.isEmpty()); f.startup.close()
        }
    }
    @Test fun identityEmailTlsAndUnknownRefusalsNeverTryAnotherRoute() {
        for (kind in 0..5) {
            val f = Fixture()
            f.onProfile = {
                when (kind) {
                    0 -> it.profileResult = AbemaAccountProfileResult.Identity("different-account")
                    1 -> it.profileResult = AbemaAccountProfileResult.Rejected
                    2 -> it.profileResult = AbemaAccountProfileResult.Inconclusive
                    3 -> throw AbemaAccountException(AbemaAccountFailure.TEMPORARY, ProviderTransportEvidence(ProviderTransportFailure.TLS))
                    4 -> throw AbemaAccountException(AbemaAccountFailure.TEMPORARY)
                    else -> it.emailResult = AbemaAccountEmailResult.UNREGISTERED
                }
            }
            assertTrue(f.prepare() is ProviderStartupResult.Failed); assertFalse(f.startup.admitted)
            assertEquals(1, f.createdBindings); assertEquals(1, f.transports.single().closes); f.startup.close()
        }
    }
    @Test fun cleanupFailureDominatesTransportAvailabilityAndSuccessfulReplies() {
        for (kind in 0..2) {
            val f = Fixture()
            f.onProfile = {
                if (kind == 0) it.cleanup = false
                if (kind == 1) it.closeThrows = true
                if (kind != 2) throw AbemaAccountException(AbemaAccountFailure.TEMPORARY, ProviderTransportEvidence(ProviderTransportFailure.DNS))
            }
            f.onClose = { if (kind == 2) it.cleanup = false }
            val failed = f.prepare() as ProviderStartupResult.Failed
            assertEquals(ProviderStartupFailure.CLEANUP_UNCONFIRMED, failed.failure.reason)
            assertEquals(1, f.createdBindings); assertFalse(f.startup.admitted); f.startup.close()
        }
    }
    @Test fun ownerGrantForegroundAndPostCleanupChangesCannotPublishAValidatedCandidate() {
        for (kind in 0..3) {
            val f = Fixture()
            f.onProfile = { when (kind) {
                0 -> f.routeCurrent = false
                1 -> f.store.invalidateForget()
                2 -> f.active = false
            } }
            f.onClose = { if (kind == 3) f.store.invalidateForget() }
            assertTrue(f.prepare() is ProviderStartupResult.Failed); assertFalse(f.startup.admitted)
            assertEquals(1, f.createdBindings); assertEquals(1, f.transports.single().closes); f.startup.close()
        }
    }
    @Test fun expiryAfterReadHoldsCurrentCandidateWithoutGuestOrAnotherExit() {
        val f = Fixture()
        f.onProfile = { if (it.ordinal == 1) throw AbemaAccountException(AbemaAccountFailure.TEMPORARY,
            ProviderTransportEvidence(ProviderTransportFailure.CONNECT)) else f.wall = 2000000 }
        val held = f.prepare() as ProviderStartupResult.HeldForMutation
        assertEquals(routeId, held.owner.choice.connectionId); assertFalse(f.startup.admitted)
        assertEquals(listOf(1, 1), f.transports.map { it.closes }); assertTrue(f.transports.all { it.emails == 0 })
        f.startup.close()
    }
}
