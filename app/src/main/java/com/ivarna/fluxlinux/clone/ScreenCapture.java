package com.ivarna.fluxlinux.clone;

import android.graphics.Bitmap;
import android.media.Image;

import java.nio.ByteBuffer;

/** Image -> Bitmap conversion. Ported from ShadowAuto's ScreenCapture. */
final class ScreenCapture {
    private ScreenCapture() {
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
