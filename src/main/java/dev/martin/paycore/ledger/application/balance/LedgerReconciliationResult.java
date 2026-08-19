package dev.martin.paycore.ledger.application.balance;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record LedgerReconciliationResult(
        UUID accountId,
        BigDecimal projectedDebits,
        BigDecimal projectedCredits,
        BigDecimal confirmedDebits,
        BigDecimal confirmedCredits,
        boolean consistent) {

    public LedgerReconciliationResult {
        Objects.requireNonNull(accountId, "accountId");
        requireNonNegative(projectedDebits, "projectedDebits");
        requireNonNegative(projectedCredits, "projectedCredits");
        requireNonNegative(confirmedDebits, "confirmedDebits");
        requireNonNegative(confirmedCredits, "confirmedCredits");
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
    }
}
