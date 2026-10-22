package com.altaris.linux.ui.debug

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * TEMPORARY — development helper, removed before the final release.
 *
 * Captures the app window on its own (no system screenshot, no other
 * app in the frame) and either writes it to the app's external files
 * directory or hands it to the share sheet.
 *
 * Trigger it from a shell:
 *
 *     am start -n com.nopiece.bliss/com.altaris.linux.MainActivity \
 *         --ez altaris.capture true [--ez altaris.share true]
 */
object WindowCapture {

    const val EXTRA_CAPTURE = "altaris.capture"
    const val EXTRA_SHARE = "altaris.share"

    /** True when the intent asks for a capture. */
    fun wantsCapture(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_CAPTURE, false) == true

    /** True when the intent also asks for the share sheet. */
    fun wantsShare(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_SHARE, false) == true

    /**
     * Draws [view] into a bitmap and writes it to the external files dir.
     * Returns the file, or null when the view has no size yet.
     */
    fun capture(context: Context, view: View): File? {
        if (view.width <= 0 || view.height <= 0) return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        view.draw(canvas)
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "altaris-capture-${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file
    }

    /** Offers the captured file to the share sheet. */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".provider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share Altaris capture")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
