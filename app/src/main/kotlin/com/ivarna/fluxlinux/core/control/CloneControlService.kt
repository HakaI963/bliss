package com.ivarna.fluxlinux.core.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ivarna.fluxlinux.MainActivity
import com.ivarna.fluxlinux.R

/**
 * Keeps the localhost control plane and the clone engine reachable while the
 * user is in another app or in a distro shell.
 *
 * The service still needs a foreground notification to satisfy Android's
 * process-lifetime rules, but its channel is deliberately IMPORTANCE_NONE so
 * it never appears in the shade. The loopback API is a developer surface, not
 * a user-facing notification.
 */
class CloneControlService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        ControlServer.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        ControlServer.start(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ControlServer.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            // Remove the old visible channel once, then keep a silent channel
            // that Android accepts for the FGS but does not render in the shade.
            runCatching { manager.deleteNotificationChannel("bliss_control_v1") }
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Altaris background engine",
                        NotificationManager.IMPORTANCE_NONE
                    ).apply {
                        description = "Local clone control API and MCP server"
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                        lockscreenVisibility = Notification.VISIBILITY_SECRET
                    }
                )
            }
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle("Altaris engine")
            .setContentText("Local control API")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "altaris_control_v2"
        private const val NOTIFICATION_ID = 4911

        fun start(context: Context) {
            val intent = Intent(context, CloneControlService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
