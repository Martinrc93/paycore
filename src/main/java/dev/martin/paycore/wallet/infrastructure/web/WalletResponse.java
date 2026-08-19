package dev.martin.paycore.wallet.infrastructure.web;

import dev.martin.paycore.wallet.application.query.WalletView;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record WalletResponse(
        UUID walletId,
        String status,
        String currency,
        BigDecimal available,
        BigDecimal reserved,
        BigDecimal total) {

    public static WalletResponse from(WalletView view) {
        Objects.requireNonNull(view, "view");
        return new WalletResponse(view.walletId().value(), view.status().name(), view.currency().name(),
                view.availableBalance(), view.reservedBalance(), view.totalBalance());
    }
}
