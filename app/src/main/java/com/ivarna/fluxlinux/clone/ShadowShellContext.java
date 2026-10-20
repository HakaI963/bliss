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
 * System context for the shell-side (uid 2000) display owner.
 *
 * <p>Ported from ShadowAuto's {@code ShellContext} (Apache-2.0) — see
 * shadow-shell/LICENSE. ShadowAuto builds the context in a static initialiser,
 * which can brick the whole process when any of the reflective steps throws
 * (the failure then happens before any caller can react). Here the context is
 * created lazily and every step is individually guarded, so a ROM that
 * refuses one of them still yields a usable context.
 */
final class ShadowShellContext extends ContextWrapper {
    static final String PACKAGE_NAME = "com.android.shell";

    private static volatile ShadowShellContext instance;
    private final boolean fabricated;

    static ShadowShellContext get() {
        ShadowShellContext local = instance;
        if (local == null) {
            synchronized (ShadowShellContext.class) {
                local = instance;
                if (local == null) {
                    local = new ShadowShellContext();
                    Workarounds.apply(local);
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * Prefer the real context Shizuku passes to the user service. The
     * fabricated ActivityThread context is only a fallback: on newer ROMs the
     * reflective ActivityThread surgery fails, which used to make every
     * createVirtualDisplay() call return -1.
     */
    static ShadowShellContext from(android.content.Context base) {
        if (base != null) {
            try {
                return new ShadowShellContext(base);
            } catch (Throwable ignored) {
                // fall through to the fabricated shell context
            }
        }
        return get();
    }

    private ShadowShellContext(android.content.Context base) {
        super(base);
        this.fabricated = false;
    }

    private ShadowShellContext() {
        super(Workarounds.systemContext());
        this.fabricated = true;
    }

    String describeSource() {
        return fabricated ? "fabricated com.android.shell" : "Shizuku service context";
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

    @SuppressWarnings("unused")
    public int getDeviceId() {
        return 0;
    }

    @Override
    public Context getApplicationContext() {
        return this;
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

    /** The reflective parts of ShadowAuto's ShellContext, made failure-proof. */
    @SuppressLint("PrivateApi,DiscouragedPrivateApi")
    private static final class Workarounds {
        private static final Class<?> ACTIVITY_THREAD_CLASS;
        private static final Object ACTIVITY_THREAD;

        static {
            Class<?> threadClass = null;
            Object thread = null;
            try {
                threadClass = Class.forName("android.app.ActivityThread");
                Constructor<?> ctor = threadClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                thread = ctor.newInstance();
                Field current = threadClass.getDeclaredField("sCurrentActivityThread");
                current.setAccessible(true);
                current.set(null, thread);
                Field system = threadClass.getDeclaredField("mSystemThread");
                system.setAccessible(true);
                system.setBoolean(thread, true);
            } catch (Throwable ignored) {
                // Leave whatever we managed to build; getSystemContext() below
                // reports the real problem if it cannot work.
            }
            ACTIVITY_THREAD_CLASS = threadClass;
            ACTIVITY_THREAD = thread;
        }

        static void apply(ShadowShellContext context) {
            if (Build.VERSION.SDK_INT >= 31) {
                fillConfigurationController();
            }
            fillAppInfo();
            fillAppContext(context);
        }

        static Context systemContext() {
            Object thread = ACTIVITY_THREAD;
            Class<?> threadClass = ACTIVITY_THREAD_CLASS;
            if (thread == null) {
                // Some ROMs refuse the reflective ActivityThread constructor;
                // use the process's existing thread when there is one.
                try {
                    if (threadClass == null) {
                        threadClass = Class.forName("android.app.ActivityThread");
                    }
                    Method current = threadClass.getDeclaredMethod("currentActivityThread");
                    current.setAccessible(true);
                    thread = current.invoke(null);
                } catch (Throwable ignored) {
                }
            }
            if (thread == null || threadClass == null) {
                throw new IllegalStateException("no ActivityThread available for the shell context");
            }
            try {
                Method method = threadClass.getDeclaredMethod("getSystemContext");
                method.setAccessible(true);
                Context context = (Context) method.invoke(thread);
                if (context == null) {
                    throw new IllegalStateException("getSystemContext() returned null");
                }
                return context;
            } catch (Throwable e) {
                throw new IllegalStateException("system context unavailable", e);
            }
        }

        private static void fillAppInfo() {
            try {
                Class<?> bindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
                Object bindData = bindDataClass.getDeclaredConstructor().newInstance();
                ApplicationInfo appInfo = new ApplicationInfo();
                appInfo.packageName = PACKAGE_NAME;
                Field appInfoField = bindDataClass.getDeclaredField("appInfo");
                appInfoField.setAccessible(true);
                appInfoField.set(bindData, appInfo);
                Field bound = ACTIVITY_THREAD_CLASS.getDeclaredField("mBoundApplication");
                bound.setAccessible(true);
                bound.set(ACTIVITY_THREAD, bindData);
            } catch (Throwable ignored) {
            }
        }

        private static void fillAppContext(ShadowShellContext context) {
            try {
                Application app = Instrumentation.newApplication(Application.class, context);
                Field field = ACTIVITY_THREAD_CLASS.getDeclaredField("mInitialApplication");
                field.setAccessible(true);
                field.set(ACTIVITY_THREAD, app);
            } catch (Throwable ignored) {
            }
        }

        private static void fillConfigurationController() {
            try {
                Class<?> controllerClass = Class.forName("android.app.ConfigurationController");
                Class<?> internalClass = Class.forName("android.app.ActivityThreadInternal");
                Constructor<?> ctor = controllerClass.getDeclaredConstructor(internalClass);
                ctor.setAccessible(true);
                Object controller = ctor.newInstance(ACTIVITY_THREAD);
                Field field = ACTIVITY_THREAD_CLASS.getDeclaredField("mConfigurationController");
                field.setAccessible(true);
                field.set(ACTIVITY_THREAD, controller);
            } catch (Throwable ignored) {
            }
        }
    }
}
