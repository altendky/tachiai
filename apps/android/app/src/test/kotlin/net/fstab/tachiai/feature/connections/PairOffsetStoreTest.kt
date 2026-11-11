package net.fstab.tachiai.feature.connections

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.MAX_PAIR_OFFSET_MS
import net.fstab.tachiai.presentation.PairOffsetPreference
import net.fstab.tachiai.presentation.PairOffsetCorrespondence
import net.fstab.tachiai.platform.media.MAX_NATIVE_TIMELINE_SAMPLES
import net.fstab.tachiai.platform.media.NativeHlsTimelineSample
import net.fstab.tachiai.platform.media.NativeTimelineEvidence
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PairOffsetStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var writes = 0
        var failRead = false
        var failWrite = false
        override fun read(): ByteArray? {
            if (failRead) throw IOException("Synthetic private storage detail")
            return bytes?.copyOf()
        }
        override fun write(plaintext: ByteArray) {
            if (failWrite) throw IOException("Synthetic private storage detail")
            bytes = plaintext.copyOf(); writes++
        }
    }
    private fun key(index: Int) = index.toString(16).padStart(64, '0')
    private fun encoded(version: Int = 1, count: Int = 1, key: String = key(1), offset: Long = 26_400,
        enabled: Int = 1, duplicate: Boolean = false, trailing: Boolean = false): ByteArray =
        ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(version); data.writeInt(count)
            repeat(if (duplicate) 2 else 1) { data.writeUTF(key); data.writeLong(offset); data.writeByte(enabled) }
            if (trailing) data.writeByte(0)
        } }.toByteArray()

    @Test fun missingReadHasNoWritesAndRoundTripPreservesOtherPairsAndDisabledValue() {
        val memory = Memory(); val store = PairOffsetStore(memory)
        assertTrue(store.read().isEmpty()); assertEquals(0, memory.writes)
        val first = PairOffsetPreference(26_400, true)
        val second = PairOffsetPreference(-5_000, false)
        store.save(key(1), first); store.save(key(2), second)
        assertEquals(mapOf(key(1) to first, key(2) to second), PairOffsetStore(memory).read())
        store.save(key(1), first.copy(enabled = false))
        assertEquals(first.copy(enabled = false), store.read()[key(1)])
        assertEquals(second, store.read()[key(2)])
        assertEquals(3, memory.writes)
    }

    @Test fun corruptRecordsAlwaysFailClosedAndSaveNeverReplacesThem() {
        val corrupt = listOf(byteArrayOf(), encoded(version = 3), encoded(count = -1), encoded(count = 65),
            encoded(key = "invalid"), encoded(offset = MAX_PAIR_OFFSET_MS + 1), encoded(offset = Long.MIN_VALUE),
            encoded(enabled = 2), encoded(count = 2, duplicate = true), encoded(trailing = true),
            encoded().dropLast(1).toByteArray(), ByteArray(PRIVATE_SECRET_LIMIT + 1))
        for (bytes in corrupt) {
            val memory = Memory().also { it.bytes = bytes.copyOf() }; val store = PairOffsetStore(memory)
            val read = assertThrows(PairOffsetStoreException::class.java) { store.read() }
            assertEquals(PairOffsetStoreFailure.INVALID_RECORD, read.failure)
            val save = assertThrows(PairOffsetStoreException::class.java) { store.save(key(2), PairOffsetPreference(0, true)) }
            assertEquals(PairOffsetStoreFailure.INVALID_RECORD, save.failure)
            assertArrayEquals(bytes, memory.bytes); assertEquals(0, memory.writes)
            assertNull(read.cause); assertEquals("INVALID_RECORD", read.message)
        }
    }

    @Test fun boundedCapacityAllowsUpdatingExistingPairWithoutSilentlyEvictingAnother() {
        val memory = Memory(); val store = PairOffsetStore(memory)
        repeat(MAX_PAIR_OFFSET_RECORDS) { store.save(key(it), PairOffsetPreference(it.toLong(), true)) }
        val before = memory.bytes!!.copyOf()
        assertEquals(PairOffsetStoreFailure.CAPACITY, assertThrows(PairOffsetStoreException::class.java) {
            store.save(key(64), PairOffsetPreference(1, true))
        }.failure)
        assertArrayEquals(before, memory.bytes)
        store.save(key(0), PairOffsetPreference(-MAX_PAIR_OFFSET_MS, false))
        assertEquals(64, store.read().size)
        assertEquals(PairOffsetPreference(-MAX_PAIR_OFFSET_MS, false), store.read()[key(0)])
        assertEquals(PairOffsetPreference(63, true), store.read()[key(63)])
    }

    @Test fun storageReadAndWriteFailureNeverInventACommitOrExposePrivateExceptionDetail() {
        val memory = Memory(); val store = PairOffsetStore(memory)
        store.save(key(1), PairOffsetPreference(5_000, true))
        val before = memory.bytes!!.copyOf()
        for (read in listOf(true, false)) {
            memory.failRead = read; memory.failWrite = !read
            val error = assertThrows(PairOffsetStoreException::class.java) {
                store.save(key(2), PairOffsetPreference(10_000, true))
            }
            assertEquals(PairOffsetStoreFailure.STORAGE, error.failure)
            assertEquals("STORAGE", error.message); assertNull(error.cause)
            assertArrayEquals(before, memory.bytes); assertEquals(1, memory.writes)
        }
        memory.failRead = false; memory.failWrite = false
        assertEquals(mapOf(key(1) to PairOffsetPreference(5_000, true)), store.read())
    }

    @Test fun fileLockCoversReadModifyWriteAndIsReleasedOnStorageFailure() {
        val file = temporary.newFile("pair-offsets.lock")
        val memory = Memory()
        val store = PairOffsetStore(object : PrivateSecretStore {
            private fun locked() = RandomAccessFile(file, "rw").use { handle ->
                assertThrows(OverlappingFileLockException::class.java) { handle.channel.tryLock() }
            }
            override fun read(): ByteArray? { locked(); return memory.read() }
            override fun write(plaintext: ByteArray) { locked(); memory.write(plaintext) }
        }, file)
        store.save(key(1), PairOffsetPreference(MAX_PAIR_OFFSET_MS, true))
        memory.failRead = true
        assertEquals(PairOffsetStoreFailure.STORAGE, assertThrows(PairOffsetStoreException::class.java) { store.read() }.failure)
        RandomAccessFile(file, "rw").use { handle -> handle.channel.tryLock().use { assertNotNull(it) } }
    }

    @Test fun invalidSaveKeyDoesNotReadOrWriteAndReturnedMapCannotBeChanged() {
        val memory = Memory(); val store = PairOffsetStore(memory)
        assertThrows(IllegalArgumentException::class.java) { store.save("invalid", PairOffsetPreference(0, false)) }
        assertEquals(0, memory.writes)
        val result = store.save(key(1), PairOffsetPreference(0, false))
        assertThrows(UnsupportedOperationException::class.java) {
            (result as MutableMap<String, PairOffsetPreference>)[key(2)] = PairOffsetPreference(1, true)
        }
        assertEquals(1, store.read().size)
    }

    private fun correspondence() = PairOffsetCorrespondence(1_700_000_000_100, 1_800_000_000_200,
        true, true, NativeTimelineEvidence.Dash(1_700_000_000_000, 0),
        NativeTimelineEvidence.Hls(listOf(NativeHlsTimelineSample(50, 1_800_000_000_000, 6_000, 3))), -250)

    @Test fun writingV2MigratesV1WithoutLosingOtherDisabledValuesOrCorrespondence() {
        val memory = Memory().also { it.bytes = encoded(enabled = 0) }
        val store = PairOffsetStore(memory)
        assertEquals(PairOffsetPreference(26_400, false), store.read()[key(1)])
        assertEquals(0, memory.writes)
        val saved = PairOffsetPreference(-20_000, false, correspondence())
        store.save(key(2), saved)
        assertEquals(2, java.io.DataInputStream(memory.bytes!!.inputStream()).readInt())
        assertEquals(mapOf(key(1) to PairOffsetPreference(26_400, false), key(2) to saved), store.read())
        store.save(key(1), PairOffsetPreference(5_000, true,
            correspondence().copy(aLive = false, aTimeline = NativeTimelineEvidence.Replay(100_000), bTimeline = null)))
        assertEquals(saved, store.read()[key(2)])
        assertEquals(NativeTimelineEvidence.Replay(100_000), store.read()[key(1)]!!.correspondence!!.aTimeline)
        assertNull(store.read()[key(1)]!!.correspondence!!.bTimeline)
    }

    private fun v2(body: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(2); data.writeInt(1); data.writeUTF(key(1)); data.writeLong(5_000); data.writeByte(1)
            data.body()
        }
    }.toByteArray()

    @Test fun unknownTagsInvalidEvidenceAndEveryTruncationRefuseWithoutOverwriting() {
        val corrupt = mutableListOf(
            v2 { writeByte(2) },
            v2 { writeByte(1); writeLong(-1); writeLong(2); writeByte(1); writeByte(0); writeByte(0); writeByte(0); writeLong(0) },
            v2 { writeByte(1); writeLong(1); writeLong(2); writeByte(2); writeByte(0); writeByte(0); writeByte(0); writeLong(0) },
            v2 { writeByte(1); writeLong(1); writeLong(2); writeByte(1); writeByte(0); writeByte(4) },
            v2 { writeByte(1); writeLong(1); writeLong(2); writeByte(1); writeByte(0); writeByte(2); writeByte(0) },
            v2 { writeByte(1); writeLong(1); writeLong(2); writeByte(1); writeByte(0); writeByte(2); writeByte(MAX_NATIVE_TIMELINE_SAMPLES + 1) },
            v2 { writeByte(1); writeLong(1); writeLong(2); writeByte(1); writeByte(0); writeByte(3); writeLong(-1) },
            v2 { writeByte(1); writeLong(1); writeLong(2); writeByte(1); writeByte(0); writeByte(0); writeByte(0); writeLong(MAX_PAIR_OFFSET_MS + 1) },
        )
        val valid = Memory().also { PairOffsetStore(it).save(key(1), PairOffsetPreference(5_000, true, correspondence())) }.bytes!!
        for (length in 0 until valid.size) corrupt += valid.copyOf(length)
        corrupt += valid + byteArrayOf(0)
        for (bytes in corrupt) {
            val memory = Memory().also { it.bytes = bytes }; val store = PairOffsetStore(memory)
            assertEquals(PairOffsetStoreFailure.INVALID_RECORD,
                assertThrows(PairOffsetStoreException::class.java) { store.read() }.failure)
            assertThrows(PairOffsetStoreException::class.java) { store.save(key(2), PairOffsetPreference(0, true)) }
            assertArrayEquals(bytes, memory.bytes); assertEquals(0, memory.writes)
        }
    }

    @Test fun evidenceByteCapacityRefusesBeforeWriteAndDoesNotEvictExistingAnchors() {
        val samples = List(MAX_NATIVE_TIMELINE_SAMPLES) { NativeHlsTimelineSample(it.toLong(), 1_700_000_000_000 + it * 6_000L, 6_000, 0) }
        val saved = PairOffsetPreference(25_000, true, correspondence().copy(
            aTimeline = NativeTimelineEvidence.Hls(samples), bTimeline = NativeTimelineEvidence.Hls(samples)))
        val memory = Memory(); val store = PairOffsetStore(memory)
        var count = 0
        while (true) {
            val before = memory.bytes?.copyOf(); val writes = memory.writes
            try { store.save(key(count), saved); count++ }
            catch (error: PairOffsetStoreException) {
                assertEquals(PairOffsetStoreFailure.CAPACITY, error.failure)
                assertArrayEquals(before, memory.bytes); assertEquals(writes, memory.writes)
                break
            }
        }
        assertTrue(count in 1 until MAX_PAIR_OFFSET_RECORDS)
        assertTrue(memory.bytes!!.size <= PRIVATE_SECRET_LIMIT)
        assertEquals(count, store.read().size)
        assertTrue(store.read().values.all { it == saved })
    }
}
