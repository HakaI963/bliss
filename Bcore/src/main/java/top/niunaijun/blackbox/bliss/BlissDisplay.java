package top.niunaijun.blackbox.bliss;

import android.app.ActivityOptions;
import android.content.Context;
import android.os.Bundle;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;

/**
 * Bliss clone-space: tells BlackBox which (virtual) display clone apps should
 * launch on. Written by the Bliss app process as
 * <filesDir>/clone/display.id; read back by whichever BlackBox process performs
 * the real ActivityManager call (daemon :black or a :pN proxy process).
 * 0 = default display (engine behaves exactly as upstream).
 */
public final class BlissDisplay {

    private static final String REL = "clone/display.id";
    private static final int UNSET = Integer.MIN_VALUE;
    private static volatile int sProcessOverride = UNSET;

    private BlissDisplay() {
    }

    /** Force a display id for this process (used by tests / manual launches). */
    public static void setProcessDisplayId(int id) {
        sProcessOverride = id;
    }

    public static int getDisplayId(Context ctx) {
        if (sProcessOverride != UNSET) {
            return sProcessOverride;
        }
        if (ctx == null) {
            return 0;
        }
        try {
            File f = new File(ctx.getFilesDir(), REL);
            if (!f.exists()) {
                return 0;
            }
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(32L, Math.max(1L, f.length()))];
            int n = in.read(buf);
            in.close();
            String s = new String(buf, 0, Math.max(n, 0), "UTF-8").trim();
            int id = Integer.parseInt(s);
            return Math.max(id, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Merge a launch-display id into an existing ActivityOptions bundle. */
    public static Bundle launchOptions(Context ctx, Bundle existing) {
        try {
            int id = getDisplayId(ctx);
            if (id == 0) {
                return existing;
            }
            ActivityOptions opts = null;
            if (existing != null) {
                // ActivityOptions.fromBundle is hidden API; reflection is fine here
                // because the host targets SDK 28 (no hidden-API enforcement).
                try {
                    Method m = ActivityOptions.class.getDeclaredMethod("fromBundle", Bundle.class);
                    m.setAccessible(true);
                    opts = (ActivityOptions) m.invoke(null, existing);
                } catch (Throwable ignored) {
                    opts = null;
                }
            }
            if (opts == null) {
                opts = ActivityOptions.makeBasic();
            }
            opts.setLaunchDisplayId(id);
            return opts.toBundle();
        } catch (Throwable t) {
            return existing;
        }
    }
}
