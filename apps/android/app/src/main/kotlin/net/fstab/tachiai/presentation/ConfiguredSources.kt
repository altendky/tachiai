package net.fstab.tachiai.presentation

import java.util.UUID
import net.fstab.tachiai.platform.media.NativeQualityPreferences
import net.fstab.tachiai.provider.catalog.*

internal const val MAX_CONFIGURED_SOURCES = 32

internal data class ConfiguredSource(
    val id: String,
    val instanceId: String,
    val entry: CatalogEntry,
    val quality: NativeQualityPreferences = NativeQualityPreferences(),
) {
    init { require(validProviderInstanceId(id) && validProviderInstanceId(instanceId)) }
    val choice get() = ConfiguredFeedChoice(id, instanceId)
}

internal data class ConfiguredFeedChoice(val itemId: String, val instanceId: String) {
    init { require(validProviderInstanceId(itemId) && validProviderInstanceId(instanceId)) }
    fun resolve(sources: List<ConfiguredSource>): ConfiguredSource? =
        sources.singleOrNull { it.id == itemId && it.instanceId == instanceId }
}

internal data class ConfiguredFeedAssignments(val a: ConfiguredFeedChoice?, val b: ConfiguredFeedChoice?) {
    fun assign(slot: PrototypeSlot, choice: ConfiguredFeedChoice, checked: Boolean): ConfiguredFeedAssignments {
        val previous = if (slot == PrototypeSlot.A) a else b
        val next = if (checked) choice else previous?.takeUnless { it == choice }
        return if (slot == PrototypeSlot.A) copy(a = next) else copy(b = next)
    }
}

internal fun configuredLegacyId(instanceId: String, source: PrototypeSource): String {
    require(validProviderInstanceId(instanceId))
    return UUID.nameUUIDFromBytes("tachiai:configured:v1:$instanceId:${source.name}".toByteArray(Charsets.US_ASCII)).toString()
}

internal fun prototypeCatalogResource(source: PrototypeSource): CatalogResource = when (source) {
    PrototypeSource.ABEMA_LIVE -> CatalogResource(ProviderId("abema"), "channel", "abema-news", CatalogIntent.CHANNEL)
    PrototypeSource.ABEMA_REPLAY -> CatalogResource(ProviderId("abema"), "episode", "394-72_s10_p8529", CatalogIntent.VIDEO)
    else -> CatalogResource(ProviderId("twitch"), if (source.kind == PrototypePlaybackKind.LIVE) "channel" else "video",
        checkNotNull(source.resourceId), if (source.kind == PrototypePlaybackKind.LIVE) CatalogIntent.CHANNEL else CatalogIntent.VIDEO)
}

// Read-only migration: provider routes/grants, source metadata, quality records
// and recovery files retain their original identities and bytes. New items own
// copied defaults, so subsequent edits cannot cross account/instance boundaries.
internal fun legacyConfiguredSources(instance: ProviderInstance, setups: Map<PrototypeSource, SourceSetup>,
    qualities: Map<PrototypeSource, NativeQualityPreferences>): List<ConfiguredSource> =
    PrototypeSource.entries.filter { it.service == instance.service }.map { source ->
        ConfiguredSource(configuredLegacyId(instance.id, source), instance.id,
            CatalogEntry(prototypeCatalogResource(source), checkNotNull(setups[source]).name),
            qualities[source] ?: NativeQualityPreferences())
    }

internal fun configuredChoice(choice: PrototypeFeedChoice) =
    ConfiguredFeedChoice(configuredLegacyId(choice.instanceId, choice.source), choice.instanceId)

// Exact resource bridge until provider-specific dynamic resolution lands. Local
// IDs may change on remove/re-add; public identity prevents relabeled playback.
internal fun legacyPrototypeSource(source: ConfiguredSource): PrototypeSource? = PrototypeSource.entries.singleOrNull {
    source.entry.resource == prototypeCatalogResource(it)
}

internal fun encodeConfiguredFeedChoice(choice: ConfiguredFeedChoice?): String? =
    choice?.let { "v2|${it.itemId}|${it.instanceId}" }

internal fun decodeConfiguredFeedChoice(encoded: String?): ConfiguredFeedChoice? {
    if (encoded == null || encoded.length > 77) return null
    if (!encoded.startsWith("v2|")) return decodePrototypeFeedChoice(encoded)?.let(::configuredChoice)
    return runCatching {
        val parts = encoded.split('|'); check(parts.size == 3)
        ConfiguredFeedChoice(parts[1], parts[2])
    }.getOrNull()
}

internal fun restoreConfiguredFeedAssignments(savedStatePresent: Boolean, a: String?, b: String?): ConfiguredFeedAssignments =
    if (savedStatePresent) ConfiguredFeedAssignments(decodeConfiguredFeedChoice(a), decodeConfiguredFeedChoice(b))
    else PrototypeSelection().feeds.let { ConfiguredFeedAssignments(configuredChoice(it[0]), configuredChoice(it[1])) }
