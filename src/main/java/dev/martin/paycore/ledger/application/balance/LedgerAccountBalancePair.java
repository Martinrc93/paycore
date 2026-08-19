package dev.martin.paycore.ledger.application.balance;

import dev.martin.paycore.ledger.domain.model.LedgerAccountBalance;
import java.util.Objects;

public record LedgerAccountBalancePair(
        LedgerAccountBalance available,
        LedgerAccountBalance reserved) {

    public LedgerAccountBalancePair {
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(reserved, "reserved");
    }
}
