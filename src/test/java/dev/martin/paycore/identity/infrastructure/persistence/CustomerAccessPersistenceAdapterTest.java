package dev.martin.paycore.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.identity.domain.model.ExternalIdentity;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.task.scheduling.enabled=false"
})
class CustomerAccessPersistenceAdapterTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withEnv("TZ", "UTC")
            .withEnv("PGTZ", "UTC");

    @Autowired
    CustomerAccessPersistenceAdapter adapter;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("TRUNCATE TABLE external_identities, customers CASCADE").update();
    }

    @Test
    void resolvesExactIssuerAndSubjectWithoutCrossingIssuerBoundaries() {
        CustomerId first = customer(1, CustomerStatus.ACTIVE);
        CustomerId second = customer(2, CustomerStatus.SUSPENDED);
        identity("https://issuer.example/realms/first", "shared-subject", first);
        identity("https://issuer.example/realms/second", "shared-subject", second);

        var access = adapter.findByExternalIdentity(new ExternalIdentity(
                "https://issuer.example/realms/second", "shared-subject"));

        assertThat(access).hasValueSatisfying(result -> {
            assertThat(result.customerId()).isEqualTo(second);
            assertThat(result.status()).isEqualTo(CustomerStatus.SUSPENDED);
        });
        assertThat(adapter.findByExternalIdentity(new ExternalIdentity(
                "https://issuer.example/realms/unknown", "shared-subject"))).isEmpty();
    }

    @Test
    void resolvesCurrentActiveAndInactiveStatusByCustomerId() {
        CustomerId active = customer(3, CustomerStatus.ACTIVE);
        CustomerId blocked = customer(4, CustomerStatus.BLOCKED);

        assertThat(adapter.findByCustomerId(active)).hasValueSatisfying(access -> {
            assertThat(access.customerId()).isEqualTo(active);
            assertThat(access.isActive()).isTrue();
        });
        assertThat(adapter.findByCustomerId(blocked)).hasValueSatisfying(access -> {
            assertThat(access.customerId()).isEqualTo(blocked);
            assertThat(access.status()).isEqualTo(CustomerStatus.BLOCKED);
            assertThat(access.isActive()).isFalse();
        });
        assertThat(adapter.findByCustomerId(new CustomerId(new UUID(0, 999)))).isEmpty();
    }

    private CustomerId customer(long id, CustomerStatus status) {
        CustomerId customerId = new CustomerId(new UUID(0, id));
        jdbcClient.sql("""
                        INSERT INTO customers
                            (id, email, customer_type, status, created_at, updated_at, version)
                        VALUES (:id, :email, 'INDIVIDUAL', :status, :now, :now, 0)
                        """)
                .param("id", customerId.value())
                .param("email", "customer-" + id + "@example.com")
                .param("status", status.name())
                .param("now", OffsetDateTime.parse("2026-08-08T12:00:00Z"))
                .update();
        return customerId;
    }

    private void identity(String issuer, String subject, CustomerId customerId) {
        jdbcClient.sql("""
                        INSERT INTO external_identities (issuer, subject, customer_id, created_at)
                        VALUES (:issuer, :subject, :customerId, :now)
                        """)
                .param("issuer", issuer)
                .param("subject", subject)
                .param("customerId", customerId.value())
                .param("now", OffsetDateTime.parse("2026-08-08T12:00:00Z"))
                .update();
    }
}
