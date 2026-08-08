package dev.martin.paycore.identity.infrastructure.persistence;

import dev.martin.paycore.identity.application.port.out.CustomerRepository;
import dev.martin.paycore.identity.domain.model.Customer;
import dev.martin.paycore.identity.domain.model.CustomerId;
import dev.martin.paycore.identity.domain.model.Email;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class CustomerPersistenceAdapter implements CustomerRepository {

    private final CustomerJpaRepository repository;

    public CustomerPersistenceAdapter(CustomerJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Customer> findById(CustomerId customerId) {
        return repository.findById(customerId.value()).map(CustomerPersistenceAdapter::toDomain);
    }

    @Override
    public void save(Customer customer) {
        CustomerEntity entity = repository.findById(customer.id().value())
                .orElseThrow(() -> new IllegalStateException("Customer disappeared during status change"));
        entity.status = customer.status();
        entity.updatedAt = customer.updatedAt();
        repository.save(entity);
    }

    private static Customer toDomain(CustomerEntity entity) {
        return Customer.rehydrate(new CustomerId(entity.id), Email.of(entity.email), entity.type,
                entity.status, entity.createdAt, entity.updatedAt);
    }
}
