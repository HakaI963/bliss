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

    /** Recent service log lines, so the app can surface real shell-side errors. */
    String[] logs();

    // Staged installs: the app uploads APK bytes over binder into /data/local/tmp
    // (no stdin piping), then the shell runs one pm install for them.
    long stageWrite(String path, long offset, in byte[] data);
    String pmInstall(in String[] paths, int userId);
    void deleteStage(in String[] paths);
}
