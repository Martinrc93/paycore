package dev.martin.paycore.ledger.application.balance;

import java.util.Objects;
import java.util.UUID;

public record LedgerBalanceQuery(UUID accountId) {

    public LedgerBalanceQuery {
        Objects.requireNonNull(accountId, "accountId");
    }
}
