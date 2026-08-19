package dev.martin.paycore.ledger.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

public record LedgerAccountBalance(
        LedgerAccountType accountType,
        LedgerAccountStatus status,
        CurrencyCode currency,
        LedgerBalancePolicy balancePolicy,
        BigDecimal debits,
        BigDecimal credits) {

    public LedgerAccountBalance(LedgerAccountType accountType, BigDecimal debits, BigDecimal credits) {
        this(accountType, LedgerAccountStatus.OPEN, CurrencyCode.USD,
                LedgerBalancePolicy.NON_NEGATIVE, debits, credits);
    }

    public LedgerAccountBalance {
        Objects.requireNonNull(accountType, "accountType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(balancePolicy, "balancePolicy");
        Objects.requireNonNull(debits, "debits");
        Objects.requireNonNull(credits, "credits");
        if (debits.signum() < 0 || credits.signum() < 0) {
            throw new IllegalArgumentException("Cumulative debits and credits cannot be negative");
        }
    }

    public BigDecimal naturalBalance() {
        return switch (accountType) {
            case ASSET, EXPENSE -> debits.subtract(credits);
            case LIABILITY, EQUITY, REVENUE -> credits.subtract(debits);
        };
    }
}
