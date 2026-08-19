package dev.martin.paycore.wallet.infrastructure.persistence;

import dev.martin.paycore.wallet.domain.model.WalletCurrency;
import dev.martin.paycore.wallet.domain.model.WalletStatus;
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
@Table(name = "wallets")
class WalletEntity {

    @Id
    UUID id;

    @Column(name = "customer_id", nullable = false, unique = true)
    UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    WalletCurrency currency;

    @Column(name = "available_account_id", nullable = false, unique = true)
    UUID availableAccountId;

    @Column(name = "reserved_account_id", nullable = false, unique = true)
    UUID reservedAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    WalletStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "pre_block_status", length = 16)
    WalletStatus preBlockStatus;

    @Column(name = "activated_at")
    Instant activatedAt;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @Version
    @Column(nullable = false)
    long version;

    protected WalletEntity() {
    }
}
