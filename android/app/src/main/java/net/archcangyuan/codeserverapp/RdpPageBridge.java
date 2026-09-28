package net.archcangyuan.codeserverapp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * JavaScript interface ({@code window.YourWorkspaceRdp}) for the built-in
 * remote desktop page served by {@link RdpGateway}. It hands the page its
 * session configuration, backs the page's clipboard with the Android
 * clipboard, and reports session events to the app. It is only added to
 * WebViews that are restricted to the gateway's origin.
 */
final class RdpPageBridge {
    /** Session details for one gateway token. */
    static final class Session {
        final String address;
        final String host;
        final String username;
        final String domain;
        final String password;

        Session(String address, String host, String username, String domain, String password) {
            this.address = address;
            this.host = host;
            this.username = username;
            this.domain = domain;
            this.password = password;
        }
    }

    /** Receives page events on the main thread. */
    interface Listener {
        void onRdpSessionEvent(Session session, String event, String detail);
    }

    private static final String CLIPBOARD_LABEL = "Remote desktop";

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Listener listener;
    private volatile RdpGateway gateway;

    RdpPageBridge(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    void register(String gatewayToken, Session session, RdpGateway gateway) {
        sessions.put(gatewayToken, session);
        this.gateway = gateway;
    }

    @JavascriptInterface
    public String config(String gatewayToken) {
        Session session = sessions.get(gatewayToken);
        JSONObject config = new JSONObject();
        if (session == null) {
            return config.toString();
        }
        try {
            config.put("username", session.username);
            config.put("domain", session.domain);
            config.put("password", session.password);
            config.put("destination", session.host + ":3389");
            config.put("proxyAddress", gateway == null ? "" : gateway.proxyAddress());
        } catch (Exception ignored) {
            // Fields are plain strings.
        }
        return config.toString();
    }

    /** The gateway's view of the latest connection attempt; see {@link RdpGateway#status}. */
    @JavascriptInterface
    public String gatewayStatus(String gatewayToken) {
        RdpGateway current = gateway;
        return current == null || !sessions.containsKey(gatewayToken) ? "{}" : current.status(gatewayToken);
    }

    /** Adds a timestamped line from the page to the gateway's clipboard log. */
    @JavascriptInterface
    public void gatewayNote(String gatewayToken, String text) {
        RdpGateway current = gateway;
        if (current != null && sessions.containsKey(gatewayToken)) {
            current.note(gatewayToken, text);
        }
    }

    /** Switches a gateway clipboard rewrite; see {@link RdpTrace#setOption}. */
    @JavascriptInterface
    public boolean gatewayOption(String gatewayToken, String name, boolean value) {
        return sessions.containsKey(gatewayToken) && RdpTrace.setOption(name, value);
    }

    @JavascriptInterface
    public String clipboardText() {
        FutureTask<String> read = new FutureTask<>(() -> {
            // Without window focus (e.g. a file picker in front) Android hides the
            // clipboard. null tells the page "unknown", not "empty": an empty
            // read would look like a new copy and replace the PC's clipboard.
            if (!activity.hasWindowFocus()) {
                return null;
            }
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                // Android also answers null when it denies the read, so this
                // is "unknown": a flip to "" would be sent to the PC as a copy.
                return null;
            }
            CharSequence text = clip.getItemAt(0).coerceToText(activity);
            return text == null ? "" : text.toString();
        });
        handler.post(read);
        try {
            return read.get(2, TimeUnit.SECONDS);
        } catch (Exception exception) {
            return null;
        }
    }

    @JavascriptInterface
    public void setClipboardText(String text) {
        handler.post(() -> {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            if (clipboard == null) {
                return;
            }
            ClipData current = clipboard.getPrimaryClip();
            CharSequence existing = current == null || current.getItemCount() == 0
                ? null
                : current.getItemAt(0).getText();
            if (existing != null && existing.toString().equals(text)) {
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, text == null ? "" : text));
        });
    }

    @JavascriptInterface
    public void onSessionEvent(String gatewayToken, String event, String detail) {
        Session session = sessions.get(gatewayToken);
        if (session == null) {
            return;
        }
        handler.post(() -> listener.onRdpSessionEvent(session, event, detail));
    }
}
