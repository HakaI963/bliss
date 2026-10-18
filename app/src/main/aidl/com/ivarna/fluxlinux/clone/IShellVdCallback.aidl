package com.ivarna.fluxlinux.clone;

oneway interface IShellVdCallback {
    void onFrame(in byte[] jpeg, int width, int height);
    void onLog(String line);
}
