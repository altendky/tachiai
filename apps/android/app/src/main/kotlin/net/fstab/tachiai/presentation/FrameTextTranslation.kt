package net.fstab.tachiai.presentation

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
    /** The higher-resolution capture's long edge, used only while a name's small reading is sought. */
    const val DETAIL_LONG_EDGE = 1920
    /** A crop above a name is enlarged to about this height, at most [DETAIL_MAX_ZOOM] times. */
    const val DETAIL_CROP_HEIGHT = 160
    const val DETAIL_MAX_ZOOM = 3f
    /** Lines shorter than this fraction of the frame height are skipped (16 px at 540). */
    const val MIN_LINE_HEIGHT_FRACTION = 0.03f
    /** Intersection over union at or above which two boxes are roughly the same place. */
    const val SAME_BOX_OVERLAP = 0.5f
    /** A new stable block replaces a held one covering this fraction of the smaller box. */
    const val REPLACE_OVERLAP = 0.3f
    /** A position change this far from what playback explains counts as a jump. */
    const val JUMP_TOLERANCE_MS = 3_000L
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
internal data class FrameTextBlock(
    val text: String,
    val box: ViewerRect,
    val lineHeight: Int,
    /** The recognized lines with their boxes, when known; used to pair names with readings. */
    val lines: List<FrameTextLine> = emptyList(),
    /** A name's romanized printed reading, shown instead of a translation. */
    val reading: String? = null,
)

/** A recognized line and its elements (words or characters), in sampled-frame pixels. */
internal data class FrameTextLine(val text: String, val box: ViewerRect, val elements: List<FrameTextElement> = emptyList())

internal data class FrameTextElement(val text: String, val box: ViewerRect)

/** A stable block whose translation is shown until the block changes or disappears. */
internal data class HeldFrameText(val id: Long, val text: String, val box: ViewerRect, val lineHeight: Int,
    /** A romanized reading shown instead of a translation. */
    val literal: String? = null)

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

/** True when the shared area is at least [fraction] of the smaller box, such as a new caption over an old one. */
internal fun frameTextBoxesOverlap(a: ViewerRect, b: ViewerRect, fraction: Float = FrameTextTuning.REPLACE_OVERLAP): Boolean {
    val w = min(a.left + a.width, b.left + b.width) - max(a.left, b.left)
    val h = min(a.top + a.height, b.top + b.height) - max(a.top, b.top)
    if (w <= 0 || h <= 0) return false
    val smaller = min(a.width.toLong() * a.height, b.width.toLong() * b.height)
    return smaller > 0 && w.toLong() * h >= smaller * fraction
}

/**
 * The position change between two measurements against what playback
 * explains: the elapsed time while playing at both, nothing while paused at
 * both, and anything in between when playback started or stopped between
 * them. [deltaMs] is how far the change fell outside that range.
 */
internal data class FrameTextPositionCheck(
    val previousPositionMs: Long,
    val positionMs: Long,
    val elapsedMs: Long,
    val expectedMs: Long,
    val deltaMs: Long,
    val jumped: Boolean,
) {
    val movedMs: Long get() = positionMs - previousPositionMs
}

internal fun frameTextPositionCheck(
    previousPositionMs: Long,
    previousAtMs: Long,
    positionMs: Long,
    atMs: Long,
    wasPlaying: Boolean,
    playing: Boolean,
    toleranceMs: Long = FrameTextTuning.JUMP_TOLERANCE_MS,
): FrameTextPositionCheck {
    val elapsed = atMs - previousAtMs
    val moved = positionMs - previousPositionMs
    val expected = when {
        wasPlaying && playing -> elapsed
        wasPlaying || playing -> moved.coerceIn(0L, max(0L, elapsed))
        else -> 0L
    }
    val delta = moved - expected
    return FrameTextPositionCheck(previousPositionMs, positionMs, elapsed, expected, delta, abs(delta) > toleranceMs)
}

/**
 * True when the picture may have jumped between two samples: the position
 * moved by more than [toleranceMs] away from what playing (or staying paused)
 * explains. Catches jumps that report no seek, such as a resume after a hold.
 */
internal fun frameTextPositionJumped(
    previousPositionMs: Long,
    previousAtMs: Long,
    positionMs: Long,
    atMs: Long,
    playing: Boolean,
    toleranceMs: Long = FrameTextTuning.JUMP_TOLERANCE_MS,
): Boolean = frameTextPositionCheck(previousPositionMs, previousAtMs, positionMs, atMs, playing, playing, toleranceMs).jumped

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
    private class Held(val id: Long, val text: String, var box: ViewerRect, var lineHeight: Int, var literal: String?,
        var misses: Int = 0)
    private var previous: List<FrameTextBlock> = emptyList()
    private val held = mutableListOf<Held>()
    private val replaced = mutableListOf<FrameTextBlock>()
    private var nextId = 1L

    /** Blocks dropped by the minimum line height in the last update (diagnostics). */
    var filteredSmall = 0
        private set

    fun update(blocks: List<FrameTextBlock>, frameHeight: Int): List<HeldFrameText> {
        val minimum = frameHeight * minLineFraction
        filteredSmall = 0
        val candidates = blocks.mapNotNull { block ->
            val text = normalizedFrameText(block.text)
            if (text.any(Char::isLetter) && block.lineHeight < minimum) filteredSmall++
            block.copy(text = text).takeIf { text.any(Char::isLetter) && block.lineHeight >= minimum }
        }
        val unmatched = candidates.toMutableList()
        held.forEach { entry ->
            val match = unmatched.firstOrNull { it.text == entry.text && sameFrameTextBox(it.box, entry.box) }
            if (match == null) entry.misses++
            else {
                unmatched.remove(match); entry.box = match.box; entry.lineHeight = match.lineHeight; entry.misses = 0
                entry.literal = match.reading ?: entry.literal
            }
        }
        held.removeAll { it.misses >= missesToDrop }
        // A replaced block stays suppressed while it is still read, so two
        // overlapping texts cannot take turns.
        replaced.retainAll { gone -> candidates.any { it.text == gone.text && sameFrameTextBox(it.box, gone.box) } }
        unmatched.forEach { block ->
            if (!previous.any { it.text == block.text && sameFrameTextBox(it.box, block.box) }) return@forEach
            if (replaced.any { it.text == block.text && sameFrameTextBox(it.box, block.box) }) return@forEach
            // A new stable block replaces any held block it overlaps, so chips never stack.
            held.removeAll { old ->
                frameTextBoxesOverlap(old.box, block.box).also { if (it) replaced += FrameTextBlock(old.text, old.box, old.lineHeight) }
            }
            if (held.size < maxHeld) held += Held(nextId++, block.text, block.box, block.lineHeight, block.reading)
        }
        previous = candidates
        return held.map { HeldFrameText(it.id, it.text, it.box, it.lineHeight, it.literal) }
    }

    fun clear() { previous = emptyList(); held.clear(); replaced.clear() }
}

// ---- Pipeline ----

/** A captured frame. Both calls block the worker thread so the frame stays untouched until they return. */
internal interface FrameTextSample {
    val width: Int
    val height: Int
    fun signature(): ByteArray
    fun recognize(): List<FrameTextBlock>
    /**
     * Lines read from [region] (in sample pixels) at a higher resolution, mapped
     * back to sample pixels; null when no higher-resolution frame was captured.
     */
    fun recognizeRegion(region: ViewerRect): List<FrameTextLine>? = null
    /** True when a higher-resolution frame was captured for this sample. */
    val detailed: Boolean get() = false
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
    /** Held blocks showing a romanized reading rather than a translation. */
    val romanized: Int = 0,
    /** True when the capture was at the higher resolution used to read small readings. */
    val highRes: Boolean = false,
)

internal fun frameTextSampleLog(timing: FrameTextSampleTiming): String =
    "sample intervalMs=${timing.intervalMs} captureMs=${timing.captureMs} " +
        (timing.ocrMs?.let { "ocrMs=$it" } ?: "ocr=SKIPPED_UNCHANGED") +
        " blocks=${timing.blocks} stable=${timing.stable} shown=${timing.shown} romanized=${timing.romanized}" +
        if (timing.highRes) " capture=HIGH_RES" else ""

internal fun frameTextTranslateLog(ms: Long, translated: Boolean): String =
    "translate ms=$ms result=${if (translated) "TRANSLATED" else "ORIGINAL"}"

/**
 * The playing position on the stream's own clock: the window's start time
 * plus [positionMs] when the stream gives one (DASH live, HLS with program
 * date times), otherwise the position from the start of the window's first
 * period. A live window sliding forward, playback crossing into the next
 * period or an old period leaving the window does not move it (#723).
 */
internal fun frameTextStreamPositionMs(
    timeline: Timeline,
    windowIndex: Int,
    positionMs: Long,
    window: Timeline.Window = Timeline.Window(),
): Long {
    if (timeline.isEmpty || windowIndex !in 0 until timeline.windowCount) return positionMs
    timeline.getWindow(windowIndex, window)
    return positionMs + if (window.windowStartTimeMs != C.TIME_UNSET) window.windowStartTimeMs
        else window.positionInFirstPeriodMs
}

/** Whether [frameTextStreamPositionMs] is measured from the window's start time. */
internal fun frameTextWindowStartKnown(timeline: Timeline, windowIndex: Int, window: Timeline.Window = Timeline.Window()) =
    !timeline.isEmpty && windowIndex in 0 until timeline.windowCount &&
        timeline.getWindow(windowIndex, window).windowStartTimeMs != C.TIME_UNSET

internal enum class FrameTextDiscontinuityAction { RESET, CHECK_POSITION, REBASE, IGNORE }

/**
 * What a player discontinuity means for held text and chips. A seek
 * (transport, scrub, skip, Back to live, a timing shift), its adjustment, a
 * skipped ad or period, or a move into another item changes the picture, so
 * they are dropped. Playback moving on into the next period of the same item
 * is never a jump, even where the stream clock does not line up across the
 * boundary, as at ABEMA live ad and programme boundaries (#723): the jump
 * check restarts from the new position. The current period leaving the
 * timeline drops them only when the stream position jumped.
 */
internal fun frameTextDiscontinuityAction(reason: Int, sameItem: Boolean): FrameTextDiscontinuityAction =
    when (reason) {
        Player.DISCONTINUITY_REASON_SEEK,
        Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT,
        Player.DISCONTINUITY_REASON_SKIP,
        -> FrameTextDiscontinuityAction.RESET
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION ->
            if (sameItem) FrameTextDiscontinuityAction.REBASE else FrameTextDiscontinuityAction.RESET
        Player.DISCONTINUITY_REASON_REMOVE ->
            if (sameItem) FrameTextDiscontinuityAction.CHECK_POSITION else FrameTextDiscontinuityAction.RESET
        else -> FrameTextDiscontinuityAction.IGNORE
    }

/** Whether [action] drops held text, given the position measured at the discontinuity. */
internal fun frameTextDiscontinuityResets(action: FrameTextDiscontinuityAction, check: FrameTextPositionCheck?): Boolean =
    when (action) {
        FrameTextDiscontinuityAction.RESET -> true
        FrameTextDiscontinuityAction.CHECK_POSITION -> check?.jumped == true
        FrameTextDiscontinuityAction.REBASE, FrameTextDiscontinuityAction.IGNORE -> false
    }

private fun frameTextDiscontinuityReasonName(reason: Int) = when (reason) {
    Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
    Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
    Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
    Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
    Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
    Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
    Player.DISCONTINUITY_REASON_SILENCE_SKIP -> "SILENCE_SKIP"
    else -> "OTHER"
}

private fun windowStartName(known: Boolean) = if (known) "KNOWN" else "UNKNOWN"

private fun FrameTextPositionCheck?.closedFields() = if (this == null)
    "previousMs=UNKNOWN streamMs=UNKNOWN elapsedMs=UNKNOWN movedMs=UNKNOWN expectedMs=UNKNOWN deltaMs=UNKNOWN"
    else "previousMs=$previousPositionMs streamMs=$positionMs elapsedMs=$elapsedMs movedMs=$movedMs " +
        "expectedMs=$expectedMs deltaMs=$deltaMs"

/**
 * One discontinuity as closed names and numbers only: the Media3 reason,
 * period indexes, Media3's own window-relative jump, the stream positions the
 * jump check measured against the previous measurement, and what was done.
 */
internal fun frameTextDiscontinuityLog(
    reason: Int,
    fromPeriod: Int,
    toPeriod: Int,
    periods: Int,
    sameItem: Boolean,
    windowStartKnown: Boolean,
    windowJumpMs: Long,
    check: FrameTextPositionCheck?,
    action: FrameTextDiscontinuityAction,
    reset: Boolean,
): String = "discontinuity reason=${frameTextDiscontinuityReasonName(reason)} from=$fromPeriod to=$toPeriod " +
    "of=$periods sameItem=$sameItem windowStart=${windowStartName(windowStartKnown)} windowJumpMs=$windowJumpMs " +
    "${check.closedFields()} action=${action.name} reset=$reset"

/** A jump found between samples, with the same numbers as [frameTextDiscontinuityLog]. */
internal fun frameTextPositionJumpLog(check: FrameTextPositionCheck, windowStartKnown: Boolean): String =
    "positionJump windowStart=${windowStartName(windowStartKnown)} ${check.closedFields()}"

internal enum class FrameTextResetCause { DISCONTINUITY, POSITION_JUMP, PLAYER_CHANGED }

internal fun frameTextResetLog(cause: FrameTextResetCause): String = "reset cause=${cause.name}"

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
    private val names = FrameTextNames()
    private val pending = mutableSetOf<String>()
    // Held texts that keep their original (same language, unsure, unavailable); not retried while held.
    private val originals = mutableSetOf<String>()

    /** True when the next capture should include a higher-resolution frame for small name readings. */
    val wantsDetail: Boolean get() = names.wantsDetail

    fun process(sample: FrameTextSample, captureMs: Long, intervalMs: Long): List<FrameTextChip> {
        val next = sample.signature()
        var ocrMs: Long? = null
        val unchanged = frameUnchanged(signature, next)
        val recognized = if (unchanged) blocks else {
            val started = clock()
            sample.recognize().also { ocrMs = clock() - started }
        }
        signature = next; blocks = recognized
        // Names with a printed reading are found before the stability and height filters.
        val pairing = names.pair(recognized, translator.romanizes(), sample.width, sample.height,
            detail = if (unchanged) null else sample::recognizeRegion, clock = clock)
        val stable = stability.update(pairing.blocks, sample.height)
        val texts = stable.filter { it.literal == null }.map { it.text }.toSet()
        synchronized(lock) { held = stable; originals.retainAll(texts) }
        texts.forEach(::request)
        val chips = chips()
        val romanized = stable.count { it.literal != null }
        log(frameTextSampleLog(FrameTextSampleTiming(intervalMs, captureMs, ocrMs, recognized.size, stable.size, chips.size, romanized, sample.detailed)))
        if (pairing.stats.relevant) log(frameTextPairingLog(pairing.stats.copy(filteredSmall = stability.filteredSmall)))
        return chips
    }

    /** Held blocks with a romanized reading, or whose translation is ready. */
    fun chips(): List<FrameTextChip> = synchronized(lock) {
        held.mapNotNull { entry ->
            (entry.literal ?: translator.cached(entry.text))?.let { FrameTextChip(entry.id, entry.box, entry.lineHeight, it) }
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

    /**
     * Forgets every frame-derived value; the next sample starts fresh and text
     * must settle again. Used on detach and on any position discontinuity.
     */
    fun clear() {
        synchronized(lock) { held = emptyList(); pending.clear(); originals.clear() }
        signature = null; blocks = emptyList(); stability.clear(); names.clear()
    }
}
