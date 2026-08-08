package dev.martin.paycore.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.martin.paycore.identity.application.registration.ClaimedRegistration;
import dev.martin.paycore.identity.application.registration.IdempotencyDigester;
import dev.martin.paycore.identity.application.registration.ProvisionedIdentity;
import dev.martin.paycore.identity.application.registration.RegisterCustomerCommand;
import dev.martin.paycore.identity.application.registration.RegisterCustomerService;
import dev.martin.paycore.identity.application.registration.RegistrationOperationState;
import dev.martin.paycore.identity.application.registration.RegistrationIntegrityException;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class RegistrationWorkAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withEnv("TZ", "UTC").withEnv("PGTZ", "UTC");

    @Autowired
    RegistrationAcceptanceAdapter acceptanceAdapter;

    @Autowired
    RegistrationWorkAdapter workAdapter;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void prepareOperation() {
        jdbcClient.sql("TRUNCATE TABLE registration_operations, external_identities, customers, registration_rate_limits")
                .update();
        new RegisterCustomerService(
                acceptanceAdapter,
                new IdempotencyDigester(1, Map.of(
                        1, "registration-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8))),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> new CustomerId(UUID.fromString("11111111-1111-1111-1111-111111111111")))
                .register(new RegisterCustomerCommand("key-1", "person@example.com", CustomerType.INDIVIDUAL));
    }

    @Test
    void grantsOneLeaseAndFencesCompetingClaim() {
        ClaimedRegistration first = workAdapter.claimNext(NOW, Duration.ofMinutes(2)).orElseThrow();
        Optional<ClaimedRegistration> competing = workAdapter.claimNext(NOW, Duration.ofMinutes(2));

        assertThat(first.fencingVersion()).isEqualTo(1);
        assertThat(first.attemptCount()).isEqualTo(1);
        assertThat(competing).isEmpty();
    }

    @Test
    void concurrentWorkersGrantExactlyOneActiveLease() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<ClaimedRegistration>> first = executor.submit(() -> claimAfter(start));
            Future<Optional<ClaimedRegistration>> second = executor.submit(() -> claimAfter(start));
            start.countDown();

            int claimsGranted = (first.get().isPresent() ? 1 : 0) + (second.get().isPresent() ? 1 : 0);
            assertThat(claimsGranted).isEqualTo(1);
        }
    }

    @Test
    void reclaimsExpiredLeaseWithNextFencingVersion() {
        workAdapter.claimNext(NOW, Duration.ofSeconds(30)).orElseThrow();

        ClaimedRegistration reclaimed = workAdapter.claimNext(NOW.plusSeconds(31), Duration.ofMinutes(2))
                .orElseThrow();

        assertThat(reclaimed.fencingVersion()).isEqualTo(2);
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
    }

    @Test
    void renewsCurrentLeaseBeforeRemoteWork() {
        ClaimedRegistration claim = workAdapter.claimNext(NOW, Duration.ofSeconds(30)).orElseThrow();

        assertThat(workAdapter.renewLease(claim, NOW.plusSeconds(20), Duration.ofMinutes(2))).isTrue();
        assertThat(workAdapter.claimNext(NOW.plusSeconds(31), Duration.ofMinutes(2))).isEmpty();
        assertThat(workAdapter.claimNext(NOW.plusSeconds(141), Duration.ofMinutes(2))).isPresent();
    }

    @Test
    void persistsIdentityLinkAndAdvancesAtomically() {
        ClaimedRegistration claim = workAdapter.claimNext(NOW, Duration.ofMinutes(2)).orElseThrow();

        assertThat(workAdapter.markIdentityLinked(claim,
                new ProvisionedIdentity("https://identity.example/realms/paycore", "subject-1"), NOW)).isTrue();

        assertThat(value("SELECT state FROM registration_operations")).isEqualTo("IDENTITY_LINKED");
        assertThat(value("SELECT subject FROM external_identities")).isEqualTo("subject-1");
    }

    @Test
    void activatesCustomerAndCompletesAtomically() {
        ClaimedRegistration first = workAdapter.claimNext(NOW, Duration.ofMinutes(2)).orElseThrow();
        workAdapter.markIdentityLinked(first,
                new ProvisionedIdentity("https://identity.example/realms/paycore", "subject-1"), NOW);
        ClaimedRegistration linked = workAdapter.claimNext(NOW, Duration.ofMinutes(2)).orElseThrow();

        assertThat(workAdapter.complete(linked, NOW.plusSeconds(1))).isTrue();

        assertThat(value("SELECT state FROM registration_operations")).isEqualTo("COMPLETED");
        assertThat(value("SELECT status FROM customers")).isEqualTo("ACTIVE");
    }

    @Test
    void staleClaimCannotAdvanceState() {
        ClaimedRegistration stale = workAdapter.claimNext(NOW, Duration.ofSeconds(30)).orElseThrow();
        workAdapter.claimNext(NOW.plusSeconds(31), Duration.ofMinutes(2)).orElseThrow();

        assertThat(workAdapter.markIdentityLinked(stale,
                new ProvisionedIdentity("https://identity.example/realms/paycore", "subject-1"), NOW.plusSeconds(32)))
                .isFalse();
        assertThat(jdbcClient.sql("SELECT count(*) FROM external_identities").query(Long.class).single())
                .isZero();
    }

    @Test
    void retryRecordsFailureTimeInsteadOfFutureScheduleTime() {
        ClaimedRegistration claim = workAdapter.claimNext(NOW, Duration.ofMinutes(2)).orElseThrow();

        workAdapter.scheduleRetry(claim, NOW.plusSeconds(1), NOW.plus(Duration.ofHours(1)), "KEYCLOAK_503");

        assertThat(jdbcClient.sql("SELECT updated_at FROM registration_operations")
                .query(java.time.OffsetDateTime.class).single().toInstant())
                .isEqualTo(NOW.plusSeconds(1));
        assertThat(jdbcClient.sql("SELECT next_attempt_at FROM registration_operations")
                .query(java.time.OffsetDateTime.class).single().toInstant())
                .isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @ParameterizedTest
    @EnumSource(value = RegistrationOperationState.class, names = {
            "COMPLETED", "DUPLICATE_SUPPRESSED", "RECONCILIATION_REQUIRED"
    })
    void terminalOperationIsNeverClaimed(RegistrationOperationState terminalState) {
        jdbcClient.sql("UPDATE registration_operations SET state=:state")
                .param("state", terminalState.name())
                .update();

        assertThat(workAdapter.claimNext(NOW, Duration.ofMinutes(2))).isEmpty();
    }

    @Test
    void pendingOperationCannotSkipIdentityLinkedState() {
        ClaimedRegistration pending = workAdapter.claimNext(NOW, Duration.ofMinutes(2)).orElseThrow();

        assertThat(workAdapter.complete(pending, NOW.plusSeconds(1))).isFalse();
        assertThat(value("SELECT state FROM registration_operations")).isEqualTo("PENDING_IDENTITY");
        assertThat(value("SELECT status FROM customers")).isEqualTo("PROVISIONING");
    }

    @Test
    void identityConflictRollsBackOperationStateAdvance() {
        ClaimedRegistration first = workAdapter.claimNext(NOW, Duration.ofMinutes(2)).orElseThrow();
        ProvisionedIdentity identity = new ProvisionedIdentity(
                "https://identity.example/realms/paycore", "subject-1");
        workAdapter.markIdentityLinked(first, identity, NOW);
        ClaimedRegistration linked = workAdapter.claimNext(NOW, Duration.ofMinutes(2)).orElseThrow();
        workAdapter.complete(linked, NOW.plusSeconds(1));

        register("key-2", "second@example.com", "22222222-2222-2222-2222-222222222222");
        ClaimedRegistration second = workAdapter.claimNext(NOW.plusSeconds(2), Duration.ofMinutes(2))
                .orElseThrow();

        assertThatThrownBy(() -> workAdapter.markIdentityLinked(second, identity, NOW.plusSeconds(2)))
                .isInstanceOf(RegistrationIntegrityException.class)
                .hasMessage("IDENTITY_LINK_CONFLICT");

        assertThat(jdbcClient.sql("""
                        SELECT state FROM registration_operations WHERE customer_id=:customerId
                        """).param("customerId", second.customerId().value()).query(String.class).single())
                .isEqualTo("PENDING_IDENTITY");
        assertThat(jdbcClient.sql("SELECT count(*) FROM external_identities").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void activationFailureRollsBackOperationCompletion() {
        ClaimedRegistration first = workAdapter.claimNext(NOW, Duration.ofMinutes(2)).orElseThrow();
        workAdapter.markIdentityLinked(first,
                new ProvisionedIdentity("https://identity.example/realms/paycore", "subject-1"), NOW);
        ClaimedRegistration linked = workAdapter.claimNext(NOW, Duration.ofMinutes(2)).orElseThrow();
        jdbcClient.sql("UPDATE customers SET status='PROVISIONING_FAILED'").update();

        assertThatThrownBy(() -> workAdapter.complete(linked, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Customer could not be activated");

        assertThat(value("SELECT state FROM registration_operations")).isEqualTo("IDENTITY_LINKED");
        assertThat(value("SELECT status FROM customers")).isEqualTo("PROVISIONING_FAILED");
    }

    private void register(String key, String email, String customerId) {
        new RegisterCustomerService(
                acceptanceAdapter,
                new IdempotencyDigester(1, Map.of(
                        1, "registration-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8))),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> new CustomerId(UUID.fromString(customerId)))
                .register(new RegisterCustomerCommand(key, email, CustomerType.INDIVIDUAL));
    }

    private String value(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }

    private Optional<ClaimedRegistration> claimAfter(CountDownLatch start) {
        try {
            start.await();
            return workAdapter.claimNext(NOW, Duration.ofMinutes(2));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
