package com.altaris.linux.clone.engine;

import android.content.Context;
import android.os.Binder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

/**
 * Shizuku UserService entry point (runs as shell/root in a dedicated process).
 *
 * <p>Direct Binder transactions instead of ShadowAuto's JSON-RPC server
 * (Apache-2.0). JSON commands answer with a JSON string; frame commands answer
 * with {@code int ok, String error, byte[] jpeg}.
 *
 * <p>Display-scoped commands read the session key first. The key is the clone
 * package, so each clone owns its own virtual display and input stream.
 */
public class DisplayService extends Binder {
    private static final String TAG = "AltarisEngine";
    private static volatile DisplayEngine engine;

    /** No-arg constructor used by Shizuku releases that do not pass a Context. */
    public DisplayService() {
        this(null);
    }

    /** Context constructor used by Shizuku 13+ (the app's Application object). */
    public DisplayService(Context context) {
        super();
        install(context);
    }

    private static synchronized void install(Context context) {
        if (engine != null) {
            return;
        }
        Context resolved = context;
        if (resolved == null) {
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                java.lang.reflect.Method current = activityThread.getMethod("currentApplication");
                resolved = (Context) current.invoke(null);
            } catch (Throwable ignored) {
            }
        }
        try {
            engine = new DisplayEngine(resolved);
        } catch (Throwable e) {
            Log.e(TAG, "engine init failed", e);
        }
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code != Protocol.CMD_STATUS && code != Protocol.CMD_SHUTDOWN
                && code != Protocol.CMD_SESSIONS
                && (code < Protocol.CMD_CREATE || code > Protocol.CMD_CLONE_FROM_PATH)) {
            return super.onTransact(code, data, reply, flags);
        }
        try {
            handle(code, data, reply);
        } catch (Throwable e) {
            Log.e(TAG, "command " + code + " failed", e);
            try {
                if (isFrameCommand(code)) {
                    reply.writeInt(0);
                    reply.writeString(Jsons.error(e).replace("\"", "'"));
                    reply.writeByteArray(new byte[0]);
                } else {
                    reply.writeString(Jsons.error(e));
                }
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    private static boolean isFrameCommand(int code) {
        return code == Protocol.CMD_CAPTURE;
    }

    private void handle(int code, Parcel data, Parcel reply) {
        DisplayEngine local = engine;
        if (local == null && code != Protocol.CMD_SHUTDOWN) {
            install(null);
            local = engine;
        }
        if (local == null && code != Protocol.CMD_SHUTDOWN) {
            reply.writeString(Jsons.error("engine unavailable"));
            return;
        }

        switch (code) {
            case Protocol.CMD_SHUTDOWN: {
                if (local != null) {
                    local.releaseAll();
                }
                reply.writeString(Jsons.ok("shutdown requested"));
                new Thread(() -> {
                    SystemClock.sleep(200);
                    System.exit(0);
                }, "headless-exit").start();
                return;
            }
            case Protocol.CMD_STATUS: {
                reply.writeString(local.statusJson());
                return;
            }
            case Protocol.CMD_SESSIONS: {
                reply.writeString(local.sessionsJson());
                return;
            }
            case Protocol.CMD_LOG_TAIL: {
                reply.writeString(local.logTailJson(data.readInt()));
                return;
            }
            case Protocol.CMD_CREATE: {
                String key = data.readString();
                int width = data.readInt();
                int height = data.readInt();
                int dpi = data.readInt();
                int fps = data.readInt();
                reply.writeString(local.createDisplay(key, width, height, dpi, fps));
                return;
            }
            case Protocol.CMD_RELEASE: {
                String key = data.readString();
                reply.writeString(local.releaseDisplay(key));
                return;
            }
            case Protocol.CMD_LIST_APPS: {
                reply.writeString(local.listAppsJson());
                return;
            }
            case Protocol.CMD_LAUNCH_APP: {
                String packageName = data.readString();
                String key = data.readString();
                boolean forceStop = data.readInt() == 1;
                reply.writeString(local.launchApp(packageName, key, forceStop));
                return;
            }
            case Protocol.CMD_CAPTURE: {
                String key = data.readString();
                int timeoutMs = data.readInt();
                int quality = data.readInt();
                byte[] jpeg = local.captureJpeg(key, timeoutMs, quality);
                writeFrame(reply, jpeg, jpeg == null ? "no frame available yet" : "");
                return;
            }
            case Protocol.CMD_TAP: {
                String key = data.readString();
                reply.writeString(local.tap(key, data.readInt(), data.readInt()));
                return;
            }
            case Protocol.CMD_SWIPE: {
                String key = data.readString();
                int x1 = data.readInt();
                int y1 = data.readInt();
                int x2 = data.readInt();
                int y2 = data.readInt();
                int duration = data.readInt();
                reply.writeString(local.swipe(key, x1, y1, x2, y2, duration));
                return;
            }
            case Protocol.CMD_KEY: {
                String key = data.readString();
                reply.writeString(local.key(key, data.readInt()));
                return;
            }
            case Protocol.CMD_TEXT: {
                String key = data.readString();
                reply.writeString(local.text(key, data.readString()));
                return;
            }

            // ------------------------------------------------------ clones

            case Protocol.CMD_CLONE_LIST: {
                reply.writeString(local.cloneListJson());
                return;
            }
            case Protocol.CMD_CLONE_CREATE_INSTALLED: {
                String packageName = data.readString();
                String name = data.readString();
                reply.writeString(local.cloneFromInstalled(packageName, name));
                return;
            }
            case Protocol.CMD_CLONE_CREATE_APK: {
                String name = data.readString();
                ParcelFileDescriptor descriptor =
                        ParcelFileDescriptor.CREATOR.createFromParcel(data);
                reply.writeString(local.cloneFromApk(descriptor, name));
                return;
            }
            case Protocol.CMD_CLONE_LAUNCH: {
                String clonePackage = data.readString();
                String key = data.readString();
                reply.writeString(local.cloneLaunch(clonePackage, key));
                return;
            }
            case Protocol.CMD_CLONE_KILL: {
                String clonePackage = data.readString();
                reply.writeString(local.cloneKill(clonePackage));
                return;
            }
            case Protocol.CMD_CLONE_DELETE: {
                String clonePackage = data.readString();
                reply.writeString(local.cloneDelete(clonePackage));
                return;
            }
            case Protocol.CMD_CLONE_LOGS: {
                String clonePackage = data.readString();
                int maxLines = data.readInt();
                reply.writeString(local.cloneLogs(clonePackage, maxLines));
                return;
            }
            case Protocol.CMD_CLONE_FROM_PATH: {
                String path = data.readString();
                String name = data.readString();
                reply.writeString(local.cloneFromPath(path, name));
                return;
            }
            case Protocol.CMD_AUTH: {
                reply.writeString(local.auth(data.readString())
                        ? Jsons.ok("authenticated")
                        : Jsons.error("bad control token"));
                return;
            }
            default: {
                reply.writeString(Jsons.error("unknown command " + code));
            }
        }
    }

    private static void writeFrame(Parcel reply, byte[] jpeg, String error) {
        boolean ok = jpeg != null && jpeg.length > 0;
        reply.writeInt(ok ? 1 : 0);
        reply.writeString(ok ? "" : error);
        reply.writeByteArray(ok ? jpeg : new byte[0]);
    }
}
