package com.ivarna.fluxlinux.clone;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Application;
import android.app.Instrumentation;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * System context for the shell (uid 2000) process. Ported from ShadowAuto's
 * ShellContext; the user service process is not a real application process, so
 * activity-thread plumbing has to be faked before framework services work.
 *
 * The heavy lifting is LAZY and retried: a failure inside the old static
 * initializer marked the class erroneous forever (NoClassDefFoundError on
 * every later use — seen on HyperOS). Now every step is individually optional
 * and get() rebuilds the instance on demand after a failed attempt.
 */
final class ShellContext extends ContextWrapper {
    static final String PACKAGE_NAME = "com.android.shell";
    private static volatile ShellContext INSTANCE;

    static void init() {
        Workarounds.apply();
    }

    static ShellContext get() {
        ShellContext i = INSTANCE;
        if (i != null) {
            return i;
        }
        synchronized (ShellContext.class) {
            if (INSTANCE == null) {
                INSTANCE = new ShellContext(Workarounds.systemContext());
            }
            return INSTANCE;
        }
    }

    private ShellContext(Context base) {
        super(base);
    }

    @Override
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public String getOpPackageName() {
        return PACKAGE_NAME;
    }

    @TargetApi(31)
    @Override
    public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(Process.SHELL_UID).setPackageName(PACKAGE_NAME).build();
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @SuppressWarnings("unused")
    public int getDeviceId() {
        return 0;
    }

    @SuppressLint("SoonBlockedPrivateApi")
    @Override
    public Object getSystemService(String name) {
        Object service = super.getSystemService(name);
        if (service == null) {
            return null;
        }
        if (Context.CLIPBOARD_SERVICE.equals(name) || Context.ACTIVITY_SERVICE.equals(name)) {
            try {
                Field field = service.getClass().getDeclaredField("mContext");
                field.setAccessible(true);
                field.set(service, this);
            } catch (Throwable ignored) {
            }
        }
        return service;
    }

    /**
     * Activity-thread plumbing. Every step is optional; nothing may throw from
     * a static initializer (that bricks the class for the whole process life).
     */
    @SuppressLint("PrivateApi,DiscouragedPrivateApi")
    private static final class Workarounds {
        private static Object activityThread;

        private Workarounds() {
        }

        /** Best-effort wiring; each failure is tolerated. */
        static void apply() {
            try {
                ensureActivityThread();
            } catch (Throwable ignored) {
            }
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    fillConfigurationController();
                } catch (Throwable ignored) {
                }
            }
            try {
                fillAppInfo();
            } catch (Throwable ignored) {
            }
            try {
                fillAppContext();
            } catch (Throwable ignored) {
            }
        }

        private static Object ensureActivityThread() throws Exception {
            if (activityThread != null) {
                return activityThread;
            }
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object at = ctor.newInstance();
            Field current = cls.getDeclaredField("sCurrentActivityThread");
            current.setAccessible(true);
            current.set(null, at);
            Field system = cls.getDeclaredField("mSystemThread");
            system.setAccessible(true);
            system.setBoolean(at, true);
            activityThread = at;
            return at;
        }

        /** The system context: getSystemContext, falling back to systemMain(). */
        static Context systemContext() {
            Throwable first = null;
            try {
                Object at = ensureActivityThread();
                Method method = at.getClass().getMethod("getSystemContext");
                return (Context) method.invoke(at);
            } catch (Throwable t) {
                first = t;
            }
            // Plan B: systemMain() runs the full system attach itself and
            // builds the context through a different path.
            try {
                Object at = Class.forName("android.app.ActivityThread")
                        .getMethod("systemMain").invoke(null);
                activityThread = at;
                Method method = at.getClass().getMethod("getSystemContext");
                return (Context) method.invoke(at);
            } catch (Throwable t2) {
                IllegalStateException e = new IllegalStateException("system context unavailable");
                e.addSuppressed(first);
                e.addSuppressed(t2);
                throw e;
            }
        }

        private static void fillAppInfo() throws Exception {
            Object at = ensureActivityThread();
            Class<?> bindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
            Object bindData = bindDataClass.getDeclaredConstructor().newInstance();
            ApplicationInfo appInfo = new ApplicationInfo();
            appInfo.packageName = PACKAGE_NAME;
            Field appInfoField = bindDataClass.getDeclaredField("appInfo");
            appInfoField.setAccessible(true);
            appInfoField.set(bindData, appInfo);
            Field bound = at.getClass().getDeclaredField("mBoundApplication");
            bound.setAccessible(true);
            bound.set(at, bindData);
        }

        private static void fillAppContext() throws Exception {
            Object at = ensureActivityThread();
            Application app = Instrumentation.newApplication(Application.class, get());
            Field field = at.getClass().getDeclaredField("mInitialApplication");
            field.setAccessible(true);
            field.set(at, app);
        }

        private static void fillConfigurationController() throws Exception {
            Object at = ensureActivityThread();
            Class<?> controllerClass = Class.forName("android.app.ConfigurationController");
            Class<?> internalClass = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> ctor = controllerClass.getDeclaredConstructor(internalClass);
            ctor.setAccessible(true);
            Object controller = ctor.newInstance(at);
            Field field = at.getClass().getDeclaredField("mConfigurationController");
            field.setAccessible(true);
            field.set(at, controller);
        }
    }
}
