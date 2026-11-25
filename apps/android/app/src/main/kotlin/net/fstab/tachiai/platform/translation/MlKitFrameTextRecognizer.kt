package net.fstab.tachiai.platform.translation

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import net.fstab.tachiai.presentation.FrameTextBlock
import net.fstab.tachiai.presentation.ViewerRect

// On-device text recognition for the video picture (#664), with ML Kit's
// bundled Japanese model (it also reads Latin text). Frames and recognized text
// stay in memory and are never logged, stored or sent. Created on first use
// while Translate text in video runs; the manifest removes ML Kit's startup
// provider. Every call runs on the caller's single worker thread.
internal class MlKitFrameTextRecognizer(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private var recognizer: TextRecognizer? = null

    /** Blocks until recognition of [frame] completes, so the caller may reuse the bitmap afterward. */
    fun recognize(frame: Bitmap): List<FrameTextBlock> {
        val client = recognizer ?: run {
            MlKitContext.initializeIfNeeded(context)
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()).also { recognizer = it }
        }
        val text = Tasks.await(client.process(InputImage.fromBitmap(frame, 0)))
        return text.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val heights = block.lines.mapNotNull { line -> line.boundingBox?.height() }
            if (heights.isEmpty()) return@mapNotNull null
            FrameTextBlock(block.lines.joinToString("\n") { it.text }, ViewerRect(box.left, box.top, box.width(), box.height()),
                heights.sum() / heights.size)
        }
    }

    override fun close() { recognizer?.close(); recognizer = null }
}
