package dev.martin.paycore.identity.domain.model;

import java.time.Instant;
import java.util.Objects;

public final class Customer {

    private final CustomerId id;
    private final Email email;
    private final CustomerType type;
    private final Instant createdAt;
    private CustomerStatus status;
    private Instant updatedAt;

    private Customer(CustomerId id, Email email, CustomerType type, CustomerStatus status,
            Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = Objects.requireNonNull(email, "email");
        this.type = Objects.requireNonNull(type, "type");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Customer register(CustomerId id, Email email, CustomerType type, Instant now) {
        return new Customer(id, email, type, CustomerStatus.PROVISIONING, now, now);
    }

    public static Customer rehydrate(CustomerId id, Email email, CustomerType type, CustomerStatus status,
            Instant createdAt, Instant updatedAt) {
        return new Customer(id, email, type, status, createdAt, updatedAt);
    }

    public void activate(Instant now) {
        transitionTo(CustomerStatus.ACTIVE, now);
    }

    public void failProvisioning(Instant now) {
        transitionTo(CustomerStatus.PROVISIONING_FAILED, now);
    }

    public void suspend(Instant now) {
        transitionActiveTo(CustomerStatus.SUSPENDED, now);
    }

    public void block(Instant now) {
        transitionActiveTo(CustomerStatus.BLOCKED, now);
    }

    private void transitionTo(CustomerStatus target, Instant now) {
        if (status != CustomerStatus.PROVISIONING) {
            throw new IllegalStateException("Customer registration is no longer provisioning");
        }
        status = target;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    private void transitionActiveTo(CustomerStatus target, Instant now) {
        if (status != CustomerStatus.ACTIVE) {
            throw new IllegalStateException("Customer is not active");
        }
        status = target;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public CustomerId id() {
        return id;
    }

    public Email email() {
        return email;
    }

    public CustomerType type() {
        return type;
    }

    public CustomerStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
