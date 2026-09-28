package net.archcangyuan.codeserverapp;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

public final class KeepAliveService extends Service {
    private static final String PREFERENCES = "code_server_app";
    private static final String KEEP_ALIVE_KEY = "keep_alive_enabled";
    private static final String RESTART_ACTION =
        "net.archcangyuan.codeserverapp.RESTART_KEEP_ALIVE";
    private static final String CHANNEL_ID = "your_workspace_keep_alive";
    private static final int NOTIFICATION_ID = 1001;
    private static final int RESTART_REQUEST_CODE = 1002;

    /** Set by the app while a built-in remote desktop session is open. */
    static volatile boolean remoteDesktopActive;

    private PowerManager.WakeLock wakeLock;
    private WindowManager windowManager;
    private View processAnchor;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean keepAlive = isEnabled(this);
        if (!keepAlive && !remoteDesktopActive) {
            stopSelf();
            return START_NOT_STICKY;
        }
        installProcessAnchor();

        Intent launchIntent = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(keepAlive
                ? "YourWorkspace is keeping sessions alive"
                : "Remote desktop connected")
            .setContentText(keepAlive
                ? "Tap to return to your remote workspace"
                : "Keeps the remote desktop connected in the background")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
        startForeground(NOTIFICATION_ID, notification);
        return START_STICKY;
    }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getBoolean(KEEP_ALIVE_KEY, false);
    }

    static void scheduleRestart(Context context) {
        if (!isEnabled(context)) {
            return;
        }
        AlarmManager alarmManager =
            (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        Intent restartIntent = new Intent(context, KeepAliveRestartReceiver.class)
            .setAction(RESTART_ACTION);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context,
            RESTART_REQUEST_CODE,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 5_000L,
            pendingIntent
        );
    }

    @SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "YourWorkspace:SessionKeepAlive"
        );
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Session keep-alive",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps remote workspace sessions connected when enabled");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void installProcessAnchor() {
        if (processAnchor != null || !Settings.canDrawOverlays(this)) {
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            return;
        }
        View anchor = new View(this);
        anchor.setAlpha(0.01f);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        try {
            windowManager.addView(anchor, params);
            processAnchor = anchor;
        } catch (RuntimeException ignored) {
            processAnchor = null;
        }
    }

    private void removeProcessAnchor() {
        if (windowManager != null && processAnchor != null) {
            try {
                windowManager.removeView(processAnchor);
            } catch (RuntimeException ignored) {}
        }
        processAnchor = null;
        windowManager = null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        scheduleRestart(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        removeProcessAnchor();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        scheduleRestart(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
