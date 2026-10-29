package com.altaris.linux.clone.engine;

import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shell-side state machine: owns the virtual displays, the input bridge and the
 * clone manager, and answers Binder commands from the UI process.
 *
 * <p>Displays are kept in a session map keyed by the clone package (or an
 * arbitrary label). Creating a display for one key never touches another key,
 * which is what lets several clones — and therefore several agents — run at the
 * same time. The only shared resources are the input bridge (it injects per
 * display id) and the wake lock (reference counted by the session count).
 */
final class DisplayEngine {
    private static final String TAG = "AltarisEngine";
    private static final int MAX_LOG_LINES = 400;
    private static final int MAX_SESSIONS = 6;

    private final Context context;
    private final Object lock = new Object();
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final CloneManager clones;
    private final String controlToken;

    /** Live sessions, oldest first. Key is the clone package or a caller label. */
    private final LinkedHashMap<String, VirtualDisplaySession> sessions = new LinkedHashMap<>();
    /** Session the UI viewer is attached to; "" when none. */
    private String viewerKey = "";
    private InputBridge input;
    private PowerManager.WakeLock wakeLock;
    private String lastError = "";
    private final long createdUptime = SystemClock.uptimeMillis();

    DisplayEngine(Context context) {
        if (context == null) {
            throw new IllegalStateException("no Context available in the shell service");
        }
        this.context = context;
        this.clones = new CloneManager(context, this);
        this.controlToken = loadOrCreateControlToken();
        log("engine created: uid=" + Process.myUid()
                + " sdk=" + Build.VERSION.SDK_INT
                + " model=" + Build.MODEL
                + " product=" + Build.PRODUCT);
    }

    // ---------------------------------------------------------------- status

    /** Display id of the viewer session, or of the newest session, or -1. */
    int displayId() {
        synchronized (lock) {
            VirtualDisplaySession session = resolveLocked("");
            return session == null ? -1 : session.displayId;
        }
    }

    int displayIdFor(String key) {
        synchronized (lock) {
            VirtualDisplaySession session = resolveLocked(key);
            return session == null ? -1 : session.displayId;
        }
    }

    String statusJson() {
        JSONObject object = new JSONObject();
        synchronized (lock) {
            try {
                VirtualDisplaySession session = resolveLocked("");
                object.put("service_version", Protocol.VERSION);
                object.put("uid", Process.myUid());
                object.put("sdk", Build.VERSION.SDK_INT);
                object.put("model", Build.MODEL);
                object.put("uptime_ms", SystemClock.uptimeMillis() - createdUptime);
                object.put("display_ready", session != null);
                object.put("display_id", session == null ? -1 : session.displayId);
                object.put("display_width", session == null ? 0 : session.width);
                object.put("display_height", session == null ? 0 : session.height);
                object.put("display_dpi", session == null ? 0 : session.dpi);
                object.put("display_state", session == null ? "" : session.stateName());
                object.put("display_flags", session == null ? "" : session.flagsLabel());
                object.put("display_frames", session == null ? 0 : session.frameCount());
                object.put("display_last_frame_ms", session == null ? 0 : session.lastFrameAt());
                object.put("display_rotation", session == null ? -1 : session.rotation());
                object.put("display_refresh_rate", session == null ? 0 : session.refreshRate());
                object.put("ime_local", session != null && session.localIme());
                object.put("input_ready", input != null && input.available());
                object.put("input_error", input == null
                        ? "not initialised" : input.unavailableReason());
                object.put("session_count", sessions.size());
                object.put("viewer_key", viewerKey);
                object.put("sessions", sessionsJsonLocked());
                object.put("error", lastError);
            } catch (Throwable ignored) {
            }
        }
        return object.toString();
    }

    String sessionsJson() {
        synchronized (lock) {
            return sessionsJsonLocked().toString();
        }
    }

    private JSONArray sessionsJsonLocked() {
        JSONArray array = new JSONArray();
        for (Map.Entry<String, VirtualDisplaySession> entry : sessions.entrySet()) {
            VirtualDisplaySession session = entry.getValue();
            try {
                JSONObject item = new JSONObject();
                item.put("key", entry.getKey());
                item.put("package", entry.getKey());
                item.put("display_id", session.displayId);
                item.put("width", session.width);
                item.put("height", session.height);
                item.put("dpi", session.dpi);
                item.put("state", session.stateName());
                item.put("frames", session.frameCount());
                item.put("rotation", session.rotation());
                item.put("viewer", entry.getKey().equals(viewerKey));
                array.put(item);
            } catch (Throwable ignored) {
            }
        }
        return array;
    }

    String listAppsJson() {
        try {
            return AppCatalog.appsJson(context);
        } catch (Throwable e) {
            return "[]";
        }
    }

    String logTailJson(int maxLines) {
        JSONArray array = new JSONArray();
        synchronized (logLines) {
            int skip = Math.max(0, logLines.size() - Math.max(1, maxLines));
            int index = 0;
            for (String line : logLines) {
                if (index++ >= skip) {
                    array.put(line);
                }
            }
        }
        return array.toString();
    }

    // --------------------------------------------------------------- display

    /**
     * Creates (or recreates) the display for [key] and makes it the viewer
     * session. Other sessions keep running untouched.
     */
    String createDisplay(String key, int width, int height, int dpi, int fps) {
        synchronized (lock) {
            String session = normalizeKey(key);
            if (session.isEmpty()) {
                return Jsons.error("display session key is required");
            }
            if (!sessions.containsKey(session) && sessions.size() >= MAX_SESSIONS) {
                return Jsons.error("at most " + MAX_SESSIONS
                        + " displays can run at once; stop a clone first");
            }
            releaseLocked(session);
            int safeWidth = clamp(width, 320, 2160, 720);
            int safeHeight = clamp(height, 480, 3840, 1280);
            int safeDpi = clamp(dpi, 120, 640, 320);
            int safeFps = clamp(fps, 10, 240, 60);
            try {
                VirtualDisplaySession created = VirtualDisplaySession.create(
                        context, safeWidth, safeHeight, safeDpi, safeFps);
                sessions.put(session, created);
                viewerKey = session;
                boolean on = created.ensureOn();
                ensureInput();
                acquireWakeLock();
                lastError = "";
                log("display created key=" + session + " id=" + created.displayId
                        + " " + safeWidth + "x" + safeHeight + " @" + safeDpi
                        + " " + safeFps + "fps"
                        + " state=" + created.stateName()
                        + (on ? "" : " (still not ON after surface toggle)")
                        + " flags=" + created.flagsLabel()
                        + " sessions=" + sessions.size());
                return Jsons.display("display " + created.displayId + " " + session + " "
                        + safeWidth + "x" + safeHeight + " @" + safeDpi
                        + " " + safeFps + "fps"
                        + " state=" + created.stateName(),
                        created.displayId, session);
            } catch (Throwable e) {
                lastError = message(e);
                log("display create failed for " + session + ": " + message(e));
                return Jsons.error(message(e));
            }
        }
    }

    /** Releases one session, or every session when [key] is empty. */
    String releaseDisplay(String key) {
        synchronized (lock) {
            String session = normalizeKey(key);
            if (session.isEmpty()) {
                int count = sessions.size();
                if (count == 0) {
                    return Jsons.ok("no display");
                }
                releaseAllLocked();
                log("all " + count + " display(s) released");
                return Jsons.ok("released " + count + " display(s)");
            }
            VirtualDisplaySession existing = sessions.get(session);
            if (existing == null) {
                return Jsons.ok("no display for " + session);
            }
            int id = existing.displayId;
            releaseLocked(session);
            log("display " + id + " (" + session + ") released");
            return Jsons.ok("display " + id + " released");
        }
    }

    void releaseAll() {
        synchronized (lock) {
            releaseAllLocked();
        }
    }

    private void releaseAllLocked() {
        List<String> keys = new ArrayList<>(sessions.keySet());
        for (String key : keys) {
            releaseLocked(key);
        }
        viewerKey = "";
        releaseWakeLock();
    }

    /** Drops one session: removes its tasks first so nothing migrates to the phone. */
    private void releaseLocked(String key) {
        VirtualDisplaySession session = sessions.remove(key);
        if (session != null) {
            // The clone task still lives on the virtual display. Releasing the
            // display while it is the top task makes Android migrate that task
            // to the default display: the clone flashes full-screen on the
            // phone. Remove the display's root task first (force-stop alone
            // leaves the task record behind).
            removeDisplayTasks(session.displayId);
            try {
                session.release();
            } catch (Throwable ignored) {
            }
        }
        if (key.equals(viewerKey)) {
            viewerKey = sessions.isEmpty() ? "" : newestKey();
        }
        if (sessions.isEmpty()) {
            releaseWakeLock();
        }
    }

    private String newestKey() {
        String last = "";
        for (String key : sessions.keySet()) {
            last = key;
        }
        return last;
    }

    /** Blank key resolves to the viewer session, then to the newest session. */
    private VirtualDisplaySession resolveLocked(String key) {
        String session = normalizeKey(key);
        if (session.isEmpty()) {
            if (!viewerKey.isEmpty() && sessions.containsKey(viewerKey)) {
                return sessions.get(viewerKey);
            }
            String newest = newestKey();
            return newest.isEmpty() ? null : sessions.get(newest);
        }
        return sessions.get(session);
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.trim();
    }

    /** Removes every root task hosted by [displayId] before the display dies. */
    private void removeDisplayTasks(int displayId) {
        try {
            AppCatalog.CommandResult list = AppCatalog.run("am stack list", 10);
            if (!list.ok()) {
                log("stack list failed: " + oneLine(list.output));
                return;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("RootTask id=(\\d+)[^\\n]*displayId=" + displayId + "\\b")
                    .matcher(list.output);
            while (matcher.find()) {
                String stackId = matcher.group(1);
                AppCatalog.CommandResult removed =
                        AppCatalog.run("am stack remove " + stackId, 10);
                log("removed stack " + stackId + " on display " + displayId
                        + " exit=" + removed.exit);
            }
        } catch (Throwable e) {
            log("remove display tasks failed: " + message(e));
        }
    }

    /**
     * Keeps the physical screen on while at least one virtual display exists.
     * HyperOS suspends virtual displays together with the phone screen; without
     * this the clones freeze as soon as the screen times out.
     */
    private void acquireWakeLock() {
        if (wakeLock != null) {
            return;
        }
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power == null) {
                return;
            }
            wakeLock = power.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "Altaris:virtual-display");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            log("wake lock acquired (screen stays on while a display exists)");
        } catch (Throwable e) {
            wakeLock = null;
            log("wake lock failed: " + e);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock == null) {
            return;
        }
        try {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
        wakeLock = null;
        log("wake lock released");
    }

    private void ensureInput() {
        if (input == null) {
            input = new InputBridge(context);
            log("input bridge: " + (input.available()
                    ? "ready" : "unavailable: " + input.unavailableReason()));
        }
    }

    // --------------------------------------------------------------- actions

    String launchApp(String packageName, String key, boolean forceStop) {
        int display;
        synchronized (lock) {
            VirtualDisplaySession session = resolveLocked(key);
            if (session == null) {
                return Jsons.error("no virtual display for "
                        + (normalizeKey(key).isEmpty() ? "the viewer" : key)
                        + "; create one first");
            }
            display = session.displayId;
        }
        if (packageName == null || packageName.trim().isEmpty()) {
            return Jsons.error("empty package name");
        }
        try {
            AppCatalog.CommandResult result = AppCatalog.launchPackage(
                    context, packageName.trim(), display, forceStop);
            log("launch " + packageName + " on display " + display
                    + " exit=" + result.exit + " " + oneLine(result.output));
            return result.ok()
                    ? Jsons.ok(result.output.isEmpty() ? "launched" : result.output)
                    : Jsons.error("am start exit=" + result.exit + " " + oneLine(result.output));
        } catch (Throwable e) {
            log("launch failed: " + message(e));
            return Jsons.error(message(e));
        }
    }

    String tap(String key, int x, int y) {
        return inputAction("tap", key, (bridge, display) -> {
            boolean ok = bridge.tap(display, x, y);
            return ok ? Jsons.ok("tap injected at " + x + "," + y + " on display " + display)
                    : Jsons.error("injectInputEvent returned false");
        });
    }

    String swipe(String key, int x1, int y1, int x2, int y2, int durationMs) {
        return inputAction("swipe", key, (bridge, display) -> {
            boolean ok = bridge.swipe(display, x1, y1, x2, y2, durationMs);
            return ok ? Jsons.ok("swipe injected on display " + display)
                    : Jsons.error("injectInputEvent returned false");
        });
    }

    String key(String key, int keyCode) {
        return inputAction("key", key, (bridge, display) -> {
            boolean ok = bridge.key(display, keyCode);
            return ok ? Jsons.ok("key injected on display " + display)
                    : Jsons.error("injectInputEvent returned false");
        });
    }

    String text(String key, String value) {
        return inputAction("text", key, (bridge, display) -> {
            boolean ok = bridge.text(display, value);
            return ok ? Jsons.ok("text injected on display " + display)
                    : Jsons.error("injectInputEvent returned false");
        });
    }

    private interface InputCall {
        String run(InputBridge input, int displayId) throws Exception;
    }

    private String inputAction(String name, String key, InputCall call) {
        InputBridge bridge;
        int display;
        synchronized (lock) {
            ensureInput();
            bridge = input;
            VirtualDisplaySession session = resolveLocked(key);
            if (session == null) {
                return Jsons.error("no virtual display; create one first");
            }
            display = session.displayId;
        }
        if (bridge == null || !bridge.available()) {
            return Jsons.error("input unavailable: "
                    + (bridge == null ? "not initialised" : bridge.unavailableReason()));
        }
        try {
            String result = call.run(bridge, display);
            log(name + " on display " + display + " -> " + oneLine(result));
            return result;
        } catch (Throwable e) {
            log(name + " failed: " + message(e));
            return Jsons.error(message(e));
        }
    }

    byte[] captureJpeg(String key, int timeoutMs, int quality) {
        VirtualDisplaySession session;
        synchronized (lock) {
            session = resolveLocked(key);
        }
        if (session == null) {
            return null;
        }
        return session.captureJpeg(timeoutMs, clamp(quality, 10, 100, 80));
    }

    // ------------------------------------------------------------------ auth

    boolean auth(String token) {
        return token != null && controlToken.equals(token.trim());
    }

    private String loadOrCreateControlToken() {
        File dir = new File("/data/local/tmp/altaris");
        File file = new File(dir, "control.token");
        try {
            if (file.exists()) {
                byte[] buffer = new byte[(int) Math.min(128, file.length())];
                try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                    int read = in.read(buffer);
                    if (read > 0) {
                        String existing = new String(buffer, 0, read, "UTF-8").trim();
                        if (existing.matches("[0-9a-f]{32}")) {
                            log("control token loaded: " + file.getAbsolutePath());
                            return existing;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String token = newControlToken();
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                log("cannot create control token directory");
                return token;
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(token.getBytes("UTF-8"));
            }
            AppCatalog.run("chmod 770 " + dir.getAbsolutePath()
                    + "; chmod 640 " + file.getAbsolutePath());
            log("control token created: " + file.getAbsolutePath());
        } catch (Throwable e) {
            log("control token setup failed: " + e);
        }
        return token;
    }

    private static String newControlToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value));
        }
        return builder.toString();
    }

    // ----------------------------------------------------------------- clones

    String cloneListJson() {
        return clones.listJson();
    }

    String cloneFromInstalled(String packageName, String name) {
        return clones.createFromInstalled(packageName, name);
    }

    String cloneFromApk(ParcelFileDescriptor descriptor, String name) {
        return clones.createFromApk(descriptor, name);
    }

    String cloneFromPath(String path, String name) {
        return clones.createFromPath(path, name);
    }

    String cloneLaunch(String clonePackage, String key) {
        return clones.launch(clonePackage, key);
    }

    String cloneKill(String clonePackage) {
        return clones.kill(clonePackage);
    }

    String cloneDelete(String clonePackage) {
        return clones.delete(clonePackage);
    }

    String cloneLogs(String clonePackage, int maxLines) {
        return clones.logsJson(clonePackage, maxLines);
    }

    // ----------------------------------------------------------------- utils

    void log(String message) {
        String line = String.format(Locale.US, "[%.2fs] %s",
                (SystemClock.uptimeMillis() - createdUptime) / 1000.0, message);
        synchronized (logLines) {
            logLines.addLast(line);
            while (logLines.size() > MAX_LOG_LINES) {
                logLines.removeFirst();
            }
        }
        Log.i(TAG, line);
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').trim();
    }

    private static String message(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static int clamp(int value, int minimum, int maximum, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
