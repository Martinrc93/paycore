package dev.martin.paycore.identity.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;

import dev.martin.paycore.identity.domain.model.CustomerId;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.task.scheduling.enabled=false"
})
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
    JdbcClient jdbcClient;

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

    private long sessionCount() {
        return jdbcClient.sql("SELECT count(*) FROM spring_session").query(Long.class).single();
    }

    private long attributeCount() {
        return jdbcClient.sql("SELECT count(*) FROM spring_session_attributes").query(Long.class).single();
    }

    @SuppressWarnings("unchecked")
    private SessionRepository<Session> sessions() {
        return (SessionRepository<Session>) (SessionRepository<?>) repository;
    }

    @SuppressWarnings("unchecked")
    private FindByIndexNameSessionRepository<Session> indexedSessions() {
        return (FindByIndexNameSessionRepository<Session>) (FindByIndexNameSessionRepository<?>) repository;
    }

    private static CustomerId customerId(long value) {
        return new CustomerId(new UUID(0, value));
    }
}
