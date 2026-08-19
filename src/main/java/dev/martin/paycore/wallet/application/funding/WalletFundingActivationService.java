package dev.martin.paycore.wallet.application.funding;

import dev.martin.paycore.wallet.application.port.out.WalletStore;
import dev.martin.paycore.wallet.domain.model.Wallet;
import dev.martin.paycore.wallet.domain.model.WalletStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class WalletFundingActivationService implements ActivateAfterConfirmedIncomingCredit {

    private final WalletStore wallets;

    public WalletFundingActivationService(WalletStore wallets) {
        this.wallets = Objects.requireNonNull(wallets, "wallets");
    }

    @Override
    public Wallet activateAfterConfirmedIncomingCredit(UUID customerId, Instant activatedAt) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(activatedAt, "activatedAt");
        return wallets.inTransaction(() -> wallets.lockAndFindByCustomerId(customerId)
                .map(wallet -> activate(wallet, activatedAt))
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found")));
    }

    private Wallet activate(Wallet wallet, Instant activatedAt) {
        if (wallet.status() == WalletStatus.ACTIVE) {
            return wallet;
        }
        if (wallet.status() != WalletStatus.UNFUNDED) {
            throw new IllegalStateException(
                    "Only an unfunded or active wallet can be activated after an incoming credit");
        }
        return wallets.save(wallet.activate(activatedAt));
    }
}
