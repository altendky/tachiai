package net.fstab.tachiai.presentation

import java.util.Locale
import net.fstab.tachiai.provider.catalog.CatalogResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

// Display-only translation of provider-supplied text (#643). Search, matching,
// persistence and IDs always keep the original; Tachiai's own strings never
// enter this layer.

/** A provider instance's Source language: [AUTO] detects each string, otherwise a fixed BCP-47 language. */
@JvmInline
internal value class ProviderSourceLanguage(val tag: String) {
    val auto get() = tag == AUTO_TAG
    companion object {
        const val AUTO_TAG = "auto"
        val AUTO = ProviderSourceLanguage(AUTO_TAG)
        fun valid(tag: String) = tag == AUTO_TAG || PROVIDER_SOURCE_LANGUAGES.any { it.first == tag }
    }
}

/** The common Source language choices. Labels are Tachiai UI text, not provider text. */
internal val PROVIDER_SOURCE_LANGUAGES: List<Pair<String, String>> = listOf(
    "ja" to "Japanese", "en" to "English", "ko" to "Korean", "zh" to "Chinese", "es" to "Spanish",
    "pt" to "Portuguese", "fr" to "French", "de" to "German", "it" to "Italian", "ru" to "Russian",
    "th" to "Thai", "vi" to "Vietnamese", "id" to "Indonesian",
)

internal fun providerSourceLanguageLabel(language: ProviderSourceLanguage): String =
    if (language.auto) "Auto" else PROVIDER_SOURCE_LANGUAGES.firstOrNull { it.first == language.tag }?.second ?: language.tag

/** ABEMA text is Japanese; kanji-only strings would otherwise be detected as Chinese. */
internal fun defaultProviderSourceLanguage(service: PrototypeService): ProviderSourceLanguage = when (service) {
    PrototypeService.ABEMA -> ProviderSourceLanguage("ja")
    PrototypeService.TWITCH, PrototypeService.YOUTUBE -> ProviderSourceLanguage.AUTO
}

/** Which provider supplied a string; the instance ID selects its Source language setting. */
internal data class ProviderTextSource(val service: PrototypeService, val instanceId: String? = null)

internal fun providerTextSource(providerId: ProviderId, instanceId: String?): ProviderTextSource? =
    PrototypeService.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == providerId.value }
        ?.let { ProviderTextSource(it, instanceId) }

/**
 * True for Tachiai's own catalog titles: link placeholders made before provider
 * metadata, unavailable-item fallbacks and legacy built-in labels. Each check
 * uses the function that builds that title for this exact resource.
 */
internal fun tachiaiCatalogTitle(resource: CatalogResource, title: String): Boolean = when (resource.providerId.value) {
    "abema" -> title == net.fstab.tachiai.provider.abema.abemaPublicResourceTitle(resource)
    "twitch" -> title == net.fstab.tachiai.provider.twitch.catalog.twitchUnavailableTitle(resource) ||
        title == net.fstab.tachiai.provider.twitch.catalog.twitchUncheckedVideoTitle(resource.identity)
    "youtube" -> title == net.fstab.tachiai.provider.youtube.youTubeUncheckedTitle(resource)
    else -> false
} || PrototypeSource.entries.any { prototypeCatalogResource(it) == resource && (title == it.title || title == it.optionTitle) }

/** The source for a catalog title, or null when Tachiai made the title (never translated). */
internal fun catalogTitleSource(resource: CatalogResource, title: String, source: ProviderTextSource?): ProviderTextSource? =
    source.takeUnless { tachiaiCatalogTitle(resource, title) }

/** Null when the shown title is Tachiai's own label rather than provider text. */
internal fun configuredSourceTextSource(source: ConfiguredSource): ProviderTextSource? =
    catalogTitleSource(source.entry.resource, configuredSourceDisplayTitle(source),
        providerTextSource(source.entry.resource.providerId, source.instanceId))

internal const val MAX_TRANSLATION_SETTINGS_ENTRIES = 64

internal data class ProviderTextTranslationSettings(
    val enabled: Boolean = false,
    // Explicit per-instance choices only; an absent entry uses the provider default.
    val sourceLanguages: Map<String, ProviderSourceLanguage> = emptyMap(),
    // Instances whose Translate text in video default is on (#664); absent means off.
    val frameTextInstances: Set<String> = emptySet(),
) {
    init {
        require(sourceLanguages.size <= MAX_TRANSLATION_SETTINGS_ENTRIES)
        require(sourceLanguages.all { (id, language) -> validProviderInstanceId(id) && ProviderSourceLanguage.valid(language.tag) })
        require(frameTextInstances.size <= MAX_TRANSLATION_SETTINGS_ENTRIES)
        require(frameTextInstances.all(::validProviderInstanceId))
    }

    /** The provider default for translating text in the video picture (#664); off unless chosen. */
    fun frameText(instanceId: String?): Boolean = instanceId != null && instanceId in frameTextInstances

    fun withFrameText(instance: ProviderInstance, on: Boolean, existingIds: Set<String>) =
        copy(frameTextInstances = frameTextInstances.filter { it in existingIds && it != instance.id }.toSet() +
            if (on) setOf(instance.id) else emptySet())

    /** Settings without any choice for a removed instance. */
    fun withoutInstance(id: String) = copy(sourceLanguages = sourceLanguages - id, frameTextInstances = frameTextInstances - id)

    fun sourceLanguage(source: ProviderTextSource): ProviderSourceLanguage =
        source.instanceId?.let(sourceLanguages::get) ?: defaultProviderSourceLanguage(source.service)

    fun withSourceLanguage(instance: ProviderInstance, language: ProviderSourceLanguage, existingIds: Set<String>) =
        copy(sourceLanguages = (sourceLanguages.filterKeys { it in existingIds && it != instance.id } +
            if (language == defaultProviderSourceLanguage(instance.service)) emptyMap() else mapOf(instance.id to language)))

    /** Null means show the original without consulting an engine. */
    fun request(text: String, source: ProviderTextSource, target: String?): TranslationRequest? =
        if (!enabled) null else languageRequest(text, source, target)

    /**
     * The same language rules without the Translate provider text switch. Text
     * recognized in the video (#664) has its own per-provider switch and
     * in-stream override, and uses this provider's Source language.
     */
    fun languageRequest(text: String, source: ProviderTextSource, target: String?): TranslationRequest? {
        if (target == null || text.length > MAX_TRANSLATED_TEXT || text.none(Char::isLetter)) return null
        val language = sourceLanguage(source)
        if (!language.auto && (language.tag == target || !textMayBeInLanguage(text, language.tag))) return null
        return TranslationRequest(text, language, target)
    }
}

// Languages written in a distinctive script. A fixed Source language keeps text
// without any character of that script (for example a Latin-only title under
// ABEMA's Japanese) as the original. Latin-script languages are not checked.
private val LANGUAGE_SCRIPTS: Map<String, Set<Character.UnicodeScript>> = buildMap {
    put("ja", setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HAN))
    put("zh", setOf(Character.UnicodeScript.HAN))
    put("ko", setOf(Character.UnicodeScript.HANGUL))
    listOf("ru", "uk", "be", "bg", "mk").forEach { put(it, setOf(Character.UnicodeScript.CYRILLIC)) }
    listOf("ar", "fa", "ur").forEach { put(it, setOf(Character.UnicodeScript.ARABIC)) }
    put("he", setOf(Character.UnicodeScript.HEBREW))
    put("th", setOf(Character.UnicodeScript.THAI))
    put("el", setOf(Character.UnicodeScript.GREEK))
    listOf("hi", "mr", "ne").forEach { put(it, setOf(Character.UnicodeScript.DEVANAGARI)) }
    put("bn", setOf(Character.UnicodeScript.BENGALI))
    put("ta", setOf(Character.UnicodeScript.TAMIL))
    put("te", setOf(Character.UnicodeScript.TELUGU))
    put("kn", setOf(Character.UnicodeScript.KANNADA))
    put("gu", setOf(Character.UnicodeScript.GUJARATI))
    put("ka", setOf(Character.UnicodeScript.GEORGIAN))
}

/** False when [language] has a distinctive script and [text] has no character in it. */
internal fun textMayBeInLanguage(text: String, language: String): Boolean {
    val scripts = LANGUAGE_SCRIPTS[language] ?: return true
    return text.codePoints().anyMatch { Character.UnicodeScript.of(it) in scripts }
}

internal const val MAX_TRANSLATED_TEXT = 2_000

/** Device language as the translation target, or null when it is not a plain language. */
internal fun translationTargetLanguage(locale: Locale): String? =
    locale.toLanguageTag().substringBefore('-').lowercase(Locale.ROOT).takeIf { it.length in 2..3 && it != "und" }

internal data class TranslationRequest(val text: String, val source: ProviderSourceLanguage, val target: String)

/** Engine boundary: ML Kit on Android; fakes in tests. */
internal interface TextTranslationEngine {
    fun supports(language: String): Boolean
    /** Detected BCP-47 language, or null when detection is unsure. */
    suspend fun identify(text: String): String?
    /** Ensures the on-device model for this pair; throws when unavailable. */
    suspend fun prepare(source: String, target: String)
    suspend fun translate(text: String, source: String, target: String): String
}

internal sealed interface TranslationAvailability {
    data object Idle : TranslationAvailability
    data class Downloading(val languages: Set<String>) : TranslationAvailability
    data object Ready : TranslationAvailability
    data class TargetUnsupported(val target: String) : TranslationAvailability
    data class DownloadFailed(val languages: Set<String>) : TranslationAvailability
}

internal fun translationAvailabilityText(enabled: Boolean, availability: TranslationAvailability): String = when {
    !enabled -> "Off. Provider text is shown as supplied."
    else -> when (availability) {
        TranslationAvailability.Idle -> "On. Text is translated as it appears."
        is TranslationAvailability.Downloading -> "Downloading ${languageNames(availability.languages)} translation model… Originals are shown until it is ready."
        TranslationAvailability.Ready -> "On. Translated text replaces originals once ready."
        is TranslationAvailability.TargetUnsupported -> "Translation is unavailable: this device language is not supported. Originals are shown."
        is TranslationAvailability.DownloadFailed -> "Translation is unavailable: the ${languageNames(availability.languages)} model could not be downloaded. Originals are shown; it is retried later."
    }
}

private fun languageNames(languages: Set<String>) = languages.sorted().joinToString(", ") { tag ->
    PROVIDER_SOURCE_LANGUAGES.firstOrNull { it.first == tag }?.second
        ?: Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH).ifBlank { tag }
}

/**
 * Lazily translates provider text. The original is always available first;
 * a translation replaces it only when ready. Detection that is unsure, text
 * already in the target language and unsupported languages keep the original.
 * Model or engine failures also keep the original, without caching that result.
 */
internal class ProviderTextTranslator(
    private val engine: TextTranslationEngine,
    private val scope: CoroutineScope,
    private val cacheEntries: Int = 1_024,
    private val retryAfterMillis: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private sealed interface Outcome {
        data class Translated(val text: String) : Outcome
        data object KeepOriginal : Outcome
        data object Unavailable : Outcome
    }
    private sealed interface ModelState {
        data class Downloading(val work: Deferred<Boolean>) : ModelState
        data object Ready : ModelState
        data class Failed(val atMillis: Long) : ModelState
    }
    private data class Pair(val source: String, val target: String)

    private val lock = Any()
    // Keys are source text plus language pair only; no provider identity is kept.
    private val cache = object : LinkedHashMap<TranslationRequest, Outcome>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TranslationRequest, Outcome>) = size > cacheEntries
    }
    private val pending = mutableMapOf<TranslationRequest, Deferred<Outcome>>()
    private val models = mutableMapOf<Pair, ModelState>()
    private var unsupportedTarget: String? = null
    private val mutableAvailability = MutableStateFlow<TranslationAvailability>(TranslationAvailability.Idle)
    val availability: StateFlow<TranslationAvailability> = mutableAvailability

    /** The cached display text, or null when the original should be shown for now. */
    fun cached(request: TranslationRequest): String? = synchronized(lock) {
        (cache[request] as? Outcome.Translated)?.text
    }

    /** Emits the original (or a cached translation) first, then a translation when it becomes available. */
    fun display(request: TranslationRequest): Flow<String> = flow {
        val cached = cached(request)
        emit(cached ?: request.text)
        if (cached == null) translate(request)?.let { emit(it) }
    }

    /** The translation, or null to keep showing the original. Never throws for engine failures. */
    suspend fun translate(request: TranslationRequest): String? {
        val work = synchronized(lock) {
            cache[request]?.let { return (it as? Outcome.Translated)?.text }
            pending.getOrPut(request) {
                scope.async { resolve(request) }.also { deferred ->
                    deferred.invokeOnCompletion { synchronized(lock) { if (pending[request] === deferred) pending.remove(request) } }
                }
            }
        }
        return (work.await() as? Outcome.Translated)?.text
    }

    /** Starts model downloads for fixed source languages once translation is enabled. */
    fun prepare(sources: Collection<String>, target: String) {
        if (!checkTarget(target)) return
        sources.filter { it != target && engine.supports(it) }.distinct().forEach { model(Pair(it, target)) }
    }

    private suspend fun resolve(request: TranslationRequest): Outcome {
        val outcome = try {
            if (!checkTarget(request.target)) Outcome.Unavailable else {
                val source = if (request.source.auto) normalizedDetection(engine.identify(request.text)) else request.source.tag
                when {
                    source == null || source == request.target || !engine.supports(source) -> Outcome.KeepOriginal
                    !model(Pair(source, request.target)).await() -> Outcome.Unavailable
                    else -> engine.translate(request.text, source, request.target).let {
                        if (it.isBlank() || it == request.text) Outcome.KeepOriginal else Outcome.Translated(it)
                    }
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { Outcome.Unavailable }
        if (outcome != Outcome.Unavailable) synchronized(lock) { cache[request] = outcome }
        return outcome
    }

    // A script subtag (for example ja-Latn) is romanized text; treat it as unsure.
    private fun normalizedDetection(tag: String?): String? =
        tag?.lowercase(Locale.ROOT)?.takeIf { it != "und" && '-' !in it && it.length in 2..3 }

    private fun checkTarget(target: String): Boolean = synchronized(lock) {
        if (engine.supports(target)) { if (unsupportedTarget == target) unsupportedTarget = null; publish(); true }
        else { unsupportedTarget = target; publish(); false }
    }

    private fun model(pair: Pair): Deferred<Boolean> = synchronized(lock) {
        when (val state = models[pair]) {
            ModelState.Ready -> return CompletedTrue
            is ModelState.Downloading -> return state.work
            is ModelState.Failed -> if (clock() - state.atMillis < retryAfterMillis) return CompletedFalse
            null -> Unit
        }
        val work = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val ready = try { engine.prepare(pair.source, pair.target); true }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { false }
            synchronized(lock) { models[pair] = if (ready) ModelState.Ready else ModelState.Failed(clock()); publish() }
            ready
        }
        models[pair] = ModelState.Downloading(work)
        publish()
        work.start()
        work
    }

    private fun publish() {
        mutableAvailability.value = unsupportedTarget?.let { TranslationAvailability.TargetUnsupported(it) } ?: run {
            val downloading = models.filterValues { it is ModelState.Downloading }.keys.map { it.source }.toSet()
            val failed = models.filterValues { it is ModelState.Failed }.keys.map { it.source }.toSet()
            when {
                downloading.isNotEmpty() -> TranslationAvailability.Downloading(downloading)
                failed.isNotEmpty() -> TranslationAvailability.DownloadFailed(failed)
                models.isNotEmpty() -> TranslationAvailability.Ready
                else -> TranslationAvailability.Idle
            }
        }
    }

    private companion object {
        val CompletedTrue: Deferred<Boolean> = kotlinx.coroutines.CompletableDeferred(true)
        val CompletedFalse: Deferred<Boolean> = kotlinx.coroutines.CompletableDeferred(false)
    }
}
