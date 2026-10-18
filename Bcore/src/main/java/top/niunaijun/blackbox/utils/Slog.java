

package top.niunaijun.blackbox.utils;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import top.niunaijun.blackbox.BlackBoxCore;


public final class Slog {
     public static final int LOG_ID_SYSTEM = 3;

    // Bliss: tee engine logs into the host files dir (files/logs/blackbox.log)
    // so Tools > Diagnostics and Apps > Logs can show, on-device, why a clone
    // launch failed - without adb/logcat. Bounded to 256 KB with one rotation.
    private static final long MAX_BYTES = 256 * 1024;
    private static final Object LOCK = new Object();

    private Slog() {
    }

    private static void tee(String tag, String msg) {
        try {
            android.content.Context ctx = BlackBoxCore.getContext();
            if (ctx == null || msg == null) {
                return;
            }
            synchronized (LOCK) {
                File dir = new File(ctx.getFilesDir(), "logs");
                if (!dir.exists() && !dir.mkdirs()) {
                    return;
                }
                File f = new File(dir, "blackbox.log");
                if (f.length() > MAX_BYTES) {
                    File old = new File(dir, "blackbox.old.log");
                    if (old.exists()) {
                        old.delete();
                    }
                    f.renameTo(old);
                }
                String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                        + " " + tag + ": " + msg + "\n";
                FileWriter w = new FileWriter(f, true);
                try {
                    w.write(line);
                } finally {
                    w.close();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static int v(String tag, String msg) {
        tee(tag, msg);
        return Log.println(Log.VERBOSE, tag, msg);
    }

    public static int v(String tag, String msg, Throwable tr) {
        return Log.println(Log.VERBOSE, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    
    public static int d(String tag, String msg) {
        tee(tag, msg);
        return Log.println(Log.DEBUG, tag, msg);
    }

    
    public static int d(String tag, String msg, Throwable tr) {
        return Log.println(Log.DEBUG, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    
    public static int i(String tag, String msg) {
        tee(tag, msg);
        return Log.println(Log.INFO, tag, msg);
    }

    public static int i(String tag, String msg, Throwable tr) {
        return Log.println(Log.INFO, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    
    public static int w(String tag, String msg) {
        tee(tag, msg);
        return Log.println(Log.WARN, tag, msg);
    }

    
    public static int w(String tag, String msg, Throwable tr) {
        return Log.println(Log.WARN, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int w(String tag, Throwable tr) {
        tee(tag, Log.getStackTraceString(tr));
        return Log.println(Log.WARN, tag, Log.getStackTraceString(tr));
    }

    
    public static int e(String tag, String msg) {
        tee(tag, msg);
        return Log.println(Log.ERROR, tag, msg);
    }

    
    public static int e(String tag, String msg, Throwable tr) {
        tee(tag, msg + '\n' + Log.getStackTraceString(tr));
        return Log.println(Log.ERROR, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int println(int priority, String tag, String msg) {
        return Log.println(priority, tag, msg);
    }
}

