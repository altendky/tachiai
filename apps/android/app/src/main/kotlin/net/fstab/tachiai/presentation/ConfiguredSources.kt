package net.fstab.tachiai.presentation

import java.util.Base64
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

// An exact episode chosen from a saved collection for one feed (#550). It is
// bound to that collection's public identity and is not a configured item.
internal data class ConfiguredEpisodeChoice(val collection: CatalogResource, val episode: CatalogResource, val title: String) {
    init {
        require(collection.intent == CatalogIntent.COLLECTION && episode.intent == CatalogIntent.VIDEO &&
            collection.providerId == episode.providerId)
        require(title.length in 1..160 && title == title.trim() && title.none { it.isISOControl() })
    }
    // ASCII-only, for saved state and the recovery checkpoint.
    fun encode(): String = listOf("e1", collection.providerId.value, collection.kind, collection.identity, episode.kind,
        episode.identity, Base64.getUrlEncoder().withoutPadding().encodeToString(title.toByteArray(Charsets.UTF_8))).joinToString("|")

    companion object {
        const val MAX_ENCODED = 1024
        fun decode(encoded: String?): ConfiguredEpisodeChoice? {
            if (encoded == null || encoded.length > MAX_ENCODED) return null
            return runCatching {
                val parts = encoded.split('|')
                check(parts.size == 7 && parts[0] == "e1")
                val provider = ProviderId(parts[1])
                val title = String(Base64.getUrlDecoder().decode(parts[6]), Charsets.UTF_8)
                ConfiguredEpisodeChoice(CatalogResource(provider, parts[2], parts[3], CatalogIntent.COLLECTION),
                    CatalogResource(provider, parts[4], parts[5], CatalogIntent.VIDEO), title).takeIf { it.encode() == encoded }
            }.getOrNull()
        }
    }
}

internal data class ConfiguredFeedAssignments(val a: ConfiguredFeedChoice?, val b: ConfiguredFeedChoice?,
    val aEpisode: ConfiguredEpisodeChoice? = null, val bEpisode: ConfiguredEpisodeChoice? = null) {
    init { require((aEpisode == null || a != null) && (bEpisode == null || b != null)) }

    fun choice(slot: PrototypeSlot) = if (slot == PrototypeSlot.A) a else b
    fun episode(slot: PrototypeSlot) = if (slot == PrototypeSlot.A) aEpisode else bEpisode

    private fun with(slot: PrototypeSlot, choice: ConfiguredFeedChoice?, episode: ConfiguredEpisodeChoice?) =
        if (slot == PrototypeSlot.A) copy(a = choice, aEpisode = episode) else copy(b = choice, bEpisode = episode)

    // Changing a slot's item clears its episode; re-checking the same item keeps it.
    fun assign(slot: PrototypeSlot, choice: ConfiguredFeedChoice, checked: Boolean): ConfiguredFeedAssignments {
        val previous = choice(slot)
        return if (checked) with(slot, choice, episode(slot).takeIf { previous == choice })
            else if (previous == choice) with(slot, null, null) else this
    }

    // Assigns one exact episode of a collection item; unchecking it empties the slot.
    fun assignEpisode(slot: PrototypeSlot, choice: ConfiguredFeedChoice, episode: ConfiguredEpisodeChoice,
        checked: Boolean): ConfiguredFeedAssignments =
        if (checked) with(slot, choice, episode)
        else if (choice(slot) == choice && episode(slot) == episode) with(slot, null, null) else this

    // A removed item leaves its slot empty; it is never substituted. An episode
    // survives only while its item still holds the same collection.
    fun resolvedIn(sources: List<ConfiguredSource>): ConfiguredFeedAssignments {
        fun kept(choice: ConfiguredFeedChoice?) = choice?.takeIf { it.resolve(sources) != null }
        fun episode(choice: ConfiguredFeedChoice?, episode: ConfiguredEpisodeChoice?) =
            episode?.takeIf { choice?.resolve(sources)?.entry?.resource == it.collection }
        return ConfiguredFeedAssignments(kept(a), kept(b), episode(kept(a), aEpisode), episode(kept(b), bEpisode))
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

// Former built-in projection, kept only for fixtures until plumbing cleanup.
// Product reads never seed it: an instance without a saved list has no items.
internal fun legacyConfiguredSources(instance: ProviderInstance, setups: Map<PrototypeSource, SourceSetup>,
    qualities: Map<PrototypeSource, NativeQualityPreferences>): List<ConfiguredSource> =
    PrototypeSource.entries.filter { it.service == instance.service }.map { source ->
        ConfiguredSource(configuredLegacyId(instance.id, source), instance.id,
            CatalogEntry(prototypeCatalogResource(source), checkNotNull(setups[source]).name),
            qualities[source] ?: NativeQualityPreferences())
    }

// Saved built-in Twitch live entries name a login, not the catalog broadcaster
// identity configured playback requires, so they cannot play and are dropped.
internal fun legacyTwitchLiveSource(source: ConfiguredSource): Boolean = PrototypeSource.entries.any {
    it.service == PrototypeService.TWITCH && it.kind == PrototypePlaybackKind.LIVE &&
        source.id == configuredLegacyId(source.instanceId, it) && source.entry.resource == prototypeCatalogResource(it)
}

internal fun configuredChoice(choice: PrototypeFeedChoice) =
    ConfiguredFeedChoice(configuredLegacyId(choice.instanceId, choice.source), choice.instanceId)

// Display only: saved built-in items keep their short historical titles.
internal fun legacyPrototypeSource(source: ConfiguredSource): PrototypeSource? = PrototypeSource.entries.singleOrNull {
    source.entry.resource == prototypeCatalogResource(it)
}

internal fun configuredSourceDisplayTitle(source: ConfiguredSource): String {
    val legacy = legacyPrototypeSource(source)
    return if (legacy != null && source.entry.title == legacy.title) legacy.optionTitle else source.entry.title
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

// There is no first-run default pair: without saved state both slots are empty.
internal fun restoreConfiguredFeedAssignments(savedStatePresent: Boolean, a: String?, b: String?,
    aEpisode: String? = null, bEpisode: String? = null): ConfiguredFeedAssignments =
    if (savedStatePresent) decodeConfiguredFeedAssignments(a, b, aEpisode, bEpisode)
    else ConfiguredFeedAssignments(null, null)

// An episode without its item is dropped rather than failing the whole restore.
internal fun decodeConfiguredFeedAssignments(a: String?, b: String?, aEpisode: String?, bEpisode: String?): ConfiguredFeedAssignments {
    val first = decodeConfiguredFeedChoice(a); val second = decodeConfiguredFeedChoice(b)
    return ConfiguredFeedAssignments(first, second,
        ConfiguredEpisodeChoice.decode(aEpisode)?.takeIf { first != null },
        ConfiguredEpisodeChoice.decode(bEpisode)?.takeIf { second != null })
}
