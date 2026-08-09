package dev.martin.paycore.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.testsupport.ProtectedSecurityTestConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "paycore.authentication.enabled=true",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false"
})
@Import(ProtectedSecurityTestConfiguration.class)
public class ProtectedSessionSecurityTest {

    public static final String SESSION_COOKIE = "__Host-paycore-session";
    public static final Instant NOW = Instant.parse("2026-08-08T18:00:00Z");
    public static final UUID CUSTOMER_ID = UUID.fromString("60000000-0000-0000-0000-000000000006");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withEnv("TZ", "UTC")
            .withEnv("PGTZ", "UTC");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcIndexedSessionRepository sessions;

    @Autowired
    FindByIndexNameSessionRepository<Session> exactSessions;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    MutableClock clock;

    @Autowired
    RecordingAuthorizedClientManager authorizedClientManager;

    @BeforeEach
    void resetState() {
        jdbcClient.sql("TRUNCATE TABLE spring_session CASCADE").update();
        jdbcClient.sql("TRUNCATE TABLE external_identities, customers CASCADE").update();
        clock.set(NOW);
        authorizedClientManager.reset();
    }

    @Test
    void activeRequestRefreshesOnlyServerSideWithoutChangingAuthenticatedAt() throws Exception {
        insertCustomer(CUSTOMER_ID, CustomerStatus.ACTIVE);
        Instant authenticatedAt = NOW.minus(Duration.ofHours(2));
        Session session = authenticatedSession(CUSTOMER_ID, authenticatedAt);

        MvcResult result = perform(sessionCookie(session));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo(CUSTOMER_ID.toString());
        assertThat(result.getResponse().getContentAsString()).doesNotContain("renewed-access-token")
                .doesNotContain("refresh-token");
        assertThat(authorizedClientManager.calls()).isEqualTo(1);
        Session persisted = sessions.findById(session.getId());
        assertThat(persisted.<Instant>getAttribute(CustomerOidcAuthenticationSuccessHandler.AUTHENTICATED_AT_ATTRIBUTE))
                .isEqualTo(authenticatedAt);
        assertThat(persisted.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void eachRequestCapsRepositoryIdleExpiryToRemainingAbsoluteLifetime() throws Exception {
        insertCustomer(CUSTOMER_ID, CustomerStatus.ACTIVE);
        Session session = authenticatedSession(CUSTOMER_ID,
                NOW.minus(Duration.ofHours(7)).minus(Duration.ofMinutes(50)));

        assertThat(perform(sessionCookie(session)).getResponse().getStatus()).isEqualTo(200);

        Session persisted = sessions.findById(session.getId());
        assertThat(persisted.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void fractionalAbsoluteLifetimeIsPersistedAtTheExactDeadlineWithoutEarlyExpiry() throws Exception {
        insertCustomer(CUSTOMER_ID, CustomerStatus.ACTIVE);
        Session session = authenticatedSession(CUSTOMER_ID,
                NOW.minus(Duration.ofHours(7)).minus(Duration.ofMinutes(50)).minusMillis(500));

        assertThat(perform(sessionCookie(session)).getResponse().getStatus()).isEqualTo(200);

        Map<String, Long> persisted = jdbcClient.sql("""
                        SELECT max_inactive_interval, expiry_time
                        FROM spring_session
                        WHERE session_id = :id
                        """)
                .param("id", session.getId())
                .query((rs, rowNum) -> Map.of(
                        "maxInactiveInterval", rs.getLong("max_inactive_interval"),
                        "expiryTime", rs.getLong("expiry_time")))
                .single();
        assertThat(persisted.get("maxInactiveInterval")).isEqualTo(600L);
        assertThat(persisted.get("expiryTime"))
                .isEqualTo(Instant.parse("2026-08-08T18:09:59.500Z").toEpochMilli());
    }

    @Test
    void repositoryRejectsTheSessionAtItsFractionalAbsoluteDeadline() {
        Instant authenticatedAt = NOW.minus(Duration.ofHours(8)).plusMillis(500);
        Session session = exactSessions.createSession();
        session.setAttribute(CustomerOidcAuthenticationSuccessHandler.AUTHENTICATED_AT_ATTRIBUTE, authenticatedAt);
        exactSessions.save(session);
        assertThat(exactSessions.findById(session.getId())).isNotNull();

        clock.set(NOW.plusMillis(500));

        assertThat(exactSessions.findById(session.getId())).isNull();
        assertThat(sessions.findById(session.getId())).isNull();
    }

    @Test
    void absoluteExpiryRunsBeforeStatusAndRefreshAndReturnsSanitizedUnauthorized() throws Exception {
        insertCustomer(CUSTOMER_ID, CustomerStatus.SUSPENDED);
        Session session = authenticatedSession(CUSTOMER_ID, NOW.minus(Duration.ofHours(8)));

        MvcResult result = perform(sessionCookie(session));

        assertSanitized(result, 401, "{\"code\":\"unauthorized\"}");
        assertThat(sessions.findById(session.getId())).isNull();
        assertThat(authorizedClientManager.calls()).isZero();
    }

    @Test
    void staleSessionDoesNotInterceptPublicRegistration() throws Exception {
        Session session = authenticatedSession(CUSTOMER_ID, NOW.minus(Duration.ofHours(1)));

        MvcResult result = mockMvc.perform(post("/api/customers")
                        .secure(true)
                        .cookie(sessionCookie(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void staleSessionDoesNotInterceptPublicLoginInitiation() throws Exception {
        Session session = authenticatedSession(CUSTOMER_ID, NOW.minus(Duration.ofHours(1)));

        MvcResult result = mockMvc.perform(get("/oauth2/authorization/paycore")
                        .secure(true)
                        .cookie(sessionCookie(session)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).startsWith("https://idp.example.test/authorize?");
    }

    @Test
    void staleSessionDoesNotInterceptPublicLoginCallback() throws Exception {
        Session session = authenticatedSession(CUSTOMER_ID, NOW.minus(Duration.ofHours(1)));

        MvcResult result = mockMvc.perform(get("/login/oauth2/code/paycore")
                        .secure(true)
                        .cookie(sessionCookie(session))
                        .queryParam("error", "access_denied"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"code\":\"forbidden\"}");
    }

    @Test
    void repositoryIdleBoundaryUsesTheSameActivityResetWindowBeforeAndAtThirtyMinutes() throws Exception {
        insertCustomer(CUSTOMER_ID, CustomerStatus.ACTIVE);
        Instant authenticatedAt = NOW.minus(Duration.ofHours(1));
        Session session = authenticatedSession(CUSTOMER_ID, authenticatedAt);
        long resetAt = jdbcClient.sql("""
                        WITH reset_window AS (
                            SELECT (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint - 1799000
                                AS reset_at
                        )
                        UPDATE spring_session
                        SET last_access_time = reset_window.reset_at,
                            expiry_time = reset_window.reset_at + 1800000
                        FROM reset_window
                        WHERE session_id = :id
                        RETURNING last_access_time
                        """)
                .param("id", session.getId()).query(Long.class).single();
        long idleDeadline = resetAt + Duration.ofMinutes(30).toMillis();

        Session immediatelyBeforeDeadline = sessions.findById(session.getId());
        assertThat(immediatelyBeforeDeadline).isNotNull();
        assertThat(System.currentTimeMillis()).isLessThan(idleDeadline);
        assertThat(immediatelyBeforeDeadline.getLastAccessedTime().toEpochMilli()).isEqualTo(resetAt);
        assertThat(immediatelyBeforeDeadline.<Instant>getAttribute(
                CustomerOidcAuthenticationSuccessHandler.AUTHENTICATED_AT_ATTRIBUTE)).isEqualTo(authenticatedAt);

        while (System.currentTimeMillis() < idleDeadline) {
            long remainingMillis = idleDeadline - System.currentTimeMillis();
            LockSupport.parkNanos(Duration.ofMillis(Math.min(remainingMillis, 10)).toNanos());
        }
        authorizedClientManager.reset();

        assertSanitized(perform(sessionCookie(session)), 401, "{\"code\":\"unauthorized\"}");
        assertThat(authorizedClientManager.calls()).isZero();
    }

    @Test
    void statusIsReloadedAndFirstInactiveRequestRevokesAllSessionsThenReuseIsUnauthorized() throws Exception {
        insertCustomer(CUSTOMER_ID, CustomerStatus.ACTIVE);
        Session first = authenticatedSession(CUSTOMER_ID, NOW.minus(Duration.ofHours(1)));
        Session second = authenticatedSession(CUSTOMER_ID, NOW.minus(Duration.ofHours(1)));
        assertThat(perform(sessionCookie(first)).getResponse().getStatus()).isEqualTo(200);
        authorizedClientManager.reset();
        jdbcClient.sql("UPDATE customers SET status = 'SUSPENDED', updated_at = :now WHERE id = :id")
                .param("now", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)).param("id", CUSTOMER_ID).update();

        MvcResult discovered = perform(sessionCookie(first));

        assertSanitized(discovered, 403, "{\"code\":\"forbidden\"}");
        assertThat(indexedSessions().findByPrincipalName(CUSTOMER_ID.toString())).isEmpty();
        assertThat(authorizedClientManager.calls()).isZero();
        assertSanitized(perform(sessionCookie(second)), 401, "{\"code\":\"unauthorized\"}");
    }

    @Test
    void missingCurrentCustomerAccessRevokesTheSessionWithoutAttemptingRefresh() throws Exception {
        Session session = authenticatedSession(CUSTOMER_ID, NOW.minus(Duration.ofHours(1)));

        MvcResult result = perform(sessionCookie(session));

        assertSanitized(result, 403, "{\"code\":\"forbidden\"}");
        assertThat(sessions.findById(session.getId())).isNull();
        assertThat(authorizedClientManager.calls()).isZero();
    }

    public Cookie sessionCookie(Session session) {
        return new Cookie(SESSION_COOKIE, Base64.getEncoder().encodeToString(session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    public Session authenticatedSession(UUID customerId, Instant authenticatedAt) {
        Session session = sessionRepository().createSession();
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                new CustomerPrincipal(customerId), List.of(), "paycore");
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, customerId.toString());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        session.setAttribute(CustomerOidcAuthenticationSuccessHandler.AUTHENTICATED_AT_ATTRIBUTE, authenticatedAt);
        sessionRepository().save(session);
        return session;
    }

    public void insertCustomer(UUID customerId, CustomerStatus status) {
        jdbcClient.sql("""
                        INSERT INTO customers (id, email, customer_type, status, created_at, updated_at)
                        VALUES (:id, :email, 'INDIVIDUAL', :status, :now, :now)
                        """)
                .param("id", customerId)
                .param("email", customerId + "@example.test")
                .param("status", status.name())
                .param("now", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .update();
    }

    private MvcResult perform(Cookie cookie) throws Exception {
        return mockMvc.perform(get("/test/protected").secure(true).cookie(cookie)).andReturn();
    }

    private static void assertSanitized(MvcResult result, int status, String body) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(result.getResponse().getContentAsString()).isEqualTo(body);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("invalid_grant")
                .doesNotContain("refresh-token");
    }

    @SuppressWarnings("unchecked")
    private SessionRepository<Session> sessionRepository() {
        return (SessionRepository<Session>) (SessionRepository<?>) sessions;
    }

    @SuppressWarnings("unchecked")
    private FindByIndexNameSessionRepository<Session> indexedSessions() {
        return (FindByIndexNameSessionRepository<Session>) (FindByIndexNameSessionRepository<?>) sessions;
    }

    public static final class RecordingAuthorizedClientManager implements OAuth2AuthorizedClientManager {

        private final ClientRegistration registration;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<OAuth2AuthorizationException> rejection = new AtomicReference<>();

        public RecordingAuthorizedClientManager(ClientRegistration registration) {
            this.registration = registration;
        }

        @Override
        public OAuth2AuthorizedClient authorize(OAuth2AuthorizeRequest authorizeRequest) {
            calls.incrementAndGet();
            OAuth2AuthorizationException rejected = rejection.get();
            if (rejected != null) {
                throw rejected;
            }
            Instant issuedAt = NOW.minusSeconds(60);
            return new OAuth2AuthorizedClient(registration, authorizeRequest.getPrincipal().getName(),
                    new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "renewed-access-token",
                            issuedAt, NOW.plusSeconds(300)),
                    new OAuth2RefreshToken("refresh-token", issuedAt));
        }

        public int calls() {
            return calls.get();
        }

        public void rejectWith(OAuth2AuthorizationException exception) {
            rejection.set(exception);
        }

        public void reset() {
            calls.set(0);
            rejection.set(null);
        }
    }

    public static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;

        public MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        public void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Task clock is UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
