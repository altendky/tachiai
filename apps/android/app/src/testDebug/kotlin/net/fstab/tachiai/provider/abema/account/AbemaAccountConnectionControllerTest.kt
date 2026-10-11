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
        var profileMismatch = false; var preparations = 0; var tokens = 0; var closed = 0; var closeThrows = false
        var onToken: () -> Unit = {}
        var ownerReads = 0
        val results = ArrayDeque<AbemaAccountTokenResult>()
        val worker = Work(); val cancel = Work(); val main = ArrayDeque<() -> Unit>()
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
                    if (bearer == "tachiai-invalid-bearer" && !negativeAccepted) return AbemaAccountProfileResult.Rejected
                    return AbemaAccountProfileResult.Identity(if (profileMismatch && id != "guest") "other" else id)
                }
                override fun token(challenge: AbemaAccountChallenge, expectedId: String): AbemaAccountTokenResult {
                    assertEquals("intended-account", expectedId); tokens++; onToken()
                    return results.removeFirstOrNull() ?: AbemaAccountTokenResult.Pending
                }
                override fun cancelActiveRequest() = Unit
                override fun close() { closed++; if (closeThrows) throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED) }
            }
        }
        val controller = AbemaAccountConnectionController(binding, worker, cancel, { main.add(it) },
            { delay, action -> later.add(now + delay to action) }, { now }, { wall })
        fun pump() {
            repeat(300) {
                var did = worker.one() || cancel.one()
                main.removeFirstOrNull()?.let { it(); did = true }
                val task = later.firstOrNull { it.first <= now }
                if (task != null) { later.remove(task); task.second(); did = true }
                if (!did) return
            }; error("Fixture did not settle")
        }
        fun prepared() { controller.load(); pump(); controller.connect("intended-account"); pump(); assertTrue(controller.state.value.canApprove) }
        fun handedOff() { prepared(); var launched = false; controller.handoff { launched = true; assertTrue(validAbemaVerificationUri(it)); true }; pump(); assertTrue(launched) }
        fun granted() = AbemaAccountTokenResult.Granted(decodedAbemaGrant(fixtureAccountToken(), "intended-account", wall))
    }
    @Test fun completeProviderControlledFlowPersistsOnlyMatchingPositiveGrantAfterPrivateHandoff() {
        val f = Fixture(); f.handedOff(); assertNull(f.storage.bytes)
        f.controller.setForeground(false); f.now += 10000; f.controller.setForeground(true)
        f.results.add(f.granted()); f.controller.continueApproval(); f.pump()
        assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        assertEquals("intended-account", f.store.read()?.grant?.userId); assertEquals(1, f.tokens)
        assertFalse(f.controller.state.value.toString().contains("intended-account")); assertFalse(f.controller.state.value.toString().contains("private-code"))
    }
    @Test fun positiveProfileAloneCannotPermitHandoffOrPersistence() {
        val f = Fixture(); f.negativeAccepted = true; f.controller.load(); f.pump()
        f.controller.connect("intended-account"); f.pump()
        assertFalse(f.controller.state.value.canApprove); assertEquals(AbemaAccountStatus.NOT_VERIFIED, f.controller.state.value.status)
        var launches = 0; f.controller.handoff { launches++; true }; f.pump(); assertEquals(0, launches); assertNull(f.storage.bytes); assertEquals(0, f.tokens)
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
    @Test fun recreationAndResumeRequireNewEnforcementBeforeSavedAccountCanBeConnected() {
        val f = Fixture(); f.handedOff(); f.results.add(f.granted()); f.controller.continueApproval(); f.pump()
        f.controller.setForeground(false); f.controller.setForeground(true)
        assertEquals(AbemaAccountStatus.UNVERIFIED, f.controller.state.value.status)
        f.negativeAccepted = true; f.controller.revalidate(); f.pump(); assertNotEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        f.negativeAccepted = false; f.controller.revalidate(); f.pump(); assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        f.controller.close(); assertNotNull(f.store.read())
    }
    @Test fun invalidConnectWithCurrentLeaseIsPureLocalGuardWithoutOwnerReadOrWorkerDispatch() {
        val f = Fixture(); f.handedOff(); f.results.add(f.granted()); f.controller.continueApproval(); f.pump()
        assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        val reads = f.ownerReads; val previous = f.storage.bytes!!.copyOf()
        for (invalid in listOf("invalid account ID", "  ", "a".repeat(257), " fixture/account ")) {
            f.controller.connect(invalid)
            assertEquals(reads, f.ownerReads); assertTrue(f.worker.queued.isEmpty())
            assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status); assertArrayEquals(previous, f.storage.bytes)
        }
    }
    @Test fun normalizedOwnerInputIsTheExactExpectedIdentityThroughoutApprovalAndGrantAcceptance() {
        val f = Fixture(); f.controller.load(); f.pump()
        val reads = f.ownerReads
        f.controller.connect(" \tintended-account\r\n ")
        assertEquals(reads, f.ownerReads) // No protected read on the caller.
        assertEquals(AbemaAccountProgress.PREPARING, f.controller.state.value.progress)
        f.pump(); assertTrue(f.controller.state.value.canApprove)
        f.controller.handoff { true }; f.pump(); f.results.add(f.granted())
        f.controller.continueApproval(); f.pump()
        assertEquals(AbemaAccountStatus.CONNECTED, f.controller.state.value.status)
        assertEquals("intended-account", f.store.read()!!.grant.userId); assertEquals(1, f.tokens)
        assertFalse(f.controller.state.value.toString().contains("intended-account"))
    }
    @Test fun normalizedGuestIdentityStillCannotStartAnExistingAccountApproval() {
        val f = Fixture(); f.controller.load(); f.pump(); f.controller.connect(" \tguest\n "); f.pump()
        assertFalse(f.controller.state.value.canApprove); assertFalse(f.controller.state.value.canContinue)
        assertEquals(AbemaAccountStatus.NOT_VERIFIED, f.controller.state.value.status)
        assertEquals(1, f.preparations); assertEquals(0, f.tokens); assertNull(f.storage.bytes)
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
            f.controller.connect("intended-account"); f.pump(); f.controller.handoff { true }; f.pump()
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
