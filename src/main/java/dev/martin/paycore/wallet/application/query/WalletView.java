package dev.martin.paycore.wallet.application.query;

import dev.martin.paycore.wallet.domain.model.WalletCurrency;
import dev.martin.paycore.wallet.domain.model.WalletId;
import dev.martin.paycore.wallet.domain.model.WalletStatus;
import java.math.BigDecimal;
import java.util.Objects;

public record WalletView(
        WalletId walletId,
        WalletStatus status,
        WalletCurrency currency,
        BigDecimal availableBalance,
        BigDecimal reservedBalance,
        BigDecimal totalBalance) {

    public WalletView {
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(currency, "currency");
        requireNonNegative(availableBalance, "availableBalance");
        requireNonNegative(reservedBalance, "reservedBalance");
        requireNonNegative(totalBalance, "totalBalance");
        if (totalBalance.compareTo(availableBalance.add(reservedBalance)) != 0) {
            throw new IllegalArgumentException("Wallet total must equal available plus reserved");
        }
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
    }
}
