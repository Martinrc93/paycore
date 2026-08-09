package dev.martin.paycore.identity.infrastructure.session;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBooleanProperty(name = "paycore.authentication.enabled")
final class AuthenticationSessionMetrics {

    AuthenticationSessionMetrics(MeterRegistry meters, JdbcClient jdbcClient) {
        Gauge.builder("paycore.authentication.sessions.active", jdbcClient,
                        AuthenticationSessionMetrics::activeSessionCount)
                .description("Replicated global PostgreSQL active-session count; aggregate replicas with max, never sum")
                .strongReference(true)
                .register(meters);
    }

    private static double activeSessionCount(JdbcClient jdbcClient) {
        return jdbcClient.sql("""
                        SELECT count(*)
                        FROM spring_session
                        WHERE expiry_time >= (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint
                        """)
                .query(Long.class)
                .single();
    }
}
