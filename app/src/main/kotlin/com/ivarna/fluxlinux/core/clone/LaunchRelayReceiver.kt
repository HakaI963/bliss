package com.ivarna.fluxlinux.core.clone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Receives the engine daemon's launch relay (Bcore `BlissLaunchRelay`) and
 * starts the clone proxy activity from this (usually foreground) process.
 *
 * Android 10+ background-activity-start rules — and MIUI's extra "display
 * pop-up windows while running in background" gate — silently drop activity
 * starts issued by background processes like the engine's :black daemon. The
 * relay routes them through the visible host process instead.
 */
class LaunchRelayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val shadow = try {
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra("relay_intent", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("relay_intent") as? Intent
            }
        } catch (t: Throwable) {
            null
        }
        if (shadow == null) {
            CloneEngine.log("Relay: launch intent missing")
            return
        }
        val options = intent.getBundleExtra("relay_options")
        shadow.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(shadow, options)
            CloneEngine.log("Relay: started ${shadow.component?.flattenToShortString() ?: "?"}")
        } catch (t: Throwable) {
            CloneEngine.log("Relay: start failed: ${t.message}")
        }
    }
}
