package com.ivarna.fluxlinux.clone;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * The clone display, owned by the shell (uid 2000) process.
 *
 * Ported from ShadowAuto's VirtualDisplaySession, adapted to a continuous
 * frame pump: the ImageReader surface is the display output for the whole
 * lifetime of the display, and the service reads the latest composed frame
 * from it. Flags are tried as a candidate ladder (hidden SUPPORTS_TOUCH /
 * OWN_FOCUS / TRUSTED first) so the display behaves like a real secondary
 * screen across Android versions. The IME policy is set to LOCAL so the clone
 * keyboard shows up on the clone display, never on the phone screen.
 */
final class DisplaySession {
    final int displayId;
    final int width;
    final int height;
    final int dpi;

    private final ImageReader reader;
    private final Surface readerSurface;
    private final VirtualDisplay display;
    private final WindowManagerBridge windowManager;
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
        this.windowManager = new WindowManagerBridge();
        this.previousImePolicy = windowManager.getDisplayImePolicy(displayId);
        this.localImeEnabled = windowManager.setDisplayImePolicy(displayId, WindowManagerBridge.DISPLAY_IME_POLICY_LOCAL);
    }

    boolean localImeEnabled() {
        return localImeEnabled;
    }

    static DisplaySession create(ShellContext context, int width, int height, int dpi) {
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        Surface surface = reader.getSurface();
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
        for (int flags : candidates) {
            try {
                VirtualDisplay display = manager.createVirtualDisplay("FluxClone", width, height, dpi, surface, flags);
                if (display != null && display.getDisplay() != null) {
                    return new DisplaySession(width, height, dpi, reader, display);
                }
            } catch (Throwable e) {
                lastError = e;
            }
        }
        try {
            surface.release();
        } catch (Throwable ignored) {
        }
        try {
            reader.close();
        } catch (Throwable ignored) {
        }
        throw new IllegalStateException("createVirtualDisplay failed", lastError);
    }

    /** Latest composed frame, or null when the display has nothing new. Caller must close. */
    Image acquireLatestImage() {
        return reader.acquireLatestImage();
    }

    void release() {
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
            readerSurface.release();
        } catch (Throwable ignored) {
        }
        try {
            reader.close();
        } catch (Throwable ignored) {
        }
    }

    private static DisplayManager displayManager(ShellContext context) {
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
