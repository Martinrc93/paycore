package dev.martin.paycore.identity.application.authentication;

import dev.martin.paycore.identity.application.port.out.CustomerAccessRepository;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.ExternalIdentity;
import java.util.Objects;
import java.util.Optional;

public final class ResolveCustomerAccessService implements ResolveCustomerAccess {

    private final CustomerAccessRepository repository;

    public ResolveCustomerAccessService(CustomerAccessRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Optional<CustomerAccess> resolve(ExternalIdentity identity) {
        return repository.findByExternalIdentity(Objects.requireNonNull(identity, "identity"));
    }

    @Override
    public Optional<CustomerAccess> resolve(CustomerId customerId) {
        return repository.findByCustomerId(Objects.requireNonNull(customerId, "customerId"));
    }
}
