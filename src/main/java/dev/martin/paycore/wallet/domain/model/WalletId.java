package dev.martin.paycore.wallet.domain.model;

import java.util.Objects;
import java.util.UUID;

public record WalletId(UUID value) {

    public WalletId {
        Objects.requireNonNull(value, "value");
    }

    public static WalletId newId() {
        return new WalletId(UUID.randomUUID());
    }
}
