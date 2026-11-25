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

/** What a held block shows: a translation, fixed text such as a romanized reading, or nothing. */
internal sealed interface FrameTextDisplay {
    data object Translate : FrameTextDisplay
    data class Literal(val text: String) : FrameTextDisplay
    data object Hidden : FrameTextDisplay
}

/**
 * True when [reading] sits directly above [name]: their horizontal overlap is
 * most of the narrower block's width, and the gap between them is within
 * about one line of the name.
 */
internal fun readingAbove(reading: HeldFrameText, name: HeldFrameText): Boolean {
    val r = reading.box; val n = name.box
    val overlap = min(r.left + r.width, n.left + n.width) - max(r.left, n.left)
    if (overlap <= 0 || overlap < min(r.width, n.width) * READING_OVERLAP) return false
    val gap = n.top - (r.top + r.height)
    val line = max(name.lineHeight, 1)
    return r.top < n.top && gap <= line && gap >= -line / 2
}

private const val READING_OVERLAP = 0.6f

/**
 * Chooses each held block's display. With [romanize] (the device language
 * is not Japanese), a short kanji-only name with a kana-only reading directly
 * above it shows the romanized reading, and that reading gets no chip of its
 * own; a block that is a reading line over a name line is shown the same way.
 * Everything else, including kana with no name below it and names with no
 * printed reading, is translated.
 */
internal fun frameTextDisplays(held: List<HeldFrameText>, romanize: Boolean): Map<Long, FrameTextDisplay> {
    if (!romanize) return held.associate { it.id to FrameTextDisplay.Translate }
    val result = mutableMapOf<Long, FrameTextDisplay>()
    val readings = held.filter { kanaOnly(it.text) }
    held.filter { shortKanjiOnly(it.text) }.forEach { name ->
        val reading = readings.filter { readingAbove(it, name) }.minByOrNull { name.box.top - (it.box.top + it.box.height) }
            ?: return@forEach
        romanizeKana(reading.text).takeIf { it.isNotEmpty() }?.let {
            result[name.id] = FrameTextDisplay.Literal(it)
            result[reading.id] = FrameTextDisplay.Hidden
        }
    }
    held.forEach { entry ->
        if (entry.id in result) return@forEach
        val lines = entry.text.lines()
        // One block holding a reading line over a name line is the same pair.
        val pairedReading = if (lines.size == 2 && kanaOnly(lines[0]) && shortKanjiOnly(lines[1]))
            romanizeKana(lines[0]).takeIf { it.isNotEmpty() } else null
        result[entry.id] = pairedReading?.let { FrameTextDisplay.Literal(it) } ?: FrameTextDisplay.Translate
    }
    return result
}
