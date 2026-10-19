package com.ivarna.fluxlinux.clone;

import com.ivarna.fluxlinux.clone.IShellVdCallback;

interface IShellVd {
    /** Creates (or reuses) the clone virtual display. Returns the display id. */
    int createDisplay(int width, int height, int dpi);
    int displayId();

    /** Starts the still-frame stream; frames arrive through onScreenshot(). */
    void startFrames(IShellVdCallback cb, int maxWidth, int jpegQuality, int fps);
    void stopFrames();
    void releaseDisplay();

    boolean tap(int x, int y);
    boolean longPress(int x, int y, int ms);
    boolean swipe(int x1, int y1, int x2, int y2, int ms);
    boolean key(int keyCode);
    boolean text(String value);

    String launch(String pkg, int userId);
    String kill(String pkg, int userId);

    /** Writes bytes into a shell-readable path (staged installs). */
    long stageWrite(String path, long offset, in byte[] data);
    String pmInstall(in String[] paths, int userId);
    void deleteStage(in String[] paths);

    /** Registers the callback used to push live service log lines. */
    void attach(IShellVdCallback cb);

    /** Recent service log lines, so the app can surface real shell-side errors. */
    String[] logs();
    void exit();
}
