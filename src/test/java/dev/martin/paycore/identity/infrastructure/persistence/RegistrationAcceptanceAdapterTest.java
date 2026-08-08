package dev.martin.paycore.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.martin.paycore.identity.application.registration.IdempotencyConflictException;
import dev.martin.paycore.identity.application.registration.IdempotencyDigester;
import dev.martin.paycore.identity.application.registration.IdempotencyDigests;
import dev.martin.paycore.identity.application.registration.RegisterCustomerCommand;
import dev.martin.paycore.identity.application.registration.RegisterCustomerService;
import dev.martin.paycore.identity.application.registration.RegistrationIntent;
import dev.martin.paycore.identity.application.registration.RegistrationResponse;
import dev.martin.paycore.identity.application.registration.VersionedDigest;
import dev.martin.paycore.identity.domain.model.Customer;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerType;
import dev.martin.paycore.identity.domain.model.Email;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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
class RegistrationAcceptanceAdapterTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withEnv("TZ", "UTC")
            .withEnv("PGTZ", "UTC");

    @Autowired
    RegistrationAcceptanceAdapter adapter;

    @Autowired
    CustomerPersistenceAdapter customerPersistenceAdapter;

    @Autowired
    RegistrationCleanupAdapter cleanupAdapter;

    @Autowired
    JdbcClient jdbcClient;

    private final AtomicInteger ids = new AtomicInteger();

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("TRUNCATE TABLE registration_operations, external_identities, customers, registration_rate_limits")
                .update();
    }

    @Test
    void storesNewCustomerAndPendingOperationAtomically() {
        service().register(new RegisterCustomerCommand("key-1", "person@example.com", CustomerType.INDIVIDUAL));

        assertThat(count("customers")).isEqualTo(1);
        assertThat(count("registration_operations")).isEqualTo(1);
        assertThat(singleString("SELECT status FROM customers")).isEqualTo("PROVISIONING");
        assertThat(singleString("SELECT state FROM registration_operations")).isEqualTo("PENDING_IDENTITY");
        assertThat(singleString("SELECT email FROM customers")).isEqualTo("person@example.com");
    }

    @Test
    void suppressesDuplicateEmailWithoutCreatingAnotherCustomer() {
        service().register(new RegisterCustomerCommand("key-1", "person@example.com", CustomerType.INDIVIDUAL));
        service().register(new RegisterCustomerCommand("key-2", " PERSON@example.com ", CustomerType.BUSINESS));

        assertThat(count("customers")).isEqualTo(1);
        assertThat(count("registration_operations")).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT count(*) FROM registration_operations WHERE state='DUPLICATE_SUPPRESSED'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void replaysSameKeyAndRejectsDifferentPayload() {
        RegisterCustomerService service = service();
        service.register(new RegisterCustomerCommand("same-key", "person@example.com", CustomerType.INDIVIDUAL));

        service.register(new RegisterCustomerCommand("same-key", "person@example.com", CustomerType.INDIVIDUAL));
        assertThatThrownBy(() -> service.register(
                new RegisterCustomerCommand("same-key", "other@example.com", CustomerType.BUSINESS)))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(count("registration_operations")).isEqualTo(1);
        assertThat(count("customers")).isEqualTo(1);
    }

    @Test
    void allowsKeyReuseAfterRetentionExpires() {
        service().register(new RegisterCustomerCommand(
                "reusable-key", "first@example.com", CustomerType.INDIVIDUAL));

        serviceAt(Instant.parse("2026-08-09T12:00:01Z")).register(new RegisterCustomerCommand(
                "reusable-key", "second@example.com", CustomerType.BUSINESS));

        assertThat(count("registration_operations")).isEqualTo(2);
        assertThat(count("customers")).isEqualTo(2);
    }

    @Test
    void concurrentDifferentKeysCreateOneCustomer() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RegistrationResponse> first = executor.submit(() -> registerAfter(start, "key-a"));
            Future<RegistrationResponse> second = executor.submit(() -> registerAfter(start, "key-b"));
            start.countDown();
            assertThat(first.get()).isEqualTo(RegistrationResponse.accepted());
            assertThat(second.get()).isEqualTo(RegistrationResponse.accepted());
        }

        assertThat(count("customers")).isEqualTo(1);
        assertThat(count("registration_operations")).isEqualTo(2);
    }

    @Test
    void concurrentSameKeyAndPayloadReturnTheGenericAcceptedResponse() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RegistrationResponse> first = executor.submit(() -> registerAfter(start, "shared-key"));
            Future<RegistrationResponse> second = executor.submit(() -> registerAfter(start, "shared-key"));
            start.countDown();

            assertThat(first.get()).isEqualTo(RegistrationResponse.accepted());
            assertThat(second.get()).isEqualTo(RegistrationResponse.accepted());
        }

        assertThat(count("customers")).isEqualTo(1);
        assertThat(count("registration_operations")).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyWithDifferentPayloadCreatesOnlyWinningCustomer() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> attemptRegistrationAfter(
                    start, "shared-key", "first@example.com"));
            Future<Boolean> second = executor.submit(() -> attemptRegistrationAfter(
                    start, "shared-key", "second@example.com"));
            start.countDown();

            assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
        }

        assertThat(count("customers")).isEqualTo(1);
        assertThat(count("registration_operations")).isEqualTo(1);
    }

    @Test
    void preservesUtcInstants() {
        service().register(new RegisterCustomerCommand("key-1", "person@example.com", CustomerType.INDIVIDUAL));

        assertThat(jdbcClient.sql("SELECT created_at FROM customers")
                .query(OffsetDateTime.class).single().toInstant())
                .isEqualTo(Instant.parse("2026-08-08T12:00:00Z"));
    }

    @Test
    void mapsPersistenceCustomerBackToDomain() {
        service().register(new RegisterCustomerCommand("key-1", "person@example.com", CustomerType.BUSINESS));

        var customer = customerPersistenceAdapter.findById(
                new CustomerId(new UUID(0, 1))).orElseThrow();

        assertThat(customer.email().value()).isEqualTo("person@example.com");
        assertThat(customer.type()).isEqualTo(CustomerType.BUSINESS);
        assertThat(customer.createdAt()).isEqualTo(Instant.parse("2026-08-08T12:00:00Z"));
    }

    @Test
    void rollsBackCustomerWhenOperationInsertFails() {
        Instant now = Instant.parse("2026-08-08T12:00:00Z");
        VersionedDigest oversizedDigest = new VersionedDigest(1, "a".repeat(65));
        RegistrationIntent invalidIntent = new RegistrationIntent(
                new IdempotencyDigests(oversizedDigest, List.of(oversizedDigest)),
                "f".repeat(64),
                Customer.register(new CustomerId(new UUID(0, 99)), Email.of("rollback@example.com"),
                        CustomerType.INDIVIDUAL, now),
                now.plusSeconds(24 * 60 * 60));

        assertThatThrownBy(() -> adapter.accept(invalidIntent))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(count("customers")).isZero();
        assertThat(count("registration_operations")).isZero();
    }

    @Test
    void retainsCompletedResultForFullIdempotencyWindow() {
        service().register(new RegisterCustomerCommand("key-1", "person@example.com", CustomerType.INDIVIDUAL));
        jdbcClient.sql("UPDATE registration_operations SET state='COMPLETED'").update();

        assertThat(cleanupAdapter.deleteExpiredTerminal(Instant.parse("2026-08-09T11:59:59.999999Z")))
                .isZero();
        assertThat(count("registration_operations")).isEqualTo(1);

        assertThat(cleanupAdapter.deleteExpiredTerminal(Instant.parse("2026-08-09T12:00:00Z")))
                .isEqualTo(1);
        assertThat(count("registration_operations")).isZero();
    }

    @Test
    void cleanupDeletesExpiredDuplicateButNeverReconciliationRequired() {
        service().register(new RegisterCustomerCommand("key-1", "person@example.com", CustomerType.INDIVIDUAL));
        service().register(new RegisterCustomerCommand("key-2", "person@example.com", CustomerType.BUSINESS));
        jdbcClient.sql("""
                        UPDATE registration_operations
                        SET state='RECONCILIATION_REQUIRED'
                        WHERE state='PENDING_IDENTITY'
                        """).update();

        assertThat(cleanupAdapter.deleteExpiredTerminal(Instant.parse("2026-08-09T12:00:00Z")))
                .isEqualTo(1);
        assertThat(count("registration_operations")).isEqualTo(1);
        assertThat(singleString("SELECT state FROM registration_operations"))
                .isEqualTo("RECONCILIATION_REQUIRED");
    }

    @Test
    void cleanupDeletesExpiredRateLimitBuckets() {
        jdbcClient.sql("""
                        INSERT INTO registration_rate_limits (bucket_key, window_start, attempts, expires_at)
                        VALUES (:key, :windowStart, 1, :expiresAt)
                        """)
                .param("key", "a".repeat(64))
                .param("windowStart", OffsetDateTime.parse("2026-08-08T11:58:00Z"))
                .param("expiresAt", OffsetDateTime.parse("2026-08-08T12:00:00Z"))
                .update();

        assertThat(cleanupAdapter.deleteExpiredRateLimits(Instant.parse("2026-08-08T12:00:00Z")))
                .isEqualTo(1);
        assertThat(count("registration_rate_limits")).isZero();
    }

    private RegistrationResponse registerAfter(CountDownLatch start, String key) {
        try {
            start.await();
            return service().register(
                    new RegisterCustomerCommand(key, "person@example.com", CustomerType.INDIVIDUAL));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private boolean attemptRegistrationAfter(CountDownLatch start, String key, String email) {
        try {
            start.await();
            service().register(new RegisterCustomerCommand(key, email, CustomerType.INDIVIDUAL));
            return true;
        } catch (IdempotencyConflictException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private RegisterCustomerService service() {
        return serviceAt(Instant.parse("2026-08-08T12:00:00Z"));
    }

    private RegisterCustomerService serviceAt(Instant now) {
        return new RegisterCustomerService(
                adapter,
                new IdempotencyDigester(1, Map.of(
                        1, "registration-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8))),
                Clock.fixed(now, ZoneOffset.UTC),
                () -> new CustomerId(new UUID(0, ids.incrementAndGet())));
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private String singleString(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }
}
