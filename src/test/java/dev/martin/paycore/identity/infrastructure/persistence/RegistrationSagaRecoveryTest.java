package dev.martin.paycore.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.martin.paycore.identity.application.port.out.ExternalIdentityProvisioner;
import dev.martin.paycore.identity.application.registration.ClaimedRegistration;
import dev.martin.paycore.identity.application.registration.IdempotencyDigester;
import dev.martin.paycore.identity.application.registration.ProcessRegistrationService;
import dev.martin.paycore.identity.application.registration.ProvisionedIdentity;
import dev.martin.paycore.identity.application.registration.RegisterCustomerCommand;
import dev.martin.paycore.identity.application.registration.RegisterCustomerService;
import dev.martin.paycore.identity.application.registration.RegistrationBackoff;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerType;
import dev.martin.paycore.identity.domain.model.Email;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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
class RegistrationSagaRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

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

    private final AtomicInteger customerIds = new AtomicInteger();

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("TRUNCATE TABLE wallets, registration_operations, external_identities, customers, registration_rate_limits")
                .update();
    }

    @Test
    void simultaneousSameKeyRequestsAndCompetingWorkersCreateUserOnce() throws Exception {
        CountDownLatch registrationStart = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> registerAfter(registrationStart));
            Future<?> second = executor.submit(() -> registerAfter(registrationStart));
            registrationStart.countDown();
            first.get();
            second.get();
        }

        RecoveringProvisioner provisioner = new RecoveringProvisioner();
        CountDownLatch workerStart = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> processAfter(workerStart, provisioner));
            Future<Boolean> second = executor.submit(() -> processAfter(workerStart, provisioner));
            workerStart.countDown();
            first.get();
            second.get();
        }

        assertThat(count("customers")).isEqualTo(1);
        assertThat(count("registration_operations")).isEqualTo(1);
        assertThat(provisioner.createAttempts.get()).isEqualTo(1);
        assertThat(provisioner.recoveryChecks.get()).isZero();
    }

    @ParameterizedTest
    @EnumSource(CrashPoint.class)
    void resumesAfterCrashAtEveryDurableBoundary(CrashPoint crashPoint) {
        RecoveringProvisioner provisioner = new RecoveringProvisioner();
        register("crash-key", "person@example.com");
        Instant recoveryAt = arrangeCrash(crashPoint, provisioner);

        ProcessRegistrationService restarted = processorAt(recoveryAt, provisioner);
        while (restarted.processNext()) {
            // Drain durable work exactly as a later worker poll would.
        }

        assertThat(value("SELECT state FROM registration_operations")).isEqualTo("COMPLETED");
        assertThat(value("SELECT status FROM customers")).isEqualTo("PENDING_VERIFICATION");
        assertThat(count("external_identities")).isEqualTo(1);
        assertThat(provisioner.createAttempts.get()).isEqualTo(1);
        if (crashPoint == CrashPoint.USER_CREATE) {
            assertThat(provisioner.recoveryChecks.get()).isEqualTo(1);
        }
        if (crashPoint == CrashPoint.EMAIL_REQUEST) {
            assertThat(provisioner.emailRequests.get()).isEqualTo(2);
        }
    }

    private Instant arrangeCrash(CrashPoint crashPoint, RecoveringProvisioner provisioner) {
        return switch (crashPoint) {
            case ACCEPTANCE -> NOW;
            case CLAIM -> {
                workAdapter.claimNext(NOW, LEASE_DURATION).orElseThrow();
                yield NOW.plusSeconds(31);
            }
            case USER_CREATE -> {
                workAdapter.claimNext(NOW, LEASE_DURATION).orElseThrow();
                provisioner.createWithoutResponse();
                yield NOW.plusSeconds(31);
            }
            case IDENTITY_LINK_COMMIT -> {
                ClaimedRegistration claim = workAdapter.claimNext(NOW, LEASE_DURATION).orElseThrow();
                ProvisionedIdentity identity = provisioner.provision(claim.customerId(), claim.email());
                workAdapter.markIdentityLinked(claim, identity, NOW);
                yield NOW;
            }
            case EMAIL_REQUEST -> {
                ClaimedRegistration claim = workAdapter.claimNext(NOW, LEASE_DURATION).orElseThrow();
                ProvisionedIdentity identity = provisioner.provision(claim.customerId(), claim.email());
                workAdapter.markIdentityLinked(claim, identity, NOW);
                ClaimedRegistration linked = workAdapter.claimNext(NOW, LEASE_DURATION).orElseThrow();
                provisioner.sendRequiredActions(linked.externalSubject());
                yield NOW.plusSeconds(31);
            }
            case ACTIVATION_COMMIT -> {
                ProcessRegistrationService processor = processorAt(NOW, provisioner);
                assertThat(processor.processNext()).isTrue();
                assertThat(processor.processNext()).isTrue();
                yield NOW;
            }
        };
    }

    private void registerAfter(CountDownLatch start) {
        try {
            start.await();
            register("shared-key", "person@example.com");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private boolean processAfter(CountDownLatch start, RecoveringProvisioner provisioner) {
        try {
            start.await();
            return processorAt(NOW, provisioner).processNext();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void register(String key, String email) {
        new RegisterCustomerService(
                acceptanceAdapter,
                new IdempotencyDigester(1, Map.of(
                        1, "registration-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8))),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> new CustomerId(new UUID(0, customerIds.incrementAndGet())))
                .register(new RegisterCustomerCommand(key, email, CustomerType.INDIVIDUAL));
    }

    private ProcessRegistrationService processorAt(Instant now, RecoveringProvisioner provisioner) {
        return new ProcessRegistrationService(
                workAdapter, provisioner,
                new RegistrationBackoff(Duration.ofSeconds(5), Duration.ofHours(1), () -> 0.5),
                (operationId, attemptCount, failureCode) -> { }, Integer.MAX_VALUE,
                Clock.fixed(now, ZoneOffset.UTC), LEASE_DURATION);
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private String value(String sql) {
        return jdbcClient.sql(sql).query(String.class).single();
    }

    private enum CrashPoint {
        ACCEPTANCE,
        CLAIM,
        USER_CREATE,
        IDENTITY_LINK_COMMIT,
        EMAIL_REQUEST,
        ACTIVATION_COMMIT
    }

    private static final class RecoveringProvisioner implements ExternalIdentityProvisioner {
        private static final ProvisionedIdentity IDENTITY = new ProvisionedIdentity(
                "https://identity.example/realms/paycore", "subject-1");
        private final AtomicInteger createAttempts = new AtomicInteger();
        private final AtomicInteger recoveryChecks = new AtomicInteger();
        private final AtomicInteger emailRequests = new AtomicInteger();
        private volatile boolean userExists;

        @Override
        public synchronized ProvisionedIdentity provision(CustomerId customerId, Email email) {
            if (userExists) {
                recoveryChecks.incrementAndGet();
                return IDENTITY;
            }
            createAttempts.incrementAndGet();
            userExists = true;
            return IDENTITY;
        }

        @Override
        public void sendRequiredActions(String subject) {
            emailRequests.incrementAndGet();
        }

        private synchronized void createWithoutResponse() {
            createAttempts.incrementAndGet();
            userExists = true;
        }
    }
}
