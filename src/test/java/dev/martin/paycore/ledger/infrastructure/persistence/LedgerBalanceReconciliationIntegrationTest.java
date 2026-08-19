package dev.martin.paycore.ledger.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.martin.paycore.ledger.application.balance.LedgerBalanceQuery;
import dev.martin.paycore.ledger.application.balance.LedgerReconciliationResult;
import dev.martin.paycore.ledger.application.balance.QueryLedgerBalancesService;
import dev.martin.paycore.ledger.application.balance.RebuildLedgerBalancesService;
import dev.martin.paycore.ledger.application.balance.ReconcileLedgerBalancesService;
import dev.martin.paycore.ledger.application.posting.FinancialPostingResult;
import dev.martin.paycore.ledger.application.posting.PostLedgerTransactionCommand;
import dev.martin.paycore.ledger.application.posting.PostLedgerTransactionService;
import dev.martin.paycore.ledger.application.posting.PostingLineCommand;
import dev.martin.paycore.ledger.domain.model.CurrencyCode;
import dev.martin.paycore.ledger.domain.model.LedgerAccountId;
import dev.martin.paycore.ledger.domain.model.LedgerAccountType;
import dev.martin.paycore.ledger.domain.model.LedgerBalancePolicy;
import dev.martin.paycore.ledger.domain.model.LedgerEntryDirection;
import dev.martin.paycore.ledger.domain.model.LedgerValidationException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.dao.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = {
        "paycore.authentication.enabled=false",
        "paycore.registration.enabled=false",
        "paycore.registration.worker-enabled=false"
})
class LedgerBalanceReconciliationIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private PostLedgerTransactionService posting;

    @Autowired
    private QueryLedgerBalancesService queries;

    @Autowired
    private ReconcileLedgerBalancesService reconciliation;

    @Autowired
    private RebuildLedgerBalancesService rebuild;

    @Autowired
    private JdbcTemplate jdbc;

    private LedgerAccountId debitAccount;
    private LedgerAccountId creditAccount;

    @BeforeEach
    void setUp() {
        debitAccount = LedgerAccountId.newId();
        creditAccount = LedgerAccountId.newId();
        insertAccount(debitAccount, LedgerAccountType.ASSET, "reconciliation-cash");
        insertAccount(creditAccount, LedgerAccountType.LIABILITY, "reconciliation-payable");
    }

    @Test
    void reportsAConsistentProjection() {
        FinancialPostingResult posted = post("reconcile-clean", "10.00");

        LedgerReconciliationResult result = reconciliation.reconcile(debitAccount.value());

        assertThat(result.consistent()).isTrue();
        assertThat(result.projectedDebits()).isEqualByComparingTo("10.00");
        assertThat(result.confirmedDebits()).isEqualByComparingTo("10.00");
        assertThat(posted.transaction().lines()).hasSize(2);
    }

    @Test
    void marksMismatchAndBlocksAffectedPosting() {
        post("reconcile-mismatch-seed", "10.00");
        jdbc.update("UPDATE ledger_account_balances SET cumulative_debits = 11.00 WHERE account_id = ?",
                debitAccount.value());

        LedgerReconciliationResult result = reconciliation.reconcile(debitAccount.value());

        assertThat(result.consistent()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT consistency_status FROM ledger_account_balances WHERE account_id = ?",
                String.class, debitAccount.value())).isEqualTo("INCONSISTENT");
        assertThatThrownBy(() -> post("reconcile-blocked", "1.00"))
                .isInstanceOf(LedgerValidationException.class)
                .hasMessageContaining("inconsistent");
        assertThatThrownBy(() -> queries.find(new LedgerBalanceQuery(debitAccount.value())))
                .isInstanceOf(LedgerValidationException.class)
                .hasMessageContaining("inconsistent");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE idempotency_key = ?",
                Integer.class, "reconcile-blocked")).isZero();
    }

    @Test
    void rebuildsFromImmutableLinesAndRestoresPosting() {
        FinancialPostingResult initial = post("rebuild-seed", "10.00");
        jdbc.update("UPDATE ledger_account_balances SET cumulative_debits = 99.00 WHERE account_id = ?",
                debitAccount.value());
        reconciliation.reconcile(debitAccount.value());

        LedgerReconciliationResult result = rebuild.rebuild(debitAccount.value());

        assertThat(result.consistent()).isTrue();
        assertThat(result.projectedDebits()).isEqualByComparingTo("10.00");
        assertThat(queries.find(new LedgerBalanceQuery(debitAccount.value())).naturalBalance())
                .isEqualByComparingTo("10.00");
        FinancialPostingResult afterRebuild = post("rebuild-after", "2.00");
        assertThat(afterRebuild.replayed()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transaction_lines WHERE transaction_id = ?",
                Integer.class, initial.transaction().id())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT amount FROM ledger_transaction_lines WHERE transaction_id = ? AND account_id = ?",
                java.math.BigDecimal.class, initial.transaction().id(), debitAccount.value()))
                .isEqualByComparingTo("10.00");
    }

    @Test
    void serializesPostingAndRebuildOnTheProjectionRow() throws Exception {
        post("race-seed", "10.00");
        jdbc.update("UPDATE ledger_account_balances SET cumulative_debits = 99.00 WHERE account_id = ?",
                debitAccount.value());
        reconciliation.reconcile(debitAccount.value());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<LedgerReconciliationResult> rebuildTask = () -> {
                ready.countDown();
                start.await();
                return rebuild.rebuild(debitAccount.value());
            };
            Callable<FinancialPostingResult> postingTask = () -> {
                ready.countDown();
                start.await();
                return post("race-post", "1.00");
            };
            Future<LedgerReconciliationResult> rebuilt = executor.submit(rebuildTask);
            Future<FinancialPostingResult> posted = executor.submit(postingTask);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(rebuilt.get(10, java.util.concurrent.TimeUnit.SECONDS).consistent()).isTrue();
            try {
                assertThat(posted.get(10, java.util.concurrent.TimeUnit.SECONDS).replayed()).isFalse();
            } catch (java.util.concurrent.ExecutionException exception) {
                assertThat(exception.getCause()).isInstanceOf(LedgerValidationException.class);
            }
            assertThat(reconciliation.reconcile(debitAccount.value()).consistent()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rollsBackFailedApplicationRebuildWithoutChangingHistory() {
        FinancialPostingResult initial = post("rebuild-rollback-seed", "10.00");
        jdbc.update("UPDATE ledger_account_balances SET cumulative_debits = 99.00 WHERE account_id = ?",
                debitAccount.value());
        reconciliation.reconcile(debitAccount.value());
        installRebuildFailureTrigger();

        try {
            assertThatThrownBy(() -> rebuild.rebuild(debitAccount.value()))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("forced rebuild failure");
            assertThat(jdbc.queryForObject(
                    "SELECT cumulative_debits FROM ledger_account_balances WHERE account_id = ?",
                    java.math.BigDecimal.class, debitAccount.value())).isEqualByComparingTo("99.00");
            assertThat(jdbc.queryForObject(
                    "SELECT consistency_status FROM ledger_account_balances WHERE account_id = ?",
                    String.class, debitAccount.value())).isEqualTo("INCONSISTENT");
            assertThat(jdbc.queryForObject(
                    "SELECT amount FROM ledger_transaction_lines WHERE transaction_id = ? AND account_id = ?",
                    java.math.BigDecimal.class, initial.transaction().id(), debitAccount.value()))
                    .isEqualByComparingTo("10.00");
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_rebuild_projection ON ledger_account_balances");
            jdbc.execute("DROP FUNCTION test_fail_rebuild_projection()");
        }
    }

    private FinancialPostingResult post(String key, String amount) {
        return posting.post(new PostLedgerTransactionCommand(
                Instant.parse("2026-08-13T12:00:00Z"), LocalDate.of(2026, 8, 13), key,
                "reconciliation-operation",
                List.of(
                        new PostingLineCommand(1, debitAccount.value(), amount, CurrencyCode.ARS,
                                LedgerEntryDirection.DEBIT),
                        new PostingLineCommand(2, creditAccount.value(), amount, CurrencyCode.ARS,
                                LedgerEntryDirection.CREDIT))));
    }

    private void insertAccount(LedgerAccountId id, LedgerAccountType type, String name) {
        jdbc.update("""
                INSERT INTO ledger_accounts
                    (id, account_type, status, name, currency, balance_policy, created_at)
                VALUES (?, ?, 'OPEN', ?, 'ARS', ?, ?)
                """, id.value(), type.name(), name, LedgerBalancePolicy.ALLOW_NEGATIVE.name(),
                Instant.now().atOffset(ZoneOffset.UTC));
    }

    private void installRebuildFailureTrigger() {
        jdbc.execute("""
                CREATE FUNCTION test_fail_rebuild_projection() RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF OLD.consistency_status = 'INCONSISTENT'
                       AND NEW.consistency_status = 'CONSISTENT' THEN
                        RAISE EXCEPTION 'forced rebuild failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER test_fail_rebuild_projection
                BEFORE UPDATE ON ledger_account_balances
                FOR EACH ROW EXECUTE FUNCTION test_fail_rebuild_projection()
                """);
    }
}
