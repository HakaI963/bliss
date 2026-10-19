package com.ivarna.fluxlinux.clone;

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
 * The clone display, owned by the shell (uid 2000) process.
 *
 * <p>Ported from ShadowAuto's {@code VirtualDisplaySession} (Apache-2.0) —
 * see shadow-shell/LICENSE. Two HyperOS-specific deviations, both verified on
 * the device:
 *
 * <ol>
 *   <li><b>No {@code VIRTUAL_DISPLAY_FLAG_TRUSTED}.</b> On this ROM the
 *       trusted flag <i>is</i> the secure flag: the created display reports
 *       {@code FLAG_SECURE}. A secure display is never composited into a
 *       non-secure destination surface, so every capture path (ImageReader,
 *       MediaCodec encoder surface, screencap) saw zero frames while the
 *       display sat ON with the app's layers present in SurfaceFlinger — and
 *       the launched app never got past its splash screen.</li>
 *   <li><b>The capture surface is attached at creation.</b> A display created
 *       with a null output surface comes up in state OFF on this ROM and
 *       never composites at all. The reader is drained continuously by a
 *       background thread, so the buffer queue never applies backpressure to
 *       the compositor, and the most recent frame is kept for capture.</li>
 * </ol>
 */
final class ShadowDisplay {
    final int displayId;
    final int width;
    final int height;
    final int dpi;
    final int flagsUsed;

    private final ImageReader captureReader;
    private final Surface captureSurface;
    private final VirtualDisplay display;
    private final ShadowWindowManager windowManager;
    private final int previousImePolicy;
    private final boolean localImeEnabled;

    private final Object frameLock = new Object();
    private Bitmap latestFrame;
    private long latestFrameAt;
    private long framesProduced;
    private volatile boolean draining = true;
    private final Thread drainThread;

    private ShadowDisplay(int width, int height, int dpi, ImageReader captureReader,
                          VirtualDisplay display, int flagsUsed) {
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.flagsUsed = flagsUsed;
        this.captureReader = captureReader;
        this.captureSurface = captureReader.getSurface();
        this.display = display;
        this.displayId = display.getDisplay().getDisplayId();

        ShadowWindowManager bridge = null;
        int previous = -1;
        boolean imeEnabled = false;
        try {
            bridge = new ShadowWindowManager();
        } catch (Throwable ignored) {
        }
        if (bridge != null) {
            try {
                previous = bridge.getDisplayImePolicy(displayId);
            } catch (Throwable ignored) {
            }
            try {
                imeEnabled = bridge.setDisplayImePolicy(displayId, ShadowWindowManager.DISPLAY_IME_POLICY_LOCAL);
            } catch (Throwable ignored) {
            }
        }
        this.windowManager = bridge;
        this.previousImePolicy = previous;
        this.localImeEnabled = imeEnabled;

        this.drainThread = new Thread(this::drainLoop, "shadow-drain");
        this.drainThread.setDaemon(true);
        this.drainThread.start();
    }

    boolean localImeEnabled() {
        return localImeEnabled;
    }

    static ShadowDisplay create(int width, int height, int dpi) {
        android.content.Context context = ShadowShellContext.get();
        DisplayManager manager = displayManager(context);
        ImageReader captureReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        int baseFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        int touchFlag = hiddenFlag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH");
        int focusFlag = hiddenFlag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS");
        // NOTE: VIRTUAL_DISPLAY_FLAG_TRUSTED is intentionally NOT set — on
        // HyperOS it is the secure flag and a secure display never composites
        // into our capture surface.
        int[] candidates = new int[]{
                baseFlags | touchFlag | focusFlag,
                baseFlags | touchFlag,
                baseFlags
        };
        Throwable lastError = null;
        for (int flags : candidates) {
            try {
                VirtualDisplay display = manager.createVirtualDisplay("Bliss", width, height, dpi,
                        captureReader.getSurface(), flags);
                if (display != null && display.getDisplay() != null) {
                    return new ShadowDisplay(width, height, dpi, captureReader, display, flags);
                }
            } catch (Throwable e) {
                lastError = e;
            }
        }
        try {
            captureReader.close();
        } catch (Throwable ignored) {
        }
        throw new IllegalStateException("createVirtualDisplay failed", lastError);
    }

    /**
     * Latest composed frame. Waits up to (retries+1)*delayMs for a frame
     * newer than the one handed out last time, so callers get a fresh picture.
     */
    Bitmap captureBitmap(int retries, long delayMs) {
        long deadline = SystemClock.uptimeMillis() + Math.max(0, retries) * Math.max(1, delayMs);
        Bitmap previous = null;
        while (true) {
            Bitmap frame = null;
            long age;
            synchronized (frameLock) {
                if (latestFrame != null && !latestFrame.isRecycled()) {
                    frame = latestFrame;
                    age = SystemClock.uptimeMillis() - latestFrameAt;
                } else {
                    age = Long.MAX_VALUE;
                }
            }
            if (frame != null && (frame != previous || age > 400 || SystemClock.uptimeMillis() >= deadline)) {
                return frame;
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                return frame;
            }
            previous = frame;
            SystemClock.sleep(40);
        }
    }

    long framesProduced() {
        synchronized (frameLock) {
            return framesProduced;
        }
    }

    private void drainLoop() {
        long lastConvertAt = 0;
        while (draining) {
            Image image = null;
            try {
                image = captureReader.acquireLatestImage();
                if (image == null) {
                    SystemClock.sleep(30);
                    continue;
                }
                // Convert at most ~5 frames/second: the display renders at
                // 60 fps and every conversion allocates a full-res bitmap.
                long now = SystemClock.uptimeMillis();
                if (now - lastConvertAt < 200) {
                    continue;
                }
                lastConvertAt = now;
                Bitmap bitmap = ShadowScreenCapture.bitmapFromImage(image);
                if (bitmap != null) {
                    synchronized (frameLock) {
                        Bitmap old = latestFrame;
                        latestFrame = bitmap;
                        latestFrameAt = now;
                        framesProduced++;
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

    private static DisplayManager displayManager(android.content.Context context) {
        try {
            Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(android.content.Context.class);
            ctor.setAccessible(true);
            return ctor.newInstance(context);
        } catch (Throwable ignored) {
            return (DisplayManager) context.getSystemService(android.content.Context.DISPLAY_SERVICE);
        }
    }

    void release() {
        draining = false;
        try {
            if (previousImePolicy != -1 && windowManager != null) {
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
