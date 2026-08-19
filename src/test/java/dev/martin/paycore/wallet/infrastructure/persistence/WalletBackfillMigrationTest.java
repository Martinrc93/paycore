package dev.martin.paycore.wallet.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.martin.paycore.ledger.application.balance.RebuildLedgerBalancesService;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.target=3",
        "spring.task.scheduling.enabled=false",
        "paycore.authentication.enabled=false",
        "paycore.registration.enabled=false",
        "paycore.registration.worker-enabled=false"
})
class WalletBackfillMigrationTest {

    private static final Instant BEFORE_MIGRATION = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID HISTORICAL_ACCOUNT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID HISTORICAL_COUNTERPARTY = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID MIXED_COUNTERPARTY = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID EXISTING_CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTROLLED_INTERVAL_CUSTOMER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SUSPENDED_CUSTOMER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withEnv("TZ", "UTC").withEnv("PGTZ", "UTC");

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private RebuildLedgerBalancesService rebuildLedgerBalances;

    @BeforeEach
    void resetToV3() {
        flyway(3).clean();
        flyway(3).migrate();
    }

    @Test
    void infersCurrencyRebuildsProjectionAndRejectsMismatchedLines() {
        insertAccountV3(HISTORICAL_ACCOUNT, "historical-account");
        insertAccountV3(HISTORICAL_COUNTERPARTY, "historical-counterparty");
        insertHistory("historical-key", HISTORICAL_ACCOUNT, HISTORICAL_COUNTERPARTY, "10.00", "ARS");

        migrateToV5();

        assertThat(accountCurrency(HISTORICAL_ACCOUNT)).isEqualTo("ARS");
        assertThat(accountPolicy(HISTORICAL_ACCOUNT)).isEqualTo("ALLOW_NEGATIVE");
        assertThat(projection(HISTORICAL_ACCOUNT)).containsExactly(10.00, 0.00, "CONSISTENT");

        jdbcClient.sql("UPDATE ledger_account_balances SET cumulative_debits = 999.00 WHERE account_id = :id")
                .param("id", HISTORICAL_ACCOUNT).update();
        rebuildLedgerBalances.rebuild(HISTORICAL_ACCOUNT);

        assertThat(projection(HISTORICAL_ACCOUNT)).containsExactly(10.00, 0.00, "CONSISTENT");

        UUID usdAccount = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        insertAccountV5(usdAccount, "usd-account", "USD", "ALLOW_NEGATIVE");
        insertProjection(usdAccount);
        assertThatThrownBy(() -> insertMismatchedLine(usdAccount))
                .hasMessageContaining("ledger line currency must match account currency");
    }

    @Test
    void v5DoesNotInstallADatabaseRebuildFunction() {
        migrateToV5();

        assertThat(jdbcClient.sql("""
                SELECT COUNT(*)
                  FROM pg_proc p
                  JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'public'
                   AND p.proname = 'rebuild_ledger_account_balance'
                """).query(Long.class).single()).isZero();
    }

    @Test
    void defaultsAccountWithoutHistoryToUsdAndCreatesAConsistentProjection() {
        insertAccountV3(HISTORICAL_ACCOUNT, "empty-account");

        migrateToV5();

        assertThat(accountCurrency(HISTORICAL_ACCOUNT)).isEqualTo("USD");
        assertThat(accountPolicy(HISTORICAL_ACCOUNT)).isEqualTo("ALLOW_NEGATIVE");
        assertThat(projection(HISTORICAL_ACCOUNT)).containsExactly(0.00, 0.00, "CONSISTENT");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM wallets").query(Long.class).single()).isZero();
    }

    @Test
    void mixedHistoricalCurrenciesFailAtomically() {
        insertAccountV3(HISTORICAL_ACCOUNT, "mixed-account");
        insertAccountV3(HISTORICAL_COUNTERPARTY, "ars-counterparty");
        insertAccountV3(MIXED_COUNTERPARTY, "usd-counterparty");
        insertHistory("mixed-ars-key", HISTORICAL_ACCOUNT, HISTORICAL_COUNTERPARTY, "10.00", "ARS");
        insertHistory("mixed-usd-key", HISTORICAL_ACCOUNT, MIXED_COUNTERPARTY, "10.00", "USD");

        assertThatThrownBy(this::migrateToV5)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("multiple historical currencies");

        assertThat(columnExists("ledger_accounts", "currency")).isFalse();
        assertThat(tableExists("ledger_account_balances")).isFalse();
        assertThat(tableExists("wallets")).isFalse();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM ledger_transaction_lines")
                .query(Long.class).single()).isEqualTo(4L);
    }

    @Test
    void backfillFailureAfterV5StartsRollsBackSchemaAndData() {
        migrateTo(4);
        insertCustomer(CONTROLLED_INTERVAL_CUSTOMER, "ACTIVE");
        installBackfillFailureTrigger();

        assertThatThrownBy(this::migrateToV5)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("forced wallet backfill failure");

        assertThat(columnExists("ledger_accounts", "currency")).isFalse();
        assertThat(tableExists("ledger_account_balances")).isFalse();
        assertThat(tableExists("wallets")).isFalse();
        assertThat(customerStatus(CONTROLLED_INTERVAL_CUSTOMER)).isEqualTo("ACTIVE");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM ledger_accounts")
                .query(Long.class).single()).isZero();
    }

    @Test
    void backfillsOnlyControlledIntervalActiveCustomersAndIsIdempotent() {
        insertCustomer(EXISTING_CUSTOMER, "ACTIVE");
        insertCustomer(SUSPENDED_CUSTOMER, "SUSPENDED");
        migrateTo(4);

        insertCustomer(CONTROLLED_INTERVAL_CUSTOMER, "ACTIVE");
        migrateToV5();

        assertThat(customerStatus(EXISTING_CUSTOMER)).isEqualTo("PENDING_VERIFICATION");
        assertThat(walletCount(EXISTING_CUSTOMER)).isZero();
        assertThat(customerStatus(SUSPENDED_CUSTOMER)).isEqualTo("SUSPENDED");
        assertThat(walletCount(CONTROLLED_INTERVAL_CUSTOMER)).isEqualTo(1L);
        assertThat(walletAccountCount(CONTROLLED_INTERVAL_CUSTOMER)).isEqualTo(2L);
        assertThat(walletStatus(CONTROLLED_INTERVAL_CUSTOMER)).isEqualTo("UNFUNDED");
        assertThat(walletCurrency(CONTROLLED_INTERVAL_CUSTOMER)).isEqualTo("USD");
        assertThat(walletAccountPolicies(CONTROLLED_INTERVAL_CUSTOMER)).containsExactly("NON_NEGATIVE", "NON_NEGATIVE");
        assertThat(walletProjectionCount(CONTROLLED_INTERVAL_CUSTOMER)).isEqualTo(2L);

        migrateToV5();

        assertThat(walletCount(CONTROLLED_INTERVAL_CUSTOMER)).isEqualTo(1L);
        assertThat(walletAccountCount(CONTROLLED_INTERVAL_CUSTOMER)).isEqualTo(2L);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM ledger_accounts WHERE name LIKE :prefix")
                .param("prefix", "wallet:" + CONTROLLED_INTERVAL_CUSTOMER + ":%").query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void walletConstraintsRequireDistinctOwnedUsdNonNegativeLiabilityAccounts() {
        migrateTo(4);
        insertCustomer(CONTROLLED_INTERVAL_CUSTOMER, "ACTIVE");
        migrateToV5();

        UUID walletId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        insertAccountV5(accountId, "manual-account", "USD", "NON_NEGATIVE");
        insertProjection(accountId);

        assertThatThrownBy(() -> jdbcClient.sql("""
                INSERT INTO wallets
                    (id, customer_id, currency, available_account_id, reserved_account_id,
                     status, created_at, updated_at, version)
                VALUES (:id, :customer, 'USD', :account, :account, 'UNFUNDED', :at, :at, 0)
                """)
                .param("id", walletId)
                .param("customer", CONTROLLED_INTERVAL_CUSTOMER)
                .param("account", accountId)
                .param("at", atUtc(BEFORE_MIGRATION), Types.TIMESTAMP_WITH_TIMEZONE)
                .update())
                .hasMessageContaining("wallet account references must be distinct");

        UUID availableAccountId = walletAvailableAccount(CONTROLLED_INTERVAL_CUSTOMER);
        assertThatThrownBy(() -> jdbcClient.sql(
                "UPDATE ledger_accounts SET status = 'BLOCKED' WHERE id = :id")
                .param("id", availableAccountId).update())
                .hasMessageContaining("wallet account status must match wallet lifecycle");
        assertThat(accountStatus(availableAccountId)).isEqualTo("OPEN");

        assertThatThrownBy(() -> jdbcClient.sql(
                "UPDATE ledger_accounts SET status = 'CLOSED' WHERE id = :id")
                .param("id", availableAccountId).update())
                .hasMessageContaining("wallet account status must match wallet lifecycle");
        assertThat(accountStatus(availableAccountId)).isEqualTo("OPEN");
        assertThat(walletStatus(CONTROLLED_INTERVAL_CUSTOMER)).isEqualTo("UNFUNDED");
    }

    private void migrateToV5() {
        migrateTo(5);
    }

    private void migrateTo(int target) {
        flyway(target).migrate();
    }

    private Flyway flyway(int target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(Integer.toString(target))
                .cleanDisabled(false)
                .load();
    }

    private void insertAccountV3(UUID id, String name) {
        jdbcClient.sql("""
                INSERT INTO ledger_accounts (id, account_type, status, name, created_at)
                VALUES (:id, 'ASSET', 'OPEN', :name, :at)
                """)
                .param("id", id)
                .param("name", name)
                .param("at", atUtc(BEFORE_MIGRATION), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private void insertAccountV5(UUID id, String name, String currency, String policy) {
        jdbcClient.sql("""
                INSERT INTO ledger_accounts (id, account_type, status, name, currency, balance_policy, created_at)
                VALUES (:id, 'ASSET', 'OPEN', :name, :currency, :policy, :at)
                """)
                .param("id", id)
                .param("name", name)
                .param("currency", currency)
                .param("policy", policy)
                .param("at", atUtc(BEFORE_MIGRATION), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private void insertProjection(UUID accountId) {
        jdbcClient.sql("INSERT INTO ledger_account_balances (account_id) VALUES (:id)")
                .param("id", accountId).update();
    }

    private void installBackfillFailureTrigger() {
        jdbcClient.sql("""
                CREATE FUNCTION test_fail_wallet_backfill() RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.name LIKE 'wallet:%' THEN
                        RAISE EXCEPTION 'forced wallet backfill failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER test_fail_wallet_backfill
                BEFORE INSERT ON ledger_accounts
                FOR EACH ROW EXECUTE FUNCTION test_fail_wallet_backfill()
                """).update();
    }

    private void insertHistory(String key, UUID debitAccount, UUID creditAccount, String amount, String currency) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            UUID transactionId = UUID.randomUUID();
            OffsetDateTime postedAt = atUtc(BEFORE_MIGRATION);
            jdbcClient.sql("""
                    INSERT INTO ledger_posting_idempotency
                        (idempotency_key, request_fingerprint, created_at)
                    VALUES (:key, :fingerprint, :at)
                    """)
                    .param("key", key)
                    .param("fingerprint", key + "-fingerprint")
                    .param("at", postedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                    .update();
            jdbcClient.sql("""
                    INSERT INTO ledger_transactions
                        (id, posted_at, value_date, idempotency_key, operation_reference, currency)
                    VALUES (:id, :postedAt, :valueDate, :key, :operation, :currency)
                    """)
                    .param("id", transactionId)
                    .param("postedAt", postedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("valueDate", java.time.LocalDate.of(2026, 1, 1))
                    .param("key", key)
                    .param("operation", key + "-operation")
                    .param("currency", currency)
                    .update();
            insertLine(transactionId, 1, debitAccount, "DEBIT", amount, currency);
            insertLine(transactionId, 2, creditAccount, "CREDIT", amount, currency);
        });
    }

    private void insertLine(UUID transactionId, int sequence, UUID accountId, String direction,
            String amount, String currency) {
        jdbcClient.sql("""
                INSERT INTO ledger_transaction_lines
                    (transaction_id, line_sequence, account_id, direction, amount, currency)
                VALUES (:transaction, :sequence, :account, :direction, :amount, :currency)
                """)
                .param("transaction", transactionId)
                .param("sequence", sequence)
                .param("account", accountId)
                .param("direction", direction)
                .param("amount", new BigDecimal(amount), Types.NUMERIC)
                .param("currency", currency)
                .update();
    }

    private void insertMismatchedLine(UUID accountId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            UUID transactionId = UUID.randomUUID();
            String key = "mismatch-" + UUID.randomUUID();
            OffsetDateTime postedAt = atUtc(BEFORE_MIGRATION);
            jdbcClient.sql("""
                    INSERT INTO ledger_posting_idempotency
                        (idempotency_key, request_fingerprint, created_at)
                    VALUES (:key, :fingerprint, :at)
                    """)
                    .param("key", key).param("fingerprint", key)
                    .param("at", postedAt, Types.TIMESTAMP_WITH_TIMEZONE).update();
            jdbcClient.sql("""
                    INSERT INTO ledger_transactions
                        (id, posted_at, value_date, idempotency_key, operation_reference, currency)
                    VALUES (:id, :postedAt, :valueDate, :key, :operation, 'USD')
                    """)
                    .param("id", transactionId).param("postedAt", postedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("valueDate", java.time.LocalDate.of(2026, 1, 1)).param("key", key)
                    .param("operation", key).update();
            insertLine(transactionId, 1, accountId, "DEBIT", "1.00", "ARS");
        });
    }

    private void insertCustomer(UUID id, String status) {
        jdbcClient.sql("""
                INSERT INTO customers (id, email, customer_type, status, created_at, updated_at, version)
                VALUES (:id, :email, 'INDIVIDUAL', :status, :at, :at, 0)
                """)
                .param("id", id)
                .param("email", id + "@example.com")
                .param("status", status)
                .param("at", atUtc(BEFORE_MIGRATION), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    private String accountCurrency(UUID accountId) {
        return jdbcClient.sql("SELECT currency FROM ledger_accounts WHERE id=:id")
                .param("id", accountId).query(String.class).single();
    }

    private String accountPolicy(UUID accountId) {
        return jdbcClient.sql("SELECT balance_policy FROM ledger_accounts WHERE id=:id")
                .param("id", accountId).query(String.class).single();
    }

    private String accountStatus(UUID accountId) {
        return jdbcClient.sql("SELECT status FROM ledger_accounts WHERE id=:id")
                .param("id", accountId).query(String.class).single();
    }

    private java.util.List<Object> projection(UUID accountId) {
        return jdbcClient.sql("""
                SELECT cumulative_debits::double precision, cumulative_credits::double precision, consistency_status
                  FROM ledger_account_balances WHERE account_id=:id
                """).param("id", accountId).query((rs, rowNum) -> java.util.List.of(
                (Object) rs.getDouble(1), rs.getDouble(2), rs.getString(3))).single();
    }

    private long walletCount(UUID customerId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM wallets WHERE customer_id=:id")
                .param("id", customerId).query(Long.class).single();
    }

    private long walletAccountCount(UUID customerId) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM ledger_accounts a
                JOIN wallets w ON a.id IN (w.available_account_id, w.reserved_account_id)
                WHERE w.customer_id=:id
                """).param("id", customerId).query(Long.class).single();
    }

    private long walletProjectionCount(UUID customerId) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM ledger_account_balances b
                JOIN wallets w ON b.account_id IN (w.available_account_id, w.reserved_account_id)
                WHERE w.customer_id=:id
                """).param("id", customerId).query(Long.class).single();
    }

    private String walletStatus(UUID customerId) {
        return jdbcClient.sql("SELECT status FROM wallets WHERE customer_id=:id")
                .param("id", customerId).query(String.class).single();
    }

    private String walletCurrency(UUID customerId) {
        return jdbcClient.sql("SELECT currency FROM wallets WHERE customer_id=:id")
                .param("id", customerId).query(String.class).single();
    }

    private UUID walletAvailableAccount(UUID customerId) {
        return jdbcClient.sql("SELECT available_account_id FROM wallets WHERE customer_id=:id")
                .param("id", customerId).query(UUID.class).single();
    }

    private java.util.List<String> walletAccountPolicies(UUID customerId) {
        return jdbcClient.sql("""
                SELECT a.balance_policy FROM ledger_accounts a
                JOIN wallets w ON a.id IN (w.available_account_id, w.reserved_account_id)
                WHERE w.customer_id=:id ORDER BY a.id
                """).param("id", customerId).query(String.class).list();
    }

    private String customerStatus(UUID customerId) {
        return jdbcClient.sql("SELECT status FROM customers WHERE id=:id")
                .param("id", customerId).query(String.class).single();
    }

    private boolean columnExists(String table, String column) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema='public' AND table_name=:table AND column_name=:column
                """).param("table", table).param("column", column).query(Long.class).single() > 0;
    }

    private boolean tableExists(String table) {
        return jdbcClient.sql("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema='public' AND table_name=:table
                """).param("table", table).query(Long.class).single() > 0;
    }

    private static OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
