package com.ivarna.fluxlinux.core.control

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeps the localhost control plane and the clone engine reachable while the
 * user is in another app or in a distro shell.
 *
 * This is intentionally a plain started service, not a foreground service.
 * Android forces every foreground-service notification into the shade, even
 * when its channel is created with IMPORTANCE_NONE, and the control API is a
 * developer/automation surface that should not leave a permanent notification.
 * [ControlServer] is also started directly by the application process, so the
 * service is only a best-effort process-priority hint.
 */
class CloneControlService : Service() {

    override fun onCreate() {
        super.onCreate()
        ControlServer.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ControlServer.start(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // FluxApp owns the process-wide server; do not stop it when Android trims
    // this optional service.
    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        /** Remove the old visible foreground-service channel and notification. */
        fun clearLegacyNotification(context: Context) {
            runCatching {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.cancel(4911)
                manager.cancel(4912)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    manager.deleteNotificationChannel("bliss_control_v1")
                    manager.deleteNotificationChannel("altaris_control_v2")
                }
            }
        }

        fun start(context: Context) {
            clearLegacyNotification(context)
            val intent = Intent(context, CloneControlService::class.java)
            // A normal service is enough: the app starts the HTTP server itself
            // and this service must never post a permanent notification.
            runCatching { context.startService(intent) }
        }
    }
}
