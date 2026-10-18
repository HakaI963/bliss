package top.niunaijun.blackbox.core.system;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class DaemonService extends Service {
    public static final String TAG = "DaemonService";
    // Bliss: reuse the clone-session notification slot so the daemon and the
    // host CloneSessionService collapse into ONE visible notification.
    private static final int NOTIFY_ID = 43;
    private static final String CHANNEL_ID = "FluxLinuxCloneSession_v1";
    private static final String CHANNEL_NAME = "Bliss Clone Space";
    private static final String CHANNEL_DESCRIPTION = "Shows while clone apps run inside Bliss";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "DaemonService onCreate");
        
        
        if (BuildCompat.isOreo()) {
            createNotificationChannel();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "DaemonService onStartCommand");
        
        try {
            
            Intent innerIntent = new Intent(this, DaemonInnerService.class);
            startService(innerIntent);
            
            
            if (BuildCompat.isOreo()) {
                if (!startForegroundService()) {
                    Log.w(TAG, "Failed to start foreground service, falling back to regular service");
                    return START_STICKY;
                }
            }
            
            Log.d(TAG, "DaemonService started successfully");
            return START_STICKY;
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting DaemonService: " + e.getMessage(), e);
            
            return START_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "DaemonService onDestroy");
        super.onDestroy();
    }

    
    private void createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription(CHANNEL_DESCRIPTION);
                channel.setShowBadge(false);
                channel.setSound(null, null);
                channel.enableVibration(false);
                
                NotificationManager notificationManager = getSystemService(NotificationManager.class);
                if (notificationManager != null) {
                    notificationManager.createNotificationChannel(channel);
                    Log.d(TAG, "Notification channel created successfully");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create notification channel: " + e.getMessage(), e);
        }
    }

    private int resolveSmallIcon() {
        try {
            int res = getResources().getIdentifier("ic_stat_bliss", "drawable", getPackageName());
            return res != 0 ? res : android.R.drawable.ic_dialog_info;
        } catch (Throwable t) {
            return android.R.drawable.ic_dialog_info;
        }
    }

    private boolean startForegroundService() {
        try {
            Notification notification = createNotification();
            if (notification != null) {
                startForeground(NOTIFY_ID, notification);
                Log.d(TAG, "Foreground service started successfully");
                return true;
            } else {
                Log.e(TAG, "Failed to create notification");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground service: " + e.getMessage(), e);
            return false;
        }
    }

    
    private Notification createNotification() {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bliss")
                .setContentText("Clone space is active")
                .setSmallIcon(resolveSmallIcon())
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false);
            
            return builder.build();
        } catch (Exception e) {
            Log.e(TAG, "Failed to create notification: " + e.getMessage(), e);
            return null;
        }
    }

    
    public static class DaemonInnerService extends Service {
        @Override
        public void onCreate() {
            Log.i(TAG, "DaemonInnerService -> onCreate");
            super.onCreate();
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            Log.i(TAG, "DaemonInnerService -> onStartCommand");
            
            try {
                // Bliss: do NOT cancel the foreground notification here — it is the
                // single merged "Bliss clone space" notification shared with the host.
                stopSelf();
                return START_NOT_STICKY;
                
            } catch (Exception e) {
                Log.e(TAG, "Error in DaemonInnerService: " + e.getMessage(), e);
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public void onDestroy() {
            Log.i(TAG, "DaemonInnerService -> onDestroy");
            super.onDestroy();
        }
    }
}
