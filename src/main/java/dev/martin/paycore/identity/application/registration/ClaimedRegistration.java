package dev.martin.paycore.identity.application.registration;

import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.Email;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClaimedRegistration(
        UUID operationId,
        CustomerId customerId,
        Email email,
        RegistrationOperationState state,
        String externalSubject,
        UUID claimToken,
        long fencingVersion,
        int attemptCount,
        Instant leaseUntil) {

    public ClaimedRegistration {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (fencingVersion < 1 || attemptCount < 1) {
            throw new IllegalArgumentException("Claim versions and attempts must be positive");
        }
    }
}
