package dev.martin.paycore.ledger.domain.model;

import java.util.Locale;

public enum CurrencyCode {
    ARS(2),
    USD(2),
    EUR(2),
    JPY(0);

    private final int scale;

    CurrencyCode(int scale) {
        this.scale = scale;
    }

    public int scale() {
        return scale;
    }

    public static CurrencyCode of(String value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        try {
            return value.strip().toUpperCase(Locale.ROOT).transform(CurrencyCode::valueOf);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported currency", exception);
        }
    }
}
