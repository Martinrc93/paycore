package dev.martin.paycore.ledger.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_account_balances")
class LedgerBalanceEntity {

    @Id
    @Column(name = "account_id")
    UUID accountId;

    @Column(name = "cumulative_debits", nullable = false, precision = 19, scale = 2)
    BigDecimal cumulativeDebits;

    @Column(name = "cumulative_credits", nullable = false, precision = 19, scale = 2)
    BigDecimal cumulativeCredits;

    @Column(name = "consistency_status", nullable = false, length = 16)
    String consistencyStatus;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected LedgerBalanceEntity() {
    }
}
