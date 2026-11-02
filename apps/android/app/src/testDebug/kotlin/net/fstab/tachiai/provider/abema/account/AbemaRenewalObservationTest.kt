package net.fstab.tachiai.provider.abema.account

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import net.fstab.tachiai.provider.abema.account.AbemaRenewalReadinessCoordinator.Completion
import net.fstab.tachiai.provider.abema.account.AbemaRenewalReadinessCoordinator.Status

class AbemaRenewalObservationTest {
    private class Fixture {
        val instance = UUID.randomUUID().toString()
        val storage = MemoryAbemaAccountStorage()
        val store = AbemaAccountGrantStore(instance, storage)
        var elapsed = 100L
        var wall = 2_000_001L
        var owner = true
        var clockReads = 0
        val coordinator = AbemaRenewalReadinessCoordinator<String>({ clockReads++; elapsed }, { "a".repeat(32) })
        val old = decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1_000_000)
        val next = decodedAbemaGrant(fixtureAccountToken("intended-account", "3000"), "intended-account", wall)
        init { store.replace(old, 1_000_000, store.revision()) { true } }
        val expected = store.readSnapshot()
        val before = checkNotNull(storage.bytes).copyOf()
        var reads = 0
        val observation = AbemaRenewalObservation(expected, { coordinator.completion(instance) { it == "original" } },
            { owner }, { reads++; store.readSnapshot() }, store::usable,
            { abemaReplacementReceiptCurrent(store, it) }, { wall })
        fun start(value: String = "original"): String {
            val ticket = checkNotNull(coordinator.check(instance, value, value).ticket)
            assertEquals(value, coordinator.claim(ticket))
            return ticket
        }
        fun publish(accept: (AbemaAccountSnapshot) -> Boolean = { true }): Boolean = withAbemaAccountReplacement(
            store, next, next.userId, expected.revision, 1_000_000, { wall }, canCommit = { true }) { receipt, _ -> accept(receipt) }
        fun complete(): String {
            val ticket = start(); assertTrue(publish()); coordinator.finish(ticket, true); return ticket
        }
    }

    @Test fun predecessorReadThenSuccessorPublicationWaitsForCompletedReceiptInsteadOfFailingObsoleteRevision() {
        val f = Fixture()
        val predecessor = f.store.readSnapshot()
        val ticket = f.start()
        assertTrue(f.publish {
            assertFalse(f.store.usable(predecessor.revision))
            assertEquals(AbemaRenewalObservation.Step.WAIT, f.observation.poll())
            assertEquals(0, f.reads) // Tentative disk contents are not evidence.
            abemaReplacementReceiptCurrent(f.store, it)
        })
        assertEquals(AbemaRenewalObservation.Step.WAIT, f.observation.poll())
        f.coordinator.finish(ticket, true)
        assertEquals(AbemaRenewalObservation.Step.REPLACED, f.observation.poll())
        assertEquals(Completion.READY, f.observation.observedCompletion)
        assertEquals(f.expected.record!!.generation + 1, f.store.read()!!.generation)
    }

    @Test fun tentativeSuccessorThenRejectedAcceptanceAndRollbackNeverReportsReplacement() {
        val f = Fixture(); val ticket = f.start()
        assertFalse(f.publish {
            assertTrue(f.store.readSnapshot().revision > f.expected.revision)
            assertEquals(AbemaRenewalObservation.Step.WAIT, f.observation.poll())
            assertEquals(0, f.reads)
            false
        })
        assertArrayEquals(f.before, f.storage.bytes)
        f.coordinator.finish(ticket, false)
        assertEquals(AbemaRenewalObservation.Step.REFUSED, f.observation.poll())
        assertEquals(AbemaRenewalObservation.Guard.COMPLETION_REFUSED, f.observation.firstFailedGuard)
        assertEquals(0, f.reads)
    }

    @Test fun unrelatedOperationOrUnclaimedReadyCannotSupplyCompletion() {
        val f = Fixture(); val ticket = f.start("different-owner-or-predecessor")
        assertTrue(f.publish()); f.coordinator.finish(ticket, true)
        assertEquals(Completion.ABSENT, f.coordinator.completion(f.instance) { it == "original" })
        assertEquals(AbemaRenewalObservation.Step.WAIT, f.observation.poll())
        assertEquals(AbemaRenewalObservation.Step.REFUSED, f.observation.deadline())
        assertEquals(0, f.reads)
        val c = AbemaRenewalReadinessCoordinator<String>({ 100 }, { "b".repeat(32) })
        val unclaimed = checkNotNull(c.check("instance", "scope", "original").ticket)
        c.finish(unclaimed, true)
        assertEquals(Completion.FAILURE, c.completion("instance") { true })
        assertEquals(Completion.ABSENT, c.completion("other") { true })
    }

    @Test fun cleanupUnconfirmedCannotBeUpgradedByAnotherFinish() {
        val f = Fixture(); val ticket = f.start(); assertTrue(f.publish())
        f.coordinator.finish(ticket, Status.READY, cleanupConfirmed = false)
        f.coordinator.finish(ticket, Status.READY, cleanupConfirmed = true)
        assertEquals(Completion.CLEANUP_UNCONFIRMED, f.coordinator.completion(f.instance) { true })
        assertEquals(AbemaRenewalObservation.Step.REFUSED, f.observation.poll())
        assertEquals(0, f.reads)
    }

    @Test fun observationDoesNotReadClockExpirePruneOrExtendCoordinatorState() {
        val f = Fixture(); val ticket = f.start(); val before = f.clockReads
        f.elapsed = 1_000_000
        repeat(3) { assertEquals(Completion.PENDING, f.coordinator.completion(f.instance) { true }) }
        assertEquals(before, f.clockReads)
        // Query did not update the high-water clock or expire the operation.
        f.elapsed = 101
        assertTrue(f.coordinator.current(ticket))
        f.coordinator.finish(ticket, true)
        assertEquals(Completion.READY, f.coordinator.completion(f.instance) { true })
    }

    @Test fun lostCompletionAfterExistingCooldownPruningRefusesWithoutRetainingExtraReceipt() {
        val f = Fixture(); f.complete()
        assertEquals(AbemaRenewalObservation.Step.REPLACED, f.observation.poll())
        f.elapsed += 60_001
        f.coordinator.check("other", "other", "other")
        assertEquals(Completion.ABSENT, f.coordinator.completion(f.instance) { true })
        assertEquals(AbemaRenewalObservation.Step.REFUSED, f.observation.poll())
        assertEquals(AbemaRenewalObservation.Guard.COMPLETION_LOST, f.observation.firstFailedGuard)
        assertEquals(AbemaRenewalObservation.Step.REFUSED, f.observation.deadline())
    }

    @Test fun laterReplacementOrForgetCannotReuseCompletedPredecessorReceipt() {
        for (forget in listOf(false, true)) {
            val f = Fixture(); f.complete()
            assertEquals(AbemaRenewalObservation.Step.REPLACED, f.observation.poll())
            if (forget) { f.store.invalidateForget(); f.store.forget() }
            else f.store.replace(decodedAbemaGrant(fixtureAccountToken("intended-account", "4000"), "intended-account", f.wall),
                f.wall, f.store.revision()) { true }
            assertEquals(AbemaRenewalObservation.Step.REFUSED, f.observation.poll())
            assertEquals(AbemaRenewalObservation.Guard.USABLE, f.observation.firstFailedGuard)
        }
    }

    @Test fun completedStatusStillRequiresOriginalOwnerIdentityGenerationAndCurrentExpiry() {
        val owner = Fixture(); owner.complete(); owner.owner = false
        assertEquals(AbemaRenewalObservation.Step.REFUSED, owner.observation.poll())
        assertEquals(AbemaRenewalObservation.Guard.OWNER, owner.observation.firstFailedGuard)
        val expired = Fixture(); expired.complete(); expired.wall = expired.next.expiresAtMs
        assertEquals(AbemaRenewalObservation.Step.REFUSED, expired.observation.poll())
        assertEquals(AbemaRenewalObservation.Guard.EXPIRY, expired.observation.firstFailedGuard)
        for (wrongAccount in listOf(false, true)) {
            val f = Fixture(); val ticket = f.start()
            val grant = if (wrongAccount) decodedAbemaGrant(fixtureAccountToken("other-account", "3000"), "other-account", f.wall) else f.next
            f.store.replace(grant, f.wall, f.store.revision()) { true }
            if (!wrongAccount) f.store.replace(f.next, f.wall, f.store.revision()) { true }
            f.coordinator.finish(ticket, true)
            assertEquals(AbemaRenewalObservation.Step.REFUSED, f.observation.poll())
            assertEquals(if (wrongAccount) AbemaRenewalObservation.Guard.ACCOUNT else AbemaRenewalObservation.Guard.GENERATION,
                f.observation.firstFailedGuard)
        }
    }

    @Test fun teardownAlwaysAttemptsPreservationAndRetainsFirstFailure() {
        val evidence = AbemaRenewalProbeEvidence(); var preserved = false
        evidence.phase = AbemaRenewalProbeEvidence.Phase.DISCOVERY
        evidence.fail(AbemaRenewalProbeEvidence.Guard.DISCOVERY)
        evidence.finish(close = { error("synthetic private exception must not escape") }, preservation = {
            preserved = true; error("synthetic second failure")
        })
        assertTrue(preserved)
        assertEquals(AbemaRenewalProbeEvidence.Guard.DISCOVERY, evidence.firstFailedGuard)
        assertEquals(AbemaRenewalProbeEvidence.Phase.DISCOVERY, evidence.firstFailedPhase)
        assertEquals(AbemaRenewalProbeEvidence.Phase.FINISHED, evidence.phase)
        val early = AbemaRenewalProbeEvidence()
        early.finish({}, { error("synthetic failed baseline comparison") })
        assertEquals(AbemaRenewalProbeEvidence.Guard.PRESERVATION, early.firstFailedGuard)
    }

    @Test fun minimizedEvidenceRequiresPendingAfterStopThenVerifiedReadyBeforeReturn() {
        val background = AbemaRenewalBackgroundObservation()
        background.activityResumed() // Initial launch precedes the guarded interval.
        assertTrue(background.begin(Completion.PENDING))
        background.hostStopped()
        assertTrue(background.afterStop(Completion.PENDING))
        assertTrue(background.sample(Completion.PENDING, false))
        assertTrue(background.sample(Completion.READY, true))
        assertTrue(background.allowReturn())
        background.hostStarted(); background.activityResumed()
        assertEquals(AbemaRenewalBackgroundObservation.Guard.NONE, background.firstFailedGuard)
        assertEquals(AbemaRenewalBackgroundObservation.Phase.RETURN_ALLOWED, background.phase)
    }

    @Test fun completionBeforeHomeOrBeforeStoppedPendingCannotClaimMinimizedCompletion() {
        val before = AbemaRenewalBackgroundObservation()
        assertFalse(before.begin(Completion.READY))
        assertEquals(AbemaRenewalBackgroundObservation.Guard.PENDING_BEFORE_HOME, before.firstFailedGuard)
        val duringHome = AbemaRenewalBackgroundObservation()
        assertTrue(duringHome.begin(Completion.PENDING)); duringHome.hostStopped()
        assertFalse(duringHome.afterStop(Completion.READY))
        assertEquals(AbemaRenewalBackgroundObservation.Guard.PENDING_AFTER_STOP, duringHome.firstFailedGuard)
        val noStop = AbemaRenewalBackgroundObservation()
        assertTrue(noStop.begin(Completion.PENDING)); assertFalse(noStop.afterStop(Completion.PENDING))
        assertEquals(AbemaRenewalBackgroundObservation.Guard.HOST_NOT_STOPPED, noStop.firstFailedGuard)
    }

    @Test fun resumeThenStopOrResumeRacingReadyPermanentlyRefusesBackgroundEvidence() {
        for (afterReady in listOf(false, true)) {
            val background = AbemaRenewalBackgroundObservation()
            assertTrue(background.begin(Completion.PENDING)); background.hostStopped()
            assertTrue(background.afterStop(Completion.PENDING))
            if (afterReady) assertTrue(background.sample(Completion.READY, true))
            background.activityResumed(); background.hostStopped()
            assertFalse(background.sample(Completion.READY, true)); assertFalse(background.allowReturn())
            assertEquals(AbemaRenewalBackgroundObservation.Guard.RESUMED, background.firstFailedGuard)
        }
    }

    @Test fun unverifiedOrLostCompletionAndPrematureReturnCannotPassBackgroundGuard() {
        for ((completion, replacement) in listOf(Completion.READY to false, Completion.PENDING to true,
                Completion.ABSENT to false, Completion.FAILURE to false)) {
            val background = AbemaRenewalBackgroundObservation()
            assertTrue(background.begin(Completion.PENDING)); background.hostStopped()
            assertTrue(background.afterStop(Completion.PENDING))
            assertFalse(background.sample(completion, replacement)); assertFalse(background.allowReturn())
            assertEquals(AbemaRenewalBackgroundObservation.Guard.COMPLETION, background.firstFailedGuard)
        }
        val early = AbemaRenewalBackgroundObservation()
        assertTrue(early.begin(Completion.PENDING)); early.hostStopped(); assertTrue(early.afterStop(Completion.PENDING))
        assertFalse(early.allowReturn())
        assertEquals(AbemaRenewalBackgroundObservation.Guard.RETURN, early.firstFailedGuard)
    }

    @Test fun restartOrDestructionCannotReuseStoppedHostEvidence() {
        for (destroyed in listOf(false, true)) {
            val background = AbemaRenewalBackgroundObservation()
            assertTrue(background.begin(Completion.PENDING)); background.hostStopped()
            assertTrue(background.afterStop(Completion.PENDING))
            if (destroyed) background.hostDestroyed() else background.hostStarted()
            background.hostStopped()
            assertFalse(background.sample(Completion.READY, true)); assertFalse(background.allowReturn())
            assertEquals(if (destroyed) AbemaRenewalBackgroundObservation.Guard.HOST_DESTROYED
                else AbemaRenewalBackgroundObservation.Guard.HOST_NOT_STOPPED, background.firstFailedGuard)
        }
    }

    @Test fun privateMutationSummarySeparatesSelectedAccountJournalAndOtherRecordsWithoutExportingIdentities() {
        val selected = "no_backup/abema-account-selected.enc"
        val journal = "no_backup/failure-diagnostics.tsv"
        val other = "no_backup/PRIVATE_SYNTHETIC_RECORD.enc"
        val baseline = mapOf(selected to byteArrayOf(1), journal to byteArrayOf(2), other to byteArrayOf(3))
        fun summary(after: Map<String, ByteArray>) = abemaRenewalOtherMutations(baseline, after, selected)
        assertEquals(AbemaRenewalOtherMutations(0, 0, 0, AbemaRenewalOtherMutations.Kind.NONE),
            summary(baseline + (selected to byteArrayOf(4))))
        assertEquals(AbemaRenewalOtherMutations(1, 0, 0, AbemaRenewalOtherMutations.Kind.DIAGNOSTIC_JOURNAL_ONLY),
            summary(baseline + (journal to byteArrayOf(4))))
        assertEquals(AbemaRenewalOtherMutations(1, 0, 0, AbemaRenewalOtherMutations.Kind.OTHER_PROTECTED_RECORD),
            summary(baseline + (selected to byteArrayOf(4)) + (other to byteArrayOf(5))))
        val mixed = summary(baseline + (journal to byteArrayOf(4)) + (other to byteArrayOf(5)))
        assertEquals(AbemaRenewalOtherMutations.Kind.MIXED, mixed.kind)
        assertFalse(mixed.toString().contains("PRIVATE"))
        assertFalse(mixed.toString().contains("no_backup"))
    }

    @Test fun freezeBeforeTeardownCannotInventHostDestructionOrPermitAReturn() {
        val background = AbemaRenewalBackgroundObservation()
        assertTrue(background.begin(Completion.PENDING)); background.hostStopped()
        assertTrue(background.afterStop(Completion.PENDING))
        val snapshot = background.freeze()
        background.hostDestroyed(); background.activityResumed(); background.hostStarted(); background.hostStopped()
        assertSame(snapshot, background.freeze())
        assertEquals(AbemaRenewalBackgroundObservation.Phase.STOPPED_PENDING, snapshot.phase)
        assertEquals(AbemaRenewalBackgroundObservation.Guard.NONE, snapshot.guard)
        assertTrue(snapshot.hostIsStopped)
        assertFalse(background.allowReturn()); assertFalse(background.sample(Completion.READY, true))
        assertEquals(snapshot.guard, background.firstFailedGuard)
    }

    @Test fun freezePreservesAGenuineEarlierLifecycleFailureAndItsOriginalPhase() {
        for (destroyed in listOf(false, true)) {
            val background = AbemaRenewalBackgroundObservation()
            assertTrue(background.begin(Completion.PENDING)); background.hostStopped()
            assertTrue(background.afterStop(Completion.PENDING))
            if (destroyed) background.hostDestroyed() else background.activityResumed()
            val snapshot = background.freeze()
            background.hostDestroyed()
            assertEquals(if (destroyed) AbemaRenewalBackgroundObservation.Guard.HOST_DESTROYED else AbemaRenewalBackgroundObservation.Guard.RESUMED,
                snapshot.guard)
            assertSame(snapshot, background.freeze())
        }
    }

    @Test fun refusalKeepsTheSameAtomicCompletionDiagnosticAndDoesNotReadAnUnacceptedAccount() {
        val f = Fixture(); val ticket = f.start()
        val diagnostic = AbemaRenewalDiagnostic(AbemaRenewalDiagnostic.Phase.LIFETIME, AbemaRenewalDiagnostic.Reason.DEADLINE,
            cleanup = AbemaRenewalDiagnostic.Cleanup.CONFIRMED)
        val producer = AbemaRenewalDiagnostic(AbemaRenewalDiagnostic.Phase.REGION, AbemaRenewalDiagnostic.Reason.REGION_REJECTED,
            cleanup = AbemaRenewalDiagnostic.Cleanup.CONFIRMED)
        f.coordinator.finish(ticket, Status.TEMPORARY_FAILURE, true, diagnostic, producer)
        val observation = AbemaRenewalObservation(f.expected, { error("Atomic evidence must be used") },
            { true }, { error("Refusal must not read candidate storage") }, f.store::usable, { false }, { f.wall },
            evidence = { f.coordinator.evidence(f.instance) { it == "original" } })
        assertEquals(AbemaRenewalObservation.Step.REFUSED, observation.poll())
        assertEquals(Completion.TEMPORARY_FAILURE, observation.observedCompletion)
        assertEquals(diagnostic, observation.observedDiagnostic)
        assertEquals(producer, observation.observedProducerDiagnostic)
        assertEquals(AbemaRenewalObservation.Guard.COMPLETION_REFUSED, observation.firstFailedGuard)
    }

    @Test fun privateMutationSummaryIncludesInventoryChangesAndNeverTreatsJournalLookalikesAsTheJournal() {
        val selected = "selected"
        val removed = "shared_prefs/PRIVATE.xml"
        val added = "no_backup/failure-diagnostics.tsv.enc"
        val result = abemaRenewalOtherMutations(mapOf(selected to byteArrayOf(1), removed to byteArrayOf(2)),
            mapOf(selected to byteArrayOf(1), added to byteArrayOf(3)), selected)
        assertEquals(AbemaRenewalOtherMutations(0, 1, 1, AbemaRenewalOtherMutations.Kind.OTHER_PROTECTED_RECORD), result)
    }

    @Test fun privateMutationCountsSaturateWithoutExportingOversizedInventories() {
        val before = (1..100).associate { "before$it" to byteArrayOf(1) }
        val after = (1..100).associate { "after$it" to byteArrayOf(2) }
        assertEquals(AbemaRenewalOtherMutations(0, 64, 64, AbemaRenewalOtherMutations.Kind.OTHER_PROTECTED_RECORD),
            abemaRenewalOtherMutations(before, after, "selected"))
    }
}
