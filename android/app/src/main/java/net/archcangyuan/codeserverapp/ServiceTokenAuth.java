package net.archcangyuan.codeserverapp;

import android.net.Uri;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Authenticates to a Cloudflare Access application with a service token and
 * returns the CF_Authorization cookie Access issues, for the WebView's cookie
 * store. Runs on a background thread.
 */
final class ServiceTokenAuth {
    /** Outcome of one authentication request. */
    static final class Result {
        final List<String> cookies;
        final boolean rejected;

        Result(List<String> cookies, boolean rejected) {
            this.cookies = cookies;
            this.rejected = rejected;
        }
    }

    private static final int TIMEOUT_MS = 10_000;
    private static final String ACCESS_COOKIE = "CF_Authorization=";

    private ServiceTokenAuth() {}

    static Result authorize(String url, ServiceTokenStore.ServiceToken token) {
        List<String> cookies = new ArrayList<>();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            for (Map.Entry<String, String> header
                : AccessCredential.service(token).headers().entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            int status = connection.getResponseCode();
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() == null || !header.getKey().equalsIgnoreCase("Set-Cookie")) {
                    continue;
                }
                for (String cookie : header.getValue()) {
                    if (cookie != null && cookie.startsWith(ACCESS_COOKIE)) {
                        cookies.add(cookie);
                    }
                }
            }
            String location = connection.getHeaderField("Location");
            boolean rejected = cookies.isEmpty()
                && (status == 401 || status == 403
                    || (location != null && isAccessLogin(Uri.parse(location))));
            return new Result(cookies, rejected);
        } catch (IOException | RuntimeException exception) {
            return new Result(cookies, false);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Whether {@code url} is a Cloudflare Access login page. */
    static boolean isAccessLogin(Uri url) {
        if (url == null) {
            return false;
        }
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.US);
        String path = url.getPath() == null ? "" : url.getPath();
        return host.endsWith(".cloudflareaccess.com") || path.startsWith("/cdn-cgi/access/login");
    }
}
