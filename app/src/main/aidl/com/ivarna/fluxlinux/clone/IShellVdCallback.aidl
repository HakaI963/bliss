package com.ivarna.fluxlinux.clone;

oneway interface IShellVdCallback {
    /**
     * One still frame of the clone display, captured on demand by swapping an
     * ImageReader surface into the VirtualDisplay (ShadowAuto's model). JPEG
     * bytes; null/empty when the display produced no frame.
     */
    void onScreenshot(in byte[] data, int width, int height);
    void onLog(String line);
}
