package dev.martin.paycore.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.identity.domain.model.ExternalIdentity;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    WalletProvisioningCustomerActivationAdapter activationAdapter;

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

    @Test
    void activatesPendingCustomerWithCompareAndSet() {
        CustomerId pending = customer(5, CustomerStatus.PENDING_VERIFICATION);
        identity("https://issuer.example/realms/paycore", "pending-subject", pending);

        assertThat(adapter.findByExternalIdentity(new ExternalIdentity(
                "https://issuer.example/realms/paycore", "pending-subject")))
                .hasValueSatisfying(access -> assertThat(access.status()).isEqualTo(CustomerStatus.PENDING_VERIFICATION));
        assertThat(activationAdapter.activatePending(pending, OffsetDateTime.parse("2026-08-14T12:00:00Z").toInstant()))
                .hasValueSatisfying(access -> assertThat(access.status()).isEqualTo(CustomerStatus.ACTIVE));
        assertThat(adapter.findByCustomerId(pending).orElseThrow().status()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(version(pending)).isEqualTo(1L);
        assertThat(walletCount(pending)).isEqualTo(1L);
        assertThat(walletAccountCount(pending)).isEqualTo(2L);
    }

    @Test
    void repeatedActivationReturnsExistingActiveResult() {
        CustomerId pending = customer(6, CustomerStatus.PENDING_VERIFICATION);
        var activationAt = OffsetDateTime.parse("2026-08-14T12:00:00Z").toInstant();

        assertThat(activationAdapter.activatePending(pending, activationAt)).isPresent();
        assertThat(activationAdapter.activatePending(pending, activationAt.plusSeconds(1)))
                .hasValueSatisfying(access -> assertThat(access.status()).isEqualTo(CustomerStatus.ACTIVE));
        assertThat(version(pending)).isEqualTo(1L);
        assertThat(walletCount(pending)).isEqualTo(1L);
        assertThat(walletAccountCount(pending)).isEqualTo(2L);
    }

    @Test
    void activeCustomerWithoutACompleteWalletCannotBeConfirmedForAuthentication() {
        CustomerId active = customer(10, CustomerStatus.ACTIVE);

        assertThat(activationAdapter.confirmActive(active)).isEmpty();
    }

    @Test
    void activeCustomerWithACompleteWalletCanBeConfirmedForAuthentication() {
        CustomerId active = customer(11, CustomerStatus.PENDING_VERIFICATION);
        activationAdapter.activatePending(active, OffsetDateTime.parse("2026-08-14T12:00:00Z").toInstant());

        assertThat(activationAdapter.confirmActive(active))
                .hasValueSatisfying(access -> assertThat(access.status()).isEqualTo(CustomerStatus.ACTIVE));
    }

    @Test
    void concurrentActivationsConvergeOnOneVersionedTransition() throws Exception {
        CustomerId pending = customer(7, CustomerStatus.PENDING_VERIFICATION);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> activateAfter(start, pending));
            Future<?> second = executor.submit(() -> activateAfter(start, pending));
            start.countDown();

            assertThat(first.get()).isNotNull();
            assertThat(second.get()).isNotNull();
        }

        assertThat(adapter.findByCustomerId(pending).orElseThrow().status()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(version(pending)).isEqualTo(1L);
        assertThat(walletCount(pending)).isEqualTo(1L);
        assertThat(walletAccountCount(pending)).isEqualTo(2L);
    }

    @Test
    void rollsBackCustomerActivationWalletAndAccountsWhenProvisioningFails() {
        CustomerId pending = customer(9, CustomerStatus.PENDING_VERIFICATION);
        installWalletClaimFailureTrigger(pending);
        try {
            assertThat(activationAdapter.activatePending(
                    pending, OffsetDateTime.parse("2026-08-14T12:00:00Z").toInstant())).isEmpty();
        } finally {
            jdbcClient.sql("DROP TRIGGER test_fail_wallet_activation ON wallets").update();
            jdbcClient.sql("DROP FUNCTION test_fail_wallet_activation()").update();
        }

        assertThat(adapter.findByCustomerId(pending).orElseThrow().status())
                .isEqualTo(CustomerStatus.PENDING_VERIFICATION);
        assertThat(version(pending)).isZero();
        assertThat(walletCount(pending)).isZero();
        assertThat(walletAccountCount(pending)).isZero();
    }

    @Test
    void verifiedActivationRacingWithBlockingCannotLeaveBlockedCustomerActive() throws Exception {
        CustomerId pending = customer(8, CustomerStatus.PENDING_VERIFICATION);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> activation = executor.submit(() -> activateAfter(start, pending));
            Future<?> blocking = executor.submit(() -> blockAfter(start, pending));
            start.countDown();

            Object activationResult = activation.get();
            assertThat(blocking.get()).isEqualTo(1);

            long finalVersion = version(pending);
            assertThat(finalVersion).isBetween(1L, 2L);
            if (finalVersion == 1L) {
                assertThat(activationResult).isEqualTo(java.util.Optional.empty());
            } else {
                assertThat(activationResult).isInstanceOf(java.util.Optional.class)
                        .extracting(result -> ((java.util.Optional<?>) result).orElseThrow())
                        .extracting("status")
                        .isEqualTo(CustomerStatus.ACTIVE);
            }
        }

        assertThat(adapter.findByCustomerId(pending).orElseThrow().status()).isEqualTo(CustomerStatus.BLOCKED);
    }

    private Object activateAfter(CountDownLatch start, CustomerId customerId) {
        try {
            start.await();
            return activationAdapter.activatePending(
                    customerId, OffsetDateTime.parse("2026-08-14T12:00:00Z").toInstant());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private int blockAfter(CountDownLatch start, CustomerId customerId) {
        try {
            start.await();
            return jdbcClient.sql("""
                            UPDATE customers
                               SET status='BLOCKED', updated_at=:now, version=version+1
                             WHERE id=:id AND status IN ('PENDING_VERIFICATION', 'ACTIVE')
                            """)
                    .param("now", OffsetDateTime.parse("2026-08-14T12:00:01Z"))
                    .param("id", customerId.value())
                    .update();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private long version(CustomerId customerId) {
        return jdbcClient.sql("SELECT version FROM customers WHERE id=:id")
                .param("id", customerId.value()).query(Long.class).single();
    }

    private long walletCount(CustomerId customerId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM wallets WHERE customer_id=:id")
                .param("id", customerId.value()).query(Long.class).single();
    }

    private long walletAccountCount(CustomerId customerId) {
        return jdbcClient.sql("""
                SELECT COUNT(*)
                  FROM ledger_accounts a
                  JOIN wallets w ON a.id IN (w.available_account_id, w.reserved_account_id)
                 WHERE w.customer_id=:id
                """).param("id", customerId.value()).query(Long.class).single();
    }

    private void installWalletClaimFailureTrigger(CustomerId customerId) {
        jdbcClient.sql("CREATE FUNCTION test_fail_wallet_activation() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.customer_id = '" + customerId.value() + "'::uuid THEN "
                + "RAISE EXCEPTION 'forced wallet claim failure'; END IF; "
                + "RETURN NEW; END; $$").update();
        jdbcClient.sql("""
                CREATE TRIGGER test_fail_wallet_activation
                BEFORE INSERT ON wallets
                FOR EACH ROW EXECUTE FUNCTION test_fail_wallet_activation()
                """).update();
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
