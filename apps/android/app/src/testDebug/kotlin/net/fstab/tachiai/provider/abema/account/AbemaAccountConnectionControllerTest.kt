package net.fstab.tachiai.provider.abema.account

import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.abema.AbemaCatalogRouteOwner
import net.fstab.tachiai.platform.network.parseConnectionProfile
import org.junit.Assert.*
import org.junit.Test

class AbemaAccountConnectionControllerTest {
    private class Work : Executor {
        val queued = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queued.add(command) }
        fun one() = queued.removeFirstOrNull()?.let { it.run(); true } ?: false
    }
    private class Fixture(shared: Boolean = false) {
        val storage = MemoryAbemaAccountStorage()
        val id = UUID.randomUUID().toString()
        val store = AbemaAccountGrantStore(id, storage)
        var owner = AbemaCatalogRouteOwner(ProviderInstance(id, PrototypeService.ABEMA, "Fixture"), null)
        var matching = true; var now = 1000L; var wall = 1000000L; var negativeAccepted = false
        var profileMismatch = false; var profileRejected = false; var preparations = 0; var tokens = 0; var closed = 0; var closeThrows = false
        var onToken: () -> Unit = {}
        var onProfile: () -> Unit = {}
        var onEmail: () -> Unit = {}
        var onElapsed: () -> Unit = {}
        var onOwner: () -> Unit = {}
        var onMatch: () -> Unit = {}
        var onClose: () -> Unit = {}
        var profileInconclusive = false
        var profileFailure: AbemaAccountFailure? = null
        var emailResult = AbemaAccountEmailResult.REGISTERED
        var emailChecks = 0
        var emailBearer = fixtureAccountToken()
        var ownerReads = 0
        val profileIds = mutableListOf<String>()
        val profileBearers = mutableListOf<String>()
        val results = ArrayDeque<AbemaAccountTokenResult>()
        val worker = Work(); val cancel = Work(); val main = ArrayDeque<() -> Unit>()
        val inbox = AbemaEmailGrantInbox({ now }, { wall })
        private val later = mutableListOf<Pair<Long, () -> Unit>>()
        val binding = object : AbemaAccountConnectionBinding {
            override val store = this@Fixture.store
            override fun owner(): AbemaCatalogRouteOwner { ownerReads++; onOwner(); return this@Fixture.owner }
            override fun sameOwner(owner: AbemaCatalogRouteOwner): Boolean { ownerReads++; onMatch(); return matching && owner === this@Fixture.owner }
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
                    profileFailure?.let { throw AbemaAccountException(it) }
                    if (profileInconclusive) return AbemaAccountProfileResult.Inconclusive
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
                    assertEquals(emailBearer, bearer); emailChecks++; onEmail(); return emailResult
                }
                override fun cancelActiveRequest() = Unit
                override fun close() { closed++; onClose(); if (closeThrows) throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED) }
            }
        }
        val sharedOwner by lazy {
            val captured = owner
            AbemaAccountAccessOwner(id, captured.proof(), store, { wall }, { onElapsed(); now },
                { binding.sameOwner(captured) }, { gate -> binding.transport(captured, gate) }, { !closeThrows })
        }
        val controller = AbemaAccountConnectionController(binding, worker, cancel, { main.add(it) },
            { delay, action -> later.add(now + delay to action) }, { onElapsed(); now }, { wall }, emailInbox = inbox,
            accountAccess = if (shared) { captured ->
                assertEquals(captured.proof(), sharedOwner.ownerProof)
                sharedOwner.attach()
            } else null)
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
        fun save() {
            store.replace(granted().grant, wall, store.revision()) { true }
        }
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
        fun expiredSnapshot(): AbemaAccountSnapshot { save(); wall = 2_000_001; return store.readSnapshot() }
        fun renewedGrant(id: String = "intended-account", expiry: String = "3000"): AbemaAccountGrant =
            decodedAbemaGrant(fixtureAccountToken(id, expiry), id, wall).also { emailBearer = it.bearer }
        fun renewalOpened(snapshot: AbemaAccountSnapshot, results: MutableList<AbemaAutomaticRenewalResult>): AbemaApprovalBrowserRequest? {
            var request: AbemaApprovalBrowserRequest? = null
            controller.renewEmail(snapshot, { request = it; true }, { results.add(it) }); pump()
            return request
        }
        fun renewalReturned(request: AbemaApprovalBrowserRequest, grant: AbemaAccountGrant) {
            controller.setForeground(false)
            assertTrue(inbox.deliver(checkNotNull(request.emailNonce), request.instanceId, request.ownerProof,
                request.deadlineElapsedMs, grant))
            controller.emailBrowserReturned()
            assertTrue(profileIds.isEmpty())
            controller.setForeground(true); pump()
        }
    }
    @Test fun automaticEmailRenewalPinsExpiredSnapshotAndCommitsOnlySameAccountWithLongerProviderExpiry() {
        val f = Fixture(); val original = f.expiredSnapshot(); val prior = f.storage.bytes!!.copyOf()
        val completed = mutableListOf<AbemaAutomaticRenewalResult>()
        val request = checkNotNull(f.renewalOpened(original, completed))
        assertTrue(request.automaticReturn); assertFalse(request.resetBrowserSession)
        assertEquals(91_000L, request.deadlineElapsedMs); assertArrayEquals(prior, f.storage.bytes)
        val grant = f.renewedGrant()
        f.renewalReturned(request, grant)
        assertEquals(listOf(AbemaAutomaticRenewalResult.SUCCESS), completed)
        val saved = checkNotNull(f.store.read())
        assertEquals(grant.bearer, saved.grant.bearer); assertEquals(grant.expiresAtMs, saved.grant.expiresAtMs)
        assertEquals(checkNotNull(original.record).generation + 1, saved.generation)
        assertEquals(listOf("intended-account"), f.profileIds); assertEquals(1, f.emailChecks)
        assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        f.controller.emailBrowserReturned(); f.controller.cancelAction(); f.controller.close(); f.pump()
        assertEquals(1, completed.size); assertEquals(0, f.tokens); assertEquals(0, f.preparations)
    }

    @Test fun renewalUsesOneClippedOriginalDeadlineForInboxBrowserAndValidation() {
        val f = Fixture(); val original = f.expiredSnapshot(); val completed = mutableListOf<AbemaAutomaticRenewalResult>()
        var request: AbemaApprovalBrowserRequest? = null
        f.controller.renewEmail(original, { request = it; true }, { completed.add(it) }, originalDeadlineElapsedMs = 6000)
        f.now = 4000; f.pump()
        val browser = checkNotNull(request)
        assertEquals(6000L, browser.deadlineElapsedMs)
        val grant = f.renewedGrant()
        assertFalse(f.inbox.deliver(checkNotNull(browser.emailNonce), browser.instanceId, browser.ownerProof, 91000, grant))
        f.renewalReturned(browser, grant)
        assertEquals(listOf(AbemaAutomaticRenewalResult.SUCCESS), completed)
        assertEquals(1, f.profileIds.size); assertEquals(1, f.emailChecks)
    }

    @Test fun expiredQueuedOrMainDelayedRenewalNeverLaunchesOrExtendsItsOriginalBudget() {
        for (phase in 0..2) {
            val f = Fixture(); val original = f.expiredSnapshot(); val before = f.storage.bytes!!.copyOf()
            val completed = mutableListOf<AbemaAutomaticRenewalResult>(); var launches = 0
            val ownerReads = f.ownerReads
            f.controller.renewEmail(original, { launches++; true }, { completed.add(it) },
                originalDeadlineElapsedMs = if (phase == 0) f.now else 6000)
            if (phase == 2) assertTrue(f.worker.one())
            f.now = 6000; f.pump()
            assertEquals(0, launches); assertEquals(listOf(AbemaAutomaticRenewalResult.INTERACTION_REQUIRED), completed)
            assertTrue(f.profileIds.isEmpty()); assertArrayEquals(before, f.storage.bytes)
            if (phase < 2) assertEquals(ownerReads, f.ownerReads)
        }
    }

    @Test fun cancellationBeforePreparationAndAfterCommitCannotDeliverAStaleSuccess() {
        for (phase in 0..1) {
            val f = Fixture(); val original = f.expiredSnapshot(); val completed = mutableListOf<AbemaAutomaticRenewalResult>()
            if (phase == 0) {
                val reads = f.ownerReads; val before = f.storage.bytes!!.copyOf()
                f.controller.renewEmail(original, { fail("Cancelled renewal launched"); false }, { completed.add(it) })
                f.controller.close(); f.pump()
                assertEquals(reads, f.ownerReads); assertArrayEquals(before, f.storage.bytes)
            } else {
                val request = checkNotNull(f.renewalOpened(original, completed)); val grant = f.renewedGrant()
                assertTrue(f.inbox.deliver(checkNotNull(request.emailNonce), request.instanceId, request.ownerProof,
                    request.deadlineElapsedMs, grant))
                f.controller.emailBrowserReturned(); assertTrue(f.worker.one())
                assertEquals(grant.bearer, checkNotNull(f.store.read()).grant.bearer)
                // Completion is queued after the durable replacement. A later
                // Forget revokes the replacement before success publication.
                f.controller.forget(); f.pump(); assertNull(f.store.read())
            }
            assertEquals(listOf(AbemaAutomaticRenewalResult.CANCELLED), completed)
        }
    }

    @Test fun queuedPostcommitPublicationAndCompletionCannotSucceedPastTheOriginalDeadline() {
        for (phase in 0..2) {
            val f = Fixture(); val original = f.expiredSnapshot(); val completed = mutableListOf<AbemaAutomaticRenewalResult>()
            val request = checkNotNull(f.renewalOpened(original, completed)); val grant = f.renewedGrant()
            assertTrue(f.inbox.deliver(checkNotNull(request.emailNonce), request.instanceId, request.ownerProof,
                request.deadlineElapsedMs, grant))
            f.controller.emailBrowserReturned(); assertTrue(f.worker.one())
            assertEquals(grant.bearer, checkNotNull(f.store.read()).grant.bearer)
            if (phase >= 1) checkNotNull(f.main.removeFirstOrNull()).invoke()
            assertTrue(completed.isEmpty())
            if (phase == 2) {
                var checks = 0
                f.onElapsed = { if (++checks == 2) f.now = request.deadlineElapsedMs }
            } else f.now = request.deadlineElapsedMs
            f.pump()
            assertEquals(listOf(if (phase == 0) AbemaAutomaticRenewalResult.INTERACTION_REQUIRED
                else AbemaAutomaticRenewalResult.CANCELLED), completed)
            assertEquals(1, f.profileIds.size); assertEquals(1, f.emailChecks)
        }
    }

    @Test fun wrongAccountNonextendingExpiredOrMalformedCandidatesNeverReachNetworkOrWrite() {
        val f = Fixture(); val expected = f.expiredSnapshot(); val before = f.storage.bytes!!.copyOf()
        val completed = mutableListOf<AbemaAutomaticRenewalResult>()
        val request = checkNotNull(f.renewalOpened(expected, completed))
        f.renewalReturned(request, f.renewedGrant("other"))
        assertEquals(listOf(AbemaAutomaticRenewalResult.INTERACTION_REQUIRED), completed)
        assertTrue(f.profileIds.isEmpty()); assertEquals(0, f.emailChecks); assertArrayEquals(before, f.storage.bytes)
        val old = checkNotNull(expected.record).grant
        assertFalse(abemaRenewalCandidateExtends(expected, old, f.wall))
        assertFalse(abemaRenewalCandidateExtends(expected, AbemaAccountGrant(old.userId, "invalid", 3_000_000), f.wall))
        val renewed = f.renewedGrant()
        assertFalse(abemaRenewalCandidateExtends(expected, renewed, renewed.expiresAtMs))
    }

    @Test fun automaticReturnWithoutGrantRequiresInteractionWhileManualReturnDoesNotAutoFinish() {
        val f = Fixture(); val expected = f.expiredSnapshot(); val before = f.storage.bytes!!.copyOf()
        val completed = mutableListOf<AbemaAutomaticRenewalResult>()
        checkNotNull(f.renewalOpened(expected, completed))
        f.controller.emailBrowserReturned(); f.controller.emailBrowserReturned(); f.pump()
        assertEquals(listOf(AbemaAutomaticRenewalResult.INTERACTION_REQUIRED), completed)
        assertTrue(f.profileIds.isEmpty()); assertArrayEquals(before, f.storage.bytes)
        val manual = Fixture(); val request = manual.emailOpened()
        assertFalse(request.automaticReturn); assertFalse(request.resetBrowserSession)
        manual.controller.emailBrowserReturned(); manual.pump()
        assertTrue(manual.profileIds.isEmpty()); assertFalse(manual.controller.state.value.canContinue)
    }

    @Test fun obsoleteOrInconsistentRenewalSnapshotCannotLaunchABrowser() {
        for (change in 0..5) {
            val f = Fixture(); val original = f.expiredSnapshot(); val old = checkNotNull(original.record)
            val expected = when (change) {
                0 -> { f.store.forget(); original }
                1 -> AbemaAccountSnapshot(AbemaStoredAccount(old.grant, old.acceptedWallMs + 1, old.generation), original.revision)
                2 -> AbemaAccountSnapshot(AbemaStoredAccount(old.grant, old.acceptedWallMs, old.generation + 1), original.revision)
                3 -> { f.wall = old.acceptedWallMs - 1; original }
                4 -> { f.matching = false; original }
                else -> AbemaAccountSnapshot(AbemaStoredAccount(AbemaAccountGrant(old.grant.userId, old.grant.bearer,
                    old.grant.expiresAtMs + 1), old.acceptedWallMs, old.generation), original.revision)
            }
            val before = f.storage.bytes!!.copyOf(); val completed = mutableListOf<AbemaAutomaticRenewalResult>()
            assertNull(f.renewalOpened(expected, completed))
            assertEquals(listOf(AbemaAutomaticRenewalResult.CANCELLED), completed)
            assertArrayEquals(before, f.storage.bytes); assertTrue(f.profileIds.isEmpty())
        }
    }

    @Test fun renewalCancelForgetCloseOwnerLossOrOriginalDeadlineCannotResurrect() {
        for (loss in 0..4) {
            val f = Fixture(); val expected = f.expiredSnapshot(); val completed = mutableListOf<AbemaAutomaticRenewalResult>()
            val request = checkNotNull(f.renewalOpened(expected, completed)); val before = f.storage.bytes!!.copyOf()
            val grant = f.renewedGrant()
            when (loss) {
                0 -> f.controller.cancelAction()
                1 -> f.controller.close()
                2 -> f.controller.forget()
                3 -> f.matching = false
                4 -> f.now = request.deadlineElapsedMs
            }
            if (loss <= 2) assertFalse(f.inbox.deliver(checkNotNull(request.emailNonce), request.instanceId,
                request.ownerProof, request.deadlineElapsedMs, grant))
            else if (loss == 3) assertTrue(f.inbox.deliver(checkNotNull(request.emailNonce), request.instanceId,
                request.ownerProof, request.deadlineElapsedMs, grant))
            f.controller.emailBrowserReturned(); f.pump(); f.controller.finishEmailConnection(); f.pump()
            assertEquals(1, completed.size)
            assertEquals(if (loss == 4) AbemaAutomaticRenewalResult.INTERACTION_REQUIRED else AbemaAutomaticRenewalResult.CANCELLED,
                completed.single())
            assertTrue(f.profileIds.isEmpty()); if (loss == 2) assertNull(f.store.read()) else assertArrayEquals(before, f.storage.bytes)
        }
    }

    @Test fun foregroundOrForgetDuringRenewalValidationCancelsWithoutAnotherAttempt() {
        for (loss in 0..2) {
            val f = Fixture(); val expected = f.expiredSnapshot(); val before = f.storage.bytes!!.copyOf()
            val completed = mutableListOf<AbemaAutomaticRenewalResult>(); val request = checkNotNull(f.renewalOpened(expected, completed))
            when (loss) {
                0 -> f.onProfile = { f.controller.setForeground(false) }
                1 -> f.onProfile = { f.store.forget() }
                2 -> f.onEmail = { f.matching = false }
            }
            f.renewalReturned(request, f.renewedGrant())
            assertEquals(listOf(AbemaAutomaticRenewalResult.CANCELLED), completed)
            f.controller.setForeground(true); f.pump()
            assertNotEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
            if (loss == 1) assertNull(f.store.read()) else assertArrayEquals(before, f.storage.bytes)
        }
    }

    @Test fun transientCleanupAndStorageFailuresRemainTypedAndDoNotClearTheOldGrant() {
        for (failure in 0..3) {
            val f = Fixture(); val expected = f.expiredSnapshot(); val before = f.storage.bytes!!.copyOf()
            val completed = mutableListOf<AbemaAutomaticRenewalResult>(); val request = checkNotNull(f.renewalOpened(expected, completed))
            when (failure) {
                0 -> f.profileInconclusive = true
                1 -> f.emailResult = AbemaAccountEmailResult.INCONCLUSIVE
                2 -> f.closeThrows = true
                3 -> f.storage.failWrite = true
            }
            f.renewalReturned(request, f.renewedGrant())
            assertEquals(listOf(if (failure == 3) AbemaAutomaticRenewalResult.STORAGE_FAILURE
                else AbemaAutomaticRenewalResult.TEMPORARY_FAILURE), completed)
            assertArrayEquals(before, f.storage.bytes); assertNotEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        }
    }

    @Test fun renewalWriteRaceAndClockRollbackCannotPublishSuccessOrReplaceSavedBytes() {
        for (race in 0..3) {
            val f = Fixture(); val expected = f.expiredSnapshot(); val before = f.storage.bytes!!.copyOf()
            val completed = mutableListOf<AbemaAutomaticRenewalResult>(); val request = checkNotNull(f.renewalOpened(expected, completed))
            when (race) {
                0 -> f.storage.writeHook = { f.storage.writeHook = {}; f.controller.setForeground(false) }
                1 -> f.onProfile = { f.wall -= 1 }
                2 -> f.onProfile = { f.now -= 1 }
                3 -> f.storage.writeHook = { f.storage.writeHook = {}; f.matching = false }
            }
            f.renewalReturned(request, f.renewedGrant())
            assertEquals(listOf(AbemaAutomaticRenewalResult.CANCELLED), completed)
            assertArrayEquals(before, f.storage.bytes)
            assertNotEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        }
    }

    @Test fun sharedSavedValidationKeepsForegroundProofForPeersAndReusesItWithoutMutatingTheGrant() {
        val f = Fixture(shared = true); f.save(); val bytes = f.storage.bytes!!.copyOf()
        f.controller.load(); f.pump()
        assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        val peer = f.sharedOwner.attach()
        try {
            val lease = (peer.acquire() as AbemaAccountAccessResult.Account).lease
            assertTrue(lease.current()); assertEquals(1, f.profileIds.size); assertEquals(1, f.emailChecks)
            f.controller.revalidate(); f.pump()
            assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
            f.controller.setForeground(false); f.pump()
            assertEquals(AbemaAccountStatus.PAUSED, f.controller.state.value.status)
            assertTrue(lease.current())
            f.controller.setForeground(true); f.pump()
            assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
            assertTrue(lease.checkWorker()); assertEquals(1, f.profileIds.size); assertEquals(1, f.emailChecks)
            assertArrayEquals(bytes, f.storage.bytes); assertEquals(0, f.tokens); assertEquals(0, f.preparations)
        } finally { f.controller.close(); peer.close() }
        assertTrue(f.sharedOwner.cleanupConfirmed)
    }

    @Test fun sharedControllerLastPauseReleasesProofAndResumePerformsOneFreshValidation() {
        val f = Fixture(shared = true); f.save(); val bytes = f.storage.bytes!!.copyOf()
        try {
            f.controller.load(); f.pump(); assertEquals(1, f.profileIds.size)
            f.controller.setForeground(false); f.pump(); assertTrue(f.sharedOwner.cleanupConfirmed)
            f.controller.setForeground(true); f.controller.setForeground(true); f.pump()
            assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
            assertEquals(2, f.profileIds.size); assertEquals(2, f.emailChecks); assertArrayEquals(bytes, f.storage.bytes)
        } finally { f.controller.close() }
    }

    @Test fun pausingSharedValidationReleasesControllerWorkerWhileTheHealthyPeerKeepsItsFlight() {
        val f = Fixture(shared = true); f.save(); val bytes = f.storage.bytes!!.copyOf()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.onProfile = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val peer = f.sharedOwner.attach(); val executor = Executors.newFixedThreadPool(2)
        try {
            f.controller.load()
            val controllerJob = executor.submit<Boolean> { f.worker.one() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val peerJob = executor.submit<AbemaAccountAccessResult> { peer.acquire() }
            f.controller.setForeground(false)
            assertTrue(controllerJob.get(1, TimeUnit.SECONDS))
            f.pump(); assertEquals(AbemaAccountStatus.PAUSED, f.controller.state.value.status)
            assertFalse(f.sharedOwner.cleanupConfirmed)
            release.countDown()
            val lease = (peerJob.get(3, TimeUnit.SECONDS) as AbemaAccountAccessResult.Account).lease
            assertTrue(lease.current()); assertEquals(1, f.profileIds.size); assertEquals(1, f.emailChecks)
            f.pump(); assertEquals(AbemaAccountStatus.PAUSED, f.controller.state.value.status)
            assertArrayEquals(bytes, f.storage.bytes)
        } finally { release.countDown(); f.controller.close(); peer.close(); executor.shutdownNow() }
    }

    @Test fun sharedSavedValidationRefusesProviderFailuresForgetAndLateAdmissionWithoutGuestFallback() {
        for (failure in listOf("profile", "email", "cleanup", "timeout", "forget", "pause")) {
            val f = Fixture(shared = true); f.save(); val bytes = f.storage.bytes!!.copyOf()
            when (failure) {
                "profile" -> f.profileRejected = true
                "email" -> f.emailResult = AbemaAccountEmailResult.UNREGISTERED
                "cleanup" -> f.closeThrows = true
                "timeout" -> f.onProfile = { f.now += 30_000 }
                "forget" -> f.onProfile = { f.store.forget() }
                "pause" -> f.onProfile = { f.controller.setForeground(false) }
            }
            try {
                f.controller.load(); f.pump()
                assertNotEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
                assertEquals(1, f.profileIds.size); assertEquals(if (failure in listOf("email", "cleanup")) 1 else 0, f.emailChecks)
                if (failure == "forget") assertNull(f.store.read()) else assertArrayEquals(bytes, f.storage.bytes)
                assertEquals(0, f.tokens); assertEquals(0, f.preparations)
            } finally { f.controller.close() }
        }
    }

    @Test fun sharedSavedValidationCannotRestartItsBudgetAfterTheCapturedOwnerRead() {
        val f = Fixture(shared = true); f.save(); val bytes = f.storage.bytes!!.copyOf()
        f.onOwner = { f.now += 30_000 }
        try {
            f.controller.load(); f.pump()
            assertEquals(AbemaAccountStatus.TEMPORARY, f.controller.state.value.status)
            assertTrue(f.profileIds.isEmpty()); assertEquals(0, f.emailChecks); assertArrayEquals(bytes, f.storage.bytes)
        } finally { f.controller.close() }
    }

    @Test fun explicitEmailOrQrReplacementWithoutPauseDoesNotReuseThePriorSharedLeaseAtPublication() {
        for (method in listOf("email", "qr")) {
            val f = Fixture(shared = true); f.save(); val bytes = f.storage.bytes!!.copyOf()
            f.controller.load(); f.pump()
            assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
            val prior = checkNotNull(f.store.read())
            try {
                if (method == "email") {
                    var request: AbemaApprovalBrowserRequest? = null
                    f.controller.connectEmail { request = it; true }; f.pump()
                    assertArrayEquals(bytes, f.storage.bytes)
                    val returned = checkNotNull(request)
                    assertTrue(f.inbox.deliver(checkNotNull(returned.emailNonce), returned.instanceId, returned.ownerProof,
                        returned.deadlineElapsedMs, f.granted().grant))
                    f.controller.emailBrowserReturned(); f.pump()
                } else {
                    f.controller.connect(); f.pump(); f.controller.handoff { true }; f.pump()
                    assertArrayEquals(bytes, f.storage.bytes)
                    f.results.add(f.granted()); f.controller.continueApproval(); f.pump()
                }
                assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
                val current = checkNotNull(f.store.read())
                assertEquals(prior.generation + 1, current.generation)
                assertEquals(prior.grant.userId, current.grant.userId)
                assertFalse(bytes.contentEquals(f.storage.bytes))
                assertFalse(f.controller.state.value.canContinue)
                assertEquals(2, f.profileIds.size)
                assertEquals(if (method == "email") 2 else 1, f.emailChecks)
                assertEquals(if (method == "qr") 1 else 0, f.tokens)
            } finally { f.controller.close() }
        }
    }
    @Test fun emailSetupUsesTheProviderBrowserAndSavesOnlyAfterNativeIdentityAndRegistrationChecks() {
        val f = Fixture(); val request = f.emailOpened()
        assertEquals("https://abema.tv/account", request.verificationUri)
        assertFalse(request.resetBrowserSession)
        assertEquals(0, f.preparations); assertEquals(0, f.tokens); assertTrue(f.profileIds.isEmpty())
        assertNull(f.storage.bytes); assertFalse(f.controller.state.value.canApprove)
        f.emailReturned(request); assertNull(f.storage.bytes)
        f.controller.finishEmailConnection(); f.pump()
        assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        assertEquals("intended-account", f.store.read()!!.grant.userId)
        assertEquals(listOf("intended-account"), f.profileIds); assertEquals(1, f.emailChecks)
        assertFalse(f.controller.state.value.canContinue); assertEquals(0, f.tokens)
    }

    @Test fun returnedRememberedLoginCompletesOnceAfterForegroundWithoutAnotherChallengeOrManualFinish() {
        val f = Fixture(); val request = f.emailOpened()
        f.controller.setForeground(false)
        assertTrue(f.inbox.deliver(checkNotNull(request.emailNonce), request.instanceId, request.ownerProof,
            request.deadlineElapsedMs, f.granted().grant))
        f.controller.emailBrowserReturned(); f.controller.emailBrowserReturned(); f.pump()
        assertNull(f.storage.bytes); assertTrue(f.profileIds.isEmpty()); assertEquals(0, f.emailChecks)
        f.controller.setForeground(true); f.pump()
        assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        assertEquals(listOf("intended-account"), f.profileIds); assertEquals(1, f.emailChecks)
        assertEquals(0, f.preparations); assertEquals(0, f.tokens)
        f.controller.emailBrowserReturned(); f.controller.setForeground(true); f.pump()
        assertEquals(1, f.profileIds.size); assertEquals(1, f.emailChecks)
    }

    @Test fun browserReturnDoesNotBypassForgetOwnerDeadlineOrRegistrationAdmission() {
        for (failure in 0..3) {
            val f = Fixture(); val request = f.emailOpened(); f.controller.setForeground(false)
            assertTrue(f.inbox.deliver(checkNotNull(request.emailNonce), request.instanceId, request.ownerProof,
                request.deadlineElapsedMs, f.granted().grant))
            when (failure) {
                0 -> f.store.forget()
                1 -> f.matching = false
                2 -> f.now = request.deadlineElapsedMs
                3 -> f.emailResult = AbemaAccountEmailResult.UNREGISTERED
            }
            f.controller.emailBrowserReturned(); f.controller.setForeground(true); f.pump()
            assertNull(f.store.read()); assertNotEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
            assertEquals(0, f.preparations); assertEquals(0, f.tokens)
        }
    }

    @Test fun explicitFreshSignInRequestsOnlyBrowserResetAndPreservesThePriorNativeGrantBeforeReplacement() {
        val f = Fixture(); f.save(); val original = f.storage.bytes!!.copyOf()
        f.controller.load(); f.pump()
        var request: AbemaApprovalBrowserRequest? = null
        f.controller.signInAgain { request = it; true }; f.pump()
        assertTrue(checkNotNull(request).resetBrowserSession)
        assertNotNull(checkNotNull(request).emailNonce)
        assertArrayEquals(original, f.storage.bytes)
        f.controller.cancelAction(); f.pump()
        assertArrayEquals(original, f.storage.bytes)
        assertEquals(0, f.preparations); assertEquals(0, f.tokens)
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
        val preparations = f.preparations; val profiles = f.profileIds.size
        f.controller.setForeground(false); f.controller.setForeground(true)
        assertEquals(AbemaAccountStatus.UNVERIFIED, f.controller.state.value.status)
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
        f.pump() // Automatic missing-account read is separate from explicit Connect.
        val resumedReads = f.ownerReads
        f.controller.connect()
        assertEquals(resumedReads, f.ownerReads); assertEquals(1, f.worker.queued.size)
        f.controller.connect(); assertEquals(1, f.worker.queued.size); assertEquals(resumedReads, f.ownerReads)
        f.pump(); assertTrue(f.controller.state.value.canApprove); assertNull(f.storage.bytes)
    }
    @Test fun startupAndResumeValidateAutomaticallyWithoutChangingProtectedGrantOrRequestingConsent() {
        val f = Fixture(); f.save(); val before = f.storage.bytes!!.copyOf(); val revision = f.store.revision()
        f.controller.load(); f.pump()
        assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        assertEquals(1, f.profileIds.size); assertEquals(1, f.emailChecks)
        f.controller.setForeground(false); f.controller.setForeground(true); f.pump()
        assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        assertEquals(2, f.profileIds.size); assertEquals(2, f.emailChecks)
        assertEquals(0, f.preparations); assertEquals(0, f.tokens)
        assertFalse(f.profileBearers.contains("tachiai-invalid-bearer"))
        assertEquals(revision, f.store.revision()); assertArrayEquals(before, f.storage.bytes)
    }
    @Test fun duplicateForegroundAndBusyManualValidationCoalesceWithoutRevokingTheCompletedSession() {
        val f = Fixture(); f.save(); f.controller.load()
        f.controller.load(); f.controller.revalidate(); f.controller.setForeground(true)
        assertEquals(1, f.worker.queued.size); assertEquals(0, f.ownerReads)
        f.pump(); assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        f.controller.setForeground(true); f.pump()
        assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        assertEquals(1, f.profileIds.size); assertEquals(1, f.emailChecks)
    }
    @Test fun automaticValidationSkipsMissingExpiredAndPendingForgetRecordsBeforeRequests() {
        for (kind in 0..2) {
            val f = Fixture()
            if (kind != 0) f.save()
            if (kind == 1) f.wall = f.store.read()!!.grant.expiresAtMs
            if (kind == 2) f.storage.pending = true
            val before = f.storage.bytes?.copyOf()
            f.controller.load(); f.pump()
            assertTrue(f.profileIds.isEmpty()); assertEquals(0, f.emailChecks)
            assertEquals(if (kind == 1) AbemaAccountStatus.RECONNECT_REQUIRED else AbemaAccountStatus.MISSING,
                f.controller.state.value.status)
            assertArrayEquals(before, f.storage.bytes)
        }
    }
    @Test fun automaticMaintenancePreservesReadyEmailAndPendingQrApproval() {
        val email = Fixture(); email.save(); val request = email.emailOpened()
        val profiles = email.profileIds.size; val checks = email.emailChecks
        email.emailReturned(request)
        email.controller.load(); email.controller.revalidate(); email.pump()
        assertEquals(profiles, email.profileIds.size); assertEquals(checks, email.emailChecks)
        assertTrue(email.controller.state.value.canContinue)
        email.controller.finishEmailConnection(); email.pump()
        assertEquals(AbemaAccountStatus.CONNECTED, email.controller.state.value.status)
        val qr = Fixture(); qr.save(); qr.handedOff(); val qrProfiles = qr.profileIds.size
        qr.controller.setForeground(false); qr.controller.setForeground(true)
        qr.controller.load(); qr.controller.revalidate(); qr.pump()
        assertEquals(qrProfiles, qr.profileIds.size); assertTrue(qr.controller.state.value.canContinue)
        assertEquals(0, qr.tokens)
    }
    @Test fun savedValidationRecapturesChangedRouteBeforeCandidateAdmissionAndReadsOnlyOnWorker() {
        val f = Fixture(); f.save(); f.controller.load(); f.pump()
        val original = f.storage.bytes!!.copyOf()
        f.controller.setForeground(false)
        f.owner = AbemaCatalogRouteOwner(f.owner.instance.copy(setup = ProviderSetup(SourceRouteChoice(
            SourceRouteMode.SAVED_CONNECTION, UUID.randomUUID().toString(), "New route"))),
            parseConnectionProfile("http://proxy.example.test:3128".toByteArray()))
        val reads = f.ownerReads
        f.controller.setForeground(true); assertEquals(reads, f.ownerReads)
        f.pump(); assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        assertEquals(2, f.profileIds.size); assertArrayEquals(original, f.storage.bytes)
    }
    @Test fun transientValidationFailuresPreserveSavedGrantAndRecoverOnTheNextResumeWithoutRetryLoop() {
        for (kind in 0..2) {
            val f = Fixture(); f.save(); val original = f.storage.bytes!!.copyOf()
            when (kind) {
                0 -> f.profileInconclusive = true
                1 -> f.emailResult = AbemaAccountEmailResult.INCONCLUSIVE
                2 -> f.profileFailure = AbemaAccountFailure.TIME_LIMIT_REACHED
            }
            f.controller.load(); f.pump()
            assertEquals(AbemaAccountStatus.TEMPORARY, f.controller.state.value.status)
            assertEquals(1, f.profileIds.size); assertArrayEquals(original, f.storage.bytes)
            f.profileInconclusive = false; f.emailResult = AbemaAccountEmailResult.REGISTERED; f.profileFailure = null
            f.controller.setForeground(false); f.controller.setForeground(true); f.pump()
            assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
            assertEquals(2, f.profileIds.size); assertArrayEquals(original, f.storage.bytes)
        }
    }
    @Test fun oneValidationDeadlineIncludesBothRequestsOwnerReadsAndCleanupWithoutPublishingLateSuccess() {
        for (kind in 0..5) {
            val f = Fixture(); f.save(); val original = f.storage.bytes!!.copyOf()
            when (kind) {
                0 -> f.onProfile = { f.now += 30_000 }
                1 -> { f.onProfile = { f.now += 20_000 }; f.onEmail = { f.now += 10_000 } }
                2 -> f.onOwner = { f.now += 30_000 }
                3 -> { f.profileRejected = true; f.onClose = { f.now += 30_000 } }
                4 -> f.onMatch = {
                    val session = AbemaAccountConnectionController::class.java.getDeclaredField("session")
                        .apply { isAccessible = true }.get(f.controller) as AbemaAccountSession
                    if (session.status() == AbemaAccountStatus.CONNECTED) f.now += 30_000
                }
                5 -> f.onProfile = { f.now-- }
            }
            f.controller.load(); f.pump()
            assertEquals(AbemaAccountStatus.TEMPORARY, f.controller.state.value.status)
            assertArrayEquals(original, f.storage.bytes); assertTrue(f.worker.queued.isEmpty())
            if (kind in setOf(0, 2, 3, 5)) assertEquals(0, f.emailChecks)
        }
        for (invalid in listOf(-1L, Long.MAX_VALUE - 29_999)) {
            val f = Fixture(); f.save(); f.now = invalid; f.controller.load(); f.pump()
            assertEquals(AbemaAccountStatus.TEMPORARY, f.controller.state.value.status)
            assertTrue(f.profileIds.isEmpty())
        }
    }
    @Test fun automaticValidationCannotPublishAfterPauseForgetReplacementOrCleanupFailure() {
        for (kind in 0..3) {
            val f = Fixture(); f.save(); val original = f.storage.bytes!!.copyOf()
            f.onProfile = { when (kind) {
                0 -> f.controller.setForeground(false)
                1 -> f.controller.forget()
                2 -> f.store.replace(f.granted("other").grant, f.wall, f.store.revision()) { true }
                3 -> f.closeThrows = true
            } }
            f.controller.load(); f.pump()
            assertNotEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
            assertEquals(0, f.emailChecks)
            if (kind in setOf(0, 3)) assertArrayEquals(original, f.storage.bytes)
            if (kind == 1) assertNull(f.store.read())
            if (kind == 2) assertEquals("other", f.store.read()!!.grant.userId)
        }
    }
    @Test fun actualGrantExpiryDuringValidationRequiresReconnectWithoutErasingTheRecord() {
        val f = Fixture(); f.save(); val original = f.storage.bytes!!.copyOf()
        val expiry = f.store.read()!!.grant.expiresAtMs
        f.onProfile = { f.wall = expiry }
        f.controller.load(); f.pump()
        assertEquals(AbemaAccountStatus.RECONNECT_REQUIRED, f.controller.state.value.status)
        assertEquals(1, f.profileIds.size); assertEquals(0, f.emailChecks)
        assertArrayEquals(original, f.storage.bytes)
    }
    @Test fun queuedCheckingAndSuccessCannotOverwriteALaterPause() {
        val f = Fixture(); f.save(); f.controller.load()
        assertTrue(f.worker.one()) // CHECKING and final publication remain queued.
        f.controller.setForeground(false); f.pump()
        assertEquals(AbemaAccountStatus.PAUSED, f.controller.state.value.status)
        assertEquals(AbemaAccountProgress.IDLE, f.controller.state.value.progress)
        assertFalse(f.controller.state.value.busy)
        f.controller.setForeground(true); f.pump()
        assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
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
                    val profiles = f.profileIds.size
                    f.controller.setForeground(true); f.pump()
                    // The replacement's retained provider handoff is still
                    // pending; automatic maintenance must not displace it.
                    assertNotEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
                    assertEquals(profiles, f.profileIds.size)
                }
            } finally { release.countDown(); worker.shutdownNow(); ui.shutdownNow() }
        }
    }
}
