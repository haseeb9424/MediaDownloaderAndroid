package com.haseeb.mediadownloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class DownloadForegroundService extends Service {
    private static final String CHANNEL_ID = "media_downloads";
    private static final int NOTIFICATION_ID = 4301;
    private static final String ACTION_START = "com.haseeb.mediadownloader.START";
    private static final String ACTION_UPDATE = "com.haseeb.mediadownloader.UPDATE";
    private static final String ACTION_STOP = "com.haseeb.mediadownloader.STOP";

    public static void start(Context context, String title) {
        Intent intent = new Intent(context, DownloadForegroundService.class)
                .setAction(ACTION_START)
                .putExtra("title", title);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void update(Context context, String title, int progress, String status) {
        Intent intent = new Intent(context, DownloadForegroundService.class)
                .setAction(ACTION_UPDATE)
                .putExtra("title", title)
                .putExtra("progress", progress)
                .putExtra("status", status);
        context.startService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, DownloadForegroundService.class).setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Media downloads",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows active media download progress");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        String title = intent.getStringExtra("title");
        if (title == null || title.isEmpty()) title = "Media Downloader";
        int progress = intent.getIntExtra("progress", 0);
        String status = intent.getStringExtra("status");
        if (status == null || status.isEmpty()) status = "Preparing download…";

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(status)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, Math.max(0, Math.min(100, progress)), progress <= 0)
                .build();

        if (ACTION_START.equals(action)) startForeground(NOTIFICATION_ID, notification);
        else getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
