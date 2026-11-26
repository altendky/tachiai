package net.fstab.tachiai.presentation

import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
        val numeric = Regex("^(sample|translate|pairing)( [a-zA-Z]+=(-?[0-9]+(\\.[0-9]+)?|[A-Z_]+))+$")
        logged.forEach { line ->
            assertTrue(line, numeric.matches(line))
            listOf(secret, other, translation).forEach { assertFalse(line.contains(it)) }
        }
        assertTrue(logged.any { it.contains("ocr=SKIPPED_UNCHANGED") })
        pipeline.clear()
        assertTrue(pipeline.chips().isEmpty())
    }

    @Test fun logFormattersCarryOnlyTimingsCountsAndStates() {
        assertEquals("sample intervalMs=2000 captureMs=4 ocrMs=120 blocks=3 stable=1 shown=1 romanized=1",
            frameTextSampleLog(FrameTextSampleTiming(2_000, 4, 120, 3, 1, 1, 1)))
        assertEquals("sample intervalMs=2001 captureMs=5 ocr=SKIPPED_UNCHANGED blocks=3 stable=2 shown=0 romanized=0",
            frameTextSampleLog(FrameTextSampleTiming(2_001, 5, null, 3, 2, 0)))
        assertEquals("translate ms=80 result=TRANSLATED", frameTextTranslateLog(80, true))
        assertEquals("state=RUNNING sample=960x540", frameTextStateLog(FrameTextRun.RUNNING, 960, 540))
        assertEquals("state=OFF", frameTextStateLog(FrameTextRun.OFF))
        assertEquals("reset cause=DISCONTINUITY", frameTextResetLog(FrameTextResetCause.DISCONTINUITY))
    }

    // ---- Overlapping chips ----

    @Test fun aNewStableBlockReplacesTheHeldBlockItOverlaps() {
        val stability = FrameTextStability()
        val old = block("And 'double payment' is a sanctuary?", left = 100, top = 400, width = 700, height = 40)
        stability.update(listOf(old), 540)
        val oldId = stability.update(listOf(old), 540).single().id
        // The new caption sits over the old one's area while the old is still read.
        val new = block("Keio University", left = 300, top = 410, width = 300, height = 40)
        assertEquals(listOf(oldId), stability.update(listOf(old, new), 540).map { it.id })
        val held = stability.update(listOf(old, new), 540)
        assertEquals(listOf("Keio University"), held.map { it.text })
        // The replaced text does not come back while both are still read.
        assertEquals(listOf("Keio University"), stability.update(listOf(old, new), 540).map { it.text })
        assertEquals(listOf("Keio University"), stability.update(listOf(old, new), 540).map { it.text })
        // Blocks that do not overlap are held side by side.
        val side = block("Waseda University", left = 100, top = 100, width = 300, height = 40)
        stability.update(listOf(new, side), 540)
        assertEquals(setOf("Keio University", "Waseda University"), stability.update(listOf(new, side), 540).map { it.text }.toSet())
    }

    @Test fun aHeldRomanizedReadingStaysWithItsName() {
        val stability = FrameTextStability()
        val name = FrameTextBlock("髙安", ViewerRect(110, 114, 100, 36), 36, reading = "Takayasu")
        stability.update(listOf(name), 540)
        assertEquals("Takayasu", stability.update(listOf(name), 540).single().literal)
        // A later sample without the reading keeps the held literal.
        assertEquals("Takayasu", stability.update(listOf(name.copy(reading = null)), 540).single().literal)
        // Small lines are counted for diagnostics.
        stability.update(listOf(block("小", line = 10)), 540)
        assertEquals(1, stability.filteredSmall)
    }

    @Test fun overlapIsMeasuredAgainstTheSmallerBox() {
        val wide = ViewerRect(0, 0, 1000, 50)
        assertTrue(frameTextBoxesOverlap(wide, ViewerRect(200, 10, 100, 30)))
        assertTrue(frameTextBoxesOverlap(wide, ViewerRect(200, 30, 100, 40)))
        assertFalse(frameTextBoxesOverlap(wide, ViewerRect(200, 45, 100, 40)))
        assertFalse(frameTextBoxesOverlap(wide, ViewerRect(0, 60, 1000, 50)))
    }

    // ---- Position discontinuities ----

    @Test fun seeksSkipsAndItemChangesReset() {
        listOf(Player.DISCONTINUITY_REASON_SEEK, Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT,
            Player.DISCONTINUITY_REASON_SKIP).forEach { reason ->
            listOf(true, false).forEach { assertEquals(FrameTextDiscontinuityAction.RESET, frameTextDiscontinuityAction(reason, it)) }
        }
        // A transition or removal into another item resets.
        listOf(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, Player.DISCONTINUITY_REASON_REMOVE).forEach {
            assertEquals(FrameTextDiscontinuityAction.RESET, frameTextDiscontinuityAction(it, sameItem = false))
        }
        // Within the item, playback moving on restarts the jump check and a removal is checked.
        assertEquals(FrameTextDiscontinuityAction.REBASE,
            frameTextDiscontinuityAction(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, sameItem = true))
        assertEquals(FrameTextDiscontinuityAction.CHECK_POSITION,
            frameTextDiscontinuityAction(Player.DISCONTINUITY_REASON_REMOVE, sameItem = true))
        // A seek resets whatever the position check found, even with nothing measured yet.
        assertTrue(frameTextDiscontinuityResets(FrameTextDiscontinuityAction.RESET, null))
        assertTrue(frameTextDiscontinuityResets(FrameTextDiscontinuityAction.RESET,
            frameTextPositionCheck(60_000, 1_000, 62_000, 3_000, wasPlaying = true, playing = true)))
        listOf(Player.DISCONTINUITY_REASON_INTERNAL, Player.DISCONTINUITY_REASON_SILENCE_SKIP).forEach {
            assertEquals(FrameTextDiscontinuityAction.IGNORE, frameTextDiscontinuityAction(it, sameItem = true))
        }
    }

    @Test fun playingAcrossALivePeriodBoundaryDoesNotReset() {
        // An ad period [0 s, 15 s) then a programme period, both in the live window.
        val periods = listOf(0L to 15_000L, 15_000L to 600_000L)
        val before = LiveTimeline(periods, offsetInFirstPeriodMs = 5_000)
        // 13 s into the ad, then 2 s later 0.2 s into the programme while the window slid 2 s.
        val previous = frameTextStreamPositionMs(before, 0, 13_000 - 5_000)
        val after = LiveTimeline(periods, offsetInFirstPeriodMs = 7_000)
        val position = frameTextStreamPositionMs(after, 0, 15_200 - 7_000)
        assertEquals(AVAILABILITY_START_MS + 13_000, previous)
        assertEquals(AVAILABILITY_START_MS + 15_200, position)
        val action = frameTextDiscontinuityAction(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, sameItem = true)
        assertFalse(frameTextDiscontinuityResets(action,
            frameTextPositionCheck(previous, 1_000, position, 3_000, wasPlaying = true, playing = true)))
        assertFalse(frameTextPositionJumped(previous, 1_000, position, 3_000, playing = true))
        // Seeking back 30 s in the same window is still a jump.
        assertTrue(frameTextPositionJumped(position, 3_000, position - 30_000, 5_000, playing = true))
    }

    @Test fun aPeriodLeavingTheWindowWhilePlayingContinuouslyDoesNotReset() {
        // The window starts 0.5 s before the programme, which plays 20 s in.
        val before = LiveTimeline(listOf(0L to 15_000L, 15_000L to 600_000L), offsetInFirstPeriodMs = 14_500)
        val previous = frameTextStreamPositionMs(before, 0, 35_000 - 14_500)
        // 2 s later the ad period has left the window: the programme is first and the window starts 1.5 s into it.
        val after = LiveTimeline(listOf(15_000L to 600_000L), offsetInFirstPeriodMs = 1_500)
        val position = frameTextStreamPositionMs(after, 0, 37_000 - 15_000 - 1_500)
        assertEquals(AVAILABILITY_START_MS + 37_000, position)
        assertEquals(FrameTextDiscontinuityAction.CHECK_POSITION,
            frameTextDiscontinuityAction(Player.DISCONTINUITY_REASON_REMOVE, sameItem = true))
        assertFalse(frameTextPositionJumped(previous, 1_000, position, 3_000, playing = true))
        // A removal that moves playback (fallen behind the window) still resets.
        val moved = frameTextStreamPositionMs(after, 0, 60_000 - 15_000 - 1_500)
        assertTrue(frameTextPositionJumped(previous, 1_000, moved, 3_000, playing = true))
    }

    @Test fun anAdBoundaryWhoseStreamClockDoesNotLineUpDoesNotReset() {
        // The programme's declared end and the ad's declared start leave an 8 s gap on the stream clock.
        val timeline = LiveTimeline(listOf(0L to 60_000L, 68_000L to 30_000L), offsetInFirstPeriodMs = 10_000)
        val previous = frameTextStreamPositionMs(timeline, 0, 59_000 - 10_000)
        // 1 s later playback moves on into the ad, at its start.
        val position = frameTextStreamPositionMs(timeline, 0, 68_000 - 10_000)
        val check = frameTextPositionCheck(previous, 1_000, position, 2_000, wasPlaying = true, playing = true)
        assertEquals(9_000, check.movedMs)
        assertEquals(1_000, check.expectedMs)
        assertEquals(8_000, check.deltaMs)
        assertTrue(check.jumped)
        val transition = frameTextDiscontinuityAction(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, sameItem = true)
        assertFalse(frameTextDiscontinuityResets(transition, check))
        // The check restarts from the ad's start, so the next sample 2.2 s later is no jump.
        val next = frameTextStreamPositionMs(timeline, 0, 70_200 - 10_000)
        assertFalse(frameTextPositionJumped(position, 2_000, next, 4_200, playing = true))
        // The same offset after the current period was removed is a jump.
        val removal = frameTextDiscontinuityAction(Player.DISCONTINUITY_REASON_REMOVE, sameItem = true)
        assertTrue(frameTextDiscontinuityResets(removal, check))
    }

    @Test fun playbackStartingOrStoppingBetweenMeasurementsIsNoJump() {
        // Buffering at a boundary 3.5 s after the last sample, having played up to it.
        val buffering = frameTextPositionCheck(60_000, 1_000, 63_500, 4_500, wasPlaying = true, playing = false)
        assertEquals(3_500, buffering.expectedMs)
        assertFalse(buffering.jumped)
        // Resuming after buffering, having played for part of the interval.
        assertFalse(frameTextPositionCheck(60_000, 1_000, 61_000, 4_500, wasPlaying = false, playing = true).jumped)
        // Moving back, or further than the elapsed time explains, is still a jump.
        assertTrue(frameTextPositionCheck(60_000, 1_000, 56_000, 4_500, wasPlaying = true, playing = false).jumped)
        assertTrue(frameTextPositionCheck(60_000, 1_000, 70_000, 4_500, wasPlaying = false, playing = true).jumped)
    }

    @Test fun discontinuityDiagnosticsAreNumbersAndClosedNames() {
        val check = frameTextPositionCheck(1_000_059_000, 1_000, 1_000_068_000, 2_000, wasPlaying = true, playing = true)
        assertEquals("discontinuity reason=AUTO_TRANSITION from=1 to=2 of=3 sameItem=true windowStart=KNOWN " +
            "windowJumpMs=9000 previousMs=1000059000 streamMs=1000068000 elapsedMs=1000 movedMs=9000 expectedMs=1000 " +
            "deltaMs=8000 action=REBASE reset=false",
            frameTextDiscontinuityLog(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, 1, 2, 3, sameItem = true,
                windowStartKnown = true, windowJumpMs = 9_000, check = check, action = FrameTextDiscontinuityAction.REBASE,
                reset = false))
        assertEquals("discontinuity reason=SEEK from=0 to=0 of=1 sameItem=true windowStart=UNKNOWN windowJumpMs=-30000 " +
            "previousMs=UNKNOWN streamMs=UNKNOWN elapsedMs=UNKNOWN movedMs=UNKNOWN expectedMs=UNKNOWN deltaMs=UNKNOWN " +
            "action=RESET reset=true",
            frameTextDiscontinuityLog(Player.DISCONTINUITY_REASON_SEEK, 0, 0, 1, sameItem = true, windowStartKnown = false,
                windowJumpMs = -30_000, check = null, action = FrameTextDiscontinuityAction.RESET, reset = true))
        assertEquals("positionJump windowStart=KNOWN previousMs=1000059000 streamMs=1000068000 elapsedMs=1000 " +
            "movedMs=9000 expectedMs=1000 deltaMs=8000", frameTextPositionJumpLog(check, windowStartKnown = true))
        assertTrue(frameTextWindowStartKnown(LiveTimeline(listOf(0L to 15_000L), offsetInFirstPeriodMs = 0), 0))
        assertFalse(frameTextWindowStartKnown(LiveTimeline(listOf(0L to 15_000L), offsetInFirstPeriodMs = 0,
            availabilityStartMs = C.TIME_UNSET), 0))
        assertFalse(frameTextWindowStartKnown(Timeline.EMPTY, 0))
    }

    @Test fun withoutAWindowStartTimeTheStreamPositionIsFromTheFirstPeriod() {
        val replay = LiveTimeline(listOf(0L to 15_000L, 15_000L to 600_000L), offsetInFirstPeriodMs = 0,
            availabilityStartMs = C.TIME_UNSET)
        assertEquals(14_000, frameTextStreamPositionMs(replay, 0, 14_000))
        assertEquals(16_000, frameTextStreamPositionMs(replay, 0, 16_000))
        assertEquals(16_000, frameTextStreamPositionMs(Timeline.EMPTY, 0, 16_000))
    }

    @Test fun positionJumpsBeyondPlaybackAreDetected() {
        // Playing for 2.2 s moves about 2.2 s: no jump, even with a slight rate change.
        assertFalse(frameTextPositionJumped(60_000, 1_000, 62_200, 3_200, playing = true))
        assertFalse(frameTextPositionJumped(60_000, 1_000, 61_000, 3_200, playing = true))
        // Skip back a minute, or resume at a new position.
        assertTrue(frameTextPositionJumped(60_000, 1_000, 2_200, 3_200, playing = true))
        assertTrue(frameTextPositionJumped(60_000, 1_000, 90_000, 3_200, playing = true))
        // Paused or just paused: staying put or moving under one interval is no jump.
        assertFalse(frameTextPositionJumped(60_000, 1_000, 60_000, 3_200, playing = false))
        assertFalse(frameTextPositionJumped(60_000, 1_000, 62_200, 3_200, playing = false))
        assertTrue(frameTextPositionJumped(60_000, 1_000, 120_000, 3_200, playing = false))
    }

    @Test fun aResetDropsHeldTextAndTextMustSettleAgain() {
        val lines = mutableListOf<String>()
        val translator = FakeTranslator(emptyMap()).also { it.cache["Caption"] = "Caption EN" }
        val pipeline = FrameTextPipeline(translator, scope, { synchronized(lines) { lines += it } }, { 0L }) {}
        val sample = FakeSample(listOf(block("Caption")))
        pipeline.process(sample, 1, 0)
        assertEquals(listOf("Caption EN"), pipeline.process(sample, 1, 2_000).map { it.text })
        pipeline.clear()
        assertTrue(pipeline.chips().isEmpty())
        // The same picture after the jump is recognized again and needs two samples.
        assertTrue(pipeline.process(sample, 1, 2_000).isEmpty())
        assertEquals(2, sample.recognitions)
        assertEquals(listOf("Caption EN"), pipeline.process(sample, 1, 2_000).map { it.text })
    }

    /**
     * A live window over consecutive periods (start, duration in ms), shaped
     * like Media3's DASH timeline: the window starts [offsetInFirstPeriodMs]
     * into the first period and its start time is the availability start plus
     * that period's start and the offset.
     */
    private class LiveTimeline(
        private val periods: List<Pair<Long, Long>>,
        private val offsetInFirstPeriodMs: Long,
        private val availabilityStartMs: Long = AVAILABILITY_START_MS,
    ) : Timeline() {
        override fun getWindowCount() = 1
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
            val firstStartMs = periods.first().first
            val endMs = periods.last().let { it.first + it.second }
            return window.set(Window.SINGLE_WINDOW_UID, null, null, availabilityStartMs,
                if (availabilityStartMs == C.TIME_UNSET) C.TIME_UNSET else availabilityStartMs + firstStartMs + offsetInFirstPeriodMs,
                C.TIME_UNSET, true, availabilityStartMs != C.TIME_UNSET, null, 0,
                (endMs - firstStartMs - offsetInFirstPeriodMs) * 1_000, 0, periods.size - 1, offsetInFirstPeriodMs * 1_000)
        }
        override fun getPeriodCount() = periods.size
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            val (startMs, durationMs) = periods[periodIndex]
            return period.set(startMs, startMs, 0, durationMs * 1_000,
                (startMs - periods.first().first - offsetInFirstPeriodMs) * 1_000, AdPlaybackState.NONE, false)
        }
        override fun getIndexOfPeriod(uid: Any) = periods.indexOfFirst { it.first == uid }
        override fun getUidOfPeriod(periodIndex: Int): Any = periods[periodIndex].first
    }

    private companion object {
        const val AVAILABILITY_START_MS = 1_790_000_000_000L
    }
}
