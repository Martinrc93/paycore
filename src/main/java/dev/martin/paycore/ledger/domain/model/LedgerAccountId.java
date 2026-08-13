package dev.martin.paycore.ledger.domain.model;

import java.util.Objects;
import java.util.UUID;

public record LedgerAccountId(UUID value) {

    public LedgerAccountId {
        Objects.requireNonNull(value, "value");
    }

    public static LedgerAccountId newId() {
        return new LedgerAccountId(UUID.randomUUID());
    }
}
