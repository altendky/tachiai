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

    // ---- Reading above a name ----

    private fun held(id: Long, text: String, left: Int, top: Int, width: Int, height: Int, line: Int = height) =
        HeldFrameText(id, text, ViewerRect(left, top, width, height), line)

    private val reading = held(1, "おうほう", 100, 300, 120, 20)
    private val name = held(2, "王鵬", 95, 325, 130, 40)

    @Test fun aKanjiNameWithItsReadingDirectlyAboveShowsTheReadingOnce() {
        val other = held(3, "26歳", 100, 380, 80, 30)
        val place = held(4, "東京都", 400, 100, 120, 30)
        val cheer = held(5, "がんばれ", 600, 400, 160, 30)
        val plan = frameTextDisplays(listOf(reading, name, other, place, cheer), romanize = true)
        assertEquals(FrameTextDisplay.Literal("Ōhō"), plan[2])
        assertEquals(FrameTextDisplay.Hidden, plan[1])
        assertEquals(FrameTextDisplay.Translate, plan[3])
        assertEquals(FrameTextDisplay.Translate, plan[4])
        // Kana with no name below it is translated like any other text.
        assertEquals(FrameTextDisplay.Translate, plan[5])
    }

    @Test fun readingsThatAreNotDirectlyAboveDoNotPair() {
        val far = held(1, "おうほう", 100, 200, 120, 20)
        val beside = held(1, "おうほう", 300, 300, 120, 20)
        val below = held(1, "おうほう", 100, 370, 120, 20)
        val offset = held(1, "おうほう", 190, 300, 120, 20)
        listOf(far, beside, below, offset).forEach { candidate ->
            val plan = frameTextDisplays(listOf(candidate, name), romanize = true)
            assertEquals(FrameTextDisplay.Translate, plan[2])
            assertEquals(FrameTextDisplay.Translate, plan[1])
        }
        // A long kanji run is not treated as a name.
        val long = held(2, "一二三四五六七", 95, 325, 130, 40)
        assertEquals(FrameTextDisplay.Translate, frameTextDisplays(listOf(reading, long), romanize = true)[2])
    }

    @Test fun oneBlockWithAReadingLineOverANameLineShowsTheReading() {
        val merged = held(1, "はくのふじ\n伯乃富士", 100, 300, 160, 70, line = 30)
        assertEquals(FrameTextDisplay.Literal("Hakunofuji"), frameTextDisplays(listOf(merged), romanize = true)[1])
    }

    @Test fun aJapaneseDeviceKeepsTranslatingEverything() {
        val plan = frameTextDisplays(listOf(reading, name), romanize = false)
        assertEquals(listOf(FrameTextDisplay.Translate, FrameTextDisplay.Translate), listOf(plan[1], plan[2]))
    }

    @Test fun pipelineShowsReadingsWithoutTranslatingThemAndLogsOnlyCounts() {
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
            override fun recognize() = listOf(
                FrameTextBlock("おうほう", ViewerRect(100, 300, 120, 20), 20),
                FrameTextBlock("王鵬", ViewerRect(95, 325, 130, 40), 40))
        }
        pipeline.process(sample, 1, 0)
        val chips = pipeline.process(sample, 1, 2_000)
        assertEquals(listOf(FrameTextChip(chips.single().id, ViewerRect(95, 325, 130, 40), 40, "Ōhō")), chips)
        assertTrue(synchronized(requests) { requests.isEmpty() })
        val logged = synchronized(lines) { lines.toList() }
        assertTrue(logged.last().endsWith("shown=1 romanized=1"))
        logged.forEach { line -> listOf("おうほう", "王鵬", "Ōhō").forEach { assertFalse(line.contains(it)) } }
    }
}
