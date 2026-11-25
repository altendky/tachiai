package net.fstab.tachiai.platform.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import net.fstab.tachiai.presentation.FrameTextBlock
import net.fstab.tachiai.presentation.FrameTextElement
import net.fstab.tachiai.presentation.FrameTextLine
import net.fstab.tachiai.presentation.ViewerRect

// On-device text recognition for the video picture (#664), with ML Kit's
// bundled Japanese model (it also reads Latin text). Frames and recognized text
// stay in memory and are never logged, stored or sent. Created on first use
// while Translate text in video runs; the manifest removes ML Kit's startup
// provider. Every call runs on the caller's single worker thread.
internal class MlKitFrameTextRecognizer(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private var recognizer: TextRecognizer? = null

    // Blocks until recognition completes, so the caller may reuse the bitmap afterward.
    private fun read(frame: Bitmap): Text {
        val client = recognizer ?: run {
            MlKitContext.initializeIfNeeded(context)
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()).also { recognizer = it }
        }
        return Tasks.await(client.process(InputImage.fromBitmap(frame, 0)))
    }

    /** Blocks with their lines and elements, in [frame] pixels. */
    fun recognize(frame: Bitmap): List<FrameTextBlock> = read(frame).textBlocks.mapNotNull { block ->
        val box = block.boundingBox ?: return@mapNotNull null
        val lines = block.lines.mapNotNull { line -> lineOf(line) { it } }
        if (lines.isEmpty()) return@mapNotNull null
        FrameTextBlock(block.lines.joinToString("\n") { it.text }, box.toViewer { it }, lines.sumOf { it.box.height } / lines.size, lines)
    }

    /** Lines in [frame], each box mapped by [map] (for example from a crop back to sample pixels). */
    fun recognizeLines(frame: Bitmap, map: (ViewerRect) -> ViewerRect): List<FrameTextLine> =
        read(frame).textBlocks.flatMap { block -> block.lines.mapNotNull { line -> lineOf(line, map) } }

    private fun lineOf(line: Text.Line, map: (ViewerRect) -> ViewerRect): FrameTextLine? {
        val box = line.boundingBox ?: return null
        val elements = line.elements.mapNotNull { element ->
            element.boundingBox?.let { FrameTextElement(element.text, it.toViewer(map)) }
        }
        return FrameTextLine(line.text, box.toViewer(map), elements)
    }

    private fun Rect.toViewer(map: (ViewerRect) -> ViewerRect) = map(ViewerRect(left, top, width(), height()))

    override fun close() { recognizer?.close(); recognizer = null }
}
