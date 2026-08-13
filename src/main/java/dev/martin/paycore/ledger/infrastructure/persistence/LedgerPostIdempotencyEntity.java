package dev.martin.paycore.ledger.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_posting_idempotency")
class LedgerPostIdempotencyEntity {

    @Id
    @Column(name = "idempotency_key", length = 128)
    String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    String requestFingerprint;

    @Column(name = "transaction_id", unique = true)
    UUID transactionId;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected LedgerPostIdempotencyEntity() {
    }
}
