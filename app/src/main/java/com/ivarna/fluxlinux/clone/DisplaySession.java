package com.ivarna.fluxlinux.clone;

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
 * The clone display, owned by the shell (uid 2000) process.
 *
 * Ported from ShadowAuto's VirtualDisplaySession: the display is created
 * with no persistent scanout surface; captures swap the ImageReader surface
 * in on demand (setSurface -> grab -> setSurface(null)). A permanently
 * attached reader produces no frames at all on some OEM builds. Flags are
 * tried as a candidate ladder (hidden SUPPORTS_TOUCH / OWN_FOCUS / TRUSTED
 * first) so the display behaves like a real secondary screen. The IME policy
 * is set to LOCAL so the clone keyboard shows up on the clone display, never
 * on the phone screen.
 */
final class DisplaySession {
    final int displayId;
    final int width;
    final int height;
    final int dpi;

    private final ImageReader reader;
    private final Surface readerSurface;
    private final VirtualDisplay display;
    private final WindowManagerBridge windowManager; // optional: may be null on locked-down builds
    private final int previousImePolicy;
    private final boolean localImeEnabled;

    private DisplaySession(int width, int height, int dpi, ImageReader reader, VirtualDisplay display) {
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.reader = reader;
        this.readerSurface = reader.getSurface();
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
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        DisplayManager manager = displayManager(context);
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
        // Shadow model: the display is created with NO persistent scanout
        // surface. A permanently attached ImageReader never receives a single
        // composed frame on some OEM builds; swapping the reader surface in on
        // demand (setSurface -> capture -> setSurface(null)) always does.
        for (int flags : candidates) {
            try {
                VirtualDisplay display = manager.createVirtualDisplay("FluxClone", width, height, dpi, null, flags);
                if (display != null && display.getDisplay() != null) {
                    return new DisplaySession(width, height, dpi, reader, display);
                }
            } catch (Throwable e) {
                lastError = e;
            }
        }
        try {
            reader.close();
        } catch (Throwable ignored) {
        }
        throw new IllegalStateException("createVirtualDisplay failed", lastError);
    }

    /**
     * Swaps the reader surface in, waits for SurfaceFlinger to compose the
     * current content into it, and returns the latest frame as a Bitmap (null
     * when nothing arrived). The display's output is restored to null before
     * returning — exactly the reference engine's capture model.
     */
    synchronized Bitmap captureBitmap(int retries, long delayMs) {
        Image image = null;
        try {
            display.setSurface(readerSurface);
        } catch (Throwable t) {
            return null;
        }
        try {
            for (int i = 0; i <= retries; i++) {
                if (delayMs > 0) {
                    SystemClock.sleep(delayMs);
                }
                image = reader.acquireLatestImage();
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
                display.setSurface(null);
            } catch (Throwable ignored) {
            }
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
            reader.close();
        } catch (Throwable ignored) {
        }
    }

    private static DisplayManager displayManager(android.content.Context context) {
        try {
            Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            return ctor.newInstance(context);
        } catch (Throwable ignored) {
            return (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
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
