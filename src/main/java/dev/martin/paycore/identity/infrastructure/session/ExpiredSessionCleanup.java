package dev.martin.paycore.identity.infrastructure.session;

import dev.martin.paycore.identity.infrastructure.security.AuthenticationMetrics;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

@Component
@ConditionalOnBooleanProperty(name = "paycore.authentication.enabled")
public final class ExpiredSessionCleanup {

    private static final Duration MINIMUM_DELAY = Duration.ofMinutes(1);
    private static final Duration MAXIMUM_DELAY = Duration.ofHours(1);

    private final JdbcClient jdbcClient;
    private final Clock clock;
    private final AuthenticationMetrics metrics;
    private final TransactionOperations transactions;
    private final int batchSize;

    public ExpiredSessionCleanup(JdbcClient jdbcClient, Clock clock, AuthenticationMetrics metrics,
            TransactionOperations springSessionTransactionOperations,
            @Value("${paycore.authentication.session-cleanup.delay:5m}") Duration delay,
            @Value("${paycore.authentication.session-cleanup.initial-delay:5m}") Duration initialDelay,
            @Value("${paycore.authentication.session-cleanup.batch-size:1000}") int batchSize) {
        if (!isBounded(delay) || !isBounded(initialDelay)) {
            throw new IllegalArgumentException("Session cleanup delays must be between 1 minute and 1 hour");
        }
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("Session cleanup batch size must be between 1 and 10000");
        }
        this.jdbcClient = jdbcClient;
        this.clock = clock;
        this.metrics = metrics;
        this.transactions = springSessionTransactionOperations;
        this.batchSize = batchSize;
    }

    private static boolean isBounded(Duration delay) {
        return delay.compareTo(MINIMUM_DELAY) >= 0 && delay.compareTo(MAXIMUM_DELAY) <= 0;
    }

    @Scheduled(
            fixedDelayString = "${paycore.authentication.session-cleanup.delay:5m}",
            initialDelayString = "${paycore.authentication.session-cleanup.initial-delay:5m}")
    public int cleanUpExpiredSessions() {
        int deleted = transactions.execute(status -> jdbcClient.sql("""
                                WITH expired AS (
                                    SELECT primary_id
                                    FROM spring_session
                                    WHERE expiry_time < :now
                                    ORDER BY expiry_time, primary_id
                                    LIMIT :batchSize
                                    FOR UPDATE SKIP LOCKED
                                )
                                DELETE FROM spring_session session
                                USING expired
                                WHERE session.primary_id = expired.primary_id
                                """)
                        .param("now", clock.millis())
                        .param("batchSize", batchSize)
                        .update());
        metrics.expiredSessionsCleaned(deleted);
        return deleted;
    }
}
