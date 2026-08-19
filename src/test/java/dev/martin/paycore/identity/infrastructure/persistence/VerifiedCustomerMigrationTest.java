package dev.martin.paycore.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.Types;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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
        "spring.flyway.target=3",
        "spring.task.scheduling.enabled=false"
})
class VerifiedCustomerMigrationTest {

    private static final Instant BEFORE_MIGRATION = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID ACTIVE_CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SUSPENDED_CUSTOMER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BLOCKED_CUSTOMER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withEnv("TZ", "UTC").withEnv("PGTZ", "UTC");

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("TRUNCATE TABLE spring_session CASCADE").update();
        jdbcClient.sql("TRUNCATE TABLE external_identities, registration_operations, customers, registration_rate_limits")
                .update();
    }

    @Test
    void migratesActiveCustomersAndRevokesTheirSessionsOnly() {
        insertCustomer(ACTIVE_CUSTOMER, "ACTIVE", 4);
        insertCustomer(SUSPENDED_CUSTOMER, "SUSPENDED", 7);
        insertCustomer(BLOCKED_CUSTOMER, "BLOCKED", 9);
        insertSession(ACTIVE_CUSTOMER, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        insertSession(SUSPENDED_CUSTOMER, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(status(ACTIVE_CUSTOMER)).isEqualTo("PENDING_VERIFICATION");
        assertThat(version(ACTIVE_CUSTOMER)).isEqualTo(5L);
        assertThat(updatedAt(ACTIVE_CUSTOMER)).isAfter(BEFORE_MIGRATION);
        assertThat(status(SUSPENDED_CUSTOMER)).isEqualTo("SUSPENDED");
        assertThat(status(BLOCKED_CUSTOMER)).isEqualTo("BLOCKED");
        assertThat(sessionCount(ACTIVE_CUSTOMER)).isZero();
        assertThat(sessionCount(SUSPENDED_CUSTOMER)).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM wallets WHERE customer_id=:id")
                .param("id", ACTIVE_CUSTOMER).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM ledger_account_balances")
                .query(Long.class).single()).isZero();
    }

    private void insertCustomer(UUID id, String status, long version) {
        jdbcClient.sql("""
                        INSERT INTO customers (id, email, customer_type, status, created_at, updated_at, version)
                        VALUES (:id, :email, 'INDIVIDUAL', :status, :at, :at, :version)
                        """)
                .param("id", id)
                .param("email", id + "@example.com")
                .param("status", status)
                .param("at", atUtc(BEFORE_MIGRATION), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("version", version)
                .update();
    }

    private void insertSession(UUID customerId, String sessionId) {
        jdbcClient.sql("""
                        INSERT INTO spring_session
                            (primary_id, session_id, creation_time, last_access_time, max_inactive_interval,
                             expiry_time, principal_name)
                        VALUES (:id, :id, 1, 1, 1800, 9999999999999, :principal)
                        """)
                .param("id", sessionId)
                .param("principal", customerId.toString())
                .update();
    }

    private String status(UUID customerId) {
        return jdbcClient.sql("SELECT status FROM customers WHERE id=:id")
                .param("id", customerId).query(String.class).single();
    }

    private long version(UUID customerId) {
        return jdbcClient.sql("SELECT version FROM customers WHERE id=:id")
                .param("id", customerId).query(Long.class).single();
    }

    private Instant updatedAt(UUID customerId) {
        return jdbcClient.sql("SELECT updated_at FROM customers WHERE id=:id")
                .param("id", customerId).query(OffsetDateTime.class).single().toInstant();
    }

    private long sessionCount(UUID customerId) {
        return jdbcClient.sql("SELECT count(*) FROM spring_session WHERE principal_name=:principal")
                .param("principal", customerId.toString()).query(Long.class).single();
    }

    private static OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
