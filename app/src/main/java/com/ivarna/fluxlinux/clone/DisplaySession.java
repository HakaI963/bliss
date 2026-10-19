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
 * <p>Ported verbatim from ShadowAuto's {@code VirtualDisplaySession}: the
 * display is created with a <b>null output surface</b>. It exists for windows,
 * input injection and accessibility, not for a live video pipe. Still frames
 * are captured on demand by swapping an {@code RGBA_8888} ImageReader surface
 * in with {@link VirtualDisplay#setSurface}, taking one image, and swapping the
 * null surface back.
 *
 * <p>There is no MediaCodec here on purpose. The previous architecture made an
 * H.264 encoder input surface the display output and decoded it in the app;
 * that decoder is what repeatedly killed the client process, and it was never
 * how the reference implementation worked.
 *
 * <p>Flags are tried as a candidate ladder (hidden SUPPORTS_TOUCH / OWN_FOCUS /
 * TRUSTED first) so the display behaves like a real secondary screen. The IME
 * policy is set to LOCAL so the clone keyboard shows up on the clone display,
 * never on the phone screen.
 */
final class DisplaySession {
    final int displayId;
    final int width;
    final int height;
    final int dpi;

    private final ImageReader captureReader;
    private final Surface captureSurface;
    private Surface outputSurface;
    private final VirtualDisplay display;
    private final WindowManagerBridge windowManager; // optional: may be null on locked-down builds
    private final int previousImePolicy;
    private final boolean localImeEnabled;

    private DisplaySession(int width, int height, int dpi, ImageReader captureReader,
                           Surface outputSurface, VirtualDisplay display) {
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.captureReader = captureReader;
        this.captureSurface = captureReader.getSurface();
        this.outputSurface = outputSurface;
        this.display = display;
        this.displayId = display.getDisplay().getDisplayId();
        // IME policy is best-effort: a WindowManagerBridge failure must not
        // destroy a display that was just created.
        WindowManagerBridge bridge = null;
        int previous = -1;
        boolean imeEnabled = false;
        try {
            bridge = new WindowManagerBridge();
        } catch (Throwable ignored) {
        }
        if (bridge != null) {
            try {
                previous = bridge.getDisplayImePolicy(displayId);
            } catch (Throwable ignored) {
            }
            try {
                imeEnabled = bridge.setDisplayImePolicy(displayId, WindowManagerBridge.DISPLAY_IME_POLICY_LOCAL);
            } catch (Throwable ignored) {
            }
        }
        this.windowManager = bridge;
        this.previousImePolicy = previous;
        this.localImeEnabled = imeEnabled;
    }

    boolean localImeEnabled() {
        return localImeEnabled;
    }

    static DisplaySession create(android.content.Context context, int width, int height, int dpi) {
        DisplayManager manager = displayManager(context);
        ImageReader captureReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        int baseFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        int touchFlag = hiddenFlag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH");
        int focusFlag = hiddenFlag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS");
        int trustedFlag = hiddenFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED");
        int[] candidates = new int[]{
                baseFlags | touchFlag | focusFlag | trustedFlag,
                baseFlags | touchFlag | focusFlag,
                baseFlags | touchFlag,
                baseFlags
        };
        Throwable lastError = null;
        for (int flags : candidates) {
            try {
                // Null surface: the display has no live output. Windows still
                // compose into it, and captureBitmap() swaps a reader in.
                VirtualDisplay display = manager.createVirtualDisplay("Bliss", width, height, dpi,
                        null, flags);
                if (display != null && display.getDisplay() != null) {
                    return new DisplaySession(width, height, dpi, captureReader, null, display);
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
     * One still frame of the display: swap the capture surface in, wait for the
     * compositor to produce an image, then restore the previous output surface.
     * Returns null when nothing composed within the retry window.
     */
    synchronized Bitmap captureBitmap(int retries, long delayMs) {
        Surface restoreSurface = outputSurface;
        Image image = null;
        try {
            drainCaptureReader();
            display.setSurface(captureSurface);
            for (int i = 0; i <= retries; i++) {
                SystemClock.sleep(delayMs);
                image = captureReader.acquireLatestImage();
                if (image != null) {
                    return ScreenCapture.bitmapFromImage(image);
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Throwable ignored) {
                }
            }
            try {
                display.setSurface(restoreSurface);
                outputSurface = restoreSurface;
            } catch (Throwable ignored) {
            }
        }
    }

    private void drainCaptureReader() {
        while (true) {
            Image image;
            try {
                image = captureReader.acquireLatestImage();
            } catch (Throwable t) {
                return;
            }
            if (image == null) {
                return;
            }
            try {
                image.close();
            } catch (Throwable ignored) {
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
