package net.fstab.tachiai.feature.connections

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.platform.storage.configuredProviderInstanceBindingName
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*

internal fun configuredSourceStore(context: Context, instance: ProviderInstance) = ConfiguredSourceStore(
    AndroidPrivateSecretStore.configuredProviderInstance(context, instance.id), instance.id,
    ProviderId(instance.service.name.lowercase(java.util.Locale.ROOT)),
    File(context.noBackupFilesDir, "${configuredProviderInstanceBindingName(instance.id)}.lock"),
)

// Missing records expose a read-only legacy projection. An explicit mutation
// commits only this instance's catalog; routes, grants and legacy slots are untouched.
internal class ConfiguredSourceStore(
    private val store: PrivateSecretStore,
    private val instanceId: String,
    private val providerId: ProviderId,
    private val lockFile: File? = null,
) {
    init {
        require(validProviderInstanceId(instanceId))
        require(providerId.value.matches(Regex("[a-z][a-z0-9_-]{0,31}")))
    }
    companion object { private val lock = Any() }
    private fun <T> locked(action: () -> T): T = synchronized(lock) {
        val file = lockFile
        if (file == null) action() else RandomAccessFile(file, "rw").use { handle ->
            handle.channel.lock().use { action() }
        }
    }

    fun read(legacy: () -> List<ConfiguredSource>): List<ConfiguredSource> = locked { readUnlocked(legacy) }

    private fun readUnlocked(legacy: () -> List<ConfiguredSource>): List<ConfiguredSource> {
        val bytes = store.read() ?: return validate(legacy().toList()).also { encode(it) }
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readInt() == 1)
            check(input.readUTF() == instanceId && input.readUTF() == providerId.value)
            val count = input.readInt(); check(count in 0..MAX_CONFIGURED_SOURCES)
            fun marker() = input.readUnsignedByte().also { check(it in 0..1) } == 1
            fun request(kind: NativeQualityKind): NativeQualityRequest {
                if (!marker()) return NativeQualityRequest.auto
                val codec = NativeQualityCodec.valueOf(input.readUTF())
                fun number() = input.readInt().let { if (it == -1) null else it }
                val bitrate = number(); val first = number(); val second = number()
                return NativeQualityRequest(if (kind == NativeQualityKind.VIDEO)
                    NativeQualityTrack(kind, codec, bitrate, first, second)
                else NativeQualityTrack(kind, codec, bitrate, channelCount = first, sampleRateHz = second))
            }
            val entries = List(count) {
                val id = input.readUTF()
                val resource = CatalogResource(providerId, input.readUTF(), input.readUTF(), CatalogIntent.valueOf(input.readUTF()))
                val title = input.readUTF(); val availability = CatalogAvailability.valueOf(input.readUTF())
                val start = if (marker()) input.readLong() else null
                val quality = NativeQualityPreferences(request(NativeQualityKind.VIDEO), request(NativeQualityKind.AUDIO))
                ConfiguredSource(id, instanceId, CatalogEntry(resource, title, availability, start), quality)
            }
            check(input.read() == -1)
            validate(entries)
        }
    }

    private fun validate(entries: List<ConfiguredSource>): List<ConfiguredSource> {
        check(entries.size <= MAX_CONFIGURED_SOURCES)
        check(entries.all { it.instanceId == instanceId && it.entry.resource.providerId == providerId })
        check(entries.map { it.id }.distinct().size == entries.size)
        check(entries.map { it.entry.resource }.distinct().size == entries.size)
        return entries
    }

    fun add(entry: CatalogEntry, legacy: () -> List<ConfiguredSource>): List<ConfiguredSource> = mutate(legacy) { entries ->
        require(entry.resource.providerId == providerId)
        if (entries.any { it.entry.resource == entry.resource }) entries
        else entries + ConfiguredSource(UUID.randomUUID().toString(), instanceId, entry)
    }

    fun remove(id: String, legacy: () -> List<ConfiguredSource>): List<ConfiguredSource> = mutate(legacy) { entries ->
        check(entries.any { it.id == id })
        entries.filterNot { it.id == id }
    }

    fun reorder(ids: List<String>, legacy: () -> List<ConfiguredSource>): List<ConfiguredSource> = mutate(legacy) { entries ->
        check(ids.size == entries.size && ids.toSet() == entries.map { it.id }.toSet())
        ids.map { id -> entries.single { it.id == id } }
    }

    fun refresh(id: String, entry: CatalogEntry, legacy: () -> List<ConfiguredSource>): List<ConfiguredSource> = mutate(legacy) { entries ->
        val existing = entries.single { it.id == id }
        check(existing.entry.resource == entry.resource)
        entries.map { if (it.id == id) it.copy(entry = entry) else it }
    }

    fun saveQuality(id: String, kind: NativeQualityKind, request: NativeQualityRequest,
        legacy: () -> List<ConfiguredSource>): List<ConfiguredSource> = mutate(legacy) { entries ->
        check(entries.any { it.id == id })
        entries.map { if (it.id == id) it.copy(quality = it.quality.with(kind, request)) else it }
    }

    private fun mutate(legacy: () -> List<ConfiguredSource>, update: (List<ConfiguredSource>) -> List<ConfiguredSource>): List<ConfiguredSource> = locked {
        val entries = validate(update(readUnlocked(legacy)))
        store.write(encode(entries))
        entries
    }

    private fun encode(entries: List<ConfiguredSource>): ByteArray {
        val bytes = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(1); data.writeUTF(instanceId); data.writeUTF(providerId.value); data.writeInt(entries.size)
            fun request(value: NativeQualityRequest) {
                val track = value.track
                data.writeBoolean(track != null)
                if (track != null) {
                    data.writeUTF(track.codec.name); data.writeInt(track.bitrateBps ?: -1)
                    data.writeInt((if (track.kind == NativeQualityKind.VIDEO) track.width else track.channelCount) ?: -1)
                    data.writeInt((if (track.kind == NativeQualityKind.VIDEO) track.height else track.sampleRateHz) ?: -1)
                }
            }
            entries.forEach { source ->
                val entry = source.entry; val resource = entry.resource
                data.writeUTF(source.id); data.writeUTF(resource.kind); data.writeUTF(resource.identity); data.writeUTF(resource.intent.name)
                data.writeUTF(entry.title); data.writeUTF(entry.availability.name)
                data.writeBoolean(entry.scheduledStartEpochMs != null); entry.scheduledStartEpochMs?.let(data::writeLong)
                request(source.quality.video); request(source.quality.audio)
            }
        } }.toByteArray()
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        return bytes
    }
}
