package net.fstab.tachiai.feature.connections

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import net.fstab.tachiai.platform.media.NativeQualityCodec
import net.fstab.tachiai.platform.media.NativeQualityKind
import net.fstab.tachiai.platform.media.NativeQualityPreferences
import net.fstab.tachiai.platform.media.NativeQualityRequest
import net.fstab.tachiai.platform.media.NativeQualityTrack
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore
import net.fstab.tachiai.platform.storage.PrivateAuthorizationSlot
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.presentation.PrototypeSource

internal fun streamQualityStore(context: Context) = StreamQualityStore(
    AndroidPrivateSecretStore(context, PrivateAuthorizationSlot.STREAM_QUALITY),
    File(context.noBackupFilesDir, "stream-quality.lock"),
)

// Separate record: resetting quality never rewrites routes, legacy stream
// metadata or authorization. No raw SDK or manifest fields are serialized.
internal class StreamQualityStore(private val store: PrivateSecretStore, private val lockFile: File? = null) {
    companion object { private val lock = Any() }
    private fun <T> locked(action: () -> T): T = synchronized(lock) {
        val file = lockFile
        if (file == null) action() else RandomAccessFile(file, "rw").use { handle -> handle.channel.lock().use { action() } }
    }
    private fun readUnlocked(): Map<PrototypeSource, NativeQualityPreferences> {
        val bytes = store.read() ?: return emptyMap()
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readInt() == 1)
            val count = input.readInt(); check(count in 0..PrototypeSource.entries.size)
            fun request(kind: NativeQualityKind): NativeQualityRequest {
                val manual = input.readUnsignedByte(); check(manual in 0..1)
                if (manual == 0) return NativeQualityRequest.auto
                val codec = NativeQualityCodec.valueOf(input.readUTF())
                fun number() = input.readInt().let { if (it == -1) null else it }
                val bitrate = number(); val first = number(); val second = number()
                return NativeQualityRequest(if (kind == NativeQualityKind.VIDEO)
                    NativeQualityTrack(kind, codec, bitrate, first, second)
                else NativeQualityTrack(kind, codec, bitrate, channelCount = first, sampleRateHz = second))
            }
            val entries = List(count) { PrototypeSource.valueOf(input.readUTF()) to
                NativeQualityPreferences(request(NativeQualityKind.VIDEO), request(NativeQualityKind.AUDIO)) }
            check(entries.map { it.first }.distinct().size == count && input.read() == -1)
            entries.toMap()
        }
    }
    fun read(): Map<PrototypeSource, NativeQualityPreferences> = locked { readUnlocked() }
    fun save(source: PrototypeSource, kind: NativeQualityKind, request: NativeQualityRequest): Map<PrototypeSource, NativeQualityPreferences> = locked {
        val existing = readUnlocked()
        val value = (existing[source] ?: NativeQualityPreferences()).with(kind, request)
        val updated = if (value == NativeQualityPreferences()) existing - source else existing + (source to value)
        val bytes = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(1); data.writeInt(updated.size)
            fun writeRequest(value: NativeQualityRequest) {
                val track = value.track
                data.writeBoolean(track != null)
                if (track != null) {
                    data.writeUTF(track.codec.name); data.writeInt(track.bitrateBps ?: -1)
                    data.writeInt((if (track.kind == NativeQualityKind.VIDEO) track.width else track.channelCount) ?: -1)
                    data.writeInt((if (track.kind == NativeQualityKind.VIDEO) track.height else track.sampleRateHz) ?: -1)
                }
            }
            PrototypeSource.entries.filter { it in updated }.forEach { source ->
                data.writeUTF(source.name)
                val preferences = checkNotNull(updated[source]); writeRequest(preferences.video); writeRequest(preferences.audio)
            }
        } }.toByteArray()
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        store.write(bytes)
        updated
    }
}
