package dev.martin.paycore.ledger.infrastructure.persistence;

import dev.martin.paycore.ledger.domain.model.CurrencyCode;
import dev.martin.paycore.ledger.domain.model.LedgerEntryDirection;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "ledger_transaction_lines")
class LedgerTransactionLineEntity {

    @EmbeddedId
    LedgerTransactionLineId id;

    @Column(name = "account_id", nullable = false)
    UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    LedgerEntryDirection direction;

    @Column(nullable = false, precision = 19, scale = 2)
    BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    CurrencyCode currency;

    protected LedgerTransactionLineEntity() {
    }
}
