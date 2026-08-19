package dev.martin.paycore.wallet.application.provisioning;

import dev.martin.paycore.wallet.domain.model.WalletCurrency;
import java.util.Objects;
import java.util.UUID;

public record ProvisionWalletCommand(UUID customerId, WalletCurrency currency) {

    public ProvisionWalletCommand {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(currency, "currency");
    }
}
