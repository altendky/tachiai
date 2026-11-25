package net.fstab.tachiai.presentation

import kotlin.math.max
import kotlin.math.min

// Readings of Japanese text in the video (#664, user decision 2026-11-25).
// The offline translator mistranslates proper names (王鵬 → "Rang"), while
// broadcasts commonly print a name's kana reading (furigana) directly above
// it. A short kanji-only block with a kana-only block directly above it
// therefore shows that reading romanized (Hepburn, long vowels with macrons)
// instead of a translation. This follows the general reading-above-name
// convention, not any one program. Other kana is translated as usual.

private const val LONG_MARK = 'ー'
private const val MIDDLE_DOT = '・'
internal const val MAX_NAME_KANJI = 6

private fun hiragana(c: Char): Boolean = c in 'ぁ'..'ゖ'
private fun katakana(c: Char): Boolean = c in 'ァ'..'ヺ'
private fun kana(c: Char): Boolean = hiragana(c) || katakana(c)
private fun kanji(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

/** Hiragana, katakana, the long-vowel mark, the middle dot and spaces only, with at least one kana. */
internal fun kanaOnly(text: String): Boolean =
    text.any(::kana) && text.all { kana(it) || it == LONG_MARK || it == MIDDLE_DOT || it.isWhitespace() }

/** A short run of kanji (no kana, Latin or digits), as a name usually is. */
internal fun shortKanjiOnly(text: String, maxLength: Int = MAX_NAME_KANJI): Boolean {
    val letters = text.filterNot(Char::isWhitespace)
    return letters.length in 1..maxLength && letters.all(::kanji)
}

private val SYLLABLES: Map<Char, String> = buildMap {
    fun row(kana: String, romaji: String) = kana.toList().zip(romaji.split(' ')).forEach { (k, r) -> put(k, r) }
    row("あいうえお", "a i u e o")
    row("かきくけこ", "ka ki ku ke ko"); row("がぎぐげご", "ga gi gu ge go")
    row("さしすせそ", "sa shi su se so"); row("ざじずぜぞ", "za ji zu ze zo")
    row("たちつてと", "ta chi tsu te to"); row("だぢづでど", "da ji zu de do")
    row("なにぬねの", "na ni nu ne no")
    row("はひふへほ", "ha hi fu he ho"); row("ばびぶべぼ", "aiueo".map { "b$it" }.joinToString(" ")); row("ぱぴぷぺぽ", "pa pi pu pe po")
    row("まみむめも", "ma mi mu me mo")
    row("やゆよ", "ya yu yo")
    row("らりるれろ", "ra ri ru re ro")
    row("わゐゑを", "wa i e o")
    row("ゔゕゖ", "vu ka ke")
    // Small kana standing alone.
    row("ぁぃぅぇぉゃゅょゎ", "a i u e o ya yu yo wa")
}
private const val SMALL_Y = "ゃゅょ"
private const val SMALL_VOWELS = "ぁぃぅぇぉ"
private const val VOWELS = "aeiou"
private val MACRONS = mapOf('a' to 'ā', 'e' to 'ē', 'i' to 'ī', 'o' to 'ō', 'u' to 'ū')

private fun toHiragana(c: Char): Char = if (c in 'ァ'..'ヶ') c - 0x60 else when (c) {
    'ヷ' -> 'わ'; 'ヸ' -> 'ゐ'; 'ヹ' -> 'ゑ'; 'ヺ' -> 'を'
    else -> c
}

/** One word of kana as Hepburn romaji, lower case. */
private fun romanizeWord(word: String): String {
    val syllables = mutableListOf<String>()
    var geminate = false
    var index = 0
    val chars = word.map(::toHiragana)
    while (index < chars.size) {
        val c = chars[index]
        val next = chars.getOrNull(index + 1)
        when {
            c == 'っ' -> geminate = true
            c == 'ん' -> syllables += "n"
            c == LONG_MARK -> syllables.lastOrNull()?.let { last ->
                val vowel = last.lastOrNull()
                if (vowel != null && vowel in MACRONS) syllables[syllables.lastIndex] = last.dropLast(1) + MACRONS.getValue(vowel)
            }
            else -> {
                var syllable = SYLLABLES[c] ?: run { index++; continue }
                if (next != null && next in SMALL_Y && syllable.length >= 2 && syllable.endsWith("i")) {
                    val base = syllable.dropLast(1)
                    val vowel = SYLLABLES.getValue(next).last()
                    syllable = if (base == "sh" || base == "ch" || base == "j") base + vowel else base + "y" + vowel
                    index++
                } else if (next != null && next in SMALL_VOWELS) {
                    val vowel = SYLLABLES.getValue(next)
                    syllable = if (syllable == "u") "w$vowel" else syllable.dropLast(1) + vowel
                    index++
                }
                if (geminate) {
                    syllable = if (syllable.startsWith("ch")) "t$syllable" else syllable.first().takeIf { it !in VOWELS }?.let { it + syllable } ?: syllable
                    geminate = false
                }
                // Long vowels: ou, oo, uu, aa and ee take a macron; ii and ei stay.
                val last = syllables.lastOrNull()
                val previousVowel = last?.lastOrNull()
                if (last != null && syllable.length == 1 && previousVowel != null && last != "n" &&
                    (previousVowel == 'o' && (syllable == "u" || syllable == "o") ||
                        previousVowel == 'u' && syllable == "u" || previousVowel == 'a' && syllable == "a" ||
                        previousVowel == 'e' && syllable == "e")) {
                    syllables[syllables.lastIndex] = last.dropLast(1) + MACRONS.getValue(previousVowel)
                } else {
                    // ん before a vowel or y is written n' (Shin'ichi).
                    if (last == "n" && (syllable.first() in VOWELS || syllable.first() == 'y')) syllables[syllables.lastIndex] = "n'"
                    syllables += syllable
                }
            }
        }
        index++
    }
    return syllables.joinToString("")
}

/** Kana text as Hepburn romaji with macrons, each word capitalized: おうほう → Ōhō. */
internal fun romanizeKana(text: String): String =
    text.split(Regex("[\\s${MIDDLE_DOT}]+")).filter { it.isNotEmpty() }.map(::romanizeWord).filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) }

/** Kanji-only text of any length (no kana, Latin or digits). */
internal fun kanjiOnly(text: String): Boolean = text.isNotEmpty() && text.all(::kanji)

// ---- Pairing a reading with the name below it ----
//
// Pairing runs on recognized lines and elements before the stability and
// minimum-height filters: a reading only has to pair, it never gets a chip of
// its own. A name may be a whole short kanji line or a run of kanji elements
// inside a longer line (for example beside a rank). A reading may be one line
// or several kana lines on one row (letter-spaced characters). When the
// reading is too small to read in the sample, a crop above the name is read at
// a higher resolution, at most a few per sample.

internal object FrameTextNameTuning {
    /** The reading must cover at least this fraction of the narrower width of the two. */
    const val READING_OVERLAP = 0.6f
    /** Gap between the reading's bottom and the name's top, in name heights. */
    const val MIN_GAP_LINES = -0.5f
    const val MAX_GAP_LINES = 1.0f
    /** Crops read at a higher resolution per sample. */
    const val MAX_DETAIL_REGIONS = 3
    /** Samples a remembered pairing survives without its name. */
    const val MAX_MISSES = 2
}

private fun ViewerRect.right() = left + width
private fun ViewerRect.bottom() = top + height
private fun union(boxes: List<ViewerRect>): ViewerRect {
    val left = boxes.minOf { it.left }; val top = boxes.minOf { it.top }
    return ViewerRect(left, top, boxes.maxOf { it.right() } - left, boxes.maxOf { it.bottom() } - top)
}

/** Horizontal overlap as a fraction of the narrower box. */
internal fun horizontalOverlap(a: ViewerRect, b: ViewerRect): Float {
    val overlap = min(a.right(), b.right()) - max(a.left, b.left)
    val narrower = min(a.width, b.width)
    return if (overlap <= 0 || narrower <= 0) 0f else overlap.toFloat() / narrower
}

/** Gap from the reading's bottom to the name's top, in name heights; negative when they overlap. */
internal fun readingGapLines(reading: ViewerRect, name: ViewerRect): Float =
    (name.top - reading.bottom()).toFloat() / max(name.height, 1)

/**
 * The kana lines that form the reading directly above [name]: lines above it
 * within the allowed gap whose centers lie over the name (with a margin of
 * half its height), on the row nearest the name, left to right. Null when
 * they do not cover enough of the name's width.
 */
internal fun readingLinesAbove(name: ViewerRect, kana: List<FrameTextLine>): List<FrameTextLine>? {
    val margin = name.height / 2
    val near = kana.filter { line ->
        val center = line.box.left + line.box.width / 2
        val gap = readingGapLines(line.box, name)
        line.box.top < name.top && center in (name.left - margin)..(name.right() + margin) &&
            gap >= FrameTextNameTuning.MIN_GAP_LINES && gap <= FrameTextNameTuning.MAX_GAP_LINES
    }
    val anchor = near.maxByOrNull { it.box.bottom() } ?: return null
    val row = near.filter { line ->
        val shared = min(line.box.bottom(), anchor.box.bottom()) - max(line.box.top, anchor.box.top)
        shared >= min(line.box.height, anchor.box.height) / 2
    }.sortedBy { it.box.left }
    return row.takeIf { horizontalOverlap(union(it.map { line -> line.box }), name) >= FrameTextNameTuning.READING_OVERLAP }
}

/** The reading's kana: letter spacing removed, word spaces kept only when the name itself has a space. */
internal fun joinReading(lines: List<FrameTextLine>, name: String): String {
    val joined = lines.sortedBy { it.box.left }.joinToString(" ") { it.text.trim() }
    return if (name.any(Char::isWhitespace)) joined.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        else joined.filterNot(Char::isWhitespace)
}

/** Where a name's reading would be printed, in sample pixels, clamped to the frame. */
internal fun readingRegion(name: ViewerRect, frameWidth: Int, frameHeight: Int): ViewerRect {
    val pad = max(name.width / 2, name.height)
    val left = (name.left - pad).coerceIn(0, frameWidth)
    val right = (name.right() + pad).coerceIn(left, frameWidth)
    val top = (name.top - name.height * 8 / 5).coerceIn(0, frameHeight)
    val bottom = (name.top + name.height / 4).coerceIn(top, frameHeight)
    return ViewerRect(left, top, right - left, bottom - top)
}

/** Text-free pairing diagnostics: counts and geometry only. */
internal data class FrameTextPairingStats(
    val lines: Int = 0,
    val kanaOnly: Int = 0,
    val shortKanji: Int = 0,
    val paired: Int = 0,
    val remembered: Int = 0,
    val unpaired: Int = 0,
    val detail: Int = 0,
    val detailFound: Int = 0,
    val detailMs: Long = 0,
    val sameBlock: Int = 0,
    val filteredSmall: Int = 0,
    val bestOverlap: Float? = null,
    val bestGapLines: Float? = null,
    val readingHeightPx: Int? = null,
    val nameHeightPx: Int? = null,
) {
    val relevant get() = kanaOnly > 0 || shortKanji > 0
}

private fun decimal(value: Float?) = value?.let { String.format(java.util.Locale.ROOT, "%.2f", it) } ?: "NONE"

internal fun frameTextPairingLog(stats: FrameTextPairingStats): String = with(stats) {
    "pairing lines=$lines kanaOnly=$kanaOnly shortKanji=$shortKanji paired=$paired remembered=$remembered " +
        "unpaired=$unpaired detail=$detail detailFound=$detailFound detailMs=$detailMs sameBlock=$sameBlock " +
        "filteredSmall=$filteredSmall bestOverlap=${decimal(bestOverlap)} bestGapLines=${decimal(bestGapLines)} " +
        "readingHeightPx=${readingHeightPx ?: "NONE"} nameHeightPx=${nameHeightPx ?: "NONE"}"
}

internal class FrameTextPairing(val blocks: List<FrameTextBlock>, val stats: FrameTextPairingStats)

/**
 * Finds names with their printed reading in one sample's recognized blocks.
 * Each paired name becomes its own block carrying the romanized reading; the
 * name and reading are removed from the blocks they came from, and what
 * remains of those blocks is translated piece by piece. A pairing is
 * remembered while its name stays, so a sample that misses the small reading
 * keeps it. Not thread-safe: used on the pipeline's worker thread.
 */
internal class FrameTextNames(
    private val maxDetail: Int = FrameTextNameTuning.MAX_DETAIL_REGIONS,
    private val maxMisses: Int = FrameTextNameTuning.MAX_MISSES,
) {
    private class Remembered(val name: String, var box: ViewerRect, val romaji: String?, var misses: Int = 0)
    private val known = mutableListOf<Remembered>()
    // Names whose higher-resolution crop found no reading; not retried while they stay.
    private val noReading = mutableListOf<Remembered>()
    private var previousCandidates: List<Pair<String, ViewerRect>> = emptyList()

    /** True when the next sample should be captured at a higher resolution to read small readings. */
    @Volatile var wantsDetail = false
        private set

    private data class LineRef(val block: Int, val line: Int, val value: FrameTextLine)
    private data class Candidate(val ref: LineRef, val elements: IntRange?, val text: String, val box: ViewerRect)

    private fun linesOf(block: FrameTextBlock): List<FrameTextLine> {
        if (block.lines.isNotEmpty()) return block.lines
        val parts = block.text.lines()
        // A block without line boxes: a reading line over a name line splits a third / two thirds.
        if (parts.size == 2 && kanaOnly(parts[0]) && shortKanjiOnly(parts[1])) {
            val b = block.box; val readingHeight = b.height / 3
            return listOf(FrameTextLine(parts[0], ViewerRect(b.left, b.top, b.width, readingHeight)),
                FrameTextLine(parts[1], ViewerRect(b.left, b.top + readingHeight, b.width, b.height - readingHeight)))
        }
        return listOf(FrameTextLine(block.text, block.box))
    }

    // A whole short kanji line, or maximal runs of two or more kanji elements inside a longer line.
    private fun candidates(ref: LineRef): List<Candidate> {
        val line = ref.value
        if (shortKanjiOnly(line.text)) return listOf(Candidate(ref, null, line.text.filterNot(Char::isWhitespace), line.box))
        val result = mutableListOf<Candidate>()
        var start = -1
        fun close(end: Int) {
            if (start < 0) return
            val run = line.elements.subList(start, end)
            val text = run.joinToString("") { it.text }
            if (text.length in 2..MAX_NAME_KANJI) result += Candidate(ref, start until end, text, union(run.map { it.box }))
            start = -1
        }
        line.elements.forEachIndexed { index, element ->
            if (kanjiOnly(element.text)) { if (start < 0) start = index } else close(index)
        }
        close(line.elements.size)
        return result
    }

    private fun same(name: String, box: ViewerRect, other: Remembered) = other.name == name && sameFrameTextBox(other.box, box)

    fun pair(
        blocks: List<FrameTextBlock>,
        romanize: Boolean,
        frameWidth: Int,
        frameHeight: Int,
        detail: ((ViewerRect) -> List<FrameTextLine>?)? = null,
        clock: () -> Long = { 0L },
    ): FrameTextPairing {
        val refs = blocks.flatMapIndexed { b, block -> linesOf(block).mapIndexed { l, line -> LineRef(b, l, line) } }
        val kanaRefs = refs.filter { kanaOnly(it.value.text) }
        val candidates = if (romanize) refs.flatMap(::candidates) else emptyList()
        var stats = FrameTextPairingStats(lines = refs.size, kanaOnly = kanaRefs.size, shortKanji = candidates.size)
        if (!romanize) { wantsDetail = false; return FrameTextPairing(blocks, stats) }

        val usedLines = mutableSetOf<Pair<Int, Int>>()
        val usedElements = mutableMapOf<Pair<Int, Int>, MutableSet<Int>>()
        val names = mutableListOf<FrameTextBlock>()
        val seen = mutableListOf<Pair<String, ViewerRect>>()
        var best: Pair<FrameTextLine, Candidate>? = null
        var bestOverlap = -1f
        var detailUsed = 0
        var want = false
        for (candidate in candidates) {
            val key = candidate.ref.block to candidate.ref.line
            if (key in usedLines) continue
            seen += candidate.text to candidate.box
            kanaRefs.forEach { kana ->
                if (kana.value.box.top < candidate.box.top) {
                    val overlap = horizontalOverlap(kana.value.box, candidate.box)
                    if (overlap > bestOverlap) { bestOverlap = overlap; best = kana.value to candidate }
                }
            }
            val available = kanaRefs.filter { (it.block to it.line) !in usedLines }
            val reading = readingLinesAbove(candidate.box, available.map { it.value })
            val readingRefs = reading?.let { lines -> available.filter { it.value in lines } }.orEmpty()
            val remembered = known.firstOrNull { same(candidate.text, candidate.box, it) }
            var romaji = remembered?.romaji
                ?: reading?.let { romanizeKana(joinReading(it, candidate.text)) }?.takeIf { it.isNotEmpty() }
            val blocked = noReading.any { same(candidate.text, candidate.box, it) }
            if (romaji == null && !blocked) {
                // Read the area above at a higher resolution only for a name that held still since the last sample.
                val steady = previousCandidates.any { (text, box) -> text == candidate.text && sameFrameTextBox(box, candidate.box) }
                if (detail != null && steady && detailUsed < maxDetail) {
                    detailUsed++
                    val started = clock()
                    val found = detail(readingRegion(candidate.box, frameWidth, frameHeight))
                    stats = stats.copy(detailMs = stats.detailMs + (clock() - started))
                    romaji = found?.filter { kanaOnly(it.text) }?.let { readingLinesAbove(candidate.box, it) }
                        ?.let { romanizeKana(joinReading(it, candidate.text)) }?.takeIf { it.isNotEmpty() }
                    if (romaji != null) stats = stats.copy(detailFound = stats.detailFound + 1)
                    else if (found != null) noReading += Remembered(candidate.text, candidate.box, null)
                } else if (steady) want = true // Moving text never asks for a higher resolution.
            }
            if (romaji == null) { stats = stats.copy(unpaired = stats.unpaired + 1); continue }
            if (candidate.elements == null) usedLines += key
            else usedElements.getOrPut(key) { mutableSetOf() }.addAll(candidate.elements)
            readingRefs.forEach { usedLines += it.block to it.line }
            if (readingRefs.any { it.block == candidate.ref.block }) stats = stats.copy(sameBlock = stats.sameBlock + 1)
            stats = if (remembered != null) stats.copy(remembered = stats.remembered + 1) else stats.copy(paired = stats.paired + 1)
            if (remembered == null) known += Remembered(candidate.text, candidate.box, romaji) else remembered.box = candidate.box
            names += FrameTextBlock(candidate.text, candidate.box, candidate.box.height, reading = romaji)
        }
        stats = stats.copy(detail = detailUsed)
        best?.let { (kana, candidate) ->
            stats = stats.copy(bestOverlap = bestOverlap, bestGapLines = readingGapLines(kana.box, candidate.box),
                readingHeightPx = kana.box.height, nameHeightPx = candidate.box.height)
        }
        // Remembered pairings and failed crops last while their name stays.
        listOf(known, noReading).forEach { list ->
            list.forEach { entry -> if (seen.any { (text, box) -> same(text, box, entry) }) entry.misses = 0 else entry.misses++ }
            list.removeAll { it.misses > maxMisses }
        }
        previousCandidates = seen
        wantsDetail = want
        return FrameTextPairing(rebuild(blocks, usedLines, usedElements) + names, stats)
    }

    // Blocks without the paired names and readings; a block that lost lines is split into its remaining pieces.
    private fun rebuild(
        blocks: List<FrameTextBlock>,
        usedLines: Set<Pair<Int, Int>>,
        usedElements: Map<Pair<Int, Int>, Set<Int>>,
    ): List<FrameTextBlock> = blocks.flatMapIndexed { b, block ->
        val lines = linesOf(block)
        if (lines.indices.none { (b to it) in usedLines || (b to it) in usedElements }) return@flatMapIndexed listOf(block)
        lines.flatMapIndexed { l, line ->
            if ((b to l) in usedLines) return@flatMapIndexed emptyList()
            val used = usedElements[b to l] ?: return@flatMapIndexed listOf(FrameTextBlock(line.text, line.box, line.box.height))
            // Contiguous runs of the elements that remain.
            val pieces = mutableListOf<List<FrameTextElement>>()
            var current = mutableListOf<FrameTextElement>()
            line.elements.forEachIndexed { index, element ->
                if (index in used) { if (current.isNotEmpty()) pieces += current; current = mutableListOf() } else current += element
            }
            if (current.isNotEmpty()) pieces += current
            pieces.map { piece ->
                val box = union(piece.map { it.box })
                FrameTextBlock(piece.joinToString(" ") { it.text }, box, box.height)
            }
        }
    }

    fun clear() { known.clear(); noReading.clear(); previousCandidates = emptyList(); wantsDetail = false }
}
