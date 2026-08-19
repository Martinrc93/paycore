package dev.martin.paycore.ledger.domain.model;

import java.util.Objects;

public record LedgerAccount(
        LedgerAccountId id,
        LedgerAccountType type,
        LedgerAccountStatus status,
        String name,
        CurrencyCode currency,
        LedgerBalancePolicy balancePolicy) {

    public LedgerAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(balancePolicy, "balancePolicy");
        if (name.isBlank() || name.length() > 128) {
            throw new IllegalArgumentException("Account name must contain 1 to 128 characters");
        }
    }

    public static LedgerAccount open(
            LedgerAccountId id,
            LedgerAccountType type,
            String name,
            CurrencyCode currency,
            LedgerBalancePolicy balancePolicy) {
        return new LedgerAccount(id, type, LedgerAccountStatus.OPEN, name.strip(), currency, balancePolicy);
    }

    public boolean isOpen() {
        return status == LedgerAccountStatus.OPEN;
    }

    public LedgerAccount block() {
        if (status != LedgerAccountStatus.OPEN) {
            throw new IllegalStateException("Only open accounts can be blocked");
        }
        return new LedgerAccount(id, type, LedgerAccountStatus.BLOCKED, name, currency, balancePolicy);
    }

    public LedgerAccount close() {
        if (status == LedgerAccountStatus.CLOSED) {
            throw new IllegalStateException("Account is already closed");
        }
        return new LedgerAccount(id, type, LedgerAccountStatus.CLOSED, name, currency, balancePolicy);
    }

    public LedgerAccount open() {
        throw new IllegalStateException("Closed or blocked accounts cannot be reopened");
    }

    public LedgerAccount unblock() {
        if (status != LedgerAccountStatus.BLOCKED) {
            throw new IllegalStateException("Only blocked accounts can be unblocked");
        }
        return new LedgerAccount(id, type, LedgerAccountStatus.OPEN, name, currency, balancePolicy);
    }
}
