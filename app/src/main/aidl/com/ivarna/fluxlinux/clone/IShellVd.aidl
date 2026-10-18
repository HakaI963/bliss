package com.ivarna.fluxlinux.clone;

import com.ivarna.fluxlinux.clone.IShellVdCallback;

interface IShellVd {
    int createDisplay(int width, int height, int dpi);
    int displayId();
    boolean tap(int x, int y);
    boolean longPress(int x, int y, int ms);
    boolean swipe(int x1, int y1, int x2, int y2, int ms);
    boolean key(int keyCode);
    boolean text(String value);
    void startFrames(IShellVdCallback cb, int maxWidth, int jpegQuality, int fps);
    void stopFrames();
    void releaseDisplay();
    String launch(String pkg, int userId);
    String kill(String pkg, int userId);
    String info();
    void exit();
}
