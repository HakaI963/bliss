package com.ivarna.fluxlinux.clone;

import android.graphics.Bitmap;
import android.media.Image;

import java.nio.ByteBuffer;

/**
 * Image -> Bitmap conversion.
 *
 * <p>Ported verbatim from ShadowAuto's {@code ScreenCapture} (Apache-2.0) —
 * see shadow-shell/LICENSE. The rowStride/pixelStride handling is what makes
 * the capture reader's padded rows crop back to the display's real size.
 */
final class ShadowScreenCapture {
    private ShadowScreenCapture() {
    }

        static Bitmap bitmapFromImage(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int bitmapWidth = rowStride / pixelStride;

        Bitmap padded = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        padded.recycle();
        return cropped;
    }
}
