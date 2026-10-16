package net.fstab.tachiai.platform.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test

class ProviderRouteStartupTest {
    private val a = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, "00000000-0000-0000-0000-000000000010", "First exit")
    private val b = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, "00000000-0000-0000-0000-000000000011", "Second exit")
    private val c = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, "00000000-0000-0000-0000-000000000012", "Third exit")
    private fun profile(port: Int) = parseConnectionProfile("http://fixture-user:fixture-secret@proxy.example.test:$port".toByteArray())
    private fun captured(routes: List<SourceRouteChoice>, profiles: Map<String, ConnectionProfile>) =
        defaultProviderInstances().first().copy(setup = ProviderSetup(routes.firstOrNull(), fallbackRoutes = routes.drop(1))).let { instance ->
            captureProviderRoutePool(instance.id, instance.service, { listOf(instance) }) { ids -> profiles.filterKeys { it in ids } }
        }
    private class Lease(private val events: MutableList<String>, private val name: String,
        private val confirms: Boolean = true, private val cancelFails: Boolean = false) : ProviderStartupLease {
        var closes = 0
        var cancellations = 0
        override fun cancel() { cancellations++; events += "cancel:$name"; check(!cancelFails) }
        override fun closeAndConfirm(): Boolean { closes++; events += "close:$name"; return confirms }
    }
    private inner class Fixture(routes: List<SourceRouteChoice> = listOf(a, b),
        profiles: Map<String, ConnectionProfile> = mapOf(a.connectionId!! to profile(8080), b.connectionId!! to profile(8081))) {
        val pool = captured(routes, profiles)
        var now = 0L
        var current = true
        var alarm: (() -> Unit)? = null
        val progress = mutableListOf<ProviderStartupProgress>()
        val scope = ProviderRouteStartup(pool, 1000, { now }, { current }, { progress += it }, { _, action ->
            alarm = action; AutoCloseable { alarm = null }
        })
    }
    private fun transport(failure: ProviderTransportFailure, phase: ProviderTransportPhase = ProviderTransportPhase.UNKNOWN,
        cancelled: Boolean = false) = ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.TRANSPORT,
            ProviderTransportEvidence(failure, phase, cancelled)))
    private fun reason(result: ProviderStartupResult<*>) = (result as ProviderStartupResult.Failed).failure.reason

    @Test fun orderedAvailabilityFailureClosesPartialResourcesBeforeTheRealResponseSelectsTheNextRoute() {
        val f = Fixture(); val events = mutableListOf<String>(); var first: Lease? = null; var winner: Lease? = null
        val result = f.scope.runReadOnly { owner, context ->
            events += "request:${owner.choice.title}"
            if (owner.choice == a) {
                first = context.own(Lease(events, "first"))
                throw ProviderTransportException(ProviderTransportEvidence(ProviderTransportFailure.DNS))
            }
            assertEquals(listOf("request:First exit", "close:first", "request:Second exit"), events)
            winner = context.own(Lease(events, "winner"))
            ProviderStartupAttempt.Admitted("validated channel response", winner)
        } as ProviderStartupResult.Ready
        assertEquals(b, result.owner.choice); assertEquals("validated channel response", result.value)
        assertEquals(1, first!!.closes); assertEquals(0, winner!!.closes)
        assertTrue(f.scope.admitted); assertFalse(f.scope.cleanupConfirmed)
        assertEquals(listOf(1, 2), f.progress.filter { it.phase == ProviderStartupPhase.TRYING }.map { it.ordinal })
        assertEquals(ProviderStartupPhase.SELECTED, f.scope.progress.phase)
        f.scope.close(); assertEquals(1, winner!!.closes); assertTrue(f.scope.cleanupConfirmed)
    }

    @Test fun canonicalAliasesDoNotRepeatAnAlreadyFailedPhysicalRoute() {
        val same = profile(8080)
        val f = Fixture(listOf(a, b, c), mapOf(a.connectionId!! to same, b.connectionId!! to same, c.connectionId!! to profile(8082)))
        val calls = mutableListOf<SourceRouteChoice>()
        val result = f.scope.runReadOnly { owner, _ ->
            calls += owner.choice
            if (owner.choice == a) transport(ProviderTransportFailure.CONNECT) else ProviderStartupAttempt.Admitted("response")
        } as ProviderStartupResult.Ready
        assertEquals(listOf(a, c), calls); assertEquals(c, result.owner.choice)
        assertEquals(3, f.scope.progress.ordinal); assertEquals(3, f.scope.progress.count)
    }

    @Test fun aFailedAttemptContextCannotBecomeActiveAgainWhenAnotherCandidateSucceeds() {
        val f = Fixture(); lateinit var retired: ProviderStartupAttemptContext
        f.scope.runReadOnly { owner, context ->
            if (owner.choice == a) { retired = context; transport(ProviderTransportFailure.CONNECT) }
            else { assertFalse(retired.canContinue()); ProviderStartupAttempt.Admitted("winner response") }
        }
        assertFalse(retired.canContinue()); assertTrue(f.scope.canContinue())
    }

    @Test fun localMissingProfilesCanSkipOnlyToAnExplicitAlternativeAndNeverInventSystem() {
        val f = Fixture(listOf(a, SourceRouteChoice.system), emptyMap())
        val calls = mutableListOf<SourceRouteChoice>()
        val ready = f.scope.runReadOnly { owner, _ -> calls += owner.choice; ProviderStartupAttempt.Admitted("response") }
        assertEquals(listOf(SourceRouteChoice.system), calls); assertTrue(ready is ProviderStartupResult.Ready)
        val missing = Fixture(listOf(a), emptyMap())
        assertEquals(ProviderStartupFailure.CONFIGURATION_UNAVAILABLE,
            reason(missing.scope.runReadOnly<Nothing> { _, _ -> error("Missing route cannot request") }))
        val empty = Fixture(emptyList(), emptyMap())
        assertEquals(ProviderStartupFailure.CHOICE_REQUIRED,
            reason(empty.scope.runReadOnly<Nothing> { _, _ -> error("No implicit System") }))
    }

    @Test fun onlyClassifiedAvailabilityCanTryAnotherConfiguredRoute() {
        val cases = listOf(
            ProviderTransportEvidence(ProviderTransportFailure.TLS),
            ProviderTransportEvidence(ProviderTransportFailure.UNKNOWN),
            ProviderTransportEvidence(ProviderTransportFailure.CANCELLED),
            ProviderTransportEvidence(ProviderTransportFailure.CONNECT, localCancellationRequested = true),
            ProviderTransportEvidence(ProviderTransportFailure.TIMEOUT),
            ProviderTransportEvidence(ProviderTransportFailure.TIMEOUT, ProviderTransportPhase.TLS),
            ProviderTransportEvidence(ProviderTransportFailure.TIMEOUT, ProviderTransportPhase.REQUEST),
            ProviderTransportEvidence(ProviderTransportFailure.TIMEOUT, ProviderTransportPhase.RESPONSE),
        )
        for (evidence in cases) {
            val f = Fixture(); var calls = 0
            assertEquals(ProviderStartupFailure.TRANSPORT, reason(f.scope.runReadOnly<Nothing> { _, _ ->
                calls++; ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.TRANSPORT, evidence))
            }))
            assertEquals(1, calls)
        }
        for (phase in listOf(ProviderTransportPhase.DNS, ProviderTransportPhase.CONNECT)) {
            val f = Fixture(); var calls = 0
            assertTrue(f.scope.runReadOnly { _, _ ->
                if (++calls == 1) transport(ProviderTransportFailure.TIMEOUT, phase) else ProviderStartupAttempt.Admitted("real response")
            } is ProviderStartupResult.Ready)
            assertEquals(2, calls)
        }
    }

    @Test fun providerRejectionsRemainTerminalEvenWhenTransportEvidenceWouldOtherwiseAllowFallback() {
        for (failure in listOf(ProviderStartupFailure.AUTHENTICATION, ProviderStartupFailure.ENTITLEMENT,
            ProviderStartupFailure.DRM, ProviderStartupFailure.TLS, ProviderStartupFailure.REGION, ProviderStartupFailure.HTTP,
            ProviderStartupFailure.RATE_LIMITED, ProviderStartupFailure.MALFORMED, ProviderStartupFailure.UNKNOWN)) {
            val f = Fixture(); var calls = 0
            assertEquals(failure, reason(f.scope.runReadOnly<Nothing> { _, _ ->
                calls++; ProviderStartupAttempt.Rejected(ProviderStartupRejection(failure,
                    ProviderTransportEvidence(ProviderTransportFailure.DNS)))
            }))
            assertEquals(1, calls)
        }
    }

    @Test fun exhaustionIsBoundedAndCannotBeRetriedWithinTheSameScope() {
        val f = Fixture(); var calls = 0
        repeat(2) {
            assertEquals(ProviderStartupFailure.TRANSPORT, reason(f.scope.runReadOnly<Nothing> { _, _ ->
                calls++; transport(ProviderTransportFailure.CONNECT)
            }))
        }
        assertEquals(2, calls); assertNull(f.scope.selectedRoute)
        assertTrue(f.scope.progress.exhausted)
    }

    @Test fun exhaustionIsDistinctFromTerminalTransportAndProviderFailuresOrAnEmptyPool() {
        val unknown = Fixture()
        unknown.scope.runReadOnly<Nothing> { _, _ -> transport(ProviderTransportFailure.UNKNOWN) }
        assertFalse(unknown.scope.progress.exhausted)
        val tls = Fixture()
        tls.scope.runReadOnly<Nothing> { _, _ -> transport(ProviderTransportFailure.TLS) }
        assertFalse(tls.scope.progress.exhausted)
        val empty = Fixture(emptyList(), emptyMap())
        empty.scope.runReadOnly<Nothing> { _, _ -> error("No route") }
        assertFalse(empty.scope.progress.exhausted)
        val missing = Fixture(listOf(a), emptyMap())
        missing.scope.runReadOnly<Nothing> { _, _ -> error("Unavailable route") }
        assertTrue(missing.scope.progress.exhausted)
    }

    @Test fun theMaximumPoolAttemptsEachDistinctConfiguredRouteOnlyOnce() {
        val routes = (1..MAX_PROVIDER_ROUTES).map {
            SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, "00000000-0000-0000-0000-%012d".format(it), "Exit $it")
        }
        val f = Fixture(routes, routes.mapIndexed { index, route -> route.connectionId!! to profile(8100 + index) }.toMap())
        val calls = mutableListOf<SourceRouteChoice>()
        assertEquals(ProviderStartupFailure.TRANSPORT, reason(f.scope.runReadOnly<Nothing> { owner, _ ->
            calls += owner.choice; transport(ProviderTransportFailure.DNS)
        }))
        assertEquals(routes, calls)
        val oversized = ProviderRoutePoolSnapshot(f.pool.configuredInstance,
            f.pool.candidates + f.pool.candidates.first(), f.pool.proof)
        assertThrows(IllegalArgumentException::class.java) {
            ProviderRouteStartup(oversized, 1000, { 0 }, { true })
        }
    }

    @Test fun unconfirmedCleanupPoisonsTheScopeAndASecondCloseCannotEraseTheFailure() {
        val f = Fixture(); val events = mutableListOf<String>(); val lease = Lease(events, "partial", confirms = false)
        var calls = 0
        assertEquals(ProviderStartupFailure.CLEANUP_UNCONFIRMED, reason(f.scope.runReadOnly<Nothing> { _, context ->
            calls++; context.own(lease); transport(ProviderTransportFailure.DNS)
        }))
        f.scope.close()
        assertEquals(1, calls); assertEquals(1, lease.closes); assertFalse(f.scope.cleanupConfirmed)
        assertEquals(ProviderStartupPhase.CLEANUP_UNCONFIRMED, f.scope.progress.phase)
    }

    @Test fun cancellationHookFailureAlsoBlocksFallbackAfterAReportedSuccessfulClose() {
        val f = Fixture(); val events = mutableListOf<String>(); val lease = Lease(events, "partial", cancelFails = true)
        assertEquals(ProviderStartupFailure.CLEANUP_UNCONFIRMED, reason(f.scope.runReadOnly<Nothing> { _, context ->
            context.own(lease); f.scope.cancel(); transport(ProviderTransportFailure.DNS)
        }))
        assertEquals(1, lease.closes); assertFalse(f.scope.cleanupConfirmed)
    }

    @Test fun deadlineSignalsRegisteredWorkAndRefusesALateValidatedResponse() {
        val f = Fixture(); val events = mutableListOf<String>(); val lease = Lease(events, "request")
        val result = f.scope.runReadOnly { _, context ->
            context.own(lease); f.now = 1000; f.alarm!!.invoke()
            assertFalse(context.canContinue())
            ProviderStartupAttempt.Admitted("late response", lease)
        }
        assertEquals(ProviderStartupFailure.DEADLINE, reason(result))
        assertEquals(1, lease.cancellations); assertEquals(1, lease.closes); assertNull(f.scope.selectedRoute)
    }

    @Test fun cancellationOwnsResourcesRegisteredAfterItsSignalAndCannotRevive() {
        val f = Fixture(); val events = mutableListOf<String>(); val late = Lease(events, "late")
        assertEquals(ProviderStartupFailure.CANCELLED, reason(f.scope.runReadOnly { _, context ->
            f.scope.cancel(); context.own(late); ProviderStartupAttempt.Admitted("late", late)
        }))
        assertEquals(1, late.cancellations); assertEquals(1, late.closes)
        assertEquals(ProviderStartupFailure.CANCELLED, reason(f.scope.runReadOnly<Nothing> { _, _ -> error("Cannot revive") }))
    }

    @Test fun anObservedConfigurationChangeCannotReviveWhenTheOldSnapshotReturns() {
        val f = Fixture(); var calls = 0
        assertEquals(ProviderStartupFailure.REVOKED, reason(f.scope.runReadOnly { _, _ ->
            calls++; f.current = false; ProviderStartupAttempt.Admitted("response from revoked scope")
        }))
        f.current = true
        assertEquals(ProviderStartupFailure.REVOKED, reason(f.scope.runReadOnly<Nothing> { _, _ -> error("Cannot revive") }))
        assertEquals(1, calls)
    }

    @Test fun configurationLossDuringResponseCleanupCannotPublishAValidatedButRevokedValue() {
        val f = Fixture(); var closes = 0
        val result = f.scope.runReadOnly { _, context ->
            context.own(object : ProviderStartupLease {
                override fun cancel() {}
                override fun closeAndConfirm(): Boolean { closes++; f.current = false; return true }
            })
            ProviderStartupAttempt.Admitted("validated but stale response")
        }
        assertEquals(ProviderStartupFailure.REVOKED, reason(result)); assertEquals(1, closes)
        assertNull(f.scope.selectedRoute)
    }

    @Test fun successfulOwnerAndRetainedContextOutliveStartupDeadlineButALaterFailureNeverChangesRoute() {
        val f = Fixture(); val calls = mutableListOf<SourceRouteChoice>(); lateinit var retained: ProviderStartupAttemptContext
        var lateAlarm: (() -> Unit)? = null
        f.scope.runReadOnly { owner, context ->
            calls += owner.choice
            if (owner.choice == a) transport(ProviderTransportFailure.DNS) else {
                retained = context; lateAlarm = f.alarm; ProviderStartupAttempt.Admitted("first response")
            }
        }
        f.now = 2000
        lateAlarm!!.invoke()
        assertTrue(retained.canContinue()); assertEquals(0L, retained.remainingMs); assertTrue(f.scope.canContinue())
        val second = f.scope.runReadOnly { owner, _ -> calls += owner.choice; ProviderStartupAttempt.Admitted("second distinct response") }
        assertEquals("second distinct response", (second as ProviderStartupResult.Ready).value)
        assertEquals(ProviderStartupFailure.TRANSPORT, reason(f.scope.runReadOnly<Nothing> { owner, _ ->
            calls += owner.choice; transport(ProviderTransportFailure.DNS)
        }))
        assertEquals(listOf(a, b, b, b), calls); assertEquals(b, f.scope.selectedRoute!!.choice)
    }

    @Test fun localAuthorizationHoldsFirstWithoutClaimingAProviderResponseAndLaterValidationCanAdmitIt() {
        val f = Fixture()
        val held = f.scope.holdFirstForMutation() as ProviderStartupResult.HeldForMutation
        assertEquals(a, held.owner.choice); assertFalse(f.scope.admitted)
        assertEquals(ProviderStartupPhase.HELD_FOR_MUTATION, f.scope.progress.phase)
        f.now = 2000
        assertTrue(f.scope.canContinue())
        val ready = f.scope.runReadOnly { owner, _ -> assertEquals(a, owner.choice); ProviderStartupAttempt.Admitted("validated after auth") }
        assertTrue(ready is ProviderStartupResult.Ready); assertTrue(f.scope.admitted)
    }

    @Test fun aMatchedHandoffCanHoldItsExactLaterCandidateWithoutAnHttpSuccessClaim() {
        val f = Fixture()
        val held = f.scope.holdForMutation(f.pool.candidates[1].proof) as ProviderStartupResult.HeldForMutation
        assertEquals(b, held.owner.choice); assertFalse(f.scope.admitted)
        val ready = f.scope.runReadOnly { owner, _ -> assertEquals(b, owner.choice); ProviderStartupAttempt.Admitted("post-handoff validation") }
        assertTrue(ready is ProviderStartupResult.Ready)
        assertTrue(f.scope.admitted)
        assertEquals(ProviderStartupFailure.REVOKED, reason(f.scope.holdForMutation(f.pool.candidates[0].proof)))
        val changed = Fixture(listOf(b, a))
        assertEquals(ProviderStartupFailure.REVOKED, reason(changed.scope.holdForMutation(f.pool.candidates[1].proof)))
        assertNull(changed.scope.selectedRoute)
    }

    @Test fun synchronousProgressCancellationCannotPublishAReadyOrMutationHoldOrStartANewAttempt() {
        for (phase in listOf(ProviderStartupPhase.TRYING, ProviderStartupPhase.SELECTED, ProviderStartupPhase.HELD_FOR_MUTATION)) {
            val f = Fixture(); lateinit var scope: ProviderRouteStartup; var calls = 0
            val lease = Lease(mutableListOf(), "winner")
            scope = ProviderRouteStartup(f.pool, 1000, { 0 }, { true }, {
                if (it.phase == phase) scope.cancel()
            }, { _, _ -> AutoCloseable {} })
            val result = scope.runReadOnly { _, context ->
                calls++; context.own(lease)
                if (phase == ProviderStartupPhase.HELD_FOR_MUTATION) ProviderStartupAttempt.RequiresMutation
                else ProviderStartupAttempt.Admitted("response", lease)
            }
            assertEquals(ProviderStartupFailure.CANCELLED, reason(result))
            assertEquals(if (phase == ProviderStartupPhase.TRYING) 0 else 1, calls)
            assertEquals(if (phase == ProviderStartupPhase.TRYING) 0 else 1, lease.closes)
            assertEquals(ProviderStartupPhase.CANCELLED, scope.progress.phase)
        }
    }

    @Test fun lateOldCleanupPoisonBlocksReplacementPublicationAndItsLifetimeGateCannotRevive() {
        val f = Fixture(); var poisoned = false
        val scope = ProviderRouteStartup(f.pool, 1000, { 0 }, { true }, {
            if (it.phase == ProviderStartupPhase.SELECTED) poisoned = true
        }, { _, _ -> AutoCloseable {} }, canPublishLocally = { !poisoned })
        val lease = Lease(mutableListOf(), "replacement")
        val result = scope.runReadOnly { _, context -> context.own(lease); ProviderStartupAttempt.Admitted("response", lease) }
        assertEquals(ProviderStartupFailure.CLEANUP_UNCONFIRMED, reason(result))
        assertEquals(1, lease.closes); assertFalse(scope.canContinue())
        poisoned = false
        assertFalse(scope.canContinue())
    }

    @Test fun aLaterCandidatesAuthorizationNeedHoldsThatCandidateAndClosesReadOnlyResourcesBeforeMutation() {
        val f = Fixture(); val events = mutableListOf<String>(); val scratch = Lease(events, "auth read")
        val held = f.scope.runReadOnly<Nothing> { owner, context ->
            if (owner.choice == a) transport(ProviderTransportFailure.DNS)
            else { context.own(scratch); ProviderStartupAttempt.RequiresMutation }
        } as ProviderStartupResult.HeldForMutation
        assertEquals(b, held.owner.choice); assertEquals(1, scratch.closes); assertFalse(f.scope.admitted)
        assertEquals(ProviderStartupFailure.AUTHENTICATION, reason(f.scope.runReadOnly<Nothing> { owner, _ ->
            assertEquals(b, owner.choice); ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.AUTHENTICATION))
        }))
    }

    @Test fun concurrentConsumersShareASelectionButReceiveTheirOwnReadOnlyResponses() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        var first: ProviderStartupResult<String>? = null; var second: ProviderStartupResult<String>? = null
        val worker = thread { first = f.scope.runReadOnly { _, _ ->
            calls.incrementAndGet(); entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); ProviderStartupAttempt.Admitted("first")
        } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val duplicate = thread { second = f.scope.runReadOnly { owner, _ ->
            assertEquals(a, owner.choice); calls.incrementAndGet(); ProviderStartupAttempt.Admitted("second")
        } }
        release.countDown(); worker.join(5000); duplicate.join(5000)
        assertFalse(worker.isAlive); assertFalse(duplicate.isAlive); assertEquals(2, calls.get())
        assertEquals("first", (first as ProviderStartupResult.Ready).value)
        assertEquals("second", (second as ProviderStartupResult.Ready).value)
    }

    @Test fun unknownThrownFailuresAndUnregisteredWinnersFailClosedAndNeverExposeTheirResponseOrSecrets() {
        val f = Fixture(); var calls = 0
        val result = f.scope.runReadOnly<Nothing> { _, _ -> calls++; error("fixture-secret https://private.example.test/token") }
        assertEquals(ProviderStartupFailure.TRANSPORT, reason(result)); assertEquals(1, calls)
        for (value in listOf(result, f.scope, f.scope.progress)) {
            assertFalse(value.toString().contains("fixture-secret")); assertFalse(value.toString().contains("private.example.test"))
        }
        val unregistered = Fixture(); val lease = Lease(mutableListOf(), "unregistered")
        assertEquals(ProviderStartupFailure.UNKNOWN, reason(unregistered.scope.runReadOnly { _, _ ->
            ProviderStartupAttempt.Admitted("unregistered", lease)
        }))
        assertEquals(1, lease.closes); assertNull(unregistered.scope.selectedRoute)
    }
}
