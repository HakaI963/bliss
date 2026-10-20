package com.ivarna.fluxlinux.core.control

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** On-device OCR for the clone display. Returns text plus screen boxes. */
object OcrEngine {

    suspend fun recognize(bitmap: Bitmap): JSONObject = withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(recognizer.process(image), 30, TimeUnit.SECONDS)
            val blocks = JSONArray()
            for (block in result.textBlocks) {
                val lines = JSONArray()
                for (line in block.lines) {
                    lines.put(
                        JSONObject()
                            .put("text", line.text)
                            .put("box", box(line.boundingBox))
                    )
                }
                blocks.put(
                    JSONObject()
                        .put("text", block.text)
                        .put("box", box(block.boundingBox))
                        .put("lines", lines)
                )
            }
            JSONObject()
                .put("ok", true)
                .put("text", result.text)
                .put("blocks", blocks)
        } catch (t: Throwable) {
            JSONObject()
                .put("ok", false)
                .put("error", t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { recognizer.close() }
        }
    }

    private fun box(rect: android.graphics.Rect?): JSONObject {
        if (rect == null) return JSONObject()
        return JSONObject()
            .put("x", rect.left)
            .put("y", rect.top)
            .put("w", rect.width())
            .put("h", rect.height())
            .put("cx", rect.centerX())
            .put("cy", rect.centerY())
    }
}
