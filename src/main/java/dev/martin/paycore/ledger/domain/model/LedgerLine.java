package dev.martin.paycore.ledger.domain.model;

import java.util.Objects;

public record LedgerLine(
        int sequence,
        LedgerAccountId accountId,
        Money money,
        LedgerEntryDirection direction) {

    public LedgerLine {
        if (sequence < 1) {
            throw new LedgerValidationException("Line sequence must be positive");
        }
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(money, "money");
        Objects.requireNonNull(direction, "direction");
    }

    public static LedgerLine debit(int sequence, LedgerAccountId accountId, Money money) {
        return new LedgerLine(sequence, accountId, money, LedgerEntryDirection.DEBIT);
    }

    public static LedgerLine credit(int sequence, LedgerAccountId accountId, Money money) {
        return new LedgerLine(sequence, accountId, money, LedgerEntryDirection.CREDIT);
    }

    public LedgerLine oppositeDirection() {
        return new LedgerLine(sequence, accountId, money,
                direction == LedgerEntryDirection.DEBIT
                        ? LedgerEntryDirection.CREDIT
                        : LedgerEntryDirection.DEBIT);
    }
}
