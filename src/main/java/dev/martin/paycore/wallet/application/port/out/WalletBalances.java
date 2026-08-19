package dev.martin.paycore.wallet.application.port.out;

import java.math.BigDecimal;
import java.util.Objects;

public record WalletBalances(BigDecimal available, BigDecimal reserved) {

    public WalletBalances {
        requireNonNegative(available, "available");
        requireNonNegative(reserved, "reserved");
    }

    public BigDecimal total() {
        return available.add(reserved);
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
    }
}
