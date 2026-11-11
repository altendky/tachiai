package net.fstab.tachiai.feature.connections

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import javax.crypto.KeyGenerator
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.platform.storage.decryptPrivateSecret
import net.fstab.tachiai.platform.storage.encryptPrivateSecret
import net.fstab.tachiai.presentation.ConfiguredFeedAssignments
import net.fstab.tachiai.presentation.ConfiguredFeedChoice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ViewerSelectionStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val a = ConfiguredFeedChoice("12345678-1234-4000-8000-123456789abc", "23456789-1234-4000-8000-123456789abc")
    private val b = ConfiguredFeedChoice("34567890-1234-4000-8000-123456789abc", "45678901-1234-4000-8000-123456789abc")
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var writes = 0
        var failRead = false
        var failWrite = false
        override fun read(): ByteArray? {
            if (failRead) throw IOException("private fixture failure")
            return bytes?.copyOf()
        }
        override fun write(plaintext: ByteArray) {
            if (failWrite) throw IOException("private fixture failure")
            bytes = plaintext.copyOf(); writes++
        }
    }
    private fun failure(expected: ViewerSelectionStoreFailure, action: () -> Unit) {
        val error = assertThrows(ViewerSelectionStoreException::class.java) { action() }
        assertEquals(expected, error.failure)
        assertEquals(expected.name, error.message)
        assertNull(error.cause)
    }

    @Test fun absentAndExplicitlyEmptyRemainDistinctAcrossStoreRecreation() {
        val memory = Memory()
        assertNull(ViewerSelectionStore(memory).read())
        assertEquals(0, memory.writes)
        val empty = ConfiguredFeedAssignments(null, null)
        ViewerSelectionStore(memory).save(empty)
        assertEquals(empty, ViewerSelectionStore(memory).read())
        assertEquals(10, memory.bytes!!.size)
    }

    @Test fun partialDuplicateAndOrderedChoicesRoundTripExactlyWithoutOtherMetadata() {
        val memory = Memory()
        for (assignments in listOf(ConfiguredFeedAssignments(a, null), ConfiguredFeedAssignments(null, b),
            ConfiguredFeedAssignments(a, a), ConfiguredFeedAssignments(a, b), ConfiguredFeedAssignments(b, a),
            ConfiguredFeedAssignments(null, null))) {
            ViewerSelectionStore(memory).save(assignments)
            assertEquals(assignments, ViewerSelectionStore(memory).read())
            val expectedSize = 10 + listOf(assignments.a, assignments.b).count { it != null } * 72
            assertEquals(expectedSize, memory.bytes!!.size)
        }
    }

    private fun record(magic: Int = 0x5456534c, version: Int = 1, firstFlag: Int = 1,
        secondFlag: Int = 1, firstItem: String = a.itemId, firstInstance: String = a.instanceId): ByteArray =
        ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(magic); data.writeInt(version)
            data.writeByte(firstFlag)
            if (firstFlag == 1) {
                data.write(firstItem.toByteArray(Charsets.US_ASCII)); data.write(firstInstance.toByteArray(Charsets.US_ASCII))
            }
            data.writeByte(secondFlag)
            if (secondFlag == 1) {
                data.write(b.itemId.toByteArray(Charsets.US_ASCII)); data.write(b.instanceId.toByteArray(Charsets.US_ASCII))
            }
        } }.toByteArray()

    @Test fun malformedHeadersFlagsIdsTrailingAndOversizedRecordsAreNeverOverwritten() {
        val valid = record()
        val malformed = listOf(record(magic = 0), record(version = 0), record(version = 2),
            record(firstFlag = 2), record(secondFlag = 255), record(firstItem = a.itemId.uppercase()),
            record(firstInstance = "x".repeat(36)), valid + byteArrayOf(0), ByteArray(PRIVATE_SECRET_LIMIT + 1)) +
            valid.indices.map { valid.copyOf(it) }
        for (bytes in malformed) {
            val memory = Memory().apply { this.bytes = bytes.copyOf() }
            val store = ViewerSelectionStore(memory)
            failure(ViewerSelectionStoreFailure.INVALID_RECORD) { store.read() }
            failure(ViewerSelectionStoreFailure.INVALID_RECORD) { store.save(ConfiguredFeedAssignments(null, null)) }
            assertArrayEquals(bytes, memory.bytes)
            assertEquals(0, memory.writes)
        }
    }

    @Test fun unreadableRecordRefusesSaveAndKeepsItsExistingBytes() {
        val memory = Memory()
        ViewerSelectionStore(memory).save(ConfiguredFeedAssignments(a, b))
        val before = memory.bytes!!.copyOf()
        memory.failRead = true
        failure(ViewerSelectionStoreFailure.STORAGE) { ViewerSelectionStore(memory).read() }
        failure(ViewerSelectionStoreFailure.STORAGE) { ViewerSelectionStore(memory).save(ConfiguredFeedAssignments(b, a)) }
        assertArrayEquals(before, memory.bytes)
        assertEquals(1, memory.writes)
    }

    @Test fun failedAtomicWriteDoesNotInventANewSelectionOrDiscardTheOldOne() {
        val memory = Memory()
        val original = ConfiguredFeedAssignments(a, b)
        ViewerSelectionStore(memory).save(original)
        val before = memory.bytes!!.copyOf()
        memory.failWrite = true
        failure(ViewerSelectionStoreFailure.STORAGE) { ViewerSelectionStore(memory).save(ConfiguredFeedAssignments(b, null)) }
        assertArrayEquals(before, memory.bytes)
        assertEquals(original, ViewerSelectionStore(memory).read())
        assertEquals(1, memory.writes)
    }

    @Test fun authenticatedEncryptionTamperingIsStorageFailureAndCannotBeReplacedByAnEmptySelection() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val binding = "test:viewer-selection:v1".toByteArray()
        var encrypted: ByteArray? = null
        var writes = 0
        val store = ViewerSelectionStore(object : PrivateSecretStore {
            override fun read() = encrypted?.let { decryptPrivateSecret(key, binding, it) }
            override fun write(plaintext: ByteArray) { encrypted = encryptPrivateSecret(key, binding, plaintext); writes++ }
        })
        val original = ConfiguredFeedAssignments(a, b)
        store.save(original)
        assertEquals(original, store.read())
        encrypted!!.let { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val before = encrypted!!.copyOf()
        failure(ViewerSelectionStoreFailure.STORAGE) { store.read() }
        failure(ViewerSelectionStoreFailure.STORAGE) { store.save(ConfiguredFeedAssignments(null, null)) }
        assertArrayEquals(before, encrypted)
        assertEquals(1, writes)
    }

    @Test fun stableFileLockCoversReadAndWriteAndReleasesAfterEitherStorageFailure() {
        val file = temporary.newFile("viewer-selection.lock")
        val memory = Memory()
        val guarded = object : PrivateSecretStore {
            fun assertLocked() = RandomAccessFile(file, "rw").use { handle ->
                assertThrows(OverlappingFileLockException::class.java) { handle.channel.tryLock() }
            }
            override fun read(): ByteArray? { assertLocked(); return memory.read() }
            override fun write(plaintext: ByteArray) { assertLocked(); memory.write(plaintext) }
        }
        fun assertReleased() = RandomAccessFile(file, "rw").use { handle ->
            handle.channel.tryLock().use { assertNotNull(it) }
        }
        val store = ViewerSelectionStore(guarded, file)
        store.save(ConfiguredFeedAssignments(a, b)); assertReleased()
        assertEquals(ConfiguredFeedAssignments(a, b), ViewerSelectionStore(guarded, file).read()); assertReleased()
        memory.failRead = true
        failure(ViewerSelectionStoreFailure.STORAGE) { store.save(ConfiguredFeedAssignments(null, null)) }; assertReleased()
        memory.failRead = false; memory.failWrite = true
        failure(ViewerSelectionStoreFailure.STORAGE) { store.save(ConfiguredFeedAssignments(null, null)) }; assertReleased()
        memory.failWrite = false
        store.save(ConfiguredFeedAssignments(b, a)); assertReleased()
        assertEquals(ConfiguredFeedAssignments(b, a), store.read())
    }
}
