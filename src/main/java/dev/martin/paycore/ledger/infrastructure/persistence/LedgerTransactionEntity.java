package dev.martin.paycore.ledger.infrastructure.persistence;

import dev.martin.paycore.ledger.domain.model.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "ledger_transactions")
class LedgerTransactionEntity {

    @Id
    UUID id;

    @Column(name = "posted_at", nullable = false)
    Instant postedAt;

    @Column(name = "value_date", nullable = false)
    LocalDate valueDate;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    String idempotencyKey;

    @Column(name = "operation_reference", nullable = false, length = 128)
    String operationReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    CurrencyCode currency;

    @Column(name = "reversal_of")
    UUID reversalOf;

    @Column(name = "correction_of")
    UUID correctionOf;

    protected LedgerTransactionEntity() {
    }
}
