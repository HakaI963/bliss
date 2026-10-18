package com.ivarna.fluxlinux.core.clone

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * On-device OCR over the clone display frames (ML Kit, bundled model).
 * Used by the agent bridge (`clone ocr` / MCP bliss_clone_ocr) so text-only
 * models can still "see" a cloned app without any network round-trip.
 */
object CloneOcr {

    data class Line(val text: String, val x: Int, val y: Int, val w: Int, val h: Int)

    /** Blocking recognition (runs on IO). Returns null when recognition failed. */
    suspend fun recognize(bitmap: Bitmap): List<Line>? = withContext(Dispatchers.IO) {
        runCatching {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(recognizer.process(image), 25, TimeUnit.SECONDS)
            val out = ArrayList<Line>()
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val r = line.boundingBox ?: continue
                    out.add(Line(line.text, r.left, r.top, r.width(), r.height()))
                }
            }
            out
        }.getOrNull()
    }
}
