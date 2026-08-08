package dev.martin.paycore.identity.application.registration;

import java.util.Objects;

public record ProvisionedIdentity(String issuer, String subject) {

    public ProvisionedIdentity {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
    }
}
