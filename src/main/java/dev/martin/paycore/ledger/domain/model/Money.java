package dev.martin.paycore.ledger.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Money(BigDecimal amount, CurrencyCode currency) {

    private static final int PERSISTENCE_PRECISION = 19;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Money amount must be positive");
        }
        if (amount.scale() > currency.scale()) {
            throw new IllegalArgumentException("Money amount has too many fractional digits");
        }
        if (amount.precision() - amount.scale() > PERSISTENCE_PRECISION - currency.scale()) {
            throw new IllegalArgumentException("Money amount exceeds supported precision");
        }
        amount = amount.setScale(currency.scale(), RoundingMode.UNNECESSARY);
    }

    public static Money of(String amount, CurrencyCode currency) {
        Objects.requireNonNull(amount, "amount");
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money of(BigDecimal amount, CurrencyCode currency) {
        return new Money(amount, currency);
    }

    public Money add(Money other) {
        Objects.requireNonNull(other, "other");
        if (currency != other.currency) {
            throw new IllegalArgumentException("Cannot add different currencies");
        }
        return new Money(amount.add(other.amount), currency);
    }
}
