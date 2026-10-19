package com.ivarna.fluxlinux.clone;

oneway interface IShellVdCallback {
    /** Encoded H.264 access unit from the display's encoder surface. */
    void onVideoSample(in byte[] data, long ptsUs, boolean keyframe, boolean format, int width, int height);
    void onLog(String line);
}
