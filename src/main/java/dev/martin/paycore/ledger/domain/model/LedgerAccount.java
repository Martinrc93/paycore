package dev.martin.paycore.ledger.domain.model;

import java.util.Objects;

public record LedgerAccount(
        LedgerAccountId id,
        LedgerAccountType type,
        LedgerAccountStatus status,
        String name) {

    public LedgerAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > 128) {
            throw new IllegalArgumentException("Account name must contain 1 to 128 characters");
        }
    }

    public static LedgerAccount open(LedgerAccountId id, LedgerAccountType type, String name) {
        return new LedgerAccount(id, type, LedgerAccountStatus.OPEN, name.strip());
    }

    public boolean isOpen() {
        return status == LedgerAccountStatus.OPEN;
    }

    public LedgerAccount block() {
        if (status != LedgerAccountStatus.OPEN) {
            throw new IllegalStateException("Only open accounts can be blocked");
        }
        return new LedgerAccount(id, type, LedgerAccountStatus.BLOCKED, name);
    }

    public LedgerAccount close() {
        if (status == LedgerAccountStatus.CLOSED) {
            throw new IllegalStateException("Account is already closed");
        }
        return new LedgerAccount(id, type, LedgerAccountStatus.CLOSED, name);
    }

    public LedgerAccount open() {
        throw new IllegalStateException("Closed or blocked accounts cannot be reopened");
    }
}
