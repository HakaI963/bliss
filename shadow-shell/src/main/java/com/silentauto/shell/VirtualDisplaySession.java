package com.silentauto.shell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.SystemClock;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Virtual display session — vendored from ShadowAuto (Apache-2.0) and adapted
 * for Bliss.
 *
 * <p>Bliss changes, both verified against HyperOS:
 *
 * <ol>
 *   <li><b>Non-secure flags.</b> {@code VIRTUAL_DISPLAY_FLAG_TRUSTED} is the
 *       secure flag on this ROM (the created display reports
 *       {@code FLAG_SECURE}). A secure display is never composited into a
 *       non-secure destination surface, so the capture reader attached to it
 *       received zero frames while SurfaceFlinger happily held the app's
 *       layers. The trusted flag is therefore not set.</li>
 *   <li><b>Surface attached at creation.</b> The capture reader's surface is
 *       passed to {@code createVirtualDisplay} instead of {@code null}: a
 *       null-surface display comes up in state OFF and never composites.</li>
 *   <li><b>Continuous drain.</b> A background thread drains the reader so the
 *       buffer queue never applies backpressure to the compositor, keeping the
 *       most recent frame available for {@link #captureBitmap}.</li>
 * </ol>
 */
final class VirtualDisplaySession {
    final int displayId;
    final int width;
    final int height;
    final int dpi;
    private final ImageReader captureReader;
    private final Surface captureSurface;
    private Surface outputSurface;
    private final VirtualDisplay display;
    private final WindowManagerBridge windowManager;
    private final int previousImePolicy;
    private final boolean localImeEnabled;

    private final Object frameLock = new Object();
    private Bitmap latestFrame;
    private long latestFrameAt;
    private volatile boolean draining = true;
    private final Thread drainThread;

    private VirtualDisplaySession(int width, int height, int dpi, ImageReader captureReader, Surface outputSurface, VirtualDisplay display) {
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.captureReader = captureReader;
        this.captureSurface = captureReader.getSurface();
        this.outputSurface = outputSurface;
        this.display = display;
        this.displayId = display.getDisplay().getDisplayId();
        this.windowManager = new WindowManagerBridge();
        this.previousImePolicy = windowManager.getDisplayImePolicy(displayId);
        this.localImeEnabled = windowManager.setDisplayImePolicy(displayId, WindowManagerBridge.DISPLAY_IME_POLICY_LOCAL);
        this.drainThread = new Thread(this::drainLoop, "shadow-drain");
        this.drainThread.setDaemon(true);
        this.drainThread.start();
    }

    boolean localImeEnabled() {
        return localImeEnabled;
    }

    static VirtualDisplaySession create(int width, int height, int dpi, Surface outputSurface) {
        DisplayManager manager = displayManager();
        ImageReader captureReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        int baseFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        int touchFlag = hiddenFlag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH");
        int focusFlag = hiddenFlag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS");
        // NOTE (Bliss): no VIRTUAL_DISPLAY_FLAG_TRUSTED — on HyperOS that is the
        // secure flag, and a secure display never composites into a
        // non-secure capture surface (zero frames, screencap refused).
        int[] candidates = new int[]{
                baseFlags | touchFlag | focusFlag,
                baseFlags | touchFlag,
                baseFlags
        };
        Throwable lastError = null;
        for (int flags : candidates) {
            try {
                // The capture surface is attached at creation: a null-surface
                // display comes up OFF and never composites on this ROM.
                Surface target = outputSurface != null ? outputSurface : captureReader.getSurface();
                VirtualDisplay display = manager.createVirtualDisplay("ShadowAuto", width, height, dpi, target, flags);
                if (display != null && display.getDisplay() != null) {
                    return new VirtualDisplaySession(width, height, dpi, captureReader, target, display);
                }
            } catch (Throwable e) {
                lastError = e;
            }
        }
        captureReader.close();
        throw new IllegalStateException("createVirtualDisplay failed", lastError);
    }

    /** Latest composed frame; waits up to (retries+1)*delayMs for a fresh one. */
    synchronized Bitmap captureBitmap(int retries, long delayMs) {
        long deadline = SystemClock.uptimeMillis() + Math.max(0, retries) * Math.max(1, delayMs);
        long since = 0;
        while (true) {
            Bitmap frame = null;
            synchronized (frameLock) {
                if (latestFrame != null && !latestFrame.isRecycled()) {
                    frame = latestFrame;
                    since = SystemClock.uptimeMillis() - latestFrameAt;
                }
            }
            if (frame != null && (since < 400 || SystemClock.uptimeMillis() >= deadline)) {
                return frame;
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                return frame;
            }
            SystemClock.sleep(40);
        }
    }

    private void drainLoop() {
        while (draining) {
            Image image = null;
            try {
                image = captureReader.acquireLatestImage();
                if (image == null) {
                    SystemClock.sleep(30);
                    continue;
                }
                Bitmap bitmap = ScreenCapture.bitmapFromImage(image);
                if (bitmap != null) {
                    synchronized (frameLock) {
                        Bitmap old = latestFrame;
                        latestFrame = bitmap;
                        latestFrameAt = SystemClock.uptimeMillis();
                        if (old != null && old != bitmap) {
                            try {
                                old.recycle();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (image != null) {
                    try {
                        image.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static DisplayManager displayManager() {
        try {
            Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            return ctor.newInstance(ShellContext.get());
        } catch (Throwable ignored) {
            return (DisplayManager) ShellContext.get().getSystemService(Context.DISPLAY_SERVICE);
        }
    }

    void release() {
        draining = false;
        try {
            if (previousImePolicy != -1) {
                windowManager.setDisplayImePolicy(displayId, previousImePolicy);
            }
        } catch (Throwable ignored) {
        }
        try {
            display.release();
        } catch (Throwable ignored) {
        }
        try {
            captureSurface.release();
        } catch (Throwable ignored) {
        }
        try {
            captureReader.close();
        } catch (Throwable ignored) {
        }
        synchronized (frameLock) {
            if (latestFrame != null) {
                try {
                    latestFrame.recycle();
                } catch (Throwable ignored) {
                }
                latestFrame = null;
            }
        }
    }

    private static int hiddenFlag(String name) {
        try {
            Field field = DisplayManager.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
