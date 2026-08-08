package dev.martin.paycore.identity.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "external_identities")
class ExternalIdentityEntity {

    @EmbeddedId
    ExternalIdentityKey id;

    @Column(name = "customer_id", nullable = false)
    UUID customerId;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected ExternalIdentityEntity() {
    }
}
