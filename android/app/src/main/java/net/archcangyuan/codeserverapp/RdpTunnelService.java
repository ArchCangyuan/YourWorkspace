package net.archcangyuan.codeserverapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.IOException;

/**
 * Foreground service that keeps a Cloudflare Access RDP tunnel listening on a
 * loopback port while the user connects with a remote desktop client.
 */
public final class RdpTunnelService extends Service {
    /** Observes tunnel state changes; always called on the main thread. */
    interface StateListener {
        void onTunnelStateChanged();
    }

    static final String EXTRA_OPEN_RDP_HOST = "open_rdp_host";
    private static final String ACTION_START = "net.archcangyuan.codeserverapp.RDP_TUNNEL_START";
    private static final String ACTION_STOP = "net.archcangyuan.codeserverapp.RDP_TUNNEL_STOP";
    private static final String EXTRA_HOST = "host";
    private static final String CHANNEL_ID = "your_workspace_rdp_tunnel";
    private static final int NOTIFICATION_ID = 1101;
    private static final int PREFERRED_PORT = 3390;
    private static final int PORT_ATTEMPTS = 10;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static volatile String runningHost;
    private static volatile int runningPort;
    private static volatile int activeConnections;
    private static volatile boolean loginRequired;
    private static volatile String lastError;
    private static StateListener stateListener;

    private AccessTunnel tunnel;

    static void start(Context context, String host) {
        Intent intent = new Intent(context, RdpTunnelService.class)
            .setAction(ACTION_START)
            .putExtra(EXTRA_HOST, host);
        context.startForegroundService(intent);
    }

    static void stop(Context context) {
        context.startService(new Intent(context, RdpTunnelService.class).setAction(ACTION_STOP));
    }

    static void setStateListener(StateListener listener) {
        stateListener = listener;
    }

    static boolean isRunning(String host) {
        return host != null && host.equalsIgnoreCase(runningHost) && runningPort > 0;
    }

    static int runningPort() {
        return runningPort;
    }

    static int activeConnections() {
        return activeConnections;
    }

    static boolean loginRequired() {
        return loginRequired;
    }

    static String lastError() {
        return lastError;
    }

    private static void notifyStateChanged() {
        mainHandler.post(() -> {
            StateListener listener = stateListener;
            if (listener != null) {
                listener.onTunnelStateChanged();
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID,
                "Remote desktop tunnel",
                NotificationManager.IMPORTANCE_LOW
            ));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutDown();
            return START_NOT_STICKY;
        }
        String host = intent == null ? null : intent.getStringExtra(EXTRA_HOST);
        if (host == null || host.isEmpty()) {
            shutDown();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(host, "Starting…"));
        if (tunnel != null && host.equalsIgnoreCase(runningHost)) {
            updateNotification();
            return START_NOT_STICKY;
        }
        closeTunnel();
        loginRequired = false;
        lastError = null;
        Context appContext = getApplicationContext();
        try {
            tunnel = AccessTunnel.start(
                PREFERRED_PORT,
                PORT_ATTEMPTS,
                () -> {
                    // Read the credential for every connection so a fresh sign-in
                    // or token change applies without restarting the tunnel.
                    AccessCredential credential = AccessTokenStore.credential(appContext, host);
                    if (credential == null) {
                        throw new AccessWebSocket.LoginRequiredException("Sign-in required");
                    }
                    return AccessWebSocket.connect(host, credential);
                },
                new AccessTunnel.Listener() {
                    @Override
                    public void onConnectionsChanged(int count) {
                        activeConnections = count;
                        loginRequired = false;
                        lastError = null;
                        mainHandler.post(RdpTunnelService.this::updateNotification);
                        notifyStateChanged();
                    }

                    @Override
                    public void onLoginRequired() {
                        if (ServiceTokenStore.forProject(appContext, "rdp://" + host) == null) {
                            AccessTokenStore.clearToken(appContext, host);
                        }
                        loginRequired = true;
                        mainHandler.post(RdpTunnelService.this::updateNotification);
                        notifyStateChanged();
                    }

                    @Override
                    public void onError(String message) {
                        lastError = message;
                        notifyStateChanged();
                    }
                }
            );
            runningHost = host;
            runningPort = tunnel.port();
            activeConnections = 0;
            updateNotification();
        } catch (IOException exception) {
            lastError = exception.getMessage();
            shutDown();
        }
        notifyStateChanged();
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String host, String text) {
        Intent openIntent = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_RDP_HOST, host);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent stopIntent = PendingIntent.getService(
            this,
            2,
            new Intent(this, RdpTunnelService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Remote desktop · " + host)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .addAction(new Notification.Action.Builder(null, "Stop", stopIntent).build())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
    }

    private void updateNotification() {
        String host = runningHost;
        if (host == null || tunnel == null) {
            return;
        }
        String text;
        if (loginRequired) {
            text = "Cloudflare sign-in expired · tap to sign in again";
        } else if (activeConnections > 0) {
            text = "127.0.0.1:" + runningPort + " · " + activeConnections
                + (activeConnections == 1 ? " connection" : " connections");
        } else {
            text = "Listening on 127.0.0.1:" + runningPort;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(host, text));
        }
    }

    private void closeTunnel() {
        if (tunnel != null) {
            tunnel.close();
            tunnel = null;
        }
    }

    private void shutDown() {
        closeTunnel();
        runningHost = null;
        runningPort = 0;
        activeConnections = 0;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        notifyStateChanged();
    }

    @Override
    public void onDestroy() {
        closeTunnel();
        runningHost = null;
        runningPort = 0;
        activeConnections = 0;
        notifyStateChanged();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
