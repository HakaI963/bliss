package com.ivarna.fluxlinux.clone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import top.niunaijun.blackbox.bliss.BlissLaunchRelay

/**
 * The engine daemon cannot start proxy activities from the background on
 * Android 10+, so it broadcasts the launch here. This receiver runs in the
 * visible host process, which is allowed to start the cloned app's proxy.
 */
class LaunchRelayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = intent.getParcelableExtra<Intent>(BlissLaunchRelay.EXTRA_INTENT) ?: return
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = intent.getBundleExtra(BlissLaunchRelay.EXTRA_OPTIONS)
        runCatching { context.startActivity(target, options) }
            .onFailure { Log.w("BlissRelay", "clone launch relay failed", it) }
    }
}
