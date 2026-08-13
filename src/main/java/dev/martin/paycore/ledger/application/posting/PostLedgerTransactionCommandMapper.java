package dev.martin.paycore.ledger.application.posting;

import dev.martin.paycore.ledger.domain.model.FinancialTransaction;
import dev.martin.paycore.ledger.domain.model.LedgerAccountId;
import dev.martin.paycore.ledger.domain.model.LedgerLine;
import dev.martin.paycore.ledger.domain.model.Money;
import java.util.List;

final class PostLedgerTransactionCommandMapper {

    private PostLedgerTransactionCommandMapper() {
    }

    static PostLedgerTransactionCommand from(FinancialTransaction transaction) {
        return new PostLedgerTransactionCommand(
                transaction.postedAt(),
                transaction.valueDate(),
                transaction.idempotencyKey(),
                transaction.operationReference(),
                transaction.lines().stream().map(line -> new PostingLineCommand(
                        line.sequence(),
                        line.accountId().value(),
                        line.money().amount().toPlainString(),
                        line.money().currency(),
                        line.direction())).toList(),
                transaction.reversalOf(),
                transaction.correctionOf());
    }

    static LedgerLine toDomain(PostingLineCommand line) {
        return new LedgerLine(
                line.sequence(),
                new LedgerAccountId(line.accountId()),
                Money.of(line.amount(), line.currency()),
                line.direction());
    }
}
