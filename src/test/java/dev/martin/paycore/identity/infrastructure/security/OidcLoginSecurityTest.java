package dev.martin.paycore.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "paycore.authentication.enabled=true",
        "spring.security.oauth2.client.registration.paycore.client-id=paycore-test",
        "spring.security.oauth2.client.registration.paycore.client-secret=test-secret",
        "spring.security.oauth2.client.registration.paycore.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.paycore.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
        "spring.security.oauth2.client.registration.paycore.scope=openid",
        "paycore.authentication.success-uri=/signed-in",
        "spring.task.scheduling.enabled=false"
})
@Import(OidcLoginSecurityTest.FixedClockConfiguration.class)
class OidcLoginSecurityTest {

    private static final String SESSION_COOKIE = "__Host-paycore-session";
    private static final Instant AUTHENTICATED_AT = Instant.parse("2026-08-08T18:00:00Z");
    private static final OidcProvider PROVIDER = OidcProvider.start();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withEnv("TZ", "UTC")
            .withEnv("PGTZ", "UTC");

    @DynamicPropertySource
    static void oidcProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.paycore.issuer-uri", PROVIDER::issuer);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    JdbcIndexedSessionRepository sessions;

    @Autowired
    ClientRegistrationRepository registrations;

    @Autowired
    OAuth2AuthorizedClientRepository authorizedClients;

    @Autowired
    RecordingAuthorizedClientRepository recordingAuthorizedClients;

    @Autowired
    OAuth2AuthorizedClientManager authorizedClientManager;

    @Autowired
    ApplicationContext applicationContext;

    @BeforeEach
    void resetState() {
        jdbcClient.sql("TRUNCATE TABLE spring_session CASCADE").update();
        jdbcClient.sql("TRUNCATE TABLE external_identities, customers CASCADE").update();
        PROVIDER.reset();
        recordingAuthorizedClients.reset();
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop();
    }

    @Test
    void loginInitiationUsesStateNonceAndS256PkceWithoutExposingTokens() throws Exception {
        MvcResult result = startLogin();
        Map<String, String> parameters = queryParameters(result.getResponse().getRedirectedUrl());

        assertThat(parameters.get("response_type")).isEqualTo("code");
        assertThat(parameters.get("state")).isNotBlank();
        assertThat(parameters.get("nonce")).isNotBlank();
        assertThat(parameters.get("code_challenge")).isNotBlank();
        assertThat(parameters.get("code_challenge_method")).isEqualTo("S256");
        assertNoTokens(result);
    }

    @Test
    void enabledAuthenticationHasNoApplicationGlobalAuthorizedClientService() {
        assertThat(applicationContext.getBeansOfType(OAuth2AuthorizedClientService.class)).isEmpty();
    }

    @Test
    void successfulCallbackRotatesSessionAndPersistsOnlyTheLocalCustomerIdentity(CapturedOutput output)
            throws Exception {
        UUID customerId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        linkCustomer(customerId, CustomerStatus.ACTIVE, PROVIDER.subject());
        LoginStart login = loginStart();

        MvcResult result = callback(login, "accepted-code");

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/signed-in");
        Cookie acceptedCookie = requiredSessionCookie(result);
        assertThat(acceptedCookie.getValue()).isNotEqualTo(login.cookie().getValue());
        assertCookieContract(acceptedCookie);
        assertNoTokens(result);

        Session session = sessions.findById(repositorySessionId(acceptedCookie));
        assertThat(session).isNotNull();
        assertThat(session.<Instant>getAttribute("paycore.authenticated-at")).isEqualTo(AUTHENTICATED_AT);
        SecurityContext context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication()).isInstanceOf(OAuth2AuthenticationToken.class);
        assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(CustomerPrincipal.class);
        assertThat(((CustomerPrincipal) context.getAuthentication().getPrincipal()).getAttributes()).isEmpty();
        assertThat(context.getAuthentication().getName()).isEqualTo(customerId.toString());
        assertThat(sessions.findByPrincipalName(customerId.toString())).containsKey(session.getId());

        OAuth2AuthorizedClient authorizedClient = findAuthorizedClient(session);
        assertThat(authorizedClient.getPrincipalName()).isEqualTo(customerId.toString());
        assertThat(authorizedClient.getAccessToken().getTokenValue()).isEqualTo(OidcProvider.ACCESS_TOKEN);
        assertThat(authorizedClient.getRefreshToken().getTokenValue()).isEqualTo(OidcProvider.REFRESH_TOKEN);
        assertThat(session.getAttributeNames()).noneMatch(name -> name.contains(PROVIDER.subject()));
        assertThat(output.getAll()).doesNotContain(OidcProvider.ACCESS_TOKEN)
                .doesNotContain(OidcProvider.REFRESH_TOKEN);
    }

    @ParameterizedTest
    @EnumSource(value = CustomerStatus.class, names = {"PROVISIONING", "PROVISIONING_FAILED", "SUSPENDED", "BLOCKED"})
    void inactiveCustomersReceiveTheSameSanitizedForbiddenResponse(CustomerStatus status) throws Exception {
        linkCustomer(UUID.randomUUID(), status, PROVIDER.subject());

        MvcResult result = callback(loginStart(), "inactive-code");

        assertSanitizedForbiddenWithoutAcceptedSession(result);
    }

    @Test
    void unknownIdentityReceivesSanitizedForbiddenWithoutAnAcceptedSession() throws Exception {
        MvcResult result = callback(loginStart(), "unknown-code");

        assertSanitizedForbiddenWithoutAcceptedSession(result);
    }

    @Test
    void providerFailureCreatesNoAuthenticatedLocalSession() throws Exception {
        LoginStart login = loginStart();

        MvcResult result = mockMvc.perform(get("/login/oauth2/code/paycore")
                        .secure(true)
                        .cookie(login.cookie())
                        .queryParam("error", "access_denied")
                        .queryParam("error_description", "provider detail that must not escape")
                        .queryParam("state", login.state()))
                .andReturn();

        assertSanitizedForbiddenWithoutAcceptedSession(result);
    }

    @Test
    void tamperedStateIsRejectedBeforeTokenExchange() throws Exception {
        LoginStart login = loginStart();

        MvcResult result = callback(new LoginStart(login.cookie(), login.state() + "tampered"), "state-code");

        assertSanitizedForbiddenWithoutAcceptedSession(result);
    }

    @Test
    void mismatchedNonceIsRejectedBeforeLocalAuthentication() throws Exception {
        UUID customerId = UUID.randomUUID();
        linkCustomer(customerId, CustomerStatus.ACTIVE, PROVIDER.subject());
        LoginStart login = loginStart();
        PROVIDER.useNonce("different-nonce");

        MvcResult result = callback(login, "nonce-code");

        assertSanitizedForbiddenWithoutAcceptedSession(result);
        assertThat(sessions.findByPrincipalName(customerId.toString())).isEmpty();
    }

    @Test
    void idTokenWithTheWrongAudienceIsRejectedBeforeLocalResolution() throws Exception {
        UUID customerId = UUID.randomUUID();
        linkCustomer(customerId, CustomerStatus.ACTIVE, PROVIDER.subject());
        PROVIDER.useAudience("different-client");

        MvcResult result = callback(loginStart(), "wrong-audience-code");

        assertSanitizedForbiddenWithoutAcceptedSession(result);
        assertThat(sessions.findByPrincipalName(customerId.toString())).isEmpty();
    }

    @Test
    void audienceValidatorAddsTheClientRequirementWithoutDroppingDefaultJwtValidation() {
        ClientRegistration registration = registrations.findByRegistrationId("paycore");
        Instant now = Instant.now();
        Jwt wrongIssuer = Jwt.withTokenValue("id-token")
                .header("alg", "RS256")
                .issuer("https://wrong.example")
                .subject("subject")
                .audience(java.util.List.of(registration.getClientId()))
                .issuedAt(now.minusSeconds(30))
                .expiresAt(now.plusSeconds(300))
                .build();

        var validator = JwtValidators.createDefaultWithValidators(
                new org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator(registration),
                new OidcAudienceValidator(registration.getClientId()));

        assertThat(validator.validate(wrongIssuer).hasErrors()).isTrue();
    }

    @Test
    void expiredAccessTokenIsRenewedOnlyOnTheServerAndPersistedBackIntoTheSession() throws Exception {
        OAuth2AuthenticationToken authentication = localAuthentication();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        OAuth2AuthorizedClient expired = expiredAuthorizedClient(authentication.getName());
        authorizedClients.saveAuthorizedClient(expired, authentication, request, response);

        OAuth2AuthorizedClient renewed = authorizedClientManager.authorize(authorizeRequest(authentication, request, response));

        assertThat(renewed.getAccessToken().getTokenValue()).isEqualTo(OidcProvider.RENEWED_ACCESS_TOKEN);
        assertThat(authorizedClients.<OAuth2AuthorizedClient>loadAuthorizedClient("paycore", authentication, request)
                .getAccessToken().getTokenValue()).isEqualTo(OidcProvider.RENEWED_ACCESS_TOKEN);
        assertThat(response.getContentAsString()).doesNotContain(OidcProvider.RENEWED_ACCESS_TOKEN)
                .doesNotContain(OidcProvider.REFRESH_TOKEN);
        assertThat(response.getCookies()).isEmpty();
    }

    @Test
    void configuredRefreshRejectionRemovesPersistedTokensBeforeInvalidatingTheSession(CapturedOutput output)
            throws Exception {
        UUID customerId = UUID.fromString("30000000-0000-0000-0000-000000000003");
        linkCustomer(customerId, CustomerStatus.ACTIVE, PROVIDER.subject());
        Cookie cookie = requiredSessionCookie(callback(loginStart(), "accepted-code"));
        Session session = sessions.findById(repositorySessionId(cookie));
        replaceAuthorizedClient(session, expiredAuthorizedClient(customerId.toString()));
        PROVIDER.rejectRefresh();

        MvcResult result = mockMvc.perform(get("/test/protected").secure(true).cookie(cookie)).andReturn();

        assertThat(PROVIDER.refreshRequests()).isEqualTo(1);
        assertThat(recordingAuthorizedClients.removedBeforeInvalidation()).isTrue();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("Authentication required");
        assertThat(sessions.findById(repositorySessionId(cookie))).isNull();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM spring_session_attributes")
                .query(Long.class).single()).isZero();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(OidcProvider.ACCESS_TOKEN)
                .doesNotContain(OidcProvider.REFRESH_TOKEN);
        assertThat(output.getAll()).doesNotContain(OidcProvider.ACCESS_TOKEN)
                .doesNotContain(OidcProvider.REFRESH_TOKEN);
    }

    private MvcResult startLogin() throws Exception {
        return mockMvc.perform(get("/oauth2/authorization/paycore").secure(true))
                .andExpect(status().is3xxRedirection())
                .andReturn();
    }

    private LoginStart loginStart() throws Exception {
        MvcResult result = startLogin();
        Map<String, String> parameters = queryParameters(result.getResponse().getRedirectedUrl());
        PROVIDER.useNonce(parameters.get("nonce"));
        return new LoginStart(requiredSessionCookie(result), parameters.get("state"));
    }

    private MvcResult callback(LoginStart login, String code) throws Exception {
        return mockMvc.perform(get("/login/oauth2/code/paycore")
                        .secure(true)
                        .cookie(login.cookie())
                        .queryParam("code", code)
                        .queryParam("state", login.state()))
                .andReturn();
    }

    private void linkCustomer(UUID customerId, CustomerStatus status, String subject) {
        jdbcClient.sql("""
                        INSERT INTO customers (id, email, customer_type, status, created_at, updated_at)
                        VALUES (:id, :email, 'INDIVIDUAL', :status, :now, :now)
                        """)
                .param("id", customerId)
                .param("email", customerId + "@example.test")
                .param("status", status.name())
                .param("now", OffsetDateTime.ofInstant(AUTHENTICATED_AT, ZoneOffset.UTC))
                .update();
        jdbcClient.sql("""
                        INSERT INTO external_identities (issuer, subject, customer_id, created_at)
                        VALUES (:issuer, :subject, :customerId, :now)
                        """)
                .param("issuer", PROVIDER.issuer())
                .param("subject", subject)
                .param("customerId", customerId)
                .param("now", OffsetDateTime.ofInstant(AUTHENTICATED_AT, ZoneOffset.UTC))
                .update();
    }

    private static Map<String, String> queryParameters(String location) {
        var query = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        Map<String, String> parameters = new HashMap<>();
        query.forEach((name, values) -> parameters.put(
                name, UriUtils.decode(values.getFirst(), StandardCharsets.UTF_8)));
        return parameters;
    }

    private static Cookie requiredSessionCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(SESSION_COOKIE);
        assertThat(cookie).as("opaque session cookie").isNotNull();
        return cookie;
    }

    private static void assertCookieContract(Cookie cookie) {
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getAttribute("SameSite")).isEqualToIgnoringCase("Lax");
    }

    private static String repositorySessionId(Cookie cookie) {
        return new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
    }

    private static void assertNoTokens(MvcResult result) throws Exception {
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(OidcProvider.ACCESS_TOKEN)
                .doesNotContain(OidcProvider.REFRESH_TOKEN);
        assertThat(java.util.Objects.requireNonNullElse(result.getResponse().getRedirectedUrl(), ""))
                .doesNotContain(OidcProvider.ACCESS_TOKEN)
                .doesNotContain(OidcProvider.REFRESH_TOKEN);
        assertThat(result.getResponse().getCookies()).allSatisfy(cookie -> assertThat(cookie.getValue())
                .doesNotContain(OidcProvider.ACCESS_TOKEN)
                .doesNotContain(OidcProvider.REFRESH_TOKEN));
    }

    private void assertSanitizedForbiddenWithoutAcceptedSession(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("Authentication failed");
        assertThat(result.getResponse().getContentType()).isEqualTo("text/plain;charset=UTF-8");
        assertNoTokens(result);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM spring_session_attributes
                        WHERE attribute_name = 'SPRING_SECURITY_CONTEXT'
                        """).query(Long.class).single()).isZero();
    }

    @SuppressWarnings("unchecked")
    private static OAuth2AuthorizedClient findAuthorizedClient(Session session) {
        return session.getAttributeNames().stream()
                .map(session::<Object>getAttribute)
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(map -> map.get("paycore"))
                .filter(OAuth2AuthorizedClient.class::isInstance)
                .map(OAuth2AuthorizedClient.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No server-side authorized client was persisted"));
    }

    private OAuth2AuthenticationToken localAuthentication() {
        return new OAuth2AuthenticationToken(
                new CustomerPrincipal(UUID.fromString("20000000-0000-0000-0000-000000000002")),
                java.util.List.of(), "paycore");
    }

    private OAuth2AuthorizedClient expiredAuthorizedClient(String principalName) {
        ClientRegistration registration = registrations.findByRegistrationId("paycore");
        Instant now = Instant.now();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, OidcProvider.ACCESS_TOKEN,
                now.minus(Duration.ofMinutes(10)), now.minus(Duration.ofMinutes(5)));
        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(OidcProvider.REFRESH_TOKEN, now.minus(Duration.ofMinutes(10)));
        return new OAuth2AuthorizedClient(registration, principalName, accessToken, refreshToken);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void replaceAuthorizedClient(Session session, OAuth2AuthorizedClient replacement) {
        String attributeName = session.getAttributeNames().stream()
                .filter(name -> session.getAttribute(name) instanceof Map<?, ?> clients
                        && clients.containsKey("paycore"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No authorized-client session attribute was persisted"));
        Map<String, OAuth2AuthorizedClient> clients = new HashMap<>((Map) session.getAttribute(attributeName));
        clients.put("paycore", replacement);
        session.setAttribute(attributeName, clients);
        ((SessionRepository) sessions).save(session);
    }

    private static OAuth2AuthorizeRequest authorizeRequest(OAuth2AuthenticationToken authentication,
            MockHttpServletRequest request, MockHttpServletResponse response) {
        return OAuth2AuthorizeRequest.withClientRegistrationId("paycore")
                .principal(authentication)
                .attribute(jakarta.servlet.http.HttpServletRequest.class.getName(), request)
                .attribute(jakarta.servlet.http.HttpServletResponse.class.getName(), response)
                .build();
    }

    private record LoginStart(Cookie cookie, String state) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock authenticationClock() {
            return Clock.fixed(AUTHENTICATED_AT, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        @ConditionalOnBooleanProperty(name = "paycore.authentication.enabled")
        RecordingAuthorizedClientRepository recordingAuthorizedClientRepository() {
            return new RecordingAuthorizedClientRepository();
        }
    }

    static final class RecordingAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

        private final OAuth2AuthorizedClientRepository delegate =
                new org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository();
        private final AtomicBoolean removedBeforeInvalidation = new AtomicBoolean();

        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
                org.springframework.security.core.Authentication principal, HttpServletRequest request) {
            return delegate.loadAuthorizedClient(clientRegistrationId, principal, request);
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient,
                org.springframework.security.core.Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
            delegate.saveAuthorizedClient(authorizedClient, principal, request, response);
        }

        @Override
        public void removeAuthorizedClient(String clientRegistrationId,
                org.springframework.security.core.Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
            HttpSession session = request.getSession(false);
            boolean validBeforeRemoval = isValid(session);
            delegate.removeAuthorizedClient(clientRegistrationId, principal, request, response);
            if (validBeforeRemoval && delegate.loadAuthorizedClient(clientRegistrationId, principal, request) == null) {
                removedBeforeInvalidation.set(true);
            }
        }

        boolean removedBeforeInvalidation() {
            return removedBeforeInvalidation.get();
        }

        void reset() {
            removedBeforeInvalidation.set(false);
        }

        private static boolean isValid(HttpSession session) {
            if (session == null) {
                return false;
            }
            try {
                session.getId();
                return true;
            } catch (IllegalStateException exception) {
                return false;
            }
        }
    }

    private static final class OidcProvider {

        static final String ACCESS_TOKEN = "provider-access-token-secret";
        static final String RENEWED_ACCESS_TOKEN = "renewed-access-token-secret";
        static final String REFRESH_TOKEN = "provider-refresh-token-secret";

        private final HttpServer server;
        private final RSAKey signingKey;
        private final AtomicReference<String> nonce = new AtomicReference<>("missing-nonce");
        private final AtomicReference<String> subject = new AtomicReference<>("provider-subject");
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
            return "http://127.0.0.1:" + server.getAddress().getPort();
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
            subject.set("provider-subject");
            audience.set("paycore-test");
            rejectRefresh.set(false);
            refreshRequests.set(0);
        }

        void stop() {
            server.stop(0);
        }

        private void registerContexts() {
            server.createContext("/.well-known/openid-configuration", exchange -> json(exchange, 200, """
                    {"issuer":"%s","authorization_endpoint":"%s/authorize","token_endpoint":"%s/token",\
                    "jwks_uri":"%s/jwks","subject_types_supported":["public"],\
                    "id_token_signing_alg_values_supported":["RS256"]}
                    """.formatted(issuer(), issuer(), issuer(), issuer())));
            server.createContext("/jwks", exchange -> json(exchange, 200,
                    com.nimbusds.jose.util.JSONObjectUtils.toJSONString(
                            new JWKSet(signingKey.toPublicJWK()).toJSONObject())));
            server.createContext("/token", this::tokenResponse);
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
}
