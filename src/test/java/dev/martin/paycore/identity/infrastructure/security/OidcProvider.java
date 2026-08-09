package dev.martin.paycore.identity.infrastructure.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class OidcProvider {

    static final String ISSUER_PATH = "/issuer-sensitive-sentinel";
    static final String SUBJECT = "subject-sensitive-sentinel";
    static final String ACCESS_TOKEN = "provider-access-token-secret";
    static final String RENEWED_ACCESS_TOKEN = "renewed-access-token-secret";
    static final String REFRESH_TOKEN = "provider-refresh-token-secret";

    private final HttpServer server;
    private final RSAKey signingKey;
    private final AtomicReference<String> nonce = new AtomicReference<>("missing-nonce");
    private final AtomicReference<String> subject = new AtomicReference<>(SUBJECT);
    private final AtomicReference<String> audience = new AtomicReference<>("paycore-test");
    private final AtomicBoolean rejectRefresh = new AtomicBoolean();
    private final AtomicInteger refreshRequests = new AtomicInteger();

    private OidcProvider(HttpServer server, RSAKey signingKey) {
        this.server = server;
        this.signingKey = signingKey;
    }

    static OidcProvider start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            OidcProvider provider = new OidcProvider(
                    server, new RSAKeyGenerator(2048).keyID("paycore-test-key").generate());
            provider.registerContexts();
            server.start();
            return provider;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to start test OIDC provider", exception);
        }
    }

    String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + ISSUER_PATH;
    }

    String subject() {
        return subject.get();
    }

    void useNonce(String value) {
        nonce.set(value);
    }

    void useAudience(String value) {
        audience.set(value);
    }

    void rejectRefresh() {
        rejectRefresh.set(true);
    }

    int refreshRequests() {
        return refreshRequests.get();
    }

    void reset() {
        nonce.set("missing-nonce");
        subject.set(SUBJECT);
        audience.set("paycore-test");
        rejectRefresh.set(false);
        refreshRequests.set(0);
    }

    void stop() {
        server.stop(0);
    }

    private void registerContexts() {
        server.createContext(ISSUER_PATH + "/.well-known/openid-configuration", exchange -> json(exchange, 200, """
                {"issuer":"%s","authorization_endpoint":"%s/authorize","token_endpoint":"%s/token",\
                "jwks_uri":"%s/jwks","subject_types_supported":["public"],\
                "id_token_signing_alg_values_supported":["RS256"]}
                """.formatted(issuer(), issuer(), issuer(), issuer())));
        server.createContext(ISSUER_PATH + "/jwks", exchange -> json(exchange, 200,
                com.nimbusds.jose.util.JSONObjectUtils.toJSONString(
                        new JWKSet(signingKey.toPublicJWK()).toJSONObject())));
        server.createContext(ISSUER_PATH + "/token", this::tokenResponse);
    }

    private void tokenResponse(HttpExchange exchange) throws IOException {
        Map<String, String> form = form(exchange);
        if ("refresh_token".equals(form.get("grant_type"))) {
            refreshRequests.incrementAndGet();
            if (rejectRefresh.get()) {
                json(exchange, 400, "{\"error\":\"invalid_grant\"}");
            } else {
                json(exchange, 200, """
                        {"access_token":"%s","token_type":"Bearer","expires_in":300}
                        """.formatted(RENEWED_ACCESS_TOKEN));
            }
            return;
        }
        if (form.getOrDefault("code_verifier", "").isBlank()) {
            json(exchange, 400, "{\"error\":\"invalid_grant\"}");
            return;
        }
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer())
                    .subject(subject.get())
                    .audience(audience.get())
                    .issueTime(java.util.Date.from(now.minusSeconds(30)))
                    .expirationTime(java.util.Date.from(now.plusSeconds(300)))
                    .claim("nonce", nonce.get())
                    .build();
            SignedJWT idToken = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
            idToken.sign(new RSASSASigner(signingKey));
            json(exchange, 200, """
                    {"access_token":"%s","refresh_token":"%s","token_type":"Bearer",\
                    "expires_in":300,"scope":"openid","id_token":"%s"}
                    """.formatted(ACCESS_TOKEN, REFRESH_TOKEN, idToken.serialize()));
        } catch (Exception exception) {
            throw new IOException("Unable to sign test ID token", exception);
        }
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> values = new HashMap<>();
        for (String entry : body.split("&")) {
            String[] pair = entry.split("=", 2);
            values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
