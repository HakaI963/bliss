package com.ivarna.fluxlinux.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ivarna.fluxlinux.MainActivity
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.clone.CloneEngine

/**
 * Keep-alive FGS while a clone app runs on the hidden clone display.
 *
 * Two jobs: (1) the app process must not be frozen/collected while a clone app
 * is live, and (2) if the user swipes Bliss away, clone tasks are stopped
 * before the display disappears, so a live clone task can never migrate onto
 * the real screen.
 */
class CloneSessionService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            runCatching { startForeground(NOTIF_ID, notification) }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching { CloneEngine.releaseAll() }
            .onFailure { Log.w(TAG, "releaseAll on task removed failed", it) }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runCatching { CloneEngine.releaseAll() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bliss — Clone space")
            .setContentText("A clone app is running on the hidden clone display.")
            .setSmallIcon(R.drawable.ic_stat_bliss)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bliss Clone Space",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows while a clone app is running inside Bliss"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "CloneSessionService"
        const val CHANNEL_ID = "FluxLinuxCloneSession_v1"
        private const val NOTIF_ID = 43

        fun start(ctx: Context) {
            runCatching {
                val intent = Intent(ctx, CloneSessionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, CloneSessionService::class.java)) }
        }
    }
}
