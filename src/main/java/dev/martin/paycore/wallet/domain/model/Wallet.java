package dev.martin.paycore.wallet.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Wallet(
        WalletId id,
        UUID customerId,
        WalletCurrency currency,
        UUID availableAccountId,
        UUID reservedAccountId,
        WalletStatus status,
        WalletStatus preBlockStatus,
        Instant activatedAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public Wallet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(availableAccountId, "availableAccountId");
        Objects.requireNonNull(reservedAccountId, "reservedAccountId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (currency != WalletCurrency.USD) {
            throw new IllegalArgumentException("Wallet currency must be USD");
        }
        if (availableAccountId.equals(reservedAccountId)) {
            throw new IllegalArgumentException("Wallet accounts must be distinct");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Wallet update time cannot precede creation time");
        }
        if (version < 0) {
            throw new IllegalArgumentException("Wallet version cannot be negative");
        }
        if (status == WalletStatus.BLOCKED) {
            if (preBlockStatus != WalletStatus.UNFUNDED && preBlockStatus != WalletStatus.ACTIVE) {
                throw new IllegalArgumentException("Blocked wallets must retain an operational state");
            }
        } else if (preBlockStatus != null) {
            throw new IllegalArgumentException("Only blocked wallets can have a pre-block state");
        }
        if (status == WalletStatus.UNFUNDED && activatedAt != null) {
            throw new IllegalArgumentException("Unfunded wallets cannot have an activation time");
        }
        if (status == WalletStatus.ACTIVE && activatedAt == null) {
            throw new IllegalArgumentException("Active wallets require an activation time");
        }
    }

    public static Wallet unfunded(
            WalletId id, UUID customerId, UUID availableAccountId, UUID reservedAccountId, Instant createdAt) {
        return new Wallet(id, customerId, WalletCurrency.USD, availableAccountId, reservedAccountId,
                WalletStatus.UNFUNDED, null, null, createdAt, createdAt, 0);
    }

    public Wallet activate(Instant at) {
        Objects.requireNonNull(at, "at");
        if (status != WalletStatus.UNFUNDED) {
            throw new IllegalStateException("Only unfunded wallets can be activated");
        }
        return changed(WalletStatus.ACTIVE, null, at, at);
    }

    public Wallet block(Instant at) {
        Objects.requireNonNull(at, "at");
        if (status == WalletStatus.CLOSED) {
            throw new IllegalStateException("Closed wallets cannot be blocked");
        }
        if (status == WalletStatus.BLOCKED) {
            return this;
        }
        return changed(WalletStatus.BLOCKED, status, activatedAt, at);
    }

    public Wallet unblock(Instant at) {
        Objects.requireNonNull(at, "at");
        if (status != WalletStatus.BLOCKED) {
            throw new IllegalStateException("Only blocked wallets can be unblocked");
        }
        return changed(preBlockStatus, null, activatedAt, at);
    }

    public Wallet close(BigDecimal totalBalance, boolean hasActiveReservations, Instant at) {
        Objects.requireNonNull(totalBalance, "totalBalance");
        Objects.requireNonNull(at, "at");
        if (totalBalance.signum() < 0) {
            throw new IllegalArgumentException("Wallet balance cannot be negative");
        }
        if (status == WalletStatus.CLOSED) {
            throw new IllegalStateException("Wallet is already closed");
        }
        if (totalBalance.signum() != 0 || hasActiveReservations) {
            throw new IllegalStateException("Wallet must have zero balance and no active reservations");
        }
        return changed(WalletStatus.CLOSED, null, activatedAt, at);
    }

    private Wallet changed(WalletStatus nextStatus, WalletStatus nextPreBlockStatus,
            Instant nextActivatedAt, Instant at) {
        if (at.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Wallet update time cannot precede the previous update time");
        }
        return new Wallet(id, customerId, currency, availableAccountId, reservedAccountId,
                nextStatus, nextPreBlockStatus, nextActivatedAt, createdAt, at, version + 1);
    }
}
