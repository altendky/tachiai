package net.fstab.tachiai.presentation

import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ProviderTextTranslationTest {
    private val abema = ProviderTextSource(PrototypeService.ABEMA, defaultProviderInstanceId(PrototypeService.ABEMA))
    private val twitch = ProviderTextSource(PrototypeService.TWITCH, defaultProviderInstanceId(PrototypeService.TWITCH))
    private val on = ProviderTextTranslationSettings(providerTextInstances = setOf(abema.instanceId!!, twitch.instanceId!!))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class FakeEngine(private val supported: Set<String> = setOf("ja", "en", "ko", "zh", "fr")) : TextTranslationEngine {
        val detections = mutableMapOf<String, String?>()
        val identified = mutableListOf<String>()
        val prepared = mutableListOf<Pair<String, String>>()
        val translated = mutableListOf<Triple<String, String, String>>()
        var prepareFails = false
        var prepareGate: CompletableDeferred<Unit>? = null
        override fun supports(language: String) = language in supported
        override suspend fun identify(text: String): String? { identified += text; return detections[text] }
        override suspend fun prepare(source: String, target: String) {
            synchronized(prepared) { prepared += source to target }
            prepareGate?.await()
            if (prepareFails) error("download failed")
        }
        override suspend fun translate(text: String, source: String, target: String): String {
            synchronized(translated) { translated += Triple(text, source, target) }
            return "[$source>$target] $text"
        }
    }

    @After fun close() { scope.cancel() }

    @Test fun disabledOrNonTextKeepsOriginalWithoutEngine() {
        assertNull(ProviderTextTranslationSettings().request("大相撲", abema, "en"))
        assertNull(on.request("大相撲", abema, null))
        assertNull(on.request("11.22 | 15", abema, "en"))
        assertNull(on.request("x".repeat(MAX_TRANSLATED_TEXT + 1), abema, "en"))
        // A fixed source language equal to the device language needs no engine.
        assertNull(on.request("大相撲", abema, "ja"))
        assertEquals(TranslationRequest("大相撲", ProviderSourceLanguage("ja"), "en"), on.request("大相撲", abema, "en"))
    }

    @Test fun providerTextIsPerInstanceAndOffByDefault() {
        val abemaInstance = ProviderInstance(abema.instanceId!!, PrototypeService.ABEMA, "ABEMA")
        val twitchInstance = ProviderInstance(twitch.instanceId!!, PrototypeService.TWITCH, "Twitch")
        val ids = setOf(abemaInstance.id, twitchInstance.id)
        val off = ProviderTextTranslationSettings()
        assertFalse(off.providerText(abemaInstance.id)); assertFalse(off.providerText(null))
        assertNull(off.request("大相撲", abema, "en"))
        // ABEMA on, Twitch off: only ABEMA's text goes through the translator.
        val abemaOnly = off.withProviderText(abemaInstance, true, ids)
        assertEquals(setOf(abemaInstance.id), abemaOnly.providerTextInstances)
        assertNotNull(abemaOnly.request("大相撲", abema, "en"))
        assertNull(abemaOnly.request("大相撲", twitch, "en"))
        // A source without an instance is never translated.
        assertNull(abemaOnly.request("大相撲", ProviderTextSource(PrototypeService.ABEMA), "en"))
        // Text in video stays independent.
        assertFalse(abemaOnly.frameText(abemaInstance.id))
        assertNull(off.withFrameText(abemaInstance, true, ids).request("大相撲", abema, "en"))
        // Stale instances are dropped on the next change; removed instances lose their switch.
        val stale = "12345678-1234-1234-1234-123456789abd"
        assertEquals(setOf(abemaInstance.id, twitchInstance.id),
            abemaOnly.copy(providerTextInstances = abemaOnly.providerTextInstances + stale)
                .withProviderText(twitchInstance, true, ids).providerTextInstances)
        assertEquals(emptySet<String>(), abemaOnly.withProviderText(abemaInstance, false, ids).providerTextInstances)
        assertFalse(abemaOnly.withoutInstance(abemaInstance.id).providerText(abemaInstance.id))
        assertThrows(IllegalArgumentException::class.java) {
            ProviderTextTranslationSettings(providerTextInstances = setOf("not-an-instance"))
        }
    }

    @Test fun sourceLanguageDefaultsAndExplicitChoices() {
        assertEquals(ProviderSourceLanguage("ja"), on.sourceLanguage(abema))
        assertEquals(ProviderSourceLanguage.AUTO, on.sourceLanguage(twitch))
        assertEquals(ProviderSourceLanguage.AUTO, on.sourceLanguage(ProviderTextSource(PrototypeService.YOUTUBE)))
        val instance = ProviderInstance(defaultProviderInstanceId(PrototypeService.ABEMA), PrototypeService.ABEMA, "ABEMA")
        val other = "12345678-1234-1234-1234-123456789abc"
        val stale = "12345678-1234-1234-1234-123456789abd"
        val auto = on.copy(sourceLanguages = mapOf(stale to ProviderSourceLanguage("ko"), other to ProviderSourceLanguage("fr")))
            .withSourceLanguage(instance, ProviderSourceLanguage.AUTO, setOf(instance.id, other))
        assertEquals(mapOf(other to ProviderSourceLanguage("fr"), instance.id to ProviderSourceLanguage.AUTO), auto.sourceLanguages)
        assertEquals(ProviderSourceLanguage.AUTO, auto.sourceLanguage(abema))
        assertEquals(TranslationRequest("大相撲", ProviderSourceLanguage.AUTO, "ja"), auto.request("大相撲", abema, "ja"))
        // Choosing the provider default stores nothing for that instance.
        assertEquals(mapOf(other to ProviderSourceLanguage("fr")),
            auto.withSourceLanguage(instance, ProviderSourceLanguage("ja"), setOf(instance.id, other)).sourceLanguages)
        assertThrows(IllegalArgumentException::class.java) {
            ProviderTextTranslationSettings(sourceLanguages = mapOf(other to ProviderSourceLanguage("xx")))
        }
    }

    @Test fun fixedLanguageKeepsTextWithoutItsScript() {
        assertNull(on.request("ABEMA NEWS", abema, "en"))
        assertNull(on.request("ＡＢＥＭＡ Prime", abema, "en"))
        assertNotNull(on.request("大相撲LIVE 十一月場所", abema, "en"))
        assertNotNull(on.request("ニュース", abema, "en"))
        val instance = "12345678-1234-1234-1234-123456789abc"
        fun fixed(tag: String) = ProviderTextTranslationSettings(mapOf(instance to ProviderSourceLanguage(tag)),
            providerTextInstances = setOf(instance))
        val source = ProviderTextSource(PrototypeService.TWITCH, instance)
        assertNull(fixed("zh").request("Chillhop", source, "en"))
        assertNotNull(fixed("zh").request("中文频道", source, "en"))
        assertNull(fixed("ko").request("Live now", source, "en"))
        assertNotNull(fixed("ko").request("한국어 방송", source, "en"))
        assertNull(fixed("ru").request("Stream", source, "en"))
        assertNotNull(fixed("ru").request("Привет", source, "en"))
        assertNull(fixed("th").request("Bangkok", source, "en"))
        // Latin-script source languages keep the existing behavior.
        assertNotNull(fixed("fr").request("Bonjour à tous", source, "en"))
        assertNotNull(fixed("es").request("Hola", source, "en"))
        // Auto is unaffected; detection decides.
        assertNotNull(on.request("ABEMA NEWS", twitch, "en"))
        assertTrue(textMayBeInLanguage("Hello", "en"))
        assertFalse(textMayBeInLanguage("Hello 123", "ja"))
    }

    @Test fun tachiaiPlaceholderTitlesAreNeverTranslated() {
        val slot = checkNotNull(net.fstab.tachiai.provider.abema.parseAbemaPublicResource(
            "https://abema.tv/channels/sumo/slots/CQ2AnExAX8s7LT"))
        assertEquals("ABEMA broadcast · sumo/CQ2AnExAX8s7LT", slot.title)
        assertTrue(tachiaiCatalogTitle(slot.resource, slot.title))
        assertNull(catalogTitleSource(slot.resource, slot.title, abema))
        assertEquals(abema, catalogTitleSource(slot.resource, "大相撲LIVE 十一月場所", abema))
        listOf("https://abema.tv/channels/abema-news", "https://abema.tv/video/episode/90-1_s1_p1",
            "https://abema.tv/video/title/90-1").forEach {
            val entry = checkNotNull(net.fstab.tachiai.provider.abema.parseAbemaPublicResource(it))
            assertTrue(entry.title, tachiaiCatalogTitle(entry.resource, entry.title))
        }
        val video = net.fstab.tachiai.provider.catalog.CatalogResource(ProviderId("twitch"), "video", "2080217716",
            net.fstab.tachiai.provider.catalog.CatalogIntent.VIDEO)
        assertTrue(tachiaiCatalogTitle(video, net.fstab.tachiai.provider.twitch.catalog.twitchUnavailableTitle(video)))
        assertTrue(tachiaiCatalogTitle(video, "Public Twitch video 2080217716 (metadata not checked)"))
        assertFalse(tachiaiCatalogTitle(video, "Public Twitch video 1 (metadata not checked)"))
        val youtube = net.fstab.tachiai.provider.catalog.CatalogResource(ProviderId("youtube"), "video", "dQw4w9WgXcQ",
            net.fstab.tachiai.provider.catalog.CatalogIntent.VIDEO)
        assertTrue(tachiaiCatalogTitle(youtube, "YouTube video dQw4w9WgXcQ (metadata not checked)"))
        // A configured link saved before metadata keeps its placeholder untranslated.
        val configured = ConfiguredSource("12345678-1234-1234-1234-123456789abd", abema.instanceId!!, slot)
        assertNull(configuredSourceTextSource(configured))
        assertEquals(abema, configuredSourceTextSource(configured.copy(entry = slot.copy(title = "大相撲LIVE"))))
    }

    @Test fun targetLanguageIsThePlainDeviceLanguage() {
        assertEquals("en", translationTargetLanguage(Locale.US))
        assertEquals("he", translationTargetLanguage(Locale.forLanguageTag("he-IL")))
        assertEquals("zh", translationTargetLanguage(Locale.forLanguageTag("zh-Hant-TW")))
        assertNull(translationTargetLanguage(Locale.ROOT))
    }

    @Test fun providerSourcesComeOnlyFromProviderText() {
        assertEquals(abema, providerTextSource(ProviderId("abema"), abema.instanceId))
        assertNull(providerTextSource(ProviderId("unknown"), null))
        val instance = defaultProviderInstances().first { it.service == PrototypeService.TWITCH }
        val legacy = legacyConfiguredSources(instance, defaultSourceSetups(), emptyMap())
            .single { legacyPrototypeSource(it) == PrototypeSource.TWITCH_LIVE }
        // Tachiai's own legacy label is never translated; a provider title is.
        assertNull(configuredSourceTextSource(legacy))
        val provider = legacy.copy(entry = legacy.entry.copy(title = "Provider title"))
        assertEquals(ProviderTextSource(PrototypeService.TWITCH, instance.id), configuredSourceTextSource(provider))
    }

    @Test fun displayEmitsOriginalThenTranslationAndCaches() = runBlocking {
        val engine = FakeEngine()
        val translator = ProviderTextTranslator(engine, scope)
        val request = checkNotNull(on.request("生中継・見逃し", abema, "en"))
        assertNull(translator.cached(request))
        assertEquals(listOf("生中継・見逃し", "[ja>en] 生中継・見逃し"), translator.display(request).toList())
        assertEquals("[ja>en] 生中継・見逃し", translator.cached(request))
        assertEquals(listOf("[ja>en] 生中継・見逃し"), translator.display(request).toList())
        assertEquals("[ja>en] 生中継・見逃し", translator.translate(request))
        assertEquals(1, engine.translated.size)
        assertEquals(listOf("ja" to "en"), engine.prepared)
        assertEquals(TranslationAvailability.Ready, translator.availability.value)
        assertTrue(engine.identified.isEmpty()) // A fixed language skips detection.
    }

    @Test fun concurrentRequestsShareOneTranslation() = runBlocking {
        val engine = FakeEngine().apply { prepareGate = CompletableDeferred() }
        val translator = ProviderTextTranslator(engine, scope)
        val request = checkNotNull(on.request("大相撲", abema, "en"))
        val first = async(Dispatchers.Default) { translator.translate(request) }
        val second = async(Dispatchers.Default) { translator.translate(request) }
        while (synchronized(engine.prepared) { engine.prepared.isEmpty() }) yield()
        assertEquals(TranslationAvailability.Downloading(setOf("ja")), translator.availability.value)
        checkNotNull(engine.prepareGate).complete(Unit)
        assertEquals("[ja>en] 大相撲", first.await())
        assertEquals("[ja>en] 大相撲", second.await())
        assertEquals(1, engine.translated.size)
    }

    @Test fun autoTranslatesDetectedLanguageAndKeepsTargetOrUnsureText() = runBlocking {
        val engine = FakeEngine().apply {
            detections["안녕하세요"] = "ko"; detections["Hello there"] = "en"; detections["konnichiwa"] = "ja-Latn"
            detections["???"] = "und"; detections["Bonjour"] = null
        }
        val translator = ProviderTextTranslator(engine, scope)
        suspend fun shown(text: String) = translator.translate(checkNotNull(on.request(text, twitch, "en")))
        assertEquals("[ko>en] 안녕하세요", shown("안녕하세요"))
        assertNull(shown("Hello there"))
        assertNull(shown("konnichiwa"))
        assertNull(shown("Bonjour"))
        // Already-in-target and unsure results are cached: detection runs once.
        assertNull(shown("Hello there"))
        assertEquals(1, engine.identified.count { it == "Hello there" })
        assertEquals(listOf("ko" to "en"), engine.prepared)
    }

    @Test fun unsupportedSourceKeepsOriginal() = runBlocking {
        val engine = FakeEngine().apply { detections["Привет"] = "ru" }
        val translator = ProviderTextTranslator(engine, scope)
        assertNull(translator.translate(checkNotNull(on.request("Привет", twitch, "en"))))
        assertTrue(engine.prepared.isEmpty())
        assertTrue(engine.translated.isEmpty())
    }

    @Test fun unsupportedTargetIsUnavailable() = runBlocking {
        val engine = FakeEngine()
        val translator = ProviderTextTranslator(engine, scope)
        assertNull(translator.translate(checkNotNull(on.request("大相撲", abema, "xx"))))
        assertEquals(TranslationAvailability.TargetUnsupported("xx"), translator.availability.value)
        assertTrue(engine.prepared.isEmpty())
    }

    @Test fun failedDownloadKeepsOriginalAndRetriesLater() = runBlocking {
        var now = 0L
        val engine = FakeEngine().apply { prepareFails = true }
        val translator = ProviderTextTranslator(engine, scope, retryAfterMillis = 1_000, clock = { now })
        val request = checkNotNull(on.request("大相撲", abema, "en"))
        assertNull(translator.translate(request))
        assertEquals(TranslationAvailability.DownloadFailed(setOf("ja")), translator.availability.value)
        assertNull(translator.cached(request))
        // Within the retry interval no new download starts.
        engine.prepareFails = false
        assertNull(translator.translate(request))
        assertEquals(1, engine.prepared.size)
        now = 1_000
        assertEquals("[ja>en] 大相撲", translator.translate(request))
        assertEquals(2, engine.prepared.size)
        assertEquals(TranslationAvailability.Ready, translator.availability.value)
    }

    @Test fun prepareDownloadsFixedLanguagesOnly() = runBlocking {
        val engine = FakeEngine()
        val translator = ProviderTextTranslator(engine, scope)
        assertEquals(TranslationAvailability.Idle, translator.availability.value)
        translator.prepare(listOf("ja", "ja", "en", "ru"), "en")
        while (translator.availability.value != TranslationAvailability.Ready) yield()
        assertEquals(listOf("ja" to "en"), engine.prepared)
    }

    @Test fun cacheIsBounded() = runBlocking {
        val engine = FakeEngine()
        val translator = ProviderTextTranslator(engine, scope, cacheEntries = 2)
        val requests = listOf("一", "二", "三").map { checkNotNull(on.request(it, abema, "en")) }
        requests.forEach { translator.translate(it) }
        assertNull(translator.cached(requests[0]))
        assertEquals("[ja>en] 三", translator.cached(requests[2]))
    }

    @Test fun availabilityText() {
        assertTrue(translationAvailabilityText(false, TranslationAvailability.Ready).startsWith("Off"))
        assertTrue(translationAvailabilityText(true, TranslationAvailability.Downloading(setOf("ja"))).contains("Japanese"))
        assertTrue(translationAvailabilityText(true, TranslationAvailability.DownloadFailed(setOf("ko"))).contains("unavailable"))
        assertTrue(translationAvailabilityText(true, TranslationAvailability.TargetUnsupported("xx")).contains("unavailable"))
    }
}
