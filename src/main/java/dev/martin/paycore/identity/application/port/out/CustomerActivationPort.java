package dev.martin.paycore.identity.application.port.out;

import dev.martin.paycore.identity.application.authentication.CustomerAccess;
import dev.martin.paycore.identity.domain.model.CustomerId;
import java.time.Instant;
import java.util.Optional;

public interface CustomerActivationPort {

    Optional<CustomerAccess> activatePending(CustomerId customerId, Instant activatedAt);
}
