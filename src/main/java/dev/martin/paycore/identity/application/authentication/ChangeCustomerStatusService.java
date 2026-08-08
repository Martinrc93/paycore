package dev.martin.paycore.identity.application.authentication;

import dev.martin.paycore.identity.application.port.out.CustomerRepository;
import dev.martin.paycore.identity.application.port.out.SessionRevocationPort;
import dev.martin.paycore.identity.domain.model.Customer;
import dev.martin.paycore.identity.domain.model.CustomerId;
import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiConsumer;

public class ChangeCustomerStatusService {

    private final CustomerRepository customers;
    private final SessionRevocationPort sessions;
    private final Clock clock;

    public ChangeCustomerStatusService(CustomerRepository customers, SessionRevocationPort sessions, Clock clock) {
        this.customers = Objects.requireNonNull(customers, "customers");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void suspend(CustomerId customerId) {
        change(customerId, Customer::suspend);
    }

    public void block(CustomerId customerId) {
        change(customerId, Customer::block);
    }

    private void change(CustomerId customerId, BiConsumer<Customer, java.time.Instant> transition) {
        CustomerId requiredId = Objects.requireNonNull(customerId, "customerId");
        Customer customer = customers.findById(requiredId)
                .orElseThrow(() -> new NoSuchElementException("Customer not found"));
        transition.accept(customer, clock.instant());
        customers.save(customer);
        sessions.revokeAll(requiredId);
    }
}
