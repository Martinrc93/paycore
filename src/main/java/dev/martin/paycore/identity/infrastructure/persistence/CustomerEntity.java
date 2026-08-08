package dev.martin.paycore.identity.infrastructure.persistence;

import dev.martin.paycore.identity.domain.model.CustomerStatus;
import dev.martin.paycore.identity.domain.model.CustomerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers")
class CustomerEntity {

    @Id
    UUID id;

    @Column(nullable = false, length = 254, unique = true)
    String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_type", nullable = false, length = 16)
    CustomerType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    CustomerStatus status;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @Version
    long version;

    protected CustomerEntity() {
    }
}
