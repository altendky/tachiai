package net.fstab.tachiai.provider.abema.account

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AbemaCachedAccountScopeTest {
    @Test fun retiredHeldFlightBlocksReplacementUntilCleanupAndReleasesRetainedOwnerWhenIdle() {
        val instanceId = UUID.randomUUID().toString(); val storage = MemoryAbemaAccountStorage()
        val store = AbemaAccountGrantStore(instanceId, storage); val registry = AbemaAccountAccessOwnerRegistry()
        var originalCurrent = true
        fun create() = AbemaAccountAccessOwner(instanceId, "fixture-owner", store, { 10000L }, { 1000L },
            { originalCurrent }, { error("Guest must not create HTTP") }, { true })
        val original = registry.owner(instanceId, "fixture-owner", { originalCurrent }, ::create)
        val old = original.attach(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        storage.readHook = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val result = AtomicReference<AbemaAccountAccessResult>()
        val worker = Thread { result.set(old.acquire()) }
        try {
            worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); originalCurrent = false
            assertEquals(AbemaAccountFailure.CANCELLED, assertThrows(AbemaAccountException::class.java) {
                registry.owner(instanceId, "fixture-owner", { true }) { error("Held cleanup must block allocation") }
            }.category)
            assertEquals(1, registry.retiredCount); assertFalse(original.cleanupConfirmed)
            release.countDown(); worker.join(5000); assertFalse(worker.isAlive); old.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while ((!original.isIdle || registry.retiredCount != 0) && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(original.isIdle); assertEquals(0, registry.retiredCount)
            originalCurrent = true
            val replacement = registry.owner(instanceId, "fixture-owner", { true }, ::create)
            assertNotSame(original, replacement)
            val fresh = replacement.attach()
            try { assertTrue((fresh.acquire() as AbemaAccountAccessResult.Guest).lease.checkWorker()) }
            finally { fresh.close() }
        } finally { release.countDown(); old.close(); worker.join(5000) }
    }
    @Test fun revokedOriginalBindingCannotTrapRestoredNewBindingOrReviveOldLease() {
        val instanceId = UUID.randomUUID().toString()
        val store = AbemaAccountGrantStore(instanceId, MemoryAbemaAccountStorage())
        val registry = AbemaAccountAccessOwnerRegistry()
        var originalBindingCurrent = true
        fun create(current: () -> Boolean) = AbemaAccountAccessOwner(instanceId, "fixture-owner", store,
            { 10000L }, { 1000L }, current, { error("Guest must not create HTTP") }, { true })
        val original = registry.owner(instanceId, "fixture-owner", { originalBindingCurrent }) { create { originalBindingCurrent } }
        val old = original.attach()
        val oldLease = (old.acquire() as AbemaAccountAccessResult.Guest).lease
        assertTrue(oldLease.current())
        // The first binding observed an edit. Restoring configuration permits a
        // new binding, while the original admission remains permanently false.
        originalBindingCurrent = false
        val replacement = registry.owner(instanceId, "fixture-owner", { true }) { create { true } }
        val fresh = replacement.attach()
        try {
            assertNotSame(original, replacement); assertFalse(oldLease.current())
            assertEquals(AbemaAccountFailure.CANCELLED, (old.acquire() as AbemaAccountAccessResult.Failed).category)
            assertTrue((fresh.acquire() as AbemaAccountAccessResult.Guest).lease.checkWorker())
            assertSame(replacement, registry.owner(instanceId, "fixture-owner", { true }) { error("Healthy exact proof must share") })
            assertEquals(1, registry.retiredCount)
            old.close()
            assertTrue(original.isIdle); assertEquals(0, registry.retiredCount)
            assertTrue((fresh.acquire() as AbemaAccountAccessResult.Guest).lease.checkWorker())
        } finally { old.close(); fresh.close() }
    }
    // #633: two feeds of one instance attach and acquire at once. Both share
    // the registry owner and its single validation flight.
    @Test fun concurrentAttachesShareOneOwnerAndOneValidationFlight() {
        val instanceId = UUID.randomUUID().toString()
        val store = AbemaAccountGrantStore(instanceId, MemoryAbemaAccountStorage())
        store.replace(decodedAbemaGrant(fixtureAccountToken(), "intended-account", 1000000L), 1000000L, store.revision()) { true }
        val registry = AbemaAccountAccessOwnerRegistry()
        val profiles = AtomicInteger()
        val profileEntered = CountDownLatch(1); val bothAttached = CountDownLatch(2)
        fun create() = AbemaAccountAccessOwner(instanceId, "fixture-owner", store, { 1000000L }, { 1000L }, { true }, { _ ->
            object : AbemaAccountTransport {
                override fun profile(id: String, bearer: String): AbemaAccountProfileResult {
                    profiles.incrementAndGet(); profileEntered.countDown()
                    check(bothAttached.await(5, TimeUnit.SECONDS))
                    return AbemaAccountProfileResult.Identity("intended-account")
                }
                override fun emailRegistration(id: String, bearer: String) = AbemaAccountEmailResult.REGISTERED
                override fun token(challenge: AbemaAccountChallenge): AbemaAccountTokenResult = error("NO TOKEN EXCHANGE")
                override fun cancelActiveRequest() {}
                override fun close() {}
            } }, { true })
        val start = CountDownLatch(1)
        val results = List(2) { AtomicReference<Any>() }
        val attachments = List(2) { AtomicReference<AbemaAccountAccessOwner.Attachment>() }
        val feeds = List(2) { index -> Thread {
            try {
                check(start.await(5, TimeUnit.SECONDS))
                val owner = registry.owner(instanceId, "fixture-owner", { true }, ::create)
                val attached = attachAbemaAccountAccessOwner(owner, { true }, { error("No cleanup failure") })
                attachments[index].set(attached); bothAttached.countDown()
                // The second feed joins while the first feed's validation is in flight.
                if (index == 1) check(profileEntered.await(5, TimeUnit.SECONDS))
                results[index].set(attached.acquire())
            } catch (error: Throwable) { results[index].set(error); bothAttached.countDown() }
        } }
        try {
            feeds.forEach(Thread::start); start.countDown(); feeds.forEach { it.join(10000) }
            results.forEach { assertTrue(it.get().toString(), it.get() is AbemaAccountAccessResult.Account) }
            assertEquals(1, profiles.get()); assertEquals(0, registry.retiredCount)
        } finally { attachments.forEach { it.get()?.close() } }
    }
    @Test fun cleanupPoisonRemainsPerInstanceAcrossCandidateProofChanges() {
        val instanceId = UUID.randomUUID().toString()
        val poison = AbemaAccountWorkers.poisoned(instanceId)
        assertFalse(poison.get())
        AndroidAbemaAccountAccessOwners.poison(AbemaAccountAccessIpc.Request("a".repeat(32), instanceId,
            "b".repeat(64), "c".repeat(64), 46000, false))
        assertTrue(poison.get())
        assertSame(poison, AbemaAccountWorkers.poisoned(instanceId))
        AndroidAbemaAccountAccessOwners.poison(AbemaAccountAccessIpc.Request("d".repeat(32), instanceId,
            "e".repeat(64), "f".repeat(64), 46000, false))
        assertTrue(AbemaAccountWorkers.poisoned(instanceId).get())
        assertFalse(AbemaAccountWorkers.poisoned(UUID.randomUUID().toString()).get())
    }
    @Test fun cachedValidationCannotReplaceFreshAttachScopeAndRefusalPreservesExistingPeer() {
        val instanceId = UUID.randomUUID().toString()
        val store = AbemaAccountGrantStore(instanceId, MemoryAbemaAccountStorage())
        val owner = AbemaAccountAccessOwner(instanceId, "fixture-owner", store, { 10000L }, { 1000L },
            { true }, { error("Empty guest grant must not create provider HTTP") }, { true })
        val peer = owner.attach()
        try {
            val lease = (peer.acquire() as AbemaAccountAccessResult.Guest).lease
            assertTrue(lease.checkWorker())
            var poison = false
            assertEquals(AbemaAccountFailure.REFUSED, assertThrows(AbemaAccountException::class.java) {
                attachAbemaAccountAccessOwner(owner, { false }, { poison = true })
            }.category)
            var checks = 0
            assertEquals(AbemaAccountFailure.REFUSED, assertThrows(AbemaAccountException::class.java) {
                attachAbemaAccountAccessOwner(owner, { ++checks == 1 }, { poison = true })
            }.category)
            assertEquals(2, checks); assertFalse(poison); assertTrue(lease.checkWorker())
            val attached = attachAbemaAccountAccessOwner(owner, { true }, { poison = true })
            try { assertTrue((attached.acquire() as AbemaAccountAccessResult.Guest).lease.checkWorker()) }
            finally { attached.close() }
            assertTrue(lease.current()); assertFalse(poison)
        } finally { peer.close(); owner.close() }
    }
}
