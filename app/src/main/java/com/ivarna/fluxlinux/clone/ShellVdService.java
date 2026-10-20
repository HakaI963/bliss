package com.ivarna.fluxlinux.clone;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Process;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shell-side (Shizuku UserService, uid 2000) owner of the clone display.
 *
 * <p>The display itself, the capture path and the input injection are
 * ShadowAuto's implementation, ported into this package:
 * {@link ShadowDisplay} (their {@code VirtualDisplaySession}),
 * {@link ShadowScreenCapture}, {@link ShadowInput} and
 * {@link ShadowWindowManager}. See shadow-shell/LICENSE (Apache-2.0).
 *
 * <p>What this class adds around them is the plumbing Bliss needs: the
 * binder surface, the still-frame pump that ships JPEG frames to the app,
 * the cross-user launch (clones live in another Android user), and the
 * shell-side error reporting (including the device crash buffer).
 */
public class ShellVdService extends IShellVd.Stub {
    private static final String TAG = "ShellVdService";
    private static final String CRASH_FILE = "/data/local/tmp/bliss-stage/crash.log";
    private static final int MAX_LOG_LINES = 300;
    private static final int MIN_WIDTH = 320;
    private static final int MAX_WIDTH = 1920;
    private static final int MIN_HEIGHT = 320;
    private static final int MAX_HEIGHT = 1920;
    private static final int MIN_DPI = 120;
    private static final int MAX_DPI = 640;
    private static final Pattern SAFE_PACKAGE = Pattern.compile("[A-Za-z0-9._]+");

    private final Object lock = new Object();
    private final List<String> logBuffer = new ArrayList<>();

    private ShadowDisplay display;
    private ShadowInput input;
    private int currentDisplayId;
    private int displayWidth;
    private int displayHeight;
    private int displayDpi;

    private IShellVdCallback frameCallback;
    private Thread frameThread;
    private volatile boolean frameRunning;
    private volatile int frameFps = 3;
    private volatile int frameMaxWidth = 540;
    private volatile int frameQuality = 80;
    private volatile long framesSent;

    /** The Context Shizuku passes to this user service (null with the no-arg ctor). */
    private static android.content.Context hostContext;

    static {
        installCrashHandler();
    }

    public ShellVdService() {
        android.util.Log.i(TAG, "ShellVdService() no-arg ctor, pid=" + Process.myPid());
    }

    public ShellVdService(android.content.Context context) {
        // Shizuku calls this constructor with the app context running under the
        // shell uid. The display owner prefers it: the fabricated shell context
        // is only a fallback for ROMs where ActivityThread reflection fails.
        hostContext = context == null ? null : context;
        android.util.Log.i(TAG, "ShellVdService(Context) ctor, pid=" + Process.myPid()
                + " context=" + (context == null ? "null" : context.getClass().getName()));
    }

    // ---------------------------------------------------------------- display

    @Override
    public int createDisplay(int width, int height, int dpi) {
        int w = clamp(width, MIN_WIDTH, MAX_WIDTH);
        int h = clamp(height, MIN_HEIGHT, MAX_HEIGHT);
        int d = clamp(dpi, MIN_DPI, MAX_DPI);
        synchronized (lock) {
            if (display != null && currentDisplayId > 0
                    && displayWidth == w && displayHeight == h && displayDpi == d) {
                log("createDisplay: reuse id=" + currentDisplayId);
                return currentDisplayId;
            }
            stopFramesLocked();
            releaseDisplayLocked();
            try {
                log("createDisplay: creating " + w + "x" + h + " dpi=" + d
                        + " pid=" + Process.myPid() + " uid=" + Process.myUid()
                        + " sdk=" + android.os.Build.VERSION.SDK_INT
                        + " context=" + (hostContext == null
                            ? "none (no-arg service ctor)"
                            : hostContext.getClass().getSimpleName()));
                ShadowDisplay session = ShadowDisplay.create(hostContext, w, h, d);
                log("createDisplay: created id=" + session.displayId
                        + " flags=0x" + Integer.toHexString(session.flagsUsed)
                        + " context=" + session.contextSource
                        + " (non-secure, capture surface attached)");
                ShadowInput bridge = null;
                try {
                    bridge = new ShadowInput(hostContext);
                } catch (Throwable t) {
                    log("input bridge unavailable (display kept): " + describe(t));
                }
                display = session;
                input = bridge;
                currentDisplayId = session.displayId;
                displayWidth = w;
                displayHeight = h;
                displayDpi = d;
                log("display created id=" + session.displayId + " " + w + "x" + h
                        + " input=" + (bridge != null ? "ok" : "retry-on-demand")
                        + (session.localImeEnabled() ? " ime=local" : " ime=default"));
                return session.displayId;
            } catch (Throwable t) {
                log("createDisplay failed: " + describe(t));
                display = null;
                input = null;
                currentDisplayId = 0;
                return -1;
            }
        }
    }

    @Override
    public int displayId() {
        synchronized (lock) {
            return currentDisplayId;
        }
    }

    @Override
    public void releaseDisplay() {
        synchronized (lock) {
            stopFramesLocked();
            releaseDisplayLocked();
            log("display released");
        }
    }

    private void releaseDisplayLocked() {
        if (display != null) {
            try {
                display.release();
            } catch (Throwable t) {
                log("display release failed: " + describe(t));
            }
        }
        display = null;
        input = null;
        currentDisplayId = 0;
        displayWidth = 0;
        displayHeight = 0;
        displayDpi = 0;
    }

    /**
     * Fresh frame for the AI observer. The display's drain thread keeps the
     * newest composed frame; captureBitmap() hands out an owned copy so the
     * encoder can never race the drain thread's recycle.
     */
    @Override
    public byte[] screenshot(int maxWidth, int quality) {
        synchronized (lock) {
            if (display == null || currentDisplayId <= 0) {
                log("screenshot: no display");
                return null;
            }
            Bitmap bitmap = null;
            Bitmap scaled = null;
            try {
                bitmap = display.captureBitmap(6, 120);
                if (bitmap == null) {
                    log("screenshot: no frame produced");
                    return null;
                }
                scaled = scaleDown(bitmap, clamp(maxWidth, 240, 1920));
                return encodeJpeg(scaled, clamp(quality, 40, 95));
            } catch (Throwable t) {
                log("screenshot failed: " + describe(t));
                return null;
            } finally {
                if (scaled != null && scaled != bitmap) {
                    try {
                        scaled.recycle();
                    } catch (Throwable ignored) {
                    }
                }
                if (bitmap != null) {
                    try {
                        bitmap.recycle();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------- frames

    @Override
    public void startFrames(IShellVdCallback cb, int maxWidth, int jpegQuality, int fps) {
        if (cb == null) {
            return;
        }
        synchronized (lock) {
            if (display == null) {
                log("startFrames: no display");
                return;
            }
            frameCallback = cb;
            frameFps = clamp(fps, 1, 10);
            frameMaxWidth = clamp(maxWidth, 240, 1920);
            frameQuality = clamp(jpegQuality, 40, 95);
            if (frameThread != null) {
                return;
            }
            frameRunning = true;
            frameThread = new Thread(this::framePump, "bliss-frames");
            frameThread.setDaemon(true);
            frameThread.start();
            log("frame pump started: " + frameFps + " fps, max " + frameMaxWidth
                    + "px, q" + frameQuality);
        }
    }

    @Override
    public void stopFrames() {
        synchronized (lock) {
            stopFramesLocked();
            log("frames stopped");
        }
    }

    private void stopFramesLocked() {
        frameRunning = false;
        Thread thread = frameThread;
        frameThread = null;
        frameCallback = null;
        if (thread != null) {
            try {
                thread.join(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Captures still frames and ships them to the app as JPEG. */
    private void framePump() {
        while (frameRunning) {
            ShadowDisplay session;
            IShellVdCallback cb;
            synchronized (lock) {
                session = display;
                cb = frameCallback;
            }
            if (session != null && cb != null) {
                Bitmap bitmap = null;
                Bitmap scaled = null;
                try {
                    bitmap = session.captureBitmap(2, 120);
                    if (bitmap != null) {
                        scaled = scaleDown(bitmap, frameMaxWidth);
                        byte[] jpeg = encodeJpeg(scaled, frameQuality);
                        if (jpeg != null) {
                            try {
                                cb.onScreenshot(jpeg, scaled.getWidth(), scaled.getHeight());
                                framesSent++;
                            } catch (Throwable t) {
                                if (isDeadObject(t)) {
                                    log("frame callback died, pump stopping");
                                    frameRunning = false;
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    log("frame pump error: " + describe(t));
                } finally {
                    if (scaled != null && scaled != bitmap) {
                        try {
                            scaled.recycle();
                        } catch (Throwable ignored) {
                        }
                    }
                    if (bitmap != null) {
                        try {
                            bitmap.recycle();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            try {
                Thread.sleep(Math.max(200, 1000 / Math.max(1, frameFps)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static Bitmap scaleDown(Bitmap bitmap, int maxWidth) {
        try {
            if (bitmap.getWidth() <= maxWidth) {
                return bitmap;
            }
            int width = maxWidth;
            int height = Math.max(1, Math.round(bitmap.getHeight() * (width / (float) bitmap.getWidth())));
            return Bitmap.createScaledBitmap(bitmap, width, height, true);
        } catch (Throwable t) {
            return bitmap;
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean tap(int x, int y) {
        return inject("tap", bridge -> bridge.tap(currentDisplayIdSnapshot(), x, y));
    }

    @Override
    public boolean longPress(int x, int y, int ms) {
        return inject("longPress", bridge -> bridge.longPress(currentDisplayIdSnapshot(), x, y, ms));
    }

    @Override
    public boolean swipe(int x1, int y1, int x2, int y2, int ms) {
        return inject("swipe", bridge -> bridge.swipe(currentDisplayIdSnapshot(), x1, y1, x2, y2, ms));
    }

    @Override
    public boolean key(int keyCode) {
        return inject("key", bridge -> bridge.key(currentDisplayIdSnapshot(), keyCode));
    }

    @Override
    public boolean text(String value) {
        return inject("text", bridge -> bridge.text(currentDisplayIdSnapshot(), value));
    }

    private interface Injection {
        boolean run(ShadowInput bridge) throws Exception;
    }

    private boolean inject(String name, Injection injection) {
        ShadowInput bridge;
        int id = currentDisplayIdSnapshot();
        if (id <= 0) {
            log(name + ": no display");
            return false;
        }
        synchronized (lock) {
            bridge = input;
        }
        if (bridge == null) {
            try {
                bridge = new ShadowInput(hostContext);
                synchronized (lock) {
                    input = bridge;
                }
            } catch (Throwable t) {
                log(name + ": input bridge unavailable: " + describe(t));
                return false;
            }
        }
        try {
            boolean ok = injection.run(bridge);
            if (!ok) {
                log(name + ": injection rejected");
            }
            return ok;
        } catch (Throwable t) {
            log(name + " failed: " + describe(t));
            return false;
        }
    }

    // ------------------------------------------------------------------- apps

    @Override
    public String launch(String pkg, int userId) {
        String packageName = pkg == null ? "" : pkg.trim();
        if (packageName.isEmpty()) {
            return "package required";
        }
        if (!SAFE_PACKAGE.matcher(packageName).matches()) {
            return "invalid package: " + packageName;
        }
        int id = currentDisplayIdSnapshot();
        if (id <= 0) {
            return "no display";
        }
        String userArg = userId > 0 ? " --user " + userId : "";
        try {
            runShell("am force-stop" + userArg + " " + shellQuote(packageName));
            String component = resolveLaunchComponent(packageName, userId);
            if (component.isEmpty()) {
                return "no launch activity for " + packageName;
            }
            // ShadowAuto's launch form, with the target user appended.
            String command = "am start" + userArg + " --display " + id
                    + " -f 0x10008000 -n " + shellQuote(component);
            ShellResult result = runShell(command);
            String output = result.output == null ? "" : result.output.trim();
            log("am start" + userArg + " --display " + id + " exit=" + result.exitCode + " " + output);
            if (result.exitCode != 0) {
                return output.isEmpty() ? ("am start failed: exit " + result.exitCode) : output;
            }
            return null;
        } catch (Throwable t) {
            String error = "launch failed: " + describe(t);
            log(error);
            return error;
        }
    }

    /** Resolves the launcher activity of a package in the given user. */
    private String resolveLaunchComponent(String packageName, int userId) {
        String userArg = userId > 0 ? " --user " + userId : "";
        try {
            ShellResult r = runShell("cmd package query-activities --brief" + userArg + " " + shellQuote(packageName));
            for (String line : r.output.split("\n")) {
                String value = line.trim();
                if (value.startsWith(packageName + "/")) {
                    return value;
                }
                int slash = value.indexOf('/');
                if (slash > 0 && value.contains(".") && !value.contains(" ")) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            ShellResult r2 = runShell("cmd package resolve-activity --brief" + userArg + " " + shellQuote(packageName));
            for (String line : r2.output.split("\n")) {
                String value = line.trim();
                if (value.contains("/") && !value.contains(" ")) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    @Override
    public String kill(String pkg, int userId) {
        String packageName = pkg == null ? "" : pkg.trim();
        if (packageName.isEmpty()) {
            return "package required";
        }
        String userArg = userId > 0 ? " --user " + userId : "";
        try {
            ShellResult result = runShell("am force-stop" + userArg + " " + shellQuote(packageName));
            log("am force-stop " + packageName + " exit=" + result.exitCode);
            return null;
        } catch (Throwable t) {
            String error = "kill failed: " + describe(t);
            log(error);
            return error;
        }
    }

    // ---------------------------------------------------------------- staging

    @Override
    public long stageWrite(String path, long offset, byte[] data) {
        if (path == null || data == null || !path.startsWith("/data/local/tmp/")) {
            return -1L;
        }
        if (path.contains("..")) {
            log("stageWrite rejected traversal: " + path);
            return -1L;
        }
        try {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (java.io.RandomAccessFile raFile = new java.io.RandomAccessFile(file, "rw")) {
                raFile.setLength(offset);
                raFile.seek(offset);
                raFile.write(data);
            }
            return offset + data.length;
        } catch (Throwable t) {
            log("stageWrite failed: " + describe(t));
            return -1L;
        }
    }

    @Override
    public String pmInstall(String[] paths, int userId) {
        if (paths == null || paths.length == 0) {
            return "no staged paths";
        }
        try {
            java.util.ArrayList<String> command = new java.util.ArrayList<>();
            command.add("pm");
            command.add("install");
            command.add("--user");
            command.add(String.valueOf(userId));
            command.add("-r");
            for (String path : paths) {
                if (path != null && !path.isEmpty()) {
                    command.add(path);
                }
            }
            java.lang.ProcessBuilder builder = new java.lang.ProcessBuilder(command);
            builder.redirectErrorStream(true);
            java.lang.Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int code = process.waitFor();
            String text = output.toString().trim();
            log("pm install exit=" + code + " " + trim(text, 300));
            if (text.contains("Success")) {
                return null;
            }
            return text.length() > 1500 ? text.substring(text.length() - 1500) : text;
        } catch (Throwable t) {
            String error = "pmInstall failed: " + describe(t);
            log(error);
            return error;
        }
    }

    @Override
    public void deleteStage(String[] paths) {
        if (paths == null) {
            return;
        }
        for (String path : paths) {
            if (path == null || path.isEmpty()) {
                continue;
            }
            try {
                new File(path).delete();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ misc

    @Override
    public void attach(IShellVdCallback cb) {
        synchronized (lock) {
            callback = cb;
        }
    }

    private IShellVdCallback callback;

    @Override
    public void exit() {
        try {
            synchronized (lock) {
                stopFramesLocked();
                releaseDisplayLocked();
            }
        } catch (Throwable ignored) {
        }
        Process.killProcess(Process.myPid());
    }

    @Override
    public String[] logs() {
        synchronized (lock) {
            ArrayList<String> out = new ArrayList<>();
            String crash = crashFileTail();
            if (!crash.isEmpty()) {
                out.add("--- PREVIOUS PROCESS CRASH ---");
                for (String line : crash.split("\n")) {
                    out.add(line);
                }
                out.add("--- current process ---");
            }
            String deviceCrash = crashBufferTail();
            if (!deviceCrash.isEmpty()) {
                out.add("--- DEVICE CRASH BUFFER (logcat -b crash) ---");
                for (String line : deviceCrash.split("\n")) {
                    out.add(line);
                }
                out.add("--- end crash buffer ---");
            }
            for (Object line : logBuffer) {
                out.add((String) line);
            }
            return out.toArray(new String[0]);
        }
    }

    private int currentDisplayIdSnapshot() {
        synchronized (lock) {
            return currentDisplayId;
        }
    }

    private void log(String line) {
        String text = line == null ? "" : line;
        synchronized (lock) {
            logBuffer.add(text);
            while (logBuffer.size() > MAX_LOG_LINES) {
                logBuffer.remove(0);
            }
            IShellVdCallback cb = callback;
            if (cb != null) {
                try {
                    cb.onLog(text);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ----------------------------------------------------------------- helpers

    private static byte[] encodeJpeg(Bitmap bitmap, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                return null;
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static ShellResult runShell(String command) throws Exception {
        java.lang.Process process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int code = process.waitFor();
        return new ShellResult(code, output.toString());
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static void installCrashHandler() {
        try {
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                try {
                    new File("/data/local/tmp/bliss-stage").mkdirs();
                    PrintWriter writer = new PrintWriter(new FileWriter(CRASH_FILE, true));
                    try {
                        writer.println("--- " + System.currentTimeMillis() + " " + thread.getName() + " ---");
                        error.printStackTrace(writer);
                    } finally {
                        writer.close();
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static String crashFileTail() {
        try {
            File file = new File(CRASH_FILE);
            if (!file.exists() || file.length() == 0) {
                return "";
            }
            java.io.RandomAccessFile raFile = new java.io.RandomAccessFile(file, "r");
            try {
                int length = (int) raFile.length();
                int start = Math.max(0, length - 4000);
                byte[] data = new byte[length - start];
                raFile.seek(start);
                raFile.readFully(data);
                return new String(data);
            } finally {
                raFile.close();
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** The system-wide logcat crash buffer, filtered to this app's process. */
    private static String crashBufferTail() {
        try {
            ShellResult result = runShell("logcat -d -b crash -t 400 -v threadtime 2>/dev/null");
            if (result == null || result.output == null || result.output.isEmpty()) {
                return "";
            }
            StringBuilder kept = new StringBuilder();
            StringBuilder block = new StringBuilder();
            boolean blockHit = false;
            int blocksKept = 0;
            for (String line : result.output.split("\n")) {
                if (line.matches("\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+\\d+.*")) {
                    if (blockHit && blocksKept < 25) {
                        kept.append(block);
                        blocksKept++;
                    }
                    block.setLength(0);
                    blockHit = false;
                }
                block.append(line).append('\n');
                String lower = line.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("nopiece") || lower.contains("ivarna")
                        || lower.contains("fluxlinux") || lower.contains("bliss")) {
                    blockHit = true;
                }
            }
            if (blockHit && blocksKept < 25) {
                kept.append(block);
            }
            return kept.toString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isDeadObject(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (current.getClass().getName().contains("DeadObjectException")) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        int depth = 0;
        while (current != null && depth < 6) {
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

    private static final class ShellResult {
        final int exitCode;
        final String output;

        ShellResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
