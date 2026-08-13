package dev.martin.paycore.ledger.application.posting;

import dev.martin.paycore.ledger.application.port.out.LedgerTransactionStore;
import dev.martin.paycore.ledger.domain.model.FinancialTransaction;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public final class CompensateLedgerTransactionService {

    private final LedgerTransactionStore transactions;
    private final PostLedgerTransactionService posting;

    public CompensateLedgerTransactionService(
            LedgerTransactionStore transactions,
            PostLedgerTransactionService posting) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.posting = Objects.requireNonNull(posting, "posting");
    }

    public FinancialPostingResult reverse(
            UUID transactionId,
            Instant postedAt,
            LocalDate valueDate,
            String idempotencyKey,
            String operationReference) {
        FinancialTransaction original = transactions.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));
        FinancialTransaction reversal = original.reverse(postedAt, valueDate, idempotencyKey, operationReference);
        return posting.post(PostLedgerTransactionCommandMapper.from(reversal));
    }

    public FinancialPostingResult correct(
            UUID transactionId,
            Instant postedAt,
            LocalDate valueDate,
            String idempotencyKey,
            String operationReference,
            java.util.List<dev.martin.paycore.ledger.application.posting.PostingLineCommand> lines) {
        FinancialTransaction original = transactions.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));
        FinancialTransaction correction = original.correct(
                postedAt,
                valueDate,
                idempotencyKey,
                operationReference,
                lines.stream().map(PostLedgerTransactionCommandMapper::toDomain).toList());
        return posting.post(PostLedgerTransactionCommandMapper.from(correction));
    }
}
