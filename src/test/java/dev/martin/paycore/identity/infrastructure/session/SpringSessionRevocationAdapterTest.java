package dev.martin.paycore.identity.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;

import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.identity.application.authentication.SessionLifetimePolicy;
import java.time.Duration;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.task.scheduling.enabled=false"
})
@Import(SpringSessionRevocationAdapterTest.GuardedSessionConfiguration.class)
class SpringSessionRevocationAdapterTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withEnv("TZ", "UTC")
            .withEnv("PGTZ", "UTC");

    @Autowired
    SpringSessionRevocationAdapter adapter;

    @Autowired
    JdbcIndexedSessionRepository repository;

    @Autowired
    FindByIndexNameSessionRepository<Session> guardedRepository;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanSessions() {
        jdbcClient.sql("TRUNCATE TABLE spring_session CASCADE").update();
    }

    @Test
    void usesThirtyMinuteIdleTimeoutAndPersistsAShorterExpiryCap() {
        SessionRepository<Session> sessions = sessions();
        Session defaultSession = sessions.createSession();
        assertThat(defaultSession.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));

        defaultSession.setMaxInactiveInterval(Duration.ofMinutes(7));
        sessions.save(defaultSession);

        assertThat(sessions.findById(defaultSession.getId()).getMaxInactiveInterval())
                .isEqualTo(Duration.ofMinutes(7));
        assertThat(jdbcClient.sql("SELECT max_inactive_interval FROM spring_session")
                .query(Integer.class).single()).isEqualTo(420);
    }

    @Test
    void savesTwoIndependentConcurrentSessionsForOneCustomer() throws Exception {
        CustomerId customerId = customerId(10);
        var first = session(customerId, "first-token");
        var second = session(customerId, "second-token");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> firstSave = executor.submit(() -> saveAfter(start, first));
            Future<?> secondSave = executor.submit(() -> saveAfter(start, second));
            start.countDown();
            firstSave.get();
            secondSave.get();
        }

        assertThat(indexedSessions().findByPrincipalName(customerId.value().toString()))
                .containsOnlyKeys(first.getId(), second.getId());
        String firstToken = sessions().findById(first.getId()).getAttribute("oauth-token");
        String secondToken = sessions().findById(second.getId()).getAttribute("oauth-token");
        assertThat(firstToken).isEqualTo("first-token");
        assertThat(secondToken).isEqualTo("second-token");
    }

    @Test
    void revokesOnlyTheSuppliedCurrentSessionAndCascadesItsAttributes() {
        CustomerId customerId = customerId(20);
        var current = savedSession(customerId, "current-token");
        var other = savedSession(customerId, "other-token");
        assertThat(attributeCount()).isEqualTo(4);

        adapter.revokeCurrent(current.getId());

        assertThat(sessions().findById(current.getId())).isNull();
        assertThat(sessions().findById(other.getId())).isNotNull();
        assertThat(attributeCount()).isEqualTo(2);
    }

    @Test
    void revokesAllSessionsByStableCustomerPrincipalAndNoOthers() {
        CustomerId customerId = customerId(30);
        CustomerId otherCustomer = customerId(31);
        var first = savedSession(customerId, "access-token");
        var second = savedSession(customerId, "refresh-token");
        var unrelated = savedSession(otherCustomer, "other-token");
        assertThat(attributeCount()).isEqualTo(6);

        adapter.revokeAll(customerId);

        assertThat(sessions().findById(first.getId())).isNull();
        assertThat(sessions().findById(second.getId())).isNull();
        assertThat(sessions().findById(unrelated.getId())).isNotNull();
        assertThat(attributeCount()).isEqualTo(2);
    }

    @Test
    void doesNotPersistASessionForAnIneligibleCustomer() {
        CustomerId customerId = customerId(40);
        insertCustomer(customerId, CustomerStatus.BLOCKED);
        Session session = session(customerId, "blocked-token");

        sessions().save(session);

        assertThat(sessions().findById(session.getId())).isNull();
    }

    @Test
    void statusChangeAndSessionSaveSerializeOnTheCustomerRow() throws Exception {
        CustomerId customerId = customerId(41);
        Session session = session(customerId, "racing-token");
        CountDownLatch customerLocked = new CountDownLatch(1);
        CountDownLatch releaseStatusChange = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> statusChange = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(ignored -> {
                        jdbcClient.sql("SELECT id FROM customers WHERE id=:id FOR UPDATE")
                                .param("id", customerId.value()).query(UUID.class).single();
                        customerLocked.countDown();
                        await(releaseStatusChange);
                        jdbcClient.sql("UPDATE customers SET status='BLOCKED', updated_at=:now, version=version+1 WHERE id=:id")
                                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                                .param("id", customerId.value()).update();
                        jdbcClient.sql("DELETE FROM spring_session WHERE principal_name=:principal")
                                .param("principal", customerId.value().toString()).update();
                    }));
            customerLocked.await();
            Future<?> sessionSave = executor.submit(() -> sessions().save(session));
            releaseStatusChange.countDown();
            statusChange.get();
            sessionSave.get();
        }

        assertThat(sessions().findById(session.getId())).isNull();
    }

    @Test
    void cleanupDeletesExpiredSessionAndItsPersistedTokenAttributes() {
        Session expired = sessions().createSession();
        expired.setAttribute("access-token", "expired-access-token");
        expired.setAttribute("refresh-token", "expired-refresh-token");
        sessions().save(expired);
        jdbcClient.sql("UPDATE spring_session SET expiry_time = 0").update();

        repository.cleanUpExpiredSessions();

        assertThat(sessions().findById(expired.getId())).isNull();
        assertThat(sessionCount()).isZero();
        assertThat(attributeCount()).isZero();
    }

    private Session savedSession(CustomerId customerId, String token) {
        Session session = session(customerId, token);
        sessions().save(session);
        return session;
    }

    private Session session(CustomerId customerId, String token) {
        insertActiveCustomerIfAbsent(customerId);
        Session session = sessions().createSession();
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                customerId.value().toString());
        session.setAttribute("oauth-token", token);
        return session;
    }

    private void saveAfter(CountDownLatch start, Session session) {
        try {
            start.await();
            sessions().save(session);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private long sessionCount() {
        return jdbcClient.sql("SELECT count(*) FROM spring_session").query(Long.class).single();
    }

    private long attributeCount() {
        return jdbcClient.sql("SELECT count(*) FROM spring_session_attributes").query(Long.class).single();
    }

    private void insertCustomer(CustomerId customerId, CustomerStatus status) {
        jdbcClient.sql("""
                        INSERT INTO customers (id, email, customer_type, status, created_at, updated_at)
                        VALUES (:id, :email, 'INDIVIDUAL', :status, :now, :now)
                        """)
                .param("id", customerId.value())
                .param("email", customerId + "@example.test")
                .param("status", status.name())
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
    }

    private void insertActiveCustomerIfAbsent(CustomerId customerId) {
        jdbcClient.sql("""
                        INSERT INTO customers (id, email, customer_type, status, created_at, updated_at)
                        VALUES (:id, :email, 'INDIVIDUAL', 'ACTIVE', :now, :now)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", customerId.value())
                .param("email", customerId + "@example.test")
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
    }

    @SuppressWarnings("unchecked")
    private SessionRepository<Session> sessions() {
        return (SessionRepository<Session>) (SessionRepository<?>) guardedRepository;
    }

    @SuppressWarnings("unchecked")
    private FindByIndexNameSessionRepository<Session> indexedSessions() {
        return (FindByIndexNameSessionRepository<Session>) (FindByIndexNameSessionRepository<?>) repository;
    }

    private static CustomerId customerId(long value) {
        return new CustomerId(new UUID(0, value));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GuardedSessionConfiguration {

        @Bean
        @Primary
        FindByIndexNameSessionRepository<Session> guardedRepository(
                JdbcIndexedSessionRepository delegate, JdbcClient jdbcClient,
                PlatformTransactionManager transactionManager) {
            Clock clock = Clock.fixed(java.time.Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC);
            return new AbsoluteExpirySessionRepository(
                    delegate, jdbcClient, new SessionLifetimePolicy(clock),
                    new TransactionTemplate(transactionManager));
        }
    }
}
