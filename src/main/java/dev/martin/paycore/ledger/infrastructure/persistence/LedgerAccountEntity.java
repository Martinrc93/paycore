package dev.martin.paycore.ledger.infrastructure.persistence;

import dev.martin.paycore.ledger.domain.model.CurrencyCode;
import dev.martin.paycore.ledger.domain.model.LedgerBalancePolicy;
import dev.martin.paycore.ledger.domain.model.LedgerAccountStatus;
import dev.martin.paycore.ledger.domain.model.LedgerAccountType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_accounts")
class LedgerAccountEntity {

    @Id
    UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 16)
    LedgerAccountType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    LedgerAccountStatus status;

    @Column(nullable = false, length = 128)
    String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    CurrencyCode currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "balance_policy", nullable = false, length = 16)
    LedgerBalancePolicy balancePolicy;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected LedgerAccountEntity() {
    }
}
