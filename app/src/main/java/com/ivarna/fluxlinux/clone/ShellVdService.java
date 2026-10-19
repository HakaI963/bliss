package com.ivarna.fluxlinux.clone;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.FileInputStream;
import android.media.Image;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.FileWriter;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shell-side (Shizuku UserService, uid 2000) owner of the clone virtual
 * display and of everything that needs shell privileges: creating the hidden
 * display, injecting input on it, streaming JPEG frames to the app, and
 * launching/killing clone apps with `am`.
 *
 * This process is not a real application process, so all framework access goes
 * through {@link ShellContext} and hidden APIs via reflection (allowed here:
 * user service processes are exempt from the non-SDK interface restrictions).
 * The class name is referenced by {@link ShellVd#ensure} through
 * {@code UserServiceArgs}; keep the name stable. Bump nothing here — the
 * service version comes from {@code BuildConfig.VERSION_CODE}.
 *
 * Everything runs behind a single lock, is null-safe and never throws across
 * binder boundaries. The frame pump only talks to the callback through a
 * captured reference and always closes its Image.
 */
public final class ShellVdService extends IShellVd.Stub {

    static {
        try {
            final Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        writeCrashLog(t, e);
                    } catch (Throwable ignored) {
                    }
                    if (previous != null) {
                        previous.uncaughtException(t, e);
                    } else {
                        Runtime.getRuntime().exit(2);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * Shizuku may instantiate user services with a (Context) constructor; the
     * context it builds for the shell process is a proven-working source of
     * framework services. Kept as the display fallback when our own shell
     * context fabrication fails on a locked-down build.
     */
    @SuppressWarnings("unused")
    public ShellVdService(android.content.Context context) {
        serviceContext = context;
    }

    public ShellVdService() {
        serviceContext = null;
    }

    /** Shizuku destroy transaction: server side sends 16777115, aidl declares 16777114. */
    private static final int TRANSACTION_DESTROY_AIDL = 16777114;
    private static final int TRANSACTION_DESTROY_SERVER = 16777115;

    private static final int MAX_LOGS = 200;
    private static final int MIN_WIDTH = 320;
    private static final int MAX_WIDTH = 2000;
    private static final int MIN_HEIGHT = 320;
    private static final int MAX_HEIGHT = 2500;
    private static final int MIN_DPI = 120;
    private static final int MAX_DPI = 640;
    private static final long MIN_FRAME_INTERVAL_MS = 33L;
    private static final Pattern SAFE_PACKAGE = Pattern.compile("[A-Za-z0-9._]+");

    private final Object lock = new Object();
    private final ArrayDeque<String> logBuffer = new ArrayDeque<>();

    private ShellContext shellContext;
    private final android.content.Context serviceContext;
    private InputBridge inputBridge;
    private DisplaySession display;

    private int currentDisplayId;
    private int displayWidth;
    private int displayHeight;
    private int displayDpi;

    private IShellVdCallback frameCallback;
    private HandlerThread frameThread;
    private volatile boolean frameRunning;
    private long framesSent;
    private long captureMisses;
    private String currentPackage = "";
    private long captureCalls;

    // ------------------------------------------------------------------ display

    @Override
    public int createDisplay(int width, int height, int dpi) {
        synchronized (lock) {
            int reuseW = clamp(width, MIN_WIDTH, MAX_WIDTH);
            int reuseH = clamp(height, MIN_HEIGHT, MAX_HEIGHT);
            int reuseD = clamp(dpi, MIN_DPI, MAX_DPI);
            synchronized (lock) {
                if (display != null && currentDisplayId > 0
                        && displayWidth == reuseW && displayHeight == reuseH && displayDpi == reuseD) {
                    log("createDisplay: reuse id=" + currentDisplayId + " (same geometry)");
                    return currentDisplayId;
                }
            }
            stopFramesLocked();
            releaseDisplayLocked();
            DisplaySession session = null;
            try {
                ShellContext context = contextLocked();
                android.content.Context displayContext = context != null ? context : fallbackContextLocked();
                if (displayContext == null) {
                    log("createDisplay: no usable context (ShellContext init failed and Shizuku passed none)");
                    return -1;
                }
                if (context == null) {
                    log("createDisplay: ShellContext unavailable, using Shizuku service context");
                }
                int w = clamp(width, MIN_WIDTH, MAX_WIDTH);
                int h = clamp(height, MIN_HEIGHT, MAX_HEIGHT);
                int d = clamp(dpi, MIN_DPI, MAX_DPI);
                log("createDisplay: context ok, creating display");
                session = DisplaySession.create(displayContext, w, h, d);
                log("createDisplay: created id=" + session.displayId + " (surface attached, flags=0x"
                        + Integer.toHexString(session.flagsUsed) + ")");
                for (String diag : session.diagnostics) {
                    log("displayInit: " + diag);
                }
                try {
                    ShellResult st = runShell("dumpsys display 2>/dev/null"
                            + " | grep 'DisplayDeviceInfo{\"Bliss\"' | grep -o 'state [A-Z]*' | head -1");
                    String state = st == null || st.output == null ? "" : st.output.trim();
                    log("display state after create: " + (state.isEmpty() ? "unknown" : state));
                } catch (Throwable ignored) {
                }
                android.graphics.Bitmap probe = session.captureBitmap(2, 250);
                if (probe != null) {
                    log("probe capture: " + probe.getWidth() + "x" + probe.getHeight()
                            + " (display composites with no app, produced=" + session.framesProduced() + ")");
                } else {
                    log("probe capture: null (display produced no frame before launch)");
                }
                // The display is the expensive, capricious part; input and IME
                // policy are optional helpers — if they fail here the display
                // must survive (input retries on demand at first use).
                InputBridge bridge = null;
                try {
                    bridge = new InputBridge(context);
                } catch (Throwable it) {
                    log("input bridge unavailable (display kept): " + describe(it));
                }
                display = session;
                inputBridge = bridge;
                currentDisplayId = session.displayId;
                displayWidth = w;
                displayHeight = h;
                displayDpi = d;
                log("display created id=" + session.displayId + " " + w + "x" + h + " dpi=" + d
                        + " input=" + (bridge != null ? "ok" : "retry-on-demand")
                        + (session.localImeEnabled() ? " ime=local" : " ime=default"));
                scheduleAliveTicks(session.displayId);
                return session.displayId;
            } catch (Throwable t) {
                if (session != null) {
                    try {
                        session.release();
                    } catch (Throwable ignored) {
                    }
                }
                display = null;
                inputBridge = null;
                currentDisplayId = 0;
                displayWidth = 0;
                displayHeight = 0;
                displayDpi = 0;
                log("createDisplay failed: " + describe(t));
                return -1;
            }
        }
    }

    @Override
    public String[] logs() {
        synchronized (lock) {
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
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

    @Override
    public int displayId() {
        synchronized (lock) {
            return currentDisplayId;
        }
    }

    // -------------------------------------------------------------------- input

    @Override
    public boolean tap(int x, int y) {
        InputBridge bridge = inputBridge();
        int id = currentDisplayIdSnapshot();
        if (bridge == null || id <= 0) {
            log("tap rejected: " + (id <= 0 ? "display gone" : "no input bridge"));
            return false;
        }
        try {
            return bridge.tap(id, x, y);
        } catch (Throwable t) {
            log("tap failed: " + describe(t));
            return false;
        }
    }

    @Override
    public boolean longPress(int x, int y, int ms) {
        InputBridge bridge = inputBridge();
        int id = currentDisplayIdSnapshot();
        if (bridge == null || id <= 0) {
            log("longPress rejected: " + (id <= 0 ? "display gone" : "no input bridge"));
            return false;
        }
        try {
            return bridge.longPress(id, x, y, ms);
        } catch (Throwable t) {
            log("longPress failed: " + describe(t));
            return false;
        }
    }

    @Override
    public boolean swipe(int x1, int y1, int x2, int y2, int ms) {
        InputBridge bridge = inputBridge();
        int id = currentDisplayIdSnapshot();
        if (bridge == null || id <= 0) {
            log("swipe rejected: " + (id <= 0 ? "display gone" : "no input bridge"));
            return false;
        }
        try {
            return bridge.swipe(id, x1, y1, x2, y2, ms);
        } catch (Throwable t) {
            log("swipe failed: " + describe(t));
            return false;
        }
    }

    @Override
    public boolean key(int keyCode) {
        InputBridge bridge = inputBridge();
        int id = currentDisplayIdSnapshot();
        if (bridge == null || id <= 0) {
            log("key rejected: " + (id <= 0 ? "display gone" : "no input bridge"));
            return false;
        }
        try {
            return bridge.key(id, keyCode);
        } catch (Throwable t) {
            log("key failed: " + describe(t));
            return false;
        }
    }

    @Override
    public boolean text(String value) {
        InputBridge bridge = inputBridge();
        int id = currentDisplayIdSnapshot();
        if (bridge == null || id <= 0 || value == null) {
            log("text rejected: " + (id <= 0 ? "display gone" : "no input bridge"));
            return false;
        }
        try {
            return bridge.text(id, value);
        } catch (Throwable t) {
            log("text failed: " + describe(t));
            return false;
        }
    }

    // ------------------------------------------------------------------- frames

    @Override
    public void startFrames(IShellVdCallback cb, int maxWidth, int jpegQuality, int fps) {
        if (cb == null) {
            return;
        }
        List<String> pendingLogs;
        synchronized (lock) {
            if (display == null) {
                log("startFrames: no display");
                return;
            }
            frameCallback = cb;
            frameRunning = true;
            appendLogLocked("frame capture armed on display " + currentDisplayId
                    + " (" + displayWidth + "x" + displayHeight + " on-demand stills)");
            pendingLogs = new ArrayList<>(logBuffer);
            logBuffer.clear();
        }
        for (String line : pendingLogs) {
            try {
                cb.onLog(line);
            } catch (Throwable ignored) {
            }
        }
        // Prime the window with one frame right away.
        captureFrame();
    }

    /**
     * ShadowAuto's capture path: swap the ImageReader surface into the display,
     * grab one composed frame, restore the null surface, ship PNG to the client.
     */
    @Override
    public void captureFrame() {
        DisplaySession session;
        IShellVdCallback cb;
        synchronized (lock) {
            session = display;
            cb = frameCallback;
            if (session == null || cb == null) {
                return;
            }
        }
        Bitmap bitmap = null;
        // Primary path: read the display's front buffer with screencap. This
        // does not depend on the VirtualDisplay's output surface at all, so it
        // works even on ROMs where the display's own surface never receives a
        // composed frame.
        try {
            bitmap = screencapDisplay(currentDisplayIdSnapshot());
        } catch (Throwable t) {
            log("screencap failed: " + describe(t));
        }
        if (bitmap == null) {
            // Fallback: the attached ImageReader (works when SF feeds it).
            try {
                bitmap = session.captureBitmap(0, 1);
            } catch (Throwable t) {
                log("capture failed: " + describe(t));
            }
        }
        if (bitmap == null) {
            long misses;
            long calls;
            synchronized (lock) {
                captureMisses++;
                misses = captureMisses;
                captureCalls++;
                calls = captureCalls;
            }
            if (calls <= 4 || misses % 10 == 0) {
                log("capture #" + calls + " -> null (misses=" + misses + ")");
            }
            if (misses == 4) {
                dumpDisplayState();
            }
            return;
        }
        byte[] png = null;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
                png = out.toByteArray();
            }
        } catch (Throwable t) {
            log("capture encode failed: " + describe(t));
        }
        if (png == null) {
            return;
        }
        try {
            cb.onScreenshot(png, w, h);
            long calls;
            synchronized (lock) {
                framesSent++;
                captureCalls++;
                calls = captureCalls;
            }
            if (calls <= 4) {
                log("capture #" + calls + " -> " + w + "x" + h);
            }
        } catch (Throwable t) {
            if (isDeadObject(t)) {
                synchronized (lock) {
                    frameCallback = null;
                    frameRunning = false;
                }
                log("frame callback died, capture paused");
            }
        }
    }

    @Override
    public void stopFrames() {
        synchronized (lock) {
            stopFramesLocked();
            log("frames stopped");
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

    // -------------------------------------------------------------------- apps

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
        synchronized (lock) {
            currentPackage = packageName;
        }
        String userArg = userId > 0 ? " --user " + userId : "";
        try {
            runShell("am force-stop" + userArg + " " + shellQuote(packageName));
            String component = resolveLaunchComponent(packageName, userId);
            String command;
            if (component.isEmpty()) {
                // Framework-side resolve: am resolves the launcher activity for
                // the target user itself — correct even when this process
                // cannot see that user's install state.
                log("no resolved component, starting by LAUNCHER intent for " + packageName);
                command = "am start" + userArg + " --display " + id
                        + " -f 0x10008000 -a android.intent.action.MAIN"
                        + " -c android.intent.category.LAUNCHER -p " + shellQuote(packageName);
            } else {
                command = "am start" + userArg + " --display " + id
                        + " -f 0x10008000 -n " + shellQuote(component);
            }
            ShellResult result = runShell(command);
            String output = result.output == null ? "" : result.output.trim();
            log("am start " + component + " exit=" + result.exitCode + " " + output);
            if (!failure(result)) {
                schedulePlacementProbe(packageName, userId, id);
            }
            return failure(result) ? output : "";
        } catch (Throwable t) {
            log("launch failed: " + describe(t));
            return "launch failed: " + describe(t);
        }
    }

    @Override
    public String kill(String pkg, int userId) {
        String packageName = pkg == null ? "" : pkg.trim();
        if (packageName.isEmpty()) {
            return "package required";
        }
        if (!SAFE_PACKAGE.matcher(packageName).matches()) {
            return "invalid package: " + packageName;
        }
        String userArg = userId > 0 ? " --user " + userId : "";
        try {
            ShellResult result = runShell("am force-stop" + userArg + " " + shellQuote(packageName));
            String output = result.output == null ? "" : result.output.trim();
            log("am force-stop " + packageName + " exit=" + result.exitCode + " " + output);
            return failure(result) ? output : "";
        } catch (Throwable t) {
            log("kill failed: " + describe(t));
            return "kill failed: " + describe(t);
        }
    }

    // ------------------------------------------------------------- installs

    /**
     * App-side upload of one chunk of a staged file. Only paths under
     * {@code /data/local/tmp/} are accepted (writable by uid 2000); the write
     * is always anchored at {@code offset} so a retried chunk overwrites itself
     * instead of corrupting the payload. Returns the number of bytes written,
     * or -1 on any failure (checked inputs, exceptions).
     */
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
            java.io.File file = new java.io.File(path);
            java.io.File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (java.io.RandomAccessFile raFile = new java.io.RandomAccessFile(file, "rw")) {
                raFile.setLength(offset);
                raFile.seek(offset);
                raFile.write(data);
            }
            return (long) data.length;
        } catch (Throwable t) {
            log("stageWrite failed: " + describe(t));
            return -1L;
        }
    }

    /**
     * Installs the currently staged APK(s) with the shell {@code pm install}:
     * one process, merged stdout+stderr, "Success" anywhere in the output
     * means the install went through. Returns "" on success, otherwise the
     * captured output (tail-capped at 1500 chars) as an error string.
     */
    @Override
    public String pmInstall(String[] paths, int userId) {
        if (paths == null || paths.length == 0) {
            return "no staged paths";
        }
        try {
            java.lang.ProcessBuilder builder = new ProcessBuilder("pm", "install",
                    "--user", String.valueOf(userId), "-r");
            for (String path : paths) {
                if (path != null && !path.isEmpty()) {
                    builder.command().add(path);
                }
            }
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
            log("pm install exit=" + code + " " + text);
            if (text.contains("Success")) {
                return "";
            }
            if (text.isEmpty()) {
                return "pm install produced no output (exit=" + code + ")";
            }
            String detail = text.length() > 1500 ? text.substring(text.length() - 1500) : text;
            return code != 0 ? "exit=" + code + ": " + detail : detail;
        } catch (Throwable t) {
            log("pmInstall failed: " + describe(t));
            return "pmInstall failed: " + describe(t);
        }
    }

    /** Best-effort removal of staged files; every failure is swallowed. */
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
                new java.io.File(path).delete();
            } catch (Throwable ignored) {
            }
        }
    }

    // -------------------------------------------------------------------- misc

    @Override
    public String info() {
        StringBuilder out = new StringBuilder();
        synchronized (lock) {
            out.append("displayId=").append(currentDisplayId).append('\n');
            out.append("width=").append(displayWidth).append('\n');
            out.append("height=").append(displayHeight).append('\n');
            out.append("dpi=").append(displayDpi).append('\n');
            out.append("frames=").append(framesSent).append(" misses=").append(captureMisses).append('\n');
            out.append("pumping=").append(frameRunning ? 1 : 0).append('\n');
            out.append("logs=").append(logBuffer.size()).append('\n');
        }
        return out.toString();
    }

    @Override
    public void exit() {
        synchronized (lock) {
            stopFramesLocked();
            releaseDisplayLocked();
            log("exit requested");
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                SystemClock.sleep(200);
                System.exit(0);
            }
        }, "ShellVdExit");
        thread.setDaemon(false);
        thread.start();
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
        if (code == TRANSACTION_DESTROY_SERVER || code == TRANSACTION_DESTROY_AIDL) {
            // Shizuku asks the user service to die when it is unbound with remove=true.
            exit();
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    // ----------------------------------------------------------------- helpers

    private ShellContext contextLocked() {
        if (shellContext == null) {
            try {
                if (android.os.Looper.myLooper() == null) {
                    android.os.Looper.prepare();
                }
            } catch (Throwable ignored) {
            }
            try {
                ShellContext.init();
                shellContext = ShellContext.get();
            } catch (Throwable t) {
                shellContext = null;
                log("shell context init failed: " + describe(t));
            }
        }
        return shellContext;
    }

    /** Shizuku-provided context (may be null when built with the no-arg ctor). */
    private android.content.Context fallbackContextLocked() {
        return serviceContext;
    }

    private ShellContext context() {
        synchronized (lock) {
            return contextLocked();
        }
    }

    private InputBridge inputBridge() {
        synchronized (lock) {
            if (inputBridge == null && display != null && currentDisplayId > 0) {
                try {
                    inputBridge = new InputBridge(contextLocked());
                    log("input bridge created on demand");
                } catch (Throwable t) {
                    log("input bridge retry failed: " + describe(t));
                }
            }
            return inputBridge;
        }
    }

    private int currentDisplayIdSnapshot() {
        synchronized (lock) {
            return currentDisplayId;
        }
    }

    private void releaseDisplayLocked() {
        DisplaySession session = display;
        display = null;
        inputBridge = null;
        currentDisplayId = 0;
        displayWidth = 0;
        displayHeight = 0;
        displayDpi = 0;
        if (session != null) {
            try {
                session.release();
            } catch (Throwable t) {
                log("display release failed: " + describe(t));
            }
        }
    }

    private void stopFramesLocked() {
        frameRunning = false;
        HandlerThread thread = frameThread;
        frameThread = null;
        frameCallback = null;
        if (thread != null) {
            try {
                thread.quitSafely();
            } catch (Throwable ignored) {
            }
        }
    }

    private void log(String line) {
        String message = line == null ? "" : line;
        IShellVdCallback cb;
        synchronized (lock) {
            appendLogLocked(message);
            cb = frameCallback;
        }
        if (cb != null) {
            try {
                cb.onLog(message);
            } catch (Throwable ignored) {
            }
        }
    }

    private void appendLogLocked(String message) {
        while (logBuffer.size() >= MAX_LOGS) {
            logBuffer.removeFirst();
        }
        logBuffer.addLast(message);
    }

    private String resolveLaunchComponent(String packageName, int userId) throws Exception {
        if (userId <= 0) {
            try {
                ShellContext context = context();
                if (context != null) {
                    Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
                    ComponentName component = intent == null ? null : intent.getComponent();
                    if (component != null) {
                        return component.flattenToShortString();
                    }
                }
            } catch (Throwable t) {
                log("getLaunchIntentForPackage failed: " + describe(t));
            }
        }
        // Install state for another user is not visible to this process's
        // PackageManager, so ask the shell `cmd` for the launcher activity.
        int user = Math.max(userId, 0);
        ShellResult result = runShell("cmd package query-activities --brief --user " + user
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " | grep -m 3 -F '" + packageName + "/'");
        String found = firstComponent(result, packageName);
        if (!found.isEmpty()) {
            return found;
        }
        // Second ladder: resolve-activity answers for the intent itself.
        ShellResult r2 = runShell("cmd package resolve-activity --brief --user " + user
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " " + shellQuote(packageName));
        found = firstComponent(r2, packageName);
        if (!found.isEmpty()) {
            return found;
        }
        log("resolve empty for " + packageName + " (user " + user + "): q="
                + oneLine(result) + " r=" + oneLine(r2));
        return "";
    }

    /**
     * Logs, ~4s after a launch, whether the app actually has windows on the
     * hidden display. If it does not, no frames will ever arrive and the
     * Logs pane says so explicitly instead of a spinner with no story.
     */
    private void schedulePlacementProbe(String packageName, int userId, int displayId) {
        schedulePlacementProbe(packageName, userId, displayId, true);
    }

    private void schedulePlacementProbe(final String packageName, final int userId,
                                        final int displayId, final boolean escalate) {
        try {
            HandlerThread probe = new HandlerThread("ShellVdProbe");
            probe.start();
            new Handler(probe.getLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        ShellResult r = runShell("dumpsys window displays 2>/dev/null"
                                + " | grep -A400 'displayId=" + displayId + "'"
                                + " | grep -m 4 -F '" + packageName + "/'");
                        boolean present = r.exitCode == 0 && r.output != null && !r.output.trim().isEmpty();
                        long sent;
                        int id;
                        synchronized (lock) {
                            id = currentDisplayId;
                            sent = framesSent;
                        }
                        if (present) {
                            log("placement: window(s) of " + packageName + " on hidden display id="
                                    + displayId + " (frames sent=" + sent + ")");
                        } else if (escalate) {
                            log("placement: NO window of " + packageName
                                    + " on hidden display id=" + displayId
                                    + " - retrying via framework start (display option)");
                            startViaFramework(packageName, userId, displayId);
                            schedulePlacementProbe(packageName, userId, displayId, false);
                        } else {
                            log("placement: STILL no window of " + packageName
                                    + " on hidden display id=" + displayId
                                    + " after framework retry (frames sent=" + sent + ")");
                        }
                    } catch (Throwable t) {
                        log("placement probe failed: " + describe(t));
                    } finally {
                        probe.quitSafely();
                    }
                }
            }, 4000);
        } catch (Throwable t) {
            log("placement probe schedule failed: " + describe(t));
        }
    }

    private static final String CRASH_DIR = "/data/local/tmp/bliss-stage";
    private static final String CRASH_FILE = CRASH_DIR + "/crash.log";
    private static final long CRASH_LIMIT = 200_000L;

    private static void writeCrashLog(Thread thread, Throwable throwable) throws Exception {
        File dir = new File(CRASH_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File file = new File(CRASH_FILE);
        if (file.length() > CRASH_LIMIT) {
            file.delete();
        }
        Writer writer = new FileWriter(file, true);
        try {
            writer.write("\n");
            writer.write("--- crash " + new java.util.Date() + " thread=" + thread.getName() + " ---\n");
            Throwable cur = throwable;
            int depth = 0;
            while (cur != null && depth < 6) {
                writer.write(cur.toString() + "\n");
                for (StackTraceElement el : cur.getStackTrace()) {
                    writer.write("      at " + el.toString() + "\n");
                }
                cur = cur.getCause();
                depth++;
            }
        } finally {
            try {
                writer.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Last lines of a previous process's crash log (empty when none). */
    /**
     * The system-wide logcat crash buffer, filtered to this app's process. Unlike
     * filesDir/crashlog it survives uninstall, and unlike the Java CrashLog handler
     * it also captures native (codec/signal) deaths that never reach a Throwable.
     */
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
        } catch (Throwable t) {
            return "";
        }
    }

    /** One-shot display/activity/window state dump when capture keeps missing. */
    private void dumpDisplayState() {
        int id;
        synchronized (lock) {
            id = currentDisplayId;
        }
        if (id <= 0) {
            return;
        }
        log("diagnostics: display=" + id + " — dumping state");
        dumpCommand("display-device", "dumpsys display 2>/dev/null | grep -E 'DisplayDeviceInfo|mDisplayStates|mDisplayId=" + id + "' | head -20");
        dumpCommand("activity", "dumpsys activity activities 2>/dev/null | grep -E 'ResumedActivity|mFocusedApp|displayId=" + id + "' | head -20");
        dumpCommand("power", "dumpsys power 2>/dev/null | grep -E 'mWakefulness|Display Power|mScreenOn' | head -8");
        dumpCommand("window", "dumpsys window displays 2>/dev/null | grep -A30 'displayId=" + id + "' | head -30");
        String pkg;
        synchronized (lock) {
            pkg = currentPackage;
        }
        if (!pkg.isEmpty()) {
            dumpCommand("sf-display", "dumpsys SurfaceFlinger 2>/dev/null | grep -E 'HWC display|mHasContent|state=' | head -16");
        dumpCommand("sf-layers", "dumpsys SurfaceFlinger --list 2>/dev/null | grep -F "
                    + shellQuote(pkg) + " | head -12");
        }
    }

    /**
     * screencap -d <displayId> reads a display's front buffer straight out of
     * SurfaceFlinger. Returns a Bitmap, or null when the capture failed (the
     * shell's own error text is logged so the reason is visible).
     */
    private Bitmap screencapDisplay(int displayId) {
        if (displayId <= 0) {
            return null;
        }
        String path = "/data/local/tmp/bliss-shot-" + displayId + ".png";
        ShellResult r;
        try {
            r = runShell("screencap -d " + displayId + " -p " + path);
        } catch (Throwable t) {
            log("screencap error: " + describe(t));
            return null;
        }
        File file = new File(path);
        byte[] data = null;
        try {
            if (file.exists() && file.length() > 0) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                FileInputStream in = new FileInputStream(file);
                try {
                    byte[] chunk = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(chunk)) > 0) {
                        bos.write(chunk, 0, n);
                    }
                } finally {
                    in.close();
                }
                data = bos.toByteArray();
            }
        } catch (Throwable t) {
            log("screencap read failed: " + describe(t));
        } finally {
            try {
                file.delete();
            } catch (Throwable ignored) {
            }
        }
        if (data == null || data.length == 0) {
            String out = r == null || r.output == null ? "" : r.output.trim();
            log("screencap: no image for display " + displayId + " (exit="
                    + (r == null ? -1 : r.exitCode) + ") " + (out.length() > 120 ? out.substring(0, 120) : out));
            return null;
        }
        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Throwable t) {
            log("screencap decode failed: " + describe(t));
        }
        if (bitmap == null) {
            log("screencap: undecodable image for display " + displayId + " ("
                    + data.length + " bytes)");
        }
        return bitmap;
    }

    private void dumpCommand(String label, String command) {
        try {
            ShellResult r = runShell(command);
            String out = r == null || r.output == null ? "" : r.output.trim();
            if (out.isEmpty()) {
                log("diag[" + label + "]: (empty)");
                return;
            }
            int n = 0;
            for (String line : out.split("\n")) {
                if (n++ >= 24) {
                    log("diag[" + label + "]: …");
                    break;
                }
                log("diag[" + label + "]: " + line.trim());
            }
        } catch (Throwable t) {
            log("diag[" + label + "] failed: " + describe(t));
        }
    }

    private static String crashFileTail() {
        try {
            File file = new File(CRASH_FILE);
            if (!file.exists() || file.length() == 0) {
                return "";
            }
            StringBuilder tail = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(file));
            try {
                java.util.ArrayList<String> lines = new java.util.ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
                int from = Math.max(0, lines.size() - 12);
                for (int i = from; i < lines.size(); i++) {
                    if (tail.length() > 0) {
                        tail.append('\n');
                    }
                    tail.append(lines.get(i));
                }
            } finally {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
            return tail.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** t+Ns quotes emitted after creation so the process death window is visible. */
    private void scheduleAliveTicks(final int displayId) {
        try {
            HandlerThread ops = new HandlerThread("ShellVdOps");
            ops.start();
            Handler handler = new Handler(ops.getLooper());
            for (int n = 1; n <= 4; n++) {
                final long delay = n * 1000L;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        long sent;
                        int id;
                        synchronized (lock) {
                            id = display == null ? -1 : displayId;
                            sent = framesSent;
                        }
                        log("alive t+" + delay / 1000 + "s: display id=" + id
                                + " frames sent=" + sent);
                    }
                }, delay);
            }
        } catch (Throwable t) {
            log("alive ticks failed: " + describe(t));
        }
    }

    /**
     * Starts the app via the framework itself, carrying the target display
     * inside ActivityOptions — bypasses the am binary so OEM builds cannot
     * drop the display argument on cross-user starts.
     */
    private boolean startViaFramework(String packageName, int userId, int displayId) {
        try {
            ShellContext context = contextLocked();
            android.content.Context ctx = context != null ? context : serviceContext;
            if (ctx == null) {
                log("startViaFramework: no context");
                return false;
            }
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_MAIN);
            intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            intent.setPackage(packageName);
            intent.addFlags(0x10000000 | 0x00008000); // NEW_TASK | RESET_TASK_IF_NEEDED
            Class<?> optsClass = Class.forName("android.app.ActivityOptions");
            Object opts = optsClass.getMethod("makeBasic").invoke(null);
            optsClass.getMethod("setLaunchDisplayId", int.class).invoke(opts, displayId);
            android.os.Bundle bundle = (android.os.Bundle) optsClass.getMethod("toBundle").invoke(opts);
            if (userId > 0) {
                android.os.UserHandle user = (android.os.UserHandle) android.os.UserHandle.class
                        .getMethod("of", int.class).invoke(null, userId);
                Throwable last = null;
                for (Class<?> type : new Class[]{android.content.Context.class, ctx.getClass()}) {
                    try {
                        type.getMethod("startActivityAsUser", android.content.Intent.class,
                                        android.os.Bundle.class, android.os.UserHandle.class)
                                .invoke(ctx, intent, bundle, user);
                        log("startViaFramework: dispatched " + packageName
                                + " user " + userId + " display " + displayId);
                        return true;
                    } catch (Throwable t) {
                        last = t;
                    }
                }
                throw last != null ? last : new IllegalStateException("startActivityAsUser unavailable");
            }
            ctx.startActivity(intent, bundle);
            log("startViaFramework: dispatched " + packageName + " display " + displayId);
            return true;
        } catch (Throwable t) {
            log("startViaFramework failed: " + describe(t));
            return false;
        }
    }

    /** First output line of the form {@code pkg/component} (tolerant of padding). */
    private static String firstComponent(ShellResult result, String packageName) {
        if (result.output == null) {
            return "";
        }
        for (String line : result.output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(packageName + "/")) {
                return trimmed;
            }
        }
        return "";
    }

    private static String oneLine(ShellResult result) {
        String output = result == null || result.output == null ? "" : result.output.trim();
        if (output.isEmpty()) {
            return "exit=" + (result == null ? -1 : result.exitCode);
        }
        return ("exit=" + result.exitCode + " " + output).replace('\n', ' ').substring(0, Math.min(220, ("exit=" + result.exitCode + " " + output).length()));
    }

    private static boolean failure(ShellResult result) {
        if (result.exitCode != 0) {
            return true;
        }
        String output = result.output == null ? "" : result.output;
        return output.contains("Error") || output.contains("Exception");
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
            return "unknown error";
        }
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 4) {
            if (depth > 0) {
                sb.append(" | caused by ");
            }
            String cls = cur.getClass().getSimpleName();
            String msg = cur.getMessage();
            sb.append(cls).append(msg == null || msg.isEmpty() ? "" : ": " + msg);
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
