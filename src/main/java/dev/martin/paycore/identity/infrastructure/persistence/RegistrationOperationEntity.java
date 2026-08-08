package dev.martin.paycore.identity.infrastructure.persistence;

import dev.martin.paycore.identity.application.registration.RegistrationOperationState;
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
@Table(name = "registration_operations")
class RegistrationOperationEntity {

    @Id
    UUID id;

    @Column(name = "key_reference", nullable = false, length = 80)
    String keyReference;

    @Column(name = "key_digest_version", nullable = false)
    int keyDigestVersion;

    @Column(name = "key_digest", nullable = false, length = 64)
    String keyDigest;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    String requestFingerprint;

    @Column(name = "customer_id")
    UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    RegistrationOperationState state;

    @Column(name = "external_subject", length = 255)
    String externalSubject;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    Instant expiresAt;

    @Column(name = "next_attempt_at")
    Instant nextAttemptAt;

    @Column(name = "claim_token")
    UUID claimToken;

    @Column(name = "lease_until")
    Instant leaseUntil;

    @Column(name = "fencing_version", nullable = false)
    long fencingVersion;

    @Column(name = "attempt_count", nullable = false)
    int attemptCount;

    @Column(name = "failure_code", length = 64)
    String failureCode;

    @Version
    long version;

    protected RegistrationOperationEntity() {
    }
}
