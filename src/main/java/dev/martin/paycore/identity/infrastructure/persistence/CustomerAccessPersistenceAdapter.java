package dev.martin.paycore.identity.infrastructure.persistence;

import dev.martin.paycore.identity.application.authentication.CustomerAccess;
import dev.martin.paycore.identity.application.port.out.CustomerAccessRepository;
import dev.martin.paycore.identity.application.port.out.CustomerActivationPort;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.identity.domain.model.ExternalIdentity;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.Types;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CustomerAccessPersistenceAdapter implements CustomerAccessRepository, CustomerActivationPort {

    private final JdbcClient jdbcClient;

    public CustomerAccessPersistenceAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<CustomerAccess> findByExternalIdentity(ExternalIdentity identity) {
        return jdbcClient.sql("""
                        SELECT c.id, c.status
                        FROM customers c
                        JOIN external_identities e ON e.customer_id = c.id
                        WHERE e.issuer = :issuer AND e.subject = :subject
                        """)
                .param("issuer", identity.issuer())
                .param("subject", identity.subject())
                .query(this::mapAccess)
                .optional();
    }

    @Override
    public Optional<CustomerAccess> findByCustomerId(CustomerId customerId) {
        return jdbcClient.sql("SELECT id, status FROM customers WHERE id = :id")
                .param("id", customerId.value())
                .query(this::mapAccess)
                .optional();
    }

    @Override
    @Transactional
    public Optional<CustomerAccess> activatePending(CustomerId customerId, Instant activatedAt) {
        int updated = jdbcClient.sql("""
                        UPDATE customers
                           SET status='ACTIVE', updated_at=:activatedAt, version=version+1
                         WHERE id=:id AND status='PENDING_VERIFICATION'
                        """)
                .param("activatedAt", atUtc(activatedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", customerId.value())
                .update();
        return findByCustomerId(customerId).filter(access -> updated == 1 || access.isActive());
    }

    private CustomerAccess mapAccess(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new CustomerAccess(
                new CustomerId(resultSet.getObject("id", UUID.class)),
                CustomerStatus.valueOf(resultSet.getString("status")));
    }

    private static OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
