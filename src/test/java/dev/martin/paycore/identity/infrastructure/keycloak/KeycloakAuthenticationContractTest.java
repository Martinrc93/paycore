package dev.martin.paycore.identity.infrastructure.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class KeycloakAuthenticationContractTest {

    private static final String REALM = "paycore";
    private static final String CLIENT_ID = "paycore-bff";
    private static final String CLIENT_SECRET = "contract-only-client-secret";
    private static final String REDIRECT_URI = "http://localhost:8080/login/oauth2/code/paycore";
    private static final String USERNAME = "contract-user@example.test";
    private static final String PASSWORD = "contract-only-password";
    private static final String UNVERIFIED_USERNAME = "contract-unverified@example.test";
    private static final String UNVERIFIED_PASSWORD = "contract-unverified-password";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LOGIN_FORM = Pattern.compile(
            "<form[^>]+id=\"kc-form-login\"[^>]+action=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    @Container
    static GenericContainer<?> keycloak = new GenericContainer<>(
            DockerImageName.parse("quay.io/keycloak/keycloak:26.5.2"))
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withEnv("PAYCORE_KEYCLOAK_PROVISIONER_CLIENT_SECRET", "contract-only-provisioner-secret")
            .withEnv("PAYCORE_REGISTRATION_REDIRECT_URI", "https://paycore.example/registration-complete")
            .withEnv("PAYCORE_OIDC_CLIENT_SECRET", CLIENT_SECRET)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("deploy/keycloak/paycore-realm.json").toAbsolutePath()),
                    "/opt/keycloak/data/import/paycore-realm.json")
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/paycore/.well-known/openid-configuration")
                    .forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    @Test
    void importedRealmCompletesPkceLoginRejectsBadCredentialsAndOverlapsSigningKeys() throws Exception {
        String baseUrl = "http://127.0.0.1:" + keycloak.getMappedPort(8080);
        String issuer = baseUrl + "/realms/" + REALM;
        JsonNode discovery = getJson(issuer + "/.well-known/openid-configuration");

        assertThat(discovery.path("issuer").asString()).isEqualTo(issuer);
        assertThat(discovery.path("authorization_endpoint").asString()).isEqualTo(
                issuer + "/protocol/openid-connect/auth");
        assertThat(discovery.path("token_endpoint").asString()).isEqualTo(
                issuer + "/protocol/openid-connect/token");
        assertThat(discovery.path("jwks_uri").asString()).isEqualTo(
                issuer + "/protocol/openid-connect/certs");
        assertThat(getJson(discovery.path("jwks_uri").asString()).path("keys").size()).isGreaterThan(0);

        String adminToken = adminToken(baseUrl);
        assertImportedClientContract(baseUrl, adminToken);
        String userId = createTestUser(baseUrl, adminToken);

        assertInvalidCredentialsDoNotYieldCodeOrSession(discovery, baseUrl, adminToken, userId);

        AuthorizationTokens oldTokens = authenticate(discovery, PASSWORD, "old-signing-state");
        assertThat(oldTokens.authorizationCode()).isNotBlank();
        assertThat(oldTokens.idToken().getJWTClaimsSet().getIssuer()).isEqualTo(issuer);
        assertThat(oldTokens.idToken().getJWTClaimsSet().getAudience()).contains(CLIENT_ID);
        assertThat(oldTokens.idToken().getJWTClaimsSet().getBooleanClaim("email_verified")).isTrue();

        createTestUser(baseUrl, adminToken, UNVERIFIED_USERNAME, UNVERIFIED_PASSWORD, false);
        assertUnverifiedUserRequiresEmailVerification(discovery);

        String oldKid = oldTokens.idToken().getHeader().getKeyID();
        addActiveSigningKey(baseUrl, adminToken);
        AuthorizationTokens newTokens = awaitTokenSignedByAnotherKey(discovery, oldKid);
        String newKid = newTokens.idToken().getHeader().getKeyID();
        JWKSet overlappingKeys = awaitOverlappingJwks(discovery.path("jwks_uri").asString(), oldKid, newKid);

        assertThat(newKid).isNotEqualTo(oldKid);
        assertThat(overlappingKeys.getKeyByKeyId(oldKid)).isNotNull();
        assertThat(overlappingKeys.getKeyByKeyId(newKid)).isNotNull();
        assertSignature(oldTokens.idToken(), overlappingKeys.getKeyByKeyId(oldKid));
        assertSignature(newTokens.idToken(), overlappingKeys.getKeyByKeyId(newKid));
    }

    private static void assertImportedClientContract(String baseUrl, String adminToken) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(adminRequest(
                        baseUrl + "/admin/realms/" + REALM + "/clients?clientId=" + CLIENT_ID, adminToken)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode client = JSON.readTree(response.body()).get(0);

        assertThat(client.path("publicClient").asBoolean()).isFalse();
        assertThat(client.path("serviceAccountsEnabled").asBoolean()).isFalse();
        assertThat(client.path("standardFlowEnabled").asBoolean()).isTrue();
        assertThat(client.path("implicitFlowEnabled").asBoolean()).isFalse();
        assertThat(client.path("directAccessGrantsEnabled").asBoolean()).isFalse();
        assertThat(strings(client.path("redirectUris"))).containsExactly(REDIRECT_URI);
        assertThat(strings(client.path("webOrigins"))).containsExactly("http://localhost:8080");
        assertThat(client.path("attributes").path("pkce.code.challenge.method").asString()).isEqualTo("S256");
        assertThat(client.path("attributes").path("access.token.lifespan").asInt()).isEqualTo(300);
        assertThat(client.path("attributes").path("client.session.idle.timeout").asInt()).isEqualTo(1800);
        assertThat(client.path("attributes").path("client.session.max.lifespan").asInt()).isEqualTo(28800);

        JsonNode realm = getAdminJson(baseUrl + "/admin/realms/" + REALM, adminToken);
        assertThat(realm.path("accessTokenLifespan").asInt()).isEqualTo(300);
        assertThat(realm.path("ssoSessionIdleTimeout").asInt()).isEqualTo(1800);
        assertThat(realm.path("ssoSessionMaxLifespan").asInt()).isEqualTo(28800);
    }

    private static String createTestUser(String baseUrl, String adminToken) throws Exception {
        return createTestUser(baseUrl, adminToken, USERNAME, PASSWORD, true);
    }

    private static String createTestUser(String baseUrl, String adminToken, String username,
            String password, boolean emailVerified) throws Exception {
        String body = JSON.writeValueAsString(Map.of(
                "username", username,
                "email", username,
                "emailVerified", emailVerified,
                "enabled", true,
                "requiredActions", List.of(),
                "credentials", List.of(Map.of(
                        "type", "password", "value", password, "temporary", false))));
        HttpResponse<String> response = HttpClient.newHttpClient().send(adminRequest(
                        baseUrl + "/admin/realms/" + REALM + "/users", adminToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        String location = response.headers().firstValue("Location").orElseThrow();
        return URI.create(location).getPath().replaceFirst(".*/", "");
    }

    private static AuthorizationTokens authenticate(JsonNode discovery, String password, String state)
            throws Exception {
        return authenticate(discovery, password, state, USERNAME);
    }

    private static AuthorizationTokens authenticate(JsonNode discovery, String password, String state,
            String username) throws Exception {
        String verifier = "paycore-contract-verifier-abcdefghijklmnopqrstuvwxyz-0123456789";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        HttpClient browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String authorizationUri = discovery.path("authorization_endpoint").asString()
                + "?" + form(Map.of(
                        "response_type", "code",
                        "client_id", CLIENT_ID,
                        "redirect_uri", REDIRECT_URI,
                        "scope", "openid",
                        "state", state,
                        "nonce", "nonce-" + state,
                        "code_challenge", challenge,
                        "code_challenge_method", "S256"));
        HttpResponse<String> loginPage = browser.send(HttpRequest.newBuilder(URI.create(authorizationUri)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(loginPage.statusCode()).isEqualTo(200);
        String action = loginAction(loginPage.body());
        HttpResponse<String> login = browser.send(HttpRequest.newBuilder(URI.create(action))
                .header("Cookie", browserCookies(loginPage))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                        "username", username, "password", password, "credentialId", ""))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as("Keycloak action=%s response=%s", action, login.body()).isEqualTo(302);
        URI callback = URI.create(login.headers().firstValue("Location").orElseThrow());
        Map<String, String> callbackParameters = query(callback);
        assertThat(callback.getScheme() + "://" + callback.getAuthority() + callback.getPath())
                .isEqualTo(REDIRECT_URI);
        assertThat(callbackParameters.get("state")).isEqualTo(state);
        String code = callbackParameters.get("code");
        assertThat(code).isNotBlank();

        HttpResponse<String> token = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create(discovery.path("token_endpoint").asString()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                        "grant_type", "authorization_code",
                        "client_id", CLIENT_ID,
                        "client_secret", CLIENT_SECRET,
                        "redirect_uri", REDIRECT_URI,
                        "code", code,
                        "code_verifier", verifier))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(token.statusCode()).isEqualTo(200);
        return new AuthorizationTokens(code, SignedJWT.parse(JSON.readTree(token.body()).path("id_token").asString()));
    }

    private static void assertUnverifiedUserRequiresEmailVerification(JsonNode discovery) throws Exception {
        String verifier = "unverified-contract-verifier-abcdefghijklmnopqrstuvwxyz-0123456789";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        HttpClient browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String authorizationUri = discovery.path("authorization_endpoint").asString() + "?" + form(Map.of(
                "response_type", "code", "client_id", CLIENT_ID, "redirect_uri", REDIRECT_URI,
                "scope", "openid", "state", "unverified-state", "nonce", "unverified-nonce",
                "code_challenge", challenge, "code_challenge_method", "S256"));
        HttpResponse<String> loginPage = browser.send(HttpRequest.newBuilder(URI.create(authorizationUri)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> login = browser.send(HttpRequest.newBuilder(URI.create(loginAction(loginPage.body())))
                .header("Cookie", browserCookies(loginPage))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                        "username", UNVERIFIED_USERNAME, "password", UNVERIFIED_PASSWORD, "credentialId", ""))))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(login.statusCode()).isEqualTo(302);
        URI requiredAction = URI.create(login.headers().firstValue("Location").orElseThrow());
        assertThat(requiredAction.getPath()).endsWith("/login-actions/required-action");
        assertThat(requiredAction.getPath()).isNotEqualTo(URI.create(REDIRECT_URI).getPath());
        assertThat(query(requiredAction)).doesNotContainKey("code");
    }

    private static void assertInvalidCredentialsDoNotYieldCodeOrSession(JsonNode discovery, String baseUrl,
            String adminToken, String userId) throws Exception {
        String verifier = "invalid-contract-verifier-abcdefghijklmnopqrstuvwxyz-0123456789";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        HttpClient browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String authorizationUri = discovery.path("authorization_endpoint").asString() + "?" + form(Map.of(
                "response_type", "code", "client_id", CLIENT_ID, "redirect_uri", REDIRECT_URI,
                "scope", "openid", "state", "invalid-state", "nonce", "invalid-nonce",
                "code_challenge", challenge, "code_challenge_method", "S256"));
        HttpResponse<String> loginPage = browser.send(HttpRequest.newBuilder(URI.create(authorizationUri)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> rejected = browser.send(HttpRequest.newBuilder(URI.create(loginAction(loginPage.body())))
                .header("Cookie", browserCookies(loginPage))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                        "username", USERNAME, "password", "wrong-contract-password", "credentialId", ""))))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(rejected.headers().firstValue("Location")).isEmpty();
        List<String> returnedCookies = new ArrayList<>(loginPage.headers().allValues("Set-Cookie"));
        returnedCookies.addAll(rejected.headers().allValues("Set-Cookie"));
        assertThat(returnedCookies).noneMatch(KeycloakAuthenticationContractTest::isIdentityOrUserSessionCookie);
        assertThat(rejected.body()).contains("Invalid username or password");
        assertThat(getAdminJson(baseUrl + "/admin/realms/" + REALM + "/users/" + userId + "/sessions",
                adminToken)).isEmpty();
    }

    private static void addActiveSigningKey(String baseUrl, String adminToken) throws Exception {
        JsonNode realm = getAdminJson(baseUrl + "/admin/realms/" + REALM, adminToken);
        String body = JSON.writeValueAsString(Map.of(
                "name", "contract-rotated-rsa",
                "providerId", "rsa-generated",
                "providerType", "org.keycloak.keys.KeyProvider",
                "parentId", realm.path("id").asString(),
                "config", Map.of(
                        "priority", List.of("200"),
                        "enabled", List.of("true"),
                        "active", List.of("true"),
                        "keySize", List.of("2048"),
                        "algorithm", List.of("RS256"))));
        HttpResponse<String> response = HttpClient.newHttpClient().send(adminRequest(
                        baseUrl + "/admin/realms/" + REALM + "/components", adminToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
    }

    private static AuthorizationTokens awaitTokenSignedByAnotherKey(JsonNode discovery, String oldKid)
            throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            AuthorizationTokens tokens = authenticate(discovery, PASSWORD, "rotated-state-" + attempt);
            if (!oldKid.equals(tokens.idToken().getHeader().getKeyID())) {
                return tokens;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Keycloak did not activate the rotated signing key");
    }

    private static JWKSet awaitOverlappingJwks(String jwksUri, String oldKid, String newKid) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            JWKSet keys = JWKSet.parse(HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(jwksUri)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            if (keys.getKeyByKeyId(oldKid) != null && keys.getKeyByKeyId(newKid) != null) {
                return keys;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("JWKS did not expose overlapping old and new signing keys");
    }

    private static void assertSignature(SignedJWT token, JWK jwk) throws Exception {
        assertThat(jwk).isInstanceOf(RSAKey.class);
        assertThat(token.verify(new RSASSAVerifier(((RSAKey) jwk).toRSAPublicKey()))).isTrue();
    }

    private static String adminToken(String baseUrl) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create(baseUrl + "/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                        "grant_type", "password", "client_id", "admin-cli",
                        "username", "admin", "password", "admin"))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).path("access_token").asString();
    }

    private static JsonNode getAdminJson(String uri, String adminToken) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(uri))
                .header("Authorization", "Bearer " + adminToken).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static JsonNode getJson(String uri) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(uri))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static HttpRequest.Builder adminRequest(String uri, String token) {
        return HttpRequest.newBuilder(URI.create(uri)).header("Authorization", "Bearer " + token);
    }

    private static String loginAction(String html) {
        Matcher matcher = LOGIN_FORM.matcher(html);
        assertThat(matcher.find()).as("Keycloak login form").isTrue();
        return matcher.group(1).replace("&amp;", "&");
    }

    private static String browserCookies(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .map(header -> header.split(";", 2)[0])
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static boolean isIdentityOrUserSessionCookie(String setCookie) {
        String name = setCookie.split("=", 2)[0].toUpperCase(java.util.Locale.ROOT);
        return name.startsWith("KEYCLOAK_SESSION")
                || name.startsWith("KEYCLOAK_IDENTITY")
                || name.startsWith("KEYCLOAK_REMEMBER_ME");
    }

    private static String form(Map<String, String> values) {
        List<String> entries = new ArrayList<>();
        values.forEach((key, value) -> entries.add(encode(key) + "=" + encode(value)));
        return String.join("&", entries);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String entry : uri.getRawQuery().split("&")) {
            String[] pair = entry.split("=", 2);
            values.put(java.net.URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private static List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asString()));
        return result;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record AuthorizationTokens(String authorizationCode, SignedJWT idToken) {
    }
}
