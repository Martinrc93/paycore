package dev.martin.paycore.identity.application.port.out;

import dev.martin.paycore.identity.domain.model.Customer;
import dev.martin.paycore.identity.domain.model.CustomerId;
import java.util.Optional;

public interface CustomerRepository {

    Optional<Customer> findById(CustomerId customerId);
}
