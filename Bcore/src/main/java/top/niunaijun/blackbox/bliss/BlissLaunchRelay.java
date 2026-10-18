package top.niunaijun.blackbox.bliss;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Bliss clone launches are performed by the HOST app process instead of the
 * engine daemon.
 *
 * Why: Android 10+ background-activity-start rules (and MIUI's extra
 * "display pop-up windows while running in background" gate) silently drop
 * activity starts issued by background processes like :black / :pN. Starting
 * the proxy activity from the visible host process is always allowed.
 *
 * The daemon sends an explicit broadcast to the host receiver
 * (com.ivarna.fluxlinux.clone.LaunchRelayReceiver); the receiver calls
 * startActivity on the (foreground) host context. If the host is not running
 * there is nothing to render anyway, so dropping the launch is correct.
 */
public final class BlissLaunchRelay {

    public static final String ACTION = "com.nopiece.bliss.CLONE_LAUNCH_RELAY";
    public static final String EXTRA_INTENT = "relay_intent";
    public static final String EXTRA_OPTIONS = "relay_options";

    private static volatile boolean sDisabled = false;

    /** Fallback to a direct start (tests / no host receiver available). */
    public static void setDisabled(boolean disabled) {
        sDisabled = disabled;
    }

    public static void start(Context ctx, Intent shadow, Bundle options) {
        if (!sDisabled) {
            try {
                Intent relay = new Intent(ACTION);
                relay.setPackage(ctx.getPackageName());
                relay.putExtra(EXTRA_INTENT, shadow);
                if (options != null) {
                    relay.putExtra(EXTRA_OPTIONS, options);
                }
                ctx.sendBroadcast(relay);
                return;
            } catch (Throwable ignored) {
                // fall through to the direct start
            }
        }
        try {
            ctx.startActivity(shadow, options);
        } catch (Throwable ignored) {
        }
    }

    private BlissLaunchRelay() {
    }
}
