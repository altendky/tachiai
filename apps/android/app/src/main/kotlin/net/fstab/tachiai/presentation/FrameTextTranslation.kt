package net.fstab.tachiai.presentation

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Translation of text that appears in the video picture (#664). The main pane's
// frames are sampled on the device, text is recognized and translated on the
// device, and a chip replaces stable source text. Frames, recognized text and
// translations stay in memory: they are never persisted, logged or sent off
// the device. Only timings, counts and cadence are logged.

internal const val FRAME_TEXT_LOG_TAG = "TachiaiFrameText"

internal object FrameTextTuning {
    const val SAMPLE_INTERVAL_MS = 2_000L
    /** The sampled frame's long edge, in pixels: 960 × 540 for 16:9 video. */
    const val SAMPLE_LONG_EDGE = 960
    /** Lines shorter than this fraction of the frame height are skipped (16 px at 540). */
    const val MIN_LINE_HEIGHT_FRACTION = 0.03f
    /** Intersection over union at or above which two boxes are roughly the same place. */
    const val SAME_BOX_OVERLAP = 0.5f
    /** A held translation goes after its source block is missing or changed for this many samples. */
    const val MISSES_TO_DROP = 2
    const val MAX_HELD = 8
    /** The separate in-memory cache for recognized text, so title translations are not evicted. */
    const val CACHE_ENTRIES = 128
    const val SIGNATURE_COLUMNS = 32
    const val SIGNATURE_ROWS = 18
    const val SIGNATURE_STRIDE = 4
    /** Largest per-cell luminance change (of 255) treated as the same picture. */
    const val SIGNATURE_TOLERANCE = 2
}

/** One recognized block in sampled-frame pixels. Held in memory only; never logged or stored. */
internal data class FrameTextBlock(val text: String, val box: ViewerRect, val lineHeight: Int)

/** A stable block whose translation is shown until the block changes or disappears. */
internal data class HeldFrameText(val id: Long, val text: String, val box: ViewerRect, val lineHeight: Int)

/** A translation ready to show over its source block, in sampled-frame pixels. */
internal data class FrameTextChip(val id: Long, val box: ViewerRect, val lineHeight: Int, val text: String)

// ---- Enablement ----

/** A feed's Translate text in video inputs: the provider default and whether its frames are readable. */
internal data class FrameTextFeed(val source: ProviderTextSource, val providerDefault: Boolean, val framesReadable: Boolean)

internal enum class FrameTextRun { OFF, RUNNING, UNAVAILABLE }

/** The in-stream override wins over the provider default. */
internal fun frameTextEnabled(feed: FrameTextFeed?, override: Boolean?): Boolean =
    feed != null && (override ?: feed.providerDefault)

/**
 * Runs only on the main (full-size) pane when it is on for that feed. A feed
 * whose frames cannot be read, such as one decoded into a secure surface, is
 * reported as unavailable and never worked around.
 */
internal fun frameTextRun(feed: FrameTextFeed?, override: Boolean?, mainPane: Boolean, surfaceReadable: Boolean): FrameTextRun = when {
    !mainPane || feed == null || !frameTextEnabled(feed, override) -> FrameTextRun.OFF
    !feed.framesReadable || !surfaceReadable -> FrameTextRun.UNAVAILABLE
    else -> FrameTextRun.RUNNING
}

/** The pane that may run: the primary feed of a pair, or the only feed. The floating inset never runs. */
internal fun <K> frameTextMainFeed(selected: Collection<K>, primary: K): K? =
    if (primary in selected) primary else selected.singleOrNull()

/**
 * The viewer's temporary in-stream choices. They are not persisted: a new
 * viewer session starts empty, so reopening a stream uses the provider default.
 */
internal class FrameTextOverrides<K> {
    private val values = mutableMapOf<K, Boolean>()
    operator fun get(key: K): Boolean? = values[key]
    /** Turns the feed's current state over and returns the new state. */
    fun toggle(key: K, feed: FrameTextFeed): Boolean = (!frameTextEnabled(feed, values[key])).also { values[key] = it }
    fun clear() = values.clear()
}

// ---- Sampling geometry ----

/** The sample bitmap size for a video surface, keeping its aspect with a long edge of at most [longEdge]. */
internal fun frameTextSampleSize(width: Int, height: Int, longEdge: Int = FrameTextTuning.SAMPLE_LONG_EDGE): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    val scale = min(1f, longEdge.toFloat() / max(width, height))
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

/** Maps a box in sampled-frame pixels to the view, given where the video surface is in that view. */
internal fun mapFrameTextBox(box: ViewerRect, frameWidth: Int, frameHeight: Int, video: ViewerRect): ViewerRect {
    require(frameWidth > 0 && frameHeight > 0)
    val sx = video.width.toFloat() / frameWidth
    val sy = video.height.toFloat() / frameHeight
    val left = video.left + floor(box.left * sx).toInt()
    val top = video.top + floor(box.top * sy).toInt()
    val right = video.left + ceil((box.left + box.width) * sx).toInt()
    val bottom = video.top + ceil((box.top + box.height) * sy).toInt()
    return ViewerRect(left, top, right - left, bottom - top)
}

/** The part of a pane not covered by the visible overlay (#656), in pane coordinates. */
internal fun frameTextClearArea(width: Int, height: Int, insets: ViewerInsets): ViewerRect =
    ViewerRect(0, insets.top, (width - insets.right).coerceAtLeast(0), (height - insets.top - insets.bottom).coerceAtLeast(0))

/** Centers a chip over its source box, kept inside [clear]; null when nothing is clear. */
internal fun placeFrameTextChip(target: ViewerRect, chipWidth: Int, chipHeight: Int, clear: ViewerRect): ViewerRect? {
    if (clear.width <= 0 || clear.height <= 0) return null
    val w = chipWidth.coerceIn(0, clear.width)
    val h = chipHeight.coerceIn(0, clear.height)
    val left = (target.left + (target.width - w) / 2).coerceIn(clear.left, clear.left + clear.width - w)
    val top = (target.top + (target.height - h) / 2).coerceIn(clear.top, clear.top + clear.height - h)
    return ViewerRect(left, top, w, h)
}

// ---- Unchanged frames ----

/** Mean luminance of a coarse grid, sampled every few pixels: a cheap downscaled frame hash. */
internal fun frameSignature(
    pixels: IntArray,
    width: Int,
    height: Int,
    columns: Int = FrameTextTuning.SIGNATURE_COLUMNS,
    rows: Int = FrameTextTuning.SIGNATURE_ROWS,
    stride: Int = FrameTextTuning.SIGNATURE_STRIDE,
): ByteArray {
    require(width > 0 && height > 0 && pixels.size >= width * height && columns > 0 && rows > 0 && stride > 0)
    val signature = ByteArray(columns * rows)
    for (row in 0 until rows) for (column in 0 until columns) {
        val x0 = column * width / columns; val x1 = max(x0 + 1, (column + 1) * width / columns).coerceAtMost(width)
        val y0 = row * height / rows; val y1 = max(y0 + 1, (row + 1) * height / rows).coerceAtMost(height)
        var sum = 0L; var count = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val pixel = pixels[y * width + x]
                sum += ((pixel shr 16 and 0xFF) * 77 + (pixel shr 8 and 0xFF) * 150 + (pixel and 0xFF) * 29) shr 8
                count++; x += stride
            }
            y += stride
        }
        signature[row * columns + column] = (if (count == 0) 0 else sum / count).toInt().toByte()
    }
    return signature
}

internal fun frameUnchanged(previous: ByteArray?, next: ByteArray, tolerance: Int = FrameTextTuning.SIGNATURE_TOLERANCE): Boolean =
    previous != null && previous.size == next.size &&
        previous.indices.all { abs((previous[it].toInt() and 0xFF) - (next[it].toInt() and 0xFF)) <= tolerance }

// ---- Stability ----

/** Trimmed lines with collapsed spaces; blank lines dropped. */
internal fun normalizedFrameText(text: String): String =
    text.lines().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }.joinToString("\n")

internal fun sameFrameTextBox(a: ViewerRect, b: ViewerRect, overlap: Float = FrameTextTuning.SAME_BOX_OVERLAP): Boolean {
    val w = min(a.left + a.width, b.left + b.width) - max(a.left, b.left)
    val h = min(a.top + a.height, b.top + b.height) - max(a.top, b.top)
    if (w <= 0 || h <= 0) return false
    val intersection = w.toLong() * h
    val union = a.width.toLong() * a.height + b.width.toLong() * b.height - intersection
    return union > 0 && intersection >= union * overlap
}

/**
 * Only stable text is translated: the same recognized text in roughly the same
 * box in two consecutive samples, with lines at least a minimum height. A held
 * block stays until it is missing or changed for [missesToDrop] samples, so a
 * translation does not flicker. Tickers, motion and partial text never settle.
 */
internal class FrameTextStability(
    private val minLineFraction: Float = FrameTextTuning.MIN_LINE_HEIGHT_FRACTION,
    private val missesToDrop: Int = FrameTextTuning.MISSES_TO_DROP,
    private val maxHeld: Int = FrameTextTuning.MAX_HELD,
) {
    private class Held(val id: Long, val text: String, var box: ViewerRect, var lineHeight: Int, var misses: Int = 0)
    private var previous: List<FrameTextBlock> = emptyList()
    private val held = mutableListOf<Held>()
    private var nextId = 1L

    fun update(blocks: List<FrameTextBlock>, frameHeight: Int): List<HeldFrameText> {
        val minimum = frameHeight * minLineFraction
        val candidates = blocks.mapNotNull { block ->
            val text = normalizedFrameText(block.text)
            block.copy(text = text).takeIf { text.any(Char::isLetter) && block.lineHeight >= minimum }
        }
        val unmatched = candidates.toMutableList()
        held.forEach { entry ->
            val match = unmatched.firstOrNull { it.text == entry.text && sameFrameTextBox(it.box, entry.box) }
            if (match == null) entry.misses++
            else { unmatched.remove(match); entry.box = match.box; entry.lineHeight = match.lineHeight; entry.misses = 0 }
        }
        held.removeAll { it.misses >= missesToDrop }
        unmatched.forEach { block ->
            if (held.size < maxHeld && previous.any { it.text == block.text && sameFrameTextBox(it.box, block.box) })
                held += Held(nextId++, block.text, block.box, block.lineHeight)
        }
        previous = candidates
        return held.map { HeldFrameText(it.id, it.text, it.box, it.lineHeight) }
    }

    fun clear() { previous = emptyList(); held.clear() }
}

// ---- Pipeline ----

/** A captured frame. Both calls block the worker thread so the frame stays untouched until they return. */
internal interface FrameTextSample {
    val width: Int
    val height: Int
    fun signature(): ByteArray
    fun recognize(): List<FrameTextBlock>
}

/** Translation of recognized text, with its own small cache. Null keeps the original visible. */
internal interface FrameTextTranslator {
    fun cached(text: String): String?
    suspend fun translate(text: String): String?
    /** True when Japanese readings are shown romanized: the target language is not Japanese. */
    fun romanizes(): Boolean = true
}

/** Numbers only, so a log line cannot carry frame content or text. */
internal data class FrameTextSampleTiming(
    val intervalMs: Long,
    val captureMs: Long,
    /** Null when the frame was unchanged and recognition was skipped. */
    val ocrMs: Long?,
    val blocks: Int,
    val stable: Int,
    val shown: Int,
    /** Shown chips holding a romanized reading rather than a translation. */
    val romanized: Int = 0,
)

internal fun frameTextSampleLog(timing: FrameTextSampleTiming): String =
    "sample intervalMs=${timing.intervalMs} captureMs=${timing.captureMs} " +
        (timing.ocrMs?.let { "ocrMs=$it" } ?: "ocr=SKIPPED_UNCHANGED") +
        " blocks=${timing.blocks} stable=${timing.stable} shown=${timing.shown} romanized=${timing.romanized}"

internal fun frameTextTranslateLog(ms: Long, translated: Boolean): String =
    "translate ms=$ms result=${if (translated) "TRANSLATED" else "ORIGINAL"}"

internal fun frameTextStateLog(run: FrameTextRun, sampleWidth: Int = 0, sampleHeight: Int = 0): String =
    "state=${run.name}" + if (sampleWidth > 0 && sampleHeight > 0) " sample=${sampleWidth}x$sampleHeight" else ""

/**
 * One feed's processing: skip recognition for an unchanged frame, keep stable
 * blocks, and translate each held block once. [process] runs on one worker
 * thread; translations complete on [scope] and report through [changed].
 * Every log line comes from the numeric formatters above.
 */
internal class FrameTextPipeline(
    private val translator: FrameTextTranslator,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val clock: () -> Long,
    private val changed: () -> Unit,
) {
    private val lock = Any()
    private val stability = FrameTextStability()
    private var signature: ByteArray? = null
    private var blocks: List<FrameTextBlock> = emptyList()
    private var held: List<HeldFrameText> = emptyList()
    private var displays: Map<Long, FrameTextDisplay> = emptyMap()
    private val pending = mutableSetOf<String>()
    // Held texts that keep their original (same language, unsure, unavailable); not retried while held.
    private val originals = mutableSetOf<String>()

    fun process(sample: FrameTextSample, captureMs: Long, intervalMs: Long): List<FrameTextChip> {
        val next = sample.signature()
        var ocrMs: Long? = null
        val recognized = if (frameUnchanged(signature, next)) blocks else {
            val started = clock()
            sample.recognize().also { ocrMs = clock() - started }
        }
        signature = next; blocks = recognized
        val stable = stability.update(recognized, sample.height)
        val plan = frameTextDisplays(stable, translator.romanizes())
        val texts = stable.filter { plan[it.id] == FrameTextDisplay.Translate }.map { it.text }.toSet()
        synchronized(lock) { held = stable; displays = plan; originals.retainAll(texts) }
        texts.forEach(::request)
        val chips = chips()
        val romanized = chips.count { plan[it.id] is FrameTextDisplay.Literal }
        log(frameTextSampleLog(FrameTextSampleTiming(intervalMs, captureMs, ocrMs, recognized.size, stable.size, chips.size, romanized)))
        return chips
    }

    /** Held blocks with a romanized reading, or whose translation is ready. */
    fun chips(): List<FrameTextChip> = synchronized(lock) {
        held.mapNotNull { entry ->
            val text = when (val display = displays[entry.id]) {
                is FrameTextDisplay.Literal -> display.text
                FrameTextDisplay.Hidden -> null
                FrameTextDisplay.Translate, null -> translator.cached(entry.text)
            }
            text?.let { FrameTextChip(entry.id, entry.box, entry.lineHeight, it) }
        }
    }

    private fun request(text: String) {
        if (translator.cached(text) != null) return
        if (!synchronized(lock) { text !in originals && pending.add(text) }) return
        scope.launch {
            val started = clock()
            val result = try { translator.translate(text) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { null }
                finally { synchronized(lock) { pending.remove(text) } }
            log(frameTextTranslateLog(clock() - started, result != null))
            val stillHeld = synchronized(lock) {
                held.any { it.text == text }.also { if (it && result == null) originals += text }
            }
            if (result != null && stillHeld) changed()
        }
    }

    /** Forgets every frame-derived value; the next sample starts fresh. */
    fun clear() {
        synchronized(lock) { held = emptyList(); displays = emptyMap(); pending.clear(); originals.clear() }
        signature = null; blocks = emptyList(); stability.clear()
    }
}
