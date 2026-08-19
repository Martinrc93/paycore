package dev.martin.paycore.ledger.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.martin.paycore.ledger.application.posting.FinancialPostingResult;
import dev.martin.paycore.ledger.application.port.out.LedgerTransactionStore;
import dev.martin.paycore.ledger.application.posting.PostLedgerTransactionCommand;
import dev.martin.paycore.ledger.application.posting.PostLedgerTransactionService;
import dev.martin.paycore.ledger.application.posting.PostingLineCommand;
import dev.martin.paycore.ledger.application.query.LedgerMovement;
import dev.martin.paycore.ledger.application.query.MovementQuery;
import dev.martin.paycore.ledger.application.query.QueryLedgerMovementsService;
import dev.martin.paycore.ledger.domain.model.CurrencyCode;
import dev.martin.paycore.ledger.domain.model.LedgerAccount;
import dev.martin.paycore.ledger.domain.model.LedgerAccountId;
import dev.martin.paycore.ledger.domain.model.LedgerAccountType;
import dev.martin.paycore.ledger.domain.model.LedgerEntryDirection;
import dev.martin.paycore.ledger.domain.model.LedgerBalancePolicy;
import dev.martin.paycore.ledger.domain.model.InsufficientLedgerBalanceException;
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
import org.springframework.transaction.support.TransactionTemplate;
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
class LedgerPersistenceIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private PostLedgerTransactionService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private QueryLedgerMovementsService movementQueries;

    @Autowired
    private LedgerTransactionStore transactionStore;

    @Autowired
    private LedgerTransactionPersistenceAdapter persistenceAdapter;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private LedgerAccountId debitAccount;
    private LedgerAccountId creditAccount;

    @BeforeEach
    void setUp() {
        debitAccount = LedgerAccountId.newId();
        creditAccount = LedgerAccountId.newId();
        insertAccount(account(debitAccount, LedgerAccountType.ASSET, "cash"));
        insertAccount(account(creditAccount, LedgerAccountType.LIABILITY, "payable"));
    }

    @Test
    void appliesMigrationAndPostsCompleteTransaction() {
        FinancialPostingResult result = service.post(command("integration-key-1", "10.00"));

        assertThat(result.replayed()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE idempotency_key = ?", Integer.class,
                "integration-key-1")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transaction_lines WHERE transaction_id = ?", Integer.class,
                result.transaction().id())).isEqualTo(2);
    }

    @Test
    void databaseRejectsNegativeNaturalBalanceForNonNegativeAccount() {
        LedgerAccountId nonNegative = LedgerAccountId.newId();
        insertAccount(account(nonNegative, LedgerAccountType.LIABILITY, "wallet", CurrencyCode.USD,
                LedgerBalancePolicy.NON_NEGATIVE));
        insertProjection(nonNegative);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ledger_account_balances
                   SET cumulative_debits = 1.00
                 WHERE account_id = ?
                """, nonNegative.value()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void databaseRejectsChangingPolicyToNonNegativeWhenExistingNaturalBalanceIsNegative() {
        LedgerAccountId accountId = LedgerAccountId.newId();
        insertAccount(account(accountId, LedgerAccountType.LIABILITY, "wallet", CurrencyCode.USD,
                LedgerBalancePolicy.ALLOW_NEGATIVE));
        insertProjection(accountId);
        jdbc.update("UPDATE ledger_account_balances SET cumulative_debits = 1.00 WHERE account_id = ?",
                accountId.value());

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ledger_accounts SET balance_policy = 'NON_NEGATIVE' WHERE id = ?", accountId.value()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void equivalentRetryDoesNotCreateSecondTransaction() {
        FinancialPostingResult first = service.post(command("integration-key-2", "20.00"));
        FinancialPostingResult second = service.post(command("integration-key-2", "20.00"));

        assertThat(second.replayed()).isTrue();
        assertThat(second.transaction().id()).isEqualTo(first.transaction().id());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE idempotency_key = ?", Integer.class,
                "integration-key-2")).isEqualTo(1);
    }

    @Test
    void updatesProjectionAndDoesNotApplyItAgainOnEquivalentReplay() {
        FinancialPostingResult first = service.post(command("projection-key", "20.00"));
        FinancialPostingResult replay = service.post(command("projection-key", "20.00"));

        assertThat(replay.replayed()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT cumulative_debits FROM ledger_account_balances WHERE account_id = ?",
                java.math.BigDecimal.class, debitAccount.value())).isEqualByComparingTo("20.00");
        assertThat(jdbc.queryForObject(
                "SELECT cumulative_credits FROM ledger_account_balances WHERE account_id = ?",
                java.math.BigDecimal.class, creditAccount.value())).isEqualByComparingTo("20.00");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transaction_lines WHERE transaction_id = ?",
                Integer.class, first.transaction().id())).isEqualTo(2);
    }

    @Test
    void rejectsAnOverdrawingNonNegativeAccountWithoutChangingHistoryOrProjection() {
        LedgerAccountId nonNegativeLiability = LedgerAccountId.newId();
        LedgerAccountId counterAsset = LedgerAccountId.newId();
        insertAccount(account(nonNegativeLiability, LedgerAccountType.LIABILITY, "non-negative", CurrencyCode.ARS,
                LedgerBalancePolicy.NON_NEGATIVE));
        insertAccount(account(counterAsset, LedgerAccountType.ASSET, "counter", CurrencyCode.ARS,
                LedgerBalancePolicy.ALLOW_NEGATIVE));
        insertProjection(nonNegativeLiability);
        insertProjection(counterAsset);

        assertThatThrownBy(() -> service.post(commandForAccounts(
                "overdraft-key", nonNegativeLiability, counterAsset, LedgerEntryDirection.DEBIT)))
                .isInstanceOf(InsufficientLedgerBalanceException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE idempotency_key = ?", Integer.class,
                "overdraft-key")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT cumulative_debits + cumulative_credits FROM ledger_account_balances WHERE account_id = ?",
                java.math.BigDecimal.class, nonNegativeLiability.value())).isEqualByComparingTo("0.00");
    }

    @Test
    void failsClosedWhenHistoricalAccountProjectionIsMissing() {
        service.post(command("historical-projection-key", "10.00"));
        jdbc.update("DELETE FROM ledger_account_balances WHERE account_id = ?", debitAccount.value());

        assertThatThrownBy(() -> service.post(command("missing-historical-projection-key", "5.00")))
                .isInstanceOf(LedgerValidationException.class)
                .hasMessageContaining("balance projection");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE idempotency_key = ?", Integer.class,
                "missing-historical-projection-key")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_account_balances WHERE account_id = ?", Integer.class,
                debitAccount.value())).isZero();
    }

    @Test
    void rollsBackHistoryAndIdempotencyWhenProjectionUpdateFails() {
         insertProjection(debitAccount);
         jdbc.update("""
                 UPDATE ledger_account_balances
                    SET cumulative_debits = 99999999999999999.99
                 WHERE account_id = ?
                """, debitAccount.value());

         assertThatThrownBy(() -> service.post(command("projection-rollback-key", "0.01")))
                 .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE idempotency_key = ?", Integer.class,
                "projection-rollback-key")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting_idempotency WHERE idempotency_key = ?", Integer.class,
                "projection-rollback-key")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT cumulative_debits FROM ledger_account_balances WHERE account_id = ?",
                java.math.BigDecimal.class, debitAccount.value())).isEqualByComparingTo("99999999999999999.99");
    }

    @Test
    void concurrentDebitsCannotOverdrawTheSameNonNegativeAccount() throws Exception {
        LedgerAccountId fundedLiability = LedgerAccountId.newId();
        insertAccount(account(fundedLiability, LedgerAccountType.LIABILITY, "funded", CurrencyCode.ARS,
                LedgerBalancePolicy.NON_NEGATIVE));
        service.post(commandForAccounts("funding-key", debitAccount, fundedLiability));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<FinancialPostingResult> post = () -> {
                ready.countDown();
                start.await();
                return service.post(commandForAmount(
                        "concurrent-overdraft-" + UUID.randomUUID(), fundedLiability, debitAccount, "7.00"));
            };
            Future<FinancialPostingResult> first = executor.submit(post);
            Future<FinancialPostingResult> second = executor.submit(post);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successful = 0;
            int rejected = 0;
            for (Future<FinancialPostingResult> result : List.of(first, second)) {
                try {
                    result.get();
                    successful++;
                } catch (java.util.concurrent.ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(InsufficientLedgerBalanceException.class);
                    rejected++;
                }
            }
            assertThat(successful).isEqualTo(1);
            assertThat(rejected).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT cumulative_credits - cumulative_debits FROM ledger_account_balances WHERE account_id = ?",
                    java.math.BigDecimal.class, fundedLiability.value())).isEqualByComparingTo("3.00");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentPostingsWithOverlappingAccountsInInverseOrderCompleteWithoutDeadlock() throws Exception {
        LedgerAccountId first = LedgerAccountId.newId();
        LedgerAccountId second = LedgerAccountId.newId();
        insertAccount(account(first, LedgerAccountType.ASSET, "overlap-first"));
        insertAccount(account(second, LedgerAccountType.LIABILITY, "overlap-second"));
        LedgerAccountId lower = first.value().compareTo(second.value()) < 0 ? first : second;
        LedgerAccountId higher = lower.equals(first) ? second : first;

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<FinancialPostingResult> lowerFirst = concurrentPosting(
                    ready, start, "inverse-order-first", lower, higher);
            Callable<FinancialPostingResult> higherFirst = concurrentPosting(
                    ready, start, "inverse-order-second", higher, lower);
            Future<FinancialPostingResult> firstResult = executor.submit(lowerFirst);
            Future<FinancialPostingResult> secondResult = executor.submit(higherFirst);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(firstResult.get(10, java.util.concurrent.TimeUnit.SECONDS).replayed()).isFalse();
            assertThat(secondResult.get(10, java.util.concurrent.TimeUnit.SECONDS).replayed()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsIdempotencyConflict() {
        service.post(command("integration-key-3", "30.00"));

        assertThatThrownBy(() -> service.post(command("integration-key-3", "31.00")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE idempotency_key = ?", Integer.class,
                "integration-key-3")).isEqualTo(1);
    }

    @Test
    void rollsBackIdempotencyClaimWhenBalanceValidationFails() {
        String key = "adapter-rollback-key";
        UUID debitAccountId = LedgerAccountId.newId().value();
        UUID creditAccountId = LedgerAccountId.newId().value();
        dev.martin.paycore.ledger.domain.model.FinancialTransaction transaction =
                dev.martin.paycore.ledger.domain.model.FinancialTransaction.confirm(
                        Instant.parse("2026-08-13T12:00:00Z"), LocalDate.of(2026, 8, 13), key,
                        "adapter-rollback-operation", List.of(
                                dev.martin.paycore.ledger.domain.model.LedgerLine.debit(
                                        1, new LedgerAccountId(debitAccountId),
                                        dev.martin.paycore.ledger.domain.model.Money.of("10.00", CurrencyCode.ARS)),
                                dev.martin.paycore.ledger.domain.model.LedgerLine.credit(
                                        2, new LedgerAccountId(creditAccountId),
                                        dev.martin.paycore.ledger.domain.model.Money.of("10.00", CurrencyCode.ARS))));

        assertThatThrownBy(() -> persistenceAdapter.post(transaction, "rollback-fingerprint"))
                .isInstanceOf(LedgerValidationException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transactions WHERE id = ?", Integer.class, transaction.id()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting_idempotency WHERE idempotency_key = ?", Integer.class, key))
                .isZero();
    }

    @Test
    void protectsConfirmedHistoryFromUpdateAndDelete() {
        FinancialPostingResult result = service.post(command("append-only-key", "40.00"));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ledger_transactions SET operation_reference = ? WHERE id = ?", "changed", result.transaction().id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ledger_transaction_lines WHERE transaction_id = ?", result.transaction().id()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void returnsMovementsInStableBoundedOrder() {
        service.post(command("movement-key-1", "50.00", Instant.parse("2026-08-13T12:00:00Z"), "movement-first"));
        service.post(command("movement-key-2", "51.00", Instant.parse("2026-08-13T12:01:00Z"), "movement-second"));

        List<LedgerMovement> movements = movementQueries.find(new MovementQuery(debitAccount.value(), 0, 10));

        assertThat(movements).hasSize(2);
        assertThat(movements).allSatisfy(movement -> {
            assertThat(movement.accountId()).isEqualTo(debitAccount.value());
            assertThat(movement.direction()).isEqualTo(LedgerEntryDirection.DEBIT);
            assertThat(movement.currency()).isEqualTo(CurrencyCode.ARS);
        });
        assertThat(movements).extracting(LedgerMovement::operationReference)
                .containsExactly("movement-first", "movement-second");
        assertThat(movementQueries.find(new MovementQuery(debitAccount.value(), 1, 1)))
                .extracting(LedgerMovement::operationReference)
                .containsExactly("movement-second");
    }

    @Test
    void concurrentEquivalentRetriesConfirmOneTransaction() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<FinancialPostingResult> post = () -> {
                ready.countDown();
                start.await();
                return service.post(command("concurrent-key", "60.00"));
            };
            Future<FinancialPostingResult> first = executor.submit(post);
            Future<FinancialPostingResult> second = executor.submit(post);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();

            FinancialPostingResult firstResult = first.get();
            FinancialPostingResult secondResult = second.get();

            assertThat(firstResult.transaction().id()).isEqualTo(secondResult.transaction().id());
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ledger_transactions WHERE idempotency_key = ?", Integer.class,
                    "concurrent-key")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void preservesZeroDecimalCurrencyWhenReadingFromNumericStorage() {
        LedgerAccountId jpyDebit = LedgerAccountId.newId();
        LedgerAccountId jpyCredit = LedgerAccountId.newId();
        insertAccount(account(jpyDebit, LedgerAccountType.ASSET, "jpy-cash", CurrencyCode.JPY));
        insertAccount(account(jpyCredit, LedgerAccountType.LIABILITY, "jpy-payable", CurrencyCode.JPY));

        PostLedgerTransactionCommand command = new PostLedgerTransactionCommand(
                Instant.parse("2026-08-13T12:00:00Z"), LocalDate.of(2026, 8, 13), "jpy-key", "jpy-operation",
                List.of(
                        new PostingLineCommand(1, jpyDebit.value(), "100", CurrencyCode.JPY, LedgerEntryDirection.DEBIT),
                        new PostingLineCommand(2, jpyCredit.value(), "100", CurrencyCode.JPY, LedgerEntryDirection.CREDIT)));

        FinancialPostingResult result = service.post(command);

        FinancialPostingResult reloaded = new FinancialPostingResult(
                transactionStore.findById(result.transaction().id()).orElseThrow(), false);
        assertThat(reloaded.transaction().currency()).isEqualTo(CurrencyCode.JPY);
        assertThat(reloaded.transaction().lines().getFirst().money().amount().scale()).isZero();
    }

    @Test
    void rejectsBlockedAndClosedAccountsBeforeWriting() {
        LedgerAccountId blocked = LedgerAccountId.newId();
        LedgerAccountId closed = LedgerAccountId.newId();
        insertAccount(account(blocked, LedgerAccountType.ASSET, "blocked").block());
        insertAccount(account(closed, LedgerAccountType.ASSET, "closed").close());

        assertThatThrownBy(() -> service.post(commandForAccounts("blocked-key", blocked, creditAccount)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.post(commandForAccounts("closed-key", closed, creditAccount)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE idempotency_key IN ('blocked-key', 'closed-key')", Integer.class))
                .isZero();
    }

    @Test
    void rejectsAnEmptyDirectTransactionAtCommit() {
        String key = "empty-transaction-key";
        UUID transactionId = UUID.randomUUID();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO ledger_posting_idempotency
                        (idempotency_key, request_fingerprint, created_at)
                    VALUES (?, ?, ?)
                    """, key, "empty-fingerprint", Instant.now().atOffset(ZoneOffset.UTC));
            jdbc.update("""
                    INSERT INTO ledger_transactions
                        (id, posted_at, value_date, idempotency_key, operation_reference, currency)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, transactionId, Instant.now().atOffset(ZoneOffset.UTC), LocalDate.of(2026, 8, 13),
                    key, "empty-operation", "ARS");
        })).isInstanceOf(DataAccessException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE id = ?", Integer.class, transactionId)).isZero();
    }

    @Test
    void equivalentRetryReturnsOriginalAfterAccountIsBlocked() {
        PostLedgerTransactionCommand originalCommand = command("blocked-after-post-key", "80.00");
        FinancialPostingResult original = service.post(originalCommand);
        jdbc.update("UPDATE ledger_accounts SET status = 'BLOCKED' WHERE id = ?", debitAccount.value());

        FinancialPostingResult replay = service.post(originalCommand);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transaction().id()).isEqualTo(original.transaction().id());
    }

    @Test
    void persistsAndReadsAnExactCompensation() {
        FinancialPostingResult original = service.post(command("compensation-original", "70.00"));
        dev.martin.paycore.ledger.application.posting.CompensateLedgerTransactionService compensation =
                new dev.martin.paycore.ledger.application.posting.CompensateLedgerTransactionService(
                        transactionStore, service);

        FinancialPostingResult reversal = compensation.reverse(
                original.transaction().id(), Instant.parse("2026-08-13T12:01:00Z"),
                LocalDate.of(2026, 8, 13), "compensation-reversal", "compensation-operation");

        assertThat(reversal.transaction().reversalOf()).isEqualTo(original.transaction().id());
        assertThat(transactionStore.findById(reversal.transaction().id())).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE reversal_of = ?", Integer.class,
                original.transaction().id())).isEqualTo(1);
    }

    private PostLedgerTransactionCommand commandForAccounts(
            String key, LedgerAccountId debitAccountId, LedgerAccountId creditAccountId) {
        return commandForAccounts(key, debitAccountId, creditAccountId, LedgerEntryDirection.DEBIT);
    }

    private PostLedgerTransactionCommand commandForAccounts(
            String key, LedgerAccountId firstAccount, LedgerAccountId secondAccount, LedgerEntryDirection firstDirection) {
        return new PostLedgerTransactionCommand(
                Instant.parse("2026-08-13T12:00:00Z"), LocalDate.of(2026, 8, 13), key, "state-operation",
                List.of(
                        new PostingLineCommand(1, firstAccount.value(), "10.00", CurrencyCode.ARS, firstDirection),
                        new PostingLineCommand(2, secondAccount.value(), "10.00", CurrencyCode.ARS,
                                firstDirection == LedgerEntryDirection.DEBIT
                                        ? LedgerEntryDirection.CREDIT
                                        : LedgerEntryDirection.DEBIT)));
    }

    private PostLedgerTransactionCommand commandForAmount(
            String key, LedgerAccountId debitAccountId, LedgerAccountId creditAccountId, String amount) {
        return new PostLedgerTransactionCommand(
                Instant.parse("2026-08-13T12:00:00Z"), LocalDate.of(2026, 8, 13), key, "concurrent-operation",
                List.of(
                        new PostingLineCommand(1, debitAccountId.value(), amount, CurrencyCode.ARS, LedgerEntryDirection.DEBIT),
                        new PostingLineCommand(2, creditAccountId.value(), amount, CurrencyCode.ARS, LedgerEntryDirection.CREDIT)));
    }

    private Callable<FinancialPostingResult> concurrentPosting(
            CountDownLatch ready,
            CountDownLatch start,
            String key,
            LedgerAccountId debitAccountId,
            LedgerAccountId creditAccountId) {
        return () -> {
            ready.countDown();
            start.await();
            return service.post(commandForAmount(key, debitAccountId, creditAccountId, "1.00"));
        };
    }

    private PostLedgerTransactionCommand command(String key, String amount) {
        return command(key, amount, Instant.parse("2026-08-13T12:00:00Z"), "integration-operation");
    }

    private PostLedgerTransactionCommand command(
            String key, String amount, Instant postedAt, String operationReference) {
        return new PostLedgerTransactionCommand(
                postedAt,
                LocalDate.of(2026, 8, 13), key, operationReference,
                List.of(
                        new PostingLineCommand(1, debitAccount.value(), amount, CurrencyCode.ARS, LedgerEntryDirection.DEBIT),
                        new PostingLineCommand(2, creditAccount.value(), amount, CurrencyCode.ARS, LedgerEntryDirection.CREDIT)));
    }

    private void insertAccount(LedgerAccount account) {
        jdbc.update("""
                INSERT INTO ledger_accounts
                    (id, account_type, status, name, currency, balance_policy, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, account.id().value(), account.type().name(), account.status().name(), account.name(),
                account.currency().name(), account.balancePolicy().name(), Instant.now().atOffset(ZoneOffset.UTC));
    }

    private void insertProjection(LedgerAccountId accountId) {
        jdbc.update("INSERT INTO ledger_account_balances (account_id) VALUES (?)", accountId.value());
    }

    private static LedgerAccount account(LedgerAccountId id, LedgerAccountType type, String name) {
        return account(id, type, name, CurrencyCode.ARS, LedgerBalancePolicy.ALLOW_NEGATIVE);
    }

    private static LedgerAccount account(
            LedgerAccountId id, LedgerAccountType type, String name, CurrencyCode currency) {
        return account(id, type, name, currency, LedgerBalancePolicy.ALLOW_NEGATIVE);
    }

    private static LedgerAccount account(
            LedgerAccountId id,
            LedgerAccountType type,
            String name,
            CurrencyCode currency,
            LedgerBalancePolicy balancePolicy) {
        return LedgerAccount.open(id, type, name, currency, balancePolicy);
    }
}
