package dev.martin.paycore.wallet.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.martin.paycore.wallet.application.provisioning.ProvisionWalletCommand;
import dev.martin.paycore.wallet.application.provisioning.ProvisionWalletService;
import dev.martin.paycore.wallet.application.query.QueryOwnWalletService;
import dev.martin.paycore.wallet.application.query.WalletView;
import dev.martin.paycore.wallet.application.query.WalletAccess;
import dev.martin.paycore.wallet.application.lifecycle.WalletLifecycleService;
import dev.martin.paycore.wallet.application.port.out.WalletStore;
import dev.martin.paycore.wallet.domain.model.Wallet;
import dev.martin.paycore.wallet.domain.model.WalletCurrency;
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.identity.infrastructure.persistence.WalletProvisioningCustomerActivationAdapter;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionOperations;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = {
        "paycore.authentication.enabled=false",
        "paycore.registration.enabled=false",
        "paycore.registration.worker-enabled=false"
})
@Import(WalletPersistenceIntegrationTest.IncompleteWalletConfirmationConfiguration.class)
class WalletPersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withEnv("TZ", "UTC").withEnv("PGTZ", "UTC");

    @Autowired
    private ProvisionWalletService provisioning;

    @Autowired
    private QueryOwnWalletService queries;

    @Autowired
    private WalletLifecycleService lifecycle;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private WalletStore walletStore;

    @Autowired
    private TransactionOperations transactions;

    @Autowired
    private WalletProvisioningCustomerActivationAdapter activation;

    @Test
    void provisionsTwoAccountsAndReturnsZeroOwnBalances() {
        UUID customerId = customer();

        Wallet wallet = provisioning.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));
        WalletView view = queries.query(customerId);

        assertThat(view.walletId()).isEqualTo(wallet.id());
        assertThat(view.availableBalance()).isZero();
        assertThat(view.reservedBalance()).isZero();
        assertThat(view.totalBalance()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM wallets WHERE customer_id=:id")
                .param("id", customerId).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger_accounts WHERE name LIKE :prefix")
                .param("prefix", "wallet:" + customerId + ":%").query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM ledger_account_balances b
                JOIN wallets w ON b.account_id IN (w.available_account_id, w.reserved_account_id)
                WHERE w.customer_id=:id
                """).param("id", customerId).query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void repeatedProvisioningDoesNotCreateDuplicateOrOrphanAccounts() {
        UUID customerId = customer();

        Wallet first = provisioning.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));
        Wallet second = provisioning.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM wallets WHERE customer_id=:id")
                .param("id", customerId).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger_accounts WHERE name LIKE :prefix")
                .param("prefix", "wallet:" + customerId + ":%").query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void concurrentProvisioningConvergesOnOneCompleteWallet() throws Exception {
        UUID customerId = customer();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Wallet>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> provisioning.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD)));
            }
            List<Future<Wallet>> futures = executor.invokeAll(tasks);
            List<Wallet> wallets = new ArrayList<>();
            for (Future<Wallet> future : futures) {
                wallets.add(future.get(10, TimeUnit.SECONDS));
            }

            assertThat(wallets).extracting(Wallet::id).containsOnly(wallets.get(0).id());
            assertThat(jdbc.sql("SELECT COUNT(*) FROM wallets WHERE customer_id=:id")
                    .param("id", customerId).query(Long.class).single()).isEqualTo(1L);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger_accounts WHERE name LIKE :prefix")
                    .param("prefix", "wallet:" + customerId + ":%").query(Long.class).single()).isEqualTo(2L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rollsBackAccountsAndProjectionsWhenWalletClaimFails() {
        UUID customerId = customer();
        installClaimFailureTrigger(customerId);
        try {
            assertThatThrownBy(() -> provisioning.provision(
                    new ProvisionWalletCommand(customerId, WalletCurrency.USD)))
                    .hasMessageContaining("forced wallet claim failure");
        } finally {
            jdbc.sql("DROP TRIGGER test_fail_wallet_claim ON wallets").update();
            jdbc.sql("DROP FUNCTION test_fail_wallet_claim()").update();
        }

        assertThat(jdbc.sql("SELECT COUNT(*) FROM wallets WHERE customer_id=:id")
                .param("id", customerId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger_accounts WHERE name LIKE :prefix")
                .param("prefix", "wallet:" + customerId + ":%").query(Long.class).single()).isZero();
    }

    @Test
    void cannotQueryAnotherCustomersWallet() {
        UUID owner = customer();
        UUID caller = customer();
        provisioning.provision(new ProvisionWalletCommand(owner, WalletCurrency.USD));

        assertThatThrownBy(() -> queries.query(caller))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Wallet not found");
    }

    @Test
    void roundTripsPreBlockStatusAndUnfundedUnblockThroughJpa() {
        UUID customerId = customer();
        Wallet provisioned = provisioning.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));

        Wallet blocked = lifecycle.block(customerId);
        Wallet reloaded = walletStore.findByCustomerId(customerId).orElseThrow();

        assertThat(reloaded.status()).isEqualTo(dev.martin.paycore.wallet.domain.model.WalletStatus.BLOCKED);
        assertThat(reloaded.preBlockStatus()).isEqualTo(dev.martin.paycore.wallet.domain.model.WalletStatus.UNFUNDED);

        Wallet restored = lifecycle.unblock(customerId);

        assertThat(walletStore.findByCustomerId(customerId).orElseThrow().status())
                .isEqualTo(dev.martin.paycore.wallet.domain.model.WalletStatus.UNFUNDED);
        assertThat(walletStore.findByCustomerId(customerId).orElseThrow().preBlockStatus()).isNull();
    }

    @Test
    void rejectsAnActiveWalletWithoutAnActivationInstantInPostgres() {
        UUID customerId = customer();
        provisioning.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE wallets
                   SET status = 'ACTIVE', activated_at = NULL
                 WHERE customer_id = :id
                """).param("id", customerId).update())
                .hasMessageContaining("wallet_active_requires_activation");
    }

    @Test
    void rejectsWalletReadWhenEitherProjectionRowIsMissing() {
        UUID customerId = customer();
        Wallet wallet = provisioning.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));
        jdbc.sql("DELETE FROM ledger_account_balances WHERE account_id=:id")
                .param("id", wallet.reservedAccountId()).update();

        assertThatThrownBy(() -> queries.query(customerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Wallet balance is inconsistent");
    }

    @Test
    void rollsBackRealWalletProvisioningWhenCompletenessConfirmationIsEmpty() {
        UUID customerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-17T12:00:00Z");
        jdbc.sql("""
                INSERT INTO customers (id, email, customer_type, status, created_at, updated_at, version)
                VALUES (:id, :email, 'INDIVIDUAL', 'PENDING_VERIFICATION', :at, :at, 0)
                """)
                .param("id", customerId)
                .param("email", customerId + "@example.com")
                .param("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();

        assertThat(activation.activatePending(
                new dev.martin.paycore.identity.domain.model.CustomerId(customerId), now)).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM wallets WHERE customer_id=:id")
                .param("id", customerId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ledger_accounts WHERE name LIKE :prefix")
                .param("prefix", "wallet:" + customerId + ":%").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM customers WHERE id=:id")
                .param("id", customerId).query(String.class).single())
                .isEqualTo(CustomerStatus.PENDING_VERIFICATION.name());
    }

    @Test
    void walletLinkValidationRejectsInconsistentOperationalProjectionsButAllowsBlocking() {
        UUID customerId = customer();
        Wallet wallet = provisioning.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));
        jdbc.sql("UPDATE ledger_account_balances SET consistency_status = 'INCONSISTENT' WHERE account_id=:id")
                .param("id", wallet.availableAccountId()).update();

        assertThatThrownBy(() -> jdbc.sql("UPDATE wallets SET updated_at = updated_at WHERE customer_id=:id")
                .param("id", customerId).update())
                .hasMessageContaining("wallet accounts must match lifecycle status");

        lifecycle.block(customerId);
        assertThat(walletStatus(customerId)).isEqualTo("BLOCKED");
    }

    @Test
    void lifecycleBlocksBothLedgerAccounts() {
        UUID customerId = customer();
        Wallet provisioned = provisioning.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));

        lifecycle.block(customerId);
        assertThat(walletStatus(customerId)).isEqualTo("BLOCKED");
        assertThat(accountStatus(provisioned.availableAccountId())).isEqualTo("BLOCKED");
        assertThat(accountStatus(provisioned.reservedAccountId())).isEqualTo("BLOCKED");
    }

    @Test
    void lifecycleClosesAnEmptyWalletAndBothLedgerAccounts() {
        UUID customerId = customer();
        Wallet provisioned = provisioning.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));

        lifecycle.close(customerId);
        assertThat(walletStatus(customerId)).isEqualTo("CLOSED");
        assertThat(accountStatus(provisioned.availableAccountId())).isEqualTo("CLOSED");
        assertThat(accountStatus(provisioned.reservedAccountId())).isEqualTo("CLOSED");
    }

    @Test
    void lifecycleClosesAnEmptyBlockedWalletAndBothLedgerAccounts() {
        UUID customerId = customer();
        Wallet provisioned = provisioning.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));

        lifecycle.block(customerId);
        lifecycle.close(customerId);

        assertThat(walletStatus(customerId)).isEqualTo("CLOSED");
        assertThat(accountStatus(provisioned.availableAccountId())).isEqualTo("CLOSED");
        assertThat(accountStatus(provisioned.reservedAccountId())).isEqualTo("CLOSED");
    }

    @Test
    void lifecycleUnblocksBothLedgerAccountsAndRestoresWalletStatus() {
        UUID customerId = customer();
        Wallet provisioned = provisioning.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));

        lifecycle.block(customerId);
        lifecycle.unblock(customerId);

        assertThat(walletStatus(customerId)).isEqualTo("UNFUNDED");
        assertThat(accountStatus(provisioned.availableAccountId())).isEqualTo("OPEN");
        assertThat(accountStatus(provisioned.reservedAccountId())).isEqualTo("OPEN");
    }

    @Test
    void directBlockingOfAnUnfundedLinkedAccountCannotCommit() {
        UUID customerId = customer();
        Wallet wallet = provisioning.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));

        assertThatThrownBy(() -> transactions.execute(status -> {
            jdbc.sql("UPDATE ledger_accounts SET status='BLOCKED' WHERE id=:id")
                    .param("id", wallet.availableAccountId()).update();
            return null;
        })).hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .hasStackTraceContaining("ERROR: wallet account status must match wallet lifecycle");
        assertThat(accountStatus(wallet.availableAccountId())).isEqualTo("OPEN");
        assertThat(walletStatus(customerId)).isEqualTo("UNFUNDED");
    }

    @Test
    void directClosingOfAnActiveLinkedAccountCannotCommit() {
        UUID customerId = customer();
        Wallet wallet = provisioning.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));
        activateWallet(customerId);

        assertThatThrownBy(() -> transactions.execute(status -> {
            jdbc.sql("UPDATE ledger_accounts SET status='CLOSED' WHERE id=:id")
                    .param("id", wallet.reservedAccountId()).update();
            return null;
        })).hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .hasStackTraceContaining("ERROR: wallet account status must match wallet lifecycle");
        assertThat(accountStatus(wallet.reservedAccountId())).isEqualTo("OPEN");
        assertThat(walletStatus(customerId)).isEqualTo("ACTIVE");
    }

    @Test
    void directBlockingOfAnUnfundedWalletCannotCommitWithoutBlockingBothAccounts() {
        UUID customerId = customer();
        Wallet wallet = provisioning.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));

        assertThatThrownBy(() -> transactions.execute(status -> {
            jdbc.sql("""
                    UPDATE wallets
                       SET status='BLOCKED', pre_block_status='UNFUNDED'
                     WHERE customer_id=:id
                    """).param("id", customerId).update();
            return null;
        })).hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .hasStackTraceContaining("ERROR: wallet account status must match wallet lifecycle");
        assertThat(walletStatus(customerId)).isEqualTo("UNFUNDED");
        assertThat(accountStatus(wallet.availableAccountId())).isEqualTo("OPEN");
        assertThat(accountStatus(wallet.reservedAccountId())).isEqualTo("OPEN");
    }

    @Test
    void closeWaitsForThePostingBalanceLockBeforeValidatingZeroBalances() throws Exception {
        UUID customerId = customer();
        Wallet wallet = provisioning.provision(new ProvisionWalletCommand(customerId, WalletCurrency.USD));
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> postingLock = executor.submit(() -> transactions.execute(status -> {
                jdbc.sql("""
                        SELECT account_id FROM ledger_account_balances
                         WHERE account_id IN (:available, :reserved)
                         ORDER BY account_id
                         FOR UPDATE
                        """)
                        .param("available", wallet.availableAccountId())
                        .param("reserved", wallet.reservedAccountId())
                        .query(UUID.class).list();
                lockHeld.countDown();
                await(release);
                return null;
            }));
            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Wallet> closing = executor.submit(() -> lifecycle.close(customerId));
            assertThatThrownBy(() -> closing.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            release.countDown();
            closing.get(10, TimeUnit.SECONDS);
            postingLock.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private String accountStatus(UUID accountId) {
        return jdbc.sql("SELECT status FROM ledger_accounts WHERE id=:id")
                .param("id", accountId).query(String.class).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IncompleteWalletConfirmationConfiguration {

        @Bean
        @Primary
        WalletAccess incompleteWalletAccess() {
            return new WalletAccess() {
                @Override
                public WalletView query(UUID customerId) {
                    throw new IllegalStateException("wallet confirmation intentionally incomplete");
                }

                @Override
                public java.util.Optional<WalletView> confirmCompleteUsdWallet(UUID customerId) {
                    return java.util.Optional.empty();
                }
            };
        }
    }

    private String walletStatus(UUID customerId) {
        return jdbc.sql("SELECT status FROM wallets WHERE customer_id=:id")
                .param("id", customerId).query(String.class).single();
    }

    private void activateWallet(UUID customerId) {
        jdbc.sql("""
                UPDATE wallets
                   SET status='ACTIVE', activated_at=:at, updated_at=:at
                 WHERE customer_id=:id
                """)
                .param("id", customerId)
                .param("at", OffsetDateTime.ofInstant(Instant.parse("2026-08-17T12:00:01Z"), ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private UUID customer() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-17T12:00:00Z");
        jdbc.sql("""
                INSERT INTO customers (id, email, customer_type, status, created_at, updated_at, version)
                VALUES (:id, :email, 'INDIVIDUAL', 'ACTIVE', :at, :at, 0)
                """)
                .param("id", id)
                .param("email", id + "@example.com")
                .param("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return id;
    }

    private void installClaimFailureTrigger(UUID customerId) {
        jdbc.sql("CREATE FUNCTION test_fail_wallet_claim() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.customer_id = '" + customerId + "'::uuid THEN "
                + "RAISE EXCEPTION 'forced wallet claim failure'; END IF; "
                + "RETURN NEW; END; $$").update();
        jdbc.sql("""
                CREATE TRIGGER test_fail_wallet_claim
                BEFORE INSERT ON wallets
                FOR EACH ROW EXECUTE FUNCTION test_fail_wallet_claim()
                """).update();
    }
}
