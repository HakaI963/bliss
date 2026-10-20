package com.ivarna.fluxlinux.clone;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
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
    final String contextSource;

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

    private ShadowDisplay(int width, int height, int dpi, int displayId, String contextSource,
                          ImageReader captureReader, VirtualDisplay display, int flagsUsed) {
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.flagsUsed = flagsUsed;
        this.contextSource = contextSource;
        this.captureReader = captureReader;
        this.captureSurface = captureReader.getSurface();
        this.display = display;
        this.displayId = displayId;

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
        return create(null, width, height, dpi);
    }

    /**
     * Create the display with the real Shizuku service context first. If that
     * ROM refuses it, retry with the fabricated shell context. The returned
     * error names every context/flag combination that was tried.
     */
    static ShadowDisplay create(android.content.Context base, int width, int height, int dpi) {
        android.content.Context[] contexts = base != null
                ? new android.content.Context[]{base, null}
                : new android.content.Context[]{null};
        Throwable lastError = null;
        StringBuilder attempts = new StringBuilder();
        for (android.content.Context candidate : contexts) {
            ShadowShellContext context;
            DisplayManager manager;
            try {
                context = ShadowShellContext.from(candidate);
                manager = displayManager(context);
            } catch (Throwable e) {
                lastError = e;
                attempts.append("context:").append(describe(e)).append("; ");
                continue;
            }
            if (manager == null) {
                lastError = new IllegalStateException("DISPLAY_SERVICE unavailable");
                attempts.append("no DisplayManager; ");
                continue;
            }
            attempts.append("[").append(context.describeSource()).append("] ");
            ImageReader captureReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            Surface readerSurface = captureReader.getSurface();
            SurfaceTexture dummyTexture = null;
            Surface dummySurface = null;
            boolean handedOver = false;
            try {
                int baseFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                        | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                        | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
                int publicOnly = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
                int ownContent = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
                int touchFlag = hiddenFlag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH");
                int focusFlag = hiddenFlag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS");
                int trustedFlag = hiddenFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED");
                // TRUSTED is only the very last resort: some ROMs (HyperOS)
                // treat it as secure, which stops frames from reaching the
                // capture surface. Simpler flag sets are tried first because
                // some ROMs reject PRESENTATION/OWN_CONTENT_ONLY outright.
                int[] flagsToTry = new int[]{
                        baseFlags | touchFlag | focusFlag,
                        baseFlags | touchFlag,
                        baseFlags,
                        publicOnly | ownContent | touchFlag | focusFlag,
                        publicOnly | ownContent | touchFlag,
                        publicOnly | ownContent,
                        publicOnly,
                        baseFlags | touchFlag | focusFlag | trustedFlag,
                        baseFlags | trustedFlag
                };
                // Pass 0 attaches the capture reader at creation (this is what
                // works on HyperOS). Pass 1 mirrors ShadowAuto's own sequence:
                // create with a throw-away surface, then swap the real capture
                // surface in.
                for (int pass = 0; pass < 2; pass++) {
                    Surface creationSurface = readerSurface;
                    if (pass == 1) {
                        dummyTexture = new SurfaceTexture(0);
                        dummyTexture.setDefaultBufferSize(width, height);
                        dummySurface = new Surface(dummyTexture);
                        creationSurface = dummySurface;
                    }
                    for (int flags : flagsToTry) {
                        VirtualDisplay display = null;
                        try {
                            display = manager.createVirtualDisplay("Bliss", width, height, dpi,
                                    creationSurface, flags);
                            if (display == null) {
                                lastError = new IllegalStateException("createVirtualDisplay returned null");
                                attempts.append("0x").append(Integer.toHexString(flags)).append(":null; ");
                                continue;
                            }
                            android.view.Display screen = null;
                            for (int attempt = 0; attempt < 6 && screen == null; attempt++) {
                                try {
                                    screen = display.getDisplay();
                                } catch (Throwable e) {
                                    lastError = e;
                                }
                                if (screen == null) {
                                    SystemClock.sleep(50);
                                }
                            }
                            if (screen == null) {
                                lastError = new IllegalStateException("display created but getDisplay() stayed null");
                                attempts.append("0x").append(Integer.toHexString(flags)).append(":no-display; ");
                                try {
                                    display.release();
                                } catch (Throwable ignored) {
                                }
                                continue;
                            }
                            if (pass == 1) {
                                try {
                                    display.setSurface(readerSurface);
                                } catch (Throwable e) {
                                    lastError = e;
                                    attempts.append("0x").append(Integer.toHexString(flags))
                                            .append(":setSurface=").append(brief(e)).append("; ");
                                    try {
                                        display.release();
                                    } catch (Throwable ignored) {
                                    }
                                    continue;
                                }
                            }
                            ShadowDisplay session = new ShadowDisplay(width, height, dpi,
                                    screen.getDisplayId(), context.describeSource(),
                                    captureReader, display, flags);
                            handedOver = true;
                            return session;
                        } catch (Throwable e) {
                            lastError = e;
                            attempts.append("0x").append(Integer.toHexString(flags))
                                    .append(":").append(brief(e)).append("; ");
                            if (display != null) {
                                try {
                                    display.release();
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                    releaseQuietly(dummySurface);
                    dummySurface = null;
                    releaseQuietly(dummyTexture);
                    dummyTexture = null;
                }
            } finally {
                releaseQuietly(dummySurface);
                releaseQuietly(dummyTexture);
                if (!handedOver) {
                    try {
                        captureReader.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        String attemptText = attempts.toString();
        if (attemptText.length() > 1400) {
            attemptText = attemptText.substring(attemptText.length() - 1400);
        }
        throw new IllegalStateException("createVirtualDisplay failed after " + attemptText, lastError);
    }

    private static String brief(Throwable t) {
        String text = describe(t);
        return text.length() > 180 ? text.substring(0, 180) + "…" : text;
    }

    private static void releaseQuietly(SurfaceTexture texture) {
        if (texture == null) {
            return;
        }
        try {
            texture.release();
        } catch (Throwable ignored) {
        }
    }

    private static void releaseQuietly(Surface surface) {
        if (surface == null) {
            return;
        }
        try {
            surface.release();
        } catch (Throwable ignored) {
        }
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        int depth = 0;
        while (current != null && depth < 5) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(current.getClass().getSimpleName());
            if (current.getMessage() != null) {
                sb.append(": ").append(current.getMessage());
            }
            current = current.getCause();
            depth++;
        }
        return sb.toString();
    }

    /**
     * Latest composed frame, as an owned copy.
     *
     * <p>Waits up to {@code retries * delayMs} for a frame, and returns a copy
     * so callers never race the drain thread's recycle of the newest frame.
     * The caller owns (and should recycle) the returned bitmap.
     */
    Bitmap captureBitmap(int retries, long delayMs) {
        long deadline = SystemClock.uptimeMillis() + Math.max(1, retries) * Math.max(1, delayMs);
        while (true) {
            synchronized (frameLock) {
                Bitmap frame = latestFrame;
                // SurfaceFlinger only produces a new reader frame when the
                // display content changes, so the newest frame is the current
                // screen even when it is minutes old.
                if (frame != null && !frame.isRecycled()) {
                    try {
                        return frame.copy(Bitmap.Config.ARGB_8888, false);
                    } catch (Throwable ignored) {
                        // fall through and wait for/retry the next frame
                    }
                }
                if (SystemClock.uptimeMillis() >= deadline) {
                    return null;
                }
            }
            SystemClock.sleep(30);
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
