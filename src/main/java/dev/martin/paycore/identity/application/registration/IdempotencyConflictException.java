package dev.martin.paycore.identity.application.registration;

public final class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("Idempotency key was already used for another request");
    }
}
