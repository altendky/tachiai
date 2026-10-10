package net.fstab.tachiai.provider.twitch.catalog

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.defaultProviderInstanceId
import net.fstab.tachiai.provider.twitch.TACHIAI_TWITCH_CLIENT_ID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TwitchCatalogGrantStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Memory : PrivateSecretStore {
        @Volatile var bytes: ByteArray? = null
        @Volatile var failWrite = false
        var writes = 0
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) {
            if (failWrite) throw IOException("fixture storage failure")
            bytes = plaintext.copyOf(); writes++
        }
    }
    private fun replacement(token: String = "fixture-access", refresh: String = "fixture-refresh", user: String = "123",
        lifetime: Long = 7_200_000L) = TwitchCatalogReplacement(
        TwitchCatalogCredentials(token, refresh, lifetime),
        TwitchCatalogValidation(user, setOf(TWITCH_CATALOG_SCOPE), lifetime), lifetime)
    private fun store(memory: PrivateSecretStore, id: String = UUID.randomUUID().toString()) =
        TwitchCatalogGrantStore(memory, id, wallMs = { 1_000_000L })
    private fun connect(store: TwitchCatalogGrantStore, replacement: TwitchCatalogReplacement = replacement()) =
        store.commitConnection(store.beginConnection(), replacement)!!

    @Test fun absentReadIsReadOnlyAndExplicitEmptyTombstoneSurvivesRestart() {
        val memory = Memory(); val id = UUID.randomUUID().toString(); val first = store(memory, id)
        assertNull(first.read()); assertEquals(0, memory.writes)
        val ready = connect(first)
        assertEquals("123", ready.userId); assertTrue(first.isStoredCurrent(ready))
        first.forget()
        val tombstone = memory.bytes!!.copyOf()
        assertEquals(TwitchCatalogGrantState.CLEARED, store(memory, id).read()!!.state)
        assertArrayEquals(tombstone, memory.bytes); assertFalse(first.isStoredCurrent(ready))
        assertFalse(String(tombstone, Charsets.ISO_8859_1).contains("fixture-access"))
        assertFalse(String(tombstone, Charsets.ISO_8859_1).contains("fixture-refresh"))
    }

    @Test fun exactInstanceClientScopeVersionAndCompleteCodecAreRequiredWithoutImplicitOverwrite() {
        val memory = Memory(); val id = UUID.randomUUID().toString(); val grantStore = store(memory, id)
        val ready = connect(grantStore); val original = memory.bytes!!.copyOf()
        fun marker(version: Int = 1, instance: String = id, client: String = TACHIAI_TWITCH_CLIENT_ID,
            scope: String = TWITCH_CATALOG_SCOPE, generation: String = ready.generation,
            state: String = "CLEARED") = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(version); listOf(instance, client, scope, generation, state).forEach(out::writeUTF)
            }
        }.toByteArray()
        val malformed = listOf(byteArrayOf(), byteArrayOf(1), original + 0.toByte(), ByteArray(PRIVATE_SECRET_LIMIT + 1),
            marker(version = 2), marker(instance = UUID.randomUUID().toString()), marker(client = "unrelated-client"),
            marker(scope = ""), marker(generation = "../slot"), marker(state = "UNKNOWN"), marker(state = "READY"))
        malformed.forEach { bytes ->
            memory.bytes = bytes.copyOf(); val writes = memory.writes
            val error = assertThrows(TwitchCatalogStoreException::class.java) { grantStore.read() }
            assertEquals(TwitchCatalogStoreFailure.INVALID_RECORD, error.failure)
            assertArrayEquals(bytes, memory.bytes); assertEquals(writes, memory.writes)
        }
        grantStore.forget() // Explicit user intent can clear an unreadable record.
        assertEquals(TwitchCatalogGrantState.CLEARED, grantStore.read()!!.state)
    }

    @Test fun boundedMaximumPairRoundTripsWithoutPersistingTransientLogin() {
        val memory = Memory(); val grantStore = store(memory)
        val credentials = TwitchCatalogCredentials("a".repeat(2048), "r".repeat(2048), 1_000L)
        val validation = TwitchCatalogValidation("123", setOf(TWITCH_CATALOG_SCOPE), 1_000L, "fixturelogin")
        grantStore.commitConnection(grantStore.beginConnection(), TwitchCatalogReplacement(credentials, validation, 1_000L))
        assertTrue(memory.bytes!!.size <= PRIVATE_SECRET_LIMIT)
        assertEquals(credentials.accessToken, grantStore.read()!!.credentials!!.accessToken)
        assertFalse(String(memory.bytes!!, Charsets.ISO_8859_1).contains("fixturelogin"))
        assertThrows(IllegalArgumentException::class.java) { TwitchCatalogCredentials("a".repeat(2049), "r", 1000L) }
    }

    @Test fun rejectedIdentityAndGenerationCannotReplaceAnotherInstanceOrAccount() {
        val memory = Memory(); val first = store(memory); val ready = connect(first)
        val otherMemory = Memory(); val other = store(otherMemory); val otherReady = connect(other, replacement(user = "456"))
        assertFalse(other.isStoredCurrent(ready)); assertFalse(first.isStoredCurrent(otherReady))
        assertThrows(IllegalArgumentException::class.java) { store(memory, "../slot") }
        assertThrows(IllegalArgumentException::class.java) { store(memory, defaultProviderInstanceId(PrototypeService.ABEMA)) }
        val prior = first.beginConnection(); val newest = first.beginConnection()
        assertNull(first.commitConnection(prior, replacement(user = "old-user")))
        assertNotNull(first.commitConnection(newest, replacement(user = "new-user")))
        val newestBytes = memory.bytes!!.copyOf()
        first.requireReconnect(ready)
        assertArrayEquals(newestBytes, memory.bytes)
        assertTrue(other.isStoredCurrent(otherReady))
    }

    @Test fun refreshDurablyRemovesBothOldTokensBeforeOneExchangeAndRotatesPairTogether() {
        val memory = Memory(); val grantStore = store(memory); val old = connect(grantStore)
        var calls = 0
        val next = grantStore.refresh(old) { credentials ->
            calls++
            assertEquals("fixture-refresh", credentials.refreshToken)
            val marker = grantStore.read()!!
            assertEquals(TwitchCatalogGrantState.REFRESHING, marker.state); assertNull(marker.credentials)
            val encoded = String(memory.bytes!!, Charsets.ISO_8859_1)
            assertFalse(encoded.contains("fixture-access")); assertFalse(encoded.contains("fixture-refresh"))
            replacement("new-access", "new-refresh")
        }!!
        assertEquals(1, calls); assertNotEquals(old.generation, next.generation)
        assertEquals("new-access", next.credentials!!.accessToken); assertEquals("new-refresh", next.credentials.refreshToken)
        assertFalse(grantStore.isStoredCurrent(old)); assertTrue(grantStore.isStoredCurrent(next))
        assertNull(grantStore.refresh(old) { error("Single-use credential must not be retried") })
    }

    @Test fun lostResponseAndFailedPairCommitKeepInterruptedMarkerAndNeverRetrySingleUsePair() {
        listOf(false, true).forEach { failCommit ->
            val memory = Memory(); val id = UUID.randomUUID().toString(); val grantStore = store(memory, id)
            val old = connect(grantStore)
            assertThrows(Exception::class.java) {
                grantStore.refresh(old) {
                    if (!failCommit) throw IOException("fixture lost response")
                    memory.failWrite = true
                    replacement("new-access", "new-refresh")
                }
            }
            memory.failWrite = false
            val restarted = store(memory, id)
            assertEquals(TwitchCatalogGrantState.REFRESHING, restarted.read()!!.state)
            assertNull(restarted.read()!!.credentials)
            assertNull(restarted.refresh(old) { error("No retry after ambiguous consumption") })
        }
    }

    @Test fun failedMarkerWriteDoesNotStartRequestAndFailedForgetImmediatelyBlocksAllLocalLeases() {
        val memory = Memory(); val id = UUID.randomUUID().toString(); val first = store(memory, id)
        val other = store(memory, id); val old = connect(first); val original = memory.bytes!!.copyOf()
        memory.failWrite = true
        assertThrows(TwitchCatalogStoreException::class.java) { first.refresh(old) { error("Marker must commit first") } }
        assertArrayEquals(original, memory.bytes)
        assertThrows(TwitchCatalogStoreException::class.java) { first.forget() }
        assertFalse(other.isStoredCurrent(old))
        memory.failWrite = false
        first.forget(); assertEquals(TwitchCatalogGrantState.CLEARED, other.read()!!.state)
    }

    @Test fun immediateForgetGuardRejectsLateRefreshBeforeTheClearCanAcquireTransactionLock() {
        val memory = Memory(); val id = UUID.randomUUID().toString(); val first = store(memory, id)
        val other = store(memory, id); val old = connect(first)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val workers = Executors.newFixedThreadPool(2)
        try {
            val refreshing = workers.submit<TwitchCatalogStoredGrant?> {
                first.refresh(old) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); replacement("late", "late-refresh") }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            other.invalidate() // Accepted clear must invalidate before queuing the IO job.
            val forgetting = workers.submit { other.forget() }
            assertFalse(first.isRevisionCurrent(old.revision))
            release.countDown()
            assertNull(refreshing.get(5, TimeUnit.SECONDS)); forgetting.get(5, TimeUnit.SECONDS)
            assertEquals(TwitchCatalogGrantState.CLEARED, first.read()!!.state)
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun completeRefreshHoldsOsLockAndTwoControllersSpendSingleUsePairAtMostOnce() {
        val file = temporary.newFile("catalog.lock"); val memory = Memory(); val id = UUID.randomUUID().toString()
        fun assertLocked() = RandomAccessFile(file, "rw").use { handle ->
            assertThrows(OverlappingFileLockException::class.java) { handle.channel.tryLock() }
        }
        val guarded = object : PrivateSecretStore {
            override fun read(): ByteArray? { assertLocked(); return memory.read() }
            override fun write(plaintext: ByteArray) { assertLocked(); memory.write(plaintext) }
        }
        val first = TwitchCatalogGrantStore(guarded, id, file, { 1_000_000L })
        val other = TwitchCatalogGrantStore(guarded, id, file, { 1_000_000L }); val old = connect(first)
        val workers = Executors.newFixedThreadPool(2); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val one = workers.submit<TwitchCatalogStoredGrant?> {
                first.refresh(old) { assertLocked(); entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); replacement("new", "new-refresh") }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val two = workers.submit<TwitchCatalogStoredGrant?> { other.refresh(old) { error("Already spent by another controller") } }
            release.countDown()
            assertNotNull(one.get(5, TimeUnit.SECONDS)); assertNull(two.get(5, TimeUnit.SECONDS))
            RandomAccessFile(file, "rw").use { handle -> handle.channel.tryLock().use { assertNotNull(it) } }
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun ownerChangeDuringWriteRejectsPairAndLeavesReconnectMarker() {
        val memory = Memory(); var ownerMatches = true
        val guarded = object : PrivateSecretStore {
            override fun read() = memory.read()
            override fun write(plaintext: ByteArray) {
                memory.write(plaintext)
                if (String(plaintext, Charsets.ISO_8859_1).contains("fixture-access")) ownerMatches = false
            }
        }
        val grantStore = store(guarded); val attempt = grantStore.beginConnection()
        assertNull(grantStore.commitConnection(attempt, replacement()) { ownerMatches })
        assertEquals(TwitchCatalogGrantState.RECONNECT, grantStore.read()!!.state)
        assertNull(grantStore.read()!!.credentials)
    }

    @Test fun failedClearPersistsNoSecretIntentAndRestartCannotValidateOrConnectUntilRetry() {
        val file = temporary.newFile("durable-clear.lock"); val memory = Memory(); val id = UUID.randomUUID().toString()
        val first = TwitchCatalogGrantStore(memory, id, file, { 1_000_000L }); val old = connect(first)
        memory.failWrite = true
        assertThrows(TwitchCatalogStoreException::class.java) { first.forget() }
        val marker = java.io.File(file.parentFile, "${file.name}.forget-pending")
        assertTrue(marker.exists()); assertEquals(4, marker.length())
        assertFalse(String(marker.readBytes(), Charsets.ISO_8859_1).contains("fixture"))
        // Model process death: discard only this binding's in-memory signals.
        val bindings = TwitchCatalogGrantStore::class.java.getDeclaredField("sharedBindings").apply { isAccessible = true }
            .get(null) as java.util.concurrent.ConcurrentHashMap<*, *>
        bindings.remove(id)
        val restarted = TwitchCatalogGrantStore(memory, id, file, { 1_000_000L })
        val read = assertThrows(TwitchCatalogStoreException::class.java) { restarted.read() }
        assertEquals(TwitchCatalogStoreFailure.STORAGE, read.failure)
        assertThrows(TwitchCatalogStoreException::class.java) { restarted.beginConnection() }
        assertThrows(TwitchCatalogStoreException::class.java) { restarted.refresh(old) { error("Pending clear denies refresh") } }
        memory.failWrite = false
        assertTrue(restarted.forget())
        assertFalse(marker.exists()); assertEquals(TwitchCatalogGrantState.CLEARED, restarted.read()!!.state)
        assertNotNull(restarted.beginConnection())
    }

    @Test fun staleAcceptedClearCannotEraseNewDurableGenerationEvenWithIndependentProcessRevision() {
        val memory = Memory(); val id = UUID.randomUUID().toString(); val first = store(memory, id)
        val old = connect(first); val expectedRevision = first.invalidate()
        // Another process shares the encrypted record, but no in-memory revision.
        val bindings = TwitchCatalogGrantStore::class.java.getDeclaredField("sharedBindings").apply { isAccessible = true }
            .get(null) as java.util.concurrent.ConcurrentHashMap<*, *>
        bindings.remove(id)
        val other = store(memory, id); val newer = connect(other, replacement(user = "456"))
        assertFalse(first.forget(expectedRevision, old.generation))
        assertEquals(newer.generation, other.read()!!.generation)
        assertEquals("456", other.read()!!.userId)
    }

    @Test fun oldClearIntentMayFinishDurabilityAfterPairWasAlreadyReplacedWithEmptyTombstone() {
        val memory = Memory(); val id = UUID.randomUUID().toString(); val first = store(memory, id)
        val old = connect(first); val expectedRevision = first.invalidate()
        val bindings = TwitchCatalogGrantStore::class.java.getDeclaredField("sharedBindings").apply { isAccessible = true }
            .get(null) as java.util.concurrent.ConcurrentHashMap<*, *>
        bindings.remove(id)
        val other = store(memory, id)
        other.forget()
        assertEquals(TwitchCatalogGrantState.CLEARED, other.read()!!.state)
        // Equivalent to a committed tombstone followed by failed marker-removal
        // directory sync: the known old identity must still allow empty Retry.
        assertTrue(first.forget(expectedRevision, old.generation))
        assertEquals(TwitchCatalogGrantState.CLEARED, first.read()!!.state)
        assertNull(first.read()!!.credentials)
    }
}
