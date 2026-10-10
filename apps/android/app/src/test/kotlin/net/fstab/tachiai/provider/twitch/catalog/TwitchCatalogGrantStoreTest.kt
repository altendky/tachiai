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
import net.fstab.tachiai.provider.twitch.SMART_TV_TWITCH_CLIENT_ID
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
        fun marker(version: Int = 1, instance: String = id, client: String = SMART_TV_TWITCH_CLIENT_ID,
            scope: String = TWITCH_CATALOG_SCOPE, generation: String = ready.generation,
            state: String = "CLEARED") = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(version); listOf(instance, client, scope, generation, state).forEach(out::writeUTF)
            }
        }.toByteArray()
        val malformed = listOf(byteArrayOf(), byteArrayOf(1), original + 0.toByte(), ByteArray(PRIVATE_SECRET_LIMIT + 1),
            marker(version = 3), marker(instance = UUID.randomUUID().toString()), marker(client = "unrelated-client"),
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

    @Test fun versionOnePositiveRecordRemainsProviderBoundAndReadOnly() {
        val memory = Memory(); val id = UUID.randomUUID().toString(); val generation = UUID.randomUUID().toString()
        val original = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(1)
                listOf(id, SMART_TV_TWITCH_CLIENT_ID, TWITCH_CATALOG_SCOPE, generation, "READY", "123").forEach(out::writeUTF)
                out.writeLong(1_000_000L); out.writeLong(1_100_000L)
                out.writeUTF("legacy-access"); out.writeUTF("legacy-refresh")
            }
        }.toByteArray()
        memory.bytes = original.copyOf()
        val grant = store(memory, id).read()!!
        assertEquals(1_100_000L, grant.providerExpiresAtMs); assertEquals(grant.providerExpiresAtMs, grant.expiresAtMs)
        assertNull(grant.localRetentionUntilMs); assertEquals(100_000L, grant.credentials!!.expiresInMs)
        assertEquals(0, memory.writes); assertArrayEquals(original, memory.bytes)
    }

    @Test fun localAndProviderBoundsRoundTripSeparatelyAndTighteningPreservesPairWithoutExtendingCap() {
        val memory = Memory(); val grantStore = store(memory)
        val cap = 1_000_000L + TWITCH_CATALOG_LOCAL_RETENTION_MS
        val old = connect(grantStore, TwitchCatalogReplacement(
            TwitchCatalogCredentials("fixture-access", "fixture-refresh", TWITCH_CATALOG_LOCAL_RETENTION_MS),
            TwitchCatalogValidation("123", setOf(TWITCH_CATALOG_SCOPE), null), TWITCH_CATALOG_LOCAL_RETENTION_MS,
            localRetentionUntilMs = cap, savedAtMs = 1_000_000L))
        assertNull(grantStore.read()!!.providerExpiresAtMs)
        assertEquals(cap, grantStore.read()!!.localRetentionUntilMs)
        val tightened = grantStore.tightenProviderExpiry(old, 1_060_000L)!!
        assertEquals(old.generation, tightened.generation); assertTrue(grantStore.isStoredCurrent(old))
        assertEquals(1_000_000L, tightened.savedAtMs); assertEquals(cap, tightened.localRetentionUntilMs)
        assertEquals(1_060_000L, tightened.providerExpiresAtMs); assertEquals(1_060_000L, tightened.expiresAtMs)
        val writes = memory.writes
        assertEquals(tightened.generation, grantStore.tightenProviderExpiry(tightened, 1_120_000L)!!.generation)
        assertEquals(writes, memory.writes)
    }

    @Test fun absoluteBoundsCannotBeExtendedByDelayedCommitOrRefreshAndExpiredCapStopsExchange() {
        val memory = Memory(); var wall = 1_000_000L
        val grantStore = TwitchCatalogGrantStore(memory, UUID.randomUUID().toString(), wallMs = { wall })
        val replacement = TwitchCatalogReplacement(TwitchCatalogCredentials("fixture-access", "fixture-refresh", 1000),
            TwitchCatalogValidation("123", setOf(TWITCH_CATALOG_SCOPE), null), 1000,
            localRetentionUntilMs = wall + 1000, savedAtMs = wall)
        val attempt = grantStore.beginConnection(); wall += 500
        val old = grantStore.commitConnection(attempt, replacement)!!
        assertEquals(1_001_000L, old.expiresAtMs); assertEquals(1_000_000L, old.savedAtMs)
        val next = grantStore.refresh(old) {
            wall += 100
            TwitchCatalogReplacement(TwitchCatalogCredentials("new-access", "new-refresh", 400),
                TwitchCatalogValidation("123", setOf(TWITCH_CATALOG_SCOPE), null), 400,
                localRetentionUntilMs = old.localRetentionUntilMs, savedAtMs = old.savedAtMs)
        }!!
        assertEquals(old.localRetentionUntilMs, next.localRetentionUntilMs); assertEquals(old.savedAtMs, next.savedAtMs)
        wall = 1_001_000L
        val writes = memory.writes
        val error = assertThrows(TwitchCatalogAuthException::class.java) {
            grantStore.refresh(next) { error("Expired cap must not consume or exchange the pair") }
        }
        assertEquals(TwitchCatalogAuthFailure.EXPIRED, error.failure); assertEquals(writes, memory.writes)
        assertFalse(grantStore.isStoredCurrent(next))
        val newAttempt = grantStore.beginConnection()
        assertThrows(TwitchCatalogAuthException::class.java) { grantStore.commitConnection(newAttempt, replacement) }
        assertEquals(TwitchCatalogGrantState.RECONNECT, grantStore.read()!!.state)
    }

    @Test fun versionTwoRejectsMissingNonpositiveOrOverlongBoundsWithoutImplicitWrite() {
        val memory = Memory(); val id = UUID.randomUUID().toString(); val grantStore = store(memory, id)
        fun encoded(provider: Long?, local: Long?) = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(2)
                listOf(id, SMART_TV_TWITCH_CLIENT_ID, TWITCH_CATALOG_SCOPE,
                    UUID.randomUUID().toString(), "READY", "123").forEach(out::writeUTF)
                out.writeLong(1_000_000L)
                out.writeBoolean(provider != null); provider?.let(out::writeLong)
                out.writeBoolean(local != null); local?.let(out::writeLong)
                out.writeUTF("fixture-access"); out.writeUTF("fixture-refresh")
            }
        }.toByteArray()
        listOf(encoded(null, null), encoded(0L, null), encoded(-1L, 1_100_000L),
            encoded(null, 1_000_000L), encoded(null, 1_000_001L + TWITCH_CATALOG_LOCAL_RETENTION_MS)).forEach { original ->
            memory.bytes = original.copyOf()
            val failure = assertThrows(TwitchCatalogStoreException::class.java) { grantStore.read() }
            assertEquals(TwitchCatalogStoreFailure.INVALID_RECORD, failure.failure)
            assertArrayEquals(original, memory.bytes); assertEquals(0, memory.writes)
        }
    }

    @Test fun postWriteRefreshExpiryCarriesOnlyItsAtomicallyWrittenSecretlessMarker() {
        val memory = Memory(); var wall = 1_000_000L
        val delayed = object : PrivateSecretStore {
            override fun read() = memory.read()
            override fun write(plaintext: ByteArray) {
                memory.write(plaintext)
                if (String(plaintext, Charsets.ISO_8859_1).contains("new-access")) wall += 1000
            }
        }
        val grantStore = TwitchCatalogGrantStore(delayed, UUID.randomUUID().toString(), wallMs = { wall })
        val original = connect(grantStore, TwitchCatalogReplacement(TwitchCatalogCredentials("fixture-access", "fixture-refresh", 1000),
            TwitchCatalogValidation("123", setOf(TWITCH_CATALOG_SCOPE), null), 1000,
            localRetentionUntilMs = wall + 1000, savedAtMs = wall))
        val error = assertThrows(TwitchCatalogAbandonedGrantException::class.java) {
            grantStore.refresh(original) {
                TwitchCatalogReplacement(TwitchCatalogCredentials("new-access", "new-refresh", 1000),
                    TwitchCatalogValidation("123", setOf(TWITCH_CATALOG_SCOPE), null), 1000,
                    localRetentionUntilMs = original.localRetentionUntilMs, savedAtMs = original.savedAtMs)
            }
        }
        assertEquals(TwitchCatalogAuthFailure.EXPIRED, error.failure)
        assertNotEquals(original.generation, error.marker.generation)
        assertEquals(grantStore.read()!!.generation, error.marker.generation)
        assertEquals(TwitchCatalogGrantState.RECONNECT, error.marker.state)
        assertNull(error.marker.credentials); assertNull(error.marker.userId)
        assertNull(error.cause); assertFalse(error.toString().contains("new-access"))
        assertFalse(error.toString().contains(error.marker.generation))
    }

    @Test fun differentValidClientRequiresExplicitReconnectBeforeAnyCredentialsAreReadOrRecordIsChanged() {
        val memory = Memory(); val id = UUID.randomUUID().toString(); val grantStore = store(memory, id)
        // Only the binding header is present: the different client's token
        // schema is never read or accepted as this connection's credentials.
        val previous = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(1); out.writeUTF(id); out.writeUTF("FixturePriorClient123456789")
            }
        }.toByteArray()
        memory.bytes = previous.copyOf()
        val error = assertThrows(TwitchCatalogStoreException::class.java) { grantStore.read() }
        assertEquals(TwitchCatalogStoreFailure.CLIENT_MISMATCH, error.failure)
        assertEquals(0, memory.writes); assertArrayEquals(previous, memory.bytes)
        val connected = connect(grantStore)
        assertEquals(TwitchCatalogGrantState.READY, grantStore.read()!!.state)
        assertEquals(connected.generation, grantStore.read()!!.generation)
        assertTrue(String(memory.bytes!!, Charsets.ISO_8859_1).contains(SMART_TV_TWITCH_CLIENT_ID))
        assertFalse(String(memory.bytes!!, Charsets.ISO_8859_1).contains("FixturePriorClient123456789"))
    }

    @Test fun explicitForgetCanReplaceDifferentClientEvenWithLastObservedGeneration() {
        val memory = Memory(); val id = UUID.randomUUID().toString(); val grantStore = store(memory, id)
        val old = connect(grantStore)
        memory.bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(1); out.writeUTF(id); out.writeUTF("FixturePriorClient123456789")
            }
        }.toByteArray()
        val revision = grantStore.invalidate()
        assertTrue(grantStore.forget(revision, old.generation))
        assertEquals(TwitchCatalogGrantState.CLEARED, grantStore.read()!!.state)
        assertFalse(grantStore.isRevisionCurrent(old.revision))
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
