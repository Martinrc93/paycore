package dev.martin.paycore.identity.application.port.out;

import dev.martin.paycore.identity.application.authentication.CustomerAccess;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.ExternalIdentity;
import java.util.Optional;

public interface CustomerAccessRepository {

    Optional<CustomerAccess> findByExternalIdentity(ExternalIdentity identity);

    Optional<CustomerAccess> findByCustomerId(CustomerId customerId);
}
