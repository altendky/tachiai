package net.fstab.tachiai.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class FrameTextTranslationTest {
    private val abema = ProviderTextSource(PrototypeService.ABEMA, defaultProviderInstanceId(PrototypeService.ABEMA))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }

    private fun block(text: String, left: Int = 100, top: Int = 400, width: Int = 200, height: Int = 40, line: Int = 30) =
        FrameTextBlock(text, ViewerRect(left, top, width, height), line)

    // ---- Stability and hold ----

    @Test fun textBecomesStableOnlyAfterTwoConsecutiveMatchingSamples() {
        val stability = FrameTextStability()
        assertTrue(stability.update(listOf(block("序ノ口優勝")), 540).isEmpty())
        val held = stability.update(listOf(block("序ノ口優勝", left = 104, top = 402)), 540)
        assertEquals(listOf("序ノ口優勝"), held.map { it.text })
        assertEquals(ViewerRect(104, 402, 200, 40), held.single().box)
    }

    @Test fun changedTextMovedBoxesAndShortLinesNeverSettle() {
        val stability = FrameTextStability()
        stability.update(listOf(block("砂武蔵"), block("ticker", left = 0), block("small", top = 100, line = 10)), 540)
        val held = stability.update(listOf(block("砂武"), block("ticker", left = 300), block("small", top = 100, line = 10)), 540)
        assertTrue(held.isEmpty())
    }

    @Test fun textWithoutLettersIsSkippedAndWhitespaceIsNormalized() {
        val stability = FrameTextStability()
        stability.update(listOf(block("12 - 3"), block("  砂武蔵 \n\n 東  ")), 540)
        val held = stability.update(listOf(block("12 - 3"), block("砂武蔵\n東")), 540)
        assertEquals(listOf("砂武蔵\n東"), held.map { it.text })
    }

    @Test fun heldTextStaysThroughOneMissAndGoesAfterTwo() {
        val stability = FrameTextStability()
        stability.update(listOf(block("砂武蔵")), 540)
        val id = stability.update(listOf(block("砂武蔵")), 540).single().id
        assertEquals(id, stability.update(emptyList(), 540).single().id)
        assertEquals(id, stability.update(listOf(block("砂武蔵")), 540).single().id)
        assertEquals(1, stability.update(emptyList(), 540).size)
        assertTrue(stability.update(emptyList(), 540).isEmpty())
    }

    @Test fun changedTextReplacesTheHeldBlockAfterTwoSamples() {
        val stability = FrameTextStability()
        stability.update(listOf(block("東")), 540)
        val first = stability.update(listOf(block("東")), 540).single()
        assertEquals(listOf(first), stability.update(listOf(block("西")), 540))
        val next = stability.update(listOf(block("西")), 540)
        assertEquals(listOf("西"), next.map { it.text })
        assertNotEquals(first.id, next.single().id)
    }

    @Test fun heldBlocksAreBounded() {
        val stability = FrameTextStability(maxHeld = 2)
        val blocks = (0 until 4).map { block("札$it", top = it * 60) }
        stability.update(blocks, 540)
        assertEquals(2, stability.update(blocks, 540).size)
    }

    @Test fun sameBoxUsesOverlap() {
        val box = ViewerRect(0, 0, 100, 20)
        assertTrue(sameFrameTextBox(box, ViewerRect(5, 2, 100, 20)))
        assertFalse(sameFrameTextBox(box, ViewerRect(60, 0, 100, 20)))
        assertFalse(sameFrameTextBox(box, ViewerRect(0, 40, 100, 20)))
    }

    // ---- Unchanged frames ----

    @Test fun signatureSkipsSmallNoiseButSeesChanges() {
        val width = 64; val height = 36
        val gray = IntArray(width * height) { 0xFF808080.toInt() }
        val noisy = gray.copyOf().also { it[0] = 0xFF828282.toInt() }
        val changed = gray.copyOf().also { pixels -> for (y in 0 until 10) for (x in 0 until 20) pixels[y * width + x] = 0xFFFFFFFF.toInt() }
        val base = frameSignature(gray, width, height)
        assertFalse(frameUnchanged(null, base))
        assertTrue(frameUnchanged(base, frameSignature(noisy, width, height)))
        assertFalse(frameUnchanged(base, frameSignature(changed, width, height)))
    }

    @Test fun sampleSizeKeepsAspectWithinTheLongEdge() {
        assertEquals(960 to 540, frameTextSampleSize(1920, 1080))
        assertEquals(960 to 720, frameTextSampleSize(1440, 1080))
        assertEquals(640 to 360, frameTextSampleSize(640, 360))
        assertNull(frameTextSampleSize(0, 1080))
    }

    // ---- Box mapping ----

    @Test fun boxesMapFromTheSampleToTheVideoSurface() {
        // 960 × 540 sample over a 1920 × 1080 surface letterboxed 100 px down in its pane.
        assertEquals(ViewerRect(200, 900, 400, 80),
            mapFrameTextBox(ViewerRect(100, 400, 200, 40), 960, 540, ViewerRect(0, 100, 1920, 1080)))
        // Fractional scales round outward so the chip covers the source.
        assertEquals(ViewerRect(10, 3, 7, 3), mapFrameTextBox(ViewerRect(7, 2, 4, 2), 960, 540, ViewerRect(0, 0, 1440, 810)))
    }

    @Test fun chipsCenterOnTheSourceAndStayInsideTheOverlayInsets() {
        val clear = frameTextClearArea(1920, 1080, ViewerInsets(top = 120, right = 400, bottom = 200))
        assertEquals(ViewerRect(0, 120, 1520, 760), clear)
        // Wider than the source: centered over it.
        assertEquals(ViewerRect(150, 500, 300, 60), placeFrameTextChip(ViewerRect(200, 510, 200, 40), 300, 60, clear))
        // Under the bottom bar and beside the side sheet: moved into the clear area.
        assertEquals(ViewerRect(1320, 820, 200, 60), placeFrameTextChip(ViewerRect(1700, 1000, 200, 60), 200, 60, clear))
        // Above the top bar.
        assertEquals(120, placeFrameTextChip(ViewerRect(10, 0, 100, 40), 100, 40, clear)!!.top)
        // Larger than the clear area: clipped to it.
        assertEquals(clear, placeFrameTextChip(ViewerRect(0, 0, 4000, 4000), 4000, 4000, clear))
        assertNull(placeFrameTextChip(ViewerRect(0, 0, 10, 10), 10, 10, ViewerRect(0, 0, 0, 0)))
    }

    // ---- Enablement ----

    @Test fun providerDefaultAppliesUntilTheInStreamOverride() {
        val off = FrameTextFeed(abema, providerDefault = false, framesReadable = true)
        val on = off.copy(providerDefault = true)
        assertEquals(FrameTextRun.OFF, frameTextRun(off, null, mainPane = true, surfaceReadable = true))
        assertEquals(FrameTextRun.RUNNING, frameTextRun(on, null, mainPane = true, surfaceReadable = true))
        assertEquals(FrameTextRun.RUNNING, frameTextRun(off, true, mainPane = true, surfaceReadable = true))
        assertEquals(FrameTextRun.OFF, frameTextRun(on, false, mainPane = true, surfaceReadable = true))
        assertEquals(FrameTextRun.OFF, frameTextRun(null, true, mainPane = true, surfaceReadable = true))
    }

    @Test fun overrideIsTemporaryAndANewSessionUsesTheDefault() {
        val on = FrameTextFeed(abema, providerDefault = true, framesReadable = true)
        val session = FrameTextOverrides<String>()
        assertNull(session["A"])
        assertFalse(session.toggle("A", on))
        assertEquals(FrameTextRun.OFF, frameTextRun(on, session["A"], mainPane = true, surfaceReadable = true))
        assertTrue(session.toggle("A", on))
        assertNull(session["B"])
        session.clear()
        assertNull(session["A"])
        // Reopening: a fresh viewer session starts without overrides.
        assertNull(FrameTextOverrides<String>()["A"])
    }

    @Test fun theFloatingInsetFeedIsSkipped() {
        assertEquals("A", frameTextMainFeed(listOf("A", "B"), "A"))
        assertEquals("B", frameTextMainFeed(listOf("A", "B"), "B"))
        assertEquals("B", frameTextMainFeed(listOf("B"), "A"))
        assertNull(frameTextMainFeed(emptyList<String>(), "A"))
        val on = FrameTextFeed(abema, providerDefault = true, framesReadable = true)
        assertEquals(FrameTextRun.OFF, frameTextRun(on, true, mainPane = false, surfaceReadable = true))
    }

    @Test fun unreadableFramesAreUnavailableNotWorkedAround() {
        val secure = FrameTextFeed(abema, providerDefault = true, framesReadable = false)
        assertEquals(FrameTextRun.UNAVAILABLE, frameTextRun(secure, null, mainPane = true, surfaceReadable = true))
        val clear = secure.copy(framesReadable = true)
        assertEquals(FrameTextRun.UNAVAILABLE, frameTextRun(clear, null, mainPane = true, surfaceReadable = false))
        assertEquals(FrameTextRun.OFF, frameTextRun(secure, false, mainPane = true, surfaceReadable = true))
    }

    @Test fun providerDefaultIsPerInstanceAndOffByDefault() {
        val id = defaultProviderInstanceId(PrototypeService.ABEMA)
        val instance = ProviderInstance(id, PrototypeService.ABEMA, "ABEMA")
        val settings = ProviderTextTranslationSettings()
        assertFalse(settings.frameText(id))
        val on = settings.withFrameText(instance, true, setOf(id))
        assertTrue(on.frameText(id))
        assertFalse(on.frameText(defaultProviderInstanceId(PrototypeService.TWITCH)))
        assertFalse(on.withFrameText(instance, false, setOf(id)).frameText(id))
        assertFalse(on.withoutInstance(id).frameText(id))
        // Independent of Translate provider text, with the same language rules.
        assertNull(on.request("砂武蔵", abema, "en"))
        assertEquals(TranslationRequest("砂武蔵", ProviderSourceLanguage("ja"), "en"), on.languageRequest("砂武蔵", abema, "en"))
        assertNull(on.languageRequest("Sunamusashi", abema, "en"))
    }

    // ---- Pipeline and logging ----

    private class FakeSample(var blocks: List<FrameTextBlock>, var signature: ByteArray = byteArrayOf(1)) : FrameTextSample {
        var recognitions = 0
        override val width = 960
        override val height = 540
        override fun signature() = signature
        override fun recognize(): List<FrameTextBlock> { recognitions++; return blocks }
    }

    private class FakeTranslator(private val translations: Map<String, String>) : FrameTextTranslator {
        val cache = mutableMapOf<String, String>()
        val requests = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        override fun cached(text: String) = synchronized(cache) { cache[text] }
        override suspend fun translate(text: String): String? {
            synchronized(requests) { requests += text }
            gate?.await()
            return translations[text]?.also { synchronized(cache) { cache[text] = it } }
        }
    }

    @Test fun pipelineSkipsUnchangedFramesShowsStableTranslationsAndLogsOnlyNumbers() = runBlocking {
        val secret = "砂武蔵"; val other = "序ノ口優勝"; val translation = "Sunamusashi"
        val lines = mutableListOf<String>()
        val translator = FakeTranslator(mapOf(secret to translation))
        val gate = CompletableDeferred<Unit>().also { translator.gate = it }
        val ready = CompletableDeferred<Unit>()
        var now = 0L
        val pipeline = FrameTextPipeline(translator, scope, { synchronized(lines) { lines += it } }, { now }) { ready.complete(Unit) }
        val sample = FakeSample(listOf(block(secret), block(other, top = 100)))
        now = 10
        assertTrue(pipeline.process(sample, captureMs = 3, intervalMs = 0).isEmpty())
        // Unchanged frame: recognition is skipped, and the text counts as stable.
        assertTrue(pipeline.process(sample, captureMs = 4, intervalMs = 2_000).isEmpty())
        assertEquals(1, sample.recognitions)
        // Nothing shows until the translation is ready.
        gate.complete(Unit)
        withTimeout(5_000) { ready.await() }
        val chips = pipeline.chips()
        assertEquals(listOf(translation), chips.map { it.text })
        assertEquals(ViewerRect(100, 400, 200, 40), chips.single().box)
        // A held block is translated once; text kept as original is not retried while held.
        sample.signature = byteArrayOf(99)
        pipeline.process(sample, captureMs = 3, intervalMs = 2_000)
        withTimeout(5_000) { while (synchronized(lines) { lines.count { it.startsWith("translate") } } < 2) kotlinx.coroutines.delay(5) }
        pipeline.process(sample, captureMs = 3, intervalMs = 2_000)
        assertEquals(listOf(other, secret), synchronized(translator.requests) { translator.requests.sorted() })
        // The changed frame was recognized; the unchanged one after it was not.
        assertEquals(2, sample.recognitions)
        val logged = synchronized(lines) { lines.toList() }
        assertTrue(logged.isNotEmpty())
        val numeric = Regex("^(sample|translate)( [a-zA-Z]+=([0-9]+|[A-Z_]+))+$")
        logged.forEach { line ->
            assertTrue(line, numeric.matches(line))
            listOf(secret, other, translation).forEach { assertFalse(line.contains(it)) }
        }
        assertTrue(logged.any { it.contains("ocr=SKIPPED_UNCHANGED") })
        pipeline.clear()
        assertTrue(pipeline.chips().isEmpty())
    }

    @Test fun logFormattersCarryOnlyTimingsCountsAndStates() {
        assertEquals("sample intervalMs=2000 captureMs=4 ocrMs=120 blocks=3 stable=1 shown=1",
            frameTextSampleLog(FrameTextSampleTiming(2_000, 4, 120, 3, 1, 1)))
        assertEquals("sample intervalMs=2001 captureMs=5 ocr=SKIPPED_UNCHANGED blocks=3 stable=2 shown=0",
            frameTextSampleLog(FrameTextSampleTiming(2_001, 5, null, 3, 2, 0)))
        assertEquals("translate ms=80 result=TRANSLATED", frameTextTranslateLog(80, true))
        assertEquals("state=RUNNING sample=960x540", frameTextStateLog(FrameTextRun.RUNNING, 960, 540))
        assertEquals("state=OFF", frameTextStateLog(FrameTextRun.OFF))
    }
}
