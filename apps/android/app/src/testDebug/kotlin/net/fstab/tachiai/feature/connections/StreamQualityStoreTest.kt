package net.fstab.tachiai.feature.connections

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.PrototypeSource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StreamQualityStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var writes = 0
        override fun read() = bytes
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf(); writes++ }
    }
    private val video = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC, 2_000_000, 1280, 720))
    private val audio = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.AUDIO, NativeQualityCodec.AAC, 192_000,
        channelCount = 2, sampleRateHz = 48_000))

    @Test fun roundTripAndPerKindResetPreserveOtherStreamsAndAudio() {
        val memory = Memory()
        val store = StreamQualityStore(memory)
        assertTrue(store.read().isEmpty()); assertEquals(0, memory.writes)
        store.save(PrototypeSource.ABEMA_REPLAY, NativeQualityKind.VIDEO, video)
        store.save(PrototypeSource.ABEMA_REPLAY, NativeQualityKind.AUDIO, audio)
        store.save(PrototypeSource.TWITCH_LIVE, NativeQualityKind.VIDEO, video)
        val result = StreamQualityStore(memory).save(PrototypeSource.ABEMA_REPLAY, NativeQualityKind.VIDEO, NativeQualityRequest.auto)
        assertEquals(NativeQualityPreferences(audio = audio), result[PrototypeSource.ABEMA_REPLAY])
        assertEquals(video, result[PrototypeSource.TWITCH_LIVE]?.video)
        assertEquals(result, StreamQualityStore(memory).read())
        assertFalse(String(memory.bytes!!).contains("Format"))
        assertFalse(String(memory.bytes!!).contains("PrivateKey"))
    }

    private fun record(version: Int = 1, count: Int = 1, source: String = "ABEMA_REPLAY", codec: String = "AVC",
        width: Int = 1280, marker: Int = 1) = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(version); data.writeInt(count); data.writeUTF(source)
            data.writeByte(marker); data.writeUTF(codec); data.writeInt(2_000_000); data.writeInt(width); data.writeInt(720)
            data.writeBoolean(false)
        }
    }.toByteArray()

    @Test fun malformedRecordsNeverFallBackOrGetOverwrittenOnSaveOrReset() {
        val valid = record()
        listOf(byteArrayOf(1), record(version = 2), record(count = Int.MAX_VALUE), record(source = "UNKNOWN"),
            record(codec = "OTHER"), record(codec = "AAC"), record(width = -1), record(marker = 2),
            valid + byteArrayOf(0), valid.dropLast(1).toByteArray()).forEach { bytes ->
            val memory = Memory().apply { this.bytes = bytes.copyOf() }
            val store = StreamQualityStore(memory)
            assertThrows(Exception::class.java) { store.read() }
            assertThrows(Exception::class.java) { store.save(PrototypeSource.ABEMA_REPLAY, NativeQualityKind.VIDEO, NativeQualityRequest.auto) }
            assertArrayEquals(bytes, memory.bytes); assertEquals(0, memory.writes)
        }
    }

    @Test fun failedWriteDoesNotInventCommittedPreferences() {
        val memory = Memory()
        StreamQualityStore(memory).save(PrototypeSource.TWITCH_LIVE, NativeQualityKind.AUDIO, audio)
        val before = memory.bytes!!.copyOf()
        val failed = object : PrivateSecretStore {
            override fun read() = memory.bytes
            override fun write(plaintext: ByteArray) { error("Fixture storage refusal") }
        }
        assertThrows(IllegalStateException::class.java) { StreamQualityStore(failed).save(PrototypeSource.ABEMA_REPLAY, NativeQualityKind.VIDEO, video) }
        assertArrayEquals(before, memory.bytes)
    }

    @Test fun fileLockCoversReadModifyWriteAndReleasesAfterFailure() {
        val file = temporary.newFile("quality.lock")
        var fail = false
        val store = StreamQualityStore(object : PrivateSecretStore {
            fun assertLocked() = RandomAccessFile(file, "rw").use { handle ->
                assertThrows(OverlappingFileLockException::class.java) { handle.channel.tryLock() }
            }
            override fun read(): ByteArray? { assertLocked(); check(!fail); return null }
            override fun write(plaintext: ByteArray) { assertLocked() }
        }, file)
        store.save(PrototypeSource.ABEMA_REPLAY, NativeQualityKind.VIDEO, video)
        fail = true
        assertThrows(IllegalStateException::class.java) { store.read() }
        RandomAccessFile(file, "rw").use { handle -> handle.channel.tryLock().use { assertNotNull(it) } }
    }
}
