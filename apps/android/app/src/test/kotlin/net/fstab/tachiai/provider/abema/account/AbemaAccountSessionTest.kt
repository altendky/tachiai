package net.fstab.tachiai.provider.abema.account

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

internal class MemoryAbemaAccountStorage : AbemaAccountStorage {
    var bytes: ByteArray? = null; var pending = false; var failWrite = false; var writeHook: () -> Unit = {}; var readHook: () -> Unit = {}
    override fun read(): ByteArray? { readHook(); return bytes?.copyOf() }
    override fun write(plaintext: ByteArray) { if (failWrite) error("PRIVATE STORAGE ERROR"); bytes = plaintext.copyOf(); writeHook() }
    override fun markForgetPending() { pending = true }
    override fun forgetPending() = pending
    override fun finishForget() { pending = false }
}
class AbemaAccountSessionTest {
    private fun fixture() = MemoryAbemaAccountStorage().let { it to AbemaAccountGrantStore(UUID.randomUUID().toString(), it) }
    @Test fun persistedGrantIsUnusableUntilFreshMatchingProfileThenPauseDropsLease() {
        val (_, store) = fixture(); var now = 1000000L
        val session = AbemaAccountSession(store, { now }, { true }); session.load()
        val grant = decodedAbemaGrant(fixtureAccountToken(), "intended-account", now)
        assertTrue(session.acceptReplacement(grant, grant.userId, store.revision()) { true })
        val lease = session.lease()!!; assertTrue(lease.usable())
        val restarted = AbemaAccountSession(store, { now }, { true }); restarted.load(); assertNull(restarted.lease())
        assertFalse(restarted.acceptExisting(restarted.candidate()!!, "other"))
        assertTrue(restarted.acceptExisting(restarted.candidate()!!, grant.userId)); assertNotNull(restarted.lease())
        session.setForeground(false); assertFalse(lease.usable()); session.setForeground(true); assertNull(session.lease())
    }
    @Test fun expiryRollbackOwnerLossAndForgetImmediatelyInvalidateExistingLease() {
        for (loss in 0..3) {
            val (_, store) = fixture(); var now = 1000000L; var owner = true
            val session = AbemaAccountSession(store, { now }, { owner }); session.load()
            val grant = decodedAbemaGrant(fixtureAccountToken(), "intended-account", now)
            assertTrue(session.acceptReplacement(grant, grant.userId, store.revision()) { true }); val lease = session.lease()!!
            when (loss) { 0 -> now = 2000000; 1 -> now--; 2 -> owner = false; 3 -> session.forgetImmediately() }
            assertFalse(lease.usable()); assertNull(session.lease())
        }
    }
    @Test fun failedReplacementPreservesPriorRecordAndOwnerLossDuringWriteRollsBack() {
        val (storage, store) = fixture(); val prior = decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1000000)
        store.replace(prior, 1000000, store.revision()) { true }; val old = storage.bytes!!.copyOf()
        storage.failWrite = true
        try { store.replace(decodedAbemaGrant(fixtureAccountToken("other"), "other", 1000000), 1000001, store.revision()) { true }; fail() } catch (_: AbemaAccountException) { }
        assertArrayEquals(old, storage.bytes); storage.failWrite = false
        var current = true; storage.writeHook = { current = false }
        try { store.replace(decodedAbemaGrant(fixtureAccountToken("other"), "other", 1000000), 1000001, store.revision()) { current }; fail() } catch (_: AbemaAccountException) { }
        assertArrayEquals(old, storage.bytes)
    }
    @Test fun failedForgetLeavesDurablePendingGateAndRetryWritesClearedRecord() {
        val (storage, store) = fixture(); store.replace(decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1000000), 1000000, store.revision()) { true }
        val revision = store.revision(); store.invalidateForget(); assertFalse(store.usable(revision)); storage.failWrite = true
        try { store.forget(); fail() } catch (_: AbemaAccountException) { }
        assertTrue(storage.pending); assertNull(store.read()); storage.failWrite = false; store.forget()
        assertFalse(storage.pending); assertNull(store.read())
    }
    @Test fun encryptedRecordProjectionIsBoundToInstanceAndRefusesTrailingBytes() {
        val (storage, store) = fixture(); store.replace(decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1000000), 1000000, store.revision()) { true }
        val other = AbemaAccountGrantStore(UUID.randomUUID().toString(), storage)
        try { other.read(); fail() } catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.STORAGE_FAILED, error.category) }
        storage.bytes = storage.bytes!! + byteArrayOf(1)
        try { store.read(); fail() } catch (_: AbemaAccountException) { }
    }
    @Test fun fatalWriteAfterPublicationRestoresPriorBytesButPoisonsEveryLeaseAndNormalizesForgetError() {
        val (storage, store) = fixture()
        val session = AbemaAccountSession(store, { 1000000 }, { true }); session.load()
        val prior = decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1000000)
        assertTrue(session.acceptReplacement(prior, prior.userId, store.revision()) { true })
        val lease = session.lease()!!; val old = storage.bytes!!.copyOf(); val loaded = session.candidate()!!
        var calls = 0
        storage.writeHook = { if (++calls == 1) throw AssertionError("PRIVATE FATAL WRITE") }
        try { store.replace(decodedAbemaGrant(fixtureAccountToken("other"), "other", 1000000), 1000001, store.revision()) { true }; fail() }
        catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.STORAGE_FAILED, error.category); assertFalse(error.toString().contains("PRIVATE")); assertNull(error.cause) }
        assertArrayEquals(old, storage.bytes); assertFalse(lease.usable())
        try { store.read(); fail() } catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.STORAGE_FAILED, error.category) }
        assertNull(session.candidate()); assertFalse(session.acceptExisting(loaded, prior.userId))
        storage.writeHook = { throw AssertionError("PRIVATE FATAL FORGET") }
        store.invalidateForget()
        try { store.forget(); fail() } catch (error: AbemaAccountException) { assertEquals(AbemaAccountFailure.STORAGE_FAILED, error.category); assertNull(error.cause) }
        assertTrue(storage.pending); assertFalse(lease.usable())
        storage.writeHook = {}; store.forget(); assertNull(store.read())
    }
    @Test fun anotherSessionForgetOrReplacementCannotResurrectOldLoadedRecord() {
        for (forget in listOf(true, false)) {
            val storage = MemoryAbemaAccountStorage(); val id = UUID.randomUUID().toString()
            val aStore = AbemaAccountGrantStore(id, storage); val bStore = AbemaAccountGrantStore(id, storage)
            val a = AbemaAccountSession(aStore, { 1000000 }, { true }); a.load()
            val prior = decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1000000)
            assertTrue(a.acceptReplacement(prior, prior.userId, aStore.revision()) { true })
            val old = a.candidate()!!; val oldLease = a.lease()!!
            val b = AbemaAccountSession(bStore, { 1000000 }, { true }); b.load()
            if (forget) { b.forgetImmediately(); bStore.forget() }
            else {
                val next = decodedAbemaGrant(fixtureAccountToken("other"), "other", 1000000)
                assertTrue(b.acceptReplacement(next, next.userId, bStore.revision()) { true })
                assertTrue(b.lease()!!.usable())
            }
            assertNull(a.candidate()); assertFalse(a.acceptExisting(old, prior.userId)); assertNull(a.lease()); assertFalse(oldLease.usable())
            assertNotEquals(AbemaAccountStatus.CONNECTED, a.status())
            if (forget) assertNull(bStore.read()) else assertEquals("other", bStore.read()?.grant?.userId)
        }
    }
    @Test fun revisionChangingInsideValidationGateCannotAdoptNewRevisionForOldRecord() {
        val storage = MemoryAbemaAccountStorage(); val id = UUID.randomUUID().toString()
        val aStore = AbemaAccountGrantStore(id, storage); val bStore = AbemaAccountGrantStore(id, storage)
        var invalidate = false
        val a = AbemaAccountSession(aStore, { 1000000 }, {
            if (invalidate) { invalidate = false; bStore.invalidateForget(); bStore.forget() }
            true
        })
        a.load(); val prior = decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1000000)
        assertTrue(a.acceptReplacement(prior, prior.userId, aStore.revision()) { true })
        val old = a.candidate()!!; a.setForeground(false); a.setForeground(true)
        invalidate = true
        assertFalse(a.acceptExisting(old, old.grant.userId)); assertNull(a.lease()); assertNull(a.candidate())
        assertNotEquals(AbemaAccountStatus.CONNECTED, a.status()); assertNull(bStore.read())
    }
    @Test fun heldPublishedWriteCannotBlockPauseOrForgetAndCannotPublishALateLease() {
        for (forget in listOf(false, true)) {
            val (storage, store) = fixture()
            val session = AbemaAccountSession(store, { 1000000 }, { true }); session.load()
            val prior = decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1000000)
            assertTrue(session.acceptReplacement(prior, prior.userId, store.revision()) { true })
            val priorRecord = session.candidate()!!; val lease = session.lease()!!; val priorBytes = storage.bytes!!.copyOf()
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val holdFirst = AtomicBoolean(true)
            val workers = Executors.newFixedThreadPool(2); val ui = Executors.newSingleThreadExecutor()
            storage.writeHook = { if (holdFirst.getAndSet(false)) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            } }
            try {
                val expected = store.revision()
                val replacement = decodedAbemaGrant(fixtureAccountToken("other"), "other", 1000000)
                val writing = workers.submit<Boolean> { session.acceptReplacement(replacement, replacement.userId, expected) { true } }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                ui.submit { if (forget) session.forgetImmediately() else session.setForeground(false) }.get(1, TimeUnit.SECONDS)
                assertFalse(lease.usable()); assertNull(session.lease())
                assertEquals(if (forget) AbemaAccountStatus.MISSING else AbemaAccountStatus.PAUSED, session.status())
                // Durable Forget may wait for the writer, but revocation has
                // already returned. Its success must tolerate that writer's
                // rollback revision and confirm a cleared record afterwards.
                val beforeClearing = store.revision()
                val clearing = if (forget) workers.submit { store.forget() } else null
                if (forget) {
                    val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                    while (store.revision() == beforeClearing && System.nanoTime() < limit) Thread.yield()
                    assertNotEquals(beforeClearing, store.revision())
                }
                release.countDown(); assertFalse(writing.get(2, TimeUnit.SECONDS))
                clearing?.get(2, TimeUnit.SECONDS)
                assertFalse(session.acceptExisting(priorRecord, prior.userId)); assertFalse(lease.usable())
                if (forget) {
                    assertNull(store.read()); assertTrue(store.usable(store.revision()))
                    session.load(); assertNull(session.candidate())
                } else {
                    assertArrayEquals(priorBytes, storage.bytes); assertNotEquals(AbemaAccountStatus.CONNECTED, session.status())
                    session.setForeground(true); assertFalse(lease.usable())
                }
            } finally { release.countDown(); workers.shutdownNow(); ui.shutdownNow() }
        }
    }
    @Test fun heldProtectedOwnerChecksCannotBlockPauseOrForgetOrRevalidateTheirOldSnapshot() {
        for (forget in listOf(false, true)) for (operation in 0..3) {
            val (_, store) = fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val holding = AtomicBoolean(false)
            val session = AbemaAccountSession(store, { 1000000 }, {
                if (holding.get()) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }; true
            })
            session.load(); val grant = decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1000000)
            assertTrue(session.acceptReplacement(grant, grant.userId, store.revision()) { true })
            val record = session.candidate()!!; val lease = session.lease()!!
            val worker = Executors.newSingleThreadExecutor(); val ui = Executors.newSingleThreadExecutor()
            holding.set(true)
            try {
                val checking = worker.submit<Boolean> { when (operation) {
                    0 -> session.candidate() != null
                    1 -> session.acceptExisting(record, grant.userId)
                    2 -> session.lease() != null
                    else -> { session.failed(AbemaAccountFailure.REFUSED); session.status() == AbemaAccountStatus.CONNECTED }
                } }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                ui.submit { if (forget) session.forgetImmediately() else session.setForeground(false) }.get(1, TimeUnit.SECONDS)
                assertFalse(lease.usable()); assertNull(session.lease())
                release.countDown(); assertFalse(checking.get(2, TimeUnit.SECONDS))
                assertEquals(if (forget) AbemaAccountStatus.MISSING else AbemaAccountStatus.PAUSED, session.status())
                assertFalse(session.acceptExisting(record, grant.userId))
            } finally { release.countDown(); worker.shutdownNow(); ui.shutdownNow() }
        }
    }
    @Test fun heldDiskReadCannotUndoConcurrentImmediateForget() {
        val (storage, store) = fixture(); val session = AbemaAccountSession(store, { 1000000 }, { true }); session.load()
        val grant = decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1000000)
        assertTrue(session.acceptReplacement(grant, grant.userId, store.revision()) { true })
        val record = session.candidate()!!; val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor(); val ui = Executors.newSingleThreadExecutor()
        storage.readHook = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            val loading = worker.submit { session.load() }; assertTrue(entered.await(1, TimeUnit.SECONDS))
            ui.submit { session.forgetImmediately() }.get(1, TimeUnit.SECONDS)
            assertEquals(AbemaAccountStatus.MISSING, session.status()); assertNull(session.lease())
            release.countDown(); loading.get(2, TimeUnit.SECONDS)
            assertNull(session.candidate()); assertFalse(session.acceptExisting(record, grant.userId))
            assertEquals(AbemaAccountStatus.MISSING, session.status())
            storage.readHook = {}; store.forget(); assertNull(store.read())
        } finally { release.countDown(); worker.shutdownNow(); ui.shutdownNow() }
    }
    @Test fun confirmedSameAttemptRollbackRebindsOnlyTheExactPriorRecordAndRevokesOldLeasesWhileHeld() {
        for (validated in listOf(true, false)) {
            val (storage, store) = fixture(); val session = AbemaAccountSession(store, { 1000000 }, { true }); session.load()
            val prior = decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1000000)
            assertTrue(session.acceptReplacement(prior, prior.userId, store.revision()) { true })
            val oldLease = session.lease()!!; val oldRecord = session.candidate()!!; val oldBytes = storage.bytes!!.copyOf()
            if (!validated) { session.setForeground(false); session.setForeground(true) }
            val entered = CountDownLatch(1); val release = CountDownLatch(1); var calls = 0
            val worker = Executors.newSingleThreadExecutor()
            storage.writeHook = { when (++calls) {
                1 -> error("PRIVATE WRITE AFTER PUBLICATION")
                2 -> { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                else -> Unit
            } }
            try {
                val expected = store.revision(); val next = decodedAbemaGrant(fixtureAccountToken("other"), "other", 1000000)
                val replacing = worker.submit<Boolean> { session.acceptReplacement(next, next.userId, expected) { true } }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                assertFalse(store.usable(expected)); assertFalse(oldLease.usable()); assertNull(session.lease())
                release.countDown(); assertFalse(replacing.get(2, TimeUnit.SECONDS))
                assertArrayEquals(oldBytes, storage.bytes)
                assertEquals(if (validated) AbemaAccountStatus.CONNECTED else AbemaAccountStatus.UNVERIFIED, session.status())
                assertFalse(oldLease.usable()); assertFalse(session.acceptExisting(oldRecord, prior.userId))
                val restored = session.candidate()!!
                assertEquals(prior.userId, restored.grant.userId)
                if (validated) assertTrue(session.lease()!!.usable()) else {
                    assertNull(session.lease()); assertTrue(session.acceptExisting(restored, prior.userId)); assertTrue(session.lease()!!.usable())
                }
            } finally { release.countDown(); worker.shutdownNow() }
        }
    }
    @Test fun pauseOrOwnerLossDuringPostPublicationOwnerCheckRestoresExactPriorDiskProjection() {
        for (pause in listOf(true, false)) {
            val (storage, store) = fixture(); val holding = AtomicBoolean(false); val owner = AtomicBoolean(true)
            val entered = CountDownLatch(1); val release = CountDownLatch(1); var expected = -1L
            val session = AbemaAccountSession(store, { 1000000 }, {
                if (holding.get() && store.revision() != expected) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                owner.get()
            })
            session.load(); val prior = decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1000000)
            assertTrue(session.acceptReplacement(prior, prior.userId, store.revision()) { true })
            val oldBytes = storage.bytes!!.copyOf(); val oldLease = session.lease()!!; expected = store.revision(); holding.set(true)
            val worker = Executors.newSingleThreadExecutor(); val ui = Executors.newSingleThreadExecutor()
            try {
                val next = decodedAbemaGrant(fixtureAccountToken("other"), "other", 1000000)
                val replacing = worker.submit<Boolean> { session.acceptReplacement(next, next.userId, expected) { true } }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                assertEquals("other", store.read()?.grant?.userId) // The held check is after store publication.
                ui.submit { if (pause) session.setForeground(false) else owner.set(false) }.get(1, TimeUnit.SECONDS)
                assertFalse(oldLease.usable())
                release.countDown(); assertFalse(replacing.get(2, TimeUnit.SECONDS))
                assertArrayEquals(oldBytes, storage.bytes); assertNull(session.lease())
                assertNotEquals(AbemaAccountStatus.CONNECTED, session.status())
            } finally { release.countDown(); worker.shutdownNow(); ui.shutdownNow() }
        }
    }
    @Test fun postPublicationFailureCannotRollBackANewerReplacementOrConfirmedForget() {
        for (forget in listOf(true, false)) {
            val storage = MemoryAbemaAccountStorage(); val id = UUID.randomUUID().toString()
            val store = AbemaAccountGrantStore(id, storage); val newer = AbemaAccountGrantStore(id, storage)
            val holding = AtomicBoolean(false); val owner = AtomicBoolean(true)
            val entered = CountDownLatch(1); val release = CountDownLatch(1); var expected = -1L
            val session = AbemaAccountSession(store, { 1000000 }, {
                if (holding.get() && store.revision() != expected) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                owner.get()
            })
            session.load(); val prior = decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1000000)
            assertTrue(session.acceptReplacement(prior, prior.userId, store.revision()) { true })
            expected = store.revision(); holding.set(true); val worker = Executors.newSingleThreadExecutor()
            try {
                val next = decodedAbemaGrant(fixtureAccountToken("other"), "other", 1000000)
                val replacing = worker.submit<Boolean> { session.acceptReplacement(next, next.userId, expected) { true } }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                if (forget) newer.forget() else {
                    val third = decodedAbemaGrant(fixtureAccountToken("newer"), "newer", 1000000)
                    newer.replace(third, 1000000, newer.revision()) { true }
                }
                val newerBytes = storage.bytes!!.copyOf(); owner.set(false); release.countDown()
                assertFalse(replacing.get(2, TimeUnit.SECONDS)); assertArrayEquals(newerBytes, storage.bytes)
                assertNull(session.lease()); assertNotEquals(AbemaAccountStatus.CONNECTED, session.status())
                if (forget) assertNull(newer.read()) else assertEquals("newer", newer.read()?.grant?.userId)
            } finally { release.countDown(); worker.shutdownNow() }
        }
    }
    @Test fun forgetDuringHeldUncertainRollbackRemainsBlockedUntilConfirmedClear() {
        val (storage, store) = fixture(); val session = AbemaAccountSession(store, { 1000000 }, { true }); session.load()
        val prior = decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1000000)
        assertTrue(session.acceptReplacement(prior, prior.userId, store.revision()) { true })
        val oldLease = session.lease()!!; val entered = CountDownLatch(1); val release = CountDownLatch(1); var calls = 0
        storage.writeHook = { when (++calls) {
            1 -> error("PRIVATE UNCERTAIN WRITE")
            2 -> { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            else -> Unit
        } }
        val workers = Executors.newFixedThreadPool(2); val ui = Executors.newSingleThreadExecutor()
        try {
            val expected = store.revision(); val next = decodedAbemaGrant(fixtureAccountToken("other"), "other", 1000000)
            val replacing = workers.submit<Boolean> { session.acceptReplacement(next, next.userId, expected) { true } }
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertFalse(oldLease.usable())
            ui.submit { session.forgetImmediately() }.get(1, TimeUnit.SECONDS)
            val before = store.revision(); val clearing = workers.submit { store.forget() }
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (store.revision() == before && System.nanoTime() < limit) Thread.yield()
            assertNotEquals(before, store.revision()); assertFalse(store.usable(expected)); assertNull(session.lease())
            release.countDown(); assertFalse(replacing.get(2, TimeUnit.SECONDS)); clearing.get(2, TimeUnit.SECONDS)
            assertNull(store.read()); assertTrue(store.usable(store.revision())); assertNull(session.candidate()); assertFalse(oldLease.usable())
            assertEquals(AbemaAccountStatus.MISSING, session.status())
        } finally { release.countDown(); workers.shutdownNow(); ui.shutdownNow() }
    }
}
