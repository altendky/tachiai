package net.fstab.tachiai.provider.abema

import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.channels.FileLockInterruptionException
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import net.fstab.tachiai.platform.diagnostics.FailureObservation
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage
import net.fstab.tachiai.feature.connections.ProviderInstanceStore
import net.fstab.tachiai.platform.network.ConnectionProfileStore
import net.fstab.tachiai.platform.network.ProviderRouteOwnerReads
import net.fstab.tachiai.platform.storage.EnvelopeSecretStore
import net.fstab.tachiai.platform.network.ProviderRoutePoolException
import net.fstab.tachiai.platform.network.ProviderRoutePoolFailure
import net.fstab.tachiai.platform.network.parseConnectionProfile
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AbemaEpisodePlaybackOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val routeId = "12345678-1234-1234-1234-123456789abd"
    private fun instance(choice: SourceRouteChoice = SourceRouteChoice.system) =
        ProviderInstance(id, PrototypeService.ABEMA, "ABEMA", setup = ProviderSetup(choice))
    private fun profile(secret: String = "fixture") = parseConnectionProfile("http://user:$secret@proxy.example.test:3128".toByteArray())

    @Test fun interruptedProtectedFileLockCancelsOnlyItsReadAndAllowsTheNextHealthyCheck() {
        val owner = AbemaCatalogRouteOwner(instance(), null)
        val file = temporary.newFile("owner.lock")
        var cancel = true
        var reads = 0
        val guard = AbemaEpisodePlaybackOwnership(owner, {
            reads++
            if (cancel) FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
                Thread.currentThread().interrupt()
                channel.lock().use { }
            }
            owner
        }, { true })
        try {
            assertFalse(guard.checkWorker())
            assertTrue(Thread.currentThread().isInterrupted)
            Thread.interrupted()
            assertTrue("A cancelled protected read must not revoke the binding", guard.canContinue())
            cancel = false
            assertTrue(guard.checkWorker())
            assertEquals(2, reads)
        } finally { Thread.interrupted() }
    }

    // #745: each media request's owner check over the locked stores, keeping
    // its verdict only while both stores' exact records are unchanged.
    @Test fun storeVerdictsSkipDecryptsUntilARouteChangeRefusesTheNextRequest() {
        val instanceSecrets = EnvelopeSecretStore(); val profileSecrets = EnvelopeSecretStore()
        val instanceLock = temporary.newFile("instances.lock"); val profileLock = temporary.newFile("profiles.lock")
        val instances = ProviderInstanceStore(instanceSecrets, instanceLock)
        val profiles = ConnectionProfileStore(profileSecrets, profileLock)
        fun defaults() = legacyProviderSetups(defaultSourceSetups())
        val abema = defaultProviderInstanceId(PrototypeService.ABEMA)
        val saved = profiles.add("Japan", profile()).single().id
        instances.saveRoutes(abema, null, listOf(SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, saved, "Japan")), ::defaults)
        val captured = captureAbemaCatalogRouteOwner(abema, { instances.read(::defaults) }, profiles::snapshot)
        val reads = ProviderRouteOwnerReads(instances, profiles, ::defaults)
        val guard = AbemaEpisodePlaybackOwnership(captured,
            { abemaCatalogRouteOwnerRead(reads, captured, abema, captured.proof()) }, { true })
        assertTrue(guard.checkWorker())
        val decrypts = instanceSecrets.decrypts.get() + profileSecrets.decrypts.get()
        repeat(10) { assertTrue(guard.checkWorker()) }
        assertEquals(decrypts, instanceSecrets.decrypts.get() + profileSecrets.decrypts.get())
        // A revision read cancelled while it waits for a store's file lock
        // refuses only that request.
        profileSecrets.readHook = { Thread.currentThread().interrupt(); throw FileLockInterruptionException() }
        try {
            assertFalse(guard.checkWorker())
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted(); profileSecrets.readHook = {} }
        assertTrue(guard.canContinue()); assertTrue(guard.checkWorker())
        // A profile edit in place of the saved route ends the binding.
        val other = ConnectionProfileStore(profileSecrets, profileLock).add("Osaka", profile("other")).last().id
        ProviderInstanceStore(instanceSecrets, instanceLock).saveRoutes(abema, null,
            listOf(SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, other, "Osaka")), ::defaults)
        assertFalse(guard.checkWorker()); assertFalse(guard.canContinue())
    }

    @Test fun cancellationBeforeOrAfterAMatchingReadRefusesOnlyThatOperation() {
        val owner = AbemaCatalogRouteOwner(instance(), null)
        for (beforeRead in listOf(true, false)) {
            var cancel = true
            var reads = 0
            val guard = AbemaEpisodePlaybackOwnership(owner, {
                reads++
                if (cancel && !beforeRead) Thread.currentThread().interrupt()
                owner
            }, { true })
            try {
                if (beforeRead) Thread.currentThread().interrupt()
                assertFalse(guard.checkWorker())
                assertTrue(Thread.currentThread().isInterrupted)
                assertEquals(if (beforeRead) 0 else 1, reads)
                assertTrue(guard.canContinue())
                Thread.interrupted()
                cancel = false
                assertTrue(guard.checkWorker())
                assertEquals(if (beforeRead) 1 else 2, reads)
            } finally { Thread.interrupted() }
        }
    }

    @Test fun wrappedInterruptionRestoresTheFlagWithoutLoggingOrRevokingTheBinding() {
        val owner = AbemaCatalogRouteOwner(instance(), null)
        val interruptions = listOf(InterruptedException(), ClosedByInterruptException(), FileLockInterruptionException())
        for (interruption in interruptions) {
            val error = IOException("Synthetic outer read", IllegalStateException("Synthetic wrapper", interruption))
            var failRead = true
            val observations = mutableListOf<FailureObservation>()
            val guard = AbemaEpisodePlaybackOwnership(owner, {
                if (failRead) throw error
                owner
            }, { true }, FailureReporter({ observations += it }))
            try {
                assertFalse(guard.checkWorker())
                assertTrue(Thread.currentThread().isInterrupted)
                assertTrue(guard.canContinue())
                assertTrue(observations.isEmpty())
                Thread.interrupted()
                failRead = false
                assertTrue(guard.checkWorker())
            } finally { Thread.interrupted() }
        }
    }

    @Test fun actualOwnerChangesAndTypedRouteFailuresStayStickyEvenWhenInterrupted() {
        val owner = AbemaCatalogRouteOwner(instance(), null)
        var replacement = AbemaCatalogRouteOwner(instance(SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, routeId, "Saved")), profile())
        val changed = AbemaEpisodePlaybackOwnership(owner, {
            Thread.currentThread().interrupt()
            replacement
        }, { true })
        try {
            assertFalse(changed.checkWorker())
            assertTrue(Thread.currentThread().isInterrupted)
            Thread.interrupted()
            replacement = owner
            assertFalse(changed.canContinue()); assertFalse(changed.checkWorker())
        } finally { Thread.interrupted() }

        val routeFailures = listOf<Throwable>(
            ProviderRoutePoolException(ProviderRoutePoolFailure.OWNER_MISMATCH),
            IOException("Synthetic wrapper", ProviderRoutePoolException(ProviderRoutePoolFailure.PROFILE_MISSING)),
            InterruptedException("Synthetic cancellation").apply {
                initCause(ProviderRoutePoolException(ProviderRoutePoolFailure.SNAPSHOT_CHANGED))
            },
            ProviderRoutePoolException(ProviderRoutePoolFailure.OWNER_MISMATCH).apply { initCause(InterruptedException()) },
        )
        for ((index, failure) in routeFailures.withIndex()) {
            var failRead = true
            val observations = mutableListOf<FailureObservation>()
            val guard = AbemaEpisodePlaybackOwnership(owner, {
                if (failRead) {
                    if (index < 2) Thread.currentThread().interrupt()
                    throw failure
                }
                owner
            }, { true }, FailureReporter({ observations += it }))
            try {
                assertFalse(guard.checkWorker())
                assertTrue(Thread.currentThread().isInterrupted)
                Thread.interrupted()
                failRead = false
                assertFalse(guard.canContinue()); assertFalse(guard.checkWorker())
                assertEquals(listOf(FailureStage.CACHED_ABEMA_SOURCE_PREPARE), observations.map { it.stage })
            } finally { Thread.interrupted() }
        }
    }

    @Test fun ordinaryReadErrorsAndSocketTimeoutsRemainReportedStickyFailures() {
        val owner = AbemaCatalogRouteOwner(instance(), null)
        for (error in listOf(IOException("Synthetic storage failure"), SocketTimeoutException(),
            IOException("Synthetic wrapper", SocketTimeoutException()))) {
            var failRead = true
            val observations = mutableListOf<FailureObservation>()
            val guard = AbemaEpisodePlaybackOwnership(owner, { if (failRead) throw error else owner },
                { true }, FailureReporter({ observations += it }))
            try {
                assertFalse(guard.checkWorker())
                assertFalse(Thread.currentThread().isInterrupted)
                failRead = false
                assertFalse(guard.canContinue()); assertFalse(guard.checkWorker())
                assertEquals(listOf(FailureStage.CACHED_ABEMA_SOURCE_PREPARE), observations.map { it.stage })
            } finally { Thread.interrupted() }
        }
    }

    @Test fun incompleteOrCyclicCauseEvidenceCannotTurnAFailureIntoCancellation() {
        val owner = AbemaCatalogRouteOwner(instance(), null)
        var deep: Throwable = IOException("Synthetic tail")
        repeat(8) { deep = IOException("Synthetic depth", deep) }
        val incomplete = InterruptedException().apply { initCause(deep) }
        val cyclic = InterruptedException()
        cyclic.initCause(IOException("Synthetic cycle", cyclic))
        for (error in listOf(incomplete, cyclic)) {
            var failRead = true
            val observations = mutableListOf<FailureObservation>()
            val guard = AbemaEpisodePlaybackOwnership(owner, { if (failRead) throw error else owner },
                { true }, FailureReporter({ observations += it }))
            try {
                assertFalse(guard.checkWorker())
                assertTrue(Thread.currentThread().isInterrupted)
                Thread.interrupted()
                failRead = false
                assertFalse(guard.canContinue()); assertFalse(guard.checkWorker())
                assertEquals(listOf(FailureStage.CACHED_ABEMA_SOURCE_PREPARE), observations.map { it.stage })
            } finally { Thread.interrupted() }
        }
    }

    @Test fun aCancelledReadCannotUndoAConcurrentGenuineOwnerRevocation() {
        val owner = AbemaCatalogRouteOwner(instance(), null)
        val replacement = AbemaCatalogRouteOwner(instance(SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, routeId, "Saved")), profile())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val cancellingThread = AtomicReference<Thread?>()
        val result = AtomicReference<Boolean?>()
        val interrupted = AtomicBoolean()
        val unexpected = AtomicReference<Throwable?>()
        val guard = AbemaEpisodePlaybackOwnership(owner, {
            if (Thread.currentThread() === cancellingThread.get()) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw ClosedByInterruptException()
            }
            replacement
        }, { true })
        val cancelling = thread {
            cancellingThread.set(Thread.currentThread())
            try { result.set(guard.checkWorker()); interrupted.set(Thread.currentThread().isInterrupted) }
            catch (error: Throwable) { unexpected.set(error) }
            finally { Thread.interrupted() }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(guard.checkWorker())
        } finally { release.countDown(); cancelling.join(5000) }
        assertFalse(cancelling.isAlive)
        assertNull(unexpected.get())
        assertEquals(false, result.get())
        assertTrue(interrupted.get())
        assertFalse(guard.canContinue()); assertFalse(guard.checkWorker())
    }

    @Test fun capturedSavedRouteIdModeAndConfigurationAreStickyBeforeAnyRequest() {
        val selected = instance(SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, routeId, "Saved"))
        val captured = AbemaCatalogRouteOwner(selected, profile())
        for (replacement in listOf(AbemaCatalogRouteOwner(instance(), null),
            AbemaCatalogRouteOwner(selected, profile("changed")),
            AbemaCatalogRouteOwner(selected.copy(setup = ProviderSetup(SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION,
                "12345678-1234-1234-1234-123456789abe", "Other"))), profile()),
            AbemaCatalogRouteOwner(selected.copy(service = PrototypeService.TWITCH), profile()))) {
            var now = replacement
            val guard = AbemaEpisodePlaybackOwnership(captured, { now }, { true })
            assertFalse(guard.checkWorker()); assertFalse(guard.canContinue())
            now = captured; assertFalse(guard.checkWorker())
            assertFalse(guard.toString().contains("proxy"))
        }
    }

    @Test fun cheapForegroundGateNeverReadsProtectedOwnerAndWorkerReadFailureIsRedactedSticky() {
        val owner = AbemaCatalogRouteOwner(instance(), null)
        var reads = 0; var active = true
        val guard = AbemaEpisodePlaybackOwnership(owner, { reads++; owner }, { active })
        repeat(5) { assertTrue(guard.canContinue()) }; assertEquals(0, reads)
        assertTrue(guard.checkWorker()); assertEquals(1, reads)
        active = false; assertFalse(guard.canContinue()); assertFalse(guard.checkWorker()); assertEquals(1, reads)
        val missing = AbemaEpisodePlaybackOwnership(owner, { error("Private fixture") }, { true })
        assertFalse(missing.checkWorker()); assertFalse(missing.canContinue())
    }

    @Test fun foregroundLossDuringWorkerReadCannotPublishItsLateOwnerConfirmation() {
        val owner = AbemaCatalogRouteOwner(instance(), null)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val active = AtomicBoolean(true)
        val guard = AbemaEpisodePlaybackOwnership(owner, { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); owner }, active::get)
        var result = true
        val worker = thread { result = guard.checkWorker() }
        assertTrue(entered.await(5, TimeUnit.SECONDS)); active.set(false); release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive); assertFalse(result); assertFalse(guard.canContinue())
    }
}
