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

// A missing record is an empty list; built-ins are never seeded. The fallback
// parameter remains only for older fixtures until plumbing cleanup. An explicit
// mutation commits only this instance's catalog; routes, grants and legacy slots are untouched.
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
    // Version 2 (#713) adds each item's listed length and a broadcast's real
    // replay length; version 1 records stay readable and are rewritten as
    // version 2 only by the next mutation.
    companion object { private val lock = Any(); const val RECORD_VERSION = 2 }
    private fun <T> locked(action: () -> T): T = synchronized(lock) {
        val file = lockFile
        if (file == null) action() else RandomAccessFile(file, "rw").use { handle ->
            handle.channel.lock().use { action() }
        }
    }

    fun read(legacy: () -> List<ConfiguredSource> = { emptyList() }): List<ConfiguredSource> = locked { readUnlocked(legacy) }

    private fun readUnlocked(legacy: () -> List<ConfiguredSource>): List<ConfiguredSource> {
        val bytes = store.read() ?: return validate(legacy().toList()).also { encode(it) }
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        val saved = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt(); check(version in 1..RECORD_VERSION)
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
                // Version 1 records hold no lengths.
                fun length() = if (version >= 2 && marker()) input.readInt().toLong() else null
                val duration = length(); val replay = length()
                val quality = NativeQualityPreferences(request(NativeQualityKind.VIDEO), request(NativeQualityKind.AUDIO))
                ConfiguredSource(id, instanceId, CatalogEntry(resource, title, availability, start, duration), quality, replay)
            }
            check(input.read() == -1)
            validate(entries)
        }
        // Callers hold the lock, so dropping unplayable built-in Twitch live entries commits once.
        val kept = saved.filterNot(::legacyTwitchLiveSource)
        if (kept.size != saved.size) store.write(encode(kept))
        return kept
    }

    private fun validate(entries: List<ConfiguredSource>): List<ConfiguredSource> {
        check(entries.size <= MAX_CONFIGURED_SOURCES)
        check(entries.all { it.instanceId == instanceId && it.entry.resource.providerId == providerId })
        check(entries.map { it.id }.distinct().size == entries.size)
        check(entries.map { it.entry.resource }.distinct().size == entries.size)
        return entries
    }

    fun add(entry: CatalogEntry, legacy: () -> List<ConfiguredSource>) = add(entry, null, legacy)
    fun add(entry: CatalogEntry, replayLengthSeconds: Long?, legacy: () -> List<ConfiguredSource>): List<ConfiguredSource> = mutate(legacy) { entries ->
        require(entry.resource.providerId == providerId)
        if (entries.any { it.entry.resource == entry.resource }) entries
        else entries + ConfiguredSource(UUID.randomUUID().toString(), instanceId, entry, replayLengthSeconds = replayLengthSeconds)
    }

    fun remove(id: String, legacy: () -> List<ConfiguredSource>): List<ConfiguredSource> = mutate(legacy) { entries ->
        check(entries.any { it.id == id })
        entries.filterNot { it.id == id }
    }

    fun reorder(ids: List<String>, legacy: () -> List<ConfiguredSource>): List<ConfiguredSource> = mutate(legacy) { entries ->
        check(ids.size == entries.size && ids.toSet() == entries.map { it.id }.toSet())
        ids.map { id -> entries.single { it.id == id } }
    }

    fun move(id: String, delta: Int, legacy: () -> List<ConfiguredSource>): List<ConfiguredSource> = mutate(legacy) { entries ->
        val index = entries.indexOfFirst { it.id == id }
        check(index >= 0 && delta in setOf(-1, 1) && index + delta in entries.indices)
        entries.toMutableList().apply { val moved = removeAt(index); add(index + delta, moved) }
    }

    fun refresh(id: String, entry: CatalogEntry, legacy: () -> List<ConfiguredSource>) = refresh(id, entry, null, legacy)
    // A known real replay length is kept unless a newer one is given.
    fun refresh(id: String, entry: CatalogEntry, replayLengthSeconds: Long?,
        legacy: () -> List<ConfiguredSource>): List<ConfiguredSource> = mutate(legacy) { entries ->
        val existing = entries.single { it.id == id }
        check(existing.entry.resource == entry.resource)
        entries.map { if (it.id == id) it.copy(entry = entry, replayLengthSeconds = replayLengthSeconds ?: it.replayLengthSeconds) else it }
    }

    // Saves real replay lengths read for configured broadcasts lacking one.
    // Writes only when a length is new; removed items are not recreated.
    fun recordReplayLengths(lengths: Map<CatalogResource, Long>, legacy: () -> List<ConfiguredSource>): List<ConfiguredSource> = locked {
        val entries = readUnlocked(legacy)
        val updated = entries.map { source ->
            lengths[source.entry.resource]?.takeIf { source.replayLengthSeconds == null &&
                source.entry.resource.intent == CatalogIntent.BROADCAST && it in 1..MAX_CATALOG_DURATION_SECONDS }
                ?.let { source.copy(replayLengthSeconds = it) } ?: source
        }
        if (updated != entries) store.write(encode(validate(updated)))
        updated
    }

    fun saveQuality(id: String, kind: NativeQualityKind, request: NativeQualityRequest,
        legacy: () -> List<ConfiguredSource> = { emptyList() }): List<ConfiguredSource> = mutate(legacy) { entries ->
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
            data.writeInt(RECORD_VERSION); data.writeUTF(instanceId); data.writeUTF(providerId.value); data.writeInt(entries.size)
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
                // Lengths are bounded by MAX_CATALOG_DURATION_SECONDS, so an Int holds them.
                for (length in listOf(entry.durationSeconds, source.replayLengthSeconds)) {
                    data.writeBoolean(length != null); length?.let { data.writeInt(it.toInt()) }
                }
                request(source.quality.video); request(source.quality.audio)
            }
        } }.toByteArray()
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        return bytes
    }
}
