package com.ivarna.fluxlinux.clone;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The clone display, owned by the shell (uid 2000) process.
 *
 * <p>Ported from ShadowAuto's <b>working</b> display path (VideoTest /
 * AutomationEngine), not the MCP screenshot variant: the VirtualDisplay is
 * created <b>with the capture surface already attached</b>. Diagnostics on
 * HyperOS proved why this matters — a display created with a null surface
 * reports {@code state OFF, committedState UNKNOWN}, and SurfaceFlinger never
 * composites it: no encoder samples, no ImageReader frames, no activity
 * resumption. Attaching a surface at creation makes the display ON from the
 * first frame.
 *
 * <p>The attached surface is an {@code RGBA_8888} ImageReader. A consumer
 * thread drains it continuously (so the buffer queue never applies
 * backpressure to the display) and keeps the latest converted Bitmap;
 * {@link #captureBitmap} returns that. No surface swapping, no MediaCodec.
 *
 * <p>Flags are ShadowAuto's exact ladder (hidden SUPPORTS_TOUCH / OWN_FOCUS /
 * TRUSTED first). The IME policy is set to LOCAL so the clone keyboard shows
 * up on the clone display, never on the phone screen.
 */
final class DisplaySession {
    final int displayId;
    final int width;
    final int height;
    final int dpi;
    final int flagsUsed;
    final List<String> diagnostics = new ArrayList<>();

    private final ImageReader captureReader;
    private final Surface captureSurface;
    private final VirtualDisplay display;
    private final HandlerThread captureThread;
    private final WindowManagerBridge windowManager; // optional: may be null on locked-down builds
    private final int previousImePolicy;
    private final boolean localImeEnabled;

    private final Object frameLock = new Object();
    private Bitmap latestFrame;
    private long latestAt;
    private long framesProduced;

    private DisplaySession(int width, int height, int dpi, ImageReader captureReader,
                           VirtualDisplay display, int flagsUsed) {
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.flagsUsed = flagsUsed;
        this.captureReader = captureReader;
        this.captureSurface = captureReader.getSurface();
        this.display = display;
        this.displayId = display.getDisplay().getDisplayId();
        HandlerThread thread = new HandlerThread("BlissCapture");
        thread.start();
        this.captureThread = thread;
        captureReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                consumeFrame(reader);
            }
        }, new Handler(thread.getLooper()));
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
        ImageReader captureReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4);
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
                // Surface attached at creation: this is what puts the display
                // in state ON (a null-surface create stays OFF on HyperOS).
                VirtualDisplay display = manager.createVirtualDisplay("Bliss", width, height, dpi,
                        captureReader.getSurface(), flags);
                if (display != null && display.getDisplay() != null) {
                    DisplaySession session = new DisplaySession(width, height, dpi, captureReader, display, flags);
                    session.forceDisplayOn();
                    return session;
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
     * Latest composed frame of the display. The reader is drained continuously
     * by the consumer thread, so this never swaps surfaces and never blocks
     * composition. Waits up to (retries+1)*delayMs for the first frame.
     */
    Bitmap captureBitmap(int retries, long delayMs) {
        long deadline = SystemClock.uptimeMillis() + Math.max(0, retries) * Math.max(1, delayMs);
        synchronized (frameLock) {
            while (true) {
                if (latestFrame != null && !latestFrame.isRecycled()) {
                    return latestFrame;
                }
                long left = deadline - SystemClock.uptimeMillis();
                if (left <= 0) {
                    return null;
                }
                try {
                    frameLock.wait(Math.min(left, 120L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    /** Number of frames the consumer has converted (diagnostics). */
    long framesProduced() {
        synchronized (frameLock) {
            return framesProduced;
        }
    }

    private void consumeFrame(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            // Convert at most ~5/s; drain the rest immediately so the buffer
            // queue never pushes back on the compositor.
            long now = SystemClock.uptimeMillis();
            synchronized (frameLock) {
                if (latestAt != 0 && now - latestAt < 180) {
                    return;
                }
                latestAt = now;
            }
            Bitmap bitmap = ScreenCapture.bitmapFromImage(image);
            if (bitmap == null) {
                return;
            }
            synchronized (frameLock) {
                latestFrame = bitmap;
                framesProduced++;
                frameLock.notifyAll();
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

    private static DisplayManager displayManager(android.content.Context context) {
        try {
            Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(android.content.Context.class);
            ctor.setAccessible(true);
            return ctor.newInstance(context);
        } catch (Throwable ignored) {
            return (DisplayManager) context.getSystemService(android.content.Context.DISPLAY_SERVICE);
        }
    }

    /**
     * Diagnostic power-on ladder for ROMs that leave a fresh virtual display in
     * state OFF (HyperOS). Tries the hidden framework paths in order and records
     * which one the platform accepts; any success puts the display in state ON
     * so the compositor starts producing frames.
     */
    private void forceDisplayOn() {
        Object token = virtualDisplayToken();
        diagnostics.add("vdisplay token=" + (token == null ? "null" : token.getClass().getName()));
        try {
            Object global = Class.forName("android.hardware.display.DisplayManagerGlobal")
                    .getMethod("getInstance").invoke(null);
            if (global != null && token != null) {
                Method direct = findMethod(global.getClass(), "setVirtualDisplayState", 2);
                if (direct != null) {
                    try {
                        direct.setAccessible(true);
                        Object r = direct.invoke(global, token, Boolean.TRUE);
                        diagnostics.add("setVirtualDisplayState(global)=" + r);
                        return;
                    } catch (Throwable t) {
                        diagnostics.add("setVirtualDisplayState(global) failed: " + t);
                    }
                }
                try {
                    Field dmField = global.getClass().getDeclaredField("mDm");
                    dmField.setAccessible(true);
                    Object dm = dmField.get(global);
                    Method viaDm = dm == null ? null : findMethod(dm.getClass(), "setVirtualDisplayState", 2);
                    if (viaDm != null) {
                        try {
                            Object r = viaDm.invoke(dm, token, Boolean.TRUE);
                            diagnostics.add("setVirtualDisplayState(dm)=" + r);
                            return;
                        } catch (Throwable t) {
                            diagnostics.add("setVirtualDisplayState(dm) failed: " + t);
                        }
                    } else {
                        diagnostics.add("mDm.setVirtualDisplayState not found");
                    }
                } catch (Throwable t) {
                    diagnostics.add("mDm lookup failed: " + t);
                }
            }
        } catch (Throwable t) {
            diagnostics.add("DisplayManagerGlobal lookup failed: " + t);
        }
        try {
            if (token instanceof android.os.IBinder) {
                Class<?> txClass = Class.forName("android.view.SurfaceControl$Transaction");
                Method power = findMethod(txClass, "setDisplayPowerMode", 2);
                if (power != null) {
                    Object tx = txClass.getConstructor().newInstance();
                    power.invoke(tx, token, 1); // POWER_MODE_NORMAL
                    Method apply = findMethod(txClass, "apply", 0);
                    if (apply != null) {
                        apply.invoke(tx);
                    }
                    diagnostics.add("SurfaceControl.setDisplayPowerMode(NORMAL) applied");
                    return;
                }
                diagnostics.add("setDisplayPowerMode not found");
            } else {
                diagnostics.add("setDisplayPowerMode skipped: token is "
                        + (token == null ? "null" : token.getClass().getName()));
            }
        } catch (Throwable t) {
            diagnostics.add("setDisplayPowerMode failed: " + t);
        }
    }

    /** The VirtualDisplay's first binder-ish field (display token or callback). */
    private Object virtualDisplayToken() {
        try {
            for (Field f : VirtualDisplay.class.getDeclaredFields()) {
                diagnostics.add("vdisplay field " + f.getName() + " : " + f.getType().getName());
            }
            for (Field f : VirtualDisplay.class.getDeclaredFields()) {
                if (!android.os.IBinder.class.isAssignableFrom(f.getType())
                        && !android.os.IInterface.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                f.setAccessible(true);
                Object value = f.get(display);
                if (value instanceof android.os.IBinder) {
                    return value;
                }
                if (value instanceof android.os.IInterface) {
                    return ((android.os.IInterface) value).asBinder();
                }
            }
        } catch (Throwable t) {
            diagnostics.add("token scan failed: " + t);
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, int params) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == params) {
                return m;
            }
        }
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == params) {
                return m;
            }
        }
        return null;
    }

    void release() {
        try {
            captureReader.setOnImageAvailableListener(null, null);
        } catch (Throwable ignored) {
        }
        try {
            captureThread.quitSafely();
        } catch (Throwable ignored) {
        }
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
            latestFrame = null;
            frameLock.notifyAll();
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
