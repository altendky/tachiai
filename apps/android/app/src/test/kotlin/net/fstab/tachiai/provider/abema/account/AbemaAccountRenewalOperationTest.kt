package net.fstab.tachiai.provider.abema.account

import java.util.UUID
import java.util.concurrent.Executor
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.ProviderInstance
import net.fstab.tachiai.provider.abema.AbemaCatalogRouteOwner
import org.junit.Assert.*
import org.junit.Test

class AbemaAccountRenewalOperationTest {
    private class Work : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.add(command) }
        fun one() = queue.removeFirstOrNull()?.let { it.run(); true } ?: false
    }
    private class Fixture {
        val id = UUID.randomUUID().toString()
        val storage = MemoryAbemaAccountStorage()
        val store = AbemaAccountGrantStore(id, storage)
        var elapsed = 1000L
        var wall = 2_000_001L
        var scope = true
        var scopeHook: () -> Unit = {}
        var matches = true
        var localRoute = true
        var profile: AbemaAccountProfileResult? = null
        var email = AbemaAccountEmailResult.REGISTERED
        var profileHook: () -> Unit = {}
        var ownerHook: () -> Unit = {}
        var beforeApproval: (String, Long, () -> Boolean) -> Boolean = { _, _, gate -> gate() }
        var closeFails = false
        var profiles = 0
        var emails = 0
        var closes = 0
        var requests = 0
        var cancellations = 0
        var releases = 0
        var releaseConfirmed = true
        val owner = AbemaCatalogRouteOwner(ProviderInstance(id, PrototypeService.ABEMA, "Fixture"), null)
        val worker = Work(); val cancellation = Work(); val main = ArrayDeque<() -> Unit>()
        val later = mutableListOf<Pair<Long, () -> Unit>>()
        val outcomes = mutableListOf<AbemaRenewalOperationOutcome>()
        val inbox = AbemaEmailGrantInbox({ elapsed }, { wall })
        var request: AbemaApprovalBrowserRequest? = null
        var returned: ((AbemaRenewalBrowserResult) -> Unit)? = null
        val browser = object : AbemaRenewalBrowser {
            override fun start(request: AbemaApprovalBrowserRequest, completed: (AbemaRenewalBrowserResult) -> Unit) {
                requests++; this@Fixture.request = request; returned = completed
            }
            override fun cancel() { cancellations++ }
            override fun release(): Boolean { releases++; return releaseConfirmed }
        }
        val binding = object : AbemaAccountConnectionBinding {
            override val store = this@Fixture.store
            override val routeCurrent get() = localRoute
            override fun owner() = owner
            override fun sameOwner(owner: AbemaCatalogRouteOwner): Boolean {
                ownerHook(); return matches && owner === this@Fixture.owner
            }
            override fun bootstrap(owner: AbemaCatalogRouteOwner, gate: () -> Boolean, foreground: () -> Boolean): AbemaAccountBootstrap =
                error("Renewal must not create device authorization")
            override fun transport(owner: AbemaCatalogRouteOwner, gate: () -> Boolean) = object : AbemaAccountTransport {
                override fun profile(id: String, bearer: String): AbemaAccountProfileResult {
                    check(gate()); profiles++; profileHook()
                    return profile ?: AbemaAccountProfileResult.Identity(id)
                }
                override fun emailRegistration(id: String, bearer: String): AbemaAccountEmailResult {
                    check(gate()); emails++; return email
                }
                override fun token(challenge: AbemaAccountChallenge): AbemaAccountTokenResult = error("No token polling")
                override fun cancelActiveRequest() = Unit
                override fun close() { closes++; if (closeFails) throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED) }
            }
        }
        val expected: AbemaAccountSnapshot
        val prior: ByteArray
        init {
            val grant = decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1_000_000)
            store.replace(grant, 1_000_000, store.revision()) { true }
            expected = store.readSnapshot(); prior = checkNotNull(storage.bytes).copyOf()
        }
        val deadline = elapsed + 90_000
        val operation = AbemaAccountRenewalOperation(binding, owner, expected, deadline, browser, inbox,
            worker, cancellation, { main.add(it) }, { wait, action -> later.add(elapsed + wait to action) },
            { elapsed }, { wall }, { scopeHook(); scope }, { id, deadline, gate -> beforeApproval(id, deadline, gate) }, outcomes::add)
        fun pump() {
            repeat(200) {
                var worked = worker.one() || cancellation.one()
                main.removeFirstOrNull()?.let { it(); worked = true }
                val due = later.firstOrNull { it.first <= elapsed }
                due?.let { later.remove(it); it.second(); worked = true }
                if (!worked) return
            }
            error("Fixture did not settle")
        }
        fun start() { operation.start(); pump() }
        fun grant(id: String = "intended-account") = decodedAbemaGrant(fixtureAccountToken(id, "3000"), id, wall)
        fun deliver(grant: AbemaAccountGrant = grant()): Boolean {
            val request = checkNotNull(request)
            return inbox.deliver(checkNotNull(request.emailNonce), request.instanceId, request.ownerProof,
                request.deadlineElapsedMs, grant)
        }
        fun browserDone(result: AbemaRenewalBrowserResult = AbemaRenewalBrowserResult.DELIVERED) {
            checkNotNull(returned)(result); pump()
        }
        fun onlyResult() = outcomes.single().result
    }

    @Test fun sameAccountExtensionAdoptsAfterBrowserAndNativeCleanupWithoutForegroundSession() {
        val f = Fixture(); f.start()
        assertEquals(1, f.requests); assertTrue(f.request!!.automaticReturn); assertFalse(f.request!!.resetBrowserSession)
        assertEquals(f.deadline, f.request!!.deadlineElapsedMs)
        assertTrue(f.deliver()); assertEquals(0, f.profiles); assertArrayEquals(f.prior, f.storage.bytes)
        f.browserDone()
        assertEquals(AbemaAutomaticRenewalResult.SUCCESS, f.onlyResult()); assertTrue(f.outcomes.single().cleanupConfirmed)
        assertEquals(1, f.profiles); assertEquals(1, f.emails); assertEquals(2, f.closes); assertEquals(1, f.releases)
        val replacement = f.store.readSnapshot()
        assertEquals(f.expected.record!!.generation + 1, replacement.record!!.generation)
        assertEquals("intended-account", replacement.record!!.grant.userId)
        assertTrue(replacement.record!!.grant.expiresAtMs > f.expected.record!!.grant.expiresAtMs)
        assertFalse(abemaRenewalSnapshotMatches(f.expected, replacement, f.wall))
        f.operation.cancel(); f.browserDone(); assertEquals(1, f.outcomes.size)
    }

    @Test fun startupRequiresExactOriginalRecordOwnerAndConfirmedStartupCleanup() {
        for (change in 0..4) {
            val f = Fixture()
            when (change) {
                0 -> f.matches = false
                1 -> f.localRoute = false
                2 -> f.store.invalidateForget()
                3 -> f.beforeApproval = { _, _, _ -> false }
                4 -> f.wall = 1_500_000 // A valid grant is not silently replaced.
            }
            f.start(); assertEquals(0, f.requests); assertNotEquals(AbemaAutomaticRenewalResult.SUCCESS, f.onlyResult())
            assertArrayEquals(f.prior, f.storage.bytes); assertEquals(0, f.profiles)
        }
    }

    @Test fun explicitCancellationDetachesCandidateButWaitsForActualBrowserCleanup() {
        val f = Fixture(); f.start(); assertTrue(f.deliver())
        f.operation.cancel(); f.pump()
        assertTrue(f.outcomes.isEmpty()); assertEquals(1, f.cancellations); assertFalse(f.deliver())
        f.browserDone(AbemaRenewalBrowserResult.CANCELLED)
        assertEquals(AbemaAutomaticRenewalResult.CANCELLED, f.onlyResult()); assertTrue(f.outcomes.single().cleanupConfirmed)
        assertEquals(0, f.profiles); assertArrayEquals(f.prior, f.storage.bytes)
    }

    @Test fun missingCandidateAndAmbiguousProviderFailuresNeverRequestInteraction() {
        for (failure in 0..5) {
            val f = Fixture(); f.start()
            if (failure != 0) assertTrue(f.deliver())
            when (failure) {
                1 -> f.profile = AbemaAccountProfileResult.Rejected
                2 -> f.profile = AbemaAccountProfileResult.Inconclusive
                3 -> f.email = AbemaAccountEmailResult.UNREGISTERED
                4 -> f.email = AbemaAccountEmailResult.REJECTED
                5 -> f.email = AbemaAccountEmailResult.INCONCLUSIVE
            }
            f.browserDone()
            assertEquals(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, f.onlyResult())
            assertTrue(f.outcomes.single().cleanupConfirmed); assertArrayEquals(f.prior, f.storage.bytes)
        }
    }

    @Test fun onlyVerifiedDifferentRegisteredAccountRequestsAccountSelection() {
        for (ambiguous in 0..2) {
            val f = Fixture(); f.start(); assertTrue(f.deliver(f.grant("other-account")))
            if (ambiguous == 1) f.email = AbemaAccountEmailResult.UNREGISTERED
            if (ambiguous == 2) f.profile = AbemaAccountProfileResult.Identity("inconsistent")
            f.browserDone()
            assertEquals(if (ambiguous == 0) AbemaAutomaticRenewalResult.INTERACTION_REQUIRED
                else AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, f.onlyResult())
            assertArrayEquals(f.prior, f.storage.bytes)
        }
    }

    @Test fun originalDeadlineAndClockRollbackCannotBeExtendedByLateDelivery() {
        for (clock in 0..2) {
            val f = Fixture(); f.start()
            when (clock) {
                0 -> f.elapsed = f.deadline
                1 -> f.elapsed--
                2 -> f.wall--
            }
            f.pump()
            if (clock == 0) assertFalse(f.deliver()) else f.deliver()
            f.browserDone()
            assertNotEquals(AbemaAutomaticRenewalResult.SUCCESS, f.onlyResult())
            assertNotEquals(AbemaAutomaticRenewalResult.INTERACTION_REQUIRED, f.onlyResult())
            assertArrayEquals(f.prior, f.storage.bytes)
        }
    }

    @Test fun forgetOwnerScopeAndClockLossDuringNativeVerificationCannotAdopt() {
        for (change in 0..4) {
            val f = Fixture(); f.start(); assertTrue(f.deliver())
            f.profileHook = {
                when (change) {
                    0 -> f.store.invalidateForget()
                    1 -> f.matches = false
                    2 -> f.scope = false
                    3 -> f.elapsed--
                    4 -> f.operation.cancel()
                }
            }
            f.browserDone(); assertNotEquals(AbemaAutomaticRenewalResult.SUCCESS, f.onlyResult())
            assertArrayEquals(f.prior, f.storage.bytes); assertEquals(0, f.emails)
        }
    }

    @Test fun cancellationDuringPublicationAndProtectedOwnerLossAfterCasRollBackExactWrite() {
        for (cancel in listOf(true, false)) {
            val f = Fixture(); f.start(); assertTrue(f.deliver())
            f.storage.writeHook = { if (cancel) f.operation.cancel() else f.matches = false }
            f.browserDone()
            assertEquals(AbemaAutomaticRenewalResult.CANCELLED, f.onlyResult())
            assertArrayEquals(f.prior, f.storage.bytes)
        }
    }

    @Test fun laterWriterWinsInsteadOfBeingUndoneByOldReplacementRollback() {
        val f = Fixture(); f.start(); assertTrue(f.deliver())
        var replaced = false
        f.ownerHook = {
            if (!replaced && f.store.revision() > f.expected.revision) {
                replaced = true
                f.store.replace(f.grant("later-account"), f.wall, f.store.revision()) { true }
            }
        }
        f.browserDone()
        assertEquals(AbemaAutomaticRenewalResult.CANCELLED, f.onlyResult())
        assertTrue(replaced); assertEquals("later-account", f.store.read()!!.grant.userId)
    }

    @Test fun candidateExpiryDuringPostPublicationOwnerCheckRollsBackInsteadOfPublishingSuccess() {
        val f = Fixture(); f.start()
        val grant = decodedAbemaGrant(fixtureAccountToken("intended-account", "2001"), "intended-account", f.wall)
        assertTrue(f.deliver(grant))
        f.ownerHook = { if (f.store.revision() > f.expected.revision) f.wall = grant.expiresAtMs }
        f.browserDone()
        assertEquals(AbemaAutomaticRenewalResult.CANCELLED, f.onlyResult())
        assertArrayEquals(f.prior, f.storage.bytes)
    }

    @Test fun forgetOrLaterWriterAfterReceiptReadCannotPassFinalCompletionGate() {
        for (forget in listOf(true, false)) {
            val f = Fixture(); f.start(); assertTrue(f.deliver())
            var armed = false
            var changed = false
            f.ownerHook = { if (f.store.revision() > f.expected.revision) armed = true }
            // The first scope check after the post-CAS owner check follows
            // abemaReplacementReceiptCurrent. Revoke in that final gap.
            f.scopeHook = {
                if (armed && !changed) {
                    changed = true
                    if (forget) { f.store.invalidateForget(); f.store.forget() }
                    else f.store.replace(f.grant("later-account"), f.wall, f.store.revision()) { true }
                }
            }
            f.browserDone()
            assertTrue(changed)
            assertEquals(AbemaAutomaticRenewalResult.CANCELLED, f.onlyResult())
            assertTrue(f.outcomes.single().cleanupConfirmed)
            if (forget) assertNull(f.store.read()) else assertEquals("later-account", f.store.read()!!.grant.userId)
        }
    }

    @Test fun browserReleaseNativeCleanupAndMissingCleanupResponseKeepUncertainty() {
        for (failure in 0..3) {
            val f = Fixture(); f.start(); assertTrue(f.deliver())
            when (failure) {
                0 -> f.releaseConfirmed = false
                1 -> f.closeFails = true
                2 -> { f.operation.cancel(); f.pump(); f.elapsed += 7000; f.pump() }
            }
            if (failure != 2) f.browserDone(if (failure == 3) AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED
                else AbemaRenewalBrowserResult.DELIVERED)
            assertEquals(AbemaAutomaticRenewalResult.TEMPORARY_FAILURE, f.onlyResult())
            assertFalse(f.outcomes.single().cleanupConfirmed); assertArrayEquals(f.prior, f.storage.bytes)
        }
    }
}
