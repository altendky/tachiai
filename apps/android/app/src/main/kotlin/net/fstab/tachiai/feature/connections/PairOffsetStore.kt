package net.fstab.tachiai.feature.connections

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateAuthorizationSlot
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.PairOffsetPreference
import net.fstab.tachiai.presentation.PairOffsetCorrespondence
import net.fstab.tachiai.presentation.validPairOffsetKey
import net.fstab.tachiai.platform.media.MAX_NATIVE_TIMELINE_SAMPLES
import net.fstab.tachiai.platform.media.NativeHlsTimelineSample
import net.fstab.tachiai.platform.media.NativeTimelineEvidence

internal const val MAX_PAIR_OFFSET_RECORDS = 64
internal enum class PairOffsetStoreFailure { STORAGE, INVALID_RECORD, CAPACITY }
internal class PairOffsetStoreException(val failure: PairOffsetStoreFailure) : Exception(failure.name)

internal fun pairOffsetStore(context: Context) = PairOffsetStore(
    AndroidPrivateSecretStore(context.applicationContext, PrivateAuthorizationSlot.PAIR_OFFSETS),
    File(context.applicationContext.noBackupFilesDir, "pair-offsets.lock"),
)

// Worker-only, independent encrypted atomic record. A rejected read/write never
// resets the record, invents a successful save, or modifies another setting.
internal class PairOffsetStore(private val store: PrivateSecretStore, private val lockFile: File? = null) {
    companion object { private val lock = Any() }
    private fun <T> locked(action: () -> T): T = try {
        synchronized(lock) {
            val file = lockFile
            if (file == null) action() else RandomAccessFile(file, "rw").use { handle ->
                handle.channel.lock().use { action() }
            }
        }
    } catch (error: PairOffsetStoreException) { throw error }
    catch (_: Exception) { throw PairOffsetStoreException(PairOffsetStoreFailure.STORAGE) }

    private fun readUnlocked(): Map<String, PairOffsetPreference> {
        val bytes = try { store.read() }
            catch (_: Exception) { throw PairOffsetStoreException(PairOffsetStoreFailure.STORAGE) }
            ?: return emptyMap()
        try {
            check(bytes.size in 8..PRIVATE_SECRET_LIMIT)
            val entries = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val version = input.readInt(); check(version in 1..2)
                val count = input.readInt(); check(count in 0..MAX_PAIR_OFFSET_RECORDS)
                val result = linkedMapOf<String, PairOffsetPreference>()
                repeat(count) {
                    val key = input.readUTF(); check(validPairOffsetKey(key) && key !in result)
                    val offset = input.readLong()
                    val enabled = input.readUnsignedByte(); check(enabled in 0..1)
                    val correspondence = if (version == 1) null else when (input.readUnsignedByte()) {
                        0 -> null
                        1 -> PairOffsetCorrespondence(input.readLong(), input.readLong(),
                            input.readFlag(), input.readFlag(), input.readTimeline(), input.readTimeline(), input.readLong())
                        else -> error("Invalid correspondence tag")
                    }
                    result[key] = PairOffsetPreference(offset, enabled == 1, correspondence)
                }
                check(input.read() == -1)
                result
            }
            return Collections.unmodifiableMap(entries)
        } catch (_: Exception) { throw PairOffsetStoreException(PairOffsetStoreFailure.INVALID_RECORD) }
    }

    fun read(): Map<String, PairOffsetPreference> = locked { readUnlocked() }
    fun save(key: String, preference: PairOffsetPreference): Map<String, PairOffsetPreference> {
        require(validPairOffsetKey(key))
        return locked {
            val previous = readUnlocked()
            if (key !in previous && previous.size == MAX_PAIR_OFFSET_RECORDS)
                throw PairOffsetStoreException(PairOffsetStoreFailure.CAPACITY)
            val updated = previous + (key to preference)
            val bytes = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
                data.writeInt(2); data.writeInt(updated.size)
                updated.toSortedMap().forEach { (storedKey, value) ->
                    data.writeUTF(storedKey); data.writeLong(value.offsetMs); data.writeByte(if (value.enabled) 1 else 0)
                    val correspondence = value.correspondence
                    data.writeByte(if (correspondence == null) 0 else 1)
                    if (correspondence != null) {
                        data.writeLong(correspondence.aMs); data.writeLong(correspondence.bMs)
                        data.writeByte(if (correspondence.aLive) 1 else 0); data.writeByte(if (correspondence.bLive) 1 else 0)
                        data.writeTimeline(correspondence.aTimeline); data.writeTimeline(correspondence.bTimeline)
                        data.writeLong(correspondence.residualMs)
                    }
                }
            } }.toByteArray()
            if (bytes.size > PRIVATE_SECRET_LIMIT) throw PairOffsetStoreException(PairOffsetStoreFailure.CAPACITY)
            try { store.write(bytes) }
            catch (_: Exception) { throw PairOffsetStoreException(PairOffsetStoreFailure.STORAGE) }
            Collections.unmodifiableMap(updated)
        }
    }
}

private fun DataInputStream.readFlag(): Boolean = readUnsignedByte().let { check(it in 0..1); it == 1 }
private fun DataInputStream.readTimeline(): NativeTimelineEvidence? = when (readUnsignedByte()) {
    0 -> null
    1 -> NativeTimelineEvidence.Dash(readLong(), readLong())
    2 -> {
        val count = readUnsignedByte(); check(count in 1..MAX_NATIVE_TIMELINE_SAMPLES)
        NativeTimelineEvidence.Hls(List(count) { NativeHlsTimelineSample(readLong(), readLong(), readLong(), readLong()) })
    }
    3 -> NativeTimelineEvidence.Replay(readLong())
    else -> error("Invalid timeline tag")
}
private fun DataOutputStream.writeTimeline(value: NativeTimelineEvidence?) {
    when (value) {
        null -> writeByte(0)
        is NativeTimelineEvidence.Dash -> {
            writeByte(1); writeLong(value.availabilityStartTimeMs); writeLong(value.periodStartMs)
        }
        is NativeTimelineEvidence.Hls -> {
            writeByte(2); writeByte(value.samples.size)
            value.samples.forEach { sample ->
                writeLong(sample.sequence); writeLong(sample.startTimeMs); writeLong(sample.durationMs); writeLong(sample.discontinuity)
            }
        }
        is NativeTimelineEvidence.Replay -> { writeByte(3); writeLong(value.durationMs) }
    }
}
