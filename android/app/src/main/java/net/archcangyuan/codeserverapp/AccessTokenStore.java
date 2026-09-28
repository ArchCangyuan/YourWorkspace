package net.archcangyuan.codeserverapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores Cloudflare Access application tokens (the {@code CF_Authorization}
 * JWT) and remembered Windows passwords per hostname, encrypted with a key held
 * in the Android Keystore, plus the remote desktop user name for each hostname.
 */
final class AccessTokenStore {
    private static final String PREFERENCES = "cloudflare_access";
    private static final String KEY_ALIAS = "your_workspace_access_tokens";
    private static final String TOKEN_PREFIX = "token:";
    private static final String USERNAME_PREFIX = "rdp_username:";
    private static final String PASSWORD_PREFIX = "rdp_password:";
    private static final int GCM_TAG_BITS = 128;

    private AccessTokenStore() {}

    static void saveToken(Context context, String host, String token) {
        preferences(context).edit().putString(TOKEN_PREFIX + key(host), encrypt(token)).apply();
    }

    /** Returns the stored token, or {@code null} when it is missing or expired. */
    static String loadToken(Context context, String host) {
        String value = preferences(context).getString(TOKEN_PREFIX + key(host), null);
        if (value == null) {
            return null;
        }
        try {
            String token = decrypt(value);
            long expiresAt = expiresAtMillis(token);
            if (expiresAt > 0 && expiresAt <= System.currentTimeMillis()) {
                clearToken(context, host);
                return null;
            }
            return token;
        } catch (Exception exception) {
            clearToken(context, host);
            return null;
        }
    }

    /**
     * The Cloudflare Access credential for a remote desktop host: the service
     * token chosen for its project, else the signed-in session token, else null.
     */
    static AccessCredential credential(Context context, String host) {
        ServiceTokenStore.ServiceToken serviceToken =
            ServiceTokenStore.forProject(context, "rdp://" + host);
        if (serviceToken != null) {
            return AccessCredential.service(serviceToken);
        }
        String token = loadToken(context, host);
        return token == null ? null : AccessCredential.session(token);
    }

    static void clearToken(Context context, String host) {
        preferences(context).edit().remove(TOKEN_PREFIX + key(host)).apply();
    }

    /** Reads the {@code exp} claim of a JWT, in milliseconds; 0 when unknown. */
    static long expiresAtMillis(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return 0L;
            }
            String payload = new String(
                Base64.getUrlDecoder().decode(parts[1]),
                StandardCharsets.UTF_8
            );
            return new JSONObject(payload).optLong("exp", 0L) * 1000L;
        } catch (Exception exception) {
            return 0L;
        }
    }

    static void savePassword(Context context, String host, String password) {
        preferences(context).edit()
            .putString(PASSWORD_PREFIX + key(host), encrypt(password == null ? "" : password))
            .apply();
    }

    /** Returns the remembered Windows password, or {@code null} when none is saved. */
    static String loadPassword(Context context, String host) {
        String value = preferences(context).getString(PASSWORD_PREFIX + key(host), null);
        if (value == null) {
            return null;
        }
        try {
            return decrypt(value);
        } catch (Exception exception) {
            clearPassword(context, host);
            return null;
        }
    }

    static void clearPassword(Context context, String host) {
        preferences(context).edit().remove(PASSWORD_PREFIX + key(host)).apply();
    }

    static String encrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(cipher.getIV())
                + ":" + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encrypt the credential", exception);
        }
    }

    static String decrypt(String value) throws Exception {
        String[] parts = value.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            new GCMParameterSpec(GCM_TAG_BITS, Base64.getDecoder().decode(parts[0]))
        );
        return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
    }

    static String username(Context context, String host) {
        return preferences(context).getString(USERNAME_PREFIX + key(host), "");
    }

    static void saveUsername(Context context, String host, String username) {
        preferences(context).edit()
            .putString(USERNAME_PREFIX + key(host), username == null ? "" : username.trim())
            .apply();
    }

    private static String key(String host) {
        return host.trim().toLowerCase(Locale.US);
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        );
        generator.init(new KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return generator.generateKey();
    }
}
