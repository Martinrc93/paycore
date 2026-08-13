package dev.martin.paycore.ledger.application.query;

import java.util.Objects;
import java.util.UUID;

public record MovementQuery(UUID accountId, int offset, int limit) {

    public MovementQuery {
        Objects.requireNonNull(accountId, "accountId");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
    }
}
