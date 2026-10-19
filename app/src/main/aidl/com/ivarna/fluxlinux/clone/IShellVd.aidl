package com.ivarna.fluxlinux.clone;

import com.ivarna.fluxlinux.clone.IShellVdCallback;

interface IShellVd {
    /** Writes bytes into a shell-readable path (staged installs and the engine APK). */
    long stageWrite(String path, long offset, in byte[] data);
    String pmInstall(in String[] paths, int userId);
    void deleteStage(in String[] paths);

    /**
     * Starts the vendored ShadowAuto engine as an app_process, with the staged
     * APK on its CLASSPATH. Returns null on success, otherwise the error.
     */
    String startEngine(String apkPath, int port);
    /** Stops a running engine process. */
    String stopEngine();

    /** Force-stops a package in the given user (clone app kill). */
    String kill(String pkg, int userId);

    /** Registers the callback used to push live service log lines. */
    void attach(IShellVdCallback cb);

    /** Recent service log lines, so the app can surface real shell-side errors. */
    String[] logs();
    void exit();
}
