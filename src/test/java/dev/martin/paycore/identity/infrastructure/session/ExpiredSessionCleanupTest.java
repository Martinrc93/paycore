package dev.martin.paycore.identity.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.martin.paycore.identity.infrastructure.security.AuthenticationMetrics;
import dev.martin.paycore.testsupport.ProtectedSecurityTestConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = {
        "paycore.authentication.enabled=true",
        "spring.main.allow-bean-definition-overriding=true",
        "paycore.authentication.session-cleanup.delay=1h",
        "paycore.authentication.session-cleanup.initial-delay=1h",
        "paycore.authentication.session-cleanup.batch-size=2"
})
@Import(ProtectedSecurityTestConfiguration.class)
class ExpiredSessionCleanupTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withEnv("TZ", "UTC")
            .withEnv("PGTZ", "UTC");

    @Autowired
    ExpiredSessionCleanup cleanup;

    @Autowired
    JdbcIndexedSessionRepository sessions;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    MeterRegistry meters;

    @Autowired
    AuthenticationMetrics metrics;

    @Autowired
    Clock clock;

    @Autowired
    TransactionOperations springSessionTransactionOperations;

    @Autowired
    ScheduledAnnotationBeanPostProcessor scheduledMethods;

    @BeforeEach
    void resetDatabase() {
        jdbcClient.sql("DROP TRIGGER IF EXISTS task7_slow_delete ON spring_session").update();
        jdbcClient.sql("DROP TRIGGER IF EXISTS task7_reject_delete ON spring_session").update();
        jdbcClient.sql("TRUNCATE TABLE spring_session CASCADE").update();
    }

    @Test
    void oneRunDeletesAtMostTheConfiguredBatchAndCascadesTokenAttributes() {
        saveExpired("batch-token-one");
        saveExpired("batch-token-two");
        saveExpired("batch-token-three");
        double expiredBefore = counter("paycore.authentication.sessions.expired", "reason", "expired");

        assertThat(cleanup.cleanUpExpiredSessions()).isEqualTo(2);

        assertThat(sessionCount()).isEqualTo(1);
        assertThat(attributeCount()).isEqualTo(1);
        assertThat(counter("paycore.authentication.sessions.expired", "reason", "expired") - expiredBefore)
                .isEqualTo(2);
    }

    @Test
    void concurrentReplicasLockDisjointBatchesAndCountEveryDeletedSessionOnce() throws Exception {
        for (int index = 0; index < 4; index++) {
            saveExpired("concurrent-token-" + index);
        }
        jdbcClient.sql("""
                CREATE OR REPLACE FUNCTION task7_slow_session_delete() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    PERFORM pg_sleep(0.15);
                    RETURN OLD;
                END
                $$
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER task7_slow_delete
                BEFORE DELETE ON spring_session
                FOR EACH ROW EXECUTE FUNCTION task7_slow_session_delete()
                """).update();
        double expiredBefore = counter("paycore.authentication.sessions.expired", "reason", "expired");
        double runsBefore = counter("paycore.authentication.session.cleanup.runs", "reason", "scheduled");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            results.add(executor.submit(() -> cleanAfter(start)));
            results.add(executor.submit(() -> cleanAfter(start)));
            start.countDown();
            assertThat(results.get(0).get()).isEqualTo(2);
            assertThat(results.get(1).get()).isEqualTo(2);
        }

        assertThat(sessionCount()).isZero();
        assertThat(attributeCount()).isZero();
        assertThat(counter("paycore.authentication.sessions.expired", "reason", "expired") - expiredBefore)
                .isEqualTo(4);
        assertThat(counter("paycore.authentication.session.cleanup.runs", "reason", "scheduled") - runsBefore)
                .isEqualTo(2);
    }

    @Test
    void cleanupRunsInsideTheConfiguredRealTransaction() {
        saveExpired("transaction-token");
        AtomicBoolean transactionObserved = new AtomicBoolean();
        TransactionOperations observingTransactions = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return springSessionTransactionOperations.execute(status -> {
                    transactionObserved.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return action.doInTransaction(status);
                });
            }
        };
        ExpiredSessionCleanup transactionalCleanup = new ExpiredSessionCleanup(
                jdbcClient, clock, metrics, observingTransactions,
                Duration.ofMinutes(5), Duration.ofMinutes(5), 1);

        assertThat(transactionalCleanup.cleanUpExpiredSessions()).isEqualTo(1);
        assertThat(transactionObserved).isTrue();
    }

    @Test
    void failedDeleteRollsBackTheBatchWithoutEmittingCommittedCleanupCounts() {
        saveExpired("rollback-token-one");
        saveExpired("rollback-token-two");
        jdbcClient.sql("""
                CREATE OR REPLACE FUNCTION task7_reject_session_delete() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'task7 rollback sentinel';
                END
                $$
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER task7_reject_delete
                BEFORE DELETE ON spring_session
                FOR EACH ROW EXECUTE FUNCTION task7_reject_session_delete()
                """).update();
        double expiredBefore = counter("paycore.authentication.sessions.expired", "reason", "expired");
        double runsBefore = counter("paycore.authentication.session.cleanup.runs", "reason", "scheduled");

        assertThatThrownBy(cleanup::cleanUpExpiredSessions).isInstanceOf(RuntimeException.class);

        assertThat(sessionCount()).isEqualTo(2);
        assertThat(attributeCount()).isEqualTo(2);
        assertThat(counter("paycore.authentication.sessions.expired", "reason", "expired"))
                .isEqualTo(expiredBefore);
        assertThat(counter("paycore.authentication.session.cleanup.runs", "reason", "scheduled"))
                .isEqualTo(runsBefore);
    }

    @Test
    void cleanupMethodIsRegisteredWithTheApplicationScheduler() {
        assertThat(scheduledMethods.getScheduledTasks())
                .anySatisfy(task -> assertThat(task.toString()).contains("cleanUpExpiredSessions"));
    }

    private int cleanAfter(CountDownLatch start) throws InterruptedException {
        start.await();
        return cleanup.cleanUpExpiredSessions();
    }

    private void saveExpired(String token) {
        Session session = sessionRepository().createSession();
        session.setAttribute("oauth-token", token);
        sessionRepository().save(session);
        jdbcClient.sql("UPDATE spring_session SET expiry_time = 0 WHERE session_id = :id")
                .param("id", session.getId()).update();
    }

    private double counter(String name, String tagName, String tagValue) {
        Counter counter = meters.find(name).tag(tagName, tagValue).counter();
        return counter == null ? 0 : counter.count();
    }

    private long sessionCount() {
        return jdbcClient.sql("SELECT count(*) FROM spring_session").query(Long.class).single();
    }

    private long attributeCount() {
        return jdbcClient.sql("SELECT count(*) FROM spring_session_attributes").query(Long.class).single();
    }

    @SuppressWarnings("unchecked")
    private SessionRepository<Session> sessionRepository() {
        return (SessionRepository<Session>) (SessionRepository<?>) sessions;
    }
}
