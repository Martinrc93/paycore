package dev.martin.paycore.ledger.application.posting;

public class LedgerIdempotencyConflictException extends IllegalStateException {

    public LedgerIdempotencyConflictException() {
        super("Idempotency key was already used with different content");
    }
}
