package dev.martin.paycore.wallet.application.port.out;

import java.util.Objects;
import java.util.UUID;

public record WalletAccountProvisioning(
        UUID customerId,
        UUID availableAccountId,
        UUID reservedAccountId) {

    public WalletAccountProvisioning {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(availableAccountId, "availableAccountId");
        Objects.requireNonNull(reservedAccountId, "reservedAccountId");
        if (availableAccountId.equals(reservedAccountId)) {
            throw new IllegalArgumentException("Wallet accounts must be distinct");
        }
    }
}
