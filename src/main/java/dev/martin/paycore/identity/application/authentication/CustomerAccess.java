package dev.martin.paycore.identity.application.authentication;

import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.CustomerStatus;
import java.util.Objects;

public record CustomerAccess(CustomerId customerId, CustomerStatus status) {

    public CustomerAccess {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(status, "status");
    }

    public boolean isActive() {
        return status == CustomerStatus.ACTIVE;
    }
}
