package dev.martin.paycore.identity.application.registration;

import dev.martin.paycore.identity.domain.model.Customer;
import java.time.Instant;
import java.util.Objects;

public record RegistrationIntent(
        IdempotencyDigests idempotencyDigests,
        String requestFingerprint,
        Customer customer,
        Instant expiresAt) {

    public RegistrationIntent {
        Objects.requireNonNull(idempotencyDigests, "idempotencyDigests");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
