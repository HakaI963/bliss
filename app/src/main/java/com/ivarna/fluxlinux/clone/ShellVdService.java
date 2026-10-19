package com.ivarna.fluxlinux.clone;

import android.content.ComponentName;
import android.os.Parcel;
import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Shell-side (Shizuku UserService, uid 2000) helper.
 *
 * <p>It no longer owns a virtual display: the display, capture and input all
 * live in the vendored ShadowAuto engine (see the {@code shadow-shell} module),
 * which this service starts as an {@code app_process} and which Bliss then
 * drives over JSON-RPC.
 *
 * <p>What is left here is everything that needs shell privileges but is not
 * part of the display engine: staging files into /data/local/tmp, installing
 * staged APKs, starting/stopping the engine process, force-stopping clone
 * apps, and reporting shell-side errors (including the device crash buffer).
 *
 * <p>The class name is referenced by {@link ShellVd#ensure} through
 * {@code UserServiceArgs}; keep the name stable. The service version comes
 * from {@code BuildConfig.VERSION_CODE}.
 */
public class ShellVdService extends IShellVd.Stub {
    private static final String TAG = "ShellVdService";
    private static final String CRASH_FILE = "/data/local/tmp/bliss-stage/crash.log";
    private static final String ENGINE_LOG = "/data/local/tmp/bliss-shadow/engine.log";
    private static final String ENGINE_PID = "/data/local/tmp/bliss-shadow/engine.pid";
    private static final int MAX_LOG_LINES = 300;

    private final Object lock = new Object();
    private final List<String> logBuffer = new ArrayList<>();
    private IShellVdCallback callback;

    static {
        installCrashHandler();
    }

    public ShellVdService() {
    }

    public ShellVdService(android.content.Context context) {
        // Shizuku hands over a context; keep it for the crash handler.
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

    // ---------------------------------------------------------------- engine

    /**
     * Starts the ShadowAuto engine: {@code CLASSPATH=<apk> app_process
     * /system/bin com.silentauto.shell.Main --port=<port>}. Runs detached so
     * the binder call returns immediately; the engine then serves JSON-RPC on
     * loopback.
     */
    @Override
    public String startEngine(String apkPath, int port) {
        if (apkPath == null || apkPath.isEmpty()) {
            return "engine apk path required";
        }
        if (!apkPath.startsWith("/data/local/tmp/")) {
            return "engine apk must live under /data/local/tmp";
        }
        File apk = new File(apkPath);
        if (!apk.exists() || apk.length() == 0) {
            return "engine apk missing: " + apkPath;
        }
        stopEngine();
        try {
            new File("/data/local/tmp/bliss-shadow").mkdirs();
            String command = "CLASSPATH=" + apkPath + " nohup sh -c 'exec app_process /system/bin"
                    + " com.silentauto.shell.Main --port=" + port + "' > " + ENGINE_LOG
                    + " 2>&1 </dev/null & echo $! > " + ENGINE_PID;
            ShellResult result = runShell(command);
            log("engine start: exit=" + result.exitCode + " " + trim(result.output, 200));
            // Give app_process a moment, then report whether it is alive.
            Thread.sleep(600);
            String pid = readPid();
            log("engine pid=" + (pid.isEmpty() ? "?" : pid) + " log=" + ENGINE_LOG);
            return null;
        } catch (Throwable t) {
            String error = "engine start failed: " + describe(t);
            log(error);
            return error;
        }
    }

    @Override
    public String stopEngine() {
        try {
            String pid = readPid();
            if (!pid.isEmpty()) {
                runShell("kill " + pid);
            }
            runShell("pkill -f com.silentauto.shell.Main");
            new File(ENGINE_PID).delete();
            log("engine stopped");
        } catch (Throwable t) {
            log("engine stop failed: " + describe(t));
        }
        return null;
    }

    private String readPid() {
        try {
            File file = new File(ENGINE_PID);
            if (!file.exists()) {
                return "";
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
            try {
                String line = reader.readLine();
                return line == null ? "" : line.trim();
            } finally {
                reader.close();
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ------------------------------------------------------------------ apps

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
    public void exit() {
        try {
            stopEngine();
        } catch (Throwable ignored) {
        }
        Process.killProcess(Process.myPid());
    }

    /** Registers the callback used for live log lines. */
    @Override
    public void attach(IShellVdCallback cb) {
        synchronized (lock) {
            callback = cb;
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
                    java.io.PrintWriter writer = new java.io.PrintWriter(new FileWriter(CRASH_FILE, true));
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
