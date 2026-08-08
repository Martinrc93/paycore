package dev.martin.paycore.identity.infrastructure.persistence;

import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
public class RegistrationCleanupAdapter {

    private final RegistrationOperationJpaRepository repository;
    private final JdbcClient jdbcClient;

    public RegistrationCleanupAdapter(
            RegistrationOperationJpaRepository repository, JdbcClient jdbcClient) {
        this.repository = repository;
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public int deleteExpiredTerminal(Instant now) {
        return repository.deleteExpiredTerminal(now);
    }

    @Transactional
    public int deleteExpiredRateLimits(Instant now) {
        return jdbcClient.sql("DELETE FROM registration_rate_limits WHERE expires_at <= :now")
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }
}
