package net.fstab.tachiai.feature.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.createBitmap
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.fstab.tachiai.platform.translation.MlKitFrameTextRecognizer
import net.fstab.tachiai.presentation.FRAME_TEXT_LOG_TAG
import net.fstab.tachiai.presentation.FrameTextChip
import net.fstab.tachiai.presentation.FrameTextPipeline
import net.fstab.tachiai.presentation.FrameTextResetCause
import net.fstab.tachiai.presentation.FrameTextRun
import net.fstab.tachiai.presentation.FrameTextSample
import net.fstab.tachiai.presentation.FrameTextTranslator
import net.fstab.tachiai.presentation.FrameTextTuning
import net.fstab.tachiai.presentation.ProviderTextSource
import net.fstab.tachiai.presentation.ViewerInsets
import net.fstab.tachiai.presentation.ViewerRect
import net.fstab.tachiai.presentation.frameSignature
import net.fstab.tachiai.presentation.frameTextClearArea
import net.fstab.tachiai.presentation.frameTextPositionJumped
import net.fstab.tachiai.presentation.frameTextResetLog
import net.fstab.tachiai.presentation.frameTextResetsOn
import net.fstab.tachiai.presentation.frameTextSampleSize
import net.fstab.tachiai.presentation.frameTextStateLog
import net.fstab.tachiai.presentation.mapFrameTextBox
import net.fstab.tachiai.presentation.placeFrameTextChip
import net.fstab.tachiai.presentation.translationTargetLanguage

// Translate text in video (#664): chips over a pane's video, each replacing
// its source text block. The layer sits above the video surface and below the
// pane's notice; chips stay inside the visible overlay's insets (#656) and
// never take focus or touches.
@UnstableApi
@SuppressLint("ViewConstructor")
internal class FrameTextLayer(context: Context, private val player: PlayerView) : FrameLayout(context) {
    var insets = ViewerInsets()
        set(value) { if (field != value) { field = value; requestLayout() } }
    private var chips: List<FrameTextChip> = emptyList()
    private var frameWidth = 0
    private var frameHeight = 0
    private val views = mutableMapOf<Long, TextView>()
    private val targets = mutableMapOf<Long, ViewerRect>()
    private var measuredVideo: ViewerRect? = null
    private fun dp(value: Int) = value * resources.displayMetrics.density
    private fun sp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value.toFloat(), resources.displayMetrics)

    init {
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** Shows [next] (in sampled-frame pixels of [width] × [height]); chips no longer present are removed. */
    fun show(next: List<FrameTextChip>, width: Int, height: Int) {
        frameWidth = width; frameHeight = height; chips = next
        val ids = next.map { it.id }.toSet()
        views.keys.filter { it !in ids }.forEach { id -> views.remove(id)?.let(::removeView) }
        next.forEach { chip ->
            val view = views.getOrPut(chip.id) { chipView().also(::addView) }
            if (view.text.toString() != chip.text) view.text = chip.text
        }
        requestLayout()
    }

    /** Removes every chip and forgets its text. */
    fun clear() {
        if (chips.isEmpty() && views.isEmpty()) return
        chips = emptyList(); targets.clear(); views.clear(); removeAllViews()
    }

    private fun chipView() = TextView(context).apply {
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(CHIP_BACKGROUND); cornerRadius = dp(4) }
        val horizontal = dp(6).toInt(); val vertical = dp(2).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
        gravity = Gravity.CENTER
        maxLines = 4; ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        isFocusable = false; isClickable = false
    }

    // The video surface inside the PlayerView, which is letterboxed to the video's aspect.
    private fun videoRect(): ViewerRect? {
        val surface = player.videoSurfaceView ?: return null
        if (surface.width <= 0 || surface.height <= 0) return null
        val rect = Rect(0, 0, surface.width, surface.height)
        player.offsetDescendantRectToMyCoords(surface, rect)
        return ViewerRect(player.left + rect.left, player.top + rect.top, rect.width(), rect.height())
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec); val height = MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(width, height)
        targets.clear()
        val video = videoRect().also { measuredVideo = it }
        val clear = frameTextClearArea(width, height, insets)
        chips.forEach { chip ->
            val view = views[chip.id] ?: return@forEach
            if (video == null || frameWidth <= 0 || frameHeight <= 0 || clear.width <= 0 || clear.height <= 0) {
                view.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY))
                return@forEach
            }
            val target = mapFrameTextBox(chip.box, frameWidth, frameHeight, video)
            // Roughly the source's text height, within readable bounds.
            val size = (chip.lineHeight * video.height.toFloat() / frameHeight * 0.75f).coerceIn(sp(12), sp(32))
            if (view.textSize != size) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            // At least the source block's size, so the chip covers it.
            view.minWidth = target.width.coerceAtMost(clear.width)
            view.minHeight = target.height.coerceAtMost(clear.height)
            view.measure(MeasureSpec.makeMeasureSpec(clear.width, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(clear.height, MeasureSpec.AT_MOST))
            targets[chip.id] = target
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val clear = frameTextClearArea(width, height, insets)
        chips.forEach { chip ->
            val view = views[chip.id] ?: return@forEach
            val placed = targets[chip.id]?.let { placeFrameTextChip(it, view.measuredWidth, view.measuredHeight, clear) }
            if (placed == null) view.layout(0, 0, 0, 0)
            else view.layout(placed.left, placed.top, placed.left + placed.width, placed.top + placed.height)
        }
        // The surface is laid out just before this layer; remeasure if it moved.
        if (chips.isNotEmpty() && videoRect() != measuredVideo) post { requestLayout() }
    }

    private companion object {
        const val CHIP_BACKGROUND = 0xEB101418.toInt() // Near-opaque, so the source text is replaced.
    }
}

/**
 * Samples one pane's video about every 2 s while Translate text in video runs
 * for it. The capture runs on the main thread into a reused bitmap; hashing,
 * recognition and stability run on one worker thread, which holds the bitmap
 * until it is done; translations run off the main thread. Frames, recognized
 * text and translations stay in memory and are cleared on detach and close.
 * Only [frameTextStateLog], sample timings and counts are logged.
 */
@UnstableApi
internal class FrameTextRunner(context: Context) {
    private val context = context.applicationContext
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val translations = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "TachiaiFrameText") }
    private val workerDispatcher = worker.asCoroutineDispatcher()
    // Worker thread only.
    private var recognizer: MlKitFrameTextRecognizer? = null
    private var pixels = IntArray(0)

    private class Attached(val key: Any, val player: PlayerView, val layer: FrameTextLayer,
        val source: ProviderTextSource, val pipeline: FrameTextPipeline) {
        // Main thread only; the worker reads it only inside a sample.
        var frame: Bitmap? = null
        var loop: Job? = null
        // Position discontinuities (#664): any jump drops held text and chips.
        lateinit var listener: Player.Listener
        var watched: Player? = null
        var generation = 0L
        var shownGeneration = 0L
        var lastPositionMs = 0L
        var lastAtMs = 0L
        var lastPlaying = false
    }
    private var attached: Attached? = null
    private var closed = false
    private val window = Timeline.Window()

    private fun log(line: String) { Log.d(FRAME_TEXT_LOG_TAG, line) }

    fun attach(key: Any, player: PlayerView, layer: FrameTextLayer, source: ProviderTextSource) {
        val current = attached
        if (closed || current != null && current.key == key && current.player === player && current.layer === layer &&
            current.source == source) return
        detach()
        lateinit var state: Attached
        val pipeline = FrameTextPipeline(translator(source), translations, ::log, SystemClock::elapsedRealtime) {
            main.launch {
                val frame = state.frame
                // After a reset, wait for the next sample's result rather than showing stale text.
                if (attached === state && frame != null && state.shownGeneration == state.generation)
                    layer.show(state.pipeline.chips(), frame.width, frame.height)
            }
        }
        state = Attached(key, player, layer, source, pipeline)
        state.listener = object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (frameTextResetsOn(reason)) reset(state, FrameTextResetCause.DISCONTINUITY)
            }
        }
        attached = state
        watch(state)
        state.loop = main.launch { sampleLoop(state) }
    }

    /**
     * Drops every held block and chip for this feed after a seek, skip, Back
     * to live, timing shift, resume at a new position or other jump; text must
     * settle again before new chips appear. A sample in progress is discarded.
     */
    private fun reset(state: Attached, cause: FrameTextResetCause) {
        if (attached !== state) return
        state.generation++
        state.layer.clear()
        worker.execute { state.pipeline.clear() }
        log(frameTextResetLog(cause))
    }

    // Listens to the feed's current player, on the main looper only.
    private fun watch(state: Attached) {
        val player = state.player.player?.takeIf { it.applicationLooper == Looper.getMainLooper() }
        if (player === state.watched) return
        val previous = state.watched
        previous?.removeListener(state.listener)
        state.watched = player
        player?.addListener(state.listener)
        state.lastAtMs = 0L
        if (previous != null) reset(state, FrameTextResetCause.PLAYER_CHANGED)
    }

    // A position in the period, so a live window sliding forward is not a jump.
    private fun periodPositionMs(player: Player): Long {
        val timeline = player.currentTimeline
        val offset = if (timeline.isEmpty || player.currentMediaItemIndex >= timeline.windowCount) 0L
            else timeline.getWindow(player.currentMediaItemIndex, window).positionInFirstPeriodMs
        return player.currentPosition + offset
    }

    // Catches jumps that report no seek, such as a resume after a hold.
    private fun checkPositionJump(state: Attached) {
        val player = state.watched ?: return
        val position = periodPositionMs(player)
        val at = SystemClock.elapsedRealtime()
        val playing = player.isPlaying
        if (state.lastAtMs != 0L && frameTextPositionJumped(state.lastPositionMs, state.lastAtMs, position, at,
                playing && state.lastPlaying)) reset(state, FrameTextResetCause.POSITION_JUMP)
        state.lastPositionMs = position; state.lastAtMs = at; state.lastPlaying = playing
    }

    /** Stops sampling, removes the chips and clears the frame and every derived value. */
    fun detach() {
        val state = attached ?: return
        attached = null
        state.loop?.cancel(); state.loop = null
        state.watched?.removeListener(state.listener); state.watched = null
        state.layer.clear()
        val frame = state.frame
        state.frame = null
        // Queued after any sample in progress, which keeps the bitmap until it returns.
        worker.execute { state.pipeline.clear(); frame?.recycle(); pixels.fill(0) }
        log(frameTextStateLog(FrameTextRun.OFF))
    }

    fun close() {
        if (closed) return
        detach()
        closed = true
        main.cancel(); translations.cancel()
        worker.execute { recognizer?.close(); recognizer = null; pixels = IntArray(0) }
        worker.shutdown()
    }

    private suspend fun sampleLoop(state: Attached) {
        var lastStart = 0L
        var logged = false
        while (main.isActive) {
            delay(FrameTextTuning.SAMPLE_INTERVAL_MS)
            watch(state)
            checkPositionJump(state)
            val surface = state.player.videoSurfaceView as? TextureView ?: continue
            if (!state.player.isShown || !surface.isAvailable) continue
            val (width, height) = frameTextSampleSize(surface.width, surface.height) ?: continue
            if (!logged) { log(frameTextStateLog(FrameTextRun.RUNNING, width, height)); logged = true }
            try {
                val started = SystemClock.elapsedRealtime()
                val frame = state.frame?.takeIf { it.width == width && it.height == height } ?: run {
                    // Between samples the worker holds no reference to the old frame.
                    state.frame?.recycle()
                    createBitmap(width, height).also { state.frame = it }
                }
                surface.getBitmap(frame)
                val captureMs = SystemClock.elapsedRealtime() - started
                val interval = if (lastStart == 0L) 0L else started - lastStart
                lastStart = started
                val generation = state.generation
                val chips = withContext(workerDispatcher) { state.pipeline.process(frameSample(frame), captureMs, interval) }
                // A reset during the sample means this frame may predate the jump.
                if (attached === state && state.generation == generation) {
                    state.layer.show(chips, width, height)
                    state.shownGeneration = generation
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                // No exception text: it could describe frame content.
                log("sample result=FAILED")
            }
        }
    }

    // Called on the worker thread.
    private fun frameSample(frame: Bitmap) = object : FrameTextSample {
        override val width = frame.width
        override val height = frame.height
        override fun signature(): ByteArray {
            val count = width * height
            if (pixels.size < count) pixels = IntArray(count)
            frame.getPixels(pixels, 0, width, 0, 0, width, height)
            return frameSignature(pixels, width, height)
        }
        override fun recognize() =
            (recognizer ?: MlKitFrameTextRecognizer(context).also { recognizer = it }).recognize(frame)
    }

    // The feed's Source language with the device language as target. Uses its
    // own cache; the Translate provider text switch does not apply here.
    private fun translator(source: ProviderTextSource): FrameTextTranslator {
        val translator = ProviderTextTranslation.frameTranslator(context)
        return object : FrameTextTranslator {
            private fun request(text: String) = ProviderTextTranslation.settings.value
                .languageRequest(text, source, translationTargetLanguage(Locale.getDefault()))
            override fun cached(text: String) = request(text)?.let(translator::cached)
            override suspend fun translate(text: String) = request(text)?.let { translator.translate(it) }
            // Readings are romanized unless the device language is Japanese.
            override fun romanizes() = translationTargetLanguage(Locale.getDefault()) != "ja"
        }
    }
}
