package dev.martin.paycore.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.identity.infrastructure.session.ExpiredSessionCleanup;
import dev.martin.paycore.identity.infrastructure.session.SpringSessionRevocationAdapter;
import dev.martin.paycore.testsupport.ProtectedSecurityTestConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "paycore.authentication.enabled=true",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false"
})
@Import(ProtectedSecurityTestConfiguration.class)
class AuthenticationObservabilityTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("70000000-0000-0000-0000-000000000007");
    private static final String COOKIE_VALUE = "representative-cookie-value-secret";
    private static final String CREDENTIAL = "representative-password-secret";
    private static final String AUTHORIZATION_CODE = "representative-authorization-code-secret";
    private static final String ACCESS_TOKEN = "representative-access-token-secret";
    private static final String REFRESH_TOKEN = "representative-refresh-token-secret";
    private static final String TOKEN_FRAGMENT = "eyJ-representative-token-fragment";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withEnv("TZ", "UTC")
            .withEnv("PGTZ", "UTC");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcIndexedSessionRepository sessions;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    SpringSessionRevocationAdapter revocations;

    @Autowired
    ExpiredSessionCleanup cleanup;

    @BeforeEach
    void reset() {
        jdbcClient.sql("TRUNCATE TABLE spring_session CASCADE").update();
        jdbcClient.sql("TRUNCATE TABLE external_identities, customers CASCADE").update();
    }

    @Test
    void realFailureRevocationAndCleanupPathsEmitOnlyFixedSanitizedEvents(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/login/oauth2/code/paycore")
                        .secure(true)
                        .queryParam("error", "access_denied")
                        .queryParam("error_description", CREDENTIAL + AUTHORIZATION_CODE + TOKEN_FRAGMENT))
                .andReturn();

        Session denied = authenticatedSession(CUSTOMER_ID);
        mockMvc.perform(get("/test/protected").secure(true).cookie(cookie(denied))).andReturn();

        insertCustomer(CUSTOMER_ID, CustomerStatus.ACTIVE);
        Session refreshRejected = authenticatedSession(CUSTOMER_ID);
        mockMvc.perform(get("/test/protected").secure(true).cookie(cookie(refreshRejected))).andReturn();

        Session current = persistedSession(ACCESS_TOKEN);
        Session first = persistedSession(REFRESH_TOKEN);
        Session second = persistedSession(TOKEN_FRAGMENT);
        revocations.revokeCurrent(current.getId());
        first.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, CUSTOMER_ID.toString());
        second.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, CUSTOMER_ID.toString());
        sessionRepository().save(first);
        sessionRepository().save(second);
        revocations.revokeAll(new CustomerId(CUSTOMER_ID));

        Session expired = persistedSession(COOKIE_VALUE);
        jdbcClient.sql("UPDATE spring_session SET expiry_time = 0 WHERE session_id = :id")
                .param("id", expired.getId()).update();
        cleanup.cleanUpExpiredSessions();

        assertCounter("paycore.authentication.login.failures", "reason", "authentication_rejected", 1);
        assertCounter("paycore.authentication.refresh.failures", "reason", "refresh_rejected", 1);
        assertCounter("paycore.authentication.customer.access.denials", "reason", "customer_unavailable", 1);
        assertCounter("paycore.authentication.session.revocations", "scope", "current", 1);
        assertCounter("paycore.authentication.session.revocations", "scope", "all", 2);
        assertCounter("paycore.authentication.sessions.revoked", "scope", "current", 1);
        assertCounter("paycore.authentication.sessions.revoked", "scope", "all", 3);
        assertCounter("paycore.authentication.session.cleanup.runs", "reason", "scheduled", 1);
        assertCounter("paycore.authentication.sessions.expired", "reason", "expired", 1);

        assertThat(jdbcClient.sql("SELECT count(*) FROM spring_session_attributes")
                .query(Long.class).single()).isZero();
        assertThat(output.getAll())
                .contains("category=login_failure reason=authentication_rejected")
                .contains("category=refresh_failure reason=refresh_rejected")
                .contains("category=customer_access_denial reason=customer_unavailable")
                .contains("category=session_revocation reason=requested")
                .contains("category=session_cleanup reason=expired")
                .doesNotContain(CUSTOMER_ID.toString())
                .doesNotContain(COOKIE_VALUE)
                .doesNotContain(CREDENTIAL)
                .doesNotContain(AUTHORIZATION_CODE)
                .doesNotContain(ACCESS_TOKEN)
                .doesNotContain(REFRESH_TOKEN)
                .doesNotContain(TOKEN_FRAGMENT);
        meters.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("paycore.authentication."))
                .forEach(meter -> meter.getId().getTags().forEach(tag -> assertThat(tag.getValue())
                        .isIn("authentication_rejected", "refresh_rejected", "customer_unavailable",
                                "current", "all", "scheduled", "expired")));
    }

    @Test
    void cleanupScheduleRejectsUnboundedDelayInitialDelayAndBatchSize() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new ExpiredSessionCleanup(
                null, null, null, Duration.ofSeconds(59), Duration.ofMinutes(5), 1000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new ExpiredSessionCleanup(
                null, null, null, Duration.ofMinutes(5), Duration.ofHours(2), 1000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new ExpiredSessionCleanup(
                null, null, null, Duration.ofMinutes(5), Duration.ofMinutes(5), 10_001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Session authenticatedSession(UUID customerId) {
        Session session = sessionRepository().createSession();
        Object principal = new CustomerPrincipal(customerId);
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(principal, null, List.of());
        authentication.setAuthenticated(true);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, customerId.toString());
        session.setAttribute(CustomerOidcAuthenticationSuccessHandler.AUTHENTICATED_AT_ATTRIBUTE,
                Instant.parse("2026-08-08T17:00:00Z"));
        session.setAttribute("representative-access-token", ACCESS_TOKEN);
        session.setAttribute("representative-refresh-token", REFRESH_TOKEN);
        sessionRepository().save(session);
        return session;
    }

    private Session persistedSession(String secret) {
        Session session = sessionRepository().createSession();
        session.setAttribute("secret", secret);
        sessionRepository().save(session);
        return session;
    }

    private void insertCustomer(UUID customerId, CustomerStatus status) {
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.parse("2026-08-08T18:00:00Z"), ZoneOffset.UTC);
        jdbcClient.sql("""
                        INSERT INTO customers (id, email, customer_type, status, created_at, updated_at)
                        VALUES (:id, :email, 'INDIVIDUAL', :status, :now, :now)
                        """)
                .param("id", customerId).param("email", "observability@example.test")
                .param("status", status.name()).param("now", now).update();
    }

    private void assertCounter(String name, String tagName, String tagValue, double expected) {
        assertThat(meters.get(name).tag(tagName, tagValue).counter().count()).isEqualTo(expected);
    }

    private Cookie cookie(Session session) {
        return new Cookie("__Host-paycore-session", Base64.getEncoder().encodeToString(
                session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private SessionRepository<Session> sessionRepository() {
        return (SessionRepository<Session>) (SessionRepository<?>) sessions;
    }
}
