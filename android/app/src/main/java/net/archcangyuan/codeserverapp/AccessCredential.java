package net.archcangyuan.codeserverapp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a request authenticates to Cloudflare Access: either the session token a
 * sign-in produced (the {@code CF_Authorization} JWT) or a service token's
 * client ID and secret.
 */
final class AccessCredential {
    final String sessionToken;
    final ServiceTokenStore.ServiceToken serviceToken;

    private AccessCredential(String sessionToken, ServiceTokenStore.ServiceToken serviceToken) {
        this.sessionToken = sessionToken;
        this.serviceToken = serviceToken;
    }

    static AccessCredential session(String token) {
        return new AccessCredential(token, null);
    }

    static AccessCredential service(ServiceTokenStore.ServiceToken token) {
        return new AccessCredential(null, token);
    }

    boolean isServiceToken() {
        return serviceToken != null;
    }

    /** Request headers that carry the credential. */
    Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (serviceToken != null) {
            headers.put("CF-Access-Client-Id", serviceToken.clientId);
            headers.put("CF-Access-Client-Secret", serviceToken.clientSecret);
        } else if (sessionToken != null) {
            headers.put("Cf-Access-Token", sessionToken);
        }
        return headers;
    }

    @Override
    public String toString() {
        // Never print secrets.
        return serviceToken != null ? "service token " + serviceToken.name : "session token";
    }
}
