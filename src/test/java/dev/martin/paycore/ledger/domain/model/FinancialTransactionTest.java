package dev.martin.paycore.ledger.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinancialTransactionTest {

    private final LedgerAccountId debitAccount = LedgerAccountId.newId();
    private final LedgerAccountId creditAccount = LedgerAccountId.newId();
    private final Instant postedAt = Instant.parse("2026-08-13T12:00:00Z");

    @Test
    void confirmsBalancedTransactionWithOneCurrency() {
        FinancialTransaction transaction = FinancialTransaction.confirm(
                postedAt,
                LocalDate.of(2026, 8, 13),
                "key-1",
                "operation-1",
                List.of(
                        LedgerLine.debit(1, debitAccount, Money.of("10.00", CurrencyCode.ARS)),
                        LedgerLine.credit(2, creditAccount, Money.of("10.00", CurrencyCode.ARS))));

        assertThat(transaction.lines()).hasSize(2);
        assertThat(transaction.total()).isEqualTo(Money.of("10.00", CurrencyCode.ARS));
    }

    @Test
    void rejectsUnbalancedOrIncompleteTransactions() {
        assertThatThrownBy(() -> FinancialTransaction.confirm(
                postedAt, LocalDate.of(2026, 8, 13), "key-1", "operation-1",
                List.of(LedgerLine.debit(1, debitAccount, Money.of("10.00", CurrencyCode.ARS)),
                        LedgerLine.credit(2, creditAccount, Money.of("9.00", CurrencyCode.ARS)))))
                .isInstanceOf(LedgerValidationException.class);

        assertThatThrownBy(() -> FinancialTransaction.confirm(
                postedAt, LocalDate.of(2026, 8, 13), "key-3", "operation-3",
                List.of(LedgerLine.debit(1, debitAccount, Money.of("10.00", CurrencyCode.ARS)),
                        LedgerLine.credit(2, creditAccount, Money.of("10.00", CurrencyCode.USD)))))
                .isInstanceOf(LedgerValidationException.class);

        assertThatThrownBy(() -> FinancialTransaction.confirm(
                postedAt, LocalDate.of(2026, 8, 13), "key-2", "operation-2",
                List.of(LedgerLine.debit(1, debitAccount, Money.of("10.00", CurrencyCode.ARS)))))
                .isInstanceOf(LedgerValidationException.class);
    }

    @Test
    void reversesWithoutChangingOriginal() {
        FinancialTransaction original = FinancialTransaction.confirm(
                postedAt, LocalDate.of(2026, 8, 13), "key-1", "operation-1",
                List.of(LedgerLine.debit(1, debitAccount, Money.of("10.00", CurrencyCode.ARS)),
                        LedgerLine.credit(2, creditAccount, Money.of("10.00", CurrencyCode.ARS))));

        FinancialTransaction reversal = original.reverse(
                Instant.parse("2026-08-13T12:01:00Z"), LocalDate.of(2026, 8, 13), "key-2", "operation-1-reversal");

        assertThat(reversal.reversalOf()).isEqualTo(original.id());
        assertThat(reversal.lines().get(0).direction()).isEqualTo(LedgerEntryDirection.CREDIT);
        assertThat(original.lines().get(0).direction()).isEqualTo(LedgerEntryDirection.DEBIT);
    }

    @Test
    void createsPartialCorrectionWithoutChangingOriginal() {
        FinancialTransaction original = FinancialTransaction.confirm(
                postedAt, LocalDate.of(2026, 8, 13), "key-1", "operation-1",
                List.of(LedgerLine.debit(1, debitAccount, Money.of("10.00", CurrencyCode.ARS)),
                        LedgerLine.credit(2, creditAccount, Money.of("10.00", CurrencyCode.ARS))));

        FinancialTransaction correction = original.correct(
                Instant.parse("2026-08-13T12:02:00Z"), LocalDate.of(2026, 8, 13),
                "key-3", "operation-1-correction", List.of(
                        LedgerLine.debit(1, debitAccount, Money.of("1.00", CurrencyCode.ARS)),
                        LedgerLine.credit(2, creditAccount, Money.of("1.00", CurrencyCode.ARS))));

        assertThat(correction.correctionOf()).isEqualTo(original.id());
        assertThat(correction.total()).isEqualTo(Money.of("1.00", CurrencyCode.ARS));
        assertThat(original.total()).isEqualTo(Money.of("10.00", CurrencyCode.ARS));
    }

    @Test
    void rejectsSensitiveOperationMetadata() {
        assertThatThrownBy(() -> FinancialTransaction.confirm(
                postedAt, LocalDate.of(2026, 8, 13), "key-1", "contains secret value", List.of(
                        LedgerLine.debit(1, debitAccount, Money.of("1.00", CurrencyCode.ARS)),
                        LedgerLine.credit(2, creditAccount, Money.of("1.00", CurrencyCode.ARS)))))
                .isInstanceOf(LedgerValidationException.class);
        assertThatThrownBy(() -> FinancialTransaction.confirm(
                postedAt, LocalDate.of(2026, 8, 13), "key-1", "customer@example.com", List.of(
                        LedgerLine.debit(1, debitAccount, Money.of("1.00", CurrencyCode.ARS)),
                        LedgerLine.credit(2, creditAccount, Money.of("1.00", CurrencyCode.ARS)))))
                .isInstanceOf(LedgerValidationException.class);
    }
}
