package net.fstab.tachiai.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class JapaneseReadingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }

    // ---- Transliteration ----

    @Test fun namesUseHepburnWithMacronsAndACapital() {
        assertEquals("Ōhō", romanizeKana("おうほう"))
        assertEquals("Hakunofuji", romanizeKana("はくのふじ"))
        assertEquals("Tanaka Tarō", romanizeKana("たなか たろう"))
        assertEquals("Yamada Hanako", romanizeKana("ヤマダ・ハナコ"))
    }

    @Test fun longVowelsTakeMacrons() {
        assertEquals("Tōkyō", romanizeKana("とうきょう"))
        assertEquals("Ōno", romanizeKana("おおの"))
        assertEquals("Sūgaku", romanizeKana("すうがく"))
        assertEquals("Okāsan", romanizeKana("おかあさん"))
        // ii and ei are written out.
        assertEquals("Oniisan", romanizeKana("おにいさん"))
        assertEquals("Sensei", romanizeKana("せんせい"))
    }

    @Test fun katakanaLongMarkBecomesAMacron() {
        assertEquals("Rāmen", romanizeKana("ラーメン"))
        assertEquals("Sūpā", romanizeKana("スーパー"))
        assertEquals("Tī", romanizeKana("ティー"))
    }

    @Test fun smallKanaCombine() {
        assertEquals("Kyūshū", romanizeKana("きゅうしゅう"))
        assertEquals("Chūjō", romanizeKana("ちゅうじょう"))
        assertEquals("Nyanko", romanizeKana("にゃんこ"))
        assertEquals("Fan", romanizeKana("ファン"))
        assertEquals("Windō", romanizeKana("ウィンドウ"))
        assertEquals("Chekku", romanizeKana("チェック"))
    }

    @Test fun smallTsuDoublesTheNextConsonant() {
        assertEquals("Hokkaidō", romanizeKana("ほっかいどう"))
        assertEquals("Shusshin", romanizeKana("しゅっしん"))
        assertEquals("Matcha", romanizeKana("まっちゃ"))
        assertEquals("Ippon", romanizeKana("いっぽん"))
        assertEquals("A", romanizeKana("あっ"))
    }

    @Test fun nBeforeAVowelOrYIsMarked() {
        assertEquals("Shin'ichi", romanizeKana("しんいち"))
        assertEquals("Kon'ya", romanizeKana("こんや"))
        assertEquals("Sanpo", romanizeKana("さんぽ"))
        assertEquals("Hon", romanizeKana("ほん"))
    }

    @Test fun classification() {
        assertTrue(kanaOnly("おうほう")); assertTrue(kanaOnly("カー")); assertTrue(kanaOnly("たなか たろう"))
        assertFalse(kanaOnly("王鵬")); assertFalse(kanaOnly("26歳")); assertFalse(kanaOnly("ー")); assertFalse(kanaOnly("abc"))
        assertTrue(shortKanjiOnly("王鵬")); assertTrue(shortKanjiOnly("伯乃富士")); assertTrue(shortKanjiOnly("東京都"))
        assertFalse(shortKanjiOnly("序ノ口")); assertFalse(shortKanjiOnly("6回")); assertFalse(shortKanjiOnly("一二三四五六七"))
    }

    // ---- Pairing a reading with the name below it ----

    private fun line(text: String, left: Int, top: Int, width: Int, height: Int, elements: List<FrameTextElement> = emptyList()) =
        FrameTextLine(text, ViewerRect(left, top, width, height), elements)

    private fun block(vararg lines: FrameTextLine): FrameTextBlock {
        val left = lines.minOf { it.box.left }; val top = lines.minOf { it.box.top }
        val box = ViewerRect(left, top, lines.maxOf { it.box.left + it.box.width } - left, lines.maxOf { it.box.top + it.box.height } - top)
        return FrameTextBlock(lines.joinToString("\n") { it.text }, box, lines.sumOf { it.box.height } / lines.size, lines.toList())
    }

    // A sumo-style card: rank, a small reading over the name, and the record, in one recognized block.
    private val rank = line("前頭3", 100, 80, 60, 20)
    private val reading = line("たかやす", 120, 100, 80, 12)
    private val name = line("髙安", 110, 114, 100, 36)
    private val record = line("○3 ●11", 110, 155, 100, 18)
    private val card = block(rank, reading, name, record)

    @Test fun aSmallReadingOverANameInOneBlockPairsAndTheRestIsSplit() {
        val result = FrameTextNames().pair(listOf(card), romanize = true, frameWidth = 960, frameHeight = 540)
        val named = result.blocks.single { it.reading != null }
        assertEquals("髙安", named.text)
        assertEquals("Takayasu", named.reading)
        assertEquals(name.box, named.box)
        // The reading gets no block of its own; the rank and record are translated separately.
        assertEquals(setOf("前頭3", "○3 ●11"), result.blocks.filter { it.reading == null }.map { it.text }.toSet())
        with(result.stats) {
            assertEquals(1, paired); assertEquals(1, sameBlock); assertEquals(1, kanaOnly); assertEquals(1, shortKanji)
            assertEquals(12, readingHeightPx); assertEquals(36, nameHeightPx)
        }
        // The reading (12 px) is under the chip minimum height (16 px at 540) yet still paired.
        assertTrue(12 < 540 * FrameTextTuning.MIN_LINE_HEIGHT_FRACTION)
    }

    @Test fun letterSpacedReadingsPairWithTheirOwnNames() {
        val spaced = "ふじせいうん".mapIndexed { index, c -> block(line(c.toString(), 300 + index * 22, 100, 14, 12)) }
        val right = block(line("藤青雲", 300, 114, 130, 36))
        val oneLine = block(line("ふ じ せ い う ん", 520, 100, 120, 12), line("藤青雲", 515, 114, 130, 36))
        val result = FrameTextNames().pair(listOf(card) + spaced + right + oneLine, true, 960, 540)
        assertEquals(listOf("Takayasu", "Fujiseiun", "Fujiseiun"), result.blocks.mapNotNull { it.reading })
        assertTrue(result.blocks.none { kanaOnly(it.text) })
    }

    @Test fun aNameBesideOtherTextInALinePairsByElements() {
        val elements = listOf(FrameTextElement("前頭2", ViewerRect(100, 114, 60, 36)),
            FrameTextElement("藤", ViewerRect(170, 114, 36, 36)), FrameTextElement("青", ViewerRect(208, 114, 36, 36)),
            FrameTextElement("雲", ViewerRect(246, 114, 36, 36)))
        val mixed = block(line("ふじせいうん", 175, 100, 100, 12), line("前頭2 藤青雲", 100, 114, 182, 36, elements))
        val result = FrameTextNames().pair(listOf(mixed), true, 960, 540)
        val named = result.blocks.single { it.reading != null }
        assertEquals("藤青雲", named.text); assertEquals("Fujiseiun", named.reading)
        assertEquals(ViewerRect(170, 114, 112, 36), named.box)
        assertEquals(listOf("前頭2"), result.blocks.filter { it.reading == null }.map { it.text })
    }

    @Test fun textWithoutAReadingAboveIsLeftAsItIs() {
        val place = block(line("東京都", 400, 300, 120, 30))
        val far = block(line("たかやす", 120, 20, 80, 12), line("髙安", 110, 114, 100, 36))
        val beside = block(line("たかやす", 300, 100, 80, 12), line("髙安", 110, 114, 100, 36))
        val below = block(line("髙安", 110, 114, 100, 36), line("たかやす", 120, 152, 80, 12))
        val cheer = block(line("がんばれ", 600, 400, 160, 30))
        listOf(place, far, beside, below, cheer).forEach { input ->
            val result = FrameTextNames().pair(listOf(input), true, 960, 540)
            assertSame(input, result.blocks.single())
        }
        // A Japanese device language romanizes nothing.
        assertSame(card, FrameTextNames().pair(listOf(card), false, 960, 540).blocks.single())
    }

    @Test fun aBlockWithoutLineBoxesHoldingAReadingLineOverANameLinePairs() {
        val merged = FrameTextBlock("はくのふじ\n伯乃富士", ViewerRect(100, 300, 160, 70), 30)
        assertEquals("Hakunofuji", FrameTextNames().pair(listOf(merged), true, 960, 540).blocks.single().reading)
    }

    @Test fun aPairingIsRememberedWhileTheNameStays() {
        val names = FrameTextNames()
        names.pair(listOf(card), true, 960, 540)
        // The small reading is missed in the next sample.
        val result = names.pair(listOf(block(rank, name, record)), true, 960, 540)
        assertEquals("Takayasu", result.blocks.single { it.reading != null }.reading)
        assertEquals(1, result.stats.remembered)
        // Gone for longer than the allowed misses: forgotten.
        repeat(FrameTextNameTuning.MAX_MISSES + 1) { names.pair(emptyList(), true, 960, 540) }
        assertTrue(names.pair(listOf(block(rank, name, record)), true, 960, 540).blocks.none { it.reading != null })
    }

    @Test fun anUnreadableReadingIsReadFromAHigherResolutionCropOnceTheNameHoldsStill() {
        val names = FrameTextNames()
        val alone = listOf(block(rank, name, record))
        val regions = mutableListOf<ViewerRect>()
        val detail: (ViewerRect) -> List<FrameTextLine>? = { region -> regions += region; listOf(reading) }
        // First sight: not yet steady, so no crop and no higher-resolution request.
        assertTrue(names.pair(alone, true, 960, 540, detail).blocks.none { it.reading != null })
        assertFalse(names.wantsDetail)
        // Steady but no higher-resolution frame this sample: ask for one.
        names.pair(alone, true, 960, 540, detail = null)
        assertTrue(names.wantsDetail)
        val result = names.pair(alone, true, 960, 540, detail)
        assertEquals("Takayasu", result.blocks.single { it.reading != null }.reading)
        assertEquals(1, result.stats.detail); assertEquals(1, result.stats.detailFound)
        assertTrue(regions.single().let { it.top < name.box.top && it.top + it.height > name.box.top })
        assertFalse(names.wantsDetail)
    }

    @Test fun aCropWithoutAReadingIsNotRetriedWhileTheNameStays() {
        val names = FrameTextNames()
        val place = listOf(block(line("東京都", 400, 300, 120, 30)))
        var calls = 0
        val detail: (ViewerRect) -> List<FrameTextLine>? = { calls++; emptyList() }
        repeat(5) { names.pair(place, true, 960, 540, detail) }
        assertEquals(1, calls)
        assertFalse(names.wantsDetail)
        // Moving text never asks for a crop.
        repeat(5) { step -> names.pair(listOf(block(line("大雨", 100 + step * 120, 400, 60, 30))), true, 960, 540, detail) }
        assertEquals(1, calls)
    }

    @Test fun pairingDiagnosticsCarryOnlyCountsAndGeometry() {
        val stats = FrameTextNames().pair(listOf(card), true, 960, 540).stats.copy(filteredSmall = 2)
        val line = frameTextPairingLog(stats)
        assertTrue(line, Regex("^pairing( [a-zA-Z]+=(-?[0-9]+(\\.[0-9]+)?|[A-Z_]+))+$").matches(line))
        assertTrue(line.contains("bestOverlap=1.00") && line.contains("readingHeightPx=12") && line.contains("filteredSmall=2"))
        listOf("たかやす", "髙安", "Takayasu", "前頭").forEach { assertFalse(line.contains(it)) }
    }

    @Test fun pipelineShowsTheReadingOverTheNameWithoutTranslatingEither() {
        val lines = mutableListOf<String>()
        val requests = mutableListOf<String>()
        val translator = object : FrameTextTranslator {
            override fun cached(text: String): String? = null
            override suspend fun translate(text: String): String? { synchronized(requests) { requests += text }; return null }
        }
        val pipeline = FrameTextPipeline(translator, scope, { synchronized(lines) { lines += it } }, { 0L }) {}
        val sample = object : FrameTextSample {
            override val width = 960
            override val height = 540
            override fun signature() = byteArrayOf(1)
            override fun recognize() = listOf(card)
        }
        pipeline.process(sample, 1, 0)
        val chips = pipeline.process(sample, 1, 2_000)
        assertEquals("Takayasu", chips.single { it.box == name.box }.text)
        Thread.sleep(200)
        val asked = synchronized(requests) { requests.toSet() }
        assertFalse(asked.any { "髙安" in it || "たかやす" in it })
        val logged = synchronized(lines) { lines.toList() }
        assertTrue(logged.any { it.startsWith("pairing ") })
        logged.forEach { line -> listOf("たかやす", "髙安", "Takayasu").forEach { assertFalse(line.contains(it)) } }
    }
}
