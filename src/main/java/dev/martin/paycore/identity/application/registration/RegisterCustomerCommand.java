package dev.martin.paycore.identity.application.registration;

import dev.martin.paycore.identity.domain.model.CustomerType;
import java.util.Objects;

public record RegisterCustomerCommand(String idempotencyKey, String email, CustomerType type) {

    public RegisterCustomerCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(type, "type");
    }
}
