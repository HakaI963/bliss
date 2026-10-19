package com.ivarna.fluxlinux.clone;

oneway interface IShellVdCallback {
    /**
     * One still frame of the clone display (JPEG bytes), captured by the
     * shell-side display owner.
     */
    void onScreenshot(in byte[] jpeg, int width, int height);
    void onLog(String line);
}
