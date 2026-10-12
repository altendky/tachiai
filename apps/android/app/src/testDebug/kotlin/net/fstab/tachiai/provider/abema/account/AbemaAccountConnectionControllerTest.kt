package net.fstab.tachiai.provider.abema.account

import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.abema.AbemaCatalogRouteOwner
import org.junit.Assert.*
import org.junit.Test

class AbemaAccountConnectionControllerTest {
    private class Work : Executor {
        val queued = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queued.add(command) }
        fun one() = queued.removeFirstOrNull()?.let { it.run(); true } ?: false
    }
    private class Fixture {
        val storage = MemoryAbemaAccountStorage()
        val id = UUID.randomUUID().toString()
        val store = AbemaAccountGrantStore(id, storage)
        val owner = AbemaCatalogRouteOwner(ProviderInstance(id, PrototypeService.ABEMA, "Fixture"), null)
        var matching = true; var now = 1000L; var wall = 1000000L; var negativeAccepted = false
        var profileMismatch = false; var profileRejected = false; var preparations = 0; var tokens = 0; var closed = 0; var closeThrows = false
        var onToken: () -> Unit = {}
        var onProfile: () -> Unit = {}
        var onEmail: () -> Unit = {}
        var onElapsed: () -> Unit = {}
        var emailResult = AbemaAccountEmailResult.REGISTERED
        var emailChecks = 0
        var ownerReads = 0
        val profileIds = mutableListOf<String>()
        val profileBearers = mutableListOf<String>()
        val results = ArrayDeque<AbemaAccountTokenResult>()
        val worker = Work(); val cancel = Work(); val main = ArrayDeque<() -> Unit>()
        val inbox = AbemaEmailGrantInbox({ now }, { wall })
        private val later = mutableListOf<Pair<Long, () -> Unit>>()
        val binding = object : AbemaAccountConnectionBinding {
            override val store = this@Fixture.store
            override fun owner(): AbemaCatalogRouteOwner { ownerReads++; return this@Fixture.owner }
            override fun sameOwner(owner: AbemaCatalogRouteOwner): Boolean { ownerReads++; return matching && owner === this@Fixture.owner }
            override fun bootstrap(owner: AbemaCatalogRouteOwner, gate: () -> Boolean, foreground: () -> Boolean) = object : AbemaAccountBootstrap {
                override fun prepare(): AbemaAccountChallenge? { preparations++; return fixtureAccountChallenge(1000000).takeIf { gate() && foreground() } }
                override fun cancel() = Unit
                override fun close() = Unit
            }
            override fun transport(owner: AbemaCatalogRouteOwner, gate: () -> Boolean) = object : AbemaAccountTransport {
                override fun profile(id: String, bearer: String): AbemaAccountProfileResult {
                    if (!gate()) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
                    profileIds.add(id)
                    profileBearers.add(bearer); onProfile()
                    if (profileRejected) return AbemaAccountProfileResult.Rejected
                    if (bearer == "tachiai-invalid-bearer" && !negativeAccepted) return AbemaAccountProfileResult.Rejected
                    return AbemaAccountProfileResult.Identity(if (profileMismatch && id != "guest") "other" else id)
                }
                override fun token(challenge: AbemaAccountChallenge): AbemaAccountTokenResult {
                    assertEquals("guest", challenge.clientId); tokens++; onToken()
                    return results.removeFirstOrNull() ?: AbemaAccountTokenResult.Pending
                }
                override fun emailRegistration(id: String, bearer: String): AbemaAccountEmailResult {
                    assertTrue(gate()); assertEquals("intended-account", id)
                    assertEquals(fixtureAccountToken(), bearer); emailChecks++; onEmail(); return emailResult
                }
                override fun cancelActiveRequest() = Unit
                override fun close() { closed++; if (closeThrows) throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED) }
            }
        }
        val controller = AbemaAccountConnectionController(binding, worker, cancel, { main.add(it) },
            { delay, action -> later.add(now + delay to action) }, { onElapsed(); now }, { wall }, emailInbox = inbox)
        fun due(): Boolean {
            val task = later.firstOrNull { it.first <= now } ?: return false
            later.remove(task); task.second(); return true
        }
        fun pump() {
            repeat(300) {
                var did = worker.one() || cancel.one()
                main.removeFirstOrNull()?.let { it(); did = true }
                if (due()) did = true
                if (!did) return
            }; error("Fixture did not settle")
        }
        fun prepared() { controller.load(); pump(); controller.connect(); pump(); assertTrue(controller.state.value.canApprove) }
        fun handedOff() { prepared(); var launched = false; controller.handoff { launched = true; assertTrue(validAbemaVerificationUri(it)); true }; pump(); assertTrue(launched) }
        fun granted(id: String = "intended-account") = AbemaAccountTokenResult.Granted(decodedAbemaGrant(fixtureAccountToken(id), id, wall))
        fun emailOpened(): AbemaApprovalBrowserRequest {
            controller.load(); pump()
            var request: AbemaApprovalBrowserRequest? = null
            controller.connectEmail { request = it; true }; pump()
            return checkNotNull(request)
        }
        fun emailReturned(request: AbemaApprovalBrowserRequest) {
            controller.setForeground(false)
            assertTrue(inbox.deliver(checkNotNull(request.emailNonce), request.instanceId, request.ownerProof,
                request.deadlineElapsedMs, decodedAbemaGrant(fixtureAccountToken(), "intended-account", wall)))
            assertFalse(controller.state.value.canContinue)
            controller.setForeground(true); pump(); assertTrue(controller.state.value.canContinue)
        }
    }
    @Test fun emailSetupUsesTheProviderBrowserAndSavesOnlyAfterNativeIdentityAndRegistrationChecks() {
        val f = Fixture(); val request = f.emailOpened()
        assertEquals("https://abema.tv/account", request.verificationUri)
        assertEquals(0, f.preparations); assertEquals(0, f.tokens); assertTrue(f.profileIds.isEmpty())
        assertNull(f.storage.bytes); assertFalse(f.controller.state.value.canApprove)
        f.emailReturned(request); assertNull(f.storage.bytes)
        f.controller.finishEmailConnection(); f.pump()
        assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        assertEquals("intended-account", f.store.read()!!.grant.userId)
        assertEquals(listOf("intended-account"), f.profileIds); assertEquals(1, f.emailChecks)
        assertFalse(f.controller.state.value.canContinue); assertEquals(0, f.tokens)
    }
    @Test fun emailRegistrationRefusalAndOwnerOrForegroundLossCannotSaveTheBrowserCandidate() {
        for (failure in 0..4) {
            val f = Fixture(); val request = f.emailOpened(); f.emailReturned(request)
            when (failure) {
                0 -> f.emailResult = AbemaAccountEmailResult.UNREGISTERED
                1 -> f.emailResult = AbemaAccountEmailResult.REJECTED
                2 -> f.emailResult = AbemaAccountEmailResult.INCONCLUSIVE
                3 -> f.onEmail = { f.matching = false }
                4 -> f.onEmail = { f.controller.setForeground(false) }
            }
            f.controller.finishEmailConnection(); f.pump()
            assertNull(f.storage.bytes); assertNotEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        }
    }
    @Test fun cancelledOrExpiredEmailHandoffsCannotCompleteOrCreateADeviceChallenge() {
        for (expired in listOf(false, true)) {
            val f = Fixture(); val request = f.emailOpened()
            if (expired) f.now = request.deadlineElapsedMs else f.controller.cancelAction()
            assertFalse(f.inbox.deliver(checkNotNull(request.emailNonce), request.instanceId, request.ownerProof,
                request.deadlineElapsedMs, decodedAbemaGrant(fixtureAccountToken(), "intended-account", f.wall)))
            f.controller.finishEmailConnection(); f.pump()
            assertNull(f.storage.bytes); assertEquals(0, f.preparations); assertEquals(0, f.tokens)
        }
    }
    @Test fun expiredCandidateOrChangedProtectedStorePreventsEmailValidationDispatchAndPublication() {
        for (failure in 0..3) {
            val f = Fixture(); val request = f.emailOpened(); f.emailReturned(request)
            when (failure) {
                0 -> f.wall = 2000000
                1 -> f.store.forget()
                2 -> f.onProfile = { f.store.forget() }
                3 -> f.onEmail = { f.store.forget() }
            }
            f.controller.finishEmailConnection(); f.pump()
            assertNull(f.store.read()); assertNotEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
            assertFalse(f.controller.state.value.canContinue)
            if (failure <= 1) assertTrue(f.profileIds.isEmpty())
            if (failure <= 2) assertEquals(0, f.emailChecks)
        }
    }
    @Test fun replacementLaunchFailureAndExpiryCheckPriorOwnershipOnlyOnTheWorker() {
        for (expired in listOf(false, true)) {
            val f = Fixture(); val initial = f.emailOpened(); f.emailReturned(initial)
            f.controller.finishEmailConnection(); f.pump()
            assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
            val original = f.storage.bytes!!.copyOf()
            var replacement: AbemaApprovalBrowserRequest? = null
            f.controller.connectEmail { replacement = it; expired }
            assertTrue(f.worker.one())
            val reads = f.ownerReads
            checkNotNull(f.main.removeFirstOrNull()).invoke()
            assertEquals(reads, f.ownerReads)
            if (expired) {
                f.pump()
                f.now = checkNotNull(replacement).deadlineElapsedMs
                while (f.due()) { /* Execute due UI callbacks without pumping the worker. */ }
                assertEquals(reads, f.ownerReads)
            }
            f.pump()
            assertTrue(f.ownerReads > reads)
            assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
            assertArrayEquals(original, f.storage.bytes)
            assertFalse(f.controller.state.value.canContinue)
        }
    }
    @Test fun oldEmailTimeoutCannotClearAnAttemptPreparedDuringItsClockCheck() {
        val f = Fixture(); val initial = f.emailOpened()
        f.now = initial.deadlineElapsedMs
        var replacement: AbemaApprovalBrowserRequest? = null
        f.onElapsed = {
            f.onElapsed = {}
            f.controller.connectEmail { replacement = it; true }
            assertTrue(f.worker.one())
        }
        assertTrue(f.due()); f.pump()
        val current = checkNotNull(replacement)
        assertNotEquals(initial.emailNonce, current.emailNonce)
        f.emailReturned(current)
        f.controller.finishEmailConnection(); f.pump()
        assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        assertNotNull(f.store.read())
    }

    @Test fun completeProviderControlledFlowPersistsOnlyMatchingPositiveGrantAfterPrivateHandoff() {
        val f = Fixture(); f.handedOff(); assertNull(f.storage.bytes)
        f.controller.setForeground(false); f.now += 10000; f.controller.setForeground(true)
        f.results.add(f.granted()); f.controller.continueApproval(); f.pump()
        assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        assertEquals("intended-account", f.store.read()?.grant?.userId); assertEquals(1, f.tokens)
        assertEquals(listOf("intended-account"), f.profileIds)
        assertFalse(f.profileBearers.contains("tachiai-invalid-bearer"))
        assertFalse(f.controller.state.value.toString().contains("intended-account")); assertFalse(f.controller.state.value.toString().contains("private-code"))
    }
    @Test fun routedBrowserReceivesOnlyOriginalInstanceOwnerAndDeadlineWithoutPublishingPrivateUri() {
        val f = Fixture(); f.prepared()
        var request: AbemaApprovalBrowserRequest? = null
        f.controller.handoffBrowser { request = it; true }; f.pump()
        val captured = checkNotNull(request)
        assertEquals(f.id, captured.instanceId)
        assertEquals(f.owner.proof(), captured.ownerProof)
        assertEquals(1000000L, captured.deadlineElapsedMs)
        assertTrue(validAbemaVerificationUri(captured.verificationUri))
        assertFalse(f.controller.state.value.toString().contains(captured.verificationUri))
        assertNull(f.storage.bytes); assertEquals(0, f.tokens)
        f.controller.cancelAction(); f.pump()
        assertFalse(f.controller.state.value.canContinue)
    }
    @Test fun preparationDoesNotRunProfileRejectionProbesOrPersistAnAccountBeforeApproval() {
        val f = Fixture(); f.negativeAccepted = true; f.controller.load(); f.pump()
        f.controller.connect(); f.pump()
        assertTrue(f.controller.state.value.canApprove); assertTrue(f.profileIds.isEmpty())
        var launches = 0; f.controller.handoff { launches++; true }; f.pump(); assertEquals(1, launches); assertNull(f.storage.bytes); assertEquals(0, f.tokens)
    }
    @Test fun explicitEnforcementCheckDiscardsChallengeWithoutBrowserTokenOrStorage() {
        val f = Fixture(); f.controller.checkEnforcement(); f.pump()
        assertEquals(AbemaAccountProgress.ENFORCEMENT_PROVEN, f.controller.state.value.progress)
        assertEquals(1, f.preparations); assertEquals(0, f.tokens); assertNull(f.storage.bytes); assertFalse(f.controller.state.value.canApprove)
        f.negativeAccepted = true; f.controller.checkEnforcement(); f.pump()
        assertEquals(AbemaAccountProgress.ENFORCEMENT_NOT_PROVEN, f.controller.state.value.progress)
    }
    @Test fun pendingPollingIsPacedFiniteAndSuspendedWhileProviderBrowserOwnsForeground() {
        val f = Fixture(); f.handedOff(); f.controller.setForeground(false); f.controller.continueApproval(); f.pump(); assertEquals(0, f.tokens)
        f.now += 20000; f.controller.setForeground(true); f.controller.continueApproval(); f.pump(); assertEquals(1, f.tokens)
        f.controller.continueApproval(); f.pump(); assertEquals(1, f.tokens)
        f.now += 5000; f.controller.continueApproval(); f.pump(); assertEquals(2, f.tokens)
        repeat(61) { f.now += 5000; f.pump() }
        assertEquals(60, f.tokens); assertFalse(f.controller.state.value.canContinue); assertNull(f.storage.bytes)
    }
    @Test fun unknownRefusalStopsAndProfileMismatchNeverWritesOrRetries() {
        for (mismatch in listOf(false, true)) {
            val f = Fixture(); f.handedOff(); f.profileMismatch = mismatch
            f.results.add(if (mismatch) f.granted() else AbemaAccountTokenResult.Failed(AbemaAccountFailure.REFUSED))
            f.controller.continueApproval(); f.pump(); f.now += 6000; f.pump()
            assertEquals(1, f.tokens); assertNull(f.storage.bytes); assertFalse(f.controller.state.value.canContinue)
        }
    }
    @Test fun ownerOrForegroundLossAndCleanupFailureSuppressLateGrantAndBrowser() {
        for (loss in 0..2) {
            val f = Fixture(); f.handedOff(); f.results.add(f.granted())
            when (loss) { 0 -> f.onToken = { f.matching = false }; 1 -> f.onToken = { f.controller.setForeground(false) }; 2 -> f.closeThrows = true }
            f.controller.continueApproval(); f.pump(); assertNull(f.storage.bytes)
            assertNotEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        }
        val f = Fixture(); f.prepared(); f.matching = false; var launches = 0
        f.controller.handoff { launches++; true }; f.pump(); assertEquals(0, launches)
    }
    @Test fun originalChallengeDeadlineAndForgetInvalidatePendingConnectionWithoutChangingOtherStores() {
        val f = Fixture(); f.handedOff(); val unrelated = byteArrayOf(1, 2, 3)
        f.controller.setForeground(false); f.now = 1000000; f.controller.setForeground(true)
        f.controller.continueApproval(); f.pump(); assertEquals(0, f.tokens); assertFalse(f.controller.state.value.canContinue)
        f.now = 1000; f.handedOff(); f.controller.forget(); f.pump(); f.controller.continueApproval(); f.pump()
        assertEquals(0, f.tokens); assertNull(f.store.read()); assertArrayEquals(byteArrayOf(1, 2, 3), unrelated)
    }
    @Test fun resumeValidatesTheSavedAccountDirectlyWithoutAnotherChallengeOrRejectionProbe() {
        val f = Fixture(); f.handedOff(); f.results.add(f.granted()); f.controller.continueApproval(); f.pump()
        f.controller.setForeground(false); f.controller.setForeground(true)
        assertEquals(AbemaAccountStatus.UNVERIFIED, f.controller.state.value.status)
        val preparations = f.preparations; val profiles = f.profileIds.size
        f.negativeAccepted = true; f.controller.revalidate(); f.pump(); assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        assertEquals(preparations, f.preparations)
        assertEquals(listOf("intended-account"), f.profileIds.drop(profiles))
        assertFalse(f.profileBearers.contains("tachiai-invalid-bearer"))
        f.controller.close(); assertNotNull(f.store.read())
    }
    @Test fun savedValidationRefusesRejectedMismatchedOrLateProfilesWithoutReplacingTheGrant() {
        for (resumed in listOf(false, true)) for (failure in 0..4) {
            val f = Fixture(); f.handedOff(); f.results.add(f.granted()); f.controller.continueApproval(); f.pump()
            val original = f.storage.bytes!!.copyOf()
            if (resumed) { f.controller.setForeground(false); f.controller.setForeground(true) }
            when (failure) {
                0 -> f.profileRejected = true
                1 -> f.profileMismatch = true
                2 -> f.onProfile = { f.matching = false }
                3 -> f.onProfile = { f.wall = f.store.read()!!.grant.expiresAtMs }
                4 -> f.onProfile = { f.controller.setForeground(false) }
            }
            f.controller.revalidate(); f.pump()
            assertNotEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
            assertArrayEquals(original, f.storage.bytes)
            assertFalse(f.profileBearers.contains("tachiai-invalid-bearer"))
        }
    }
    @Test fun connectRequiresNoIdentityAndNeverReadsProtectedOwnershipOnTheCaller() {
        val f = Fixture(); f.controller.load(); f.pump(); val reads = f.ownerReads
        f.controller.setForeground(false); f.controller.connect()
        assertTrue(f.worker.queued.isEmpty()); assertEquals(reads, f.ownerReads)
        f.controller.setForeground(true); f.controller.connect()
        assertEquals(reads, f.ownerReads); assertEquals(1, f.worker.queued.size)
        f.controller.connect(); assertEquals(1, f.worker.queued.size); assertEquals(reads, f.ownerReads)
        f.pump(); assertTrue(f.controller.state.value.canApprove); assertNull(f.storage.bytes)
    }
    @Test fun providerApprovedAccountDefinesTheSavedIdentityWithoutManualInput() {
        val f = Fixture(); f.controller.load(); f.pump()
        val reads = f.ownerReads
        f.controller.connect()
        assertEquals(reads, f.ownerReads) // No protected read on the caller.
        assertEquals(AbemaAccountProgress.PREPARING, f.controller.state.value.progress)
        f.pump(); assertTrue(f.controller.state.value.canApprove)
        f.controller.handoff { true }; f.pump(); f.results.add(f.granted("different-approved-account"))
        f.controller.continueApproval(); f.pump()
        assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        assertEquals("different-approved-account", f.store.read()!!.grant.userId); assertEquals(1, f.tokens)
        assertFalse(f.controller.state.value.toString().contains("different-approved-account"))
    }
    @Test fun returnedGuestIdentityNeverReplacesAnExistingAccountOrRequestsItsProfile() {
        val f = Fixture(); f.handedOff(); f.results.add(f.granted()); f.controller.continueApproval(); f.pump()
        val previous = f.storage.bytes!!.copyOf()
        f.controller.connect(); f.pump(); f.controller.handoff { true }; f.pump()
        val profiles = f.profileIds.size
        f.results.add(f.granted("guest")); f.controller.continueApproval(); f.pump()
        assertFalse(f.controller.state.value.canApprove); assertFalse(f.controller.state.value.canContinue)
        assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status) // The prior valid account remains usable.
        assertEquals(profiles, f.profileIds.size); assertEquals(2, f.tokens)
        assertArrayEquals(previous, f.storage.bytes); assertEquals("intended-account", f.store.read()!!.grant.userId)
    }
    @Test fun queuedApprovalHandoffChecksExpiryBeforeWorkerAndBeforeMainLaunch() {
        for (beforeWorker in listOf(true, false)) {
            val f = Fixture(); f.prepared(); var launches = 0
            f.controller.handoff { launches++; true }
            if (!beforeWorker) assertTrue(f.worker.one())
            f.now = 1000000
            f.pump()
            assertEquals(0, launches); assertFalse(f.controller.state.value.canApprove); assertFalse(f.controller.state.value.canContinue)
            assertEquals(AbemaAccountStatus.RECONNECT_REQUIRED, f.controller.state.value.status); assertNull(f.storage.bytes)
        }
    }
    @Test fun tokenResponseCrossingFiveMinuteWindowCannotPersistEvenBeforeChallengeExpiry() {
        val f = Fixture(); f.handedOff(); f.controller.continueApproval(); f.pump()
        assertEquals(1, f.tokens) // Establish the original polling start at 1000.
        f.now = 300000; f.results.add(f.granted()); f.onToken = { f.now = 301000 }
        f.controller.continueApproval(); f.pump()
        assertEquals(2, f.tokens); assertNull(f.storage.bytes)
        assertEquals(AbemaAccountStatus.RECONNECT_REQUIRED, f.controller.state.value.status)
        assertFalse(f.controller.state.value.canContinue)
    }
    @Test fun queuedPendingPublicationAndContinuationCannotOutliveFiveMinuteWindow() {
        for (beforePublication in listOf(true, false)) {
            val f = Fixture(); f.handedOff(); f.controller.continueApproval(); f.pump()
            f.now = 300000; f.controller.continueApproval(); assertTrue(f.worker.one())
            if (!beforePublication) f.main.removeFirst().invoke()
            f.now = if (beforePublication) 301000 else 305000; f.pump()
            assertEquals(2, f.tokens); assertNull(f.storage.bytes)
            assertFalse(f.controller.state.value.canContinue)
            assertEquals(AbemaAccountStatus.RECONNECT_REQUIRED, f.controller.state.value.status)
        }
    }
    @Test fun heldProtectedWriteCannotBlockActualPauseOrForgetOrPublishLateConnectedState() {
        for (forget in listOf(false, true)) {
            val f = Fixture(); f.handedOff(); f.results.add(f.granted()); f.controller.continueApproval(); f.pump()
            assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
            val previous = f.storage.bytes!!.copyOf()
            f.controller.connect(); f.pump(); f.controller.handoff { true }; f.pump()
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val holdFirst = AtomicBoolean(true)
            val worker = Executors.newSingleThreadExecutor(); val ui = Executors.newSingleThreadExecutor()
            f.storage.writeHook = { if (holdFirst.getAndSet(false)) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            try {
                f.results.add(f.granted()); f.controller.continueApproval()
                val writing = worker.submit<Boolean> { f.worker.one() }; assertTrue(entered.await(1, TimeUnit.SECONDS))
                ui.submit { if (forget) f.controller.forget() else f.controller.setForeground(false) }.get(1, TimeUnit.SECONDS)
                assertNotEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
                release.countDown(); assertTrue(writing.get(2, TimeUnit.SECONDS)); f.pump()
                assertNotEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
                assertFalse(f.controller.state.value.canContinue)
                if (forget) { assertNull(f.store.read()); assertTrue(f.store.usable(f.store.revision())) }
                else {
                    assertArrayEquals(previous, f.storage.bytes)
                    f.controller.setForeground(true); f.pump()
                    assertNotEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
                }
            } finally { release.countDown(); worker.shutdownNow(); ui.shutdownNow() }
        }
    }
}
