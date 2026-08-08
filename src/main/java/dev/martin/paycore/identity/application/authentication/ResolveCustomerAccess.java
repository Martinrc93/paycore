package dev.martin.paycore.identity.application.authentication;

import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.ExternalIdentity;
import java.util.Optional;

public interface ResolveCustomerAccess {

    Optional<CustomerAccess> resolve(ExternalIdentity identity);

    Optional<CustomerAccess> resolve(CustomerId customerId);
}
