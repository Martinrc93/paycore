package dev.martin.paycore.identity.infrastructure.keycloak;

import java.time.Duration;
import java.util.Objects;
import java.net.URI;

public record KeycloakProvisioningProperties(
        String baseUrl,
        String realm,
        String issuer,
        String clientId,
        String clientSecret,
        String redirectUri,
        Duration actionLifespan) {

    public KeycloakProvisioningProperties {
        requireText(baseUrl, "baseUrl");
        requireText(realm, "realm");
        requireText(issuer, "issuer");
        requireText(clientId, "clientId");
        requireText(clientSecret, "clientSecret");
        requireText(redirectUri, "redirectUri");
        Objects.requireNonNull(actionLifespan, "actionLifespan");
        requireSecureUri(baseUrl, "baseUrl");
        requireSecureUri(issuer, "issuer");
        requireSecureUri(redirectUri, "redirectUri");
        if (actionLifespan.isNegative() || actionLifespan.isZero()) {
            throw new IllegalArgumentException("actionLifespan must be positive");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireSecureUri(String value, String name) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " must be an absolute URI", exception);
        }
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host) || "::1".equals(host);
        if (host == null || uri.getUserInfo() != null
                || !("https".equalsIgnoreCase(uri.getScheme())
                || (loopback && "http".equalsIgnoreCase(uri.getScheme())))) {
            throw new IllegalArgumentException(name + " must use HTTPS (HTTP is allowed only for loopback)");
        }
    }
}
