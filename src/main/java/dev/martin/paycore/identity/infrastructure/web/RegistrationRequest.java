package dev.martin.paycore.identity.infrastructure.web;

import dev.martin.paycore.identity.domain.model.CustomerType;
import java.util.Objects;

public record RegistrationRequest(String email, CustomerType customerType) {

    public RegistrationRequest {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(customerType, "customerType");
    }
}
