package dev.martin.paycore.identity.application.authentication;

import dev.martin.paycore.identity.domain.model.ExternalIdentity;
import java.time.Instant;
import java.util.Objects;

public record VerifiedCustomerLogin(ExternalIdentity identity, boolean emailVerified, Instant authenticatedAt) {

    public VerifiedCustomerLogin {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
    }
}
