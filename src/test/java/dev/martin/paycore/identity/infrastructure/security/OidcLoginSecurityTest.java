package dev.martin.paycore.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.martin.paycore.identity.application.authentication.SessionLifetimePolicy;
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.wallet.application.provisioning.ProvisionWallet;
import dev.martin.paycore.wallet.application.provisioning.ProvisionWalletCommand;
import dev.martin.paycore.wallet.domain.model.WalletCurrency;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
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
    ProvisionWallet walletProvisioning;

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

    @Autowired
    SessionLifetimePolicy lifetimePolicy;

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
                .doesNotContain(OidcProvider.REFRESH_TOKEN)
                .doesNotContain(PROVIDER.issuer())
                .doesNotContain(PROVIDER.subject())
                .doesNotContain("issuer-sensitive-sentinel")
                .doesNotContain("subject-sensitive-sentinel");
    }

    @Test
    void successfulReauthenticationRotatesSessionAndRestartsAbsoluteAndIdleLifetimes() throws Exception {
        UUID customerId = UUID.fromString("10000000-0000-0000-0000-000000000009");
        linkCustomer(customerId, CustomerStatus.ACTIVE, PROVIDER.subject());
        Session existing = authenticatedSession(customerId, AUTHENTICATED_AT.minus(Duration.ofHours(7)));
        LoginStart login = loginStart(existing);

        MvcResult result = callback(login, "reauthenticated-code");

        Cookie rotatedCookie = requiredSessionCookie(result);
        assertThat(rotatedCookie.getValue()).isNotEqualTo(login.cookie().getValue());
        assertThat(sessions.findById(existing.getId())).isNull();
        Session reauthenticated = sessions.findById(repositorySessionId(rotatedCookie));
        assertThat(reauthenticated).isNotNull();
        Instant authenticatedAt = reauthenticated.getAttribute(
                CustomerOidcAuthenticationSuccessHandler.AUTHENTICATED_AT_ATTRIBUTE);
        assertThat(authenticatedAt).isEqualTo(AUTHENTICATED_AT);
        assertThat(lifetimePolicy.absoluteExpiry(authenticatedAt))
                .isEqualTo(Instant.parse("2026-08-09T02:00:00Z"));
        assertThat(reauthenticated.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void pendingCustomerWithVerifiedEmailIsActivatedBeforeSessionCreation() throws Exception {
        UUID customerId = UUID.fromString("10000000-0000-0000-0000-000000000010");
        linkCustomer(customerId, CustomerStatus.PENDING_VERIFICATION, PROVIDER.subject());

        MvcResult result = callback(loginStart(), "verified-pending-code");

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(requiredSessionCookie(result)).isNotNull();
        assertThat(customerStatus(customerId)).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    void pendingCustomerWithVerifiedEmailHasNoSessionWhenWalletProvisioningFails() throws Exception {
        UUID customerId = UUID.fromString("10000000-0000-0000-0000-000000000013");
        linkCustomer(customerId, CustomerStatus.PENDING_VERIFICATION, PROVIDER.subject());
        installWalletClaimFailureTrigger(customerId);
        LoginStart login = loginStart();
        Session unrelated = sessionRepository().createSession();
        sessionRepository().save(unrelated);
        try {
            MvcResult result = callback(login, "failed-wallet-code");

            assertSanitizedForbiddenWithoutAcceptedSession(result);
        } finally {
            jdbcClient.sql("DROP TRIGGER test_fail_wallet_oidc ON wallets").update();
            jdbcClient.sql("DROP FUNCTION test_fail_wallet_oidc()").update();
        }

        assertThat(customerStatus(customerId)).isEqualTo(CustomerStatus.PENDING_VERIFICATION);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM wallets WHERE customer_id=:id")
                .param("id", customerId).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM ledger_accounts WHERE name LIKE :prefix")
                .param("prefix", "wallet:" + customerId + ":%").query(Long.class).single()).isZero();
        assertThat(sessions.findById(repositorySessionId(login.cookie()))).isNull();
        assertThat(sessions.findById(unrelated.getId())).isNotNull();
    }

    @Test
    void pendingCustomerWithUnverifiedEmailIsDeniedWithoutSession() throws Exception {
        UUID customerId = UUID.fromString("10000000-0000-0000-0000-000000000011");
        linkCustomer(customerId, CustomerStatus.PENDING_VERIFICATION, PROVIDER.subject());
        PROVIDER.useEmailVerified(false);

        MvcResult result = callback(loginStart(), "unverified-pending-code");

        assertSanitizedForbiddenWithoutAcceptedSession(result);
        assertThat(customerStatus(customerId)).isEqualTo(CustomerStatus.PENDING_VERIFICATION);
    }

    @Test
    void pendingCustomerWithMissingEmailVerificationClaimIsDeniedWithoutSession() throws Exception {
        UUID customerId = UUID.fromString("10000000-0000-0000-0000-000000000012");
        linkCustomer(customerId, CustomerStatus.PENDING_VERIFICATION, PROVIDER.subject());
        PROVIDER.useEmailVerified(null);

        MvcResult result = callback(loginStart(), "missing-verification-code");

        assertSanitizedForbiddenWithoutAcceptedSession(result);
        assertThat(customerStatus(customerId)).isEqualTo(CustomerStatus.PENDING_VERIFICATION);
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

        var validator = new DelegatingOAuth2TokenValidator<>(java.util.List.of(
                JwtValidators.createDefault(),
                new org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator(registration),
                new OidcAudienceValidator(registration.getClientId())));

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
        assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"code\":\"unauthorized\"}");
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

    private LoginStart loginStart(Session session) throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/paycore")
                        .secure(true)
                        .cookie(sessionCookie(session)))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        Map<String, String> parameters = queryParameters(result.getResponse().getRedirectedUrl());
        PROVIDER.useNonce(parameters.get("nonce"));
        return new LoginStart(sessionCookie(session), parameters.get("state"));
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
        if (status == CustomerStatus.ACTIVE) {
            walletProvisioning.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));
        }
    }

    private CustomerStatus customerStatus(UUID customerId) {
        return CustomerStatus.valueOf(jdbcClient.sql("SELECT status FROM customers WHERE id=:id")
                .param("id", customerId).query(String.class).single());
    }

    private void installWalletClaimFailureTrigger(UUID customerId) {
        jdbcClient.sql("CREATE FUNCTION test_fail_wallet_oidc() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.customer_id = '" + customerId + "'::uuid THEN "
                + "RAISE EXCEPTION 'forced wallet OIDC failure'; END IF; "
                + "RETURN NEW; END; $$").update();
        jdbcClient.sql("""
                CREATE TRIGGER test_fail_wallet_oidc
                BEFORE INSERT ON wallets
                FOR EACH ROW EXECUTE FUNCTION test_fail_wallet_oidc()
                """).update();
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

    private Session authenticatedSession(UUID customerId, Instant authenticatedAt) {
        Session session = sessionRepository().createSession();
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                new CustomerPrincipal(customerId), java.util.List.of(), "paycore");
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, customerId.toString());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new org.springframework.security.core.context.SecurityContextImpl(authentication));
        session.setAttribute(CustomerOidcAuthenticationSuccessHandler.AUTHENTICATED_AT_ATTRIBUTE, authenticatedAt);
        session.setMaxInactiveInterval(Duration.ofMinutes(5));
        sessionRepository().save(session);
        return session;
    }

    private static Cookie sessionCookie(Session session) {
        return new Cookie(SESSION_COOKIE, Base64.getEncoder().encodeToString(
                session.getId().getBytes(StandardCharsets.UTF_8)));
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
        assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"code\":\"forbidden\"}");
        assertThat(result.getResponse().getContentType()).isEqualTo("application/json");
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

    @SuppressWarnings("unchecked")
    private SessionRepository<Session> sessionRepository() {
        return (SessionRepository<Session>) (SessionRepository<?>) sessions;
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

}
