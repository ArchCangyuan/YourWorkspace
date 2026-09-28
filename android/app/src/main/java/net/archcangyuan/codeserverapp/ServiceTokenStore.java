package net.archcangyuan.codeserverapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Cloudflare Access service tokens (client ID and secret), configured once and
 * chosen per project. The token list is stored as one value encrypted with the
 * app's Android Keystore key (see {@link AccessTokenStore}); the preferences
 * file is excluded from backups. Which token a project uses is stored by the
 * project's host, since Access authenticates per host.
 */
final class ServiceTokenStore {
    /** A saved service token. */
    static final class ServiceToken {
        final String id;
        final String name;
        final String clientId;
        final String clientSecret;

        ServiceToken(String id, String name, String clientId, String clientSecret) {
            this.id = id;
            this.name = name;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }

        /** The client ID shortened for display; the secret is never shown. */
        String maskedClientId() {
            String value = clientId == null ? "" : clientId;
            int dot = value.indexOf('.');
            String head = dot > 0 ? value.substring(0, dot) : value;
            return head.length() <= 8 ? head : head.substring(0, 8) + "…";
        }
    }

    private static final String TOKENS_KEY = "service_tokens";
    private static final String PROJECT_PREFIX = "service_token_for:";

    private ServiceTokenStore() {}

    static synchronized List<ServiceToken> list(Context context) {
        String encrypted = prefs(context).getString(TOKENS_KEY, null);
        if (encrypted == null) {
            return new ArrayList<>();
        }
        List<ServiceToken> tokens = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(AccessTokenStore.decrypt(encrypted));
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                tokens.add(new ServiceToken(
                    item.getString("id"),
                    item.optString("name", "Service token"),
                    item.getString("clientId"),
                    item.getString("clientSecret")
                ));
            }
        } catch (Exception exception) {
            // Unreadable (e.g. the Keystore key was reset): nothing usable is saved.
            return new ArrayList<>();
        }
        return tokens;
    }

    static ServiceToken find(Context context, String id) {
        if (id == null) {
            return null;
        }
        for (ServiceToken token : list(context)) {
            if (token.id.equals(id)) {
                return token;
            }
        }
        return null;
    }

    /** Adds the token, or replaces the one with the same id. Returns the saved token. */
    static synchronized ServiceToken save(
        Context context,
        String id,
        String name,
        String clientId,
        String clientSecret
    ) {
        List<ServiceToken> tokens = list(context);
        ServiceToken saved = new ServiceToken(
            id == null ? UUID.randomUUID().toString() : id,
            name,
            clientId,
            clientSecret
        );
        boolean replaced = false;
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).id.equals(saved.id)) {
                tokens.set(index, saved);
                replaced = true;
            }
        }
        if (!replaced) {
            tokens.add(saved);
        }
        write(context, tokens);
        return saved;
    }

    /** Deletes the token and every project's choice of it. */
    static synchronized void delete(Context context, String id) {
        List<ServiceToken> tokens = list(context);
        List<ServiceToken> kept = new ArrayList<>();
        for (ServiceToken token : tokens) {
            if (!token.id.equals(id)) {
                kept.add(token);
            }
        }
        write(context, kept);
        SharedPreferences.Editor editor = prefs(context).edit();
        for (Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
            if (entry.getKey().startsWith(PROJECT_PREFIX) && id.equals(entry.getValue())) {
                editor.remove(entry.getKey());
            }
        }
        editor.apply();
    }

    /** The service token chosen for the project at {@code address}, or null. */
    static ServiceToken forProject(Context context, String address) {
        String key = hostKey(address);
        if (key == null) {
            return null;
        }
        String id = prefs(context).getString(PROJECT_PREFIX + key, null);
        return id == null ? null : find(context, id);
    }

    static String projectTokenId(Context context, String address) {
        String key = hostKey(address);
        return key == null ? null : prefs(context).getString(PROJECT_PREFIX + key, null);
    }

    static void setForProject(Context context, String address, String id) {
        String key = hostKey(address);
        if (key == null) {
            return;
        }
        SharedPreferences.Editor editor = prefs(context).edit();
        if (id == null) {
            editor.remove(PROJECT_PREFIX + key);
        } else {
            editor.putString(PROJECT_PREFIX + key, id);
        }
        editor.apply();
    }

    /** rdp://host for remote desktops, scheme://host[:port] for web projects. */
    static String hostKey(String address) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }
        if (RdpConnectionPanel.isRdpAddress(address)) {
            return "rdp://" + RdpConnectionPanel.hostOf(address);
        }
        Uri uri = Uri.parse(address.trim());
        if (uri.getScheme() == null || uri.getHost() == null) {
            return null;
        }
        String key = uri.getScheme().toLowerCase(Locale.US) + "://"
            + uri.getHost().toLowerCase(Locale.US);
        return uri.getPort() > 0 ? key + ":" + uri.getPort() : key;
    }

    private static void write(Context context, List<ServiceToken> tokens) {
        JSONArray array = new JSONArray();
        try {
            for (ServiceToken token : tokens) {
                JSONObject item = new JSONObject();
                item.put("id", token.id);
                item.put("name", token.name);
                item.put("clientId", token.clientId);
                item.put("clientSecret", token.clientSecret);
                array.put(item);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not store the service tokens", exception);
        }
        prefs(context).edit()
            .putString(TOKENS_KEY, AccessTokenStore.encrypt(array.toString()))
            .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return AccessTokenStore.preferences(context);
    }
}
