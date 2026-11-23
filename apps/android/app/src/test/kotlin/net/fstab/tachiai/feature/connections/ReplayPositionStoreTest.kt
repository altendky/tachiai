package net.fstab.tachiai.feature.connections

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.MAX_REPLAY_POSITION_MS
import net.fstab.tachiai.presentation.MAX_REPLAY_POSITION_RECORDS
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Remembered replay positions (#655 part 2).
class ReplayPositionStoreTest {
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
    private fun encoded(version: Int = 1, count: Int = 1, key: ByteArray = ByteArray(32) { 1 }, position: Long = 5_000,
        duplicate: Boolean = false, trailing: Boolean = false): ByteArray =
        ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(version); data.writeInt(count)
            repeat(if (duplicate) 2 else 1) { data.write(key); data.writeLong(position) }
            if (trailing) data.writeByte(0)
        } }.toByteArray()

    @Test fun missingRecordReadsEmptyAndRoundTripKeepsMostRecentFirst() {
        val memory = Memory(); val store = ReplayPositionStore(memory)
        assertTrue(store.read().isEmpty()); assertEquals(0, memory.writes)
        store.record(mapOf(key(1) to 8_045_000L))
        store.record(mapOf(key(2) to 0L, key(3) to MAX_REPLAY_POSITION_MS))
        assertEquals(listOf(key(2) to 0L, key(3) to MAX_REPLAY_POSITION_MS, key(1) to 8_045_000L),
            ReplayPositionStore(memory).read().toList())
        // Saving an item again moves it to the front with its new position.
        store.record(mapOf(key(1) to 8_055_000L))
        assertEquals(listOf(key(1), key(2), key(3)), store.read().keys.toList())
        assertEquals(8_055_000L, store.read()[key(1)])
        assertEquals(3, memory.writes)
        store.record(emptyMap())
        assertEquals(3, memory.writes)
    }

    @Test fun capKeepsTheMostRecentItemsAndFitsThePrivateRecord() {
        val memory = Memory(); val store = ReplayPositionStore(memory)
        repeat(MAX_REPLAY_POSITION_RECORDS + 5) { store.record(mapOf(key(it) to it * 1_000L)) }
        val kept = store.read()
        assertEquals(MAX_REPLAY_POSITION_RECORDS, kept.size)
        assertEquals(key(MAX_REPLAY_POSITION_RECORDS + 4), kept.keys.first())
        assertEquals(key(5), kept.keys.last())
        assertFalse(key(4) in kept); assertFalse(key(0) in kept)
        assertTrue(memory.bytes!!.size <= PRIVATE_SECRET_LIMIT)
        // Touching an old item makes it the most recent, so another is dropped instead.
        store.record(mapOf(key(5) to 1L, key(1_000) to 2L))
        assertEquals(listOf(key(5), key(1_000)), store.read().keys.take(2))
        assertFalse(key(6) in store.read())
        assertEquals(MAX_REPLAY_POSITION_RECORDS, store.read().size)
    }

    @Test fun forgetRemovesOnlyTheGivenItemsAndSkipsNeedlessWrites() {
        val memory = Memory(); val store = ReplayPositionStore(memory)
        store.record(mapOf(key(1) to 1_000L, key(2) to 2_000L, key(3) to 3_000L))
        store.forget(setOf(key(2), key(9)))
        assertEquals(mapOf(key(1) to 1_000L, key(3) to 3_000L), store.read())
        val writes = memory.writes
        store.forget(setOf(key(9)))
        assertEquals(writes, memory.writes)
    }

    @Test fun corruptOrUnreadableRecordsReadEmptyAndTheNextSaveReplacesThem() {
        val corrupt = listOf(byteArrayOf(), encoded(version = 2), encoded(count = -1),
            encoded(count = MAX_REPLAY_POSITION_RECORDS + 1), encoded(position = -1),
            encoded(position = MAX_REPLAY_POSITION_MS + 1), encoded(count = 2, duplicate = true), encoded(trailing = true),
            encoded().dropLast(1).toByteArray(), ByteArray(PRIVATE_SECRET_LIMIT + 1))
        for (bytes in corrupt) {
            val memory = Memory().also { it.bytes = bytes.copyOf() }; val store = ReplayPositionStore(memory)
            assertTrue(store.read().isEmpty())
            assertEquals(0, memory.writes)
            store.record(mapOf(key(2) to 9_000L))
            assertEquals(mapOf(key(2) to 9_000L), store.read())
        }
        val memory = Memory(); val store = ReplayPositionStore(memory)
        store.record(mapOf(key(1) to 1_000L))
        memory.failRead = true
        assertTrue(store.read().isEmpty())
        store.record(mapOf(key(2) to 2_000L))
        memory.failRead = false
        assertEquals(mapOf(key(2) to 2_000L), store.read())
    }

    @Test fun writeFailurePropagatesWithoutChangingTheRecord() {
        val memory = Memory(); val store = ReplayPositionStore(memory)
        store.record(mapOf(key(1) to 1_000L))
        val before = memory.bytes!!.copyOf()
        memory.failWrite = true
        assertThrows(IOException::class.java) { store.record(mapOf(key(2) to 2_000L)) }
        assertThrows(IOException::class.java) { store.forget(setOf(key(1))) }
        assertArrayEquals(before, memory.bytes)
    }

    @Test fun invalidKeysAndPositionsAreRefusedBeforeReading() {
        val memory = Memory(); val store = ReplayPositionStore(memory)
        memory.failRead = true
        for (positions in listOf(mapOf("invalid" to 1L), mapOf(key(1).uppercase().replace('0', 'A') to 1L),
            mapOf(key(1) to -1L), mapOf(key(1) to MAX_REPLAY_POSITION_MS + 1)))
            assertThrows(IllegalArgumentException::class.java) { store.record(positions) }
        assertThrows(IllegalArgumentException::class.java) { store.forget(setOf("invalid")) }
        assertEquals(0, memory.writes)
    }

    @Test fun fileLockCoversReadModifyWriteAndIsReleasedAfterFailure() {
        val file = temporary.newFile("replay-positions.lock")
        val memory = Memory()
        val store = ReplayPositionStore(object : PrivateSecretStore {
            private fun locked() = RandomAccessFile(file, "rw").use { handle ->
                assertThrows(OverlappingFileLockException::class.java) { handle.channel.tryLock() }
            }
            override fun read(): ByteArray? { locked(); return memory.read() }
            override fun write(plaintext: ByteArray) { locked(); memory.write(plaintext) }
        }, file)
        store.record(mapOf(key(1) to 1_000L))
        memory.failWrite = true
        assertThrows(IOException::class.java) { store.record(mapOf(key(2) to 2_000L)) }
        RandomAccessFile(file, "rw").use { handle -> handle.channel.tryLock().use { assertNotNull(it) } }
    }

    // Two processes (as the ordinary and playback processes) save different
    // items at once; the cross-process lock keeps every update.
    @Test fun concurrentProcessesNeverLoseEachOthersSaves() {
        val dir = temporary.newFolder("replay-positions")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val child = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
            ReplayPositionStoreProcess::class.java.name, dir.path, "1").redirectErrorStream(true).start()
        ReplayPositionStoreProcess.run(dir, 0)
        assertTrue(child.waitFor(60, TimeUnit.SECONDS))
        val output = child.inputStream.bufferedReader().readText()
        assertEquals(output, 0, child.exitValue())
        val saved = ReplayPositionStoreProcess.store(dir).read()
        assertEquals(2 * ReplayPositionStoreProcess.SAVES, saved.size)
        repeat(ReplayPositionStoreProcess.SAVES) { index ->
            assertEquals(index.toLong(), saved[ReplayPositionStoreProcess.key(0, index)])
            assertEquals(index.toLong(), saved[ReplayPositionStoreProcess.key(1, index)])
        }
    }
}

// A plain-file record standing in for the encrypted one, replaced atomically.
private class FileSecretStore(private val file: File) : PrivateSecretStore {
    override fun read(): ByteArray? = if (file.exists()) file.readBytes() else null
    override fun write(plaintext: ByteArray) {
        val next = File(file.path + ".new")
        next.writeBytes(plaintext)
        Files.move(next.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

internal object ReplayPositionStoreProcess {
    const val SAVES = 40
    fun key(writer: Int, index: Int) = (writer * 1_000 + index).toString(16).padStart(64, '0')
    fun store(dir: File) = ReplayPositionStore(FileSecretStore(File(dir, "replay-positions.bin")), File(dir, "replay-positions.lock"))
    fun run(dir: File, writer: Int) {
        val store = store(dir)
        // Both writers start together, so their saves interleave.
        check(File(dir, "$writer.ready").createNewFile())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!File(dir, "${1 - writer}.ready").exists()) { check(System.nanoTime() < deadline); Thread.sleep(5) }
        repeat(SAVES) { index -> store.record(mapOf(key(writer, index) to index.toLong())) }
    }
    @JvmStatic fun main(args: Array<String>) {
        try { run(File(args[0]), args[1].toInt()) } catch (error: Throwable) { println(error); System.exit(1) }
    }
}
